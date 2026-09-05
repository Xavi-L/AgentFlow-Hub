package com.agentflow.agent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.task.dispatch.TaskDispatcher;
import com.agentflow.agent.task.execution.*;
import com.agentflow.agent.task.model.*;
import com.agentflow.agent.task.service.*;
import com.agentflow.infra.llm.*;
import com.agentflow.knowledge.vector.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real Runner/Engine/ToolRuntime/recorder/resolver/PostgreSQL, controlled model and vector edges. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "agentflow.postgres.integration", matches = "true")
class AgentTaskExecutionPostgresIntegrationTest {
    static final long OWNER = 4001, AGENT = 4002, KB = 4003, DOCUMENT = 4004, CHUNK = 4005;
    static final long ORDER_TOOL = 270000000000000001L, PAYMENT_TOOL = 280000000000000001L;
    static final String VECTOR = "40000000-0000-0000-0000-000000000005";
    static final String CONTENT = "Payment timeout requires checking the order and payment logs.";
    static final String FINISH = "{\"type\":\"FINISH\",\"answerPlan\":\"Explain recorded facts\"}";
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentTaskApplicationService tasks;
    @Autowired AgentTaskQueryService query;
    @Autowired AgentTaskLifecycleTransactionService lifecycle;
    @Autowired TaskRunner runner;
    @Autowired ObjectMapper json;
    @MockBean TaskDispatcher dispatcher;
    @MockBean LlmGateway llm;
    @MockBean EmbeddingGateway embeddings;
    @MockBean VectorStoreGateway vectors;
    @MockBean Clock clock;
    Instant now;
    final List<LlmChatRequest> calls = new ArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getProperty("agentflow.postgres.url"));
        r.add("spring.datasource.username", () -> System.getProperty("agentflow.postgres.user"));
        r.add("spring.datasource.password", () -> System.getProperty("agentflow.postgres.password", ""));
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        r.add("mybatis-plus.configuration.log-impl", () -> "org.apache.ibatis.logging.nologging.NoLoggingImpl");
        r.add("logging.level.com.agentflow", () -> "WARN");
        r.add("agentflow.task.execution.mode", () -> "engine");
        r.add("agentflow.security.jwt.secret-base64", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @BeforeEach
    void setup() {
        // This class is opt-in and runs only against a disposable test database.
        for (String table : List.of("tool_call_log", "rag_retrieval_hit", "rag_retrieval_log", "llm_call_log",
                "agent_step", "agent_task_event", "agent_task", "agent_tool_binding", "agent_knowledge_binding",
                "knowledge_document_reprocess_task", "knowledge_document_deletion_task", "knowledge_chunk",
                "knowledge_document", "knowledge_base", "agent_app", "app_user")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE tool_definition SET status='ACTIVE', deleted_at=NULL WHERE id IN (?,?)", ORDER_TOOL, PAYMENT_TOOL);
        jdbc.update("INSERT INTO app_user(id,username,email,password_hash,display_name) VALUES (?, 'v40', 'v40@example.test', 'hash', 'V40')", OWNER);
        jdbc.update("""
                INSERT INTO agent_app(id,user_id,name,system_prompt,model_provider,model_name,max_steps,max_tool_calls,max_tokens,timeout_seconds)
                VALUES (?,?,'V40','Frozen instruction marker','openai-compatible','frozen-model',6,4,50000,120)
                """, AGENT, OWNER);
        jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,'Payment knowledge')", KB, OWNER);
        jdbc.update("""
                INSERT INTO knowledge_document(id,user_id,knowledge_base_id,file_name,file_type,mime_type,file_size,
                    storage_bucket,storage_object_key,parse_status,vector_generation)
                VALUES (?,?,?,'payment.txt','TXT','text/plain',100,'test','v40','COMPLETED',1)
                """, DOCUMENT, OWNER, KB);
        jdbc.update("""
                INSERT INTO knowledge_chunk(id,user_id,knowledge_base_id,document_id,chunk_index,content,char_count,
                    token_count,vectorization_status,content_hash,vector_id,vector_generation,chunk_strategy_version)
                VALUES (?,?,?,?,0,?,?,15,'COMPLETED',?,?,1,'structured-token-v1')
                """, CHUNK, OWNER, KB, DOCUMENT, CONTENT, CONTENT.length(), ChunkVectorIdentityFactory.contentHash(CONTENT), VECTOR);
        jdbc.update("INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id) VALUES (4010,?,?,?)", OWNER, AGENT, KB);
        jdbc.update("INSERT INTO agent_tool_binding(id,user_id,agent_id,tool_id) VALUES (4011,?,?,?),(4012,?,?,?)",
                OWNER, AGENT, ORDER_TOOL, OWNER, AGENT, PAYMENT_TOOL);
        reset(dispatcher, llm, embeddings, vectors, clock);
        now = Instant.now();
        when(clock.instant()).thenAnswer(i -> now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(embeddings.embed(any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new EmbeddingVector(Collections.nCopies(1024, 0.1f));
        });
        when(vectors.search(any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            VectorSearchRequest request = i.getArgument(0);
            assertThat(request.userId()).isEqualTo(OWNER);
            assertThat(request.knowledgeBaseId()).isEqualTo(KB);
            assertThat(request.documents()).containsExactly(new VectorSearchRequest.DocumentGeneration(DOCUMENT, 1));
            return List.of(new VectorSearchHit(VECTOR, CHUNK, 0.9, ChunkVectorIdentityFactory.contentHash(CONTENT)));
        });
        calls.clear();
    }

    @Test
    void shouldExecuteFrozenSnapshotWithOneRagTwoRealToolsAndOneFinalCall() throws Exception {
        AgentTask task = create("main");
        jdbc.update("UPDATE agent_app SET system_prompt='CHANGED',model_name='changed-model',max_tokens=256 WHERE id=?", AGENT);
        jdbc.update("DELETE FROM agent_tool_binding WHERE agent_id=?", AGENT);
        jdbc.update("DELETE FROM agent_knowledge_binding WHERE agent_id=?", AGENT);
        script(tool("order_query"), tool("payment_log_query"), FINISH, "Payment timed out [S1].");
        runner.run(task.getId());
        AgentTask done = query.findById(task.getId());
        assertThat(done.getStatus()).isEqualTo("COMPLETED");
        assertThat(done.getFinalAnswer()).isEqualTo("Payment timed out [S1].");
        assertThat(done.getDecisionTurnsUsed()).isEqualTo(3);
        assertThat(done.getToolCallsUsed()).isEqualTo(2);
        assertThat(done.getTotalTokens()).isEqualTo(60);
        assertThat(done.getTokenUsageQuality()).isEqualTo("EXACT");
        assertThat(done.getMaxTotalTokens()).isEqualTo(50000);
        assertThat(json.readTree(done.getCitations())).isNotEmpty();
        assertThat(calls).hasSize(4).allSatisfy(c -> assertThat(c.modelName()).isEqualTo("frozen-model"));
        assertThat(calls.getFirst().messages().toString()).contains("Frozen instruction marker", CONTENT);
        assertThat(count("rag_retrieval_log", task)).isEqualTo(1);
        assertThat(count("rag_retrieval_hit", "retrieval_id IN (SELECT id FROM rag_retrieval_log WHERE task_id=?)", task)).isEqualTo(1);
        assertThat(count("tool_call_log", task)).isEqualTo(2);
        assertThat(count("llm_call_log", task)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT step_type FROM agent_step WHERE task_id=? ORDER BY step_index", String.class, task.getId()))
                .containsExactly("PRE_RETRIEVAL", "LLM_DECISION", "TOOL_CALL", "LLM_DECISION", "TOOL_CALL", "LLM_DECISION", "LLM_FINAL_GENERATION");
        List<String> semantic = events(task).stream().filter(e -> !e.equals("PHASE_CHANGED")).toList();
        assertThat(semantic).containsExactly("TASK_CREATED", "TASK_STARTED", "RAG_FINISHED", "DECISION_FINISHED",
                "TOOL_STARTED", "TOOL_FINISHED", "DECISION_FINISHED", "TOOL_STARTED", "TOOL_FINISHED",
                "DECISION_FINISHED", "FINAL_GENERATION_STARTED", "ANSWER_CHUNK", "TASK_COMPLETED");
        assertContinuous(task);
        verify(embeddings, times(1)).embed(any());
        verify(vectors, times(1)).search(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"generation", "deleted", "disabled", "empty"})
    void shouldAllowEmptyRagWithoutFallingBackToCurrentCorpus(String revocation) {
        AgentTask task = create("rag-" + revocation);
        switch (revocation) {
            case "generation" -> jdbc.update("UPDATE knowledge_document SET vector_generation=2 WHERE id=?", DOCUMENT);
            case "deleted" -> jdbc.update("UPDATE knowledge_document SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", DOCUMENT);
            case "disabled" -> jdbc.update("UPDATE knowledge_base SET status='DISABLED' WHERE id=?", KB);
            case "empty" -> doReturn(List.of()).when(vectors).search(any());
        }
        script(tool("order_query"), FINISH, "Order evidence remains available.");
        runner.run(task.getId());
        assertThat(query.findById(task.getId()).getStatus()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT valid_hit_count FROM rag_retrieval_log WHERE task_id=?", Integer.class, task.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT stale_hit_count FROM rag_retrieval_log WHERE task_id=?", Integer.class,
                task.getId())).isEqualTo(revocation.equals("empty") ? 0 : 1);
        assertThat(calls).allSatisfy(call -> assertThat(call.messages().toString()).doesNotContain(CONTENT));
        assertThat(count("tool_call_log", task)).isEqualTo(1);
        verify(vectors, atMostOnce()).search(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISABLED", "DELETED"})
    void shouldObserveEmergencyToolRevocation(String revoke) {
        AgentTask task = create("revoke-" + revoke);
        if (revoke.equals("DISABLED")) jdbc.update("UPDATE tool_definition SET status='DISABLED' WHERE id=?", ORDER_TOOL);
        else jdbc.update("UPDATE tool_definition SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", ORDER_TOOL);
        script(tool("order_query"));
        runner.run(task.getId());
        assertThat(query.findById(task.getId()).getStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tool_call_log WHERE task_id=? AND status='SUCCESS'", Integer.class, task.getId())).isZero();
        assertThat(calls).hasSize(1);
    }

    @Test
    void shouldReuseSecondDuplicateAndFailThirdWithoutInventingToolLogs() {
        AgentTask task = create("duplicate");
        script(tool("order_query"), tool("order_query"), tool("order_query"));
        runner.run(task.getId());
        assertThat(query.findById(task.getId()).getErrorCode()).isEqualTo("AGENT_DUPLICATE_TOOL_LOOP");
        assertThat(count("tool_call_log", task)).isEqualTo(1);
        assertThat(query.findById(task.getId()).getToolCallsUsed()).isEqualTo(1);
    }

    @Test
    void shouldRecordInvalidDecisionUsageAndSafeDiagnostic() {
        AgentTask task = create("invalid");
        script("not JSON Authorization: Bearer secret-value");
        runner.run(task.getId());
        assertThat(query.findById(task.getId()).getErrorCode()).isEqualTo("AGENT_INVALID_DECISION");
        assertThat(query.findById(task.getId()).getTotalTokens()).isEqualTo(15);
        Map<String,Object> log = jdbc.queryForMap("SELECT * FROM llm_call_log WHERE task_id=?", task.getId());
        assertThat(log.get("status")).isEqualTo("FAILED");
        assertThat(log.get("response_text")).isNull();
        assertThat(log.get("total_tokens")).isEqualTo(15);
        assertThat(log.get("latency_ms")).isEqualTo(7L);
        assertThat(log.toString()).doesNotContain("secret-value", "not JSON");
        assertThat(events(task)).doesNotContain("ANSWER_CHUNK", "TASK_COMPLETED");
    }

    @Test
    void shouldPersistActualOverrunAndKeepSuccessfulTaskBudgetFence() {
        AgentTask task = create("overrun");
        when(llm.chat(any())).thenReturn(result(FINISH, LlmTokenUsage.known(50000, 20, 50020)));
        runner.run(task.getId());
        AgentTask failed = query.findById(task.getId());
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getTotalTokens()).isEqualTo(50020);
        assertThat(failed.getTerminationReason()).isEqualTo("TOKEN_BUDGET_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT total_tokens FROM llm_call_log WHERE task_id=?", Integer.class, task.getId())).isEqualTo(50020);
        AgentTask success = create("success-fence");
        script(FINISH, "Answer.");
        runner.run(success.getId());
        assertThat(query.findById(success.getId()).getStatus()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_task SET input_tokens=50020,output_tokens=0,total_tokens=50020 WHERE id=?", success.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void shouldEstimateMissingUsageAndRejectUnknownFinalCitation() {
        AgentTask task = create("estimated");
        AtomicInteger n = new AtomicInteger();
        when(llm.chat(any())).thenAnswer(i -> result(n.getAndIncrement() == 0 ? FINISH : "Answer [S1].", LlmTokenUsage.unknown()));
        runner.run(task.getId());
        AgentTask done = query.findById(task.getId());
        assertThat(done.getStatus()).isEqualTo("COMPLETED");
        assertThat(done.getTokenUsageQuality()).isEqualTo("ESTIMATED");
        assertThat(done.getTotalTokens()).isPositive();
        assertThat(jdbc.queryForList("SELECT usage_quality FROM llm_call_log WHERE task_id=?", String.class, task.getId()))
                .containsOnly("ESTIMATED");
        AgentTask invalid = create("citation");
        script(FINISH, "Unsupported [S99].");
        runner.run(invalid.getId());
        assertThat(query.findById(invalid.getId()).getStatus()).isEqualTo("FAILED");
        assertThat(events(invalid)).doesNotContain("ANSWER_CHUNK", "TASK_COMPLETED");
    }

    @Test
    void shouldUseBoundedFinalGenerationAtToolLimit() {
        jdbc.update("UPDATE agent_app SET max_tool_calls=1 WHERE id=?", AGENT);
        AgentTask task = create("tool-limit");
        script(tool("order_query"), "Bounded final answer.");
        runner.run(task.getId());
        AgentTask done = query.findById(task.getId());
        assertThat(done.getStatus()).isEqualTo("COMPLETED");
        assertThat(done.getTerminationReason()).isEqualTo("MAX_TOOL_CALLS");
        assertThat(done.getDecisionTurnsUsed()).isEqualTo(1);
        assertThat(calls).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "timeout"})
    void shouldKeepUsageWhenCancellationOrDeadlineArrivesDuringModelCall(String signal) {
        AgentTask task = create(signal);
        when(llm.chat(any())).thenAnswer(i -> {
            if (signal.equals("cancel")) lifecycle.requestCancellation(OWNER, task.getId());
            else now = now.plusSeconds(121);
            return result(FINISH, LlmTokenUsage.known(10, 5, 15));
        });
        runner.run(task.getId());
        assertThat(query.findById(task.getId()).getStatus()).isEqualTo(signal.equals("cancel") ? "CANCELLED" : "TIMED_OUT");
        assertThat(query.findById(task.getId()).getTotalTokens()).isEqualTo(15);
        assertThat(count("llm_call_log", task)).isEqualTo(1);
        verify(llm, times(1)).chat(any());
        assertThat(events(task)).doesNotContain("ANSWER_CHUNK", "TASK_COMPLETED");
    }

    @Test
    void shouldPublishAllAnswerChunksAtomicallyAndRollbackInjectedMidBatchFailure() throws Exception {
        AgentTask task = create("atomic");
        lifecycle.claim(task.getId());
        long cursor = query.findById(task.getId()).getLastEventSequence();
        String answer = "中文😀\"\\\n".repeat(4000);
        TaskExecutionOutcome outcome = TaskExecutionOutcome.completed(answer, TaskTerminationReason.ANSWERED,
                1, 0, new TaskTokenUsage(10, 5, TokenUsageQuality.EXACT), json.createArrayNode());
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION v40_reject_second_chunk() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.event_type = 'ANSWER_CHUNK' AND (NEW.payload->>'chunkIndex')::int = 1 THEN
                RAISE EXCEPTION 'injected chunk failure'; END IF; RETURN NEW; END $$
                """);
        jdbc.execute("CREATE TRIGGER v40_chunk_failure BEFORE INSERT ON agent_task_event FOR EACH ROW EXECUTE FUNCTION v40_reject_second_chunk()");
        try {
            assertThatThrownBy(() -> lifecycle.complete(task.getId(), outcome)).isInstanceOf(RuntimeException.class);
            assertThat(query.findById(task.getId()).getStatus()).isEqualTo("RUNNING");
            assertThat(query.findById(task.getId()).getFinalAnswer()).isNull();
            assertThat(query.findById(task.getId()).getLastEventSequence()).isEqualTo(cursor);
            assertThat(events(task)).doesNotContain("ANSWER_CHUNK", "TASK_COMPLETED");
        } finally {
            jdbc.execute("DROP TRIGGER v40_chunk_failure ON agent_task_event");
            jdbc.execute("DROP FUNCTION v40_reject_second_chunk()");
        }
        assertThat(lifecycle.complete(task.getId(), outcome)).isTrue();
        List<String> chunks = jdbc.queryForList("SELECT payload::text FROM agent_task_event WHERE task_id=? AND event_type='ANSWER_CHUNK' ORDER BY sequence_no", String.class, task.getId());
        assertThat(chunks.size()).isGreaterThan(1);
        StringBuilder reconstructed = new StringBuilder();
        for (int i=0; i<chunks.size(); i++) {
            JsonNode payload = json.readTree(chunks.get(i));
            assertThat(json.writeValueAsBytes(payload).length).isLessThanOrEqualTo(16384);
            assertThat(payload.path("chunkIndex").asInt()).isEqualTo(i);
            reconstructed.append(payload.path("text").asText());
        }
        assertThat(reconstructed.toString()).isEqualTo(answer);
        assertThat(query.findById(task.getId()).getFinalAnswer()).isEqualTo(answer);
        assertThat(events(task).getLast()).isEqualTo("TASK_COMPLETED");
        assertContinuous(task);
    }

    AgentTask create(String key) { return tasks.createTask(new CreateAgentTaskCommand(OWNER, AGENT, key, "Why did order_1024 payment fail?")); }
    String tool(String code) { return "{\"type\":\"CALL_TOOL\",\"toolCode\":\"" + code + "\",\"arguments\":{\"orderNo\":\"order_1024\"},\"reason\":\"Check facts\"}"; }
    void script(String... responses) {
        AtomicInteger cursor = new AtomicInteger();
        when(llm.chat(any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.add(i.getArgument(0));
            int index = cursor.getAndIncrement();
            if (index >= responses.length) throw new AssertionError("Unexpected extra model call " + index);
            return result(responses[index], LlmTokenUsage.known(10, 5, 15));
        });
    }
    LlmChatResult result(String content, LlmTokenUsage usage) { return new LlmChatResult(content, "frozen-model", "stop", usage, "request-id", 7); }
    int count(String table, AgentTask task) { return count(table, "task_id=?", task); }
    int count(String table, String predicate, AgentTask task) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + predicate, Integer.class, task.getId()); }
    List<String> events(AgentTask task) { return jdbc.queryForList("SELECT event_type FROM agent_task_event WHERE task_id=? ORDER BY sequence_no", String.class, task.getId()); }
    void assertContinuous(AgentTask task) {
        List<Long> sequences = jdbc.queryForList("SELECT sequence_no FROM agent_task_event WHERE task_id=? ORDER BY sequence_no", Long.class, task.getId());
        for (int i=0; i<sequences.size(); i++) assertThat(sequences.get(i)).isEqualTo(i+1L);
        assertThat(query.findById(task.getId()).getLastEventSequence()).isEqualTo(sequences.size());
    }
}
