<#[
.SYNOPSIS
  运行权限分类器（auto 模式）判定质量诊断。

.DESCRIPTION
  驱动直接调用 PermissionClassifier.classify，也就是 auto 模式下 askOrClassify 调用的同一个
  公开契约；它测量的是提示词加模型这一对产出的判定质量，不执行任何工具、不需要 Docker。

  样本集是自建对抗清单加合法请求对照，按 docs/benchmark-evaluation.md 的约定只能作为定向诊断，
  不能写成公开 benchmark 成绩。真实运行会产生模型费用；-DryRun 只校验样本不调用模型。
#>
[CmdletBinding()]
param(
  [string]$Cases = (Join-Path (Get-Location) 'benchmarks/permission-classifier-evals/v1/cases.jsonl'),
  [string]$OutputDir,
  [string]$M2 = (Join-Path $HOME '.m2\repository'),
  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
$Cases = (Resolve-Path $Cases).Path
if (-not $OutputDir) {
  $OutputDir = Join-Path $root ('Test/permission-classifier-evals/runs/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
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
$cpFile = Join-Path $env:TEMP ('devcli-permission-classifier-cp-' + [Guid]::NewGuid().ToString('N') + '.txt')
try {
  cmd /c "mvn -B -q dependency:build-classpath -Dmdep.outputFile=`"$cpFile`" -Dmdep.includeScope=test"
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path $cpFile)) { throw '生成 classpath 失败' }
  $cp = "$root\target\classes;$root\target\test-classes;" + (Get-Content $cpFile -Raw).Trim()
} finally {
  if (Test-Path $cpFile) { Remove-Item -LiteralPath $cpFile -Force }
}

$commit = (cmd /c 'git rev-parse HEAD').Trim()
$dirty = 'false'
if ((cmd /c 'git status --porcelain').Trim().Length -gt 0) { $dirty = 'true' }
$driverArgs = @($Cases, $OutputDir)
if ($DryRun) { $driverArgs += '--dry-run' }
& $java "-Ddevcli.eval.commit=$commit" "-Ddevcli.eval.dirty=$dirty" -cp $cp com.devcli.eval.PermissionClassifierDriver @driverArgs
if ($LASTEXITCODE -ne 0) { throw "权限分类器诊断失败，退出码: $LASTEXITCODE" }
Write-Host "评测产物: $OutputDir" -ForegroundColor Green
