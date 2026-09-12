#!/usr/bin/env python3
"""V0.3-A local evaluation coordination. No scoring or task execution internals."""
from __future__ import annotations

import argparse
import collections
import contextlib
import datetime as dt
from decimal import Decimal
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ALGORITHM = "config-canonical-json-v1"
TERMINALS = {"COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"}
FINAL_COORDINATION = {"TASK_TERMINAL", "ADMISSION_REJECTED"}
SECRET_KEYS = {"authorization", "proxyauthorization", "cookie", "setcookie", "apikey",
               "xapikey", "password", "secret", "clientsecret", "accesstoken", "refreshtoken",
               "credentials", "connectionstring", "jdbcurl", "headers", "endpoint", "baseurl"}
SECRET_TEXT = re.compile(
    r"(?im)\b(?:authorization|proxy[-_ ]authorization|cookie|set[-_ ]cookie|api[-_ ]?key|"
    r"password|secret|client[-_ ]secret|access[-_ ]token|refresh[-_ ]token)\b[\"']?\s*[:=][^\r\n]*"
    r"|\bBearer\s+[^\s\"']+|\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"
    r"|\bsk-[A-Za-z0-9_-]{12,}|\b(?:https?|jdbc)://[^\s/@]+:[^\s/@]+@[^\s]*")


class EvaluationError(Exception):
    """Only constant, non-secret error codes are allowed to cross the CLI boundary."""


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def canonical_json(value):
    """Canonical primitives only; schema owners sort explicitly unordered bindings first."""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float, Decimal)):
        number = Decimal(str(value))
        if not number.is_finite():
            raise EvaluationError("NON_FINITE_NUMBER")
        if number == 0:
            return "0"
        result = format(number, "f")
        return result.rstrip("0").rstrip(".") if "." in result else result
    if isinstance(value, str):
        value.encode("utf-8", errors="strict")
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, list):
        return "[" + ",".join(canonical_json(item) for item in value) + "]"
    if isinstance(value, dict) and all(isinstance(key, str) for key in value):
        return "{" + ",".join(canonical_json(key) + ":" + canonical_json(value[key])
                               for key in sorted(value)) + "}"
    raise EvaluationError("INVALID_CANONICAL_VALUE")


