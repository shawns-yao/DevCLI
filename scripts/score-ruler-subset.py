"""Score a small post-fix RULER subset with the official metric implementation."""
import argparse
import importlib.util
import json
from pathlib import Path


def load_official():
    path = Path(__file__).with_name("score-paired-ruler.py")
    spec = importlib.util.spec_from_file_location("score_paired_ruler", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.official_functions()


def main(root):
    normalize, metric = load_official()
    jobs = [json.loads(line) for line in (root / "jobs.jsonl").read_text(encoding="utf-8").split("\n") if line]
    gold = {row["id"]: row for row in json.loads((root / "gold.json").read_text(encoding="utf-8"))}
    rows = []
    for job in jobs:
        item = gold[job["id"]]
        pair = {"id": job["id"], "answers": item["answers"]}
        for condition in ("raw", "compact"):
            path = root / "results" / job["id"] / condition / "result.json"
            if not path.exists():
                raise RuntimeError(f"missing {job['id']} {condition}")
            result = json.loads(path.read_text(encoding="utf-8"))
            prediction = normalize(result.get("prediction", ""), {})
            pair[condition] = {"result": result, "prediction": prediction,
                               "score": metric([prediction], [item["answers"]])}
        rows.append(pair)
    summary = {"cases": len(rows), "task": "niah_multikey_2", "length": 32768,
               "official_metric": "string_match_all",
               "raw_score": metric([r["raw"]["prediction"] for r in rows], [r["answers"] for r in rows]),
               "compact_score": metric([r["compact"]["prediction"] for r in rows], [r["answers"] for r in rows]),
               "answer_input_reduction": 1 - sum(r["compact"]["result"]["answer_input_tokens"] for r in rows) /
               sum(r["raw"]["result"]["answer_input_tokens"] for r in rows),
               "total_token_reduction_including_summary": 1 - sum(
                   r["compact"]["result"]["total_input_tokens"] + r["compact"]["result"]["total_output_tokens"]
                   for r in rows) / sum(r["raw"]["result"]["total_input_tokens"] + r["raw"]["result"]["total_output_tokens"]
                                       for r in rows)}
    (root / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (root / "paired-results.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    main(parser.parse_args().root.resolve())
