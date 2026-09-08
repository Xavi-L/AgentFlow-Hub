#!/usr/bin/env python3
"""Read-only V47 PostgreSQL/Qdrant evidence; never converts partial evidence to PASS.

Run after the browser attempt, while the disposable database is still alive.
Requires V47_CONTROL_DIR and the independently named QDRANT_COLLECTION=v47_....
Only Python's standard library and the existing PostgreSQL psql client are used.
"""

import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


DATABASE = "agentflow_v47_real"
DATABASE_USER = "v47_acceptance"
PROFILE = "dashscope-te-v4-1024-cosine"
PAYLOAD_KEYS = ("chunkId", "documentId", "knowledgeBaseId", "userId", "chunkIndex",
                "vectorGeneration", "contentHash", "embeddingProvider", "embeddingModel")


def array_query(select):
    return "(SELECT COALESCE(jsonb_agg(to_jsonb(e)), '[]'::jsonb) FROM (" + select + ") e)"


def evidence_sql():
    # Fixed SQL, with explicit columns. No app_user, credentials, full requests or arbitrary logs.
    queries = {
        "tasks": """SELECT id::text AS id, user_id::text AS user_id, agent_id::text AS agent_id,
            status, phase, termination_reason, max_decision_turns, max_tool_calls,
            max_total_tokens, reserved_final_tokens, decision_turns_used, tool_calls_used,
            input_tokens, output_tokens, total_tokens, token_usage_quality, final_answer,
            citations, error_code, started_at, completed_at, last_event_sequence,
            position('order_1024' in user_input) > 0 AS includes_order,
            execution_snapshot->'chatModel' AS chat_model,
            execution_snapshot->'retrieval' AS retrieval,
            execution_snapshot->'runtime' AS runtime
            FROM agent_task ORDER BY id LIMIT 2""",
        "steps": """SELECT id::text AS id, task_id::text AS task_id, step_index,
            step_type, status, error_code FROM agent_step
            WHERE task_id IN (SELECT id FROM selected_task) ORDER BY step_index LIMIT 50""",
        "llm_calls": """SELECT id::text AS id, task_id::text AS task_id, step_id::text AS step_id,
            call_type, provider, requested_model, resolved_model, finish_reason,
            provider_request_id, input_tokens, output_tokens, total_tokens,
            usage_quality, latency_ms, status, error_code, response_text,
            request_snapshot->'maxOutputTokens' AS max_output_tokens,
            request_snapshot->'responseSchema' AS response_schema,
            request_snapshot->'responseFormat' AS response_format,
            request_snapshot->'thinkingMode' AS thinking_mode
            FROM llm_call_log WHERE task_id IN (SELECT id FROM selected_task)
            ORDER BY created_at, id LIMIT 22""",
        "retrievals": """SELECT id::text AS id, task_id::text AS task_id, step_id::text AS step_id,
            embedding_profile_code, corpus_snapshot, top_k, similarity_threshold,
            candidate_count, valid_hit_count, stale_hit_count, latency_ms, status, error_code
            FROM rag_retrieval_log WHERE task_id IN (SELECT id FROM selected_task)
            ORDER BY created_at, id LIMIT 2""",
        "hits": """SELECT h.id::text AS id, h.retrieval_id::text AS retrieval_id,
            h.rank_no, h.citation_id, h.chunk_id_snapshot::text AS chunk_id,
            h.document_id_snapshot::text AS document_id,
            h.knowledge_base_id_snapshot::text AS knowledge_base_id,
            h.vector_generation, h.score, h.content_snapshot,
            h.metadata_snapshot->>'sourceContentHash' AS source_content_hash
            FROM rag_retrieval_hit h JOIN rag_retrieval_log r ON r.id=h.retrieval_id
            WHERE r.task_id IN (SELECT id FROM selected_task) ORDER BY h.rank_no LIMIT 20""",
        "tool_calls": """SELECT id::text AS id, task_id::text AS task_id, step_id::text AS step_id,
            tool_id::text AS tool_id, tool_code, arguments, result, status,
            retry_count, latency_ms, error_code, started_at, finished_at
            FROM tool_call_log WHERE task_id IN (SELECT id FROM selected_task)
            ORDER BY created_at, id LIMIT 22""",
        "documents": """SELECT id::text AS id, knowledge_base_id::text AS knowledge_base_id,
            user_id::text AS user_id, file_name, parse_status, vector_generation,
            deleted_at IS NULL AS live FROM knowledge_document ORDER BY id LIMIT 101""",
        "chunks": """SELECT id::text AS id, document_id::text AS document_id,
            knowledge_base_id::text AS knowledge_base_id, user_id::text AS user_id,
            chunk_index, chunk_strategy_version, vector_generation, vectorization_status,
            vector_id, content_hash FROM knowledge_chunk ORDER BY id LIMIT 101""",
    }
    members = ",\n".join("'%s', %s" % (key, array_query(query)) for key, query in queries.items())
    return ("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
            "WITH selected_task AS (SELECT id FROM agent_task ORDER BY id LIMIT 1)\n"
            "SELECT jsonb_build_object(" + members + ");\nCOMMIT;\n")


