#!/usr/bin/env python3
"""Controlled A07-A13 HTTP/file/process evidence. No real provider claims."""
import contextlib
from decimal import Decimal
import hashlib
import http.server
import importlib.util
import json
import multiprocessing
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest

SPEC = importlib.util.spec_from_file_location("evaluation", Path(__file__).with_name("evaluation.py"))
E = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(E)

TOKEN = "controlled-test-auth-value"


class Service:
    def __init__(self):
        self.owner, self.tasks, self.posts, self.requests = "10", {}, [], []
        self.disconnect, self.deny_trace = set(), False
        self.post_errors = []
        self.config = {"schemaVersion": "agent-config-v1", "systemPrompt": "原始 prompt\n ",
                       "budgets": {"maxTotalTokens": 100, "timeoutSeconds": 10},
                       "knowledgeBindings": [], "toolBindings": []}
        self.configuration = {"configVersionId": "20", "configHash": E.content_hash(self.config),
                              "effectiveConfigHash": "effective-actual", "hashAlgorithmVersion": E.ALGORITHM}
        service = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def reply(self, status, data=None, code="OK"):
                value = {"code": code, "data": data, "message": "password=exception-fixture-value " + TOKEN}
                encoded = E.canonical_json(value).encode()
                with contextlib.suppress(BrokenPipeError, ConnectionResetError):
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(encoded)))
                    self.end_headers()
                    self.wfile.write(encoded)

            def do_GET(self):
                service.requests.append(("GET", self.path))
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    return self.reply(401, code="AUTH_TOKEN_INVALID")
                if self.path == "/api/v1/users/me":
                    return self.reply(200, {"id": service.owner})
                if self.path == "/api/v1/agents/1/config-versions/20":
                    return self.reply(200, {"configVersionId": "20", "agentId": "1", "schemaVersion": "agent-config-v1",
                                            "hashAlgorithmVersion": E.ALGORITHM, "configHash": E.content_hash(service.config),
                                            "config": service.config})
                parts = self.path.split("/")
                if len(parts) >= 5 and parts[3] == "tasks":
                    task = next((v for v in service.tasks.values() if v["taskId"] == parts[4]), None)
                    if task is None:
                        return self.reply(404, code="COMMON_NOT_FOUND")
                    if len(parts) == 6:
                        if service.deny_trace:
                            return self.reply(404, code="COMMON_NOT_FOUND")
                        return self.reply(200, {"task": task, "executionSnapshot": {
                            "snapshotVersion": "agent-task-snapshot-v2", "agent": {"systemPrompt": "actual prompt"},
                            "runtime": {"applicationRevision": "development"}}, "events": [], "steps": [
                                {"llmCalls": [{"id": "81", "callType": "FINAL_GENERATION", "provider": "CONTROLLED",
                                               "requestedModel": "wanted", "resolvedModel": "actual-controlled",
                                               "status": "SUCCEEDED", "usageQuality": "EXACT",
                                               "responseText": "Bearer " + TOKEN, "password": "fixture-secret",
                                               "errorMessage": "password=fixture-secret"}]}]})
                    return self.reply(200, task)
                return self.reply(404, code="COMMON_NOT_FOUND")

            def do_POST(self):
                body = E.loads(self.rfile.read(int(self.headers["Content-Length"])))
                key = self.headers.get("Idempotency-Key")
                service.posts.append((key, body))
                service.requests.append(("POST", self.path))
                if body["userInput"] == "reject":
                    return self.reply(409, code="AGENT_DISABLED")
                if body["userInput"] == "always-unknown":
                    return self.reply(503, code="SYS_INTERNAL_ERROR")
                if key not in service.tasks:
                    status = "RUNNING" if body["userInput"] == "running" else "FAILED" if body["userInput"] in ("interrupt", "fail") else "COMPLETED"
                    service.tasks[key] = {"taskId": str(100 + len(service.tasks)), "agentId": "1", "status": status,
                                          "userInput": body["userInput"], "configuration": service.configuration,
                                          "lastEventSequence": 4, "terminationReason": "RESTART_INTERRUPTED" if body["userInput"] == "interrupt" else "FINISHED",
                                          "errorCode": "TASK_RESTART_INTERRUPTED" if body["userInput"] == "interrupt" else None,
                                          "tokenUsageQuality": "UNKNOWN" if body["userInput"] == "interrupt" else "EXACT",
                                          "inputTokens": 5, "outputTokens": 5, "totalTokens": 10,
                                          "finalAnswer": "answer password=must-not-land", "citations": []}
                if service.post_errors:
                    status, code = service.post_errors.pop(0)
                    return self.reply(status, code=code)
                if body["userInput"] in service.disconnect:
                    service.disconnect.remove(body["userInput"])
                    self.close_connection = True
                    self.connection.shutdown(2)
                    self.connection.close()
                    return
                return self.reply(201, service.tasks[key])

            def log_message(self, *_args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:" + str(self.server.server_port)
        self.client = E.HttpClient(self.base, TOKEN)

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def child_execute(directory, base, point, reached):
    client = E.HttpClient(base, TOKEN)
    def hook(actual, _case):
        if actual == point:
            Path(reached).write_text(actual)
            while True:
                time.sleep(0.1)
    E.execute(directory, client, hook)


class EvaluationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.run = self.root / "run"
        self.service = Service()

    def tearDown(self):
        self.service.close()
        self.temp.cleanup()

    def suite(self, inputs=("success",), **extra):
        result = {"agentId": "1", "configVersionId": "20", "suiteMode": "CONTROLLED_CONTRACT",
                  "dataset": {"datasetId": "controlled-files", "version": "v1"},
                  "cases": [{"caseId": "case-" + str(i), "userInput": value,
                             "expected": {"status": "UNASSESSED"}} for i, value in enumerate(inputs)],
                  "budget": {"maxTotalTokens": 1000, "observationTimeoutSeconds": Decimal("0.08"),
                             "requestTimeoutSeconds": Decimal("0.1"), "pollIntervalSeconds": Decimal("0.02")}}
        result.update(extra)
        return result

    def create(self, inputs=("success",), **extra):
        return E.create_run(self.suite(inputs, **extra), self.run, self.service.client)

    def test_a12_shared_golden_vectors_and_rejections(self):
        fixture = E.read_json(Path(__file__).parent / "fixtures/config-canonical-json-v1-vectors.json")
        for vector in fixture["vectors"]:
            value = E.loads(vector["inputJson"]) if "inputJson" in vector else vector["input"]
            with self.subTest(vector=vector["name"]):
                self.assertEqual(vector["canonical"], E.canonical_json(value))
                self.assertEqual(vector["sha256"], E.content_hash(value))
        for value in (float("nan"), float("inf"), Decimal("-Infinity")):
            with self.assertRaises(E.EvaluationError):
                E.canonical_json(value)
        with self.assertRaises(UnicodeError):
            E.canonical_json("\ud800")
        with self.assertRaises(E.EvaluationError):
            E.loads('{"a":1,"a":2}')
        self.assertNotEqual(E.content_hash(" x\n"), E.content_hash("x\n"))

    def test_a08_full_plan_success_admission_and_restart_denominator(self):
        manifest = self.create(("success", "reject", "interrupt"))
        self.assertEqual(3, len(manifest["cases"]))
        self.assertEqual([], self.service.posts)
        result = E.execute(self.run, self.service.client)
        self.assertTrue(result["executionComplete"])
        self.assertEqual((3, 2, 2, 1, 0), tuple(result[k] for k in ("N_planned", "N_linked", "N_terminal", "N_admissionRejected", "N_scored")))
        self.assertEqual({"COMPLETED": 1, "FAILED": 1}, result["taskStatusDistribution"])
        self.assertEqual("TASK_RESTART_INTERRUPTED", result["cases"][2]["errorCode"])
        posts_before = list(self.service.posts)
        E.execute(self.run, self.service.client)
        self.assertEqual(posts_before, self.service.posts)
        self.assertEqual(result, E.report(self.run))

    def test_a07_lost_response_reuses_original_request_and_key(self):
        self.service.disconnect.add("success")
        self.create()
        result = E.execute(self.run, self.service.client)
        self.assertEqual(1, len(self.service.tasks))
        self.assertEqual(2, len(self.service.posts))
        self.assertEqual(self.service.posts[0], self.service.posts[1])
        self.assertEqual(1, result["cases"][0]["networkChecks"])

    def test_a09_sigkill_after_intent_and_after_post_before_link(self):
        for point in ("after_intent", "after_post"):
            with self.subTest(point=point):
                directory = self.root / point
                E.create_run(self.suite(("success", "fail")), directory, self.service.client)
                reached = self.root / (point + ".reached")
                child = multiprocessing.get_context("spawn").Process(
                    target=child_execute, args=(str(directory), self.service.base, point, str(reached)))
                child.start()
                try:
                    deadline = time.monotonic() + 10
                    while not reached.exists() and child.is_alive() and time.monotonic() < deadline:
                        time.sleep(0.01)
                    self.assertTrue(reached.exists(), "child must reach durable boundary")
                    before = E.read_json(directory / "manifest.json")
                    self.assertEqual(2, len(before["cases"]))
                    with self.assertRaisesRegex(E.EvaluationError, "RUN_LOCK_UNAVAILABLE"):
                        E.report(directory)
                    os.kill(child.pid, signal.SIGKILL)
                    child.join(5)
                    self.assertEqual(-signal.SIGKILL, child.exitcode)
                    result = E.execute(directory, self.service.client)
                    self.assertEqual(2, result["N_terminal"])
                    key = before["cases"][0]["clientRequestId"]
                    self.assertEqual(1 if point == "after_intent" else 2,
                                     sum(key == existing for existing, _ in self.service.posts))
                    self.assertEqual(1, sum(task["taskId"] == result["cases"][0]["taskId"] for task in self.service.tasks.values()))
                    posted = len(self.service.posts)
                    E.execute(directory, self.service.client)
                    self.assertEqual(posted, len(self.service.posts))
                finally:
                    if child.is_alive():
                        child.kill()
                        child.join(5)

    def test_a09_unknown_limit_is_three_checks_across_all_resumes(self):
        self.create(("always-unknown", "success"))
        result = E.execute(self.run, self.service.client)
        self.assertEqual(4, len(self.service.posts))  # one initial submission plus three checks
        self.assertEqual(1, len({key for key, _ in self.service.posts}))
        self.assertEqual("SUBMISSION_UNKNOWN", result["cases"][0]["state"])
        self.assertEqual("PLANNED", result["cases"][1]["state"])
        for _ in range(2):
            E.execute(self.run, self.service.client)
        self.assertEqual(4, len(self.service.posts))
        self.assertEqual(2, result["N_planned"])

    def test_unknown_submission_then_auth_or_access_error_never_claims_no_task(self):
        self.service.post_errors = [(503, "SYS_INTERNAL_ERROR"), (401, "AUTH_TOKEN_INVALID"),
                                    (404, "COMMON_NOT_FOUND"), (409, "TASK_IDEMPOTENCY_CONFLICT")]
        self.create()
        result = E.execute(self.run, self.service.client)
        self.assertEqual("SUBMISSION_UNKNOWN", result["cases"][0]["state"])
        self.assertEqual(0, result["N_admissionRejected"])
        self.assertEqual(1, len(self.service.tasks))  # the uncertain first POST really created it
        self.assertEqual(4, len(self.service.posts))
        E.execute(self.run, self.service.client)
        self.assertEqual(4, len(self.service.posts))

    def test_a10_observation_timeout_is_not_task_timeout_and_resume_only_observes(self):
        self.create(("running", "success"))
        result = E.execute(self.run, self.service.client)
        self.assertEqual("OBSERVATION_INCOMPLETE", result["cases"][0]["state"])
        self.assertEqual("RUNNING", result["cases"][0]["taskStatus"])
        self.assertEqual("PLANNED", result["cases"][1]["state"])
        E.execute(self.run, self.service.client)
        self.assertEqual(1, len(self.service.posts))
        self.assertFalse(any("cancel" in path for _, path in self.service.requests))
        next(iter(self.service.tasks.values()))["status"] = "COMPLETED"
        result = E.execute(self.run, self.service.client)
        self.assertEqual(2, len(self.service.posts))
        self.assertTrue(result["executionComplete"])

    def test_a10_incomplete_tail_repair_middle_corruption_and_manifest_change(self):
        self.create()
        with (self.run / "journal.jsonl").open("ab") as out:
            out.write(b'{"seq":2,"event":')
        self.assertGreater(E.report(self.run)["ignoredJournalTailBytes"], 0)
        self.assertEqual(1, E.execute(self.run, self.service.client)["N_terminal"])
        self.assertEqual(0, E.report(self.run)["ignoredJournalTailBytes"])
        original = (self.run / "journal.jsonl").read_bytes()
        (self.run / "journal.jsonl").write_bytes(original.replace(b"RUN_PLANNED", b"RUN_CHANGED", 1))
        with self.assertRaisesRegex(E.EvaluationError, "JOURNAL_CORRUPT"):
            E.report(self.run)
        (self.run / "journal.jsonl").write_bytes(original)
        manifest = E.read_json(self.run / "manifest.json")
        manifest["cases"][0]["userInput"] += "changed"
        E.atomic_json(self.run / "manifest.json", manifest)
        with self.assertRaisesRegex(E.EvaluationError, "MANIFEST_HASH_MISMATCH"):
            E.execute(self.run, self.service.client)

    def test_a10_bare_cr_in_middle_does_not_discard_subsequent_complete_records(self):
        self.create()
        E.execute(self.run, self.service.client)
        path = self.run / "journal.jsonl"
        raw = path.read_bytes()
        self.assertGreater(len(raw.split(b"\n")), 3)
        path.write_bytes(raw.replace(b"RUN_PLANNED", b"RUN_\rPLANNED", 1))
        with self.assertRaises(E.EvaluationError):
            E.report(self.run)

    def test_a11_actual_configuration_and_calls_not_copied_from_expected(self):
        self.create(environment={"applicationRevision": "expected-only", "effectiveConfigHash": "expected-only"})
        row = E.execute(self.run, self.service.client)["cases"][0]
        self.assertEqual("development", row["applicationRevision"])
        self.assertEqual("effective-actual", row["configuration"]["effectiveConfigHash"])
        self.assertFalse(row["strictBuildIdentity"]["available"])
        self.assertEqual("actual-controlled", row["actualModelCalls"][0]["resolvedModel"])
        self.assertEqual(["APPLICATION_REVISION_DRIFT", "EFFECTIVE_CONFIG_HASH_DRIFT"], row["environmentDrift"])

    def test_expected_snapshot_subset_and_unsupported_conditions_are_visible(self):
        self.create(("success", "success"), environment={
            "executionSnapshot": {"agent": {"systemPrompt": "expected prompt"}, "chatModel": {"model": "expected"}},
            "unknownCondition": "expected-only"})
        result = E.execute(self.run, self.service.client)
        row = result["cases"][0]
        self.assertEqual(["EXPECTED_CONDITION_UNSUPPORTED", "EXECUTION_SNAPSHOT_DRIFT"], row["environmentDrift"])
        self.assertEqual(["unknownCondition"], row["expectedConditionEvidence"]["unsupportedKeys"])
        self.assertEqual(["executionSnapshot.agent.systemPrompt", "executionSnapshot.chatModel"],
                         row["expectedConditionEvidence"]["snapshotMismatchPaths"])
        self.assertEqual("PLANNED", result["cases"][1]["state"])
        self.assertEqual(["ENVIRONMENT_DRIFT"], result["stopReasons"])

    def test_a13_sensitive_inputs_rejected_before_run_files_or_posts(self):
        for value in ("password=do-not-store", "Bearer " + TOKEN, "hello " + TOKEN,
                      "https://user:private-secret@example.invalid", '分析 {"apiKey":"private-secret"}'):
            with self.subTest(value_kind=value.split("=")[0][:8]):
                with self.assertRaisesRegex(E.EvaluationError, "SENSITIVE_OR_INVALID_PLAN"):
                    self.create((value,))
                self.assertFalse(self.run.exists())
                self.assertEqual([], self.service.posts)
        with self.assertRaisesRegex(E.EvaluationError, "SENSITIVE_OR_INVALID_PLAN"):
            E.create_run(self.suite(materials={TOKEN: "hidden-key"}), self.run, self.service.client)
        self.assertNotIn(TOKEN, E.canonical_json(E.sanitize({TOKEN: "hidden-key"}, (TOKEN,))))

    def test_a13_trace_and_errors_sanitized_owner_cannot_change(self):
        self.create(("success", "reject"))
        E.execute(self.run, self.service.client)
        for path in self.run.rglob("*"):
            if path.is_file():
                data = path.read_text()
                for secret in (TOKEN, "fixture-secret", "must-not-land", "exception-fixture-value"):
                    self.assertNotIn(secret, data)
                self.assertEqual(0, path.stat().st_mode & 0o077)
        self.service.owner = "11"
        before = len(self.service.posts)
        with self.assertRaisesRegex(E.EvaluationError, "RUN_OWNER_MISMATCH"):
            E.execute(self.run, self.service.client)
        self.assertEqual(before, len(self.service.posts))

    def test_a13_trace_access_denial_records_incomplete_without_internal_fallback(self):
        self.service.deny_trace = True
        self.create()
        result = E.execute(self.run, self.service.client)
        self.assertEqual("OBSERVATION_INCOMPLETE", result["cases"][0]["state"])
        self.assertEqual("TRACE_UNAVAILABLE_OR_NOT_CONVERGED", result["cases"][0]["reason"])
        self.assertEqual(1, len(self.service.posts))

    def test_budget_stops_with_unknown_usage_and_keeps_plan(self):
        self.create(("interrupt", "success"))
        result = E.execute(self.run, self.service.client)
        self.assertEqual("USAGE_NOT_EXACT", result["stopReasons"][0])
        self.assertEqual("PLANNED", result["cases"][1]["state"])
        self.assertEqual(2, result["N_planned"])
        self.assertEqual(1, len(self.service.posts))

    def test_budget_reserves_next_task_and_never_infers_headroom_from_unknown(self):
        suite = self.suite(("success", "success"))
        suite["budget"]["maxTotalTokens"] = 100
        E.create_run(suite, self.run, self.service.client)
        result = E.execute(self.run, self.service.client)
        self.assertEqual(["INSUFFICIENT_REMAINING_TOKEN_BUDGET"], result["stopReasons"])
        self.assertEqual("PLANNED", result["cases"][1]["state"])
        self.assertEqual(1, len(self.service.posts))

    def test_trials_are_predeclared_unique_and_original_input_unchanged(self):
        manifest = self.create(("  中文\n  ",), trials=2)
        self.assertEqual([1, 2], [case["trialIndex"] for case in manifest["cases"]])
        self.assertEqual(2, len({case["clientRequestId"] for case in manifest["cases"]}))
        E.execute(self.run, self.service.client)
        self.assertTrue(all(body["userInput"] == "  中文\n  " for _, body in self.service.posts))
        with self.assertRaisesRegex(E.EvaluationError, "RUN_DIRECTORY_ALREADY_EXISTS"):
            self.create()

    def test_report_checks_artifact_hash_and_private_run(self):
        self.create()
        E.execute(self.run, self.service.client)
        artifact = next((self.run / "artifacts").glob("*.json"))
        artifact.write_text('{}')
        with self.assertRaisesRegex(E.EvaluationError, "ARTIFACT_HASH_MISMATCH"):
            E.report(self.run)
        self.run.chmod(0o755)
        with self.assertRaisesRegex(E.EvaluationError, "RUN_DIRECTORY_MUST_BE_PRIVATE"):
            E.report(self.run)

    def test_cli_has_only_run_resume_report_and_no_credential_arguments(self):
        env = {**os.environ, "EVAL_TOKEN": TOKEN, "EVAL_BASE_URL": self.service.base}
        suite = self.root / "suite.json"
        E.atomic_json(suite, self.suite())
        result = subprocess.run([sys.executable, str(Path(E.__file__)), "run", "--suite", str(suite),
                                 "--run-dir", str(self.run)], env=env, text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, json.loads(result.stdout)["N_linked"])
        for command in ("resume", "report"):
            result = subprocess.run([sys.executable, str(Path(E.__file__)), command, "--run-dir", str(self.run)],
                                    env=env, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
        for command in ("compare", "episode"):
            result = subprocess.run([sys.executable, str(Path(E.__file__)), command, "--token", TOKEN],
                                    env=env, text=True, capture_output=True)
            self.assertEqual(1, result.returncode)
            self.assertNotIn(TOKEN, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
