package com.agentflow.agent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                "agent_step", "agent_task_event", "agent_task", "agent_tool_binding", "agent_knowledge_binding",
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
        assertThat(get(ownerToken, "/tasks/" + taskId + "/events").getStatusCode().value()).isEqualTo(404);
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
    void shouldCancelQueuedImmediatelyAndRunningCooperativelyAndMakeTerminalCancelIdempotent() throws Exception {
        CountDownLatch modelEntered = blockFirstModelThenFinish();
        String running = data(create(ownerToken, AGENT, "cancel-running", INPUT), 201).path("taskId").asText();
        assertThat(modelEntered.await(10, TimeUnit.SECONDS)).isTrue();
        String queued = data(create(ownerToken, AGENT, "cancel-queued", INPUT), 201).path("taskId").asText();
        assertThat(data(get(ownerToken, "/tasks/" + queued), 200).path("status").asText()).isEqualTo("QUEUED");
        JsonNode cancelledQueued = data(cancel(ownerToken, queued), 200);
        assertThat(cancelledQueued.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelledQueued.path("cancelRequestedAt").isTextual()).isTrue();
        JsonNode cancelling = data(cancel(ownerToken, running), 200);
        assertThat(cancelling.path("status").asText()).isEqualTo("RUNNING");
        assertThat(cancelling.path("cancelRequestedAt").isTextual()).isTrue();
        assertThat(cancelling.path("totalTokens").asInt()).isZero();
        JsonNode during = data(get(ownerToken, "/tasks/" + running + "/trace"), 200);
        assertThat(during.path("task").path("status").asText()).isEqualTo("RUNNING");
        assertThat(during.path("task").path("lastEventSequence").asLong()).isEqualTo(during.path("events").size());
        releaseModel.countDown();
        JsonNode cancelledRunning = terminal(running, "CANCELLED");
        assertThat(cancelledRunning.path("totalTokens").asInt()).isEqualTo(15);
        for (String id : List.of(queued, running)) {
            JsonNode before = data(get(ownerToken, "/tasks/" + id), 200);
            JsonNode again = data(cancel(ownerToken, id), 200);
            assertThat(again).isEqualTo(before);
            assertTraceEvents(data(get(ownerToken, "/tasks/" + id + "/trace"), 200), id, "TASK_CANCELLED");
        }
        assertThat(jdbc.queryForList("SELECT event_type FROM agent_task_event WHERE task_id=?", String.class, Long.valueOf(queued)))
                .containsExactly("TASK_CREATED", "TASK_CANCELLED");
        verify(llm, times(1)).chat(any());
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
