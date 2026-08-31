[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$OfficialDataset,
  [Parameter(Mandatory)][string]$InstanceId,
  [Parameter(Mandatory)][string]$RepoCache,
  [Parameter(Mandatory)][string]$OutputRoot,
  [ValidateRange(256000,2000000)][int]$TargetPayloadChars = 1100000,
  [ValidateRange(12000,23000)][int]$ChunkChars = 22000,
  [switch]$MicroOnly
)

$ErrorActionPreference = 'Stop'
$OfficialDataset = (Resolve-Path $OfficialDataset).Path
$RepoCache = (Resolve-Path $RepoCache).Path
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$case = @(Get-Content -Raw -LiteralPath $OfficialDataset | ConvertFrom-Json |
  Where-Object instance_id -eq $InstanceId)
if ($case.Count -ne 1) { throw "Expected one public instance, got $($case.Count): $InstanceId" }
$case = $case[0]
git --git-dir=$RepoCache cat-file -e "$($case.base_commit)^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) { throw "Base commit is missing: $($case.base_commit)" }

$caseRoot = Join-Path $OutputRoot $InstanceId.Replace('/', '__').Replace('\', '__')
$turnRoot = Join-Path $caseRoot 'continuations'
$manifest = Join-Path $caseRoot 'protocol-manifest.json'
if (Test-Path -LiteralPath $manifest) {
  Write-Output "Retention prompts already frozen: $manifest"
  exit 0
}
New-Item -ItemType Directory -Force -Path $turnRoot | Out-Null

$paths = @(git --git-dir=$RepoCache ls-tree -r --name-only $case.base_commit -- `
  server/src/main/java server/src/test/java | Where-Object { $_ -like '*.java' } | Sort-Object)
if ($LASTEXITCODE -ne 0 -or $paths.Count -eq 0) { throw 'No public Java source files found' }

$payload = [Text.StringBuilder]::new($TargetPayloadChars + $ChunkChars)
$included = [Collections.Generic.List[string]]::new()
foreach ($path in $paths) {
  $content = git --git-dir=$RepoCache show "$($case.base_commit):$path"
  if ($LASTEXITCODE -ne 0) { throw "Unable to read public source: $path" }
  [void]$payload.Append("`n===== PUBLIC SOURCE: $path =====`n")
  [void]$payload.Append(($content -join "`n"))
  [void]$payload.Append("`n")
  $included.Add($path)
  if ($payload.Length -ge $TargetPayloadChars) { break }
}
if ($payload.Length -lt $TargetPayloadChars) {
  throw "Public source payload too small: $($payload.Length) chars"
}
$payloadText = $payload.ToString(0, $TargetPayloadChars)
$chunks = [int][Math]::Ceiling($payloadText.Length / [double]$ChunkChars)

if ($MicroOnly) {
  $middle = [int][Math]::Floor($payloadText.Length / 2)
  $task = @"
这是 SWE-bench Multilingual Java 的公开源码证据包。原始 Issue 位于消息中部；如果生产压缩把中部折叠为可恢复引用，必须先通过 DevCLI 工具恢复完整证据。
在任何工具调用之前先输出一行 RETENTION_CHECK JSON，字段包含 requirements、failure_mode、required_behavior、non_regression_constraints。随后使用真实工具链实现 Issue，最终修改由官方 SWE-bench harness 评分。

$($payloadText.Substring(0, $middle))

===== PUBLIC ISSUE EVIDENCE (MUST RETAIN AND APPLY) =====
$($case.problem_statement)
===== END PUBLIC ISSUE EVIDENCE =====

$($payloadText.Substring($middle))

现在完成该公开 Issue。不要只复述证据；恢复必要上下文、修改项目、验证实现并给出结论。
"@
  [IO.File]::WriteAllText((Join-Path $caseRoot 'task.txt'), $task, [Text.UTF8Encoding]::new($false))
  $taskHash = (Get-FileHash -LiteralPath (Join-Path $caseRoot 'task.txt') -Algorithm SHA256).Hash
  $row = [ordered]@{
    schema_version=1; protocol='swebench-256k-microcompact-retention';
    instance_id=$case.instance_id; repo=$case.repo; base_commit=$case.base_commit;
    prompt_source='official problem_statement embedded at message midpoint plus base-commit public Java source; gold patch excluded';
    target_payload_chars=$TargetPayloadChars; issue_offset_chars=$middle;
    continuation_rounds=1; included_source_files=$included;
    protocol_files=@([ordered]@{path="$($case.instance_id)/task.txt"; sha256=$taskHash});
    official_dataset_sha256=(Get-FileHash -LiteralPath $OfficialDataset -Algorithm SHA256).Hash
  }
  [IO.File]::WriteAllText($manifest, ($row | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
  Write-Output "Prepared one-turn public microcompact retention prompt: $manifest"
  exit 0
}

$task = @"
这是 SWE-bench Multilingual Java 的公开任务。当前只进行压缩前调查，不允许修改文件，不允许运行命令。
阅读并记住需求，输出一行 EVIDENCE_BASELINE JSON，至少包含 requirements、failure_mode、required_behavior、non_regression_constraints。不要开始实现。

公开 Issue：
$($case.problem_statement)
"@
[IO.File]::WriteAllText((Join-Path $caseRoot 'task.txt'), $task, [Text.UTF8Encoding]::new($false))

for ($index = 0; $index -lt $chunks; $index++) {
  $start = $index * $ChunkChars
  $length = [Math]::Min($ChunkChars, $payloadText.Length - $start)
  $chunk = $payloadText.Substring($start, $length)
  $turn = @"
压缩前公开源码证据分片 $($index + 1)/$chunks。只吸收与原 Issue 有关的信息，不使用工具、不修改文件、不运行命令；回复不超过 120 个汉字，只记录新增的关键事实。

$chunk
"@
  $name = 'turn-{0:D3}.txt' -f ($index + 2)
  [IO.File]::WriteAllText((Join-Path $turnRoot $name), $turn, [Text.UTF8Encoding]::new($false))
}

$finalRound = $chunks + 2
$final = @"
现在进入压缩后的执行阶段。首先在使用任何工具之前输出一行 RETENTION_CHECK JSON，字段必须包含 requirements、failure_mode、required_behavior、non_regression_constraints；内容只能依据此前会话，不能先重新读取证据。随后通过 DevCLI 工具检查工作区、实现原始 Issue、补充或运行相关测试。最终修改将由官方 SWE-bench harness 评分。
"@
[IO.File]::WriteAllText((Join-Path $turnRoot ('turn-{0:D3}.txt' -f $finalRound)),
  $final, [Text.UTF8Encoding]::new($false))

$protocolFiles = @((Join-Path $caseRoot 'task.txt')) + @(
  Get-ChildItem -LiteralPath $turnRoot -File -Filter '*.txt' | Sort-Object Name | ForEach-Object FullName)
$fileRows = @($protocolFiles | ForEach-Object {
  [ordered]@{path=[IO.Path]::GetRelativePath($OutputRoot, $_).Replace('\','/');
    sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash}
})
$row = [ordered]@{
  schema_version=1; protocol='swebench-256k-post-compaction-retention';
  instance_id=$case.instance_id; repo=$case.repo; base_commit=$case.base_commit;
  prompt_source='official problem_statement plus base-commit public Java source; gold patch excluded';
  target_payload_chars=$TargetPayloadChars; chunk_chars=$ChunkChars;
  evidence_chunks=$chunks; continuation_rounds=$chunks + 2;
  included_source_files=$included; protocol_files=$fileRows;
  official_dataset_sha256=(Get-FileHash -LiteralPath $OfficialDataset -Algorithm SHA256).Hash
}
[IO.File]::WriteAllText($manifest, ($row | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
Write-Output "Prepared $($row.continuation_rounds) public retention rounds: $manifest"
