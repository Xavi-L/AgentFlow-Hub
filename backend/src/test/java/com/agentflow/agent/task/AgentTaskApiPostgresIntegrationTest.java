package com.agentflow.agent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.agent.engine.TaskTokenEstimator;
import com.agentflow.agent.snapshot.AgentTaskSnapshotResolver;
import com.agentflow.infra.llm.LlmChatRequest;
import com.agentflow.infra.llm.LlmChatResult;
import com.agentflow.infra.llm.LlmGateway;
import com.agentflow.infra.llm.LlmTokenUsage;
import com.agentflow.knowledge.vector.ChunkVectorIdentityFactory;
import com.agentflow.knowledge.vector.EmbeddingGateway;
import com.agentflow.knowledge.vector.EmbeddingVector;
import com.agentflow.knowledge.vector.VectorSearchHit;
import com.agentflow.knowledge.vector.VectorSearchRequest;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.user.model.AppUser;
import com.agentflow.user.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.sql.SQLException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real JWT HTTP -> dispatcher/Runner/Engine/ToolRuntime -> PostgreSQL acceptance.
 * Only model and vector provider edges are controlled; no live provider/Qdrant claim.
 * The opt-in URL MUST designate a disposable test database, as with V38-V40 tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "agentflow.postgres.integration", matches = "true")
class AgentTaskApiPostgresIntegrationTest {
    private static final long OWNER = 4101, OTHER = 4102, AGENT = 4103, OTHER_AGENT = 4104;
    private static final long KB = 4105, DOCUMENT = 4106, CHUNK = 4107;
    private static final long ORDER_TOOL = 270000000000000001L;
    private static final String VECTOR = "41000000-0000-0000-0000-000000000007";
    private static final String INPUT = "Why did order_1024 payment fail?";
    private static final String CONTENT = "Payment timeout requires checking the order and payment logs.";
    private static final String FINISH = "{\"type\":\"FINISH\",\"answerPlan\":\"Explain recorded facts\"}";
    private static final String TOOL = "{\"type\":\"CALL_TOOL\",\"toolCode\":\"order_query\","
            + "\"arguments\":{\"orderNo\":\"order_1024\"},\"reason\":\"Check facts\"}";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private AgentTaskSnapshotResolver snapshotResolver;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("agentTaskExecutor") private ThreadPoolTaskExecutor executor;
    @MockBean private LlmGateway llm;
    @MockBean private EmbeddingGateway embeddings;
    @MockBean private VectorStoreGateway vectors;

