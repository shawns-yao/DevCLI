"""Official LongMemEval judge prompts, with a disclosed compatible-gateway judge."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import time

from dotenv import dotenv_values
import requests

ROOT = Path(__file__).resolve().parents[1]
OFFICIAL = ROOT / "Data/raw/public-benchmarks/official-harnesses/longmemeval-harness/xiaowu0162-LongMemEval-9e0b455/src/evaluation/evaluate_qa.py"


def atomic_write(path, data):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def main(out, model, interval):
    spec = importlib.util.spec_from_file_location("official_longmemeval_judge", OFFICIAL)
    official = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(official)
    config = dotenv_values(ROOT / ".env")
    key, base = config.get("OPENAI_API_KEY"), config.get("OPENAI_BASE_URL")
    if not key or not base:
        raise ValueError("Missing OpenAI-compatible configuration")
    headers = {"Authorization": "Bearer " + key, "Content-Type": "application/json"}
    refs = json.loads((out / "gold.json").read_text(encoding="utf-8"))
    judge_hash = hashlib.sha256(OFFICIAL.read_bytes()).hexdigest()
    next_call = 0.0
    for index, ref in enumerate(refs):
        conditions = ("memory", "recency") if index % 2 else ("recency", "memory")
        for condition in conditions:
            directory = out / "results" / ref["question_id"]
            source = directory / (condition + "-answer.json")
            if not source.exists():
                continue
            answer = json.loads(source.read_text(encoding="utf-8"))
            prompt = official.get_anscheck_prompt(ref["question_type"], ref["question"], ref["answer"],
                    answer.get("hypothesis", ""), abstention="_abs" in ref["question_id"])
            fingerprint = hashlib.sha256((prompt + model + judge_hash).encode()).hexdigest()
            target = directory / (condition + "-judge.json")
            if target.exists():
                if json.loads(target.read_text(encoding="utf-8"))["fingerprint"] != fingerprint:
                    raise ValueError("Judge input changed")
                continue
            started = target.with_suffix(".started")
            if started.exists():
                continue
            with started.open("x", encoding="utf-8") as marker:
                marker.write(fingerprint)
            row = {"question_id": ref["question_id"], "condition": condition, "judge_model": model,
                   "official_judge_source_sha256": judge_hash, "fingerprint": fingerprint,
                   "leaderboard_comparable": False, "status": "pending"}
            if answer.get("status") != "ok":
                row.update(status="reader_failed", label=False)
            else:
                time.sleep(max(0, next_call - time.monotonic()))
                next_call = time.monotonic() + interval
                begin = time.monotonic()
                try:
                    response = requests.post(base.rstrip("/") + "/chat/completions", headers=headers,
                            json={"model": model, "messages": [{"role": "user", "content": prompt}],
                                  "n": 1, "temperature": 0, "max_tokens": 10}, timeout=(30, 180))
                    response.raise_for_status()
                    payload = response.json()
                    text = (payload["choices"][0]["message"].get("content") or "").strip()
                    row.update(response=text, usage=payload.get("usage"), wall_ms=round((time.monotonic()-begin)*1000))
                    # Preserve official yes-in-response label, but never convert transport/empty results to 'no'.
                    if not text or not any(word in text.lower() for word in ("yes", "no")):
                        row.update(status="invalid_judge", label=None)
                    else:
                        row.update(status="ok", label="yes" in text.lower())
                except requests.RequestException as error:
                    row.update(status="judge_error", error_type=type(error).__name__, label=None)
            atomic_write(target, row)
            print(f"[judged] {ref['question_id']} {condition} {row['status']}", flush=True)
    pairs, missing = [], []
    for ref in refs:
        pair = {"id": ref["question_id"]}
        for condition in ("recency", "memory"):
            path = out / "results" / ref["question_id"] / (condition + "-judge.json")
            if path.exists():
                pair[condition] = json.loads(path.read_text(encoding="utf-8"))
        if len(pair) == 3 and all(pair[c].get("label") is not None for c in ("recency", "memory")):
            pairs.append(pair)
        else:
            missing.append(ref["question_id"])
    baseline = sum(p["recency"]["label"] for p in pairs)
    treatment = sum(p["memory"]["label"] for p in pairs)
    summary = {"planned": len(refs), "paired_valid": len(pairs), "missing_or_invalid_ids": missing,
               "complete": not missing, "judge_model": model, "official_judge_source_sha256": judge_hash,
               "leaderboard_comparable": False, "recency_correct": baseline, "memory_correct": treatment,
               "answer_quality_delta_pp": 100*(treatment-baseline)/len(pairs) if pairs else None,
               "wins": sum(not p["recency"]["label"] and p["memory"]["label"] for p in pairs),
               "losses": sum(p["recency"]["label"] and not p["memory"]["label"] for p in pairs)}
    atomic_write(out / "reader-judge-summary.json", summary)
    print(json.dumps({k: v for k, v in summary.items() if k != "missing_or_invalid_ids"}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=ROOT / "Test/paired-memory/luna-v1")
    parser.add_argument("--model", default="gpt-5.6-luna")
    parser.add_argument("--interval", type=float, default=2.2)
    args = parser.parse_args()
    main(args.out.resolve(), args.model, args.interval)
