"""RULER's unmodified metric and postprocessor, without the unrelated ASR dependency."""
import argparse
import ast
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import runpy

ROOT = Path(__file__).resolve().parents[1]
OFFICIAL = ROOT / "Data/raw/public-benchmarks/ruler/extracted/NVIDIA-RULER-38da79d"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def official_functions():
    source = OFFICIAL / "scripts/eval/evaluate.py"
    parsed = ast.parse(source.read_text(encoding="utf-8"))
    function = next(n for n in parsed.body if isinstance(n, ast.FunctionDef) and n.name == "postprocess_pred")
    namespace = {"re": re}
    exec(compile(ast.Module(body=[function], type_ignores=[]), str(source), "exec"), namespace)
    metric = runpy.run_path(str(OFFICIAL / "scripts/eval/synthetic/constants.py"))["string_match_all"]
    return namespace["postprocess_pred"], metric


def validate(out):
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    for name in ("jobs.jsonl", "gold.json"):
        if sha(out / name) != manifest[name.split(".")[0] + "_sha256"]:
            raise ValueError("Frozen RULER inputs changed")
    for relative, expected in manifest["generated_sha256"].items():
        if sha(ROOT / relative) != expected:
            raise ValueError("Official generated source changed")
    if sha(OFFICIAL / "scripts/eval/synthetic/constants.py") != manifest["metric_sha256"]:
        raise ValueError("Official metric changed")
    jobs = [json.loads(line) for line in (out / "jobs.jsonl").read_text(encoding="utf-8").splitlines()]
    gold = json.loads((out / "gold.json").read_text(encoding="utf-8"))
    if len(jobs) != 200 or len({j["id"] for j in jobs}) != 200 or [j["id"] for j in jobs] != [g["id"] for g in gold]:
        raise ValueError("Incomplete or duplicate RULER selection")
    for relative in manifest["generated_sha256"]:
        path = Path(relative)
        task, length = path.parent.name, int(path.parent.parent.name)
        rows = [json.loads(line) for line in (ROOT / relative).read_text(encoding="utf-8").splitlines()]
        selected = [(j, g) for j, g in zip(jobs, gold) if g["task"] == task and g["length"] == length]
        for job, reference in selected:
            original = rows[reference["source_row"]]
            if job["prefix"] + job["context"] + job["suffix"] != original["input"] or reference["answers"] != original["outputs"]:
                raise ValueError("RULER prompt/label roundtrip failed")
            if set(job) != {"id", "prefix", "context", "suffix", "order"}:
                raise ValueError("Unexpected field in model job")
    return jobs, gold


def prepare(out):
    validate(out)
    spec = importlib.util.spec_from_file_location("context_benchmark", Path(__file__).with_name("paired-context-benchmark.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    config = {"code_sha256": module.source_hash(), "evaluator_sha256": sha(OFFICIAL / "scripts/eval/evaluate.py"),
              "model": "gpt-5.6-luna", "chunk_chars": 8000, "retain_tokens": 2048, "trigger_tokens": 8192,
              "jobs_sha256": sha(out / "jobs.jsonl"), "gold_sha256": sha(out / "gold.json"),
              "protocol": "paired-chunked-reader; production Compactor; no recovery tools"}
    target = out / "execution-manifest.json"
    if target.exists():
        if json.loads(target.read_text(encoding="utf-8")) != config:
            raise ValueError("RULER execution configuration changed")
    elif any((out / "results").glob("*/*/started.json")):
        raise ValueError("Cannot freeze execution after model calls started")
    else:
        target.write_text(json.dumps(config, indent=2), encoding="utf-8")
    print("Verified 200 exact official prompts, unique IDs, gold, source and execution hashes")


def score(out):
    prepare(out)
    _, gold = validate(out)
    fingerprints = {json.loads(line)["id"]: hashlib.sha256((line + "\ngpt-5.6-luna").encode()).hexdigest()
                    for line in (out / "jobs.jsonl").read_text(encoding="utf-8").splitlines()}
    normalize, metric = official_functions()
    pairs, missing = [], []
    for item in gold:
        pair = {**item}
        for c in ("raw", "compact"):
            path = out / "results" / item["id"] / c / "result.json"
            if not path.exists():
                continue
            row = json.loads(path.read_text(encoding="utf-8"))
            if row["fingerprint"] != fingerprints[item["id"]] or row["condition"] != c or row["model"] != "gpt-5.6-luna":
                raise ValueError("RULER prediction provenance mismatch")
            row["prediction"] = normalize(row.get("prediction", ""), {})
            row["official_score"] = metric([row["prediction"]], [item["answers"]])
            pair[c] = row
        if "raw" in pair and "compact" in pair:
            pairs.append(pair)
        else:
            missing.append(item["id"])
    groups = {}
    for task, length in sorted({(g["task"], g["length"]) for g in gold}):
        selected = [p for p in pairs if p["task"] == task and p["length"] == length]
        if not selected:
            continue
        valid = [p for p in selected if all(p[c]["status"] == "ok" and p[c]["usage_complete"] and
                     p[c]["call_errors"] == 0 for c in ("raw", "compact"))]
        report = {"planned": 50, "paired": len(selected), "conditions": {}}
        for c in ("raw", "compact"):
            report["conditions"][c] = {"string_match_all": metric([p[c]["prediction"] for p in selected],
                                                                 [p["answers"] for p in selected]),
                                        "reader_errors": sum(p[c]["status"] != "ok" for p in selected)}
        if valid:
            values = {c: metric([p[c]["prediction"] for p in valid], [p["answers"] for p in valid]) for c in ("raw", "compact")}
            tokens = {c: sum(p[c]["total_input_tokens"] + p[c]["total_output_tokens"] for p in valid) for c in ("raw", "compact")}
            inputs = {c: sum(p[c]["answer_input_tokens"] for p in valid) for c in ("raw", "compact")}
            report["complete_case_diagnostic"] = {"pairs": len(valid), "raw_score": values["raw"], "compact_score": values["compact"],
                    "quality_delta_points": values["compact"] - values["raw"],
                    "quality_retention": values["compact"] / values["raw"] if values["raw"] else None,
                    "answer_input_reduction": 1 - inputs["compact"] / inputs["raw"] if inputs["raw"] else None,
                    "total_token_reduction_including_summary": 1 - tokens["compact"] / tokens["raw"] if tokens["raw"] else None,
                    "excluded_ids": [p["id"] for p in selected if p not in valid]}
        groups[f"{task}/{length}"] = report
    summary = {"planned": 200, "paired": len(pairs), "missing_ids": missing, "complete": not missing, "groups": groups,
               "official_metric": "unmodified string_match_all + postprocess_pred; not full evaluate.py CLI",
               "leaderboard_comparable": False, "scope": "2 NIAH tasks at 8K/32K; no tools or recovery reads"}
    (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    (out / "paired-results.json").write_text(json.dumps(pairs, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "score"))
    parser.add_argument("--out", type=Path, default=ROOT / "Test/paired-ruler/luna-v1")
    args = parser.parse_args()
    (prepare if args.action == "prepare" else score)(args.out.resolve())