def content_hash(value):
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise EvaluationError("DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def loads(data):
    def invalid(_value):
        raise EvaluationError("NON_FINITE_NUMBER")
    try:
        return json.loads(data, parse_float=Decimal, object_pairs_hook=_pairs, parse_constant=invalid)
    except (ValueError, UnicodeError):
        raise EvaluationError("INVALID_JSON") from None


def read_json(path):
    if path.is_symlink():
        raise EvaluationError("SYMLINK_NOT_ALLOWED")
    try:
        return loads(path.read_bytes())
    except OSError:
        raise EvaluationError("FILE_READ_FAILED") from None


def sensitive(value, secrets=()):
    if isinstance(value, dict):
        return any(re.sub(r"[^a-z]", "", k.lower()) in SECRET_KEYS or sensitive(k, secrets) or sensitive(v, secrets)
                   for k, v in value.items())
    if isinstance(value, list):
        return any(sensitive(v, secrets) for v in value)
    return isinstance(value, str) and (bool(SECRET_TEXT.search(value)) or
                                      any(secret and secret in value for secret in secrets))


def sanitize(value, secrets=()):
    if isinstance(value, dict):
        return {k: "[REDACTED]" if re.sub(r"[^a-z]", "", k.lower()) in SECRET_KEYS
                else sanitize(v, secrets) for k, v in value.items() if not sensitive(k, secrets)}
    if isinstance(value, list):
        return [sanitize(v, secrets) for v in value]
    if isinstance(value, str):
        value = SECRET_TEXT.sub("[REDACTED]", value)
        for secret in secrets:
            if secret:
                value = value.replace(secret, "[REDACTED]")
    return value


def fsync_directory(directory):
    fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_bytes(path, data):
    if path.is_symlink():
        raise EvaluationError("SYMLINK_NOT_ALLOWED")
    temporary = path.with_name("." + path.name + "." + uuid.uuid4().hex + ".tmp")
    fd = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
        fsync_directory(path.parent)
    finally:
        with contextlib.suppress(FileNotFoundError):
            temporary.unlink()


def atomic_json(path, value):
    atomic_bytes(path, (canonical_json(value) + "\n").encode("utf-8"))


class RunLock:
    def __init__(self, directory):
        self.directory, self.fd = Path(directory), None

    def __enter__(self):
        if self.directory.is_symlink() or not self.directory.is_dir():
            raise EvaluationError("RUN_DIRECTORY_INVALID")
        stat = self.directory.stat()
        if stat.st_uid != os.getuid() or stat.st_mode & 0o077:
            raise EvaluationError("RUN_DIRECTORY_MUST_BE_PRIVATE")
        try:
            self.fd = os.open(self.directory / ".lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (OSError, BlockingIOError):
            if self.fd is not None:
                os.close(self.fd)
                self.fd = None
            raise EvaluationError("RUN_LOCK_UNAVAILABLE") from None
        return self

    def __exit__(self, *_args):
        fcntl.flock(self.fd, fcntl.LOCK_UN)
        os.close(self.fd)


def load_manifest(directory):
    artifacts = directory / "artifacts"
    if artifacts.is_symlink() or not artifacts.is_dir():
        raise EvaluationError("ARTIFACT_DIRECTORY_INVALID")
    if artifacts.stat().st_uid != os.getuid() or artifacts.stat().st_mode & 0o077:
        raise EvaluationError("ARTIFACT_DIRECTORY_MUST_BE_PRIVATE")
    manifest = read_json(directory / "manifest.json")
    if not isinstance(manifest, dict):
        raise EvaluationError("MANIFEST_INVALID")
    claimed = manifest.get("manifestHash")
    actual = content_hash({k: v for k, v in manifest.items() if k != "manifestHash"})
    if claimed != actual or manifest.get("schemaVersion") != "agent-eval-run-v1":
        raise EvaluationError("MANIFEST_HASH_MISMATCH")
    return manifest


class Journal:
    def __init__(self, directory, repair_tail=False):
        self.path = directory / "journal.jsonl"
        if self.path.is_symlink():
            raise EvaluationError("SYMLINK_NOT_ALLOWED")
        raw = self.path.read_bytes()
        self.records, valid_bytes = [], 0
        # JSONL is framed only by LF. splitlines() would treat an injected bare
        # CR as a boundary and silently discard complete records after it.
        for segment in raw.split(b"\n")[:-1]:
            line = segment + b"\n"
            row = loads(line)
            if not isinstance(row, dict):
                raise EvaluationError("JOURNAL_CORRUPT")
            expected_previous = self.records[-1]["recordHash"] if self.records else None
            if (row.get("seq") != len(self.records) + 1 or row.get("previousHash") != expected_previous
                    or row.get("recordHash") != content_hash({k: v for k, v in row.items() if k != "recordHash"})):
                raise EvaluationError("JOURNAL_CORRUPT")
            self.records.append(row)
            valid_bytes += len(line)
        self.ignored_tail_bytes = len(raw) - valid_bytes
        if repair_tail and self.ignored_tail_bytes:
            with self.path.open("r+b") as out:
                out.truncate(valid_bytes)
                out.flush()
                os.fsync(out.fileno())

    def append(self, event, case_id=None, **data):
        row = {"seq": len(self.records) + 1, "at": now(), "event": event,
               "caseExecutionId": case_id, "data": data,
               "previousHash": self.records[-1]["recordHash"] if self.records else None}
        row["recordHash"] = content_hash(row)
        with self.path.open("ab") as out:
            out.write((canonical_json(row) + "\n").encode("utf-8"))
            out.flush()
            os.fsync(out.fileno())
        self.records.append(row)
        return row


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


class HttpClient:
    def __init__(self, base_url, token):
        parsed = urllib.parse.urlsplit(base_url)
        if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username
                or parsed.password or parsed.query or parsed.fragment or parsed.path.rstrip("/") not in ("", "/api/v1")):
            raise EvaluationError("INVALID_SERVICE_ADDRESS")
        if not token or "\n" in token or "\r" in token:
            raise EvaluationError("AUTH_ENVIRONMENT_REQUIRED")
        self.base = base_url.rstrip("/")
        if not self.base.endswith("/api/v1"):
            self.base += "/api/v1"
        self.token, self.secrets = token, (token,)
        self.endpoint_hash = hashlib.sha256(self.base.encode()).hexdigest()
        self.opener = urllib.request.build_opener(NoRedirect)

    @classmethod
    def from_environment(cls):
        return cls(os.environ.get("EVAL_BASE_URL", ""), os.environ.get("EVAL_TOKEN", ""))

    def request(self, method, path, body=None, key=None, timeout=10):
        headers = {"Authorization": "Bearer " + self.token, "Accept": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, method=method, headers=headers,
                                         data=None if body is None else canonical_json(body).encode())
        try:
            try:
                response = self.opener.open(request, timeout=float(timeout))
            except urllib.error.HTTPError as exc:
                response = exc
            with response:
                status = response.code
                # No response/exception text is printed or persisted on transport failure.
                value = loads(response.read(16 * 1024 * 1024 + 1))
                return status, value
        except (OSError, ValueError, EvaluationError, urllib.error.URLError):
            return None, None


def require_data(client, path, timeout=10):
    status, body = client.request("GET", path, timeout=timeout)
    if status != 200 or not isinstance(body, dict) or body.get("code") != "OK" or not isinstance(body.get("data"), dict):
        raise EvaluationError("READ_PREFLIGHT_FAILED")
    return body["data"]


def positive_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[1-9][0-9]*", value):
        raise EvaluationError("INVALID_RESOURCE_ID")
    return value


def positive_number(value, integral=False):
    if isinstance(value, bool) or not isinstance(value, (int, Decimal, float)) or not math.isfinite(float(value)) or value <= 0:
        raise EvaluationError("INVALID_BUDGET")
    if integral and (not isinstance(value, int) or isinstance(value, bool)):
        raise EvaluationError("INVALID_BUDGET")
    return value


def cli_identity():
    root = Path(__file__).resolve().parent.parent
    result = {"scriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "gitSha": None, "dirty": None}
    try:
        result["gitSha"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root,
                                                    stderr=subprocess.DEVNULL, timeout=3).decode().strip()
        result["dirty"] = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root,
                                                       stderr=subprocess.DEVNULL, timeout=3).strip())
    except (OSError, subprocess.SubprocessError):
        pass
    return result


