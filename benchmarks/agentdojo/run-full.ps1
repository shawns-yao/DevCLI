param(
    [Parameter(Mandatory = $true)][string]$BatchRoot,
    [ValidateSet('auto', 'shuai-luna', 'cpa-luna', 'shuai-terra')][string]$ProviderProfile = 'auto',
    [ValidateSet('auto-approve', 'terminal')][string]$ApprovalPolicy = 'auto-approve',
    [string[]]$AttackTypes = @('injecagent'),
    [switch]$Resume,
    [int]$MaxAttackCases = 0,
    [int]$UtilityLimit = 0,
    [switch]$SkipAttack,
    [switch]$SkipUtility
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$batch = [IO.Path]::GetFullPath($BatchRoot)
$utf8 = [Text.UTF8Encoding]::new($false)
$python = Join-Path $projectRoot 'Temp/agentdojo-frozen-venv/Scripts/python.exe'
$harness = Join-Path $projectRoot 'Data/raw/public-benchmarks/official-harnesses/agentdojo-089ed468-core'
$bridge = Join-Path $PSScriptRoot 'agentdojo_mcp_server.py'
$dependencyFile = Join-Path $batch 'dependency-classpath.txt'

function Get-ConfigValue([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ($value -and !$value.Trim().StartsWith('your-')) { return $value.Trim() }
    $envFile = Join-Path $projectRoot '.env'
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in Get-Content -LiteralPath $envFile) {
            if ($line -match "^\s*$([regex]::Escape($Name))=(.*)$") {
                $candidate = $Matches[1].Trim()
                if ($candidate -and !$candidate.StartsWith('your-')) { return $candidate }
            }
        }
    }
    return $null
}

function Select-ProviderProfile([string]$Requested) {
    $profiles = if ($Requested -eq 'auto') { @('shuai-luna', 'cpa-luna', 'shuai-terra') } else { @($Requested) }
    foreach ($profile in $profiles) {
        $suffix = switch ($profile) {
            'cpa-luna' { '_cpa' }
            default { '' }
        }
        $model = if ($profile -like '*terra') { 'gpt-5.6-terra' } else { 'gpt-5.6-luna' }
        $key = Get-ConfigValue "OPENAI_API_KEY$suffix"
        $base = Get-ConfigValue "OPENAI_BASE_URL$suffix"
        if ($key -and $base) {
            return [pscustomobject]@{ name = $profile; model = $model; key = $key; base = $base }
        }
    }
    throw "No usable OpenAI-compatible profile found (priority: shuai-luna, cpa-luna, shuai-terra)"
}

$selectedProfile = Select-ProviderProfile $ProviderProfile

if (!(Test-Path -LiteralPath $python)) { throw "Missing frozen Python: $python" }
if (!(Test-Path -LiteralPath $harness)) { throw "Missing frozen AgentDojo harness: $harness" }
if ($Resume) {
    if (!(Test-Path -LiteralPath $batch)) { throw "Cannot resume missing batch: $batch" }
} elseif (Test-Path -LiteralPath $batch) {
    throw "Batch already exists; use -Resume to continue: $batch"
} else {
    $null = New-Item -ItemType Directory -Path $batch
}

function Write-Json([string]$Path, $Value) {
    if (Test-Path -LiteralPath $Path) { throw "Refusing to overwrite: $Path" }
    [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Value -Depth 50), $utf8)
}

function Get-Sha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '') }
    finally { $sha.Dispose(); $stream.Dispose() }
}

function Read-JsonStable([string]$Path) {
    $lastError = $null
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try {
            $raw = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
            return $raw | ConvertFrom-Json
        } catch {
            $lastError = $_
            Start-Sleep -Milliseconds 100
        }
    }
    throw "Cannot parse stable JSON: $Path`n$lastError"
}

