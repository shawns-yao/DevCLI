[CmdletBinding()]
param(
  [string]$Dataset = "Data/raw/public-benchmarks/swebench-multilingual/test.parquet",
  [string]$Python = "C:\Download\Pycharm\python-3.10.11\python.exe",
  [string]$Model = "gpt-5.6-luna",
  [ValidateSet('smoke','public')][string]$TestType = 'smoke',
  [ValidateSet('solo','delegate')][string[]]$Mode = @('solo','delegate'),
  [ValidateSet('raw','compact')][string[]]$ContextMode = @('raw','compact'),
  [string[]]$InstanceIds = @(),
  [ValidateRange(0,1000000)][int]$CompressionTriggerTokens = 0,
  [ValidateRange(1,64)][int]$ContinuationRounds = 1,
  [ValidateRange(0,2000000)][int]$ModelContextWindowTokens = 0,
  [string]$TaskPromptRoot = '',
  [string]$ContinuationPromptRoot = '',
  [ValidateRange(1,10000000)][int]$TokenBudget = 1000000,
  [ValidateRange(1,1000)][int]$MaxIterations = 100,
  [ValidateRange(1,65536)][int]$MaxOutputTokens = 8192,
  [string]$M2 = "C:\Document\Maven\repository",
  [string]$CacheRoot = "Data/raw/public-benchmarks/swebench-multilingual/repos-cache",
  [string]$OutRoot = "Test/swebench-multilingual-java/gpt-5.6-luna-single-round",
  [string]$WorkRoot = "Temp/swj"
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Dataset = (Resolve-Path (Join-Path $Root $Dataset)).Path
$Python = (Resolve-Path $Python).Path
$M2 = (Resolve-Path $M2).Path
$CacheRoot = [IO.Path]::GetFullPath((Join-Path $Root $CacheRoot))
$OutRoot = [IO.Path]::GetFullPath((Join-Path $Root $OutRoot))
$WorkRoot = [IO.Path]::GetFullPath((Join-Path $Root $WorkRoot))
if (-not [string]::IsNullOrWhiteSpace($ContinuationPromptRoot)) {
  $ContinuationPromptRoot = (Resolve-Path (Join-Path $Root $ContinuationPromptRoot)).Path
}
if (-not [string]::IsNullOrWhiteSpace($TaskPromptRoot)) {
  $TaskPromptRoot = (Resolve-Path (Join-Path $Root $TaskPromptRoot)).Path
}
$Manifest = Join-Path $OutRoot 'java-43-manifest.json'
$OfficialDataset = Join-Path $OutRoot 'java-43-official-dataset.json'
$ResultFile = Join-Path $OutRoot 'generation-results.jsonl'
$DatasetHash = (Get-FileHash -LiteralPath $Dataset -Algorithm SHA256).Hash
$sourceFiles = @('pom.xml', 'scripts/swe-bench-multilingual-java.ps1', 'scripts/export-swebench-java.py')
$sourceFiles += @(Get-ChildItem -LiteralPath (Join-Path $Root 'benchmarks/src/main') -Recurse -File |
  ForEach-Object { [IO.Path]::GetRelativePath($Root, $_.FullName) })
$sourceFiles += @(Get-ChildItem -LiteralPath (Join-Path $Root 'src/main') -Recurse -File |
  ForEach-Object { [IO.Path]::GetRelativePath($Root, $_.FullName) })
$sourceHashes = ($sourceFiles | Sort-Object | ForEach-Object {
  $_ + ':' + (Get-FileHash -LiteralPath (Join-Path $Root $_) -Algorithm SHA256).Hash
}) -join "`n"
$SourceHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($sourceHashes)))

New-Item -ItemType Directory -Force $CacheRoot,$OutRoot,$WorkRoot | Out-Null

function Invoke-Native([scriptblock]$Command, [string]$Failure) {
  & $Command
  if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}

