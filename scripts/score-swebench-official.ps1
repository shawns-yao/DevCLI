[CmdletBinding()]
param(
    [string]$RunRoot = 'Test/swebench-multilingual-java/gpt-5.6-luna-single-round',
    [ValidateSet('solo','delegate')][string[]]$Mode = @('solo','delegate'),
    [ValidateSet('raw','compact')][string[]]$ContextMode = @('raw','compact'),
    [string[]]$InstanceIds = @(),
    [ValidateSet('auto','gpt-5.6-luna','gpt-5.6-terra')][string]$Model = 'auto',
    [string]$HarnessImage = 'devcli/swebench-harness:f7bbbb2',
    [string]$StatusOutput = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$run = (Resolve-Path (Join-Path $root $RunRoot)).Path
$statusPath = if ($StatusOutput) { Join-Path $root $StatusOutput } else { Join-Path $run 'official-status.json' }
$statusDir = Split-Path -Parent $statusPath
New-Item -ItemType Directory -Force -Path $statusDir | Out-Null
function Write-OfficialStatus([string]$status, [string]$reason = '', [int]$exitCode = 0) {
    [ordered]@{
        schema_version = 1
        status = $status
        reason = $reason
        harness_image = $HarnessImage
        run_root = [IO.Path]::GetRelativePath($root, $run).Replace('\', '/')
        updated_at = [DateTimeOffset]::Now.ToString('O')
        exit_code = $exitCode
    } | ConvertTo-Json | Set-Content -LiteralPath $statusPath -Encoding utf8NoBOM
}
$dataset = Join-Path $run 'java-43-official-dataset.json'
if (-not (Test-Path -LiteralPath $dataset)) { throw 'Missing frozen official dataset' }
docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-OfficialStatus 'blocked_external' 'docker_daemon_unavailable' 125
    throw 'Official harness blocked: Docker daemon unavailable'
}
docker image inspect $HarnessImage --format '{{.Id}}' 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-OfficialStatus 'blocked_external' 'harness_image_unavailable' 126
    throw 'Official harness blocked: harness image unavailable'
}
Write-OfficialStatus 'running'
try {
    $cases = @(Get-Content -Raw -LiteralPath (Join-Path $run 'java-43-manifest.json') | ConvertFrom-Json)
    if ($InstanceIds.Count -gt 0) {
        $unknown = @($InstanceIds | Where-Object { $_ -notin $cases.instance_id })
        if ($unknown.Count -gt 0) { throw "Unknown official instance IDs: $unknown" }
        $cases = @($cases | Where-Object { $_.instance_id -in $InstanceIds })
    }
    $grading = Join-Path $run 'official-batch'
    New-Item -ItemType Directory -Force -Path $grading | Out-Null
    foreach ($current in $Mode) {
      foreach ($currentContextMode in $ContextMode) {
        $condition = "$current-$currentContextMode"
        $predictions = @()
        $observedModels = @()
        $excluded = 0
        $missing = 0
        foreach ($case in $cases) {
            $directory = Join-Path $run "$($case.instance_id)/$condition"
            $resultFile = Join-Path $directory 'result.json'
            if (-not (Test-Path -LiteralPath $resultFile)) { $missing++; continue }
            $result = Get-Content -Raw -LiteralPath $resultFile | ConvertFrom-Json
            if ($result.schema_version -ne 2) { throw "Missing validated provenance: $($case.instance_id) $condition" }
            if (-not $result.valid_sample -or $result.external_failure) {
                $excluded++
                Write-Output "[exclude] $($case.instance_id) $condition class=$($result.failure_class)"
                continue
            }
            if ($result.actual_model -notin @('gpt-5.6-luna', 'gpt-5.6-terra') -or
                ($Model -ne 'auto' -and $result.actual_model -ne $Model) -or
                $result.base_commit -ne $case.base_commit `
                -or $result.mode -ne $current -or $result.context_mode -ne $currentContextMode) {
                throw "Provenance mismatch: $($case.instance_id) $condition"
            }
            $prediction = Get-Content -Raw -LiteralPath (Join-Path $directory 'prediction.jsonl') | ConvertFrom-Json
            if ($prediction.instance_id -ne $case.instance_id -or
                $prediction.model_name_or_path -ne "devcli-$condition-$($result.actual_model)") {
                throw "Prediction model/id mismatch: $($case.instance_id)"
            }
            $observedModels += $result.actual_model
            $predictions += ($prediction | ConvertTo-Json -Compress)
        }
        $uniqueModels = @($observedModels | Sort-Object -Unique)
        if ($uniqueModels.Count -gt 1) {
            throw "Mixed actual models in one condition: $condition ($($uniqueModels -join ', '))"
        }
        $conditionModel = if ($uniqueModels.Count -eq 1) { $uniqueModels[0] } else { $Model }
        Write-Output "[denominator] $condition original=$($cases.Count) excluded=$excluded missing=$missing valid=$($predictions.Count)"
        if ($predictions.Count -eq 0) { continue }
        $predictions | Set-Content -LiteralPath "$grading/$condition-predictions.jsonl" -Encoding utf8NoBOM
        # The model workspace is never mounted; only evaluation inputs and reports are visible here.
        docker run --rm -v /var/run/docker.sock:/var/run/docker.sock `
            -v "${run}:/evaluation" -w /evaluation/official-batch $HarnessImage `
            -d /evaluation/java-43-official-dataset.json -p "/evaluation/official-batch/$condition-predictions.jsonl" `
            --max_workers 1 --cache_level instance --clean false -id "paired-$condition-$conditionModel"
        if ($LASTEXITCODE -ne 0) { throw "Official harness failed: $condition" }
        Write-Output "Official reports: $grading (submitted $($predictions.Count)/$($cases.Count) cases)"
      }
    }
    Write-OfficialStatus 'completed'
} catch {
    $message = $_.Exception.Message
    $status = if ($message -match '(?i)docker|daemon|npipe|container') { 'blocked_external' } else { 'failed' }
    Write-OfficialStatus $status $message 1
    throw
}
