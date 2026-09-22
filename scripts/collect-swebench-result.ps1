[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Log,
    [Parameter(Mandatory)][string]$Model,
    [Parameter(Mandatory)][string]$Mode,
    [Parameter(Mandatory)][string]$ContextMode,
    [int]$ContinuationRounds = 1,
    [int]$DriverExit = 0
)
$ErrorActionPreference = 'Stop'
$text = Get-Content -Raw -LiteralPath $Log -Encoding utf8
$header = [regex]::Match($text, '(?m)^\[driver\] mode=(\S+) contextMode=(\S+)(?: continuationRounds=(\d+))? provider=(\S+) model=(\S+)')
$done = [regex]::Match($text, '(?m)^\[driver\] done[^\r\n]*wallMs=(\d+)')
$usage = [regex]::Match($text, '(?m)^\[driver\] usage inputTokens=(\d+) outputTokens=(\d+) cachedInputTokens=(\d+) estimatedCostCny=([0-9.Ee+-]+)')
$decisions = [regex]::Matches($text, '(?m)^\[context-compaction\] kind=decision enabled=(true|false) historyTokens=(\d+) triggerTokens=(\d+)')
$rounds = @([regex]::Matches($text, '(?m)^\[driver\] context round=(\d+) historyTokens=(\d+) triggerTokens=(\d+)') | ForEach-Object {
    [ordered]@{ round=[int]$_.Groups[1].Value; history_tokens=[int]$_.Groups[2].Value; trigger_tokens=[int]$_.Groups[3].Value }
})
$summaryUsage = [regex]::Matches($text, '(?m)^\[context-compaction\] kind=summary-call inputTokens=(\d+) outputTokens=(\d+) cachedInputTokens=(\d+)')
$compactions = @([regex]::Matches($text, '(?m)^\[context-compaction\] kind=history mode=(\S+) beforeTokens=(\d+) afterTokens=(\d+) triggerTokens=(\d+) tailBudgetTokens=(\d+) summaryInputBudgetTokens=(\d+) summaryTokens=(\d+) retainedTailTokens=(\d+) postCompactionHistoryTokens=(\d+) summaryChars=(\d+)') | ForEach-Object {
    [ordered]@{ mode=$_.Groups[1].Value; before_tokens=[long]$_.Groups[2].Value; after_tokens=[long]$_.Groups[3].Value;
        trigger_tokens=[long]$_.Groups[4].Value; tail_budget_tokens=[long]$_.Groups[5].Value;
        summary_input_budget_tokens=[long]$_.Groups[6].Value; summary_tokens=[long]$_.Groups[7].Value;
        retained_tail_tokens=[long]$_.Groups[8].Value;
        post_compaction_history_tokens=[long]$_.Groups[9].Value; summary_chars=[long]$_.Groups[10].Value }
})
$summaryInput = 0L; $summaryOutput = 0L; $summaryCached = 0L
foreach ($call in $summaryUsage) {
    $summaryInput += [long]$call.Groups[1].Value
    $summaryOutput += [long]$call.Groups[2].Value
    $summaryCached += [long]$call.Groups[3].Value
}
$historySamples = @($decisions | ForEach-Object { [long]$_.Groups[2].Value }) + @($rounds | ForEach-Object { [long]$_.history_tokens })
$actualModel = if ($header.Success) { $header.Groups[5].Value } else { 'unknown' }
$provider = if ($header.Success) { $header.Groups[4].Value } else { 'unknown' }
$actualRounds = if ($header.Groups[3].Success) { [int]$header.Groups[3].Value } else { 1 }
$provenanceValid = $header.Success -and $actualModel -ceq $Model -and $provider -ne 'unknown' `
    -and $header.Groups[1].Value -ceq $Mode -and $header.Groups[2].Value -ceq $ContextMode `
    -and $actualRounds -eq $ContinuationRounds
