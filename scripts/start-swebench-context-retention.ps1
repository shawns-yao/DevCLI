[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$InstanceId,
  [Parameter(Mandatory = $true)][string]$RunName,
  [ValidateRange(2, 64)][int]$ContinuationRounds = 20,
  [ValidateSet('auto', 'gpt-5.6-luna', 'gpt-5.6-terra')][string]$Model = 'auto'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outRoot = Join-Path $root "Test/public-benchmarks/context/$RunName"
$workRoot = Join-Path $root "Temp/$RunName"
$launcherRoot = Join-Path $root "Log/context-retention/$RunName"

if (Test-Path -LiteralPath $outRoot) {
  throw "Run directory already exists: $outRoot"
}

New-Item -ItemType Directory -Force -Path $launcherRoot | Out-Null
$stdout = Join-Path $launcherRoot 'stdout.log'
$stderr = Join-Path $launcherRoot 'stderr.log'
$metadata = Join-Path $launcherRoot 'process.json'
$runner = Join-Path $PSScriptRoot 'swe-bench-multilingual-java.ps1'
$pwsh = (Get-Process -Id $PID).Path

$arguments = @(
  '-NoProfile',
  '-File', $runner,
  '-TestType', 'public',
  '-Model', $Model,
  '-Mode', 'solo',
  '-ContextMode', 'compact',
  '-ConversationProtocol', 'original-task-qa',
  '-CompressionTriggerTokens', '64000',
  '-ModelContextWindowTokens', '128000',
  '-ContinuationRounds', [string]$ContinuationRounds,
  '-TokenBudget', '700000',
  '-MaxIterations', '100',
  '-InstanceIds', $InstanceId,
  '-OutRoot', "Test/public-benchmarks/context/$RunName",
  '-WorkRoot', "Temp/$RunName"
)

$process = Start-Process -FilePath $pwsh -ArgumentList $arguments `
  -WorkingDirectory $root -WindowStyle Hidden -PassThru `
  -RedirectStandardOutput $stdout -RedirectStandardError $stderr

[ordered]@{
  schema_version = 1
  process_id = $process.Id
  started_at = [DateTimeOffset]::Now.ToString('O')
  instance_id = $InstanceId
  requested_model = $Model
  continuation_rounds = $ContinuationRounds
  out_root = [IO.Path]::GetRelativePath($root, $outRoot).Replace('\', '/')
  work_root = [IO.Path]::GetRelativePath($root, $workRoot).Replace('\', '/')
  stdout = [IO.Path]::GetRelativePath($root, $stdout).Replace('\', '/')
  stderr = [IO.Path]::GetRelativePath($root, $stderr).Replace('\', '/')
} | ConvertTo-Json | Set-Content -LiteralPath $metadata -Encoding utf8

Write-Output "pid=$($process.Id)"
Write-Output "metadata=$metadata"
