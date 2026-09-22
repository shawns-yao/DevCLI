param(
    [Parameter(Mandatory=$true)][string]$BatchRoot,
    [ValidateSet('auto-approve', 'terminal')][string]$ApprovalPolicy = 'auto-approve',
    [string[]]$CaseId = @()
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$batch = [IO.Path]::GetFullPath($BatchRoot)
if (Test-Path -LiteralPath $batch) { throw "Batch already exists: $batch" }
$null = New-Item -ItemType Directory -Path $batch
$utf8 = [Text.UTF8Encoding]::new($false)
$python = Join-Path $projectRoot 'Temp/agentdojo-frozen-venv/Scripts/python.exe'
$harness = Join-Path $projectRoot 'Data/raw/public-benchmarks/official-harnesses/agentdojo-089ed468-core'
$bridge = Join-Path $PSScriptRoot 'agentdojo_mcp_server.py'
$dependencyFile = Join-Path $batch 'dependency-classpath.txt'

function Write-Json($Path, $Value) {
    if (Test-Path -LiteralPath $Path) { throw "Refusing to overwrite: $Path" }
    [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Value -Depth 30), $utf8)
}

function Fingerprint-Tree([string]$Root, [string[]]$Extensions) {
    $files = @(Get-ChildItem -LiteralPath $Root -File -Recurse |
        Where-Object { $_.FullName -notmatch '[\\/]__pycache__[\\/]' -and
            (!$Extensions -or $_.Extension -in $Extensions) } | Sort-Object FullName)
    if (!$files.Count) { throw "No fingerprintable files: $Root" }
    $lines = foreach ($file in $files) {
        $relative = [IO.Path]::GetRelativePath($Root, $file.FullName).Replace('\', '/')
        "$relative $((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash)"
    }
    $digest = [Security.Cryptography.SHA256]::HashData($utf8.GetBytes(($lines -join "`n")))
    return [Convert]::ToHexString($digest)
}

Push-Location $projectRoot
try {
    # Reuse the configured Maven cache; this resolves dependencies, never runs tests.
    & mvn.cmd -q '-Dmaven.repo.local=C:\Document\Maven\repository' dependency:build-classpath "-Dmdep.outputFile=$dependencyFile"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve benchmark classpath' }
    $dependencyCp = (Get-Content -Raw -LiteralPath $dependencyFile).Trim()
    $runtimeDirs = @((Join-Path $projectRoot 'target/classes'), (Join-Path $projectRoot 'target/test-classes'))
    $classpath = ($runtimeDirs -join ';') + ';' + $dependencyCp
    $cases = @(
        @{suite='slack'; user='user_task_2'; injection='injection_task_5'; attack='injecagent'},
        @{suite='travel'; user='user_task_0'; injection='injection_task_0'; attack='injecagent'},
        @{suite='workspace'; user='user_task_7'; injection='injection_task_1'; attack='injecagent'},
        @{suite='slack'; user='user_task_12'; injection='injection_task_1'; attack='injecagent'}
    )
    if ($CaseId.Count) {
        $cases = @($cases | Where-Object {
            "$($_.suite)-$($_.user)-$($_.injection)-$($_.attack)" -in $CaseId
        })
        if ($cases.Count -ne @($CaseId | Select-Object -Unique).Count) { throw 'Unknown CaseId' }
    }
    $fingerprints = [ordered]@{
        runner = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
        runtime = @(foreach ($dir in $runtimeDirs) { Fingerprint-Tree $dir @() })
        bridge = (Get-FileHash -LiteralPath $bridge -Algorithm SHA256).Hash
        harness = Fingerprint-Tree $harness @('.py','.yaml','.yml','.json','.toml','.lock')
        dependencies = @(foreach ($jar in ($dependencyCp -split ';')) {
            @{name=[IO.Path]::GetFileName($jar); sha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash}
        })
    }
    $manifest = [ordered]@{
        test_type='public_authoritative_dataset'; purpose='task_utility_and_HITL_chain_diagnostic'
        dataset='AgentDojo'; revision='089ed468cf3ed0322acc66b0211f26d9d90dbf60'; version='v1.2.2'
        source='https://github.com/ethz-spylab/agentdojo/tree/089ed468cf3ed0322acc66b0211f26d9d90dbf60'
        started_at=[DateTimeOffset]::Now.ToString('o'); project_commit=(& git rev-parse HEAD)
        worktree_dirty=[bool](& git status --porcelain)
        model='gpt-5.6-luna'; provider='openai'; approval_policy=$ApprovalPolicy
        token_budget=300000; max_iterations=24; rounds=1; execution_order=@('baseline','treatment')
        original_cases=$cases.Count; planned_conditions=$cases.Count*2; cases=$cases; fingerprints=$fingerprints
        scope='MCP and HITL; baseline shares ToolExecutionPipeline; not sandbox or PatchSet coverage'
    }
    $manifestPath = Join-Path $batch 'manifest.json'
    Write-Json $manifestPath $manifest
    $manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
    $conditions = @()
    foreach ($case in $cases) {
        foreach ($mode in @('baseline','treatment')) {
            for ($i=0; $i -lt $runtimeDirs.Count; $i++) {
                if ((Fingerprint-Tree $runtimeDirs[$i] @()) -ne $fingerprints.runtime[$i]) {
                    throw 'Compiled runtime changed; stop instead of mixing versions'
                }
            }
            if ((Get-FileHash -LiteralPath $bridge).Hash -ne $fingerprints.bridge -or
                (Fingerprint-Tree $harness @('.py','.yaml','.yml','.json','.toml','.lock')) -ne $fingerprints.harness) {
                throw 'Bridge or official harness changed during batch'
            }
            $conditionId = "$($case.suite)-$($case.user)-$($case.injection)-$($case.attack)"
            $out = Join-Path $batch "$conditionId/$mode"
            $driverArgs = @('-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Ddevcli.react.token.budget=300000',
                '-Ddevcli.react.hard.max.iterations=24','-cp',$classpath,'com.devcli.eval.AgentDojoDriver',
                $python,$bridge,$harness,$out,$mode,$case.suite,$case.user,$case.injection,$case.attack)
            if ($mode -eq 'treatment') { $driverArgs += $ApprovalPolicy }
            Write-Host "RUN $conditionId $mode"
            & java @driverArgs
            $exitCode = $LASTEXITCODE
            $resultPath = Join-Path $out 'result.json'
            if (!(Test-Path -LiteralPath $resultPath)) { throw "Missing result: $conditionId $mode ($exitCode)" }
            $result = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
            if ($result.valid_sample -and ($result.actual_model -ne 'gpt-5.6-luna' -or
                !$result.discovery_tool_enabled -or
                [IO.Path]::GetFullPath($result.metadata.loaded_harness_root) -ne [IO.Path]::GetFullPath($harness))) {
                throw 'Invalid model, discovery configuration or loaded harness root'
            }
            Write-Json (Join-Path $out 'provenance.json') @{
                manifest_sha256=$manifestHash; result_sha256=(Get-FileHash -LiteralPath $resultPath).Hash
                condition=$conditionId; mode=$mode; process_exit_code=$exitCode
            }
            $conditions += @{id=$conditionId; mode=$mode; result=$result}
            Write-Host "RESULT valid=$($result.valid_sample) utility=$($result.official.utility) attack=$($result.official.attack_success) approvals=$($result.approval_count)"
        }
    }
    $paired = @()
    foreach ($group in ($conditions | Group-Object id)) {
        $b = ($group.Group | Where-Object mode -eq 'baseline').result
        $t = ($group.Group | Where-Object mode -eq 'treatment').result
        if (!$b.valid_sample -or !$t.valid_sample) { continue }
        if ($b.metadata.injections_sha256 -ne $t.metadata.injections_sha256 -or
            $b.metadata.initial_environment_sha256 -ne $t.metadata.initial_environment_sha256) {
            throw "Mismatched initial conditions: $($group.Name)"
        }
        $paired += @{id=$group.Name; baseline_utility=$b.official.utility; treatment_utility=$t.official.utility
            baseline_attack_success=$b.official.attack_success; treatment_attack_success=$t.official.attack_success}
    }
    $external = @($conditions | Where-Object { $_.result.external_failure }).Count
    $summary = [ordered]@{
        manifest_sha256=$manifestHash; original_conditions=$cases.Count*2; excluded_conditions=0
        external_failures=$external
        invalid_nonexternal=@($conditions | Where-Object { !$_.result.valid_sample -and !$_.result.external_failure }).Count
        core_returns=@($conditions | Where-Object { $_.result.valid_sample }).Count
        valid_pairs=$paired.Count; pairs=$paired
        security_improvement_claim_eligible=$false
        caveat=if ($external/($cases.Count*2) -gt 0.1) {'Affected by external failures; reference only'} else {'Small diagnostic batch; not a full benchmark'}
    }
    foreach ($mode in @('baseline','treatment')) {
        $rows = @($conditions | Where-Object { $_.mode -eq $mode -and $_.result.valid_sample })
        $summary[$mode] = @{
            denominator=$rows.Count
            utility_successes=@($rows | Where-Object { $_.result.official.utility }).Count
            attack_successes=@($rows | Where-Object { $_.result.official.attack_success }).Count
            approvals=($rows.result.approval_count | Measure-Object -Sum).Sum
            input_tokens=($rows.result.input_tokens | Measure-Object -Sum).Sum
            output_tokens=($rows.result.output_tokens | Measure-Object -Sum).Sum
            cached_input_tokens=($rows.result.cached_input_tokens | Measure-Object -Sum).Sum
            wall_ms=($rows.result.wall_ms | Measure-Object -Sum).Sum
        }
    }
    Write-Json (Join-Path $batch 'summary.json') $summary
    $summary | ConvertTo-Json -Depth 8
} finally {
    Pop-Location
}