function Safe-Name([string]$Value) {
  return $Value.Replace('/', '__').Replace('\', '__')
}

function Ensure-Repo([string]$Repo, [string]$Commit) {
  $cache = Join-Path $CacheRoot ((Safe-Name $Repo) + '.git')
  if (-not (Test-Path $cache)) {
    Invoke-Native { git init --bare --quiet $cache } "cache init failed: $Repo"
    Invoke-Native { git --git-dir=$cache remote add origin "https://github.com/$Repo.git" } "remote init failed: $Repo"
  }
  git --git-dir=$cache config core.longpaths true
  git --git-dir=$cache config core.autocrlf false
  git --git-dir=$cache cat-file -e "$Commit^{commit}" 2>$null
  if ($LASTEXITCODE -ne 0) {
    Invoke-Native { git --git-dir=$cache fetch --depth=1 --no-tags origin $Commit } "fetch failed: $Repo@$Commit"
    Invoke-Native { git --git-dir=$cache cat-file -e "$Commit^{commit}" } "base commit missing: $Repo@$Commit"
  }
  Invoke-Native { git --git-dir=$cache update-ref "refs/devcli/$Commit" $Commit } "cache ref failed: $Repo@$Commit"
  return $cache
}

function Ensure-Workcopy([pscustomobject]$Case, [string]$CurrentMode, [string]$CurrentContextMode, [string]$RepoCache) {
  $name = (Safe-Name $Case.instance_id) + '-' + $CurrentMode + '-' + $CurrentContextMode
  $work = Join-Path $WorkRoot $name
  if (-not (Test-Path (Join-Path $work '.git'))) {
    Invoke-Native { git --git-dir=$RepoCache worktree add --quiet --detach $work $Case.base_commit } "worktree create failed: $name"
    git -C $work config core.autocrlf false
    git -C $work config core.longpaths true
  } else {
    $head = git -C $work rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $head -ne $Case.base_commit) { throw "Worktree base mismatch: $name" }
  }
  return $work
}

