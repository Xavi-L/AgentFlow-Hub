#!/usr/bin/env python3
"""Focused cold-cutover observation tests. Signals and replacement exec are mocked."""
import importlib.util
import json
import signal
import subprocess
import sys
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "task_cold_cutover", Path(__file__).with_name("task-cold-cutover.py")
)
CUTOVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CUTOVER)


class ColdCutoverObservationTest(unittest.TestCase):
    def run_observation(self, response, elapsed=False):
        with tempfile.TemporaryDirectory(prefix="task-cold-cutover-test-") as directory, ExitStack() as stack:
            record_path = Path(directory, "cold-cutover.json")
            lock_path = Path(directory, "task.lock")
            argv = ["task-cold-cutover.py", "--old-pid", "424242",
                    "--lock-path", str(lock_path), "--record", str(record_path),
                    "--", "/test/runtime/bin/java", "-jar", "agentflow.jar"]
            stack.enter_context(patch.object(sys, "argv", argv))
            observations = stack.enter_context(patch.object(CUTOVER.subprocess, "run", side_effect=[
                subprocess.CompletedProcess([], 0, "/test/runtime/bin/java\n", ""), response,
            ]))
            send_signal = stack.enter_context(patch.object(CUTOVER.os, "kill"))
            replacement = stack.enter_context(patch.object(CUTOVER.os, "execvp"))
            stack.enter_context(patch.dict(CUTOVER.os.environ, {}, clear=False))
            stack.enter_context(patch.object(CUTOVER.time, "sleep"))
            if elapsed:
                stack.enter_context(patch.object(CUTOVER.time, "monotonic", side_effect=[0, 61]))
            failure = None
            try:
                CUTOVER.main()
            except SystemExit as error:
                failure = str(error)
            record = json.loads(record_path.read_text())
            send_signal.assert_called_once_with(424242, signal.SIGTERM)
            self.assertEqual(observations.call_count, 2)
            return record, replacement.call_args, failure, str(lock_path)

    def test_observation_errors_refuse_replacement_and_preserve_unconfirmed_record(self):
        for response in [subprocess.CompletedProcess([], 2, "", "observation failed"),
                         subprocess.CompletedProcess([], 1, "", "permission denied"),
                         subprocess.CompletedProcess([], 0, "", "")]:
            with self.subTest(returncode=response.returncode, stderr=response.stderr):
                record, replacement, failure, _ = self.run_observation(response)
                self.assertIn("process observation failed", failure)
                self.assertIsNone(replacement)
                self.assertFalse(record["oldJvmExited"])
                self.assertNotIn("oldJvmExitedAt", record)
                self.assertNotIn("replacementExecAt", record)

    def test_confirmed_absence_allows_exact_replacement_command(self):
        record, replacement, failure, lock_path = self.run_observation(
            subprocess.CompletedProcess([], 1, "", "")
        )
        self.assertIsNone(failure)
        self.assertTrue(record["oldJvmExited"])
        self.assertEqual(record["lockPath"], lock_path)
        self.assertIn("oldJvmExitedAt", record)
        self.assertEqual(replacement.args, ("/test/runtime/bin/java",
                         ["/test/runtime/bin/java", "-jar", "agentflow.jar"]))

    def test_zombie_is_exited_and_allows_replacement(self):
        record, replacement, failure, _ = self.run_observation(
            subprocess.CompletedProcess([], 0, "Z+\n", "")
        )
        self.assertIsNone(failure)
        self.assertTrue(record["oldJvmExited"])
        self.assertIsNotNone(replacement)

    def test_still_alive_at_deadline_refuses_replacement(self):
        record, replacement, failure, _ = self.run_observation(
            subprocess.CompletedProcess([], 0, "S\n", ""), elapsed=True
        )
        self.assertIn("has not exited", failure)
        self.assertFalse(record["oldJvmExited"])
        self.assertIsNone(replacement)


if __name__ == "__main__":
    unittest.main()
