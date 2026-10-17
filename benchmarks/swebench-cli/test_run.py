"""Controller tests only: no model, GitHub checkout or SWE-bench scoring."""

from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import run


class ControllerTest(unittest.TestCase):
    def test_dry_run_cannot_spawn_a_paid_process(self):
        output = io.StringIO()
        with patch.object(sys, "argv", ["run.py", "--dry-run"]), redirect_stdout(output):
            with patch.object(run.subprocess, "Popen", side_effect=AssertionError("Unexpected process")):
                self.assertEqual(0, run.main())
        report = json.loads(output.getvalue())
        self.assertEqual(20, report["N"])
        self.assertEqual(0, report["executed"])
        self.assertEqual(20, len(set(report["instance_ids"])))

    def test_modified_fixture_is_rejected(self):
        with patch.object(Path, "read_bytes", return_value=b"changed fixture"):
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                run.load_cases()

    def test_real_process_receives_cwd_and_closed_stdin(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            workspace = root / "workspace"
            workspace.mkdir()
            command = [sys.executable, "-c",
                       "import json,os,sys; print(json.dumps({'cwd':os.getcwd(),'stdin':sys.stdin.read()}))"]
            exit_code, timed_out = run.run_cli(command, workspace, root, 5)
            self.assertEqual((0, False), (exit_code, timed_out))
            response = json.loads((root / "stdout.json").read_text())
            self.assertEqual(workspace, Path(response["cwd"]))
            self.assertEqual("", response["stdin"])
            self.assertEqual(b"", (root / "stderr.log").read_bytes())

    def test_timeout_waits_for_process_termination(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            exit_code, timed_out = run.run_cli(
                [sys.executable, "-c", "import time; time.sleep(30)"], root, root, 0.2,
            )
            self.assertTrue(timed_out)
            self.assertNotEqual(0, exit_code)


if __name__ == "__main__":
    unittest.main()
