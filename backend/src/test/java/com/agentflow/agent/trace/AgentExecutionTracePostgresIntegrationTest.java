package com.agentflow.agent.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.snapshot.AgentTaskSnapshotResolver;
import com.agentflow.agent.task.dispatch.TaskDispatcher;
import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.TaskEventType;
import com.agentflow.agent.task.model.TokenUsageQuality;
import com.agentflow.agent.task.service.AgentTaskApplicationService;
import com.agentflow.agent.task.service.AgentTaskLifecycleTransactionService;
import com.agentflow.agent.task.service.CreateAgentTaskCommand;
import com.agentflow.agent.trace.dto.TaskTraceView;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.tool.ToolExecutionCommand;
import com.agentflow.tool.ToolExecutionResult;
import com.agentflow.tool.ToolRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** Focused PostgreSQL evidence for M4D locks, constraints, and short transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(AgentExecutionTracePostgresIntegrationTest.TraceTestConfiguration.class)
@EnabledIfSystemProperty(named = "agentflow.postgres.integration", matches = "true")
class AgentExecutionTracePostgresIntegrationTest {
    private static final long USER_ID = 3901L;
    private static final long OTHER_USER_ID = 3902L;
    private static final long AGENT_ID = 4901L;
    private static final long TOOL_ID = 270000000000000001L;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredProperty("agentflow.postgres.url"));
        registry.add("spring.datasource.username", () -> requiredProperty("agentflow.postgres.user"));
        registry.add("spring.datasource.password", () -> System.getProperty("agentflow.postgres.password", ""));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "16");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("mybatis-plus.configuration.log-impl", () ->
                "org.apache.ibatis.logging.nologging.NoLoggingImpl");
        registry.add("logging.level.com.agentflow", () -> "WARN");
        registry.add("agentflow.task.execution.mode", () -> "unavailable");
        registry.add("agentflow.security.jwt.secret-base64", () ->
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AgentTaskApplicationService taskApplicationService;
    @Autowired
    private AgentTaskLifecycleTransactionService taskLifecycleTransactions;
    @Autowired
    private ExecutionRecorderFactory recorderFactory;
    @Autowired
    private TaskTraceQueryService traceQueryService;
    @Autowired
    private ToolRuntime toolRuntime;
    @Autowired
    private OuterTransactionProbe outerTransactionProbe;

    @MockBean
    private AgentTaskSnapshotResolver snapshotResolver;
    @MockBean
    private TaskDispatcher taskDispatcher;

    @BeforeEach
    void resetDatabaseAndFakes() {
        jdbc.update("DELETE FROM tool_call_log");
        jdbc.update("DELETE FROM rag_retrieval_hit");
        jdbc.update("DELETE FROM rag_retrieval_log");
        jdbc.update("DELETE FROM llm_call_log");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_task_event");
        jdbc.update("DELETE FROM agent_task");
        jdbc.update("DELETE FROM agent_tool_binding");
        jdbc.update("DELETE FROM agent_knowledge_binding");
        jdbc.update("DELETE FROM knowledge_document_reprocess_task");
        jdbc.update("DELETE FROM knowledge_document_deletion_task");
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
        jdbc.update("DELETE FROM agent_app");
        jdbc.update("DELETE FROM app_user");

        jdbc.update("""
                INSERT INTO app_user (id, username, email, password_hash, display_name)
                VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)
                """,
                USER_ID, "v39-user", "v39-user@example.test", "hash", "V39 User",
                OTHER_USER_ID, "v39-other", "v39-other@example.test", "hash", "V39 Other"
        );
        jdbc.update("""
                INSERT INTO agent_app (
                    id, user_id, name, system_prompt, model_provider, model_name,
                    max_steps, max_tool_calls, max_tokens, timeout_seconds, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                AGENT_ID, USER_ID, "V39 Agent", "Use frozen evidence.",
                "openai-compatible", "test-model", 6, 4, 8000, 120, "ACTIVE"
        );

        reset(snapshotResolver, taskDispatcher);
        when(snapshotResolver.resolve(USER_ID, AGENT_ID)).thenReturn(snapshot(AGENT_ID));
        outerTransactionProbe.reset();
    }

    @Test
    void shouldApplyV1ThroughV19AndEnforceCoreStepAndLlmChecks() throws Exception {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success",
                Integer.class
        )).isEqualTo(19);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '19'
                  AND script = 'V19__create_agent_execution_trace.sql'
                  AND success
                """,
                Integer.class
        )).isEqualTo(1);

        AgentTask queued = createTask("queued-step", "queued step");
        ExecutionRecorder queuedRecorder = recorderFactory.open(queued.getId());
        assertThatThrownBy(() -> queuedRecorder.startStep(StepType.LLM_DECISION, "not running"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING task");

        AgentTask running = claim(createTask("migration", "trace constraints"));
        ExecutionRecorder recorder = recorderFactory.open(running.getId());
        StepHandle step = recorder.startStep(StepType.LLM_DECISION, "Decision");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE agent_step SET summary = '[]'::jsonb WHERE id = ?", step.stepId()
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO llm_call_log (
                    id, task_id, step_id, call_type, provider, requested_model,
                    request_snapshot, response_text, input_tokens, output_tokens, total_tokens,
                    usage_quality, latency_ms, status, created_at
                ) VALUES (?, ?, ?, 'DECISION', 'test', 'model', '{}'::jsonb, '{}', 0, 0, 0,
                          'UNKNOWN', 0, 'SUCCESS', ?)
                """, 9101L, running.getId(), step.stepId(), OffsetDateTime.now()
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO llm_call_log (
                    id, task_id, step_id, call_type, provider, requested_model,
                    request_snapshot, response_text, input_tokens, output_tokens, total_tokens,
                    usage_quality, latency_ms, status, created_at
                ) VALUES (?, ?, ?, 'DECISION', 'test', 'model', '[]'::jsonb, '{}', 1, 1, 2,
                          'EXACT', 0, 'SUCCESS', ?)
                """, 9102L, running.getId(), step.stepId(), OffsetDateTime.now()
        )).isInstanceOf(DataIntegrityViolationException.class);

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("Authorization", "Bearer must-not-persist");
        summary.put("maxTokens", 512);
        recorder.completeStep(step, new StepSummary(summary));
        JsonNode persistedSummary = objectMapper.readTree(jdbc.queryForObject(
                "SELECT summary::text FROM agent_step WHERE id = ?",
                String.class,
                step.stepId()
        ));
        assertThat(persistedSummary.path("Authorization").asText()).isEqualTo("[REDACTED]");
        assertThat(persistedSummary.path("maxTokens").asInt()).isEqualTo(512);
    }

    @Test
    void shouldAllocateConcurrentStepIndexesAndAllowOnlyOneTerminalTransition() throws Exception {
        AgentTask running = claim(createTask("step-race", "step race"));
        ExecutionRecorder recorder = recorderFactory.open(running.getId());
        List<Callable<StepHandle>> starts = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int titleIndex = index;
            starts.add(() -> recorder.startStep(StepType.LLM_DECISION, "Decision " + titleIndex));
        }

        List<StepHandle> handles = concurrently(starts);

        assertThat(handles).extracting(StepHandle::stepIndex)
                .containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(jdbc.queryForList(
                "SELECT step_index FROM agent_step WHERE task_id = ? ORDER BY step_index",
                Integer.class,
                running.getId()
        )).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);

        StepHandle raced = handles.getFirst();
        List<Boolean> terminalWinners = concurrently(
                () -> attemptTerminal(() -> recorder.completeStep(
                        raced,
                        new StepSummary(objectMapper.createObjectNode().put("outcome", "success"))
                )),
                () -> attemptTerminal(() -> recorder.failStep(
                        raced,
                        "SCRIPTED_FAILURE",
                        "Scripted failure"
                ))
        );

        assertThat(terminalWinners).containsOnlyOnce(true);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_step WHERE id = ?",
                String.class,
                raced.stepId()
        )).isIn("SUCCESS", "FAILED");
    }

    @Test
    void shouldRejectCrossTaskAndWrongTypeLinksWhileKeepingStandaloneToolCallsLegal() {
        AgentTask taskA = claim(createTask("links-a", "links a"));
        AgentTask taskB = claim(createTask("links-b", "links b"));
        ExecutionRecorder recorderA = recorderFactory.open(taskA.getId());
        ExecutionRecorder recorderB = recorderFactory.open(taskB.getId());
        StepHandle decisionA = recorderA.startStep(StepType.LLM_DECISION, "Decision A");
        StepHandle retrievalA = recorderA.startStep(StepType.PRE_RETRIEVAL, "Retrieval A");
        StepHandle toolA = recorderA.startStep(StepType.TOOL_CALL, "Tool A");
        StepHandle toolB = recorderB.startStep(StepType.TOOL_CALL, "Tool B");

        ToolExecutionResult standalone = toolRuntime.execute(ToolExecutionCommand.standalone(
                TOOL_ID,
                objectMapper.createObjectNode().put("orderNo", "order_1024")
        ));
        assertThat(standalone.success()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tool_call_log WHERE task_id IS NULL AND step_id IS NULL",
                Integer.class
        )).isEqualTo(1);

        assertThatThrownBy(() -> insertRunningToolLog(9201L, taskA.getId(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRunningToolLog(9202L, null, toolA.stepId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRunningToolLog(9203L, taskA.getId(), toolB.stepId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertSuccessfulLlmLog(
                9204L,
                taskA.getId(),
                decisionA.stepId(),
                "FINAL_GENERATION"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSuccessfulLlmLog(
                9205L,
                taskB.getId(),
                decisionA.stepId(),
                "DECISION"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSuccessfulRagLog(
                9206L,
                taskB.getId(),
                retrievalA.stepId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRollbackRetrievalAndHitsTogetherAndKeepSnapshotsAfterSourceDeletion() {
        AgentTask task = claim(createTask("rag-atomic", "rag atomic"));
        ExecutionRecorder recorder = recorderFactory.open(task.getId());
        StepHandle step = recorder.startStep(StepType.PRE_RETRIEVAL, "Retrieve");

        List<RagRetrievalHitRecord> duplicateRanks = List.of(
                hit(1, "C1", 9301L, 9302L, 9303L, "first"),
                hit(1, "C2", 9304L, 9305L, 9306L, "second")
        );
        assertThatThrownBy(() -> recorder.recordRagRetrieval(retrieval(step, duplicateRanks)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rag_retrieval_log WHERE task_id = ?",
                Integer.class,
                task.getId()
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rag_retrieval_hit",
                Integer.class
        )).isZero();

        insertKnowledgeSource(9401L, 9402L, 9403L);
        recorder.recordRagRetrieval(retrieval(
                step,
                List.of(hit(1, "C1", 9403L, 9402L, 9401L, "historical content"))
        ));
        jdbc.update("DELETE FROM knowledge_chunk WHERE id = ?", 9403L);
        jdbc.update("DELETE FROM knowledge_document WHERE id = ?", 9402L);
        jdbc.update("DELETE FROM knowledge_base WHERE id = ?", 9401L);

        assertThat(jdbc.queryForObject(
                "SELECT content_snapshot FROM rag_retrieval_hit WHERE chunk_id_snapshot = ?",
                String.class,
                9403L
        )).isEqualTo("historical content");
        assertThat(jdbc.queryForObject(
                "SELECT vector_generation FROM rag_retrieval_hit WHERE chunk_id_snapshot = ?",
                Long.class,
                9403L
        )).isEqualTo(1L);
    }

    @Test
    void shouldKeepCommittedEarlyTraceWhenAnOuterOrLaterTraceOperationFails() {
        AgentTask task = claim(createTask("requires-new", "requires new"));

        assertThatThrownBy(() -> outerTransactionProbe.startStepThenRollback(task.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced outer rollback");
        StepHandle committed = outerTransactionProbe.lastStep();
        assertThat(committed).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_step WHERE id = ?",
                Integer.class,
                committed.stepId()
        )).isEqualTo(1);

        ObjectNode oversized = objectMapper.createObjectNode().put("content", "x".repeat(20_000));
        assertThatThrownBy(() -> recorderFactory.open(task.getId()).completeStep(
                committed,
                new StepSummary(oversized)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8 byte limit");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_step WHERE id = ?",
                String.class,
                committed.stepId()
        )).isEqualTo("RUNNING");

        ObjectNode eventPayload = objectMapper.createObjectNode();
        eventPayload.put("phase", "DECIDING");
        eventPayload.put("x-api-key", "must-not-persist");
        recorderFactory.open(task.getId()).appendEvent(new TaskEventRecord(
                TaskEventType.PHASE_CHANGED,
                eventPayload
        ));
        JsonNode persistedEvent = readJson(jdbc.queryForObject(
                """
                SELECT payload::text
                FROM agent_task_event
                WHERE task_id = ?
                ORDER BY sequence_no DESC
                LIMIT 1
                """,
                String.class,
                task.getId()
        ));
        assertThat(persistedEvent.path("x-api-key").asText()).isEqualTo("[REDACTED]");
        assertThat(jdbc.queryForObject(
                "SELECT last_event_sequence FROM agent_task WHERE id = ?",
                Long.class,
                task.getId()
        )).isEqualTo(3L);
    }

    @Test
    void shouldReturnAnOwnerScopedImmutableTraceInStableSemanticOrder() {
        AgentTask task = claim(createTask("aggregate", "aggregate"));
        ExecutionRecorder recorder = recorderFactory.open(task.getId());
        StepHandle retrievalStep = recorder.startStep(StepType.PRE_RETRIEVAL, "Retrieve");
        StepHandle decisionStep = recorder.startStep(StepType.LLM_DECISION, "Decide");
        StepHandle toolStep = recorder.startStep(StepType.TOOL_CALL, "Query order");
        StepHandle finalStep = recorder.startStep(StepType.LLM_FINAL_GENERATION, "Generate");

        recorder.recordRagRetrieval(retrieval(
                retrievalStep,
                List.of(
                        hit(2, "C2", 9502L, 9512L, 9522L, "second ranked"),
                        hit(1, "C1", 9501L, 9511L, 9521L, "first ranked")
                )
        ));
        recorder.recordLlmCall(successfulLlm(decisionStep, LlmCallType.DECISION, "req-1"));
        recorder.recordLlmCall(successfulLlm(decisionStep, LlmCallType.DECISION, "req-2"));
        ToolExecutionResult toolResult = toolRuntime.execute(ToolExecutionCommand.taskScoped(
                TOOL_ID,
                task.getId(),
                toolStep.stepId(),
                objectMapper.createObjectNode().put("orderNo", "order_1024")
        ));
        assertThat(toolResult.success()).isTrue();
        recorder.recordLlmCall(successfulLlm(
                finalStep,
                LlmCallType.FINAL_GENERATION,
                "req-final"
        ));

        complete(recorder, retrievalStep, "retrieved");
        complete(recorder, decisionStep, "decided");
        complete(recorder, toolStep, "queried");
        complete(recorder, finalStep, "generated");

        TaskTraceView trace = traceQueryService.findOwnedTrace(USER_ID, task.getId());
        assertThat(trace.steps()).extracting(TaskTraceView.Step::stepIndex)
                .containsExactly(0, 1, 2, 3);
        assertThat(trace.steps().get(0).ragRetrievals()).hasSize(1);
        assertThat(trace.steps().get(0).ragRetrievals().getFirst().hits())
                .extracting(TaskTraceView.RagHit::rankNo)
                .containsExactly(1, 2);
        assertThat(trace.steps().get(1).llmCalls())
                .extracting(TaskTraceView.LlmCall::providerRequestId)
                .containsExactly("req-1", "req-2");
        assertThat(trace.steps().get(2).toolCalls()).singleElement()
                .extracting(TaskTraceView.ToolCall::status)
                .isEqualTo("SUCCESS");
        assertThat(trace.steps().get(3).llmCalls()).singleElement()
                .extracting(TaskTraceView.LlmCall::callType)
                .isEqualTo("FINAL_GENERATION");

        assertThatThrownBy(() -> trace.steps().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        ObjectNode returnedSummary = (ObjectNode) trace.steps().get(1).summary();
        returnedSummary.put("mutated", true);
        assertThat(trace.steps().get(1).summary().has("mutated")).isFalse();
        assertThatThrownBy(() -> traceQueryService.findOwnedTrace(OTHER_USER_ID, task.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
    }

    private AgentTask createTask(String key, String input) {
        return taskApplicationService.createTask(new CreateAgentTaskCommand(
                USER_ID,
                AGENT_ID,
                key,
                input
        ));
    }

    private AgentTask claim(AgentTask task) {
        AgentTask running = taskLifecycleTransactions.claim(task.getId());
        assertThat(running).isNotNull();
        return running;
    }

    private LlmCallRecord successfulLlm(
            StepHandle step,
            LlmCallType callType,
            String providerRequestId
    ) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("modelProvider", "openai-compatible");
        request.put("modelName", "test-model");
        request.put("temperature", 0.2);
        request.put("topP", 0.8);
        request.put("maxOutputTokens", 512);
        request.putArray("messages")
                .addObject()
                .put("role", "USER")
                .put("content", "Diagnose the task using only recorded evidence.");
        String response = callType == LlmCallType.DECISION
                ? "{\"decision\":\"FINISH\",\"reason\":\"enough evidence\"}"
                : "Final answer";
        return new LlmCallRecord(
                step,
                callType,
                "openai-compatible",
                "test-model",
                "resolved-test-model",
                request,
                response,
                "stop",
                providerRequestId,
                10,
                5,
                15,
                TokenUsageQuality.EXACT,
                4,
                TraceRecordStatus.SUCCESS,
                null,
                null
        );
    }

    private RagRetrievalRecord retrieval(
            StepHandle step,
            List<RagRetrievalHitRecord> hits
    ) {
        ObjectNode corpus = objectMapper.createObjectNode();
        corpus.put("snapshotVersion", "agent-task-snapshot-v1");
        return new RagRetrievalRecord(
                step,
                "payment timeout",
                "dashscope-te-v4-1024-cosine",
                corpus,
                5,
                new BigDecimal("0.2"),
                hits.size(),
                hits.size(),
                0,
                3,
                TraceRecordStatus.SUCCESS,
                null,
                null,
                hits
        );
    }

    private RagRetrievalHitRecord hit(
            int rank,
            String citation,
            long chunkId,
            long documentId,
            long knowledgeBaseId,
            String content
    ) {
        return new RagRetrievalHitRecord(
                rank,
                citation,
                chunkId,
                documentId,
                knowledgeBaseId,
                1,
                new BigDecimal("0.8"),
                content,
                objectMapper.createObjectNode().put("source", "snapshot")
        );
    }

    private void complete(ExecutionRecorder recorder, StepHandle step, String outcome) {
        recorder.completeStep(
                step,
                new StepSummary(objectMapper.createObjectNode().put("outcome", outcome))
        );
    }

    private void insertRunningToolLog(long id, Long taskId, Long stepId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                INSERT INTO tool_call_log (
                    id, task_id, step_id, tool_id, tool_code, tool_name, arguments,
                    status, retry_count, started_at, created_at
                ) VALUES (?, ?, ?, ?, 'order_query', 'Order Query', '{}'::jsonb,
                          'RUNNING', 0, ?, ?)
                """, id, taskId, stepId, TOOL_ID, now, now);
    }

    private void insertSuccessfulLlmLog(long id, long taskId, long stepId, String callType) {
        jdbc.update("""
                INSERT INTO llm_call_log (
                    id, task_id, step_id, call_type, provider, requested_model,
                    request_snapshot, response_text, usage_quality, latency_ms, status, created_at
                ) VALUES (?, ?, ?, ?, 'test', 'model', '{}'::jsonb, '{}',
                          'UNKNOWN', 0, 'SUCCESS', ?)
                """, id, taskId, stepId, callType, OffsetDateTime.now());
    }

    private void insertSuccessfulRagLog(long id, long taskId, long stepId) {
        jdbc.update("""
                INSERT INTO rag_retrieval_log (
                    id, task_id, step_id, query, embedding_profile_code, corpus_snapshot,
                    top_k, similarity_threshold, candidate_count, valid_hit_count,
                    stale_hit_count, latency_ms, status, created_at
                ) VALUES (?, ?, ?, 'query', 'profile', '{}'::jsonb,
                          5, 0.2, 0, 0, 0, 0, 'SUCCESS', ?)
                """, id, taskId, stepId, OffsetDateTime.now());
    }

    private void insertKnowledgeSource(long knowledgeBaseId, long documentId, long chunkId) {
        jdbc.update("""
                INSERT INTO knowledge_base (id, user_id, name)
                VALUES (?, ?, 'Trace source')
                """, knowledgeBaseId, USER_ID);
        jdbc.update("""
                INSERT INTO knowledge_document (
                    id, user_id, knowledge_base_id, file_name, file_type, mime_type, file_size,
                    storage_bucket, storage_object_key, parse_status, vector_generation
                ) VALUES (?, ?, ?, 'trace.txt', 'TXT', 'text/plain', 10,
                          'test', 'trace-source', 'COMPLETED', 1)
                """, documentId, USER_ID, knowledgeBaseId);
        jdbc.update("""
                INSERT INTO knowledge_chunk (
                    id, user_id, knowledge_base_id, document_id, chunk_index, content,
                    char_count, token_count, vectorization_status, content_hash,
                    vector_generation, chunk_strategy_version
                ) VALUES (?, ?, ?, ?, 0, 'historical content', 18, 3, 'PENDING', ?, 1,
                          'structured-token-v1')
                """, chunkId, USER_ID, knowledgeBaseId, documentId, "a".repeat(64));
    }

    private JsonNode readJson(String serialized) {
        try {
            return objectMapper.readTree(serialized);
        } catch (Exception ex) {
            throw new IllegalStateException("Expected valid JSON", ex);
        }
    }

    private static boolean attemptTerminal(Runnable transition) {
        try {
            transition.run();
            return true;
        } catch (IllegalStateException expectedLoser) {
            return false;
        }
    }

    private static <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        return concurrently(List.of(first, second));
    }

    private static <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CyclicBarrier barrier = new CyclicBarrier(calls.size());
        try {
            List<Future<T>> futures = calls.stream()
                    .map(call -> executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return call.call();
                    }))
                    .toList();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static AgentTaskExecutionSnapshot snapshot(long agentId) {
        return new AgentTaskExecutionSnapshot(
                "agent-task-snapshot-v1",
                new AgentTaskExecutionSnapshot.AgentSnapshot(
                        Long.toString(agentId), "Use frozen evidence.", "ACTIVE", 6, 4, 8000, 120
                ),
                new AgentTaskExecutionSnapshot.RuntimeSnapshot(
                        "agent-decision-json-v1", "agent-runtime-rules-v1", "77ed0fd"
                ),
                new AgentTaskExecutionSnapshot.ChatModelSnapshot(
                        "openai-compatible-default", "openai-compatible", "test-model",
                        new BigDecimal("0.2"), new BigDecimal("0.8"), 32768, true
                ),
                new AgentTaskExecutionSnapshot.RetrievalSnapshot(
                        List.of(), 5, new BigDecimal("0.2"), false
                ),
                List.of()
        );
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing test system property " + name);
        }
        return value;
    }

    @TestConfiguration
    static class TraceTestConfiguration {
        @Bean
        OuterTransactionProbe outerTransactionProbe(ExecutionRecorderFactory recorderFactory) {
            return new OuterTransactionProbe(recorderFactory);
        }
    }

    static class OuterTransactionProbe {
        private final ExecutionRecorderFactory recorderFactory;
        private volatile StepHandle lastStep;

        OuterTransactionProbe(ExecutionRecorderFactory recorderFactory) {
            this.recorderFactory = recorderFactory;
        }

        @Transactional
        public void startStepThenRollback(long taskId) {
            lastStep = recorderFactory.open(taskId).startStep(StepType.LLM_DECISION, "Committed step");
            throw new IllegalStateException("forced outer rollback");
        }

        StepHandle lastStep() {
            return lastStep;
        }

        void reset() {
            lastStep = null;
        }
    }
}