def postgres_snapshot():
    port = os.environ.get("V47_PG_PORT", "55447")
    if not port.isdigit() or not 1024 < int(port) < 65536:
        raise ValueError("invalid_v47_postgres_port")
    psql = str(Path(os.environ.get("PG_BIN", "/Library/PostgreSQL/18/bin")) / "psql")
    command = [psql, "-X", "-A", "-t", "-q", "-w", "-v", "ON_ERROR_STOP=1",
               "-h", "127.0.0.1", "-p", port, "-U", DATABASE_USER, "-d", DATABASE]
    child_env = {key: value for key, value in os.environ.items() if not key.startswith("PG")}
    child_env.update(PGCONNECT_TIMEOUT="3", PGPASSFILE="/dev/null",
                     PGOPTIONS="-c statement_timeout=10000 -c default_transaction_read_only=on -c search_path=public")
    result = subprocess.run(command, input=evidence_sql(), text=True, capture_output=True,
                            timeout=20, env=child_env, check=False)
    if result.returncode:
        # psql stderr can contain DSNs; retain only the stage and exit code.
        raise RuntimeError("postgres_read_exit_%s" % result.returncode)
    return json.loads(result.stdout)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        raise urllib.error.HTTPError(request.full_url, code, "redirect_rejected", headers, None)


def qdrant_snapshot(chunks, result):
    collection = os.environ.get("QDRANT_COLLECTION", "")
    if not re.fullmatch(r"v47_[A-Za-z0-9_-]{1,124}", collection):
        raise ValueError("dedicated_v47_collection_required")
    base = os.environ.get("QDRANT_BASE_URL", "http://127.0.0.1:6333").rstrip("/")
    parsed = urllib.parse.urlsplit(base)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise ValueError("invalid_qdrant_base_url")
    handlers = [NoRedirect()]
    if parsed.hostname in ("localhost", "127.0.0.1", "::1"):
        handlers.append(urllib.request.ProxyHandler({}))
    opener = urllib.request.build_opener(*handlers)

    def request(suffix, body=None):
        data = None if body is None else json.dumps(body).encode()
        headers = {"Content-Type": "application/json"}
        if os.environ.get("QDRANT_API_KEY", "").strip():
            headers["api-key"] = os.environ["QDRANT_API_KEY"].strip()
        req = urllib.request.Request(base + "/collections/" + collection + suffix,
                                     data=data, headers=headers)
        with opener.open(req, timeout=10) as response:
            raw = response.read(2_000_001)
            if len(raw) > 2_000_000:
                raise ValueError("qdrant_evidence_size_limit")
            return json.loads(raw)

    result.update(collection=collection, base_url=base, points=[])
    config = request("")["result"]
    result.update(status=config.get("status"), points_count=config.get("points_count"),
                  vectors=config.get("config", {}).get("params", {}).get("vectors"), points=[])
    ids = sorted({chunk["vector_id"] for chunk in chunks if chunk.get("vector_id")})
    if len(ids) > 100:
        raise ValueError("v47_chunk_budget_exceeded")
    if ids:
        # POST /points is Qdrant's read-only Retrieve Points endpoint. Never upsert/delete.
        points = request("/points", {"ids": ids, "with_payload": list(PAYLOAD_KEYS),
                                     "with_vector": False})["result"]
        result["points"] = [{"id": point["id"], "payload": {
            key: point.get("payload", {}).get(key) for key in PAYLOAD_KEYS}} for point in points]
    return result