def create_run(suite, run_dir, client):
    """Read-only preflight, then durably register every request before any task POST."""
    if not isinstance(suite, dict) or sensitive(suite, client.secrets):
        raise EvaluationError("SENSITIVE_OR_INVALID_PLAN")
    agent = positive_id(suite.get("agentId"))
    version_id = positive_id(suite.get("configVersionId"))
    cases = suite.get("cases")
    trials = positive_number(suite.get("trials", 1), integral=True)
    if not isinstance(cases, list) or not cases or trials > 100:
        raise EvaluationError("INVALID_CASE_PLAN")
    ids = []
    for case in cases:
        if (not isinstance(case, dict) or not isinstance(case.get("caseId"), str) or not case["caseId"]
                or not isinstance(case.get("userInput"), str) or not case["userInput"].strip()):
            raise EvaluationError("INVALID_CASE_PLAN")
        ids.append(case["caseId"])
    if len(set(ids)) != len(ids):
        raise EvaluationError("DUPLICATE_CASE_ID")
    dataset = suite.get("dataset")
    if not isinstance(dataset, dict) or not dataset.get("datasetId") or not dataset.get("version"):
        raise EvaluationError("DATASET_IDENTITY_REQUIRED")
    if suite.get("suiteMode") not in ("CONTROLLED_CONTRACT", "REAL_PROVIDER"):
        raise EvaluationError("SUITE_MODE_REQUIRED")
    if not isinstance(suite.get("environment", {}), dict):
        raise EvaluationError("INVALID_ENVIRONMENT_EXPECTATION")
    budget = dict(suite.get("budget", {}))
    total = len(cases) * trials
    budget.setdefault("maxPlannedTasks", total)
    budget.setdefault("observationTimeoutSeconds", 60)
    budget.setdefault("requestTimeoutSeconds", 10)
    budget.setdefault("pollIntervalSeconds", 1)
    budget.setdefault("maxNetworkChecks", 3)
    for name in ("maxPlannedTasks", "maxTotalTokens", "maxNetworkChecks"):
        positive_number(budget.get(name), integral=True)
    for name in ("observationTimeoutSeconds", "requestTimeoutSeconds", "pollIntervalSeconds"):
        positive_number(budget[name])
    if total > budget["maxPlannedTasks"] or total > 10000 or budget["maxNetworkChecks"] > 3:
        raise EvaluationError("PLAN_EXCEEDS_LIMIT")
    owner = require_data(client, "/users/me")
    owner_id = positive_id(owner.get("id"))
    version = require_data(client, f"/agents/{agent}/config-versions/{version_id}")
    if (version.get("configVersionId") != version_id or version.get("agentId") != agent
            or version.get("schemaVersion") != "agent-config-v1" or version.get("hashAlgorithmVersion") != ALGORITHM
            or not isinstance(version.get("config"), dict)
            or content_hash(version["config"]) != version.get("configHash")):
        raise EvaluationError("CONFIG_VERSION_IDENTITY_MISMATCH")
    if suite.get("configHash", version["configHash"]) != version["configHash"]:
        raise EvaluationError("CONFIG_VERSION_IDENTITY_MISMATCH")
    if sensitive(version, client.secrets):
        raise EvaluationError("SENSITIVE_CONFIGURATION")
    per_task = version["config"].get("budgets", {})
    positive_number(per_task.get("maxTotalTokens"), integral=True)
    if budget["maxTotalTokens"] < per_task["maxTotalTokens"]:
        raise EvaluationError("INSUFFICIENT_INITIAL_BUDGET")
    run_id = str(uuid.uuid4())
    executions = []
    for case in cases:
        for trial in range(1, trials + 1):
            execution_id = str(uuid.uuid5(uuid.UUID(run_id), canonical_json([case["caseId"], trial])))
            key = "eval-" + str(uuid.uuid4())
            executions.append({"caseExecutionId": execution_id, "caseId": case["caseId"], "trialIndex": trial,
                               "userInput": case["userInput"], "expected": case.get("expected"),
                               "requiredJudgments": case.get("requiredJudgments", []),
                               "clientRequestId": key,
                               "request": {"method": "POST", "path": f"/agents/{agent}/tasks",
                                           "headers": {"Idempotency-Key": key},
                                           "body": {"userInput": case["userInput"], "configVersionId": version_id}}})
    manifest = {"schemaVersion": "agent-eval-run-v1", "hashAlgorithmVersion": ALGORITHM,
                "evalRunId": run_id, "createdAt": now(), "ownerId": owner_id,
                "suiteMode": suite["suiteMode"], "cliBuild": cli_identity(),
                "configuration": {"agentId": agent, "configVersionId": version_id,
                                  "configHash": version["configHash"], "hashAlgorithmVersion": ALGORITHM},
                "dataset": {**dataset, "contentHash": content_hash(cases)},
                "judgment": {"ruleSet": suite.get("ruleSet"), "rubric": suite.get("rubric"),
                             "metricSchema": suite.get("metricSchema"), "status": "NOT_EVALUATED"},
                "materials": suite.get("materials", {"businessFixture": None, "corpusManifest": None}),
                "environment": {"expected": suite.get("environment", {}), "endpointHash": client.endpoint_hash,
                                "concurrency": 1, "order": "DECLARED", "randomSeed": None},
                "budget": budget, "perTaskBudget": per_task, "plannedTaskCount": total,
                "plannedMaximumTokens": total * per_task["maxTotalTokens"], "cases": executions}
    manifest["manifestHash"] = content_hash(manifest)
    directory = Path(run_dir)
    try:
        directory.mkdir(mode=0o700)
    except FileExistsError:
        raise EvaluationError("RUN_DIRECTORY_ALREADY_EXISTS") from None
    fsync_directory(directory.parent)
    with RunLock(directory):
        (directory / "artifacts").mkdir(mode=0o700)
        atomic_bytes(directory / "journal.jsonl", b"")
        atomic_json(directory / "manifest.json", manifest)
        Journal(directory).append("RUN_PLANNED", plannedTaskCount=total)
    return manifest


