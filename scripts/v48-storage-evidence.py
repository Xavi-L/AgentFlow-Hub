#!/usr/bin/env python3
"""Independent, read-only V48 PostgreSQL and fixture-call evidence.

The only database target is the disposable loopback V48 database. Expected task
failures can pass their case; missing evidence, partial runs and collector errors
can never establish full-matrix acceptance. No third-party Python packages needed.
"""

import argparse
from collections import Counter
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys


DATABASE = "agentflow_v48_browser"
DATABASE_USER = "v48_fixture"
REQUIRED_CASES = (
    "F01_JSON", "F01_SHAPE", "F02", "F03", "F04", "F05_EMBED", "F05_VECTOR",
    "F06_UNKNOWN", "F06_MALFORMED", "B01_PRE", "B01_OVER", "B02", "B03",
    "C01", "C02", "C03", "R01", "R02", "R03", "R04_SUCCESS", "R04_FAILED", "R05",
)
TERMINAL_EVENTS = {"COMPLETED": "TASK_COMPLETED", "FAILED": "TASK_FAILED",
                   "CANCELLED": "TASK_CANCELLED", "TIMED_OUT": "TASK_TIMED_OUT"}
EVENT_FIELDS = ("status", "phase", "terminationReason", "errorCode", "stepId", "toolCode",
                "reused", "validHitCount", "candidateCount", "staleHitCount", "totalTokens",
                "usageQuality", "decisionType", "maxOutputTokens", "chunkIndex", "text")
TASK_FIELDS = {
    "taskId": "id", "agentId": "agent_id", "status": "status", "phase": "phase",
    "terminationReason": "termination_reason", "errorCode": "error_code",
    "maxDecisionTurns": "max_decision_turns", "maxToolCalls": "max_tool_calls",
    "maxTotalTokens": "max_total_tokens", "reservedFinalTokens": "reserved_final_tokens",
    "decisionTurnsUsed": "decision_turns_used", "toolCallsUsed": "tool_calls_used",
    "inputTokens": "input_tokens", "outputTokens": "output_tokens", "totalTokens": "total_tokens",
    "tokenUsageQuality": "token_usage_quality", "lastEventSequence": "last_event_sequence",
    "finalAnswer": "final_answer", "citations": "citations",
}


def array_query(select):
    return "(SELECT COALESCE(jsonb_agg(to_jsonb(e)), '[]'::jsonb) FROM (" + select + ") e)"


