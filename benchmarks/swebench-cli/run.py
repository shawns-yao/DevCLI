"""Generate SWE-bench patches through the actual DevCLI -p process; never score locally."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time


def load_cases():
    root = Path(__file__).parent / "v1"
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    payload = (root / "cases.jsonl").read_bytes()
    if hashlib.sha256(payload).hexdigest() != manifest["cases_sha256"]:
        raise ValueError("Frozen cases SHA-256 differs from the manifest")
    cases = [json.loads(line) for line in payload.decode("utf-8").splitlines()]
    ids = [case["instance_id"] for case in cases]
    if len(cases) != 20 or ids != manifest["instance_ids"] or len(set(ids)) != 20:
        raise ValueError("Expected the frozen 20 unique cases in manifest order")
    for case in cases:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", case["instance_id"]):
            raise ValueError("Invalid instance_id")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", case["repo"]):
            raise ValueError("Invalid public repository")
        if not re.fullmatch(r"[a-f0-9]{40}", case["base_commit"]):
            raise ValueError("Invalid base_commit")
    return manifest, cases


def git(workspace, *args):
    with (workspace.parent / "git.log").open("ab") as errors:
        return subprocess.run(
            ["git", "-C", str(workspace), *args], check=True, timeout=180,
            stdout=subprocess.PIPE, stderr=errors,
        ).stdout


def run_cli(command, workspace, case_dir, timeout):
    with (case_dir / "stdout.json").open("wb") as output, (case_dir / "stderr.log").open("wb") as errors:
        process = subprocess.Popen(
            command, cwd=workspace, stdin=subprocess.DEVNULL, stdout=output, stderr=errors,
            start_new_session=os.name != "nt",
        )
        try:
            return process.wait(timeout=timeout), False
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                subprocess.run(
                    ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
                )
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            return process.returncode, True


def prepare_case(case, root):
    case_dir = root / case["instance_id"]
    workspace = case_dir / "workspace"
    workspace.mkdir(parents=True)
    git(workspace, "init", "--quiet")
    git(workspace, "remote", "add", "origin", f"https://github.com/{case['repo']}.git")
    git(workspace, "fetch", "--quiet", "--depth=1", "origin", case["base_commit"])
    git(workspace, "checkout", "--quiet", "--detach", "FETCH_HEAD")


def run_case(case, root, jar, timeout):
    case_dir = root / case["instance_id"]
    workspace = case_dir / "workspace"
    if git(workspace, "rev-parse", "HEAD").decode().strip() != case["base_commit"]:
        raise ValueError("Prepared workspace HEAD differs from the frozen base_commit")
    if git(workspace, "status", "--porcelain"):
        raise ValueError("Prepared workspace contains changes before the task")
    command = [
        "java", "-Dfile.encoding=UTF-8",
        f"-Ddevcli.runtime.dir={case_dir / 'runtime'}",
        f"-Ddevcli.memory.dir={case_dir / 'memory'}",
        f"-Ddevcli.delegation.dir={case_dir / 'delegations'}",
        f"-Ddevcli.log.dir={case_dir / 'logs'}",
        "-jar", str(jar), "-p", case["problem_statement"],
    ]
    started = time.monotonic()
    exit_code, timed_out = run_cli(command, workspace, case_dir, timeout)
    result = {"exit_code": exit_code, "timed_out": timed_out, "elapsed_seconds": time.monotonic() - started}
    if timed_out:
        result["status"] = "project_timeout"
    else:
        try:
            response = json.loads((case_dir / "stdout.json").read_text(encoding="utf-8"))
            result["response"] = response
            result["status"] = "cli_completed" if exit_code == 0 and response["status"] == "completed" else "project_failure"
        except (json.JSONDecodeError, KeyError, TypeError, UnicodeDecodeError):
            result["status"] = "project_invalid_output"
    # Include new source files in the patch without committing or using model output as a patch.
    git(workspace, "add", "--intent-to-add", ".")
    patch = git(workspace, "diff", "--binary", "HEAD")
    (case_dir / "model.patch").write_bytes(patch)
    return result, patch.decode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    manifest, cases = load_cases()
    if args.dry_run:
        print(json.dumps({"mode": "dry_run", "N": len(cases), "executed": 0,
                          "entrypoint": "java -jar <jar> -p <original problem_statement>",
                          "dataset_revision": manifest["revision"], "instance_ids": manifest["instance_ids"]}))
        return 0
    if not args.output or args.timeout <= 0:
        parser.error("--output and a positive --timeout are required")
    root = args.output.resolve()
    if args.prepare_only:
        root.mkdir(parents=True, exist_ok=False)
        for case in cases:
            prepare_case(case, root)
        (root / "prepared.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        print("Prepared 20 workspaces. Configure project trust and permissions before invoking -p.")
        return 0
    if not args.jar:
        parser.error("--jar is required when running tasks")
    if (root / "run.json").exists():
        parser.error("This batch already ran; prepare a new batch")
    if json.loads((root / "prepared.json").read_text(encoding="utf-8")) != manifest:
        parser.error("Prepared dataset differs from the frozen manifest")
    jar = args.jar.resolve(strict=True)
    jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    card = {"N": 20, "E": 0, "V": 20, "timeout_seconds": args.timeout, "retries": 0,
            "jar_sha256": jar_hash, "dataset": manifest, "entrypoint": "-p",
            "quality_score": "not_scored; use the official frozen SWE-bench harness"}
    (root / "run.json").write_text(json.dumps(card, ensure_ascii=False, indent=2), encoding="utf-8")
    results = []
    with (root / "predictions.jsonl").open("w", encoding="utf-8", newline="\n") as predictions:
        for case in cases:
            try:
                result, patch = run_case(case, root, jar, args.timeout)
            except (OSError, subprocess.SubprocessError) as failure:
                result = {"status": "infrastructure_failure", "error_type": type(failure).__name__,
                          "error": str(failure)}
                patch = ""
            result["instance_id"] = case["instance_id"]
            results.append(result)
            (root / case["instance_id"] / "result.json").write_text(
                json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8",
            )
            predictions.write(json.dumps({"instance_id": case["instance_id"], "model_patch": patch,
                                          "model_name_or_path": f"devcli-{jar_hash[:12]}"}, ensure_ascii=False) + "\n")
            predictions.flush()
    completed = sum(result["status"] == "cli_completed" for result in results)
    card.update({"cli_completed": completed, "cli_completion_rate": completed / 20,
                 "infrastructure_failures": sum(result["status"] == "infrastructure_failure" for result in results),
                 "results": results})
    (root / "run.json").write_text(json.dumps(card, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if completed == 20 else 1


if __name__ == "__main__":
    raise SystemExit(main())
