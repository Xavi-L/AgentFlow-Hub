#!/usr/bin/env python3
"""Focused offline counterexamples for the collector, not browser E2E evidence."""

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("v48_evidence", Path(__file__).with_name("v48-storage-evidence.py"))
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


def controlled_failure():
    """Small independently specified FAILED fixture with a retained successful retrieval."""
    case_id, owner, task_id, agent_id = "F01_JSON", "41", "9007199254740993", "43"
    expected = {"agentId": agent_id, "status": "FAILED", "reason": "SYSTEM_ERROR",
                "errorCode": "AGENT_INVALID_DECISION", "decisions": 1, "tools": 0,
                "handlers": 0, "finals": 0, "maxSteps": 6, "maxToolCalls": 4,
                "maxTokens": 50000, "timeoutSeconds": 120}
    task = {"id": task_id, "user_id": owner, "agent_id": agent_id, "case_id": case_id,
            "client_request_id": "same-original-key", "status": "FAILED", "phase": None,
            "termination_reason": "SYSTEM_ERROR", "error_code": "AGENT_INVALID_DECISION",
            "max_decision_turns": 6, "max_tool_calls": 4, "max_total_tokens": 50000,
            "reserved_final_tokens": 64, "decision_turns_used": 1, "tool_calls_used": 0,
            "input_tokens": 10, "output_tokens": 5, "total_tokens": 15,
            "token_usage_quality": "EXACT", "final_answer": None, "citations": [],
            "started_at": "2026-09-09T00:00:00Z", "completed_at": "2026-09-09T00:00:01Z",
            "cancel_requested_at": None, "last_event_sequence": 3,
            "snapshot_version": "v1", "snapshot_agent_id": agent_id, "snapshot_decision_limit": 6,
            "snapshot_tool_limit": 4, "snapshot_token_limit": 50000, "timeout_seconds": 120,
            "retrieval": {"knowledgeBases": [{"knowledgeBaseId": "51", "documents": [
                {"documentId": "52", "vectorGeneration": 1}]}]}}
    step = lambda number, kind, status, error=None: {
        "id": str(number), "task_id": task_id, "step_index": number - 61, "step_type": kind,
        "status": status, "error_code": error, "ended_at": "2026-09-09T00:00:01Z", "summary": {}}
    storage = {"tasks": [task], "steps": [step(61, "PRE_RETRIEVAL", "SUCCESS"),
        step(62, "LLM_DECISION", "FAILED", "AGENT_INVALID_DECISION")],
        "llm_calls": [{"id": "71", "task_id": task_id, "step_id": "62", "call_type": "DECISION",
            "input_tokens": 10, "output_tokens": 5, "total_tokens": 15, "usage_quality": "EXACT",
            "status": "FAILED", "error_code": "AGENT_INVALID_DECISION", "max_output_tokens": 512}],
        "tool_calls": [], "retrievals": [{"id": "72", "task_id": task_id, "step_id": "61",
            "status": "SUCCESS", "error_code": None, "candidate_count": 1, "valid_hit_count": 1,
            "stale_hit_count": 0}], "hits": [{"id": "73", "task_id": task_id, "retrieval_id": "72",
            "rank_no": 1, "citation_id": "S1", "chunk_id": "53", "document_id": "52",
            "knowledge_base_id": "51", "vector_generation": 1, "has_content": True,
            "source_content_hash": "fixture-hash"}], "events": [],
        "documents": [{"id": "52", "knowledge_base_id": "51", "user_id": owner,
            "parse_status": "COMPLETED", "vector_generation": 1, "live": True}],
        "chunks": [{"id": "53", "document_id": "52", "knowledge_base_id": "51", "user_id": owner,
            "vector_generation": 1, "vectorization_status": "COMPLETED", "content_hash": "fixture-hash"}]}
    for sequence, kind, payload in ((1, "TASK_CREATED", {"status": "QUEUED"}),
            (2, "TASK_STARTED", {"status": "RUNNING", "phase": "PREPARING"}),
            (3, "TASK_FAILED", {"status": "FAILED", "terminationReason": "SYSTEM_ERROR",
                                 "errorCode": "AGENT_INVALID_DECISION"})):
        storage["events"].append({"id": str(80 + sequence), "task_id": task_id,
                                  "sequence_no": sequence, "event_type": kind, "payload": payload})
    public_task = {front: task.get(back) for front, back in evidence.TASK_FIELDS.items()}
    public_steps = []
    for row in storage["steps"]:
        public_steps.append({"id": row["id"], "stepType": row["step_type"], "status": row["status"],
                             "errorCode": row["error_code"], "llmCalls": [], "toolCalls": [], "ragRetrievals": []})
    public_steps[0]["ragRetrievals"] = [{"id": "72", "status": "SUCCESS", "candidateCount": 1,
                                       "validHitCount": 1, "staleHitCount": 0}]
    public_steps[1]["llmCalls"] = [{"id": "71", "callType": "DECISION", "status": "FAILED",
        "errorCode": "AGENT_INVALID_DECISION", "inputTokens": 10, "outputTokens": 5,
        "totalTokens": 15, "usageQuality": "EXACT"}]
    trace = {"task": deepcopy(public_task), "steps": public_steps, "events": [
        {"id": row["id"], "taskId": task_id, "sequenceNo": str(row["sequence_no"]),
         "eventType": row["event_type"], "payload": row["payload"]} for row in storage["events"]]}
    browser = {"caseId": case_id, "taskId": task_id, "agentId": agent_id, "status": "PASSED",
               "gatesSettled": True, "checks": [{"code": "offline_fixture", "passed": True}],
               "task": public_task, "trace": trace, "refreshedTask": deepcopy(public_task)}
    telemetry = [{"event": "llm.start", "caseId": case_id, "type": "DECISION", "callId": "d1",
                  "inputEstimate": 200, "maxOutputTokens": 512},
                 {"event": "llm.end", "caseId": case_id, "type": "DECISION", "callId": "d1",
                  "inputTokens": 10, "outputTokens": 5, "totalTokens": 15, "responseAvailable": True}]
    for component in ("embedding", "vector"):
        telemetry.extend({"event": component + suffix, "caseId": case_id, "callId": component + "1"}
                         for suffix in (".start", ".end"))
    for row in telemetry:
        row["taskId"] = task_id
    return case_id, expected, owner, storage, browser, telemetry


