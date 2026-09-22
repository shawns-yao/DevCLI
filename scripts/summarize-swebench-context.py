"""Aggregate official reports; never infer resolved from agent logs or test output."""
import argparse
import json
import re
from pathlib import Path


def condition_metrics(usage, report):
    tests = report.get("tests_status")
    f2p = tests["FAIL_TO_PASS"] if tests else None
    p2p = tests["PASS_TO_PASS"] if tests else None
    f2p_ok = len(f2p["success"]) if f2p else None
    p2p_bad = len(p2p["failure"]) if p2p else None
    f2p_total = len(f2p["success"]) + len(f2p["failure"]) if f2p else 0
    p2p_total = len(p2p["success"]) + len(p2p["failure"]) if p2p else 0
    return {
        "official_resolved": report["resolved"],
        "f2p_success": f2p_ok,
        "f2p_total": f2p_total if tests else None,
        "f2p_rate": f2p_ok / f2p_total if f2p_total else None,
        "p2p_success": len(p2p["success"]) if p2p else None,
        "p2p_total": p2p_total if tests else None,
        "p2p_retention": len(p2p["success"]) / p2p_total if p2p_total else None,
        "net_fixed_tests": f2p_ok - p2p_bad if tests else None,
        "total_tokens": usage["total_input_tokens"] + usage["total_output_tokens"],
        "input_tokens": usage["total_input_tokens"],
        "output_tokens": usage["total_output_tokens"],
        "cached_input_tokens": usage["total_cached_input_tokens"],
        "summary_input_tokens": usage.get("summary_input_tokens"),
        "summary_output_tokens": usage.get("summary_output_tokens"),
        "summary_cached_input_tokens": usage.get("summary_cached_input_tokens"),
        "summary_calls": usage.get("summary_calls"),
        "compaction_triggers": usage.get("compaction_triggers"),
        "compaction_successes": usage.get("compaction_successes"),
        "compaction_fallbacks": usage.get("compaction_fallbacks"),
        "peak_history_tokens": usage.get("peak_history_tokens"),
        "compaction_before_tokens": usage.get("compaction_before_tokens"),
        "compaction_after_tokens": usage.get("compaction_after_tokens"),
        "compaction_tail_budget_tokens": usage.get("compaction_tail_budget_tokens"),
        "compaction_summary_input_budget_tokens": usage.get("compaction_summary_input_budget_tokens"),
        "compaction_summary_tokens": usage.get("compaction_summary_tokens"),
        "compaction_retained_tail_tokens": usage.get("compaction_retained_tail_tokens"),
        "post_compaction_history_tokens": usage.get("post_compaction_history_tokens"),
        "context_limit_errors": usage.get("context_limit_errors"),
        "wall_ms": usage["agent_wall_ms"],
    }


def paired_metrics(raw, compact):
    saved = raw["total_tokens"] - compact["total_tokens"]
    complete = raw["net_fixed_tests"] is not None and compact["net_fixed_tests"] is not None
    return {
        "token_reduction": saved / raw["total_tokens"] if raw["total_tokens"] else None,
        "tokens_saved": saved,
        "f2p_quality_retention": compact["f2p_rate"] / raw["f2p_rate"]
        if raw["f2p_rate"] and compact["f2p_rate"] is not None else None,
        "net_fixed_tests_delta": compact["net_fixed_tests"] - raw["net_fixed_tests"] if complete else None,
        "f2p_loss_per_10k_saved_tokens": (raw["f2p_success"] - compact["f2p_success"]) * 10000 / saved
        if saved > 0 and complete else None,
        "wall_ms_delta": compact["wall_ms"] - raw["wall_ms"],
    }


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def official_pending_reason(root):
    """Classify a missing report without turning an evaluator outage into a score."""
    status_path = root / "official-status.json"
    if status_path.exists():
        status = read_json(status_path)
        if status.get("status") == "blocked_external":
            return {
                "reason": "external_failure",
                "detail": status.get("reason") or status.get("status"),
            }
    return {"reason": "official_report_missing_or_error"}


def compact_percentages(rows):
    def percent(numerator, denominator):
        return 100 * numerator / denominator if denominator else None

    def test_rate(samples, prefix):
        known = [r for r in samples if r.get(f"{prefix}_total") is not None]
        return percent(sum(r[f"{prefix}_success"] for r in known),
                       sum(r[f"{prefix}_total"] for r in known))

    exposed = [r for r in rows if r.get("compaction_successes", 0) > 0]
    micro_exposed = [r for r in rows if r.get("micro_cleanup_events")]
    return {
        "resolved_percent": percent(sum(r["official_resolved"] for r in rows), len(rows)),
        "f2p_percent": test_rate(rows, "f2p"),
        "p2p_retention_percent": test_rate(rows, "p2p"),
        "semantic_compaction_exposure_percent": percent(len(exposed), len(rows)),
        "micro_cleanup_exposure_percent": percent(len(micro_exposed), len(rows)),
        "post_micro_cleanup_resolved_percent": percent(
            sum(r["official_resolved"] for r in micro_exposed), len(micro_exposed)),
        "post_compaction_sample_count": len(exposed),
        "post_compaction_resolved_percent": percent(sum(r["official_resolved"] for r in exposed), len(exposed)),
        "post_compaction_f2p_percent": test_rate(exposed, "f2p"),
        "post_compaction_p2p_retention_percent": test_rate(exposed, "p2p"),
        "quality_retention_vs_raw": None,
    }