function Get-DriverEvidence([string]$LogPath) {
  $text = [IO.File]::ReadAllText($LogPath)
  $header = [regex]::Match($text, '(?m)^\[driver\] mode=[^\r\n]+').Value
  $fields = @{}
  foreach ($match in [regex]::Matches($header, '(?:^|\s)(\w+)=([^\s{}]+)')) {
    $fields[$match.Groups[1].Value] = $match.Groups[2].Value
  }
  $windows = @([regex]::Matches($text, '(?m)^(?:\[context-compaction\] kind=decision |\[driver\] context )[^\r\n]*historyTokens=(\d+) triggerTokens=(\d+)') |
    ForEach-Object { [long]$_.Groups[1].Value })
  $summaries = @([regex]::Matches($text, '(?m)^\[context-compaction\] kind=summary-call inputTokens=(\d+) outputTokens=(\d+) cachedInputTokens=(\d+)'))
  $summaryInput = 0L; $summaryOutput = 0L; $summaryCached = 0L
  foreach ($call in $summaries) {
    $summaryInput += [long]$call.Groups[1].Value
    $summaryOutput += [long]$call.Groups[2].Value
    $summaryCached += [long]$call.Groups[3].Value
  }
  $externalCodes = @([regex]::Matches($text, '(?m)^\[driver\] failure code=(\w+) external=true') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
  $externalCodes += @([regex]::Matches($text, '(?m)^\[context-compaction\] kind=summary-error code=(AUTHENTICATION|RATE_LIMITED|OVERLOADED|TIMEOUT|NETWORK|SERVER_ERROR)\b') |
    ForEach-Object { $_.Groups[1].Value })
  # Historical terminal errors are accepted only on provider error lines, never from tool content.
  $providerErrors = @([regex]::Matches($text, '(?m)^[^\r\n]*LLM (?:request|transport) failed:[^\r\n]*') |
    ForEach-Object { $_.Value })
  foreach ($errorLine in $providerErrors) {
    if ($errorLine -match 'model_not_found') { $externalCodes += 'MODEL_UNAVAILABLE' }
    elseif ($errorLine -match 'code=(AUTHENTICATION|RATE_LIMITED|OVERLOADED|TIMEOUT|NETWORK|SERVER_ERROR)') { $externalCodes += $Matches[1] }
    elseif ($errorLine -match 'status=503\b') { $externalCodes += 'SERVER_ERROR' }
  }
  return [pscustomobject]@{
    Fields = $fields
    PeakHistoryTokens = if ($windows.Count) { ($windows | Measure-Object -Maximum).Maximum } else { $null }
    RoundCount = [regex]::Matches($text, '(?m)^\[driver\] context round=').Count
    SummaryInput = $summaryInput; SummaryOutput = $summaryOutput; SummaryCached = $summaryCached
    SummaryCalls = $summaries.Count
    ExternalCodes = @($externalCodes | Sort-Object -Unique)
    ContextLimitErrors = [regex]::Matches($text, '(?m)^(?:\[driver\] failure |\[context-compaction\] kind=summary-error )code=CONTEXT_LENGTH\b').Count
    DelegationCalls = [regex]::Matches($text, '(?m)^\[driver\] delegation-call\r?$').Count
  }
}

function Build-DriverClasspath {
  $cpFile = Join-Path $OutRoot 'dependency-classpath.txt'
  # A running experiment must not load classes overwritten by another build.
  $buildRoot = Join-Path $OutRoot ('driver-build-' + $SourceHash.Substring(0, 16))
  if (-not (Test-Path -LiteralPath $buildRoot)) {
    foreach ($relative in $sourceFiles) {
      $destination = Join-Path $buildRoot $relative
      New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
      Copy-Item -LiteralPath (Join-Path $Root $relative) -Destination $destination
    }
  }
  $snapshotHashes = ($sourceFiles | Sort-Object | ForEach-Object {
    $_ + ':' + (Get-FileHash -LiteralPath (Join-Path $buildRoot $_) -Algorithm SHA256).Hash
  }) -join "`n"
  if ($snapshotHashes -cne $sourceHashes) { throw 'Source changed while freezing driver build' }
  Push-Location $buildRoot
  try {
    Invoke-Native { cmd.exe /d /s /c "mvn -B -q -o -Dmaven.repo.local=`"$M2`" -DskipTests compile" } 'DevCLI compile failed'
    Invoke-Native { cmd.exe /d /s /c "mvn -B -q -o -Dmaven.repo.local=`"$M2`" dependency:build-classpath -Dmdep.outputFile=`"$cpFile`" -Dmdep.includeScope=runtime" } 'classpath generation failed'
  } finally {
    Pop-Location
  }
  $deps = (Get-Content -Raw $cpFile).Trim()
  $driverClasses = Join-Path $buildRoot 'target/benchmark-classes'
  New-Item -ItemType Directory -Force -Path $driverClasses | Out-Null
  Invoke-Native { & (Join-Path $env:JAVA_HOME 'bin/javac.exe') -encoding UTF-8 `
    -cp "$buildRoot\target\classes;$deps" -d $driverClasses `
    (Join-Path $buildRoot 'benchmarks/src/main/java/com/devcli/eval/SweBenchDriver.java') } 'SWE driver compile failed'
  return "$buildRoot\target\classes;$driverClasses;$deps"
}

function Export-ModelPatch([string]$Work, [string]$Patch) {
  # These recoverable conversation artifacts remain in the worktree, not in the submitted patch.
  $paths = @('.', ':(exclude).devcli/microcompact_message_outputs/**',
    ':(exclude).devcli/microcompact_tool_outputs/**')
  Invoke-Native { git -C $Work add -N -- @paths } 'Patch staging failed'
  $quotedPaths = ($paths | ForEach-Object { '"' + $_ + '"' }) -join ' '
  Invoke-Native { cmd /c "git -C `"$Work`" diff --binary --full-index --ignore-cr-at-eol -- $quotedPaths > `"$Patch`"" } 'Patch export failed'
}

Invoke-Native {
  & $Python (Join-Path $Root 'scripts/export-swebench-java.py') `
    --parquet $Dataset --manifest $Manifest --dataset $OfficialDataset
} 'Java manifest export failed'

$Classpath = Build-DriverClasspath
$AllCases = @(Get-Content -Raw $Manifest | ConvertFrom-Json)
if ($InstanceIds.Count -gt 0) {
  $unknown = @($InstanceIds | Where-Object { $_ -notin $AllCases.instance_id })
  if ($unknown.Count -gt 0) { throw "Unknown official instance IDs: $unknown" }
  $Cases = @($AllCases | Where-Object { $_.instance_id -in $InstanceIds })
} else {
  $Cases = $AllCases
}
$previousModel = $env:OPENAI_MODEL
$env:OPENAI_MODEL = $Model

try {
  foreach ($case in $Cases) {
    $repoCache = Ensure-Repo $case.repo $case.base_commit
    foreach ($currentMode in $Mode) {
      foreach ($currentContextMode in $ContextMode) {
      $condition = "$currentMode-$currentContextMode"
      $caseDir = Join-Path $OutRoot ((Safe-Name $case.instance_id) + '\' + $condition)
      $done = Join-Path $caseDir 'result.json'
      if (Test-Path $done) {
        $previous = Get-Content -Raw -LiteralPath $done | ConvertFrom-Json
        if ($previous.base_commit -ne $case.base_commit -or $previous.requested_model -ne $Model `
            -or $previous.test_type -ne $TestType `
            -or $previous.mode -ne $currentMode -or $previous.context_mode -ne $currentContextMode `
            -or $previous.continuation_rounds -ne $ContinuationRounds `
            -or [int]$previous.model_context_window_tokens -ne $ModelContextWindowTokens `
            -or [int]$previous.compression_trigger_tokens -ne $CompressionTriggerTokens `
            -or $previous.dataset_sha256 -ne $DatasetHash -or $previous.source_sha256 -ne $SourceHash `
            -or $previous.token_budget -ne $TokenBudget -or $previous.max_iterations -ne $MaxIterations `
            -or $previous.max_output_tokens -ne $MaxOutputTokens `
            -or $previous.actual_model -ne $Model -or $previous.provider -ne 'openai') {
          throw "Existing result does not match requested experiment: $($case.instance_id) $condition"
        }
        Write-Host "[skip] $($case.instance_id) $condition"
        continue
      }
      New-Item -ItemType Directory -Force $caseDir | Out-Null
      $work = Ensure-Workcopy $case $currentMode $currentContextMode $repoCache
      $taskPromptSource = $null
      if (-not [string]::IsNullOrWhiteSpace($TaskPromptRoot)) {
        $taskPromptSource = Join-Path (Join-Path $TaskPromptRoot (Safe-Name $case.instance_id)) 'task.txt'
        if (-not (Test-Path -LiteralPath $taskPromptSource -PathType Leaf)) {
          throw "Missing task prompt: $taskPromptSource"
        }
      }
      $continuationDir = $null
      if (-not [string]::IsNullOrWhiteSpace($ContinuationPromptRoot)) {
        $continuationDir = Join-Path (Join-Path $ContinuationPromptRoot (Safe-Name $case.instance_id)) 'continuations'
        if (-not (Test-Path -LiteralPath $continuationDir -PathType Container)) {
          throw "Missing continuation prompt directory: $continuationDir"
        }
        $promptCount = @(Get-ChildItem -LiteralPath $continuationDir -File -Filter '*.txt').Count
        if ($promptCount -ne $ContinuationRounds - 1) {
          throw "Expected $($ContinuationRounds - 1) continuation prompts, got $promptCount"
        }
      }
      $protocolFiles = @()
      if ($null -ne $taskPromptSource) { $protocolFiles += $taskPromptSource }
      if ($null -ne $continuationDir) {
        $protocolFiles += @(Get-ChildItem -LiteralPath $continuationDir -File -Filter '*.txt' |
          Sort-Object Name | ForEach-Object FullName)
      }
      $promptProtocolHash = if ($protocolFiles.Count -gt 0) {
        $protocolHashes = ($protocolFiles | ForEach-Object {
          [IO.Path]::GetRelativePath($Root, $_) + ':' + (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash
        }) -join "`n"
        [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($protocolHashes)))
      } else { $null }
      $prompt = Join-Path $caseDir 'task.txt'
      $out = Join-Path $caseDir 'agent.out'
      $log = Join-Path $caseDir 'agent.log'
      $patch = Join-Path $caseDir 'model.patch'
      $specFile = Join-Path $caseDir 'run-spec.json'
      $spec = [ordered]@{schema_version=2; instance_id=$case.instance_id; base_commit=$case.base_commit;
        test_type=$TestType; summary_usage_scope='compactor_and_presummary';
        tool_scope='ISOLATED_PROJECT';
        model=$Model; mode=$currentMode; context_mode=$currentContextMode; continuation_rounds=$ContinuationRounds;
        model_context_window_tokens=$ModelContextWindowTokens;
        prompt_protocol_sha256=$promptProtocolHash;
        compression_trigger_tokens=$CompressionTriggerTokens; token_budget=$TokenBudget; max_iterations=$MaxIterations;
        max_output_tokens=$MaxOutputTokens; sandbox='DOCKER'; memory='isolated-disabled';
        dataset_sha256=$DatasetHash; source_sha256=$SourceHash}
      $specJson = $spec | ConvertTo-Json -Compress
      if (Test-Path $specFile) {
        if ((Get-Content -Raw -LiteralPath $specFile).Trim() -ne $specJson) {
          throw "Run specification changed: $($case.instance_id) $condition"
        }
      } elseif (Test-Path $log) {
        throw "Existing log has no frozen run specification: $($case.instance_id) $condition"
      } else {
        if (@(git -C $work status --porcelain).Count -gt 0) { throw "Unclaimed dirty worktree: $condition" }
        Set-Content -LiteralPath $specFile -Value $specJson -Encoding utf8NoBOM
      }

      $completedLog = (Test-Path $log) -and ($null -ne (Select-String -Path $log -Pattern '\[driver\] done' | Select-Object -Last 1))
      if ((Test-Path $log) -and -not $completedLog) {
        Write-Host "[interrupted-no-retry] $($case.instance_id) $condition; preserving existing workspace/log"
        continue
      }
      if ($null -ne $taskPromptSource) {
        Copy-Item -LiteralPath $taskPromptSource -Destination $prompt
      } else {
        Set-Content -LiteralPath $prompt -Value $case.problem_statement -Encoding utf8NoBOM
      }
      if ($completedLog) {
        Write-Host "[resume-result] $($case.instance_id) $condition"
        $started = (Get-Item $log).CreationTime
        $finished = (Get-Item $log).LastWriteTime
        $driverExit = 0
      } else {
        Write-Host "[run] $($case.instance_id) $condition model=$Model"
        $started = Get-Date
        $runtimeHome = Join-Path $caseDir 'runtime-home'
        New-Item -ItemType Directory -Force -Path $runtimeHome | Out-Null
        $javaArgs = @(
          '-Ddevcli.llm.http.protocol=HTTP_1_1',
          '-Dfile.encoding=UTF-8',
          '-Dstdout.encoding=UTF-8',
          '-Dstderr.encoding=UTF-8',
          '-Ddevcli.command.sandbox.mode=DOCKER',
          "-Duser.home=$runtimeHome",
          "-Ddevcli.memory.dir=$runtimeHome/memory",
          "-Ddevcli.llm.max.output.tokens=$MaxOutputTokens",
          "-Ddevcli.react.token.budget=$TokenBudget",
          "-Ddevcli.react.hard.max.iterations=$MaxIterations",
          "-Ddevcli.command.sandbox.maven.repository=$M2",
          '-Ddevcli.context.compaction.metrics.enabled=true'
        )
        if ($CompressionTriggerTokens -gt 0) {
          $javaArgs += "-Ddevcli.context.compression.trigger.tokens=$CompressionTriggerTokens"
        }
        if ($ModelContextWindowTokens -gt 0) {
          $javaArgs += "-Ddevcli.benchmark.model.context.window=$ModelContextWindowTokens"
        }
        Push-Location $Root
        try {
          $driverArgs = @($work, $prompt, $out, $currentMode, $currentContextMode, $ContinuationRounds)
          if ($null -ne $continuationDir) { $driverArgs += $continuationDir }
          & (Join-Path $env:JAVA_HOME 'bin\java.exe') @javaArgs `
            -cp $Classpath com.devcli.eval.SweBenchDriver @driverArgs *> $log
          $driverExit = $LASTEXITCODE
        } finally {
          Pop-Location
        }
        $finished = Get-Date
      }

      Export-ModelPatch $work $patch
      $patchText = if (Test-Path $patch) { [string](Get-Content -Raw $patch) } else { '' }
      if ($null -eq $patchText) { $patchText = '' }
      $usage = Select-String -Path $log -Pattern '\[driver\] usage inputTokens=(\d+) outputTokens=(\d+) cachedInputTokens=(\d+) estimatedCostCny=([0-9.Ee+-]+)' | Select-Object -Last 1
      $driver = Select-String -Path $log -Pattern '\[driver\] done.*wallMs=(\d+)' | Select-Object -Last 1
      $evidence = Get-DriverEvidence $log
      $validProvenance = $evidence.Fields.model -eq $Model -and $evidence.Fields.provider -eq 'openai' `
        -and $evidence.Fields.memoryScope -eq 'isolated' `
        -and $evidence.Fields.toolScope -eq 'ISOLATED_PROJECT' `
        -and $evidence.Fields.mode -eq $currentMode -and $evidence.Fields.contextMode -eq $currentContextMode `
        -and [int]$evidence.Fields.continuationRounds -eq $ContinuationRounds
      $externalFailure = $evidence.ExternalCodes.Count -gt 0
      $validSample = $validProvenance -and -not $externalFailure -and $driverExit -eq 0 -and $null -ne $driver `
        -and $null -ne $usage -and $evidence.RoundCount -eq $ContinuationRounds
      $compactionTriggers = @(Select-String -Path $log -Pattern '^\[context-compaction\] kind=trigger ')
      $compactionFallbacks = @(Select-String -Path $log -Pattern '^\[context-compaction\] kind=fallback ')
      $compactionSuccesses = @(Select-String -Path $log -Pattern '^\[context-compaction\] kind=history ')

      $row = [ordered]@{
        instance_id = $case.instance_id
        schema_version = 2
        test_type = $TestType
        summary_usage_scope = 'compactor_and_presummary'
        dataset_sha256 = $DatasetHash
        source_sha256 = $SourceHash
        repo = $case.repo
        base_commit = $case.base_commit
        mode = $currentMode
        context_mode = $currentContextMode
        compression_trigger_tokens = if ($CompressionTriggerTokens -gt 0) { $CompressionTriggerTokens } else { $null }
        model_context_window_tokens = if ($ModelContextWindowTokens -gt 0) { $ModelContextWindowTokens } else { $null }
        prompt_protocol_sha256 = $promptProtocolHash
        continuation_rounds = $ContinuationRounds
        token_budget = $TokenBudget
        max_iterations = $MaxIterations
        max_output_tokens = $MaxOutputTokens
        completed_rounds = $evidence.RoundCount
        peak_history_tokens = $evidence.PeakHistoryTokens
        summary_calls = $evidence.SummaryCalls
        summary_input_tokens = $evidence.SummaryInput
        summary_output_tokens = $evidence.SummaryOutput
        summary_cached_input_tokens = $evidence.SummaryCached
        context_limit_errors = $evidence.ContextLimitErrors
        delegation_calls = $evidence.DelegationCalls
        external_failure = $externalFailure
        external_failure_codes = @($evidence.ExternalCodes)
        valid_sample = [bool]$validSample
        failure_class = if ($externalFailure) { 'external_failure' } elseif (-not $validProvenance) { 'provenance_failure' } elseif (-not $validSample) { 'execution_failure' } else { $null }
        compaction_triggers = $compactionTriggers.Count
        compaction_fallbacks = $compactionFallbacks.Count
        compaction_successes = $compactionSuccesses.Count
        requested_model = $Model
        actual_model = $evidence.Fields.model
        provider = $evidence.Fields.provider
        memory_scope = $evidence.Fields.memoryScope
        tool_scope = $evidence.Fields.toolScope
        driver_exit_code = $driverExit
        patch_bytes = [Text.Encoding]::UTF8.GetByteCount($patchText)
        input_tokens = if ($usage) { [int64]$usage.Matches[0].Groups[1].Value } else { $null }
        output_tokens = if ($usage) { [int64]$usage.Matches[0].Groups[2].Value } else { $null }
        cached_input_tokens = if ($usage) { [int64]$usage.Matches[0].Groups[3].Value } else { $null }
        total_input_tokens = if ($usage) { [int64]$usage.Matches[0].Groups[1].Value + $evidence.SummaryInput } else { $null }
        total_output_tokens = if ($usage) { [int64]$usage.Matches[0].Groups[2].Value + $evidence.SummaryOutput } else { $null }
        total_cached_input_tokens = if ($usage) { [int64]$usage.Matches[0].Groups[3].Value + $evidence.SummaryCached } else { $null }
        estimated_cost_cny = if ($usage) { [double]::Parse($usage.Matches[0].Groups[4].Value, [Globalization.CultureInfo]::InvariantCulture) } else { $null }
        estimated_cost_scope = 'agent_calls_only'
        cost_includes_summary = $false
        agent_wall_ms = if ($driver) { [int64]$driver.Matches[0].Groups[1].Value } else { $null }
        started_at = $started.ToString('o')
        finished_at = $finished.ToString('o')
      }
      $json = $row | ConvertTo-Json -Compress
      [ordered]@{
        instance_id = $case.instance_id
        model_name_or_path = "devcli-$condition-$Model"
        model_patch = $patchText
      } | ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $caseDir 'prediction.jsonl') -Encoding utf8NoBOM
      Set-Content -LiteralPath "$done.tmp" -Value $json -Encoding utf8NoBOM
      Move-Item -LiteralPath "$done.tmp" -Destination $done
      Add-Content -LiteralPath $ResultFile -Value $json -Encoding utf8NoBOM
      Write-Host "[result] $($case.instance_id) $condition valid=$validSample external=$externalFailure peak=$($evidence.PeakHistoryTokens) compactions=$($compactionSuccesses.Count)"
      if ($externalFailure) { throw "External model failure; batch stopped without retry: $($evidence.ExternalCodes -join ',')" }
      }
    }
  }
} finally {
  $env:OPENAI_MODEL = $previousModel
}

Write-Host "Generation complete: $ResultFile"
