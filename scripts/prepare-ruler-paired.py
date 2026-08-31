"""Generate an immutable 200-case diagnostic with the existing official RULER code."""
import hashlib
import json
from pathlib import Path
import runpy
import subprocess
import sys

import yaml

ROOT = Path(__file__).resolve().parents[1]
OFFICIAL = ROOT / "Data/raw/public-benchmarks/ruler/extracted/NVIDIA-RULER-38da79d"
DATA = ROOT / "Data/processed/ruler-paired-20260830"
OUT = ROOT / "Test/paired-ruler/luna-v1"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if (OUT / "manifest.json").exists():
        manifest = json.loads((OUT / "manifest.json").read_text(encoding="utf-8"))
        if digest(OUT / "jobs.jsonl") != manifest["jobs_sha256"]:
            raise ValueError("Frozen RULER jobs changed")
        print("Reusing frozen RULER inputs")
        return
    base = runpy.run_path(str(OFFICIAL / "scripts/data/synthetic/constants.py"))["TASKS"]["niah"]
    configurations = yaml.safe_load((OFFICIAL / "scripts/synthetic.yaml").read_text())
    template = base["template"] + base["answer_prefix"]
    OUT.mkdir(parents=True, exist_ok=True)
    jobs, gold, generated = [], [], {}
    for length in (8192, 32768):
        for task in ("niah_single_1", "niah_multikey_2"):
            directory = DATA / str(length)
            raw = directory / task / "validation.jsonl"
            if not raw.exists():
                arguments = [sys.executable, str(OFFICIAL / "scripts/data/synthetic/niah.py"),
                        "--save_dir", str(directory), "--save_name", task, "--subset", "validation",
                        "--tokenizer_path", "cl100k_base", "--tokenizer_type", "openai",
                        "--max_seq_length", str(length), "--tokens_to_generate", str(base["tokens_to_generate"]),
                        "--num_samples", "50", "--random_seed", "42", "--template", template]
                for key, value in configurations[task]["args"].items():
                    arguments.extend(["--" + key, str(value)])
                subprocess.run(arguments, check=True, cwd=ROOT)
            rows = [json.loads(line) for line in raw.read_text(encoding="utf-8").splitlines()]
            if len(rows) != 50:
                raise ValueError("Incomplete RULER generation")
            generated[str(raw.relative_to(ROOT))] = digest(raw)
            for source_row, row in enumerate(rows):
                # Split exactly at the official question template, never by answer text.
                marker_text = "\nWhat is the special magic " if "\nWhat is the special magic " in row["input"] else "\nWhat are all the special magic "
                material, marker, question = row["input"].rpartition(marker_text)
                if not marker:
                    raise ValueError("Official RULER template changed")
                prefix, newline, context = material.partition("\n")
                if not newline:
                    raise ValueError("Missing RULER context boundary")
                identifier = f"{task}-{length}-{source_row:04d}"
                jobs.append({"id": identifier, "prefix": prefix + "\n", "context": context,
                             "suffix": marker + question, "order": ["raw", "compact"] if len(jobs) % 2 == 0 else ["compact", "raw"]})
                gold.append({"id": identifier, "task": task, "length": length, "answers": row["outputs"],
                             "source_row": source_row, "official_index": row["index"],
                             "token_position_answer": row.get("token_position_answer")})
    (OUT / "jobs.jsonl").write_text("".join(json.dumps(j) + "\n" for j in jobs), encoding="utf-8")
    (OUT / "gold.json").write_text(json.dumps(gold, indent=2), encoding="utf-8")
    manifest = {"count": len(jobs), "samples_per_task_length": 50, "seed": 42,
                "tasks": ["niah_single_1", "niah_multikey_2"], "lengths": [8192, 32768],
                "length_tokenizer": "cl100k_base (not a verified gateway model tokenizer)",
                "official_snapshot": "NVIDIA-RULER-38da79d", "generated_sha256": generated,
                "generator_sha256": digest(OFFICIAL / "scripts/data/synthetic/niah.py"),
                "metric_sha256": digest(OFFICIAL / "scripts/eval/synthetic/constants.py"),
                "jobs_sha256": digest(OUT / "jobs.jsonl"), "gold_sha256": digest(OUT / "gold.json"),
                "model_calls": 0, "scope": "2 NIAH tasks only; not the full 13-task RULER benchmark"}
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Prepared {len(jobs)} official RULER cases; no model calls")


if __name__ == "__main__":
    main()