def states(manifest, journal):
    result = {c["caseExecutionId"]: {"state": "PLANNED", "networkChecks": 0, "taskId": None}
              for c in manifest["cases"]}
    for row in journal.records:
        if row["caseExecutionId"] is None:
            continue
        if row["caseExecutionId"] not in result:
            raise EvaluationError("JOURNAL_UNKNOWN_CASE")
        state = result[row["caseExecutionId"]]
        event, data = row["event"], row["data"]
        if event == "SUBMISSION_CHECK":
            if state["taskId"] or state["state"] not in ("SUBMITTING", "SUBMISSION_UNKNOWN"):
                raise EvaluationError("JOURNAL_INVALID_TRANSITION")
            if data["networkCheck"] != state["networkChecks"] + 1 or data["networkCheck"] > manifest["budget"]["maxNetworkChecks"]:
                raise EvaluationError("JOURNAL_INVALID_TRANSITION")
            state["networkChecks"] = data["networkCheck"]
        elif event in ("SUBMITTING", "SUBMISSION_UNKNOWN", "ADMISSION_REJECTED", "TASK_LINKED",
                       "OBSERVATION_INCOMPLETE", "TASK_TERMINAL"):
            if state["state"] in FINAL_COORDINATION:
                raise EvaluationError("JOURNAL_INVALID_TRANSITION")
            if event == "SUBMITTING" and state["state"] != "PLANNED":
                raise EvaluationError("JOURNAL_INVALID_TRANSITION")
            if state["taskId"] and event in ("SUBMITTING", "SUBMISSION_UNKNOWN", "ADMISSION_REJECTED", "TASK_LINKED"):
                raise EvaluationError("JOURNAL_INVALID_TRANSITION")
            state.update(data)
            state["state"] = event
        else:
            raise EvaluationError("JOURNAL_UNKNOWN_EVENT")
    return result