def summarize_compact(root, instance_ids, model):
    rows, excluded = [], []
    dataset = {r["instance_id"]: r for r in read_json(root / "java-43-official-dataset.json")}
    for instance_id in instance_ids:
        directory = root / instance_id / "solo-compact"
        result_path = directory / "result.json"
        if not result_path.exists():
            excluded.append({"instance_id": instance_id, "reason": "generation_missing"})
            continue
        usage = read_json(result_path)
        if usage.get("external_failure") or not usage.get("valid_sample"):
            excluded.append({"instance_id": instance_id,
                             "reason": usage.get("failure_class") or "invalid_generation"})
            continue
        expected = {"test_type": "public", "actual_model": model, "provider": "openai",
                    "mode": "solo", "context_mode": "compact", "compression_trigger_tokens": 64000,
                    "conversation_protocol": "original-task-qa",
                    "initial_prompt_source": "official_problem_statement"}
        if usage.get("summary_usage_scope") not in {"compactor_only", "compactor_and_presummary"}:
            raise ValueError(f"Unqualified summary usage scope: {instance_id}")
        if "pre_summary_enabled" in usage and usage.get("pre_summary_enabled"):
            raise ValueError(f"Pre-summary must be disabled for compact-only: {instance_id}")
        if any(usage.get(k) != v for k, v in expected.items()):
            raise ValueError(f"Unqualified compact-only provenance: {instance_id}")
        turns = [json.loads(line) for line in (directory / "conversation.jsonl").read_text(encoding="utf-8").splitlines()]
        original = dataset[instance_id]["problem_statement"]
        if (len(turns) != usage["continuation_rounds"] or len({t["session_id"] for t in turns}) != 1
                or [t["round"] for t in turns] != list(range(1, usage["continuation_rounds"] + 1))
                or turns[0]["question"] != original
                or usage["base_commit"] != dataset[instance_id]["base_commit"]
                or any(len(t["question"]) >= 600 for t in turns[1:])):
            raise ValueError(f"Original task / short QA / same-session contract broken: {instance_id}")
        report = official_report(root, instance_id, "solo-compact", model)
        if report is None:
            pending = official_pending_reason(root)
            excluded.append({"instance_id": instance_id, **pending})
            continue
        row = {"instance_id": instance_id, "base_commit": usage["base_commit"],
               "source_sha256": usage.get("source_sha256"), "dataset_sha256": usage.get("dataset_sha256"),
               **condition_metrics(usage, report)}
        log = (directory / "agent.log").read_text(encoding="utf-8-sig")
        row["history_by_round"] = [{k: t[k] for k in ("round", "history_tokens", "trigger_tokens")} for t in turns]
        row["session_id"] = turns[0]["session_id"]
        row["history_compactions"] = [
            {"before_tokens": int(before), "after_tokens": int(after)}
            for before, after in re.findall(
                r"(?m)^\[context-compaction\] kind=history [^\r\n]*beforeTokens=(\d+) afterTokens=(\d+)", log)]
        micro = re.findall(r"(?m)^\[context-compaction\] kind=micro beforeTokens=(\d+) afterTokens=(\d+) "
                           r"clearedToolResults=(\d+) removedByTool=(\{[^\r\n]*?\}) "
                           r"roleBefore=(\{[^\r\n]*?\}) roleAfter=(\{[^\r\n]*?\})", log)
        row["micro_cleanup_events"] = []
        for before, after, cleared, tools, roles_before, roles_after in micro:
            if int(cleared) == 0:
                continue
            parse_map = lambda value: {k: int(v) for k, v in re.findall(r"([\w.-]+)=(\d+)", value)}
            row["micro_cleanup_events"].append({
                "before_tokens": int(before), "after_tokens": int(after), "cleared_tool_results": int(cleared),
                "removed_by_tool": parse_map(tools), "role_tokens_before": parse_map(roles_before),
                "role_tokens_after": parse_map(roles_after)})
        rows.append(row)
    external = sum(e["reason"] == "external_failure" for e in excluded)
    return {
        "test_type": "public_authoritative_dataset", "protocol": "original-task-qa",
        "quality_source": "official_SWE_bench_harness_reports", "model": model,
        "compression_trigger_tokens": 64000, "original_samples": len(instance_ids),
        "excluded_or_pending": excluded, "external_failures": external, "core_scored_samples": len(rows),
        "warning": "Affected by external failures; reference only" if external > len(instance_ids) * .1 else None,
        "metrics": compact_percentages(rows), "samples": rows,
        "limitations": "No raw control; no causal improvement or raw-relative retention claim. "
                        "Unexposed tasks do not measure semantic compression quality. QA is a fixed multi-turn protocol, not the default single-turn leaderboard.",
    }