def request_option_checks(calls, final_reserve, env=None):
    """Check recorded request options against this explicitly configured acceptance run."""
    env = os.environ if env is None else env
    schema_enabled = env.get("AGENTFLOW_TASK_DECISION_JSON_SCHEMA_ENABLED", "false").strip().lower() == "true"
    json_enabled = env.get("AGENTFLOW_TASK_DECISION_JSON_OBJECT_ENABLED", "false").strip().lower() == "true"
    thinking_disabled = env.get("AGENTFLOW_TASK_PROVIDER_THINKING_DISABLED", "false").strip().lower() == "true"
    decisions = [call for call in calls if call["call_type"] == "DECISION"]
    finals = [call for call in calls if call["call_type"] == "FINAL_GENERATION"]
    decision_cap = int(env.get("AGENTFLOW_TASK_DECISION_MAX_OUTPUT_TOKENS", "512"))
    final_cap = max(final_reserve, int(env.get("AGENTFLOW_TASK_FINAL_MAX_OUTPUT_TOKENS", "").strip() or final_reserve))
    results = {
        "exclusive_decision_format": not (schema_enabled and json_enabled),
        "decision_schema_mode_matches_requests": bool(decisions) and all(
            (isinstance(call.get("response_schema"), dict)
             and call["response_schema"].get("name") == "agent_decision_v1"
             and isinstance(call["response_schema"].get("schema"), dict)) if schema_enabled
            else call.get("response_schema") is None for call in decisions),
        "decision_json_mode_matches_requests": bool(decisions) and all(
            call.get("response_format") == ("json_object" if json_enabled else None) for call in decisions),
        "provider_thinking_mode_matches_requests": bool(calls) and all(
            call.get("thinking_mode") == ("disabled" if thinking_disabled else None) for call in calls),
        "decision_output_caps_within_configured_ceiling": bool(decisions) and all(
            type(call.get("max_output_tokens")) is int and 1 <= call["max_output_tokens"] <= decision_cap
            for call in decisions),
        "final_generation_keeps_text_format": bool(finals) and all(
            call.get("response_schema") is None and call.get("response_format") is None for call in finals),
        "final_output_caps_preserve_reserve_and_ceiling": bool(finals) and all(
            type(call.get("max_output_tokens")) is int and final_reserve <= call["max_output_tokens"] <= final_cap
            for call in finals),
    }
    return [{"code": code, "passed": bool(passed)} for code, passed in results.items()]