def submit(case, state, manifest, journal, client, hook):
    case_id, request = case["caseExecutionId"], case["request"]
    if state["state"] == "PLANNED":
        journal.append("SUBMITTING", case_id, requestHash=content_hash(request))
        hook("after_intent", case)
        first = True
    else:
        first = False
    while True:
        state = states(manifest, journal)[case_id]
        if not first:
            if state["networkChecks"] >= manifest["budget"]["maxNetworkChecks"]:
                return
            journal.append("SUBMISSION_CHECK", case_id, networkCheck=state["networkChecks"] + 1)
        status, response = client.request(request["method"], request["path"], request["body"],
                                           request["headers"]["Idempotency-Key"],
                                           manifest["budget"]["requestTimeoutSeconds"])
        hook("after_post", case)
        data = response.get("data") if isinstance(response, dict) else None
        if status in (200, 201) and isinstance(response, dict) and response.get("code") == "OK" and isinstance(data, dict):
            task_id = data.get("taskId")
            if isinstance(task_id, str) and re.fullmatch(r"[1-9][0-9]*", task_id) and data.get("agentId") == manifest["configuration"]["agentId"]:
                journal.append("TASK_LINKED", case_id, taskId=task_id)
                return
        code = response.get("code") if isinstance(response, dict) else None
        # Once delivery is uncertain, even a later 401/404/409 cannot prove the
        # original request was not created. Only the initial definite response rejects.
        if first and status is not None and 400 <= status < 500 and isinstance(code, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,100}", code) and data is None:
            journal.append("ADMISSION_REJECTED", case_id, admissionError={"httpStatus": status, "code": code})
            return
        journal.append("SUBMISSION_UNKNOWN", case_id, reason="RESPONSE_UNCONFIRMED", httpStatus=status)
        first = False


