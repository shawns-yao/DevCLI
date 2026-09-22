"""Prepare pinned paired inputs and score with the unmodified LongBench evaluator."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "Data/raw/public-benchmarks/official-harnesses/longbench-harness/THUDM-LongBench-2e00731/LongBench"
DATA = ROOT / "Data/raw/public-benchmarks/longbench/extracted/data"
QUOTAS = {"qasper": 34, "hotpotqa": 34, "qmsum": 33, "trec": 33, "passage_retrieval_en": 33, "lcc": 33}
SEED = "devcli-paired-20260830-v1"


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def dump(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def source_hash():
    paths = sorted((ROOT / "src/main/java").rglob("*.java"))
    paths += [ROOT / "benchmarks/src/main/java/com/devcli/eval/PairedContextDriver.java"]
    return hashlib.sha256("\n".join(f"{p.relative_to(ROOT).as_posix()}:{sha(p)}" for p in paths).encode()).hexdigest()


def prepare(out, tasks=None, model="gpt-5.6-luna"):
    quotas = {task: QUOTAS[task] for task in (tasks or QUOTAS)}
    manifest_path = out / "manifest.json"
    sources = {str(p.relative_to(ROOT)): sha(p) for p in [
        HARNESS / "eval.py", HARNESS / "metrics.py", HARNESS / "config/dataset2prompt.json",
        *[DATA / f"{task}.jsonl" for task in quotas]]}
    fingerprint = {"seed": SEED, "quotas": quotas, "model": model, "sources": sources, "code_sha256": source_hash(),
                   "chunk_chars": 8000, "retain_tokens": 2048, "trigger_tokens": 8192,
                   "protocol": "paired-chunked-reader; no recovery tools; one compaction before answer"}
    if manifest_path.exists():
        old = json.loads(manifest_path.read_text(encoding="utf-8"))
        if old["configuration"] != fingerprint:
            raise ValueError("Configuration changed; refusing to overwrite existing experiment")
        if sha(out / "jobs.jsonl") != old["jobs_sha256"] or sha(out / "gold.json") != old["gold_sha256"]:
            raise ValueError("Prepared inputs changed")
        print("Reusing frozen manifest")
        return
    prompts = json.loads((HARNESS / "config/dataset2prompt.json").read_text(encoding="utf-8"))
    jobs, gold = [], []
    for task, count in quotas.items():
        offset = sum(QUOTAS[name] for name in list(QUOTAS)[:list(QUOTAS).index(task)])
        rows = [json.loads(line) for line in (DATA / f"{task}.jsonl").read_text(encoding="utf-8").splitlines() if line]
        ranked = sorted(enumerate(rows), key=lambda row: hashlib.sha256(f"{SEED}:{task}:{row[0]}".encode()).digest())
        for ordinal, (index, row) in enumerate(ranked[:count], start=offset):
            prefix, suffix = prompts[task].split("{context}")
            prefix, suffix = prefix.format(**row), suffix.format(**row)
            identifier = f"{task}-{index:04d}"
            order = ["raw", "compact"] if ordinal % 2 == 0 else ["compact", "raw"]
            jobs.append({"id": identifier, "prefix": prefix, "context": row["context"], "suffix": suffix,
                         "order": order, "configuration": fingerprint})
            gold.append({"id": identifier, "dataset": task, "source_index": index, "source_id": row.get("_id"),
                         "answers": row["answers"], "all_classes": row.get("all_classes", []), "length": row["length"]})
    out.mkdir(parents=True, exist_ok=True)
    (out / "jobs.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in jobs), encoding="utf-8")
    dump(out / "gold.json", gold)
    dump(manifest_path, {"configuration": fingerprint, "sample_ids": [j["id"] for j in jobs],
                        "jobs_sha256": sha(out / "jobs.jsonl"), "gold_sha256": sha(out / "gold.json")})
    print(f"Frozen {len(jobs)} paired cases across {len(quotas)} task families")


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def score(out):
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    model = manifest["configuration"].get("model", "gpt-5.6-luna")
    if sha(out / "gold.json") != manifest["gold_sha256"]:
        raise ValueError("Gold file changed")
    if sha(out / "jobs.jsonl") != manifest["jobs_sha256"]:
        raise ValueError("Jobs file changed")
    jobs = {}
    for line in (out / "jobs.jsonl").read_text(encoding="utf-8").splitlines():
        jobs[json.loads(line)["id"]] = hashlib.sha256((line + "\n" + model).encode()).hexdigest()
    for filename in ("eval.py", "metrics.py"):
        expected = manifest["configuration"]["sources"][str((HARNESS / filename).relative_to(ROOT))]
        if sha(HARNESS / filename) != expected:
            raise ValueError("Official evaluator changed")
    sys.path.insert(0, str(HARNESS))
    spec = importlib.util.spec_from_file_location("official_longbench_eval", HARNESS / "eval.py")
    official = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(official)
    gold = json.loads((out / "gold.json").read_text(encoding="utf-8"))
    groups, pairs, missing = {}, [], []
    for item in gold:
        records = {}
        for condition in ("raw", "compact"):
            path = out / "results" / item["id"] / condition / "result.json"
            if path.exists():
                records[condition] = json.loads(path.read_text(encoding="utf-8"))
                row = records[condition]
                if row.get("fingerprint") != jobs[item["id"]] or row.get("condition") != condition or row.get("model") != model:
                    raise ValueError("Prediction provenance mismatch: " + item["id"])
        if len(records) != 2:
            missing.append(item["id"])
            continue
        pair = {"id": item["id"], "dataset": item["dataset"], "length": item["length"]}
        for condition, row in records.items():
            prediction = row.get("prediction", "")
            value = official.scorer(item["dataset"], [prediction], [item["answers"]], item["all_classes"])
            pair[condition] = {**row, "official_score": value}
            groups.setdefault((item["dataset"], condition), []).append({**item, "pred": prediction})
        pair["score_delta_points"] = pair["compact"]["official_score"] - pair["raw"]["official_score"]
        pairs.append(pair)
    official_results = {}
    for (task, condition), rows in groups.items():
        directory = out / "official" / "pred" / condition
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"{task}.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    for condition in ("raw", "compact"):
        if not any(c == condition for _, c in groups):
            continue
        subprocess.run([sys.executable, str(HARNESS / "eval.py"), "--model", condition], cwd=out / "official", check=True)
        official_results[condition] = json.loads((out / "official/pred" / condition / "result.json").read_text())
    summary = {"paired_cases": len(pairs), "planned_cases": len(gold), "missing_ids": missing,
               "complete": not missing, "protocol": manifest["configuration"]["protocol"], "tasks": {}}
    for task in QUOTAS:
        selected = [p for p in pairs if p["dataset"] == task]
        if not selected:
            continue
        raw = official_results["raw"][task]
        compact = official_results["compact"][task]
        totals = {c: {key: sum(p[c].get(key, 0) for p in selected) for key in
                     ("answer_input_tokens", "total_input_tokens", "total_output_tokens", "summary_input_tokens",
                      "summary_output_tokens", "wall_ms", "call_errors")} for c in ("raw", "compact")}
        complete_usage = all(p[c].get("usage_complete") for p in selected for c in ("raw", "compact"))
        reduction = ratio(totals["compact"]["answer_input_tokens"], totals["raw"]["answer_input_tokens"])
        total_ratio = ratio(totals["compact"]["total_input_tokens"] + totals["compact"]["total_output_tokens"],
                            totals["raw"]["total_input_tokens"] + totals["raw"]["total_output_tokens"])
        summary["tasks"][task] = {"pairs": len(selected), "raw_score": raw, "compact_score": compact,
            "delta_points": round(compact - raw, 4), "quality_retention": ratio(compact, raw),
            "answer_input_reduction": 1 - reduction if complete_usage and reduction is not None else None,
            "total_token_reduction_including_summary": 1 - total_ratio if complete_usage and total_ratio is not None else None,
            "usage_complete": complete_usage, "totals": totals,
            "changed_contexts": sum(p["compact"].get("context_changed", False) for p in selected),
            "reader_errors": {c: sum(p[c]["status"] != "ok" for p in selected) for c in ("raw", "compact")}}
        valid = [p for p in selected if all(p[c]["status"] == "ok" and p[c].get("usage_complete")
                    and p[c].get("call_errors", 0) == 0 for c in ("raw", "compact"))]
        if valid:
            values = {c: sum(p[c]["official_score"] for p in valid) / len(valid) for c in ("raw", "compact")}
            answer_ratio = ratio(sum(p["compact"]["answer_input_tokens"] for p in valid),
                                 sum(p["raw"]["answer_input_tokens"] for p in valid))
            token_ratio = ratio(sum(p["compact"]["total_input_tokens"] + p["compact"]["total_output_tokens"] for p in valid),
                                sum(p["raw"]["total_input_tokens"] + p["raw"]["total_output_tokens"] for p in valid))
            summary["tasks"][task]["complete_case_diagnostic"] = {
                "pairs": len(valid), "excluded_ids": [p["id"] for p in selected if p not in valid],
                "raw_score": values["raw"], "compact_score": values["compact"],
                "delta_points": values["compact"] - values["raw"],
                "quality_retention": ratio(values["compact"], values["raw"]),
                "answer_input_reduction": 1 - answer_ratio if answer_ratio is not None else None,
                "total_token_reduction_including_summary": 1 - token_ratio if token_ratio is not None else None,
                "wall_time_ratio": ratio(sum(p["compact"]["wall_ms"] for p in valid), sum(p["raw"]["wall_ms"] for p in valid)),
                "not_a_replacement_for_full_denominator": True}
    dump(out / "paired-results.json", pairs)
    dump(out / "summary.json", summary)
    print(json.dumps({k: v for k, v in summary.items() if k != "missing_ids"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["prepare", "score"])
    parser.add_argument("--out", type=Path, default=ROOT / "Test/paired-context/luna-v1")
    parser.add_argument("--tasks", nargs="+", choices=list(QUOTAS))
    parser.add_argument("--model", default="gpt-5.6-luna")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.out.resolve(), args.tasks, args.model)
    else:
        score(args.out.resolve())
