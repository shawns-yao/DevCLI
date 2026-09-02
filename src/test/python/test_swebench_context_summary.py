import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "summary", Path(__file__).resolve().parents[3] / "scripts" / "summarize-swebench-context.py")
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class ContextSummaryTest(unittest.TestCase):
    def test_compact_percentages_separate_unexposed_tasks_from_compression_effect(self):
        metrics = {"official_resolved": True, "f2p_success": 2, "f2p_total": 2,
                   "p2p_success": 3, "p2p_total": 3, "compaction_successes": 0}
        result = summary.compact_percentages([metrics])
        self.assertEqual(result["resolved_percent"], 100)
        self.assertEqual(result["f2p_percent"], 100)
        self.assertEqual(result["p2p_retention_percent"], 100)
        self.assertEqual(result["semantic_compaction_exposure_percent"], 0)
        self.assertIsNone(result["post_compaction_resolved_percent"])
        self.assertIsNone(result["quality_retention_vs_raw"])

    def test_compact_percentages_use_only_exposed_samples_for_post_compaction_quality(self):
        unexposed = {"official_resolved": True, "f2p_success": 2, "f2p_total": 2,
                     "p2p_success": 3, "p2p_total": 3, "compaction_successes": 0}
        exposed = {**unexposed, "official_resolved": False,
                   "f2p_success": 1, "p2p_success": 2, "compaction_successes": 1}
        result = summary.compact_percentages([unexposed, exposed])
        self.assertEqual(result["resolved_percent"], 50)
        self.assertEqual(result["semantic_compaction_exposure_percent"], 50)
        self.assertEqual(result["post_compaction_resolved_percent"], 0)
        self.assertEqual(result["post_compaction_f2p_percent"], 50)
        self.assertAlmostEqual(result["post_compaction_p2p_retention_percent"], 200 / 3)

    def test_empty_compact_denominator_is_not_reported_as_zero_percent(self):
        result = summary.compact_percentages([])
        self.assertIsNone(result["resolved_percent"])
        self.assertIsNone(result["semantic_compaction_exposure_percent"])

    def test_micro_cleanup_is_not_counted_as_semantic_compaction(self):
        row = {"official_resolved": True, "f2p_success": 1, "f2p_total": 1,
               "p2p_success": 2, "p2p_total": 2, "compaction_successes": 0,
               "micro_cleanup_events": [{"cleared_tool_results": 3}]}
        result = summary.compact_percentages([row])
        self.assertEqual(result.get("micro_cleanup_exposure_percent"), 100)
        self.assertEqual(result.get("post_micro_cleanup_resolved_percent"), 100)
        self.assertEqual(result["semantic_compaction_exposure_percent"], 0)
        self.assertIsNone(result["post_compaction_resolved_percent"])

    def test_compact_report_requires_original_question_and_same_session(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "case" / "solo-compact"
            directory.mkdir(parents=True)
            (root / "java-43-official-dataset.json").write_text(json.dumps([
                {"instance_id": "case", "problem_statement": "Original issue", "base_commit": "abc"}]), encoding="utf-8")
            usage = {"test_type": "public", "actual_model": "gpt-5.6-luna", "provider": "openai",
                     "mode": "solo", "context_mode": "compact", "compression_trigger_tokens": 64000,
                     "conversation_protocol": "original-task-qa", "continuation_rounds": 8,
                     "initial_prompt_source": "official_problem_statement", "base_commit": "abc",
                     "summary_usage_scope": "compactor_and_presummary", "valid_sample": True,
                     "total_input_tokens": 900, "total_output_tokens": 100,
                     "total_cached_input_tokens": 0, "agent_wall_ms": 10, "compaction_successes": 0}
            (directory / "result.json").write_text(json.dumps(usage), encoding="utf-8")
            turns = [{"round": n, "session_id": "same", "question": "Original issue" if n == 1 else "Follow-up?",
                      "answer": "Actual answer", "history_tokens": n * 100, "trigger_tokens": 255182}
                     for n in range(1, 9)]
            transcript = directory / "conversation.jsonl"
            transcript.write_text("\n".join(json.dumps(t) for t in turns), encoding="utf-8")
            (directory / "agent.log").write_text("", encoding="utf-8")
            grading = root / "official-batch"
            grading.mkdir()
            (grading / "devcli-solo-compact-gpt-5.6-luna.paired-solo-compact-gpt-5.6-luna.json").write_text(
                json.dumps({"empty_patch_ids": ["case"]}), encoding="utf-8")
            result = summary.summarize_compact(root, ["case"], "gpt-5.6-luna")
            self.assertEqual(result["core_scored_samples"], 1)
            self.assertEqual(result["metrics"]["resolved_percent"], 0)
            self.assertIsNone(result["metrics"]["post_compaction_resolved_percent"])
            turns[-1]["session_id"] = "restarted"
            transcript.write_text("\n".join(json.dumps(t) for t in turns), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "same-session contract"):
                summary.summarize_compact(root, ["case"], "gpt-5.6-luna")
            turns[-1]["session_id"] = "same"
            turns[0]["question"] = "Unrelated large text"
            transcript.write_text("\n".join(json.dumps(t) for t in turns), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Original task"):
                summary.summarize_compact(root, ["case"], "gpt-5.6-luna")

    def test_includes_summary_tokens_and_uses_official_test_lists(self):
        usage = {"total_input_tokens": 900, "total_output_tokens": 100,
                 "total_cached_input_tokens": 200, "agent_wall_ms": 10}
        report = {"resolved": True, "tests_status": {
            "FAIL_TO_PASS": {"success": ["a"], "failure": []},
            "PASS_TO_PASS": {"success": ["b"], "failure": ["c"]}}}
        row = summary.condition_metrics(usage, report)
        self.assertEqual(row.get("total_tokens"), 1000)
        self.assertEqual(row["net_fixed_tests"], 0)
        self.assertEqual(row["p2p_retention"], 0.5)
        self.assertTrue(row["official_resolved"])

    def test_quality_retention_is_undefined_when_raw_quality_is_zero(self):
        raw = {"total_tokens": 20000, "f2p_rate": 0, "f2p_success": 0,
               "net_fixed_tests": 0, "wall_ms": 2}
        compact = {"total_tokens": 10000, "f2p_rate": 1, "f2p_success": 1,
                   "net_fixed_tests": 1, "wall_ms": 3}
        delta = summary.paired_metrics(raw, compact)
        self.assertEqual(delta.get("token_reduction"), 0.5)
        self.assertIsNone(delta["f2p_quality_retention"])
        self.assertEqual(delta["net_fixed_tests_delta"], 1)

    def test_no_savings_does_not_claim_quality_loss_per_saved_tokens(self):
        raw = {"total_tokens": 1000, "f2p_rate": 1, "f2p_success": 1,
               "net_fixed_tests": 1, "wall_ms": 2}
        compact = {**raw, "total_tokens": 1200}
        delta = summary.paired_metrics(raw, compact)
        self.assertIsNotNone(delta.get("token_reduction"))
        self.assertAlmostEqual(delta["token_reduction"], -0.2)
        self.assertIsNone(delta["f2p_loss_per_10k_saved_tokens"])

    def test_external_failure_is_excluded_without_inventing_an_official_score(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            case = root / "case" / "solo-raw"
            case.mkdir(parents=True)
            (case / "result.json").write_text(json.dumps({
                "external_failure": True, "valid_sample": False,
                "failure_class": "external_failure"}), encoding="utf-8")
            result = summary.summarize(root, ["case"], "gpt-5.6-luna")
            self.assertEqual(result["original_pairs"], 1)
            self.assertEqual(result["valid_scored_pairs"], 0)
            self.assertEqual(result["excluded_or_pending_conditions"][0]["reason"], "external_failure")
            self.assertEqual(result["pairs"], [])

    def test_official_daemon_outage_is_reported_as_external_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "official-status.json").write_text(json.dumps({
                "status": "blocked_external", "reason": "docker_daemon_unavailable"
            }), encoding="utf-8")
            self.assertEqual(
                summary.official_pending_reason(root),
                {"reason": "external_failure", "detail": "docker_daemon_unavailable"})

    def test_official_empty_patch_is_unresolved_not_external_and_test_counts_unknown(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            grading = root / "official-batch"
            grading.mkdir()
            overall = grading / "devcli-solo-raw-gpt-5.6-luna.paired-solo-raw-gpt-5.6-luna.json"
            overall.write_text(json.dumps({"empty_patch_ids": ["case"]}), encoding="utf-8")
            report = summary.official_report(root, "case", "solo-raw", "gpt-5.6-luna")
            row = summary.condition_metrics({"total_input_tokens": 100, "total_output_tokens": 20,
                                             "total_cached_input_tokens": 0, "agent_wall_ms": 1}, report)
            self.assertFalse(row["official_resolved"])
            self.assertIsNone(row["net_fixed_tests"])
            self.assertIsNone(row["f2p_rate"])


if __name__ == "__main__":
    unittest.main()