def evidence_summary(task, trace, manifest):
    snapshot = trace.get("executionSnapshot", {})
    actual = task.get("configuration")
    drift = []
    if not isinstance(actual, dict):
        drift.append("CONFIGURATION_IDENTITY_MISSING")
    else:
        for key in ("configVersionId", "configHash", "hashAlgorithmVersion"):
            if actual.get(key) != manifest["configuration"].get(key):
                drift.append("CONFIGURATION_" + key + "_DRIFT")
    revision = snapshot.get("runtime", {}).get("applicationRevision")
    expected = manifest["environment"]["expected"]
    unsupported = sorted(set(expected) - {"applicationRevision", "effectiveConfigHash", "executionSnapshot"})
    mismatches = []
    if "executionSnapshot" in expected:
        def compare_subset(wanted, observed, path):
            if isinstance(wanted, dict):
                if not isinstance(observed, dict):
                    mismatches.append(path)
                else:
                    for key, value in wanted.items():
                        if key not in observed:
                            mismatches.append(path + "." + key)
                        else:
                            compare_subset(value, observed[key], path + "." + key)
            elif isinstance(wanted, list):
                if not isinstance(observed, list) or len(wanted) != len(observed):
                    mismatches.append(path)
                else:
                    for index, (a, b) in enumerate(zip(wanted, observed)):
                        compare_subset(a, b, path + "[" + str(index) + "]")
            elif canonical_json(wanted) != canonical_json(observed):
                mismatches.append(path)
        compare_subset(expected["executionSnapshot"], snapshot, "executionSnapshot")
    if unsupported:
        drift.append("EXPECTED_CONDITION_UNSUPPORTED")
    if mismatches:
        drift.append("EXECUTION_SNAPSHOT_DRIFT")
    if "applicationRevision" in expected and expected["applicationRevision"] != revision:
        drift.append("APPLICATION_REVISION_DRIFT")
    if "effectiveConfigHash" in expected and (actual or {}).get("effectiveConfigHash") != expected["effectiveConfigHash"]:
        drift.append("EFFECTIVE_CONFIG_HASH_DRIFT")
    calls = [{key: call.get(key) for key in ("id", "callType", "provider", "requestedModel", "resolvedModel", "status", "usageQuality")}
             for step in trace.get("steps", []) for call in step.get("llmCalls", [])]
    return {"taskStatus": task["status"], "terminationReason": task.get("terminationReason"),
            "errorCode": task.get("errorCode"), "recovery": task.get("recovery"),
            "configuration": actual, "applicationRevision": revision,
            # The ordinary API does not attest server clean/dirty or image digest. CLI Git is not server Git.
            "strictBuildIdentity": {"available": False, "reason": "SERVER_BUILD_EVIDENCE_MISSING"},
            "usage": {key: task.get(key) for key in ("inputTokens", "outputTokens", "totalTokens", "tokenUsageQuality")},
            "actualModelCalls": calls, "environmentDrift": drift,
            "expectedConditionEvidence": {"unsupportedKeys": unsupported, "snapshotMismatchPaths": mismatches}}


def observe(case, state, manifest, journal, directory, client):
    deadline = time.monotonic() + float(manifest["budget"]["observationTimeoutSeconds"])
    task_id, last_reason = state["taskId"], "OBSERVATION_DEADLINE"
    while time.monotonic() < deadline:
        timeout = min(float(manifest["budget"]["requestTimeoutSeconds"]), max(0.001, deadline - time.monotonic()))
        status, envelope = client.request("GET", f"/tasks/{task_id}", timeout=timeout)
        task = envelope.get("data") if status == 200 and isinstance(envelope, dict) and envelope.get("code") == "OK" else None
        if isinstance(task, dict) and task.get("taskId") == task_id:
            timeout = min(float(manifest["budget"]["requestTimeoutSeconds"]), max(0.001, deadline - time.monotonic()))
            trace_status, trace_envelope = client.request("GET", f"/tasks/{task_id}/trace", timeout=timeout)
            trace = trace_envelope.get("data") if trace_status == 200 and isinstance(trace_envelope, dict) and trace_envelope.get("code") == "OK" else None
            trace_task = trace.get("task", {}) if isinstance(trace, dict) else {}
            if (isinstance(trace, dict) and isinstance(trace.get("executionSnapshot"), dict)
                    and trace_task.get("taskId") == task_id and trace_task.get("status") == task.get("status")
                    and trace_task.get("lastEventSequence") == task.get("lastEventSequence")
                    and trace_task.get("configuration") == task.get("configuration")):
                evidence = sanitize({"observedAt": now(), "task": task, "trace": trace}, client.secrets)
                artifact_name = f"{case['caseExecutionId']}-{len(journal.records) + 1}.json"
                atomic_json(directory / "artifacts" / artifact_name, evidence)
                summary = sanitize(evidence_summary(task, trace, manifest), client.secrets)
                event = "TASK_TERMINAL" if task.get("status") in TERMINALS else "OBSERVATION_INCOMPLETE"
                if event == "TASK_TERMINAL":
                    journal.append(event, case["caseExecutionId"], artifact="artifacts/" + artifact_name,
                                   artifactHash=content_hash(evidence), **summary)
                    return
                # Keep the most recent observation only when the observation deadline is reached.
                last_reason = "TASK_NOT_TERMINAL"
                last_observation = {"artifact": "artifacts/" + artifact_name, "artifactHash": content_hash(evidence), **summary}
            else:
                last_reason = "TRACE_UNAVAILABLE_OR_NOT_CONVERGED"
        else:
            last_reason = "TASK_READ_UNAVAILABLE"
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(float(manifest["budget"]["pollIntervalSeconds"]), remaining))
    journal.append("OBSERVATION_INCOMPLETE", case["caseExecutionId"], reason=last_reason,
                   **locals().get("last_observation", {}))


