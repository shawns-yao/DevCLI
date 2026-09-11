<#[
.SYNOPSIS
  Run DevCLI terminal Agent Evals cases through the production AgentSessionRuntime entrypoint.

.DESCRIPTION
  The driver creates a fresh isolated workspace per case. Agent commands run through
  ToolRegistry.ISOLATED_PROJECT, which uses Docker by default. Use -DryRun to validate
  the dataset and write the evaluation card without calling a model or Docker.
#>
[CmdletBinding()]
param(
  [string]$Tasks = (Join-Path (Get-Location) 'Test/agent-evals/v1/tasks.jsonl'),
  [string]$OutputDir,
  [string]$M2 = (Join-Path $HOME '.m2\repository'),
  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
$Tasks = (Resolve-Path $Tasks).Path
if (-not $OutputDir) {
  $OutputDir = Join-Path $root ('Test/agent-evals/runs/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { throw "找不到 Java: $java" }
if (-not (Test-Path $M2)) { throw "Maven 仓库不存在: $M2" }
if (Test-Path $OutputDir) {
  if (Get-ChildItem -Force $OutputDir | Select-Object -First 1) {
    throw "输出目录必须是新目录或空目录: $OutputDir"
  }
} else {
  New-Item -ItemType Directory -Force $OutputDir | Out-Null
}

$env:MAVEN_OPTS = "-Dmaven.repo.local=$M2"
cmd /c 'mvn -B -q -DskipTests compile test-compile'
if ($LASTEXITCODE -ne 0) { throw 'DevCLI 编译失败' }
$cpFile = Join-Path $env:TEMP ('devcli-agent-evals-cp-' + [Guid]::NewGuid().ToString('N') + '.txt')
try {
  cmd /c "mvn -B -q dependency:build-classpath -Dmdep.outputFile=`"$cpFile`" -Dmdep.includeScope=test"
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path $cpFile)) { throw '生成 classpath 失败' }
  $cp = "$root\target\classes;$root\target\test-classes;" + (Get-Content $cpFile -Raw).Trim()
} finally {
  if (Test-Path $cpFile) { Remove-Item -LiteralPath $cpFile -Force }
}
$driverArgs = @($Tasks, $OutputDir)
if ($DryRun) { $driverArgs += '--dry-run' }
& $java -cp $cp com.devcli.eval.AgentEvalDriver @driverArgs
if ($LASTEXITCODE -ne 0) { throw "Agent Evals 失败，退出码: $LASTEXITCODE" }
Write-Host "评测产物: $OutputDir" -ForegroundColor Green