    private String ownerToken;
    private String otherToken;
    private CountDownLatch releaseModel;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> requiredProperty("agentflow.postgres.url"));
        r.add("spring.datasource.username", () -> requiredProperty("agentflow.postgres.user"));
        r.add("spring.datasource.password", () -> System.getProperty("agentflow.postgres.password", ""));
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        r.add("spring.datasource.hikari.minimum-idle", () -> "1");
        r.add("mybatis-plus.configuration.log-impl", () -> "org.apache.ibatis.logging.nologging.NoLoggingImpl");
        r.add("logging.level.com.agentflow", () -> "WARN");
        r.add("agentflow.task.execution.mode", () -> "engine");
        r.add("agentflow.task.dispatcher.core-pool-size", () -> "1");
        r.add("agentflow.task.dispatcher.max-pool-size", () -> "1");
        r.add("agentflow.task.dispatcher.queue-capacity", () -> "20");
        r.add("agentflow.security.jwt.secret-base64", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @BeforeEach
    void setup() {
        for (String table : List.of("tool_call_log", "rag_retrieval_hit", "rag_retrieval_log", "llm_call_log",
                "agent_step", "agent_task_event", "agent_task", "agent_config_version", "agent_tool_binding", "agent_knowledge_binding",
                "knowledge_document_reprocess_task", "knowledge_document_deletion_task", "knowledge_chunk",
                "knowledge_document", "knowledge_base", "agent_app", "app_user")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE tool_definition SET status='ACTIVE',deleted_at=NULL WHERE id=?", ORDER_TOOL);
        jdbc.update("""
                INSERT INTO app_user(id,username,email,password_hash,display_name) VALUES
                  (?,'v41-owner','v41-owner@example.test','hash','V41 Owner'),
                  (?,'v41-other','v41-other@example.test','hash','V41 Other')
                """, OWNER, OTHER);
        jdbc.update("""
                INSERT INTO agent_app(id,user_id,name,system_prompt,model_provider,model_name,
                  max_steps,max_tool_calls,max_tokens,timeout_seconds) VALUES
                  (?,?,'V41 Owner','Explain recorded facts','openai-compatible','controlled-model',6,4,50000,120),
                  (?,?,'V41 Other','Explain recorded facts','openai-compatible','controlled-model',6,4,50000,120)
                """, AGENT, OWNER, OTHER_AGENT, OTHER);
        jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,'Payment knowledge')", KB, OWNER);
        jdbc.update("""
                INSERT INTO knowledge_document(id,user_id,knowledge_base_id,file_name,file_type,mime_type,file_size,
                  storage_bucket,storage_object_key,parse_status,vector_generation)
                VALUES (?,?,?,'payment.txt','TXT','text/plain',100,'test','v41','COMPLETED',1)
                """, DOCUMENT, OWNER, KB);
        jdbc.update("""
                INSERT INTO knowledge_chunk(id,user_id,knowledge_base_id,document_id,chunk_index,content,char_count,
                  token_count,vectorization_status,content_hash,vector_id,vector_generation,chunk_strategy_version)
                VALUES (?,?,?,?,0,?,?,15,'COMPLETED',?,?,1,'structured-token-v1')
                """, CHUNK, OWNER, KB, DOCUMENT, CONTENT, CONTENT.length(),
                ChunkVectorIdentityFactory.contentHash(CONTENT), VECTOR);
        jdbc.update("INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id) VALUES (4110,?,?,?)",
                OWNER, AGENT, KB);
        jdbc.update("INSERT INTO agent_tool_binding(id,user_id,agent_id,tool_id) VALUES (4111,?,?,?)",
                OWNER, AGENT, ORDER_TOOL);
        reset(llm, embeddings, vectors);
        when(embeddings.embed(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new EmbeddingVector(Collections.nCopies(1024, 0.1f));
        });
        when(vectors.search(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            VectorSearchRequest request = invocation.getArgument(0);
            assertThat(request.userId()).isEqualTo(OWNER);
            assertThat(request.documents()).containsExactly(new VectorSearchRequest.DocumentGeneration(DOCUMENT, 1));
            return List.of(new VectorSearchHit(VECTOR, CHUNK, 0.9, ChunkVectorIdentityFactory.contentHash(CONTENT)));
        });
        ownerToken = token(OWNER);
        otherToken = token(OTHER);
        releaseModel = null;
    }

    @AfterEach
    void finishSubmittedWorkBeforeNextDatabaseReset() {
        if (releaseModel != null) releaseModel.countDown();
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(executor.getActiveCount()).isZero();
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        });
    }

    @Test
    void shouldCreateExecuteAndExposeConsistentHistoricalTraceWithSafeStringIds() {
        script(TOOL, FINISH, "Payment timed out [S1].");
        String taskId = data(create(ownerToken, AGENT, "http-main", INPUT), 201).path("taskId").asText();
        JsonNode done = terminal(taskId, "COMPLETED");
        assertThat(done.path("finalAnswer").asText()).isEqualTo("Payment timed out [S1].");
        assertThat(done.path("decisionTurnsUsed").asInt()).isEqualTo(2);
        assertThat(done.path("toolCallsUsed").asInt()).isEqualTo(1);
        assertThat(done.path("totalTokens").asInt()).isEqualTo(45);
        assertThat(done.path("tokenUsageQuality").asText()).isEqualTo("EXACT");
        assertThat(done.path("citations")).hasSize(1);
        assertThat(done.path("taskId").isTextual()).isTrue();
        assertThat(done.path("agentId").isTextual()).isTrue();

        JsonNode trace = data(get(ownerToken, "/tasks/" + taskId + "/trace"), 200);
        assertThat(trace.path("task")).isEqualTo(done);
        assertThat(trace.path("steps")).hasSize(5);
        assertThat(trace.findValues("llmCalls").stream().mapToInt(JsonNode::size).sum()).isEqualTo(3);
        assertThat(trace.findValues("ragRetrievals").stream().mapToInt(JsonNode::size).sum()).isEqualTo(1);
        assertThat(trace.findValues("toolCalls").stream().mapToInt(JsonNode::size).sum()).isEqualTo(1);
        assertThat(trace.findValues("hits").stream().mapToInt(JsonNode::size).sum()).isEqualTo(1);
        assertTraceEvents(trace, taskId, "TASK_COMPLETED");
        assertBusinessIdsAreStrings(trace);
        assertThat(trace.toString()).doesNotContain("requestFingerprint", "clientRequestId", "passwordHash", "subscriptionUrl");

        // Synthetic old/raw persisted fields establish read-time redaction independently of the recorder.
        jdbc.update("UPDATE agent_task SET execution_snapshot=execution_snapshot || ?::jsonb WHERE id=?",
                "{\"apiKey\":\"legacy-snapshot-secret\",\"endpoint\":\"https://private.example.test\"}", Long.valueOf(taskId));
        jdbc.update("UPDATE agent_step SET summary=summary || ?::jsonb WHERE task_id=?",
                "{\"nested\":{\"Authorization\":\"Bearer legacy-step-secret\",\"reasoning\":\"private-model-thought\"}}",
                Long.valueOf(taskId));
        jdbc.update("UPDATE agent_task_event SET payload=payload || ?::jsonb WHERE task_id=? AND sequence_no=1",
                "{\"secret\":\"legacy-event-secret\"}", Long.valueOf(taskId));
        jdbc.update("UPDATE agent_app SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", AGENT);
        jdbc.update("UPDATE knowledge_document SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", DOCUMENT);
        jdbc.update("UPDATE tool_definition SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", ORDER_TOOL);

        JsonNode historical = data(get(ownerToken, "/tasks/" + taskId + "/trace"), 200);
        assertThat(historical.path("task").path("finalAnswer").asText()).isEqualTo("Payment timed out [S1].");
        assertThat(historical.path("task").path("citations")).isEqualTo(done.path("citations"));
        assertThat(historical.path("steps")).hasSize(5);
        assertThat(historical.toString()).doesNotContain("legacy-snapshot-secret", "private.example.test",
                "legacy-step-secret", "private-model-thought", "legacy-event-secret");
        assertBusinessIdsAreStrings(historical);
        assertTraceEvents(historical, taskId, "TASK_COMPLETED");
        assertThat(data(get(ownerToken, "/tasks/" + taskId), 200).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(data(get(ownerToken, "/tasks"), 200).path("items").get(0).path("taskId").asText()).isEqualTo(taskId);
        assertThat(data(create(ownerToken, AGENT, "http-main", INPUT), 200).path("taskId").asText()).isEqualTo(taskId);
        assertThat(data(cancel(ownerToken, taskId), 200).path("status").asText()).isEqualTo("COMPLETED");
        verify(llm, times(3)).chat(any());
    }

    @Test
    void shouldReturnExactlyOne201ForConcurrentIdenticalHttpCreatesAndNeverRerun() throws Exception {
        CountDownLatch modelEntered = blockFirstModelThenFinish();
        int callers = 6;
        CyclicBarrier start = new CyclicBarrier(callers);
        List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(callers)) {
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) futures.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return create(ownerToken, AGENT, "http-concurrent", INPUT);
            }));
            for (Future<ResponseEntity<JsonNode>> future : futures) responses.add(future.get(15, TimeUnit.SECONDS));
        }
        assertThat(responses.stream().map(response -> response.getStatusCode().value()).toList())
                .containsExactlyInAnyOrder(201, 200, 200, 200, 200, 200);
        List<String> ids = responses.stream().map(response -> response.getBody().path("data").path("taskId").asText()).toList();
        assertThat(ids).doesNotContain("").containsOnly(ids.getFirst());
        String taskId = ids.getFirst();
        assertThat(modelEntered.await(10, TimeUnit.SECONDS)).isTrue();
        assertError(create(ownerToken, AGENT, "http-concurrent", "Different input"), 409, "TASK_IDEMPOTENCY_CONFLICT");
        assertThat(data(create(ownerToken, AGENT, "http-concurrent", INPUT), 200).path("taskId").asText()).isEqualTo(taskId);
        releaseModel.countDown();
        terminal(taskId, "COMPLETED");
        assertThat(data(create(ownerToken, AGENT, "http-concurrent", INPUT), 200).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_task", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_task_event WHERE event_type='TASK_CREATED'", Integer.class)).isEqualTo(1);
        verify(llm, times(2)).chat(any());
        verify(embeddings, times(1)).embed(any());
    }

    @Test
    void shouldKeepSnapshotAgentLockCompatibleWithForeignKeysWhileExcludingWriters() {
        TransactionTemplate snapshotTransaction = new TransactionTemplate(transactionManager);
        snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var independentConnection = Executors.newSingleThreadExecutor()) {
            snapshotTransaction.executeWithoutResult(status -> {
                assertThat(snapshotResolver.resolve(OWNER, AGENT).agent().agentId()).isEqualTo(Long.toString(AGENT));

                // Runner task updates acquire this FK lock. It must remain compatible while
                // the creation transaction still holds the resolver's Agent snapshot lock.
                Future<Long> foreignKeyLock = independentConnection.submit(() ->
                        new TransactionTemplate(transactionManager).execute(transaction -> jdbc.queryForObject(
                                "SELECT id FROM agent_app WHERE id=? AND user_id=? FOR KEY SHARE NOWAIT",
                                Long.class, AGENT, OWNER)));
                Long lockedAgent = assertDoesNotThrow(() -> foreignKeyLock.get(5, TimeUnit.SECONDS));
                assertThat(lockedAgent).isEqualTo(AGENT);

                // Existing configuration and binding writers still require FOR UPDATE.
                // NOWAIT makes a missing exclusion fail deterministically without sleeps.
                Future<?> writerLock = independentConnection.submit(() ->
                        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(transaction ->
                                jdbc.queryForObject(
                                        "SELECT id FROM agent_app WHERE id=? AND user_id=? FOR UPDATE NOWAIT",
                                        Long.class, AGENT, OWNER)))
                                .rootCause().isInstanceOfSatisfying(SQLException.class,
                                        error -> assertThat(error.getSQLState()).isEqualTo("55P03")));
                assertDoesNotThrow(() -> writerLock.get(5, TimeUnit.SECONDS));
            });
        }
    }

    @Test
    void shouldUseJwtOwnershipAndHideForeignAndMissingResourcesWithTheSame404() {
        script(FINISH, "Recorded facts.");
        String taskId = data(create(ownerToken, AGENT, "http-isolation", INPUT), 201).path("taskId").asText();
        terminal(taskId, "COMPLETED");
        for (String hiddenId : List.of(taskId, "999999999999999999")) {
            assertError(get(otherToken, "/tasks/" + hiddenId), 404, "COMMON_NOT_FOUND");
            assertError(get(otherToken, "/tasks/" + hiddenId + "/trace"), 404, "COMMON_NOT_FOUND");
            assertError(cancel(otherToken, hiddenId), 404, "COMMON_NOT_FOUND");
        }
        assertError(create(otherToken, AGENT, "foreign-agent", INPUT), 404, "COMMON_NOT_FOUND");
        assertError(create(ownerToken, OTHER_AGENT, "other-agent", INPUT), 404, "COMMON_NOT_FOUND");
        assertError(create(ownerToken, 999999999999999999L, "missing-agent", INPUT), 404, "COMMON_NOT_FOUND");
        JsonNode otherPage = data(get(otherToken, "/tasks"), 200);
        assertThat(otherPage.path("total").asLong()).isZero();
        assertThat(otherPage.path("items")).isEmpty();
        assertThat(get(null, "/tasks").getStatusCode().value()).isEqualTo(401);
        assertThat(create(ownerToken, AGENT, null, INPUT).getStatusCode().value()).isEqualTo(400);
        assertThat(create(ownerToken, AGENT, " ", INPUT).getStatusCode().value()).isEqualTo(400);
        assertThat(create(ownerToken, AGENT, "x".repeat(129), INPUT).getStatusCode().value()).isEqualTo(400);
        assertThat(create(ownerToken, AGENT, "empty-input", " ").getStatusCode().value()).isEqualTo(400);
        assertThat(create(ownerToken, AGENT, "nul-input", "invalid\u0000input").getStatusCode().value()).isEqualTo(400);
        // Recoverable SSE is exercised with a real streaming client in the V42 acceptance class.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_task", Integer.class)).isEqualTo(1);
    }

    @Test
    void shouldPageOnlyOwnTasksWithDefault20Maximum100AndCreationThenIdDescending() {
        // Bulk persisted fixtures target SQL ordering; they deliberately do not dispatch 106 executions.
        insertPageFixtures(OWNER, AGENT, 105, 410000);
        insertPageFixtures(OTHER, OTHER_AGENT, 1, 420000);
        List<String> expected = jdbc.queryForList(
                "SELECT id::text FROM agent_task WHERE user_id=? ORDER BY created_at DESC,id DESC", String.class, OWNER);
        JsonNode first = data(get(ownerToken, "/tasks"), 200);
        assertThat(first.path("page").asInt()).isEqualTo(1);
        assertThat(first.path("pageSize").asInt()).isEqualTo(20);
        assertThat(first.path("total").asLong()).isEqualTo(105);
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(taskIds(first)).isEqualTo(expected.subList(0, 20));
        JsonNode second = data(get(ownerToken, "/tasks?page=2"), 200);
        assertThat(taskIds(second)).isEqualTo(expected.subList(20, 40));
        assertThat(taskIds(data(get(ownerToken, "/tasks?page=2"), 200))).isEqualTo(taskIds(second));
        JsonNode maximum = data(get(ownerToken, "/tasks?pageSize=1000"), 200);
        assertThat(maximum.path("pageSize").asInt()).isEqualTo(100);
        assertThat(taskIds(maximum)).isEqualTo(expected.subList(0, 100));
        JsonNode tail = data(get(ownerToken, "/tasks?page=2&pageSize=100"), 200);
        assertThat(taskIds(tail)).isEqualTo(expected.subList(100, 105));
        assertThat(tail.path("hasNext").asBoolean()).isFalse();
        JsonNode beyondEnd = data(get(ownerToken, "/tasks?page=2147483647&pageSize=100"), 200);
        assertThat(beyondEnd.path("page").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(beyondEnd.path("pageSize").asInt()).isEqualTo(100);
        assertThat(beyondEnd.path("items")).isEmpty();
        assertThat(beyondEnd.path("total").asLong()).isEqualTo(105);
        assertThat(beyondEnd.path("hasNext").asBoolean()).isFalse();
        assertBusinessIdsAreStrings(first);
        assertThat(data(get(otherToken, "/tasks"), 200).path("total").asLong()).isEqualTo(1);
    }

    @Test
    void shouldExecuteTwentyBindingsRejectLegacyTwentyOneWithoutSideEffectsAndAllowExplicitRepair() {
        List<Long> twenty = new ArrayList<>();
        twenty.add(KB);
        for (long id = 5200; id < 5219; id++) {
            jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,?)", id, OWNER, "Additional KB " + id);
            twenty.add(id);
        }
        JsonNode savedTwenty = data(putKnowledgeBindings(twenty), 200).path("knowledgeBaseIds");
        assertThat(savedTwenty).hasSize(20);
        assertThat(StreamSupport.stream(savedTwenty.spliterator(), false).map(JsonNode::asText).toList())
                .containsExactlyElementsOf(twenty.stream().map(String::valueOf).toList());
        assertThat(data(get(ownerToken, "/agents/" + AGENT + "/knowledge-bases"), 200).path("knowledgeBaseIds"))
                .isEqualTo(savedTwenty);

        // Only the original KB has one READY document; all twenty bindings must still fit the snapshot.
        script(TOOL, FINISH, "At the binding limit [S1].", TOOL, FINISH, "After explicit repair [S1].");
        String originalId = data(create(ownerToken, AGENT, "v49-before-legacy", INPUT), 201).path("taskId").asText();
        JsonNode originalTask = terminal(originalId, "COMPLETED");
        JsonNode originalTrace = data(get(ownerToken, "/tasks/" + originalId + "/trace"), 200);
        assertThat(originalTrace.path("executionSnapshot").path("retrieval").path("knowledgeBases")).hasSize(20);
        assertThat(originalTask.path("finalAnswer").asText()).isEqualTo("At the binding limit [S1].");
        await().atMost(WAIT).untilAsserted(() -> assertThat(executor.getActiveCount()).isZero());
        verify(llm, times(3)).chat(any());
        verify(embeddings, times(1)).embed(any());
        verify(vectors, times(1)).search(any());
        clearInvocations(llm, embeddings, vectors);
        Map<String, Object> rowsBeforeRejection = executionRowCounts();

        long legacyKb = 5219L;
        jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,'Legacy twenty-first KB')", legacyKb, OWNER);
        jdbc.update("""
                INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id,priority)
                VALUES (5299,?,?,?,20)
                """, OWNER, AGENT, legacyKb);
        List<Long> twentyOne = new ArrayList<>(twenty);
        twentyOne.add(legacyKb);
        JsonNode legacyIds = data(get(ownerToken, "/agents/" + AGENT + "/knowledge-bases"), 200).path("knowledgeBaseIds");
        assertThat(legacyIds).hasSize(21);
        assertThat(legacyIds.path(20).asText()).isEqualTo(Long.toString(legacyKb));
        assertError(putKnowledgeBindings(twentyOne), 400, "COMMON_PARAM_INVALID");
        assertThat(data(get(ownerToken, "/agents/" + AGENT + "/knowledge-bases"), 200).path("knowledgeBaseIds"))
                .isEqualTo(legacyIds);
        assertError(create(ownerToken, AGENT, "v49-reject-active", INPUT), 409, "AGENT_BINDING_INVALID");

        // Inactive/deleted residual bindings count too; filtering them before the limit would reopen the hole.
        jdbc.update("UPDATE knowledge_base SET status='DISABLED' WHERE id=?", legacyKb);
        assertError(create(ownerToken, AGENT, "v49-reject-disabled", INPUT), 409, "AGENT_BINDING_INVALID");
        jdbc.update("UPDATE knowledge_base SET status='ACTIVE',deleted_at=CURRENT_TIMESTAMP WHERE id=?", legacyKb);
        assertError(create(ownerToken, AGENT, "v49-reject-deleted", INPUT), 409, "AGENT_BINDING_INVALID");
        assertThat(data(get(ownerToken, "/agents/" + AGENT + "/knowledge-bases"), 200).path("knowledgeBaseIds"))
                .isEqualTo(legacyIds);

        JsonNode replay = data(create(ownerToken, AGENT, "v49-before-legacy", INPUT), 200);
        assertThat(replay.path("taskId").asText()).isEqualTo(originalId);
        assertThat(data(get(ownerToken, "/tasks/" + originalId), 200)).isEqualTo(originalTask);
        assertThat(data(get(ownerToken, "/tasks/" + originalId + "/trace"), 200)).isEqualTo(originalTrace);
        assertThat(executionRowCounts()).isEqualTo(rowsBeforeRejection);
        assertThat(executor.getActiveCount()).isZero();
        assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        verifyNoInteractions(llm, embeddings, vectors);

        assertThat(data(putKnowledgeBindings(twenty), 200).path("knowledgeBaseIds")).isEqualTo(savedTwenty);
        assertThat(data(get(ownerToken, "/agents/" + AGENT + "/knowledge-bases"), 200).path("knowledgeBaseIds"))
                .isEqualTo(savedTwenty);
        String repairedId = data(create(ownerToken, AGENT, "v49-after-repair", INPUT), 201).path("taskId").asText();
        assertThat(repairedId).isNotEqualTo(originalId);
        JsonNode repairedTask = terminal(repairedId, "COMPLETED");
        assertThat(repairedTask.path("finalAnswer").asText()).isEqualTo("After explicit repair [S1].");
        assertThat(repairedTask.path("toolCallsUsed").asInt()).isEqualTo(1);
        assertThat(data(get(ownerToken, "/tasks/" + repairedId + "/trace"), 200)
                .path("executionSnapshot").path("retrieval").path("knowledgeBases")).hasSize(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_task", Integer.class)).isEqualTo(2);
        verify(llm, times(3)).chat(any());
        verify(embeddings, times(1)).embed(any());
        verify(vectors, times(1)).search(any());
    }

    @Test
    void shouldCancelQueuedImmediatelyAndRunningCooperativelyAndMakeTerminalCancelIdempotent() throws Exception {
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        releaseModel = release;
        AtomicReference<LlmChatRequest> blockedRequest = new AtomicReference<>();
        AtomicReference<Thread> modelWorker = new AtomicReference<>();
        AtomicReference<LlmChatResult> lateResult = new AtomicReference<>();
        when(llm.chat(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(modelWorker.compareAndSet(null, Thread.currentThread())).as("Only one model call may start").isTrue();
            blockedRequest.set(invocation.getArgument(0));
            modelEntered.countDown();
            long expires = System.nanoTime() + WAIT.toNanos();
            while (release.getCount() != 0) {
                long remaining = expires - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("Late model gate timed out");
                try {
                    if (!release.await(remaining, TimeUnit.NANOSECONDS)) throw new IllegalStateException("Late model gate timed out");
                } catch (InterruptedException ignored) {
                    // Model I/O deliberately ignores cancellation until the test releases its late result.
                }
            }
            LlmChatResult response = result(FINISH);
            lateResult.set(response);
            return response;
        });
        try {
            String running = data(create(ownerToken, AGENT, "cancel-running", INPUT), 201).path("taskId").asText();
            assertThat(modelEntered.await(10, TimeUnit.SECONDS)).isTrue();
            String queued = data(create(ownerToken, AGENT, "cancel-queued", INPUT), 201).path("taskId").asText();
            assertThat(data(get(ownerToken, "/tasks/" + queued), 200).path("status").asText()).isEqualTo("QUEUED");
            JsonNode cancelledQueued = data(cancel(ownerToken, queued), 200);
            assertThat(cancelledQueued.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(cancelledQueued.path("cancelRequestedAt").isTextual()).isTrue();
            assertThat(cancelledQueued.path("totalTokens").asInt()).isZero();
            JsonNode during = data(get(ownerToken, "/tasks/" + running + "/trace"), 200);
            assertThat(during.path("task").path("status").asText()).isEqualTo("RUNNING");
            assertThat(during.path("task").path("lastEventSequence").asLong()).isEqualTo(during.path("events").size());
            JsonNode cancelling = data(cancel(ownerToken, running), 200);
            assertThat(cancelling.path("status").asText()).isEqualTo("RUNNING");
            assertThat(cancelling.path("cancelRequestedAt").isTextual()).isTrue();
            assertThat(cancelling.path("totalTokens").asInt()).isZero();

            // Cancellation completes before provider usage is available: charge input estimate + actual output cap.
            JsonNode cancelledRunning = terminal(running, "CANCELLED");
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(modelWorker.get().isAlive()).isTrue();
            assertThat(lateResult.get()).isNull();
            LlmChatRequest request = blockedRequest.get();
            assertThat(request.responseSchema()).isNull();
            int expectedInput = TaskTokenEstimator.inputTokens(request.messages());
            int expectedOutput = request.maxOutputTokens();
            int expectedTotal = Math.addExact(expectedInput, expectedOutput);
            assertThat(cancelledRunning.path("inputTokens").asInt()).isEqualTo(expectedInput);
            assertThat(cancelledRunning.path("outputTokens").asInt()).isEqualTo(expectedOutput);
            assertThat(cancelledRunning.path("totalTokens").asInt()).isEqualTo(expectedTotal);
            assertThat(cancelledRunning.path("tokenUsageQuality").asText()).isEqualTo("ESTIMATED");
            assertThat(cancelledRunning.path("terminationReason").asText()).isEqualTo("USER_CANCELLED");
            assertThat(cancelledRunning.path("decisionTurnsUsed").asInt()).isEqualTo(1);
            assertThat(cancelledRunning.path("toolCallsUsed").asInt()).isZero();
            assertThat(cancelledRunning.hasNonNull("finalAnswer")).isFalse();
            assertThat(cancelledRunning.path("citations")).isEmpty();
            JsonNode cancelledTrace = data(get(ownerToken, "/tasks/" + running + "/trace"), 200);
            assertThat(cancelledTrace.path("task")).isEqualTo(cancelledRunning);
            List<JsonNode> calls = cancelledTrace.findValues("llmCalls").stream()
                    .flatMap(nodes -> StreamSupport.stream(nodes.spliterator(), false)).toList();
            assertThat(calls).hasSize(1);
            JsonNode call = calls.getFirst();
            assertThat(call.path("callType").asText()).isEqualTo("DECISION");
            assertThat(call.path("status").asText()).isEqualTo("FAILED");
            assertThat(call.path("errorCode").asText()).isEqualTo("TASK_CANCELLED");
            assertThat(call.path("inputTokens").asInt()).isEqualTo(expectedInput);
            assertThat(call.path("outputTokens").asInt()).isEqualTo(expectedOutput);
            assertThat(call.path("totalTokens").asInt()).isEqualTo(expectedTotal);
            assertThat(call.path("usageQuality").asText()).isEqualTo("ESTIMATED");
            assertThat(call.path("requestSnapshot").path("maxOutputTokens").asInt()).isEqualTo(expectedOutput);
            assertThat(call.hasNonNull("responseText")).isFalse();
            assertThat(jdbc.queryForMap("""
                    SELECT input_tokens,output_tokens,total_tokens,usage_quality,status,error_code
                    FROM llm_call_log WHERE task_id=?
                    """, Long.valueOf(running)))
                    .containsEntry("input_tokens", expectedInput).containsEntry("output_tokens", expectedOutput)
                    .containsEntry("total_tokens", expectedTotal).containsEntry("usage_quality", "ESTIMATED")
                    .containsEntry("status", "FAILED").containsEntry("error_code", "TASK_CANCELLED");
            assertTraceEvents(cancelledTrace, running, "TASK_CANCELLED");
            assertThat(StreamSupport.stream(cancelledTrace.path("events").spliterator(), false)
                    .map(event -> event.path("eventType").asText()).toList())
                    .containsOnlyOnce("TASK_CANCELLED")
                    .doesNotContain("FINAL_GENERATION_STARTED", "ANSWER_CHUNK", "TASK_COMPLETED");

            // Join the actual external-call worker, not a finally-block signal that precedes its return.
            release.countDown();
            modelWorker.get().join(5_000);
            assertThat(modelWorker.get().isAlive()).isFalse();
            assertThat(lateResult.get()).isNotNull();
            assertThat(lateResult.get().usage().totalTokens()).isEqualTo(15);
            assertThat(data(get(ownerToken, "/tasks/" + running), 200)).isEqualTo(cancelledRunning);
            assertThat(data(get(ownerToken, "/tasks/" + running + "/trace"), 200)).isEqualTo(cancelledTrace);
            for (String id : List.of(queued, running)) {
                JsonNode before = data(get(ownerToken, "/tasks/" + id), 200);
                JsonNode again = data(cancel(ownerToken, id), 200);
                assertThat(again).isEqualTo(before);
                assertTraceEvents(data(get(ownerToken, "/tasks/" + id + "/trace"), 200), id, "TASK_CANCELLED");
            }
            assertThat(jdbc.queryForList("SELECT event_type FROM agent_task_event WHERE task_id=?", String.class, Long.valueOf(queued)))
                    .containsExactly("TASK_CREATED", "TASK_CANCELLED");
            verify(llm, times(1)).chat(any());
        } finally {
            release.countDown();
            Thread worker = modelWorker.get();
            if (worker != null) {
                worker.join(5_000);
                assertThat(worker.isAlive()).as("Controlled provider must exit before database reset").isFalse();
            }
        }
    }

    private void insertPageFixtures(long userId, long agentId, int count, long idBase) {
        jdbc.update("""
                INSERT INTO agent_task(id,user_id,agent_id,client_request_id,request_fingerprint,status,user_input,
                  execution_snapshot,max_decision_turns,max_tool_calls,max_total_tokens,reserved_final_tokens,
                  created_at,updated_at)
                SELECT ? + n,?,?,concat('page-',n),repeat('0',64),'QUEUED','Pagination fixture','{}'::jsonb,
                  6,4,50000,2048,CURRENT_TIMESTAMP - (n % 3) * INTERVAL '1 minute',CURRENT_TIMESTAMP
                FROM generate_series(1,?) n
                """, idBase, userId, agentId, count);
    }

    private CountDownLatch blockFirstModelThenFinish() {
        CountDownLatch entered = new CountDownLatch(1);
        releaseModel = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(llm.chat(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                if (!releaseModel.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Model gate timed out");
                return result(FINISH);
            }
            return result("Recorded facts.");
        });
        return entered;
    }

    private void script(String... responses) {
        AtomicInteger cursor = new AtomicInteger();
        when(llm.chat(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            LlmChatRequest request = invocation.getArgument(0);
            assertThat(request.modelName()).isEqualTo("controlled-model");
            int index = cursor.getAndIncrement();
            if (index >= responses.length) throw new IllegalStateException("Unexpected extra model call " + index);
            return result(responses[index]);
        });
    }

    private static LlmChatResult result(String content) {
        return new LlmChatResult(content, "controlled-model", "stop", LlmTokenUsage.known(10, 5, 15), "controlled-request", 7);
    }

    private String token(long userId) {
        AppUser user = new AppUser();
        user.setId(userId);
        return jwt.issueAccessToken(user).value();
    }

    private ResponseEntity<JsonNode> create(String token, long agentId, String key, String input) {
        HttpHeaders headers = headers(token);
        if (key != null) headers.set("Idempotency-Key", key);
        return http.exchange("/api/v1/agents/" + agentId + "/tasks", HttpMethod.POST,
                new HttpEntity<>(Map.of("userInput", input), headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> putKnowledgeBindings(List<Long> ids) {
        return http.exchange("/api/v1/agents/" + AGENT + "/knowledge-bases", HttpMethod.PUT,
                new HttpEntity<>(Map.of("knowledgeBaseIds", ids.stream().map(String::valueOf).toList()), headers(ownerToken)),
                JsonNode.class);
    }

    private Map<String, Object> executionRowCounts() {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM agent_task) AS tasks,
                       (SELECT count(*) FROM agent_task_event) AS events,
                       (SELECT count(*) FROM agent_step) AS steps,
                       (SELECT count(*) FROM llm_call_log) AS llm_calls,
                       (SELECT count(*) FROM rag_retrieval_log) AS retrievals,
                       (SELECT count(*) FROM tool_call_log) AS tool_calls
                """);
    }

    private ResponseEntity<JsonNode> get(String token, String path) {
        return http.exchange("/api/v1" + path, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> cancel(String token, String taskId) {
        return http.exchange("/api/v1/tasks/" + taskId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(headers(token)), JsonNode.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return headers;
    }

    private JsonNode terminal(String taskId, String status) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertThat(data(get(ownerToken, "/tasks/" + taskId), 200).path("status").asText()).isEqualTo(status));
        return data(get(ownerToken, "/tasks/" + taskId), 200);
    }

    private static JsonNode data(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).as("HTTP response: %s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("OK");
        return response.getBody().path("data");
    }

    private static void assertError(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).as("HTTP response: %s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }

    private void assertTraceEvents(JsonNode trace, String taskId, String terminalType) {
        JsonNode events = trace.path("events");
        assertThat(events).isNotEmpty();
        assertThat(trace.path("task").path("lastEventSequence").asLong()).isEqualTo(events.size());
        List<String> storedTypes = jdbc.queryForList(
                "SELECT event_type FROM agent_task_event WHERE task_id=? ORDER BY sequence_no", String.class, Long.valueOf(taskId));
        List<String> types = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            JsonNode event = events.get(i);
            assertThat(event.path("sequenceNo").asLong()).isEqualTo(i + 1L);
            types.add(event.path("eventType").asText());
        }
        assertThat(types).isEqualTo(storedTypes);
        assertThat(types.getFirst()).isEqualTo("TASK_CREATED");
        assertThat(types.getLast()).isEqualTo(terminalType);
        String answer = StreamSupport.stream(events.spliterator(), false)
                .filter(event -> "ANSWER_CHUNK".equals(event.path("eventType").asText()))
                .map(event -> event.path("payload").path("text").asText()).reduce("", String::concat);
        if ("TASK_COMPLETED".equals(terminalType)) {
            assertThat(answer).isEqualTo(trace.path("task").path("finalAnswer").asText());
        } else {
            assertThat(answer).isEmpty();
        }
    }

    private static void assertBusinessIdsAreStrings(JsonNode node) {
        if (node.isObject()) node.properties().forEach(field -> {
            String name = field.getKey();
            JsonNode value = field.getValue();
            if ((name.equals("id") || name.endsWith("Id") || name.endsWith("IdSnapshot")) && !value.isNull()) {
                assertThat(value.isTextual()).as("Business ID %s: %s", name, value).isTrue();
            }
            assertBusinessIdsAreStrings(value);
        });
        else if (node.isArray()) node.forEach(AgentTaskApiPostgresIntegrationTest::assertBusinessIdsAreStrings);
    }

    private static List<String> taskIds(JsonNode page) {
        return StreamSupport.stream(page.path("items").spliterator(), false)
                .map(item -> item.path("taskId").asText()).toList();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must identify the disposable test database");
        return value;
    }
}