def stop_reason(manifest, current):
    used = 0
    for state in current.values():
        if state.get("environmentDrift"):
            return "ENVIRONMENT_DRIFT"
        if state["state"] in ("SUBMITTING", "SUBMISSION_UNKNOWN"):
            return "SUBMISSION_UNCONFIRMED"
        if state["state"] in ("TASK_LINKED", "OBSERVATION_INCOMPLETE"):
            return "OBSERVATION_INCOMPLETE"
        if state["state"] == "TASK_TERMINAL":
            usage = state.get("usage", {})
            if usage.get("tokenUsageQuality") != "EXACT" or not isinstance(usage.get("totalTokens"), int):
                return "USAGE_NOT_EXACT"
            used += usage["totalTokens"]
    if used + manifest["perTaskBudget"]["maxTotalTokens"] > manifest["budget"]["maxTotalTokens"]:
        return "INSUFFICIENT_REMAINING_TOKEN_BUDGET"
    return None


def execute(run_dir, client, hook=lambda _point, _case: None):
    directory = Path(run_dir)
    with RunLock(directory):
        manifest = load_manifest(directory)
        if sensitive({k: v for k, v in manifest.items() if k != "cases"}, client.secrets) or any(
                sensitive(c["request"]["body"], client.secrets) for c in manifest["cases"]):
            raise EvaluationError("SENSITIVE_MANIFEST")
        if manifest["environment"]["endpointHash"] != client.endpoint_hash:
            raise EvaluationError("SERVICE_IDENTITY_MISMATCH")
        if require_data(client, "/users/me").get("id") != manifest["ownerId"]:
            raise EvaluationError("RUN_OWNER_MISMATCH")
        journal = Journal(directory, repair_tail=True)
        for case in manifest["cases"]:
            current = states(manifest, journal)
            state = current[case["caseExecutionId"]]
            if state["state"] in FINAL_COORDINATION:
                continue
            if state["state"] == "PLANNED":
                reason = stop_reason(manifest, current)
                if reason:
                    journal.append("RUN_STOPPED", reason=reason)
                    break
            if state["state"] in ("PLANNED", "SUBMITTING", "SUBMISSION_UNKNOWN"):
                submit(case, state, manifest, journal, client, hook)
            state = states(manifest, journal)[case["caseExecutionId"]]
            if state["taskId"]:
                observe(case, state, manifest, journal, directory, client)
        return _report(directory, manifest, journal)


