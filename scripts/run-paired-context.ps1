[CmdletBinding()]
param(
    [ValidateSet('longbench','ruler')][string]$Suite = 'longbench',
    [string]$Output = 'Test/paired-context/luna-v1',
    [string]$Model = 'gpt-5.6-luna',
    [string[]]$Tasks = @(),
    [string]$Python = 'Temp/swebench-venv/Scripts/python.exe',
    [string]$ClasspathFile = 'Test/swebench-multilingual-java/gpt-5.6-luna-single-round/dependency-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$outputPath = [IO.Path]::GetFullPath((Join-Path $root $Output))
$pythonPath = (Resolve-Path (Join-Path $root $Python)).Path
$deps = (Get-Content -Raw (Join-Path $root $ClasspathFile)).Trim()
$driverClasses = Join-Path $root 'target/benchmark-classes'
$cp = "$root/target/classes;$driverClasses;$deps"
if (-not (Test-Path "$root/target/classes/com/devcli/memory/ConversationHistoryCompactor.class")) {
    throw 'Compile production classes before running the paired benchmark'
}
New-Item -ItemType Directory -Force -Path $driverClasses | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp "$root/target/classes;$deps" -d $driverClasses `
    "$root/benchmarks/src/main/java/com/devcli/eval/PairedContextDriver.java"
if ($LASTEXITCODE -ne 0) { throw 'Public benchmark driver compilation failed' }
$scorer = if ($Suite -eq 'ruler') { 'score-paired-ruler.py' } else { 'paired-context-benchmark.py' }
$prepareArgs = @('prepare', '--out', $outputPath)
if ($Suite -eq 'longbench') {
    $prepareArgs += @('--model', $Model)
    if ($Tasks.Count -gt 0) { $prepareArgs += @('--tasks') + $Tasks }
}
& $pythonPath "$PSScriptRoot/$scorer" @prepareArgs
if ($LASTEXITCODE -ne 0) { throw 'Frozen manifest validation failed' }
$lock = [IO.File]::Open((Join-Path $outputPath 'runner.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
    $lines = @(Get-Content -LiteralPath "$outputPath/jobs.jsonl")
    # Per-case JVM plus a pause also bounds short, non-compacting cases. The original
    # CountingClient throttles within one condition, not across condition instances.
    foreach ($line in $lines) {
        $job = $line | ConvertFrom-Json
        $caseRoot = Join-Path $outputPath "results/$($job.id)"
        $pending = @('raw','compact') | Where-Object {
            -not (Test-Path "$caseRoot/$_/result.json") -and -not (Test-Path "$caseRoot/$_/started.json")
        }
        if (@($pending).Count -eq 0) { continue }
        $shard = Join-Path $outputPath 'serial-job.jsonl'
        $line | Set-Content -LiteralPath $shard -Encoding utf8NoBOM
        & "$env:JAVA_HOME/bin/java.exe" '-Ddevcli.llm.http.protocol=HTTP_1_1' -cp $cp `
            com.devcli.eval.PairedContextDriver $shard "$outputPath/results" $Model
        if ($LASTEXITCODE -ne 0) { throw "Reader failed for $($job.id)" }
        Start-Sleep -Seconds 4
    }
    & $pythonPath "$PSScriptRoot/$scorer" score --out $outputPath
    if ($LASTEXITCODE -ne 0) { throw 'Official evaluation failed' }
} finally {
    $lock.Dispose()
}
