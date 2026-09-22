<#
.SYNOPSIS
  SWE-bench 无头三模式对照一键流水线（DevCLI）。
  固化踩坑经验：预检 -> 编译 DevCLI -> 从目标仓库开独立工作副本跑 solo/delegate/plan ->
  生成 LF patch -> 官方 Docker harness 评分 -> 结果落盘 results.jsonl。

.DESCRIPTION
  两个仓库不要混淆：
    -DevCliHome  被测系统 DevCLI 本身：编译、生成 classpath、运行 SweBenchDriver（默认当前目录）
    -TargetRepo  被 Agent 修改的目标仓库（如 javaparser 的干净 clone）：从此 clone 出每个模式的独立工作副本
                 默认取 $Instance/base

.EXAMPLE
  pwsh scripts/swe-bench-run.ps1 -Instance Temp/runs/jp
  # 题目目录含 task.txt / eval.sh / meta.json / base(目标仓库干净 clone)

.EXAMPLE
  pwsh scripts/swe-bench-run.ps1 -Instance Temp/runs/jp -Mode solo,delegate -SkipEval

.EXAMPLE
  # plan 消融：关闭计划语义建议 + reviewer 覆盖为 6 轮
  pwsh scripts/swe-bench-run.ps1 -Instance Temp/runs/jp -Mode plan -PlanSemanticReview off -ReviewerIters 6

.NOTES
  meta.json: {"instance_id":"javaparser-4538","base_commit":"<sha>",
              "image":"swebench/sweb.eval.x86_64.xxx:latest","expect":{"NodeTest":18}}
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$Instance,
  [string]$DevCliHome = (Get-Location).Path,
  [string]$TargetRepo,
  [ValidateSet('solo','delegate','plan')][string[]]$Mode = @('solo','delegate','plan'),
  [string]$OutRoot,
  [string]$M2 = (Join-Path $HOME '.m2\repository'),
  [ValidateSet('default','off')][string]$PlanSemanticReview = 'default',
  [int]$ReviewerIters = 0,
  [switch]$SkipPreflight,
  [switch]$SkipEval,
  [switch]$AutoPull
)