class EvidenceCounterexamples(unittest.TestCase):
    def test_expected_task_failure_can_pass_but_public_answer_or_extra_task_cannot(self):
        fixture = controlled_failure()
        checks = evidence.validate_case(*fixture)
        self.assertTrue(all(row["passed"] for row in checks), checks)
        duplicate = deepcopy(fixture)
        duplicate[3]["tasks"].append(deepcopy(duplicate[3]["tasks"][0]))
        self.assertFalse(evidence.validate_case(*duplicate)[0]["passed"])
        publication = deepcopy(fixture)
        publication[3]["events"].insert(2, {"id": "999", "task_id": fixture[3]["tasks"][0]["id"],
            "sequence_no": 3, "event_type": "ANSWER_CHUNK", "payload": {"chunkIndex": 0, "text": "late answer"}})
        checks = {row["code"]: row["passed"] for row in evidence.validate_case(*publication)}
        self.assertFalse(checks["failure_never_publishes_answer"])

    def test_truncated_usage_and_browser_disagreement_are_independently_rejected(self):
        fixture = controlled_failure()
        fixture[3]["tasks"][0]["total_tokens"] = 10
        checks = {row["code"]: row["passed"] for row in evidence.validate_case(*fixture)}
        self.assertFalse(checks["usage_aggregates_preserve_actual_consumption"])
        self.assertFalse(checks["get_matches_postgres"])

    def test_missing_required_case_never_becomes_full_matrix_pass(self):
        fixture = controlled_failure()
        case_id, expected, owner, storage, browser, telemetry = fixture
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            evidence.write_json(run / "manifest.json", {"schemaVersion": "v48-failure-recovery-v1",
                "ownerId": owner, "password": "must-never-be-copied", "cases": {case_id: expected}})
            evidence.write_json(run / "cases" / case_id / "browser-evidence.json", browser)
            (run / "cases" / case_id / "calls.jsonl").write_text(
                "".join(json.dumps(row) + "\n" for row in telemetry), encoding="utf-8")
            with patch.object(evidence, "postgres_snapshot", return_value=storage):
                self.assertEqual(evidence.main(["--run-dir", directory]), 1)
            text = (run / "storage-evidence.json").read_text(encoding="utf-8")
            result = json.loads(text)
            self.assertFalse(result["fullMatrixPassed"])
            self.assertEqual(result["cases"]["F01_SHAPE"]["status"], "NOT_RUN")
            self.assertNotIn("must-never-be-copied", text)

    def test_snowflake_numbers_remain_lossless_and_collection_never_limits_rows(self):
        self.assertTrue(evidence.same("9007199254740993", 9007199254740993))
        self.assertFalse(evidence.same("9007199254740993", 9007199254740992))
        sql = evidence.evidence_sql()
        self.assertIn("ISOLATION LEVEL REPEATABLE READ READ ONLY", sql)
        self.assertNotIn("LIMIT", sql)
        self.assertNotIn("SELECT *", sql)
        self.assertNotIn("FROM app_user", sql)


if __name__ == "__main__":
    unittest.main()