def evidence_sql():
    # All selected fields are explicit. Never read users, credentials, request messages,
    # raw decisions/reasoning, or arbitrary event metadata. No LIMIT hides extra work.
    event_payload = "jsonb_strip_nulls(jsonb_build_object(" + ",".join(
        "'%s', payload->'%s'" % (field, field) for field in EVENT_FIELDS) + "))"
    queries = {
        "tasks": """SELECT id::text AS id, user_id::text AS user_id, agent_id::text AS agent_id,
            client_request_id, CASE WHEN user_input LIKE 'V48:%' THEN split_part(user_input, ':', 2)
            ELSE NULL END AS case_id, status, phase, termination_reason, error_code,
            max_decision_turns, max_tool_calls, max_total_tokens, reserved_final_tokens,
            decision_turns_used, tool_calls_used, input_tokens, output_tokens, total_tokens,
            token_usage_quality, final_answer, citations, started_at, completed_at,
            cancel_requested_at, last_event_sequence,
            execution_snapshot->>'snapshotVersion' AS snapshot_version,
            execution_snapshot->'agent'->>'agentId' AS snapshot_agent_id,
            execution_snapshot->'agent'->'maxDecisionTurns' AS snapshot_decision_limit,
            execution_snapshot->'agent'->'maxToolCalls' AS snapshot_tool_limit,
            execution_snapshot->'agent'->'maxTotalTokens' AS snapshot_token_limit,
            execution_snapshot->'agent'->'timeoutSeconds' AS timeout_seconds,
            execution_snapshot->'runtime' AS runtime,
            execution_snapshot->'retrieval' AS retrieval
            FROM agent_task ORDER BY id""",
        "steps": """SELECT id::text AS id, task_id::text AS task_id, step_index,
            step_type, status, error_code, started_at, ended_at,
            jsonb_strip_nulls(jsonb_build_object('reused', summary->'reused',
                'toolCode', summary->'toolCode', 'decisionType', summary->'decisionType',
                'totalTokens', summary->'totalTokens', 'usageQuality', summary->'usageQuality',
                'validHitCount', summary->'validHitCount')) AS summary
            FROM agent_step ORDER BY task_id, step_index""",
        "llm_calls": """SELECT id::text AS id, task_id::text AS task_id, step_id::text AS step_id,
            call_type, input_tokens, output_tokens, total_tokens, usage_quality,
            status, error_code, request_snapshot->'maxOutputTokens' AS max_output_tokens,
            CASE WHEN call_type='FINAL_GENERATION' THEN response_text ELSE NULL END AS final_response
            FROM llm_call_log ORDER BY task_id, created_at, id""",
        "tool_calls": """SELECT id::text AS id, task_id::text AS task_id, step_id::text AS step_id,
            tool_id::text AS tool_id, tool_code, status, error_code, retry_count,
            started_at, finished_at, result->'success' AS result_success,
            result->>'toolCode' AS result_tool_code
            FROM tool_call_log ORDER BY task_id, created_at, id""",
        "retrievals": """SELECT id::text AS id, task_id::text AS task_id, step_id::text AS step_id,
            status, error_code, candidate_count, valid_hit_count, stale_hit_count, corpus_snapshot
            FROM rag_retrieval_log ORDER BY task_id, created_at, id""",
        "hits": """SELECT h.id::text AS id, r.task_id::text AS task_id,
            h.retrieval_id::text AS retrieval_id, h.rank_no, h.citation_id,
            h.chunk_id_snapshot::text AS chunk_id, h.document_id_snapshot::text AS document_id,
            h.knowledge_base_id_snapshot::text AS knowledge_base_id, h.vector_generation,
            length(h.content_snapshot) > 0 AS has_content,
            h.metadata_snapshot->>'sourceContentHash' AS source_content_hash
            FROM rag_retrieval_hit h JOIN rag_retrieval_log r ON r.id=h.retrieval_id
            ORDER BY r.task_id, h.rank_no""",
        "events": """SELECT id::text AS id, task_id::text AS task_id, sequence_no,
            event_type, """ + event_payload + " AS payload, created_at FROM agent_task_event ORDER BY task_id, sequence_no",
        "documents": """SELECT id::text AS id, knowledge_base_id::text AS knowledge_base_id,
            user_id::text AS user_id, parse_status, vector_generation, deleted_at IS NULL AS live
            FROM knowledge_document ORDER BY id""",
        "chunks": """SELECT id::text AS id, document_id::text AS document_id,
            knowledge_base_id::text AS knowledge_base_id, user_id::text AS user_id,
            vector_generation, vectorization_status, content_hash FROM knowledge_chunk ORDER BY id""",
    }
    return ("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
            "SELECT jsonb_build_object('snapshotAt', transaction_timestamp(), " + ",\n".join(
                "'%s', %s" % (key, array_query(query)) for key, query in queries.items())
            + ");\nCOMMIT;\n")


def postgres_snapshot(pg_bin, port):
    if not str(port).isdigit() or not 1024 < int(port) < 65536:
        raise ValueError("invalid_v48_postgres_port")
    command = [str(Path(pg_bin) / "psql"), "-X", "-A", "-t", "-q", "-w", "-v", "ON_ERROR_STOP=1",
               "-h", "127.0.0.1", "-p", str(port), "-U", DATABASE_USER, "-d", DATABASE]
    env = {key: value for key, value in os.environ.items() if not key.startswith("PG")}
    env.update(PGCONNECT_TIMEOUT="3", PGPASSFILE="/dev/null",
               PGOPTIONS="-c statement_timeout=10000 -c default_transaction_read_only=on -c search_path=public")
    result = subprocess.run(command, input=evidence_sql(), text=True, capture_output=True,
                            timeout=20, env=env, check=False)
    if result.returncode:
        raise RuntimeError("postgres_read_exit_%s" % result.returncode)
    if len(result.stdout.encode()) > 20_000_000:
        raise ValueError("postgres_evidence_size_limit")
    return json.loads(result.stdout)


def same(left, right):
    """Public numeric strings are lossless; null omission and ID strings are expected."""
    if isinstance(left, dict) and isinstance(right, dict):
        return all(same(left.get(key), right.get(key)) for key in left.keys() | right.keys())
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(same(a, b) for a, b in zip(left, right))
    if type(left) in (int, str) and type(right) in (int, str):
        return str(left) == str(right)
    return type(left) is type(right) and left == right


