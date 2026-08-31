"""Session-level retrieval evaluation against LongMemEval-S official evidence labels."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "Data/raw/public-benchmarks/longmemeval/longmemeval_s_cleaned.json"
EXPECTED_SHA = "d6f21ea9d60a0d56f34a05b609c79c88a451d2ae03597821ea3d5a9678c3a442"
REVISION = "98d7416c24c778c2fee6e6f3006e7a073259d48f"
SEED = "devcli-memory-paired-20260830-v1"


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(out):
    if sha(DATA) != EXPECTED_SHA:
        raise ValueError("Official LongMemEval-S checksum mismatch")
    if (out / "manifest.json").exists():
        old = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
        if sha(out / "jobs.jsonl") != old["jobs_sha256"] or sha(out / "gold.json") != old["gold_sha256"]:
            raise ValueError("Prepared memory inputs changed")
        print("Reusing frozen 200-case memory manifest")
        return
    rows = json.loads(DATA.read_text(encoding="utf-8"))
    groups = defaultdict(list)
    for row in rows:
        groups[row["question_type"] + ("/abstention" if "_abs" in row["question_id"] else "/answerable")].append(row)
    exact = {key: len(group) * 200 / len(rows) for key, group in groups.items()}
    quotas = {key: math.floor(value) for key, value in exact.items()}
    for key in sorted(groups, key=lambda k: (-(exact[k] - quotas[k]), k))[:200 - sum(quotas.values())]:
        quotas[key] += 1
    selected = []
    for group, count in sorted(quotas.items()):
        ranked = sorted(groups[group], key=lambda r: hashlib.sha256((SEED + r["question_id"]).encode()).digest())
        selected.extend(ranked[:count])
    anchor = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    out.mkdir(parents=True, exist_ok=True)
    gold = []
    with (out / "jobs.jsonl").open("w", encoding="utf-8") as stream:
        for row in selected:
            question_time = datetime.strptime(row["question_date"], "%Y/%m/%d (%a) %H:%M")
            sessions = []
            for sid, date, messages in zip(row["haystack_session_ids"], row["haystack_dates"], row["haystack_sessions"], strict=True):
                age = (question_time - datetime.strptime(date, "%Y/%m/%d (%a) %H:%M")).total_seconds()
                content = f"Session {sid}; date {date}\n" + "\n".join(f"{m['role']}: {m['content']}" for m in messages)
                sessions.append({"id": sid, "content": content, "age_seconds": int(age)})
            job = {"id": row["question_id"], "question": row["question"], "question_date": row["question_date"],
                   "clock_anchor": anchor, "sessions": sessions, "dataset_revision": REVISION, "seed": SEED}
            stream.write(json.dumps(job, ensure_ascii=False) + "\n")
            gold.append({k: row[k] for k in ("question_id", "question_type", "question", "question_date", "answer", "answer_session_ids")})
    write(out / "gold.json", gold)
    write(out / "manifest.json", {"dataset_revision": REVISION, "dataset_sha256": EXPECTED_SHA, "seed": SEED,
          "quotas": quotas, "count": len(selected), "sample_ids": [r["question_id"] for r in selected],
          "clock_anchor": anchor, "jobs_sha256": sha(out / "jobs.jsonl"), "gold_sha256": sha(out / "gold.json"),
          "protocol": "whole-session import; production keyword retrieval; recency-5 baseline; 16384 estimated-token budget",
          "not_tested": ["automatic memory extraction/promotion", "semantic channel", "fact-level stale-memory annotations"]})
    print(f"Frozen {len(selected)} memory cases: {dict(Counter(r['question_type'] for r in selected))}")


def retrieval_metrics(retrieved, relevant):
    retrieved = list(dict.fromkeys(retrieved))[:5]
    gold = set(relevant)
    if not gold:
        return None
    hit = len(set(retrieved) & gold)
    return {"recall_at_5": hit / len(gold), "precision_at_5": hit / 5,
            "mrr_at_5": next((1 / (i + 1) for i, sid in enumerate(retrieved) if sid in gold), 0),
            "all_evidence": float(gold.issubset(retrieved)), "returned_sessions": len(retrieved)}


def score(out):
    gold = json.loads((out / "gold.json").read_text(encoding="utf-8"))
    pairs, missing = [], []
    for item in gold:
        path = out / "results" / item["question_id"] / "retrieval.json"
        if not path.exists():
            missing.append(item["question_id"])
            continue
        record = json.loads(path.read_text(encoding="utf-8"))
        pair = {"id": item["question_id"], "question_type": item["question_type"],
                "abstention": "_abs" in item["question_id"], "retrieve_ms": record["retrieve_ms"]}
        for condition, rank_key, inject_key in (("recency", "recency_ranked_ids", "recency_injected_ids"),
                                              ("memory", "ranked_ids", "injected_ids")):
            injected_in_rank_order = [sid for sid in record[rank_key] if sid in set(record[inject_key])]
            pair[condition] = {"ranked": retrieval_metrics(record[rank_key], item["answer_session_ids"]),
                               "injected": retrieval_metrics(injected_in_rank_order, item["answer_session_ids"])}
        pairs.append(pair)
    eligible = [p for p in pairs if not p["abstention"] and p["memory"]["ranked"] is not None]
    summary = {"planned": len(gold), "paired": len(pairs), "eligible_answerable": len(eligible), "missing": missing,
               "complete": not missing, "metrics_are": "session-label retrieval metrics; not reader accuracy", "conditions": {}}
    for condition in ("recency", "memory"):
        summary["conditions"][condition] = {stage: {
            key: sum(p[condition][stage][key] for p in eligible) / len(eligible) if eligible else None
            for key in ("recall_at_5", "precision_at_5", "mrr_at_5", "all_evidence", "returned_sessions")}
            for stage in ("ranked", "injected")}
    summary["delta"] = {stage: {key: summary["conditions"]["memory"][stage][key] - summary["conditions"]["recency"][stage][key]
                               for key in ("recall_at_5", "precision_at_5", "mrr_at_5", "all_evidence")} for stage in ("ranked", "injected")} if eligible else {}
    times = sorted(p["retrieve_ms"] for p in pairs)
    summary["retrieval_latency_ms"] = {"p50": times[len(times)//2], "p95": times[math.ceil(len(times)*.95)-1]} if times else {}
    write(out / "evidence-pairs.json", pairs)
    write(out / "evidence-summary.json", summary)
    print(json.dumps({k: v for k, v in summary.items() if k != "missing"}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "score"))
    parser.add_argument("--out", type=Path, default=ROOT / "Test/paired-memory/luna-v1")
    args = parser.parse_args()
    (prepare if args.action == "prepare" else score)(args.out.resolve())
