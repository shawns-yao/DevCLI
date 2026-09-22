import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd


JAVA_REPOS = {
    "apache/druid",
    "apache/lucene",
    "google/gson",
    "javaparser/javaparser",
    "projectlombok/lombok",
    "reactivex/rxjava",
}


def json_value(value):
    if isinstance(value, np.ndarray):
        return value.tolist()
    if hasattr(value, "item"):
        return value.item()
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--parquet", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--dataset", required=True)
    args = parser.parse_args()

    frame = pd.read_parquet(args.parquet)
    java = frame[frame["repo"].isin(JAVA_REPOS)].sort_values("instance_id")
    if len(java) != 43:
        raise SystemExit(f"expected 43 Java instances, got {len(java)}")

    records = []
    manifest = []
    for _, row in java.iterrows():
        record = {column: json_value(row[column]) for column in frame.columns}
        records.append(record)
        manifest.append({
            "instance_id": record["instance_id"],
            "repo": record["repo"],
            "base_commit": record["base_commit"],
            "image": record["image"],
            "problem_statement": record["problem_statement"],
            "fail_to_pass": record["FAIL_TO_PASS"],
            "pass_to_pass": record["PASS_TO_PASS"],
        })

    for path, payload in ((args.manifest, manifest), (args.dataset, records)):
        output = Path(path)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    print(f"exported {len(records)} Java instances")


if __name__ == "__main__":
    main()