def instant(value):
    return dt.datetime.fromisoformat(str(value).replace("Z", "+00:00"))


def usage_quality(calls):
    values = {call.get("usage_quality") for call in calls}
    if not values:
        return "UNKNOWN"
    return next(iter(values)) if len(values) == 1 else "MIXED"


def task_matches(public, task):
    return isinstance(public, dict) and all(same(public.get(front), task.get(back))
                                            for front, back in TASK_FIELDS.items())


def validate_case(case_id, expected, owner_id, storage, browser, telemetry):
    checks = []

    def check(code, passed, actual=None, wanted=None):
        result = {"code": code, "passed": bool(passed)}
        if actual is not None:
            result["actual"] = actual
        if wanted is not None:
            result["expected"] = wanted
        checks.append(result)

    tasks = [task for task in storage.get("tasks", []) if task.get("case_id") == case_id]
    check("exactly_one_task", len(tasks) == 1, len(tasks), 1)
    check("browser_passed", browser.get("status") == "PASSED", browser.get("status"), "PASSED")
    browser_checks = browser.get("checks", [])
    check("browser_assertions_present_and_passed", bool(browser_checks)
          and all(item.get("passed") is True for item in browser_checks))
    check("browser_gates_settled", browser.get("gatesSettled") is True)
    if len(tasks) != 1:
        return checks
    task = tasks[0]
    task_id = task["id"]
    own = lambda table: [row for row in storage.get(table, []) if row.get("task_id") == task_id]
    steps, calls, tools, retrievals, hits, events = [own(table) for table in
        ("steps", "llm_calls", "tool_calls", "retrievals", "hits", "events")]
    by_step = {step["id"]: step for step in steps}
    calls.sort(key=lambda row: by_step.get(row.get("step_id"), {}).get("step_index", -1))
    check("run_identity", task["user_id"] == str(owner_id)
          and task["agent_id"] == str(expected.get("agentId"))
          and str(browser.get("taskId")) == task_id and browser.get("caseId") == case_id
          and str(browser.get("agentId")) == task["agent_id"])
    check("independent_telemetry_correlates_to_task", bool(telemetry)
          and all(row.get("caseId") == case_id and str(row.get("taskId")) == task_id for row in telemetry))
    check("expected_terminal", task.get("status") == expected.get("status")
          and task.get("termination_reason") == expected.get("reason")
          and task.get("error_code") == expected.get("errorCode")
          and task.get("phase") is None and task.get("completed_at") is not None,
          {key: task.get(key) for key in ("status", "termination_reason", "error_code")},
          {"status": expected.get("status"), "termination_reason": expected.get("reason"),
           "error_code": expected.get("errorCode")})
    check("frozen_budget_matches_created_task", bool(task.get("snapshot_version"))
          and task.get("snapshot_agent_id") == task["agent_id"]
          and task.get("snapshot_decision_limit") == task.get("max_decision_turns") == expected.get("maxSteps")
          and task.get("snapshot_tool_limit") == task.get("max_tool_calls") == expected.get("maxToolCalls")
          and task.get("snapshot_token_limit") == task.get("max_total_tokens") == expected.get("maxTokens")
          and task.get("timeout_seconds") == expected.get("timeoutSeconds")
          and 0 < task.get("reserved_final_tokens", 0) < task.get("max_total_tokens", 0))
    trace = browser.get("trace") or {}
    for name, public in (("get", browser.get("task")), ("trace_task", trace.get("task")),
                         ("refresh", browser.get("refreshedTask"))):
        check("%s_matches_postgres" % name, task_matches(public, task))
    check("steps_contiguous_and_finished", bool(steps)
          and [step["step_index"] for step in steps] == list(range(len(steps)))
          and all(step.get("status") in ("SUCCESS", "FAILED", "SKIPPED")
                  and step.get("ended_at") is not None for step in steps))
    trace_steps = trace.get("steps", [])
    check("public_steps_match_postgres", len(trace_steps) == len(steps) and all(
        len([p for p in trace_steps if str(p.get("id")) == step["id"]
             and p.get("stepType") == step["step_type"] and p.get("status") == step["status"]
             and p.get("errorCode") == step.get("error_code")]) == 1 for step in steps))

    decisions = [call for call in calls if call.get("call_type") == "DECISION"]
    finals = [call for call in calls if call.get("call_type") == "FINAL_GENERATION"]
    starts = [call for call in telemetry if call.get("event") == "llm.start"]
    handler_starts = [call for call in telemetry if call.get("event") == "handler.enter"]
    actual_counts = {"decisions": len(decisions), "tools": len(tools), "handlers": len(handler_starts), "finals": len(finals)}
    check("expected_persisted_and_handler_counts", all(actual_counts[key] == expected.get(key)
          for key in actual_counts), actual_counts, {key: expected.get(key) for key in actual_counts})
    check("independent_gateway_counts", len(starts) == len(calls)
          and Counter(row.get("type") for row in starts) == Counter(row.get("call_type") for row in calls)
          and len({row.get("callId") for row in starts}) == len(starts)
          and all(row.get("callId") for row in starts))
    check("usage_aggregates_preserve_actual_consumption", all(
        type(call.get("input_tokens")) is int and type(call.get("output_tokens")) is int
        and call["input_tokens"] >= 0 and call["output_tokens"] >= 0
        and call.get("total_tokens") == call["input_tokens"] + call["output_tokens"] for call in calls)
        and all(task.get(field) == sum(call.get(field) or 0 for call in calls)
                for field in ("input_tokens", "output_tokens", "total_tokens"))
        and task.get("token_usage_quality") == usage_quality(calls)
        and task.get("decision_turns_used") == len(decisions)
        and task.get("tool_calls_used") == len(tools))
    ends = {row.get("callId"): row for row in telemetry if row.get("event") == "llm.end"}
    check("all_gateway_entries_have_one_exit", Counter(row.get("callId") for row in starts)
          == Counter(row.get("callId") for row in telemetry if row.get("event") == "llm.end"))
    reported_ok = len(calls) == len(starts)
    for persisted, start in zip(calls, starts):
        end = ends.get(start.get("callId"), {})
        estimated = case_id in ("B02", "B03") and start.get("type") == "FINAL_GENERATION"
        target_usage = {"input_tokens": start.get("inputEstimate"),
                        "output_tokens": start.get("maxOutputTokens")} if estimated else {
            "input_tokens": end.get("inputTokens"), "output_tokens": end.get("outputTokens")}
        reported_ok = reported_ok and persisted.get("call_type") == start.get("type")
        reported_ok = reported_ok and all(persisted.get(key) == value and type(value) is int
                                         for key, value in target_usage.items())
        reported_ok = reported_ok and persisted.get("max_output_tokens") == start.get("maxOutputTokens")
        reported_ok = reported_ok and persisted.get("usage_quality") == ("ESTIMATED" if estimated else "EXACT")
        if not estimated:
            reported_ok = reported_ok and end.get("responseAvailable") is True and (
                end.get("totalTokens") == persisted.get("total_tokens"))
    check("recorded_usage_matches_observed_or_estimated_boundary", reported_ok)
    check("specialized_logs_link_to_real_steps", all(row.get("step_id") in by_step
          and by_step[row["step_id"]].get("step_type") == kind
          and by_step[row["step_id"]].get("status") == ("SUCCESS" if row.get("status") == "SUCCESS" else "FAILED")
          for rows, kind in ((decisions, "LLM_DECISION"), (finals, "LLM_FINAL_GENERATION"),
                             (tools, "TOOL_CALL"), (retrievals, "PRE_RETRIEVAL")) for row in rows))
    check("tool_logs_have_no_retry_or_running_tail", all(tool.get("retry_count") == 0
          and tool.get("status") in ("SUCCESS", "FAILED", "REJECTED")
          and tool.get("finished_at") is not None for tool in tools))
    check("handler_entries_have_exits", Counter(row.get("callId") for row in handler_starts)
          == Counter(row.get("callId") for row in telemetry if row.get("event") == "handler.exit"))

    for table, public_key, fields in (
            (calls, "llmCalls", {"callType": "call_type", "status": "status", "errorCode": "error_code",
                "inputTokens": "input_tokens", "outputTokens": "output_tokens", "totalTokens": "total_tokens",
                "usageQuality": "usage_quality"}),
            (tools, "toolCalls", {"status": "status", "errorCode": "error_code", "retryCount": "retry_count",
                                  "toolCode": "tool_code"}),
            (retrievals, "ragRetrievals", {"status": "status", "errorCode": "error_code",
                "candidateCount": "candidate_count", "validHitCount": "valid_hit_count", "staleHitCount": "stale_hit_count"})):
        public_rows = [row for step in trace_steps for row in step.get(public_key, [])]
        check("public_%s_match_postgres" % public_key, len(public_rows) == len(table) and all(
            len([p for p in public_rows if str(p.get("id")) == row["id"] and all(
                same(p.get(front), row.get(back)) for front, back in fields.items())]) == 1 for row in table))

    sequence = [event.get("sequence_no") for event in events]
    check("event_sequence_contiguous_and_cursor_exact", sequence == list(range(1, len(events) + 1))
          and task.get("last_event_sequence") == len(events) and bool(events))
    kinds = Counter(event.get("event_type") for event in events)
    terminal = [event for event in events if event.get("event_type") in TERMINAL_EVENTS.values()]
    check("one_create_start_and_matching_final_event", kinds["TASK_CREATED"] == kinds["TASK_STARTED"] == 1
          and len(terminal) == 1 and terminal[0].get("event_type") == TERMINAL_EVENTS.get(task.get("status"))
          and terminal[0] == events[-1] and terminal[0].get("payload", {}).get("status") == task.get("status")
          and terminal[0].get("payload", {}).get("terminationReason") == task.get("termination_reason")
          and terminal[0].get("payload", {}).get("errorCode") == task.get("error_code"))
    public_events = trace.get("events", [])
    check("public_trace_events_match_postgres", len(public_events) == len(events) and all(
        str(p.get("id")) == row["id"] and str(p.get("taskId")) == task_id
        and same(p.get("sequenceNo"), row["sequence_no"]) and p.get("eventType") == row["event_type"]
        and same(p.get("payload"), row.get("payload")) for p, row in zip(public_events, events)))
    reused_steps = [step for step in steps if step.get("step_type") == "TOOL_CALL"
                    and step.get("summary", {}).get("reused") is True]
    reuse_expected = 1 if case_id == "F02" else 2 if case_id == "C02" else 0
    check("reuse_does_not_add_handler_or_log", len(reused_steps) == reuse_expected
          and all(not any(tool.get("step_id") == step["id"] for tool in tools) for step in reused_steps)
          and sum(event.get("event_type") == "TOOL_FINISHED" and event.get("payload", {}).get("reused") is True
                  for event in events) == reuse_expected)

    answer_chunks = [event["payload"] for event in events if event.get("event_type") == "ANSWER_CHUNK"]
    if task.get("status") != "COMPLETED":
        check("failure_never_publishes_answer", task.get("final_answer") is None
              and task.get("citations") == [] and not answer_chunks and kinds["TASK_COMPLETED"] == 0)
    else:
        check("success_chunks_exactly_rebuild_final_answer", bool(answer_chunks)
              and [chunk.get("chunkIndex") for chunk in answer_chunks] == list(range(len(answer_chunks)))
              and "".join(chunk.get("text", "") for chunk in answer_chunks) == task.get("final_answer"))
        used = set(re.findall(r"\[(S\d+)\]", task.get("final_answer") or ""))
        citations = task.get("citations", [])
        check("success_citations_map_to_frozen_valid_hits", used == {citation.get("citationId") for citation in citations}
              and all(any(citation.get("citationId") == hit.get("citation_id")
                          and str(citation.get("documentId")) == hit.get("document_id")
                          and str(citation.get("chunkId")) == hit.get("chunk_id")
                          and same(citation.get("vectorGeneration"), hit.get("vector_generation"))
                          for hit in hits) for citation in citations)
              and (not citations if case_id == "C01" else bool(citations)))
        check("successful_independent_final_generation", len(finals) == 1
              and finals[0].get("status") == "SUCCESS" and finals[0].get("final_response") == task.get("final_answer"))

    check("one_execution_retrieval", len(retrievals) == 1)
    for component, expected_count in (("embedding", 1), ("vector", 0 if case_id == "F05_EMBED" else 1)):
        component_starts = [row for row in telemetry if row.get("event") == component + ".start"]
        component_ends = [row for row in telemetry if row.get("event") == component + ".end"]
        check(component + "_boundary_called_once_without_retry", len(component_starts) == expected_count
              and Counter(row.get("callId") for row in component_starts)
                  == Counter(row.get("callId") for row in component_ends))
    check("retrieval_hit_counts_match_rows", all(row.get("valid_hit_count") == sum(
          hit.get("retrieval_id") == row["id"] for hit in hits) for row in retrievals))
    frozen = {(base.get("knowledgeBaseId"), doc.get("documentId"), str(doc.get("vectorGeneration")))
              for base in (task.get("retrieval") or {}).get("knowledgeBases", []) for doc in base.get("documents", [])}
    docs = {row["id"]: row for row in storage.get("documents", [])}
    chunks = {row["id"]: row for row in storage.get("chunks", [])}
    check("hits_match_current_source_and_frozen_generation", all(
        (hit.get("knowledge_base_id"), hit.get("document_id"), str(hit.get("vector_generation"))) in frozen
        and docs.get(hit.get("document_id"), {}).get("live") is True
        and docs.get(hit.get("document_id"), {}).get("parse_status") == "COMPLETED"
        and chunks.get(hit.get("chunk_id"), {}).get("vectorization_status") == "COMPLETED"
        and all(same(source.get("vector_generation"), hit.get("vector_generation"))
                and source.get("user_id") == str(owner_id)
                and source.get("knowledge_base_id") == hit.get("knowledge_base_id") for source in
                (docs.get(hit.get("document_id"), {}), chunks.get(hit.get("chunk_id"), {})))
        and chunks.get(hit.get("chunk_id"), {}).get("document_id") == hit.get("document_id")
        and chunks.get(hit.get("chunk_id"), {}).get("content_hash") == hit.get("source_content_hash")
        and hit.get("has_content") is True for hit in hits))

    if case_id.startswith("F01") or case_id in ("R01", "R02", "R03", "R04_FAILED"):
        check("invalid_decision_retains_failed_log_and_usage", len(decisions) == 1
              and decisions[0].get("status") == "FAILED"
              and decisions[0].get("error_code") == "AGENT_INVALID_DECISION"
              and decisions[0].get("total_tokens", 0) > 0)
    if case_id == "F02":
        check("prior_tool_success_retained_before_loop_failure", len(tools) == 1 and tools[0].get("status") == "SUCCESS"
              and all(call.get("status") == "SUCCESS" for call in decisions))
    if case_id in ("F03", "F04"):
        check("tool_failure_boundary", len(tools) == 1
              and tools[0].get("status") == ("REJECTED" if case_id == "F03" else "FAILED")
              and tools[0].get("error_code") == expected.get("errorCode"))
    if case_id.startswith("F05"):
        check("execution_period_retrieval_failure", len(retrievals) == 1
              and retrievals[0].get("status") == "FAILED" and retrievals[0].get("error_code") == "RAG_RETRIEVAL_FAILED"
              and not hits and sum(row.get("event") == "embedding.start" for row in telemetry) == 1
              and sum(row.get("event") == "vector.start" for row in telemetry) == (0 if case_id == "F05_EMBED" else 1))
    else:
        check("successful_retrieval_retained", len(retrievals) == 1 and retrievals[0].get("status") == "SUCCESS"
              and (not hits if case_id in ("C01", "B01_PRE") else bool(hits)))
    if case_id.startswith("F06"):
        check("invalid_final_citation_retains_failed_usage", bool(hits) and len(finals) == 1
              and finals[0].get("status") == "FAILED" and finals[0].get("error_code") == "AGENT_INVALID_CITATION"
              and finals[0].get("final_response") is None and finals[0].get("total_tokens", 0) > 0)
    if case_id == "B01_PRE":
        pre = [row for row in telemetry if row.get("event") == "budget.preflight"]
        check("pre_request_budget_exhaustion_has_zero_unknown_usage", not calls
              and task.get("total_tokens") == 0 and task.get("token_usage_quality") == "UNKNOWN"
              and len(pre) == 1 and pre[0].get("reservedFinalTokens") == task.get("reserved_final_tokens")
              and pre[0].get("maxTotalTokens") == task.get("max_total_tokens")
              and type(pre[0].get("inputEstimate")) is int
              and type(pre[0].get("finalInputEstimate")) is int
              and pre[0]["inputEstimate"] + pre[0]["finalInputEstimate"]
                  + task["reserved_final_tokens"] >= task["max_total_tokens"]
              and pre[0].get("availableDecisionOutput") == task["max_total_tokens"]
                  - pre[0]["inputEstimate"] - pre[0]["finalInputEstimate"] - task["reserved_final_tokens"]
              and pre[0].get("availableDecisionOutput", 1) < 1)
    if case_id == "B01_OVER":
        check("reported_overrun_is_not_clamped", task.get("max_total_tokens") == 50000
              and task.get("total_tokens") == 50020 and task.get("input_tokens") == 50000
              and task.get("output_tokens") == 20 and task.get("token_usage_quality") == "EXACT")
    if case_id in ("B02", "B03"):
        entered = [row for row in telemetry if row.get("event") == "gate.entered"]
        exited = [row for row in telemetry if row.get("event") == "gate.exited"]
        interrupted = [row for row in telemetry if row.get("event") == "gate.interrupted"]
        late_checkpoints = [row for row in browser.get("checkpoints", []) if row.get("at")
                            and task_matches(row.get("task"), task) and isinstance(row.get("trace"), dict)
                            and task_matches(row["trace"].get("task"), task)]
        ordered = bool(entered and exited and interrupted)
        if ordered:
            ordered = instant(entered[0]["at"]) <= instant(task["completed_at"]) <= instant(exited[-1]["at"])
            ordered = ordered and any(instant(row["at"]) >= instant(exited[-1]["at"]) for row in late_checkpoints)
            ordered = ordered and bool(storage.get("snapshotAt")) and instant(storage["snapshotAt"]) >= instant(exited[-1]["at"])
        check("late_work_exited_before_final_evidence", ordered)
        check("inflight_final_uses_estimated_usage", len(finals) == 1
              and finals[0].get("usage_quality") == "ESTIMATED" and finals[0].get("status") == "FAILED"
              and finals[0].get("final_response") is None and finals[0].get("total_tokens", 0) > 0)
        if case_id == "B02":
            check("overall_deadline_reached", instant(task["completed_at"]) >=
                  instant(task["started_at"]) + dt.timedelta(seconds=task["timeout_seconds"]))
        else:
            check("cancellation_was_persisted", task.get("cancel_requested_at") is not None
                  and instant(task["started_at"]) <= instant(task["cancel_requested_at"]) <= instant(task["completed_at"]))
    if case_id in ("C02", "C03"):
        check("limit_final_does_not_invent_finish_decision", all(
            step.get("summary", {}).get("decisionType") == "CALL_TOOL" for step in steps
            if step.get("step_type") == "LLM_DECISION"))
    if case_id == "R01":
        creates = browser.get("creates", [])
        accepted = [row for row in creates if row.get("status") in (200, 201)]
        conflicts = [row for row in creates if row.get("status") == 409]
        check("idempotency_http_evidence_matches_single_execution", len(accepted) >= 3
              and sum(row.get("status") == 201 for row in accepted) == 1
              and all(str(row.get("taskId")) == task_id and row.get("key") == task.get("client_request_id") for row in accepted)
              and bool(conflicts) and all(row.get("code") == "TASK_IDEMPOTENCY_CONFLICT" for row in conflicts))
    if case_id == "R02":
        check("unknown_post_confirmation_reuses_persisted_key", any(row.get("status") == 200
              and str(row.get("taskId")) == task_id and row.get("key") == task.get("client_request_id")
              for row in browser.get("creates", [])))
    return checks


