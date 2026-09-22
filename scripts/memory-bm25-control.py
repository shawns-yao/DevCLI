"""A stronger, fixed BM25 retrieval control; no extra reader/model calls."""
import importlib.util
import json
from pathlib import Path

import numpy as np
from rank_bm25 import BM25Okapi

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "Test/paired-memory/luna-v1"
spec = importlib.util.spec_from_file_location("memory_metrics", Path(__file__).with_name("paired-memory-benchmark.py"))
metrics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics)
references = {r["question_id"]: r for r in json.loads((OUT / "gold.json").read_text(encoding="utf-8"))}
pairs = []
with (OUT / "jobs.jsonl").open(encoding="utf-8") as stream:
    for line in stream:
        job = json.loads(line)
        gold = references[job["id"]]
        if "_abs" in job["id"] or not gold["answer_session_ids"]:
            continue
        # Same BM25Okapi defaults / whitespace tokenization as official run_retrieval.py flat-bm25.
        # Use the identical imported corpus seen by DevCLI, including dated session headers.
        corpus = [session["content"].split(" ") for session in job["sessions"]]
        scores = BM25Okapi(corpus).get_scores(job["question"].split(" "))
        ranked = np.argsort(scores)[::-1][:5]
        ids = [job["sessions"][int(i)]["id"] for i in ranked]
        project = json.loads((OUT / "results" / job["id"] / "retrieval.json").read_text(encoding="utf-8"))
        pairs.append({"id": job["id"], "bm25_ids": ids,
                      "bm25": metrics.retrieval_metrics(ids, gold["answer_session_ids"]),
                      "devcli": metrics.retrieval_metrics(project["ranked_ids"], gold["answer_session_ids"])})
summary = {"eligible_cases": len(pairs), "rank_bm25_version": "0.2.2", "scoring_unit": "official answer_session_ids",
           "reader_not_run_for_bm25": True, "conditions": {}}
for condition in ("bm25", "devcli"):
    summary["conditions"][condition] = {key: sum(p[condition][key] for p in pairs) / len(pairs)
                                       for key in ("recall_at_5", "precision_at_5", "mrr_at_5", "all_evidence")}
summary["delta_devcli_minus_bm25"] = {key: summary["conditions"]["devcli"][key] - value
                                      for key, value in summary["conditions"]["bm25"].items()}
metrics.write(OUT / "bm25-pairs.json", pairs)
metrics.write(OUT / "bm25-summary.json", summary)
print(json.dumps(summary, indent=2))