function Fingerprint-Tree([string]$Root, [string[]]$Extensions) {
    $files = @(Get-ChildItem -LiteralPath $Root -File -Recurse |
        Where-Object { $_.FullName -notmatch '[\\/]__pycache__[\\/]' -and
            (!$Extensions -or $_.Extension -in $Extensions) } | Sort-Object FullName)
    if (!$files.Count) { throw "No fingerprintable files: $Root" }
    $rootFull = ([IO.Path]::GetFullPath($Root)).TrimEnd([char[]]@([char]92, [char]47)) + [char]92
    $lines = foreach ($file in $files) {
        $relative = $file.FullName.Substring($rootFull.Length).Replace('\', '/')
        "$relative $(Get-Sha256 $file.FullName)"
    }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $digest = $sha.ComputeHash($utf8.GetBytes(($lines -join "`n"))) } finally { $sha.Dispose() }
    return ([BitConverter]::ToString($digest)).Replace('-', '')
}

function Get-CaseManifest {
    $code = @'
import json
import agentdojo.attacks
from agentdojo.attacks.attack_registry import ATTACKS
from agentdojo.task_suite.load_suites import get_suite

suites = ["workspace", "slack", "travel", "banking"]
attack_names = __ATTACKS__
utility = []
attack_cases = []
for suite_name in suites:
    suite = get_suite("v1.2.2", suite_name)
    for user_id in sorted(suite.user_tasks):
        utility.append({"suite": suite_name, "user": user_id})
    for attack_name in attack_names:
        attack_type = ATTACKS.get(attack_name)
        if attack_type is None:
            raise ValueError(f"Unknown official AgentDojo attack: {attack_name}")
        attack = attack_type(suite, None)
        for user_id in sorted(suite.user_tasks):
            user = suite.user_tasks[user_id]
            for injection_id in sorted(suite.injection_tasks):
                injection = suite.injection_tasks[injection_id]
                generated = attack.attack(user, injection)
                if generated:
                    attack_cases.append({
                        "suite": suite_name,
                        "user": user_id,
                        "injection": injection_id,
                        "attack": attack_name,
                        "injection_vectors": sorted(generated),
                    })
print(json.dumps({"utility": utility, "attack": attack_cases}, ensure_ascii=False))
'@
    $encoded = ConvertTo-Json -InputObject ([array]$AttackTypes) -Compress
    $code = $code.Replace('__ATTACKS__', $encoded)
    $env:PYTHONPATH = Join-Path $harness 'src'
    $output = $code | & $python -
    if ($LASTEXITCODE -ne 0) { throw 'Official AgentDojo case enumeration failed' }
    return $output | ConvertFrom-Json
}