# New runs expose sanitized failure markers from the production event stream.
# Legacy logs are diagnostic only and may contain normalized provider failures in final text.
$failureMarkers = [regex]::Matches($text, '(?m)^\[(?:driver|context-compaction)\] (?:kind=summary-error|failure) code=(\w+)')
$externalErrors = @($failureMarkers | Where-Object { $_.Groups[1].Value -in @('AUTHENTICATION','RATE_LIMITED','OVERLOADED','TIMEOUT','NETWORK','SERVER_ERROR','MODEL_UNAVAILABLE') }).Count
$contextErrors = @($failureMarkers | Where-Object { $_.Groups[1].Value -eq 'CONTEXT_LENGTH' }).Count
if ($failureMarkers.Count -eq 0) {
    $externalErrors = [regex]::Matches($text, '(?im)^[^\r\n]*(?:LLM request failed:|LLM transport failed:)[^\r\n]*(?:model_not_found|status=5\d\d|code=(?:NETWORK|TIMEOUT|SERVER_ERROR|OVERLOADED|RATE_LIMITED|AUTHENTICATION))[^\r\n]*').Count
    $contextErrors = [regex]::Matches($text, '(?im)^[^\r\n]*LLM request failed:[^\r\n]*code=CONTEXT_LENGTH[^\r\n]*').Count
}
$status = if ($externalErrors -gt 0) { 'external_failure' } elseif (-not $provenanceValid) { 'provenance_failure' } `
    elseif ($DriverExit -ne 0 -or -not $done.Success -or -not $usage.Success) { 'execution_failure' } `
    elseif ($rounds.Count -ne $ContinuationRounds -or $decisions.Count -eq 0) { 'metrics_incomplete' } else { 'ok' }
$agentInput = if ($usage.Success) { [long]$usage.Groups[1].Value } else { $null }
$agentOutput = if ($usage.Success) { [long]$usage.Groups[2].Value } else { $null }
$agentCached = if ($usage.Success) { [long]$usage.Groups[3].Value } else { $null }
[ordered]@{
    actual_model=$actualModel; provider=$provider; provenance_valid=$provenanceValid
    status=$status; valid_sample=($status -eq 'ok'); external_failure_count=$externalErrors
    context_length_errors=$contextErrors
    compaction_decisions=$decisions.Count
    compaction_triggers=[regex]::Matches($text, '(?m)^\[context-compaction\] kind=trigger ').Count
    compaction_fallbacks=[regex]::Matches($text, '(?m)^\[context-compaction\] kind=fallback ').Count
    compaction_successes=[regex]::Matches($text, '(?m)^\[context-compaction\] kind=history ').Count
    compaction_events=$compactions
    compaction_before_tokens=if ($compactions.Count) { (($compactions | ForEach-Object before_tokens) | Measure-Object -Maximum).Maximum } else { $null }
    compaction_after_tokens=if ($compactions.Count) { ($compactions | Select-Object -Last 1).after_tokens } else { $null }
    compaction_tail_budget_tokens=if ($compactions.Count) { ($compactions | Select-Object -Last 1).tail_budget_tokens } else { $null }
    compaction_summary_input_budget_tokens=if ($compactions.Count) { ($compactions | Select-Object -Last 1).summary_input_budget_tokens } else { $null }
    compaction_summary_tokens=if ($compactions.Count) { ($compactions | Select-Object -Last 1).summary_tokens } else { $null }
    compaction_retained_tail_tokens=if ($compactions.Count) { ($compactions | Select-Object -Last 1).retained_tail_tokens } else { $null }
    post_compaction_history_tokens=if ($compactions.Count) { ($compactions | Select-Object -Last 1).post_compaction_history_tokens } else { $null }
    peak_history_tokens=if ($historySamples.Count) { ($historySamples | Measure-Object -Maximum).Maximum } else { $null }
    round_history_tokens=$rounds
    summary_calls=$summaryUsage.Count; summary_input_tokens=$summaryInput; summary_output_tokens=$summaryOutput; summary_cached_input_tokens=$summaryCached
    agent_input_tokens=$agentInput; agent_output_tokens=$agentOutput; agent_cached_input_tokens=$agentCached
    input_tokens=if ($usage.Success) { $agentInput + $summaryInput } else { $null }
    output_tokens=if ($usage.Success) { $agentOutput + $summaryOutput } else { $null }
    cached_input_tokens=if ($usage.Success) { $agentCached + $summaryCached } else { $null }
    agent_estimated_cost_cny=if ($usage.Success) { [double]::Parse($usage.Groups[4].Value, [Globalization.CultureInfo]::InvariantCulture) } else { $null }
    cost_includes_summary=$false
    agent_wall_ms=if ($done.Success) { [long]$done.Groups[1].Value } else { $null }
}
