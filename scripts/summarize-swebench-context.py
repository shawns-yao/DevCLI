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
                excluded.append({"instance_id": instance_id, "condition": condition, "reason": "official_report_missing_or_error"})
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
    args = parser.parse_args()
    result = summarize(args.run_root, args.instance_ids, args.model)
    text = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)
    if args.output:
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
