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
