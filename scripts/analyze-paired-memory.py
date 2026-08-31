"""Offline effect/cost analysis; missing judgements are unknown, never successes."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONDITIONS = ("recency", "memory")


def read(path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def contrast(rows):
    valid = [r for r in rows if all(r[c]["judge"] is not None and
             r[c]["judge"].get("label") is not None for c in CONDITIONS)]
    n = len(valid)
    wins = sum(not r["recency"]["judge"]["label"] and r["memory"]["judge"]["label"] for r in valid)
    losses = sum(r["recency"]["judge"]["label"] and not r["memory"]["judge"]["label"] for r in valid)
    return {"pairs": n, "wins": wins, "losses": losses, "ties": n - wins - losses,
            "quality_delta_pp": 100 * (wins - losses) / n if n else None,
            "both_correct": sum(all(r[c]["judge"]["label"] for c in CONDITIONS) for r in valid),
            "both_incorrect": sum(not any(r[c]["judge"]["label"] for c in CONDITIONS) for r in valid),
            "excluded_ids": [r["id"] for r in rows if r not in valid]}


def percentiles(values):
    values = sorted(values)
    return {"p50": values[math.ceil(len(values) * .5) - 1],
            "p95": values[math.ceil(len(values) * .95) - 1]} if values else None


def summarize(rows):
    result = {"planned": len(rows), "labelled_pairs": contrast(rows), "conditions": {}}
    complete = [r for r in rows if all(r[c]["answer"] is not None and
                r[c]["answer"].get("status") == "ok" and r[c]["judge"] is not None and
                r[c]["judge"].get("status") == "ok" for c in CONDITIONS)]
    result["complete_reader_pairs_diagnostic"] = {**contrast(complete),
            "excluded_ids": [r["id"] for r in rows if r not in complete],
            "not_a_replacement_for_full_denominator": True}
    answerable = [r for r in complete if not r["abstention"]]
    result["complete_answerable_pairs_diagnostic"] = {
            **contrast(answerable),
            "not_a_replacement_for_full_denominator": True}
    cost_pairs = [r for r in complete if all(r[c]["answer"].get("usage_complete") for c in CONDITIONS)]
    cost = {c: {key: sum(r[c]["answer"].get(key, 0) for r in cost_pairs)
                  for key in ("input_tokens", "output_tokens", "cached_tokens", "wall_ms")} for c in CONDITIONS}
    raw_total = cost["recency"]["input_tokens"] + cost["recency"]["output_tokens"]
    mem_total = cost["memory"]["input_tokens"] + cost["memory"]["output_tokens"]
    result["matched_reader_cost"] = {"pairs": len(cost_pairs), "totals": cost,
            "total_token_reduction": 1 - mem_total / raw_total if raw_total else None,
            "excludes": ["offline ingestion", "retrieval", "judge", "failed-request unknown usage"]}
    for c in CONDITIONS:
        judges = [r[c]["judge"] for r in rows if r[c]["judge"] is not None]
        labelled = [j for j in judges if j.get("label") is not None]
        correct = sum(j["label"] for j in labelled)
        unknown = len(rows) - len(labelled)
        answers = [r[c]["answer"] for r in rows if r[c]["answer"] is not None]
        result["conditions"][c] = {
            "reader_status": dict(Counter(a["status"] for a in answers)),
            "judge_status": dict(Counter(j["status"] for j in judges)),
            "correct": correct, "unknown": unknown,
            "full_denominator_quality_bounds_pct": [100 * correct / len(rows),
                    100 * (correct + unknown) / len(rows)] if rows else None,
            "observed_reader_tokens": sum(a.get("input_tokens", 0) + a.get("output_tokens", 0) for a in answers),
            "unmetered_reader_calls": sum(not a.get("usage_complete") for a in answers),
            "reader_latency_ms_diagnostic": percentiles([r[c]["answer"]["wall_ms"] for r in cost_pairs])}
        groups = {}
        for label, accept in (("all_evidence", lambda hit, total: hit == total),
                              ("partial_evidence", lambda hit, total: 0 < hit < total),
                              ("no_evidence", lambda hit, total: hit == 0)):
            selected = [r for r in complete if not r["abstention"] and r["relevant"] and
                        accept(len(set(r[c]["injected_ids"]) & set(r["relevant"])), len(set(r["relevant"])))]
            groups[label] = {"n": len(selected), "correct": sum(r[c]["judge"]["label"] for r in selected)}
        result["conditions"][c]["evidence_to_answer_diagnostic"] = groups
    result["by_question_type"] = {t: contrast([r for r in complete if r["question_type"] == t])
                                  for t in sorted({r["question_type"] for r in rows})}
    result["abstention_diagnostic"] = contrast([r for r in complete if r["abstention"]])
    result["complete"] = all(r[c]["judge"] is not None for r in rows for c in CONDITIONS)
    result["leaderboard_comparable"] = False
    return result


def main(out):
    manifest = read(out / "manifest.json")
    for name in ("jobs.jsonl", "gold.json"):
        actual = hashlib.sha256((out / name).read_bytes()).hexdigest()
        if actual != manifest[name.split(".")[0] + "_sha256"]:
            raise ValueError("Frozen memory input changed: " + name)
    # Do not use str.splitlines(): benchmark content can contain U+2028/U+2029
    # inside a JSON string, which is not a JSONL record separator.
    jobs = {json.loads(line)["id"]: line for line in
            (out / "jobs.jsonl").read_text(encoding="utf-8").split("\n") if line}
    rows = []
    for gold in read(out / "gold.json"):
        identifier = gold["question_id"]
        directory = out / "results" / identifier
        retrieval = read(directory / "retrieval.json")
        if retrieval is None or retrieval["fingerprint"] != digest(jobs[identifier]):
            raise ValueError("Retrieval provenance mismatch: " + identifier)
        row = {"id": identifier, "question_type": gold["question_type"],
               "abstention": "_abs" in identifier, "relevant": gold["answer_session_ids"]}
        for c in CONDITIONS:
            answer = read(directory / (c + "-answer.json"))
            judge = read(directory / (c + "-judge.json"))
            if answer and (answer["model"] != "gpt-5.6-luna" or answer["fingerprint"] !=
                    digest(jobs[identifier] + retrieval[c + "_context"] + answer["model"])):
                raise ValueError("Reader provenance mismatch: " + identifier)
            if judge and (judge["question_id"] != identifier or judge["condition"] != c or
                          judge["judge_model"] != "gpt-5.6-luna"):
                raise ValueError("Judge provenance mismatch: " + identifier)
            row[c] = {"answer": answer, "judge": judge, "injected_ids":
                      retrieval["injected_ids" if c == "memory" else "recency_injected_ids"]}
        rows.append(row)
    report = summarize(rows)
    report["scope"] = "imported-session keyword retrieval; Luna reader/judge; no automatic extraction or semantic channel"
    report["source_manifest_sha256"] = hashlib.sha256((out / "manifest.json").read_bytes()).hexdigest()
    target = out / "effect-summary.json"
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"file": str(target), "paired": report["labelled_pairs"],
                      "complete_reader_pairs": report["complete_reader_pairs_diagnostic"]["pairs"]}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=ROOT / "Test/paired-memory/luna-v1")
    main(parser.parse_args().out.resolve())