def read_json(path, maximum=12_000_000):
    with path.open("r", encoding="utf-8") as source:
        raw = source.read(maximum + 1)
    if len(raw.encode()) > maximum:
        raise ValueError("json_evidence_size_limit")
    return json.loads(raw)


def read_calls(run_dir, case_id):
    local = run_dir / "cases" / case_id / "calls.jsonl"
    path = local if local.exists() else run_dir / "calls.jsonl"
    if not path.exists():
        raise FileNotFoundError("fixture_calls_missing")
    if path.stat().st_size > 12_000_000:
        raise ValueError("calls_evidence_size_limit")
    with path.open(encoding="utf-8") as source:
        rows = [json.loads(line) for line in source if line.strip()]
    if any(not isinstance(row, dict) for row in rows):
        raise ValueError("invalid_call_telemetry")
    return [row for row in rows if row.get("caseId") == case_id]


def safe_error(error):
    if isinstance(error, (ValueError, RuntimeError)) and re.fullmatch(r"[a-z0-9_]+", str(error)):
        return str(error)
    return type(error).__name__


def write_json(path, value):
    temporary = path.with_suffix(".json.tmp")
    path.parent.mkdir(parents=True, exist_ok=True)
    with temporary.open("w", encoding="utf-8") as stream:
        os.chmod(temporary, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    temporary.replace(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--pg-bin", default="/Library/PostgreSQL/18/bin")
    parser.add_argument("--pg-port", default="55448")
    parser.add_argument("--case", choices=REQUIRED_CASES, dest="selected_case")
    args = parser.parse_args(argv)
    run_dir = args.run_dir.resolve()
    if not run_dir.is_dir():
        parser.error("--run-dir must be an existing directory")
    report = {"schemaVersion": "v48-storage-evidence-v1", "status": "FAIL", "fullMatrixPassed": False,
              "observedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "database": DATABASE,
              "scope": "controlled fault injection; read-only isolated PostgreSQL; browser observation recovery",
              "selectedCase": args.selected_case, "collectorErrors": [], "checks": [], "cases": {}}
    manifest, storage = {}, {}
    try:
        manifest = read_json(run_dir / "manifest.json")
        if manifest.get("schemaVersion") != "v48-failure-recovery-v1" or not isinstance(manifest.get("cases"), dict):
            raise ValueError("invalid_manifest_contract")
        report["checks"].append({"code": "required_manifest_matrix_complete",
                                 "passed": set(manifest["cases"]) == set(REQUIRED_CASES)})
    except Exception as error:
        report["collectorErrors"].append({"stage": "manifest", "code": safe_error(error)})
    try:
        storage = postgres_snapshot(args.pg_bin, args.pg_port)
        report["postgres"] = storage
    except Exception as error:
        report["collectorErrors"].append({"stage": "postgres", "code": safe_error(error)})
    selected = [args.selected_case] if args.selected_case else list(REQUIRED_CASES)
    if storage:
        tasks = storage.get("tasks", [])
        report["checks"].append({"code": "database_contains_exact_selected_tasks",
                                 "passed": Counter(task.get("case_id") for task in tasks) == Counter(selected),
                                 "actualTaskCount": len(tasks), "expectedTaskCount": len(selected)})
        task_ids = {task.get("id") for task in tasks}
        report["checks"].append({"code": "all_execution_rows_belong_to_collected_tasks",
                                 "passed": all(row.get("task_id") in task_ids for table in
                                     ("steps", "llm_calls", "tool_calls", "retrievals", "hits", "events")
                                     for row in storage.get(table, []))})
    for case_id in selected:
        result = {"caseId": case_id, "status": "NOT_RUN", "checks": [], "collectorErrors": []}
        expected = manifest.get("cases", {}).get(case_id)
        browser_path = run_dir / "cases" / case_id / "browser-evidence.json"
        if expected is None:
            result["collectorErrors"].append({"stage": "manifest", "code": "required_case_missing"})
        elif not browser_path.exists():
            result["collectorErrors"].append({"stage": "browser", "code": "browser_evidence_missing"})
        else:
            # Do not copy manifest login credentials or browser request material to the report.
            result["expected"] = {key: expected.get(key) for key in
                                  ("agentId", "status", "reason", "errorCode", "decisions", "tools", "handlers", "finals")}
            try:
                browser = read_json(browser_path)
                calls = read_calls(run_dir, case_id)
                result["taskId"] = browser.get("taskId")
                result["checks"] = validate_case(case_id, expected, manifest.get("ownerId"), storage, browser, calls)
                result["status"] = "PASSED" if result["checks"] and all(
                    check["passed"] for check in result["checks"]) and storage else "FAILED"
            except Exception as error:
                result["status"] = "BLOCKED" if not storage else "FAILED"
                result["collectorErrors"].append({"stage": "case_validation", "code": safe_error(error)})
        if not storage:
            result["status"] = "BLOCKED"
        report["cases"][case_id] = result
        write_json(run_dir / "cases" / case_id / "storage-evidence.json", result)
    passed = (not report["collectorErrors"] and bool(report["checks"])
              and all(check["passed"] for check in report["checks"])
              and all(result["status"] == "PASSED" and not result["collectorErrors"]
                      for result in report["cases"].values()))
    report["status"] = "PASS" if passed else "FAIL"
    report["fullMatrixPassed"] = passed and args.selected_case is None
    report["counts"] = dict(Counter(result["status"] for result in report["cases"].values()))
    target = run_dir / "storage-evidence.json"
    write_json(target, report)
    print("V48 storage evidence: %s; full matrix: %s (%s)" % (report["status"], report["fullMatrixPassed"], target))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
