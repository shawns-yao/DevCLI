import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


def load(name):
    script = Path(__file__).resolve().parents[3] / "scripts" / (name + ".py")
    spec = importlib.util.spec_from_file_location(name, script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PairedMetricsTest(unittest.TestCase):
    def test_context_subset_keeps_frozen_order_and_records_model(self):
        module = load("paired-context-benchmark")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            harness, data = root / "harness", root / "data"
            (harness / "config").mkdir(parents=True)
            data.mkdir()
            for name in ("eval.py", "metrics.py"):
                (harness / name).write_text("# fixture", encoding="utf-8")
            (harness / "config/dataset2prompt.json").write_text(
                json.dumps({task: "{context}{input}" for task in ("first", "qmsum")}), encoding="utf-8")
            rows = [{"context": "evidence", "input": "question", "answers": ["answer"], "length": 20}]
            for task in ("first", "qmsum"):
                (data / f"{task}.jsonl").write_text(json.dumps(rows[0]) + "\n", encoding="utf-8")
            with patch.multiple(module, ROOT=root, HARNESS=harness, DATA=data,
                                QUOTAS={"first": 1, "qmsum": 1}), patch.object(module, "source_hash", return_value="fixture"):
                full, subset = root / "full", root / "subset"
                module.prepare(full)
                module.prepare(subset, ["qmsum"], "gpt-5.6-terra")
                all_jobs = [json.loads(line) for line in (full / "jobs.jsonl").read_text().splitlines()]
                job = json.loads((subset / "jobs.jsonl").read_text())
                self.assertEqual(job["id"], all_jobs[1]["id"])
                self.assertEqual(job["order"], all_jobs[1]["order"])
                self.assertEqual(job["configuration"]["model"], "gpt-5.6-terra")
                module.prepare(subset, ["qmsum"], "gpt-5.6-terra")
                with self.assertRaises(ValueError):
                    module.prepare(subset, ["qmsum"], "gpt-5.6-luna")

    def test_partial_recall_precision_and_mrr_are_distinct(self):
        result = load("paired-memory-benchmark").retrieval_metrics(["noise", "a"], ["a", "b"])
        self.assertEqual(result["recall_at_5"], .5)
        self.assertEqual(result["precision_at_5"], .2)
        self.assertEqual(result["mrr_at_5"], .5)
        self.assertEqual(result["all_evidence"], 0)

    def test_missing_gold_is_not_perfect_recall(self):
        self.assertIsNone(load("paired-memory-benchmark").retrieval_metrics([], []))

    def test_duplicate_session_ids_do_not_inflate_recall(self):
        result = load("paired-memory-benchmark").retrieval_metrics(["a", "a", "a"], ["a", "b"])
        self.assertEqual(result["recall_at_5"], .5)
        self.assertEqual(result["returned_sessions"], 1)

    def test_zero_baseline_has_no_quality_retention_ratio(self):
        self.assertIsNone(load("paired-context-benchmark").ratio(5, 0))

    def test_unknown_judge_not_converted_to_false(self):
        rows = [{"id": "unknown", "recency": {"judge": {"label": False}},
                 "memory": {"judge": {"label": None}}},
                {"id": "win", "recency": {"judge": {"label": False}},
                 "memory": {"judge": {"label": True}}}]
        result = load("analyze-paired-memory").contrast(rows)
        self.assertEqual(result["pairs"], 1)
        self.assertEqual(result["wins"], 1)
        self.assertEqual(result["excluded_ids"], ["unknown"])

    def test_reader_failures_cannot_become_functional_gain(self):
        def arm(status, label):
            return {"answer": {"status": status, "usage_complete": status == "ok", "wall_ms": 1,
                               "input_tokens": 10, "output_tokens": 2},
                    "judge": {"status": "ok" if status == "ok" else "reader_failed", "label": label},
                    "injected_ids": ["evidence"]}
        rows = [{"id": "error", "question_type": "qa", "abstention": False, "relevant": ["evidence"],
                 "recency": arm("error", False), "memory": arm("ok", True)},
                {"id": "tie", "question_type": "qa", "abstention": False, "relevant": ["evidence"],
                 "recency": arm("ok", True), "memory": arm("ok", True)}]
        report = load("analyze-paired-memory").summarize(rows)
        self.assertEqual(report["labelled_pairs"]["quality_delta_pp"], 50)
        self.assertEqual(report["complete_reader_pairs_diagnostic"]["quality_delta_pp"], 0)
        self.assertEqual(report["complete_reader_pairs_diagnostic"]["pairs"], 1)

    def test_official_ruler_all_metric_is_fractional_not_binary(self):
        normalize, metric = load("score-paired-ruler").official_functions()
        self.assertEqual(normalize("  x\x00y  ", {}), "x\ny")
        self.assertEqual(metric(["a"], [["a", "b"]]), 50)


if __name__ == "__main__":
    unittest.main()