def validate(storage, qdrant, browser):
    checks = []

    def check(code, passed):
        checks.append({"code": code, "passed": bool(passed)})

    tasks = storage.get("tasks", [])
    check("exactly_one_task", len(tasks) == 1)
    if len(tasks) != 1:
        return checks
    task = tasks[0]
    check("browser_and_storage_identify_same_run", str(browser.get("taskId")) == task["id"]
          and str(browser.get("agentId")) == task["agent_id"]
          and any(str(browser.get("documentId")) == document["id"]
                  and str(browser.get("knowledgeBaseId")) == document["knowledge_base_id"]
                  for document in storage["documents"]))
    steps = {step["id"]: step for step in storage["steps"]}
    linked = lambda row, kind: (row.get("task_id") == task["id"]
        and steps.get(row.get("step_id"), {}).get("task_id") == task["id"]
        and steps.get(row.get("step_id"), {}).get("step_type") == kind
        and steps.get(row.get("step_id"), {}).get("status") == "SUCCESS")
    check("completed_answered_order_task", task["status"] == "COMPLETED"
          and task["termination_reason"] == "ANSWERED" and task["includes_order"]
          and bool(task["final_answer"]) and task["completed_at"] is not None)
    calls = storage["llm_calls"]
    checks.extend(request_option_checks(calls, task["reserved_final_tokens"]))
    public_calls = {str(call.get("id")): call
                    for step in (browser.get("trace") or {}).get("steps", [])
                    for call in step.get("llmCalls", [])}
    check("public_trace_and_storage_request_options_agree", bool(calls)
          and len(public_calls) == len(calls) and all(
              isinstance(public_calls.get(call["id"], {}).get("requestSnapshot"), dict)
              and all(public_calls[call["id"]]["requestSnapshot"].get(public_key) == call.get(storage_key)
                      for public_key, storage_key in (("maxOutputTokens", "max_output_tokens"),
                                                      ("responseSchema", "response_schema"),
                                                      ("responseFormat", "response_format"),
                                                      ("thinkingMode", "thinking_mode")))
              for call in calls))
    final = [call for call in calls if call["call_type"] == "FINAL_GENERATION"]
    decisions = [call for call in calls if call["call_type"] == "DECISION"]
    check("independent_successful_final_generation", len(final) == 1
          and final[0]["status"] == "SUCCESS" and linked(final[0], "LLM_FINAL_GENERATION")
          and final[0]["response_text"] == task["final_answer"]
          and all(steps[call["step_id"]]["step_index"] < steps[final[0]["step_id"]]["step_index"]
                  for call in decisions if call["step_id"] in steps))
    check("successful_model_decisions", 3 <= len(decisions) <= task["max_decision_turns"]
          and all(call["status"] == "SUCCESS" and linked(call, "LLM_DECISION") for call in decisions))
    try:
        decision_types = [json.loads(call.get("response_text") or "null") for call in decisions]
        has_finish = any(isinstance(value, dict) and value.get("type") == "FINISH" for value in decision_types)
    except (ValueError, TypeError):
        has_finish = False
    check("model_explicitly_finished", has_finish)
    check("requested_models_match_frozen_provider", bool(calls) and all(
        call["provider"] == "openai-compatible" and call["requested_model"] == task["chat_model"].get("model")
        and bool(call["requested_model"]) for call in calls))
    check("persisted_usage_within_budget", bool(calls)
          and all(isinstance(call["total_tokens"], int) and call["total_tokens"] >= 0
                  and call["total_tokens"] == call["input_tokens"] + call["output_tokens"] for call in calls)
          and sum(call["total_tokens"] for call in calls) == task["total_tokens"]
          and 0 < task["total_tokens"] <= task["max_total_tokens"]
          and len(decisions) == task["decision_turns_used"])
    tools = storage["tool_calls"]
    check("both_tools_persisted_with_task_and_step", len(tools) == 2
          and {tool["tool_code"] for tool in tools} == {"order_query", "payment_log_query"}
          and task["tool_calls_used"] == 2 and all(
              tool["status"] == "SUCCESS" and linked(tool, "TOOL_CALL")
              and tool["retry_count"] == 0 and tool["arguments"].get("orderNo") == "order_1024"
              and (tool.get("result") or {}).get("success") is True
              and tool["result"].get("toolCode") == tool["tool_code"] for tool in tools))
    by_tool = {tool["tool_code"]: (tool.get("result") or {}).get("data") or {} for tool in tools}
    order = by_tool.get("order_query", {})
    logs = by_tool.get("payment_log_query", {}).get("logs", [])
    check("demo_business_results_are_present", order.get("orderNo") == "order_1024"
          and order.get("paymentStatus") == "PAY_FAILED" and order.get("errorCode") == "E_PAY_TIMEOUT"
          and any(log.get("orderNo") == "order_1024" and log.get("traceId") == "pay-trace-1024"
                  and log.get("errorCode") == "E_PAY_TIMEOUT" for log in logs))
    retrievals, hits = storage["retrievals"], storage["hits"]
    check("one_successful_real_profile_retrieval", len(retrievals) == 1 and bool(hits)
          and retrievals[0]["status"] == "SUCCESS" and linked(retrievals[0], "PRE_RETRIEVAL")
          and retrievals[0]["embedding_profile_code"] == PROFILE
          and retrievals[0]["valid_hit_count"] == len(hits)
          and retrievals[0]["candidate_count"] >= len(hits))
    docs = {document["id"]: document for document in storage["documents"]}
    chunks = {chunk["id"]: chunk for chunk in storage["chunks"]}
    frozen = {(str(base["knowledgeBaseId"]), str(doc["documentId"]), str(doc["vectorGeneration"]))
              for base in task["retrieval"].get("knowledgeBases", []) for doc in base.get("documents", [])}
    for hit in hits:
        chunk, document = chunks.get(hit["chunk_id"], {}), docs.get(hit["document_id"], {})
        check("current_generation_hit_" + hit["id"],
              bool(chunk) and bool(document) and document.get("live")
              and document.get("parse_status") == "COMPLETED"
              and chunk.get("vectorization_status") == "COMPLETED"
              and chunk.get("chunk_strategy_version") == "structured-token-v1"
              and str(hit["vector_generation"]) == str(chunk.get("vector_generation")) == str(document.get("vector_generation"))
              and hit["document_id"] == chunk.get("document_id")
              and hit["knowledge_base_id"] == chunk.get("knowledge_base_id") == document.get("knowledge_base_id")
              and task["user_id"] == chunk.get("user_id") == document.get("user_id")
              and hit["source_content_hash"] == chunk.get("content_hash")
              and bool(hit["content_snapshot"]) and bool(chunk.get("vector_id"))
              and hit["document_id"] == str(browser.get("documentId"))
              and hit["knowledge_base_id"] == str(browser.get("knowledgeBaseId"))
              and (hit["knowledge_base_id"], hit["document_id"], str(hit["vector_generation"])) in frozen)
    citations = task["citations"]
    used = set(re.findall(r"\[(S\d+)\]", task["final_answer"] or ""))
    check("answer_citations_match_current_hits", bool(citations)
          and used == {citation.get("citationId") for citation in citations}
          and all(any(citation.get("citationId") == hit["citation_id"]
                      and str(citation.get("documentId")) == hit["document_id"]
                      and str(citation.get("chunkId")) == hit["chunk_id"]
                      and str(citation.get("vectorGeneration")) == str(hit["vector_generation"]) for hit in hits)
                  for citation in citations))
    vectors = qdrant.get("vectors") or {}
    check("qdrant_dense_1024_cosine_collection", isinstance(vectors, dict)
          and vectors.get("size") == 1024 and str(vectors.get("distance", "")).lower() == "cosine")
    points = {str(point["id"]): point["payload"] for point in qdrant.get("points", [])}
    for hit in hits:
        chunk = chunks.get(hit["chunk_id"], {})
        point = points.get(chunk.get("vector_id"), {})
        check("qdrant_current_hit_point_" + hit["id"], bool(point)
              and str(point.get("chunkId")) == hit["chunk_id"]
              and str(point.get("documentId")) == hit["document_id"]
              and str(point.get("knowledgeBaseId")) == hit["knowledge_base_id"]
              and str(point.get("userId")) == task["user_id"]
              and str(point.get("vectorGeneration")) == str(hit["vector_generation"])
              and point.get("contentHash") == chunk.get("content_hash")
              and point.get("embeddingProvider") == "dashscope"
              and point.get("embeddingModel") == "text-embedding-v4")
    return checks