# 用 Continue 而非 Stop：java/docker/git 等 native 命令正常也会写 stderr，
# Stop 会把这些 NativeCommandError 当终止错误杀掉长进程；真实失败一律靠显式 LASTEXITCODE/Test-Path+Die 判定
$ErrorActionPreference = 'Continue'
$DevCliHome = (Resolve-Path $DevCliHome).Path
$Instance   = (Resolve-Path $Instance).Path
if (-not $TargetRepo){ $TargetRepo = Join-Path $Instance 'base' }
$TargetRepo = (Resolve-Path $TargetRepo).Path
if (-not $OutRoot){ $OutRoot = Join-Path $Instance ("runs-" + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force $OutRoot | Out-Null
$OutRoot = (Resolve-Path $OutRoot).Path
$JavaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
$DevCliCommit = (git -C $DevCliHome rev-parse HEAD).Trim()

function Step($m){ Write-Host "`n==> $m" -ForegroundColor Cyan }
function Die($m){ Write-Host "[PREFLIGHT-FAIL] $m" -ForegroundColor Red; exit 1 }

function Assert-RunConfiguration($meta) {
  if ($ReviewerIters -lt 0 -or $ReviewerIters -gt 8) { Die "-ReviewerIters 必须为 0 或 1-8" }
  if (-not $meta.base_commit -or -not $meta.image)    { Die "meta.json 必须含 base_commit 与 image" }
  if (-not $SkipEval -and ($null -eq $meta.expect -or @($meta.expect.PSObject.Properties).Count -eq 0)) {
    Die "meta.json 必须含非空 expect，评分不能只靠日志关键词"
  }
}

# ---------- 0. 预检：把环境坑拦在“烧模型额度”之前 ----------
function Invoke-Preflight {
  Step "预检（不过直接停，不花一分模型钱）"
  if (-not (Test-Path $JavaExe))                       { Die "找不到 java（设置 JAVA_HOME）: $JavaExe" }
  if (-not (Test-Path (Join-Path $DevCliHome 'pom.xml'))){ Die "-DevCliHome 不是 Maven 项目: $DevCliHome" }
  if (-not (Test-Path (Join-Path $TargetRepo '.git'))){ Die "-TargetRepo 不是 git 仓库: $TargetRepo" }
  foreach($f in 'task.txt','eval.sh','meta.json'){
    if (-not (Test-Path (Join-Path $Instance $f)))    { Die "Instance 缺 $f : $Instance" }
  }
  if (-not (Test-Path (Join-Path $DevCliHome '.env'))){ Write-Host "  [warn] DevCLI 无 .env，模型可能初始化失败" -ForegroundColor Yellow }
  if (-not (Test-Path $M2))                           { Die "本地 Maven 仓库不存在: $M2（禁网命令沙箱无法解析依赖）" }
  $meta = Get-Content (Join-Path $Instance 'meta.json') -Raw | ConvertFrom-Json
  Assert-RunConfiguration $meta
  if (-not $SkipEval) {
    if (-not (Get-Command docker -EA SilentlyContinue)){ Die "未找到 docker；只跑 Agent 请加 -SkipEval" }
    docker info *> $null; if ($LASTEXITCODE -ne 0)    { Die "docker daemon 未运行" }
    if (-not (docker image ls -q $meta.image)) {
      if ($AutoPull){ Step "拉取官方镜像 $($meta.image)"; docker pull $meta.image }
      else { Die "本地缺镜像 $($meta.image)；先 docker pull 或加 -AutoPull" }
    }
  }
  # java -version 写 stderr；Stop 策略下直接 2>&1 会被当终止错误，经 cmd 合并规避
  $jv = (cmd /c "`"$JavaExe`" -version 2>&1" | Select-Object -First 1)
  Write-Host ("  java={0}  devcli={1}  base={2}" -f $jv, $DevCliCommit, $meta.base_commit)
  return $meta
}

# ---------- 1. 编译 DevCLI + 自动生成绝对 classpath ----------
function Build-DevCli {
  Step "编译 DevCLI(main+test) 并生成 classpath"
  Push-Location $DevCliHome
  try {
    cmd /c "mvn -B -q -DskipTests compile test-compile"; if ($LASTEXITCODE -ne 0){ Die "编译失败" }
    $cpFile = Join-Path $OutRoot 'cp.txt'
    cmd /c "mvn -B -q dependency:build-classpath -Dmdep.outputFile=`"$cpFile`" -Dmdep.includeScope=test"
    if (-not (Test-Path $cpFile)){ Die "生成 classpath 失败" }
    $deps = (Get-Content $cpFile -Raw).Trim()
    $cp = "$DevCliHome\target\classes;$DevCliHome\target\test-classes;$deps"
    Set-Content (Join-Path $OutRoot 'driver-cp.txt') $cp -Encoding ascii -NoNewline
  } finally { Pop-Location }
}

# ---------- 2. 从目标仓库开干净独立工作副本（固定 base，可重复） ----------
function New-Workcopy($mode, $base) {
  $wd = Join-Path $OutRoot ("work-" + $mode)
  if (Test-Path $wd) {
    Die "工作副本已存在，拒绝覆盖: $wd（请使用新的 -OutRoot）"
  }
  # -c core.longpaths=true：目标仓库（如 javaparser）含超深测试文件，叠加工作副本路径会撞 Windows 260 限制
  git -c core.longpaths=true clone --quiet --no-checkout $TargetRepo $wd
  if ($LASTEXITCODE -ne 0){ Die "克隆目标仓库失败: $TargetRepo" }
  git -C $wd config core.longpaths true
  # 关 autocrlf：还原仓库原生 LF（官方 harness 是 Linux），避免 mvnw 被转 CRLF 后在 Linux 沙箱无法执行、patch 混入行尾噪声
  git -C $wd config core.autocrlf false
  git -C $wd checkout -fq $base
  if ($LASTEXITCODE -ne 0){ Die "目标 base_commit 不可用: $base（检查 core.longpaths/路径长度）" }
  return (Resolve-Path $wd).Path
}

# ---------- 3. 跑一个模式（统一带齐必选 -D，避免漏配重跑） ----------
function Invoke-Driver($mode, $workdir, $cp) {
  $out = Join-Path $OutRoot "$mode.out"
  $log = Join-Path $OutRoot "$mode-run.log"
  $jargs = @('-Ddevcli.llm.http.protocol=HTTP_1_1',
             "-Ddevcli.command.sandbox.maven.repository=$M2")
  if ($PlanSemanticReview -eq 'off'){ $jargs += '-Ddevcli.team.plan.review.enabled=false' }
  if ($ReviewerIters -gt 0){ $jargs += "-Ddevcli.team.reviewer.max.iterations=$ReviewerIters" }
  Step "运行 $mode (work=$workdir)"
  $started = Get-Date
  Push-Location $DevCliHome
  try {
    & $JavaExe @jargs -cp $cp com.devcli.eval.SweBenchDriver $workdir (Join-Path $Instance 'task.txt') $out $mode *> $log
  } finally { Pop-Location }
  $exitCode = $LASTEXITCODE
  $finished = Get-Date
  $m = Select-String -Path $log -Pattern '\[driver\] done.*wallMs=(\d+)' | Select-Object -Last 1
  $wallMs = if ($m){ [int64]$m.Matches[0].Groups[1].Value } else { -1 }
  $u = Select-String -Path $log -Pattern '\[driver\] usage inputTokens=(\d+) outputTokens=(\d+) cachedInputTokens=(\d+) estimatedCostCny=([0-9.Ee+-]+)' | Select-Object -Last 1
  return [pscustomobject]@{
    exitCode=$exitCode; wallMs=$wallMs; started=$started; finished=$finished
    inputTokens=if($u){ [int64]$u.Matches[0].Groups[1].Value }else{ 0 }
    outputTokens=if($u){ [int64]$u.Matches[0].Groups[2].Value }else{ 0 }
    cachedInputTokens=if($u){ [int64]$u.Matches[0].Groups[3].Value }else{ 0 }
    estimatedCostCny=if($u){ [double]::Parse($u.Matches[0].Groups[4].Value,[Globalization.CultureInfo]::InvariantCulture) }else{ 0.0 }
  }
}

# ---------- 4. 生成 LF patch（经 cmd 重定向，规避 PS 的 UTF8-BOM/CRLF） ----------
function New-Patch($mode, $workdir) {
  $patch = Join-Path $OutRoot "$mode.patch"
  git -C $workdir add -N -- .
  if ($LASTEXITCODE -ne 0){ Die "登记新增文件失败: $workdir" }
  cmd /c "git -C `"$workdir`" diff --binary --full-index --ignore-cr-at-eol -- > `"$patch`""
  if ($LASTEXITCODE -ne 0){ Die "生成 patch 失败: $workdir" }
  return $patch
}

# ---------- 5. Docker harness 评分（退出码 + 预期测试双重判定） ----------
function Invoke-Harness($mode, $image, $expect) {
  $evalLog = Join-Path $OutRoot "$mode-eval.log"
  Step "Docker harness 评分 $mode"
  docker run --rm -v "${OutRoot}:/work" -v "${Instance}:/instance:ro" -v "${M2}:/root/.m2/repository" -w /testbed $image `
    sh -c "git apply --verbose /work/$mode.patch && echo PATCH_APPLIED_OK && bash /instance/eval.sh" *> $evalLog
  $harnessExitCode = $LASTEXITCODE
  $raw = Get-Content $evalLog -Raw
  $applied = $raw -match 'PATCH_APPLIED_OK'
  $bad = $raw -match 'BUILD FAILURE|FAIL!|Failures: [1-9]|Errors: [1-9]'
  $classes = @{}
  foreach($x in [regex]::Matches($raw,'Tests run: (\d+), Failures: (\d+), Errors: (\d+)[^\r\n]*-- in ([\w.$]+)')){
    $classes[$x.Groups[4].Value] = [pscustomobject]@{ n=[int]$x.Groups[1].Value; f=[int]$x.Groups[2].Value; e=[int]$x.Groups[3].Value }
  }
  $expectationsMet = $true
  $expectationResults = @{}
  foreach($property in $expect.PSObject.Properties){
    $name = $property.Name
    $expectedCount = [int]$property.Value
    # @Nested：用例归到 Outer$Inner，外层 Outer 自身 n=0；按顶层简单类名聚合所有内部类求和
    $aggN=0; $aggF=0; $aggE=0; $matchedAny=$false
    foreach($k in $classes.Keys){
      $simple = $k.Substring($k.LastIndexOf('.')+1)
      $topSimple = ($simple -split '\$')[0]
      if ($topSimple -eq $name){ $matchedAny=$true; $c=$classes[$k]; $aggN+=$c.n; $aggF+=$c.f; $aggE+=$c.e }
    }
    $passed = $matchedAny -and $aggN -eq $expectedCount -and $aggF -eq 0 -and $aggE -eq 0
    if (-not $passed){ $expectationsMet = $false }
    $expectationResults[$name] = [pscustomobject]@{
      expected=$expectedCount; matched=$matchedAny
      actual=$aggN; failures=$aggF; errors=$aggE
    }
  }
  $resolved = $harnessExitCode -eq 0 -and $applied -and -not $bad -and $expectationsMet
  return [pscustomobject]@{
    exitCode=$harnessExitCode; applied=$applied; resolved=$resolved
    expectationsMet=$expectationsMet; expectations=$expectationResults; classes=$classes
  }
}

# ================= 主流程 =================
$meta = if ($SkipPreflight){ Get-Content (Join-Path $Instance 'meta.json') -Raw | ConvertFrom-Json } else { Invoke-Preflight }
if ($SkipPreflight){ Assert-RunConfiguration $meta }
Build-DevCli
$cp = Get-Content (Join-Path $OutRoot 'driver-cp.txt') -Raw
$jsonl = Join-Path $OutRoot 'results.jsonl'; Set-Content $jsonl '' -Encoding utf8 -NoNewline
$summary = @()

foreach($mode in $Mode){
  $wd = New-Workcopy $mode $meta.base_commit
  $r  = Invoke-Driver $mode $wd $cp
  $patch = New-Patch $mode $wd
  $patchLines = if (Test-Path (Join-Path $OutRoot "$mode.patch")){ (Get-Content (Join-Path $OutRoot "$mode.patch") | Measure-Object -Line).Lines } else { 0 }
  $row = [ordered]@{
    benchmark='swebench_multilingual'; instance=$meta.instance_id; mode=$mode
    devcli_commit=$DevCliCommit; base_commit=$meta.base_commit
    plan_semantic_review=$PlanSemanticReview
    reviewer_iters=if($ReviewerIters -gt 0){ $ReviewerIters }else{ 5 }
    wall_ms=$r.wallMs; patch_lines=$patchLines
    input_tokens=$r.inputTokens; output_tokens=$r.outputTokens
    cached_input_tokens=$r.cachedInputTokens; estimated_cost_cny=$r.estimatedCostCny
    startedAt=$r.started.ToString('o'); finishedAt=$r.finished.ToString('o')
    resolved=$false; applied=$false; harness_exit_code=$null
    expectations_met=$false; expectations=@{}; tests=@{}; note=''
  }
  if ($r.exitCode -ne 0){ $row.note="DRIVER_EXIT_$($r.exitCode)" }
  elseif ($patchLines -eq 0){ $row.note='NO_PATCH' }
  elseif (-not $SkipEval){
    $h = Invoke-Harness $mode $meta.image $meta.expect
    $row.resolved=$h.resolved; $row.applied=$h.applied; $row.tests=$h.classes
    $row.harness_exit_code=$h.exitCode; $row.expectations_met=$h.expectationsMet
    $row.expectations=$h.expectations
    if ($h.exitCode -ne 0){ $row.note="HARNESS_EXIT_$($h.exitCode)" }
    elseif (-not $h.applied){ $row.note='PATCH_APPLY_FAILED' }
    elseif (-not $h.expectationsMet){ $row.note='EXPECTATION_MISMATCH' }
  }
  ($row | ConvertTo-Json -Compress -Depth 6) | Add-Content $jsonl
  $summary += [pscustomobject]@{ mode=$mode; resolved=$row.resolved; wall_s=[math]::Round($r.wallMs/1000,1); patchLines=$patchLines }
}

Step "汇总"
$summary | Format-Table -AutoSize
Write-Host "结果目录: $OutRoot" -ForegroundColor Green