def _report(directory, manifest, journal):
    current = states(manifest, journal)
    rows = []
    for case in manifest["cases"]:
        state = current[case["caseExecutionId"]]
        if state.get("artifact"):
            path = Path(state["artifact"])
            if path.parts[0] != "artifacts" or len(path.parts) != 2 or path.name in (".", ".."):
                raise EvaluationError("ARTIFACT_PATH_INVALID")
            if content_hash(read_json(directory / path)) != state["artifactHash"]:
                raise EvaluationError("ARTIFACT_HASH_MISMATCH")
        rows.append({"caseExecutionId": case["caseExecutionId"], "caseId": case["caseId"],
                     "trialIndex": case["trialIndex"], "clientRequestId": case["clientRequestId"],
                     "expected": case["expected"], **state})
    distribution = dict(collections.Counter(row["state"] for row in rows))
    task_distribution = dict(collections.Counter(row["taskStatus"] for row in rows if row.get("taskStatus")))
    complete = all(row["state"] in FINAL_COORDINATION for row in rows)
    result = {"schemaVersion": "agent-eval-report-v1", "evalRunId": manifest["evalRunId"],
              "manifestHash": manifest["manifestHash"], "suiteMode": manifest["suiteMode"],
              "executionComplete": complete, "qualityEvaluation": "NOT_EVALUATED",
              "N_planned": len(rows), "N_submitted": sum(row["state"] != "PLANNED" for row in rows),
              "N_admissionRejected": distribution.get("ADMISSION_REJECTED", 0),
              "N_linked": sum(row["taskId"] is not None for row in rows),
              "N_terminal": distribution.get("TASK_TERMINAL", 0),
              "N_incomplete": sum(row["state"] not in FINAL_COORDINATION for row in rows), "N_scored": 0,
              "coordinationDistribution": distribution, "taskStatusDistribution": task_distribution,
              "budget": manifest["budget"], "perTaskBudget": manifest["perTaskBudget"],
              "stopReasons": list(dict.fromkeys(r["data"]["reason"] for r in journal.records if r["event"] == "RUN_STOPPED")),
              "ignoredJournalTailBytes": journal.ignored_tail_bytes, "cases": rows}
    atomic_json(directory / "report.json", result)
    lines = ["# Evaluation run " + manifest["evalRunId"], "", "Suite: " + manifest["suiteMode"],
             "", "Engineering association only; quality is NOT_EVALUATED (0 scored cases).",
             "", f"Planned: {len(rows)}; linked: {result['N_linked']}; terminal: {result['N_terminal']}; "
             f"admission rejected: {result['N_admissionRejected']}; incomplete: {result['N_incomplete']}.",
             "", "Execution complete: " + str(complete).lower(), "",
             "| Case / trial | Coordination | Task | Persisted task status | Reason |", "| --- | --- | --- | --- | --- |"]
    def cell(value):
        return str(value or "—").replace("|", "\\|").replace("\n", " ").replace("\r", " ").replace("<", "&lt;")
    for row in rows:
        reason = row.get("admissionError", {}).get("code") or row.get("reason") or row.get("errorCode") or row.get("terminationReason")
        lines.append("| " + " | ".join(cell(v) for v in (row["caseId"] + " / " + str(row["trialIndex"]),
                                                         row["state"], row["taskId"], row.get("taskStatus"), reason)) + " |")
    lines += ["", "Stop reasons: " + ", ".join(result["stopReasons"] or ["none"]), "",
              "Task/Trace evidence is in artifacts/. applicationRevision alone does not attest server build identity.",
              "Expected conditions remain in manifest.json; actual configuration, usage and drift remain per case.", ""]
    atomic_bytes(directory / "report.md", "\n".join(lines).encode())
    return result


def report(run_dir):
    directory = Path(run_dir)
    with RunLock(directory):
        manifest = load_manifest(directory)
        return _report(directory, manifest, Journal(directory))


class SafeParser(argparse.ArgumentParser):
    def error(self, _message):
        raise EvaluationError("INVALID_ARGUMENTS")


def main(argv=None):
    parser = SafeParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("run", "resume", "report"):
        child = commands.add_parser(command)
        child.add_argument("--run-dir", required=True, type=Path)
        if command == "run":
            child.add_argument("--suite", required=True, type=Path)
    try:
        args = parser.parse_args(argv)
        if args.command == "report":
            result = report(args.run_dir)
        else:
            client = HttpClient.from_environment()
            if args.command == "run":
                create_run(read_json(args.suite), args.run_dir, client)
            result = execute(args.run_dir, client)
        print(canonical_json({k: result[k] for k in ("evalRunId", "executionComplete", "N_planned", "N_linked", "N_incomplete", "N_scored")}))
        return 0 if result["executionComplete"] else 2
    except EvaluationError as exc:
        print("Evaluation error: " + str(exc), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Evaluation interrupted; resume the same run directory.", file=sys.stderr)
        return 2
    except Exception:
        print("Evaluation error: OPERATION_FAILED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