def official_report(root, instance_id, condition, model):
    run_id = f"paired-{condition}-{model}"
    model_id = f"devcli-{condition}-{model}"
    grading = root / "official-batch"
    path = grading / "logs/run_evaluation" / run_id / model_id / instance_id / "report.json"
    if path.exists():
        return read_json(path)[instance_id]
    overall = grading / f"{model_id}.{run_id}.json"
    if overall.exists() and instance_id in read_json(overall)["empty_patch_ids"]:
        # Empty patches are official non-resolutions, not external failures. Test counts are unknown.
        return {"resolved": False, "empty_patch": True}
    return None


def summarize(root, instance_ids, model):
    pairs, excluded = [], []
    for instance_id in instance_ids:
        usages, metrics = {}, {}
        for context in ("raw", "compact"):
            condition = f"solo-{context}"
            result_file = root / instance_id / condition / "result.json"
            if not result_file.exists():
                excluded.append({"instance_id": instance_id, "condition": condition, "reason": "generation_missing"})
                continue
            usage = read_json(result_file)
            if usage.get("external_failure") or not usage.get("valid_sample"):
                excluded.append({"instance_id": instance_id, "condition": condition,
                                 "reason": usage.get("failure_class") or "invalid_generation"})
                continue
            if (usage.get("test_type") != "public" or usage.get("actual_model") != model
                    or usage.get("summary_usage_scope") != "compactor_and_presummary"):
                raise ValueError(f"Unqualified experiment provenance: {instance_id}/{condition}")
            usages[context] = usage
            report = official_report(root, instance_id, condition, model)
            if report is None:
                pending = official_pending_reason(root)
                excluded.append({"instance_id": instance_id, "condition": condition, **pending})
                continue
            metrics[context] = condition_metrics(usage, report)
            log = (result_file.parent / "agent.log").read_text(encoding="utf-8-sig")
            metrics[context]["history_by_round"] = [
                {"round": int(round_id), "history_tokens": int(tokens), "trigger_tokens": int(trigger)}
                for round_id, tokens, trigger in re.findall(
                    r"(?m)^\[driver\] context round=(\d+) historyTokens=(\d+) triggerTokens=(\d+)", log)]
            metrics[context]["history_compactions"] = [
                {"before_tokens": int(before), "after_tokens": int(after)}
                for before, after in re.findall(
                    r"(?m)^\[context-compaction\] kind=history [^\r\n]*beforeTokens=(\d+) afterTokens=(\d+)", log)]
        if len(usages) == 2:
            for field in ("dataset_sha256", "source_sha256", "base_commit", "requested_model", "provider",
                          "continuation_rounds", "token_budget", "max_iterations", "max_output_tokens",
                          "compression_trigger_tokens"):
                if usages["raw"].get(field) != usages["compact"].get(field) or usages["raw"].get(field) is None:
                    raise ValueError(f"A/B mismatch: {instance_id} {field}")
        if len(metrics) == 2:
            pairs.append({"instance_id": instance_id, **metrics,
                          "compression_exposed": usages["compact"]["compaction_successes"] > 0,
                          "delta": paired_metrics(metrics["raw"], metrics["compact"])})
    return {"quality_source": "official_SWE_bench_harness_reports",
            "token_scope": "agent_plus_compactor_plus_presummary_input_and_output",
            "quality_retention_definition": "compact_official_F2P_rate / raw_official_F2P_rate; null if raw is zero",
            "original_pairs": len(instance_ids), "valid_scored_pairs": len(pairs),
            "excluded_or_pending_pairs": len(instance_ids) - len(pairs),
            "excluded_or_pending_conditions": excluded,
            "compression_exposed_pairs": sum(p["compression_exposed"] for p in pairs),
            "pairs": pairs}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-root", type=Path, required=True)
    parser.add_argument("--instance-ids", nargs="+", required=True)
    parser.add_argument("--model", default="gpt-5.6-luna")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--compact-only", action="store_true")
    args = parser.parse_args()
    result = (summarize_compact if args.compact_only else summarize)(args.run_root, args.instance_ids, args.model)
    text = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)
    if args.output:
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