Push-Location $projectRoot
try {
    if (!(Test-Path -LiteralPath $dependencyFile)) {
        & mvn.cmd -q '-Dmaven.repo.local=C:\Document\Maven\repository' dependency:build-classpath "-Dmdep.outputFile=$dependencyFile"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve benchmark classpath' }
    }
    $dependencyCp = (Get-Content -Raw -LiteralPath $dependencyFile).Trim()
    $runtimeDirs = @((Join-Path $projectRoot 'target/classes'), (Join-Path $projectRoot 'target/test-classes'))
    if ($runtimeDirs | Where-Object { !(Test-Path -LiteralPath $_) }) {
        & mvn.cmd -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Cannot compile DevCLI benchmark runtime' }
    }
    $classpath = ($runtimeDirs -join ';') + ';' + $dependencyCp

    $enumerated = Get-CaseManifest
    $utilityCases = @($enumerated.utility)
    $attackCases = @($enumerated.attack)
    if ($SkipUtility) { $utilityCases = @() }
    if ($UtilityLimit -gt 0) { $utilityCases = @($utilityCases | Select-Object -First $UtilityLimit) }
    if ($SkipAttack) { $attackCases = @() }
    if ($MaxAttackCases -gt 0) { $attackCases = @($attackCases | Select-Object -First $MaxAttackCases) }
    if (!$SkipUtility -and $UtilityLimit -le 0 -and $utilityCases.Count -ne 97) { throw "Unexpected official utility task count: $($utilityCases.Count)" }
    if (!$SkipAttack -and !$attackCases.Count) { throw 'Official attack manifest is empty' }

    $fingerprints = [ordered]@{
        runner = Get-Sha256 $PSCommandPath
        runtime = @(foreach ($dir in $runtimeDirs) { Fingerprint-Tree $dir @() })
        bridge = Get-Sha256 $bridge
        harness = Fingerprint-Tree $harness @('.py', '.yaml', '.yml', '.json', '.toml', '.lock')
        model = $selectedProfile.model
        provider_profile = $selectedProfile.name
        attack_types = $AttackTypes
    }
    $enumeration = [ordered]@{
        test_type = 'public_authoritative_dataset'
        benchmark = 'AgentDojo'
        version = 'v1.2.2'
        revision = '089ed468cf3ed0322acc66b0211f26d9d90dbf60'
        source = 'https://github.com/ethz-spylab/agentdojo/tree/089ed468cf3ed0322acc66b0211f26d9d90dbf60'
        model = $selectedProfile.model
        provider_profile = $selectedProfile.name
        utility_case_count = $utilityCases.Count
        attack_case_count = $attackCases.Count
        attack_case_count_before_limit = @($enumerated.attack).Count
        attack_types = $AttackTypes
        utility_cases = $utilityCases
        attack_cases = $attackCases
        fingerprints = $fingerprints
        generated_at = [DateTimeOffset]::Now.ToString('o')
        project_commit = (& git rev-parse HEAD)
    }
    $utilityManifestPath = Join-Path $batch 'utility-manifest.json'
    $attackManifestPath = Join-Path $batch 'attack-manifest.json'
    if (!$Resume) {
        Write-Json $utilityManifestPath $enumeration.utility_cases
        Write-Json $attackManifestPath $enumeration.attack_cases
        Write-Json (Join-Path $batch 'manifest.json') $enumeration
    } elseif (!(Test-Path -LiteralPath $utilityManifestPath) -or !(Test-Path -LiteralPath $attackManifestPath)) {
        throw 'Resume batch is missing utility-manifest.json or attack-manifest.json'
    }
    $manifestHash = Get-Sha256 (Join-Path $batch 'manifest.json')

    $previousModel = $env:OPENAI_MODEL
    $previousKey = $env:OPENAI_API_KEY
    $previousBase = $env:OPENAI_BASE_URL
    $env:OPENAI_MODEL = $selectedProfile.model
    $env:OPENAI_API_KEY = $selectedProfile.key
    $env:OPENAI_BASE_URL = $selectedProfile.base
    $conditions = [System.Collections.Generic.List[object]]::new()
    $allCases = @(
        $utilityCases | ForEach-Object { [pscustomobject]@{ kind = 'utility'; suite = $_.suite; user = $_.user; injection = 'none'; attack = 'none' } }
        $attackCases | ForEach-Object { [pscustomobject]@{ kind = 'attack'; suite = $_.suite; user = $_.user; injection = $_.injection; attack = $_.attack } }
    )
    foreach ($case in $allCases) {
        $conditionId = "$($case.kind)-$($case.suite)-$($case.user)-$($case.injection)-$($case.attack)"
        foreach ($mode in @('baseline', 'treatment')) {
            $out = Join-Path $batch "$conditionId/$mode"
            $resultPath = Join-Path $out 'result.json'
            if ($Resume -and (Test-Path -LiteralPath $resultPath)) {
                $result = Read-JsonStable $resultPath
                if ($result.valid_sample -eq $true -and $result.external_failure -ne $true) {
                    $conditions.Add([pscustomobject]@{ id = $conditionId; kind = $case.kind; mode = $mode; result = $result })
                    Write-Host "RESUME $conditionId $mode valid=true"
                    continue
                }
                $archive = "$out.invalid-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff'))"
                Move-Item -LiteralPath $out -Destination $archive
                Write-Host "RETRY $conditionId $mode archived=$archive valid=$($result.valid_sample) external=$($result.external_failure)"
            }
            if (Test-Path -LiteralPath $out) {
                if (!$Resume) { throw "Refusing to reuse incomplete condition: $out" }
                $archive = "$out.incomplete-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff'))"
                Move-Item -LiteralPath $out -Destination $archive
                Write-Host "RETRY $conditionId $mode archived=$archive reason=incomplete"
            }
            $driverArgs = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                '-Ddevcli.react.token.budget=300000', '-Ddevcli.react.hard.max.iterations=24', '-cp', $classpath,
                'com.devcli.eval.AgentDojoDriver', $python, $bridge, $harness, $out, $mode,
                $case.suite, $case.user, $case.injection, $case.attack)
            if ($mode -eq 'treatment') { $driverArgs += $ApprovalPolicy }
            Write-Host "RUN $conditionId $mode profile=$($selectedProfile.name) model=$($selectedProfile.model)"
            & java @driverArgs
            $exitCode = $LASTEXITCODE
            if (!(Test-Path -LiteralPath $resultPath)) { throw "Missing result: $conditionId $mode ($exitCode)" }
            $result = Read-JsonStable $resultPath
            Write-Json (Join-Path $out 'provenance.json') @{
                manifest_sha256 = $manifestHash
                result_sha256 = Get-Sha256 $resultPath
                condition = $conditionId
                mode = $mode
                model = $selectedProfile.model
                provider_profile = $selectedProfile.name
                process_exit_code = $exitCode
            }
            $conditions.Add([pscustomobject]@{ id = $conditionId; kind = $case.kind; mode = $mode; result = $result })
            Write-Host "RESULT $conditionId $mode valid=$($result.valid_sample) utility=$($result.official.utility) attack=$($result.official.attack_success)"
        }
    }
    $valid = @($conditions | Where-Object { $_.result.valid_sample })
    $external = @($conditions | Where-Object { $_.result.external_failure }).Count
    $utilityRows = @($valid | Where-Object kind -eq 'utility')
    $attackRows = @($valid | Where-Object kind -eq 'attack')
    $summary = [ordered]@{
        benchmark = 'AgentDojo'
        version = 'v1.2.2'
        revision = '089ed468cf3ed0322acc66b0211f26d9d90dbf60'
        model = $selectedProfile.model
        provider_profile = $selectedProfile.name
        utility_planned_cases = $utilityCases.Count
        attack_planned_cases = $attackCases.Count
        planned_conditions = $allCases.Count * 2
        external_failures = $external
        valid_conditions = $valid.Count
        external_failure_rate = if ($allCases.Count * 2) { $external / ($allCases.Count * 2) } else { 0 }
        utility = @{}
        attack = @{}
        security_improvement_claim_eligible = $false
        caveat = 'Utility and attack cases are separate; baseline currently shares production execution components and is diagnostic only'
    }
    foreach ($mode in @('baseline', 'treatment')) {
        $u = @($utilityRows | Where-Object mode -eq $mode)
        $a = @($attackRows | Where-Object mode -eq $mode)
        $summary.utility[$mode] = @{ denominator = $u.Count; successes = @($u | Where-Object { $_.result.official.utility }).Count }
        $summary.attack[$mode] = @{ denominator = $a.Count; attack_successes = @($a | Where-Object { $_.result.official.attack_success }).Count }
    }
    $summary.utility.utility_rate_difference_pp =
        (100 * $summary.utility.treatment.successes / [Math]::Max(1, $summary.utility.treatment.denominator)) -
        (100 * $summary.utility.baseline.successes / [Math]::Max(1, $summary.utility.baseline.denominator))
    $summary.attack.baseline_asr = $summary.attack.baseline.attack_successes / [Math]::Max(1, $summary.attack.baseline.denominator)
    $summary.attack.treatment_asr = $summary.attack.treatment.attack_successes / [Math]::Max(1, $summary.attack.treatment.denominator)
    $summary.attack.asr_reduction_pp = 100 * ($summary.attack.baseline_asr - $summary.attack.treatment_asr)
    [IO.File]::WriteAllText((Join-Path $batch 'summary.json'), (ConvertTo-Json $summary -Depth 20), $utf8)
    $summary | ConvertTo-Json -Depth 20
} finally {
    if ($null -ne $previousModel) { $env:OPENAI_MODEL = $previousModel } else { Remove-Item Env:OPENAI_MODEL -ErrorAction SilentlyContinue }
    if ($null -ne $previousKey) { $env:OPENAI_API_KEY = $previousKey } else { Remove-Item Env:OPENAI_API_KEY -ErrorAction SilentlyContinue }
    if ($null -ne $previousBase) { $env:OPENAI_BASE_URL = $previousBase } else { Remove-Item Env:OPENAI_BASE_URL -ErrorAction SilentlyContinue }
    Pop-Location
}