def safe_error(error):
    if isinstance(error, urllib.error.HTTPError):
        return "http_status_%s" % error.code
    if isinstance(error, (ValueError, RuntimeError)) and re.fullmatch(r"[a-z0-9_]+", str(error)):
        return str(error)
    return type(error).__name__


def sanitized(value):
    secrets = [os.environ.get(name, "") for name in
               ("OPENAI_API_KEY", "DASHSCOPE_API_KEY", "QDRANT_API_KEY", "JWT_SECRET_BASE64", "POSTGRES_PASSWORD")]
    serialized = json.dumps(value, ensure_ascii=False, indent=2)
    for secret in secrets:
        if secret:
            serialized = serialized.replace(json.dumps(secret, ensure_ascii=False)[1:-1], "[REDACTED]")
    return serialized + "\n"


def main():
    run_dir = os.environ.get("V47_CONTROL_DIR")
    if not run_dir or not Path(run_dir).is_absolute() or not Path(run_dir).is_dir():
        print("V47_CONTROL_DIR must be an existing absolute directory", file=sys.stderr)
        return 2
    report = {"schema_version": "v47-storage-evidence-v1", "status": "FAIL",
              "observed_at": dt.datetime.now(dt.timezone.utc).isoformat(),
              "scope": "read-only disposable PostgreSQL and dedicated v47 Qdrant collection; demo business data",
              "database": DATABASE, "errors": [], "postgres": {}, "qdrant": {}, "browser": {}, "checks": []}
    browser = {}
    try:
        with (Path(run_dir) / "browser-evidence.json").open("r", encoding="utf-8") as source:
            raw = source.read(2_000_001)
        if len(raw.encode()) > 2_000_000:
            raise ValueError("browser_evidence_size_limit")
        browser = json.loads(raw)
        report["browser"] = {key: browser.get(key) for key in
                             ("knowledgeBaseId", "documentId", "agentId", "taskId", "phase", "status")}
    except Exception as error:
        report["errors"].append({"stage": "browser", "code": safe_error(error)})
    try:
        report["postgres"] = postgres_snapshot()
    except Exception as error:
        report["errors"].append({"stage": "postgres", "code": safe_error(error)})
    try:
        qdrant_snapshot(report["postgres"].get("chunks", []), report["qdrant"])
    except Exception as error:
        report["errors"].append({"stage": "qdrant", "code": safe_error(error)})
    try:
        report["checks"] = validate(report["postgres"], report["qdrant"], browser)
    except Exception as error:
        report["errors"].append({"stage": "validation", "code": safe_error(error)})
    if (not report["errors"] and report["checks"] and all(check["passed"] for check in report["checks"])):
        report["status"] = "PASS"
    target = Path(run_dir) / "storage-evidence.json"
    temporary = target.with_suffix(".json.tmp")
    with temporary.open("w", encoding="utf-8") as output:
        os.chmod(temporary, 0o600)
        output.write(sanitized(report))
    temporary.replace(target)
    print("V47 storage evidence: %s (%s)" % (report["status"], target))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
