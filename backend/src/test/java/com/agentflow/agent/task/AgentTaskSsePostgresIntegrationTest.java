package com.agentflow.agent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.agent.task.sse.TaskSseService;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * V42 opt-in acceptance with an actual HTTP SSE client, servlet container, JWT, Runner,
 * Engine, ToolRuntime and PostgreSQL. Only model/vector provider boundaries are controlled.
 * The configured PostgreSQL URL MUST designate a disposable database; setup deletes fixtures.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "agentflow.postgres.integration", matches = "true")
class AgentTaskSsePostgresIntegrationTest {
    private static final long OWNER = 4201, OTHER = 4202, AGENT = 4203, OTHER_AGENT = 4204;
    private static final long KB = 4205, DOCUMENT = 4206, CHUNK = 4207;
    private static final long OTHER_KB = 4215, OTHER_DOCUMENT = 4216, OTHER_CHUNK = 4217;
    private static final long ORDER_TOOL = 270000000000000001L;
    private static final String VECTOR = "42000000-0000-0000-0000-000000000007";
    private static final String INPUT = "Why did order_1024 payment fail?";
    private static final String CONTENT = "Payment timeout requires checking the order and payment logs.";
    private static final String FINISH = "{\"type\":\"FINISH\",\"answerPlan\":\"Explain recorded facts\"}";
    private static final String TOOL = "{\"type\":\"CALL_TOOL\",\"toolCode\":\"order_query\","
            + "\"arguments\":{\"orderNo\":\"order_1024\"},\"reason\":\"Check facts\"}";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private ObjectMapper json;
    @Autowired private TaskSseService sse;
    @Autowired @Qualifier("agentTaskExecutor") private ThreadPoolTaskExecutor executor;
    @MockBean private LlmGateway llm;
    @MockBean private EmbeddingGateway embeddings;
    @MockBean private VectorStoreGateway vectors;

    private final List<SseConnection> opened = new ArrayList<>();
    private HttpClient streamHttp;
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
        r.add("agentflow.task.sse.max-connections", () -> "3");
        r.add("agentflow.task.sse.max-connections-per-user", () -> "2");
        r.add("agentflow.task.sse.batch-size", () -> "2");
        r.add("agentflow.task.sse.max-pending-bytes", () -> "65536");
        r.add("agentflow.task.sse.poll-interval-ms", () -> "25");
        r.add("agentflow.task.sse.heartbeat-interval-ms", () -> "100");
        r.add("agentflow.task.sse.connection-timeout-ms", () -> "5000");
        r.add("agentflow.task.sse.send-timeout-ms", () -> "1000");
    }

    @BeforeEach
    void setup() {
        assertThat(sse.activeConnectionCount()).isZero();
        for (String table : List.of("tool_call_log", "rag_retrieval_hit", "rag_retrieval_log", "llm_call_log",
                "agent_step", "agent_task_event", "agent_task", "agent_tool_binding", "agent_knowledge_binding",
                "knowledge_document_reprocess_task", "knowledge_document_deletion_task", "knowledge_chunk",
                "knowledge_document", "knowledge_base", "agent_app", "app_user")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE tool_definition SET status='ACTIVE',deleted_at=NULL WHERE id=?", ORDER_TOOL);
        jdbc.update("""
                INSERT INTO app_user(id,username,email,password_hash,display_name) VALUES
                  (?,'v42-owner','v42-owner@example.test','hash','V42 Owner'),
                  (?,'v42-other','v42-other@example.test','hash','V42 Other')
                """, OWNER, OTHER);
        jdbc.update("""
                INSERT INTO agent_app(id,user_id,name,system_prompt,model_provider,model_name,
                  max_steps,max_tool_calls,max_tokens,timeout_seconds) VALUES
                  (?,?,'V42 Owner','Explain recorded facts','openai-compatible','controlled-model',6,4,50000,120),
                  (?,?,'V42 Other','Explain recorded facts','openai-compatible','controlled-model',6,4,50000,120)
                """, AGENT, OWNER, OTHER_AGENT, OTHER);
        jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,'Payment knowledge')", KB, OWNER);
        jdbc.update("""
                INSERT INTO knowledge_document(id,user_id,knowledge_base_id,file_name,file_type,mime_type,file_size,
                  storage_bucket,storage_object_key,parse_status,vector_generation)
                VALUES (?,?,?,'payment.txt','TXT','text/plain',100,'test','v42','COMPLETED',1)
                """, DOCUMENT, OWNER, KB);
        jdbc.update("""
                INSERT INTO knowledge_chunk(id,user_id,knowledge_base_id,document_id,chunk_index,content,char_count,
                  token_count,vectorization_status,content_hash,vector_id,vector_generation,chunk_strategy_version)
                VALUES (?,?,?,?,0,?,?,15,'COMPLETED',?,?,1,'structured-token-v1')
                """, CHUNK, OWNER, KB, DOCUMENT, CONTENT, CONTENT.length(),
                ChunkVectorIdentityFactory.contentHash(CONTENT), VECTOR);
        jdbc.update("INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id) VALUES (4210,?,?,?)",
                OWNER, AGENT, KB);
        jdbc.update("INSERT INTO agent_tool_binding(id,user_id,agent_id,tool_id) VALUES (4211,?,?,?)",
                OWNER, AGENT, ORDER_TOOL);
        // A second owner needs an independently valid creation snapshot for the global quota test.
        // Its execution stays queued behind the blocked owner task, then is cancelled through HTTP.
        jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,'Other payment knowledge')", OTHER_KB, OTHER);
        jdbc.update("""
                INSERT INTO knowledge_document(id,user_id,knowledge_base_id,file_name,file_type,mime_type,file_size,
                  storage_bucket,storage_object_key,parse_status,vector_generation)
                VALUES (?,?,?,'payment.txt','TXT','text/plain',100,'test','v42-other','COMPLETED',1)
                """, OTHER_DOCUMENT, OTHER, OTHER_KB);
        jdbc.update("""
                INSERT INTO knowledge_chunk(id,user_id,knowledge_base_id,document_id,chunk_index,content,char_count,
                  token_count,vectorization_status,content_hash,vector_id,vector_generation,chunk_strategy_version)
                VALUES (?,?,?,?,0,?,?,15,'COMPLETED',?,'42000000-0000-0000-0000-000000000017',1,'structured-token-v1')
                """, OTHER_CHUNK, OTHER, OTHER_KB, OTHER_DOCUMENT, CONTENT, CONTENT.length(),
                ChunkVectorIdentityFactory.contentHash(CONTENT));
        jdbc.update("INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id) VALUES (4220,?,?,?)",
                OTHER, OTHER_AGENT, OTHER_KB);
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
        opened.clear();
        streamHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void releaseConnectionsAndFinishSubmittedWork() throws Exception {
        if (releaseModel != null) releaseModel.countDown();
        for (SseConnection connection : opened) connection.close();
        streamHttp.shutdownNow();
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(sse.activeConnectionCount()).isZero();
            assertThat(executor.getActiveCount()).isZero();
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        });
    }

    @Test
    void shouldReplayDelayedCreationResumeFromProcessedCursorAndMatchAnswerAndTraceAcrossBatches() throws Exception {
        // Raw credential-looking answer text and multi-byte characters must survive answer projection.
        String answer = "Payment timed out [S1].\n" + "事实🙂".repeat(2500)
                + "\n{\"apiKey\":\"public-answer-example\"}\nAuthorization: Bearer public-example";
        CountDownLatch entered = scriptWithFirstCallBlocked(TOOL, FINISH, answer);
        String taskId = create(ownerToken, AGENT, "sse-resume");
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sequence(taskId)).isGreaterThan(2);

        // Subscription starts after both creation and Runner startup have committed.
        SseConnection first = open(ownerToken, taskId, "", null);
        Frame appliedFirst = first.next();
        assertThat(appliedFirst.event()).isEqualTo("TASK_CREATED");
        assertThat(appliedFirst.id()).isEqualTo("1");
        first.close(); // Anything read ahead is deliberately NOT an application acknowledgement.
        await().atMost(WAIT).untilAsserted(() -> assertThat(sse.activeConnectionCount()).isZero());
        assertThat(detail(taskId).path("status").asText()).isEqualTo("RUNNING");
        assertThat(detail(taskId).hasNonNull("cancelRequestedAt")).isFalse();

        SseConnection resumed = open(ownerToken, taskId, "", appliedFirst.id());
        releaseModel.countDown();
        List<Frame> applied = new ArrayList<>();
        applied.add(appliedFirst);
        applied.addAll(resumed.untilEnd());
        assertThat(applied.get(1).id()).isEqualTo("2");
        assertThat(applied.getLast().event()).isEqualTo("TASK_COMPLETED");
        assertThat(applied.stream().filter(frame -> frame.event().equals("ANSWER_CHUNK")).count()).isGreaterThan(1);
        assertProtocolAndTrace(taskId, applied);
        assertThat(answer(applied)).isEqualTo(answer);
        assertThat(detail(taskId).path("finalAnswer").asText()).isEqualTo(answer);
        assertThat(detail(taskId).path("toolCallsUsed").asInt()).isEqualTo(1);
        JsonNode trace = get("/tasks/" + taskId + "/trace");
        assertThat(trace.findValues("toolCalls").stream().mapToInt(JsonNode::size).sum()).isEqualTo(1);
        assertThat(trace.findValues("ragRetrievals").stream().mapToInt(JsonNode::size).sum()).isEqualTo(1);
        verify(llm, times(3)).chat(any());
        verify(vectors, times(1)).search(any());

        // An older acknowledged cursor permits retransmission; applying by sequence remains exact once.
        List<Frame> retransmission = open(ownerToken, taskId, "?afterSequence=0", "0").untilEnd();
        Map<String, Frame> deduplicated = new LinkedHashMap<>();
        AtomicInteger applications = new AtomicInteger();
        for (Frame frame : concat(applied, retransmission)) {
            if (deduplicated.putIfAbsent(frame.id(), frame) == null) applications.incrementAndGet();
        }
        assertThat(applications.get()).isEqualTo(sequence(taskId));
        assertThat(new ArrayList<>(deduplicated.values())).isEqualTo(applied);
        assertThat(answer(new ArrayList<>(deduplicated.values()))).isEqualTo(answer);
        await().atMost(WAIT).untilAsserted(() -> assertThat(sse.activeConnectionCount()).isZero());
    }

    @Test
    void shouldRejectUnauthorizedForeignMissingAndInvalidCursorsBeforeOpeningTheStream() throws Exception {
        script(FINISH, "Recorded facts.");
        String taskId = create(ownerToken, AGENT, "sse-cursors");
        terminal(taskId);
        assertThat(errorRequest(null, taskId, "", List.of()).statusCode()).isEqualTo(401);
        for (String hidden : List.of(taskId, "999999999999999999")) {
            assertError(errorRequest(otherToken, hidden, "", List.of()), 404, "COMMON_NOT_FOUND");
        }
        for (String cursor : List.of("", "-1", "x", "1.5", "%20", "%2B1", "9223372036854775808")) {
            assertError(errorRequest(ownerToken, taskId, "?afterSequence=" + cursor, List.of()),
                    400, "COMMON_PARAM_INVALID");
        }
        for (String cursor : List.of("-1", "x", "1.5", "9223372036854775808")) {
            assertError(errorRequest(ownerToken, taskId, "", List.of(cursor)), 400, "COMMON_PARAM_INVALID");
        }
        assertError(errorRequest(ownerToken, taskId, "?afterSequence=1", List.of("2")),
                400, "COMMON_PARAM_INVALID");
        assertError(errorRequest(ownerToken, taskId, "?afterSequence=1&afterSequence=1", List.of()),
                400, "COMMON_PARAM_INVALID");
        assertError(errorRequest(ownerToken, taskId, "", List.of("1", "1")),
                400, "COMMON_PARAM_INVALID");
        assertError(errorRequest(ownerToken, taskId, "?afterSequence=" + (sequence(taskId) + 1), List.of()),
                400, "COMMON_PARAM_INVALID");
        assertError(errorRequest(ownerToken, taskId, "", List.of(Long.toString(Long.MAX_VALUE))),
                400, "COMMON_PARAM_INVALID");
        assertThat(sse.activeConnectionCount()).isZero();

        List<Frame> matching = open(ownerToken, taskId, "?afterSequence=1", "1").untilEnd();
        assertThat(matching.getFirst().id()).isEqualTo("2");
        assertThat(matching.getLast().event()).isEqualTo("TASK_COMPLETED");
        assertThat(open(ownerToken, taskId, "?afterSequence=" + sequence(taskId), null).untilEnd()).isEmpty();
    }

    @Test
    void shouldReplaySafeHistoryAfterAssociatedResourcesAreDeleted() throws Exception {
        script(TOOL, FINISH, "Recorded facts [S1].");
        String taskId = create(ownerToken, AGENT, "sse-history");
        terminal(taskId);
        jdbc.update("UPDATE agent_task_event SET payload=payload || ?::jsonb WHERE task_id=? AND sequence_no=1",
                "{\"apiKey\":\"legacy-secret\",\"nested\":{\"reasoning\":\"private-thought\"}}", Long.valueOf(taskId));
        jdbc.update("UPDATE agent_app SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", AGENT);
        jdbc.update("UPDATE knowledge_base SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", KB);
        jdbc.update("UPDATE knowledge_document SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", DOCUMENT);
        jdbc.update("UPDATE tool_definition SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", ORDER_TOOL);
        List<Frame> events = open(ownerToken, taskId, "", null).untilEnd();
        assertProtocolAndTrace(taskId, events);
        assertThat(events.toString()).doesNotContain("legacy-secret", "private-thought");
        assertThat(events.getFirst().data().path("payload").path("apiKey").isMissingNode()).isTrue();
        assertThat(events.getFirst().data().path("payload").properties().stream().map(Map.Entry::getKey))
                .containsExactly("status");
        assertThat(events.getLast().event()).isEqualTo("TASK_COMPLETED");
        assertError(errorRequest(otherToken, taskId, "", List.of()), 404, "COMMON_NOT_FOUND");
    }

    @Test
    void shouldRejectInitialAndTrailingGapsWithoutOpeningAStream() throws Exception {
        script(FINISH, "Recorded facts.");
        String taskId = create(ownerToken, AGENT, "sse-initial-gap");
        terminal(taskId);
        jdbc.update("DELETE FROM agent_task_event WHERE task_id=? AND sequence_no=2", Long.valueOf(taskId));
        assertError(errorRequest(ownerToken, taskId, "", List.of()), 409, "TASK_EVENT_SEQUENCE_GAP");
        long end = sequence(taskId);
        jdbc.update("DELETE FROM agent_task_event WHERE task_id=? AND sequence_no=?", Long.valueOf(taskId), end);
        assertError(errorRequest(ownerToken, taskId, "?afterSequence=" + (end - 1), List.of()),
                409, "TASK_EVENT_SEQUENCE_GAP");
        assertThat(sse.activeConnectionCount()).isZero();
    }

    @Test
    void shouldSendExplicitGapErrorAndNeverSkipForwardAcrossABatchBoundary() throws Exception {
        script(FINISH, "Recorded facts.");
        String taskId = create(ownerToken, AGENT, "sse-later-gap");
        terminal(taskId);
        assertThat(sequence(taskId)).isGreaterThan(6);
        jdbc.update("DELETE FROM agent_task_event WHERE task_id=? AND sequence_no=5", Long.valueOf(taskId));
        List<Frame> frames = open(ownerToken, taskId, "", null).untilEnd();
        assertThat(frames).hasSize(5);
        assertThat(frames.subList(0, 4).stream().map(Frame::id)).containsExactly("1", "2", "3", "4");
        Frame error = frames.getLast();
        assertThat(error.event()).isEqualTo("STREAM_ERROR");
        assertThat(error.id()).isNull();
        assertThat(error.data().path("code").asText()).isEqualTo("TASK_EVENT_SEQUENCE_GAP");
        assertThat(error.data().path("lastSentSequence").asLong()).isEqualTo(4);
        assertThat(error.data().path("expectedSequence").asLong()).isEqualTo(5);
        await().atMost(WAIT).untilAsserted(() -> assertThat(sse.activeConnectionCount()).isZero());
    }

    @Test
    void shouldBoundGlobalAndUserConnectionsSendUnsequencedHeartbeatsAndReleaseDisconnectedSlots() throws Exception {
        CountDownLatch entered = scriptWithFirstCallBlocked(FINISH, "Recorded facts.");
        String running = create(ownerToken, AGENT, "sse-capacity-owner");
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        String queued = create(otherToken, OTHER_AGENT, "sse-capacity-other");
        String ownerCursor = "?afterSequence=" + sequence(running);
        String otherCursor = "?afterSequence=" + sequence(queued);
        SseConnection ownerOne = open(ownerToken, running, ownerCursor, null);
        SseConnection ownerTwo = open(ownerToken, running, ownerCursor, null);
        assertError(errorRequest(ownerToken, running, ownerCursor, List.of()),
                503, "TASK_SSE_CAPACITY_EXCEEDED");
        SseConnection otherOne = open(otherToken, queued, otherCursor, null);
        assertError(errorRequest(otherToken, queued, otherCursor, List.of()),
                503, "TASK_SSE_CAPACITY_EXCEEDED");
        long before = sequence(running);
        await().atMost(WAIT).untilAsserted(() -> assertThat(ownerOne.heartbeats.get()).isGreaterThan(0));
        assertThat(sequence(running)).isEqualTo(before);
        assertThat(executor.getActiveCount()).isEqualTo(1);
        assertThat(detail(running).hasNonNull("cancelRequestedAt")).isFalse();
        ownerOne.close();
        await().atMost(WAIT).untilAsserted(() -> assertThat(sse.activeConnectionCount()).isEqualTo(2));
        SseConnection otherTwo = open(otherToken, queued, otherCursor, null);
        assertThat(sse.activeConnectionCount()).isEqualTo(3);

        // Cancel the queued fixture through HTTP; disconnecting its streams has no such effect.
        ResponseEntity<JsonNode> cancellation = http.exchange("/api/v1/tasks/" + queued + "/cancel",
                HttpMethod.POST, new HttpEntity<>(headers(otherToken)), JsonNode.class);
        assertThat(cancellation.getStatusCode().value()).isEqualTo(200);
        releaseModel.countDown();
        assertThat(ownerTwo.untilEnd().getLast().event()).isEqualTo("TASK_COMPLETED");
        assertThat(otherOne.untilEnd().getLast().event()).isEqualTo("TASK_CANCELLED");
        assertThat(otherTwo.untilEnd().getLast().event()).isEqualTo("TASK_CANCELLED");
        await().atMost(WAIT).untilAsserted(() -> assertThat(sse.activeConnectionCount()).isZero());
    }

    @Test
    void shouldCloseAtConnectionDeadlineReleaseCapacityAndLeaveRunningTaskAlive() throws Exception {
        CountDownLatch entered = scriptWithFirstCallBlocked(FINISH, "Recorded facts.");
        String taskId = create(ownerToken, AGENT, "sse-timeout");
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        long acknowledged = sequence(taskId);
        SseConnection connection = open(ownerToken, taskId, "?afterSequence=" + acknowledged, null);
        assertThat(connection.untilEnd()).isEmpty();
        assertThat(connection.heartbeats.get()).isGreaterThan(0);
        await().atMost(WAIT).untilAsserted(() -> assertThat(sse.activeConnectionCount()).isZero());
        assertThat(detail(taskId).path("status").asText()).isEqualTo("RUNNING");
        assertThat(detail(taskId).hasNonNull("cancelRequestedAt")).isFalse();
        assertThat(sequence(taskId)).isEqualTo(acknowledged);
        releaseModel.countDown();
        assertThat(open(ownerToken, taskId, "", Long.toString(acknowledged)).untilEnd().getLast().event())
                .isEqualTo("TASK_COMPLETED");
    }

    private CountDownLatch scriptWithFirstCallBlocked(String... responses) {
        CountDownLatch entered = new CountDownLatch(1);
        releaseModel = new CountDownLatch(1);
        AtomicInteger cursor = new AtomicInteger();
        when(llm.chat(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            int index = cursor.getAndIncrement();
            if (index == 0) {
                entered.countDown();
                if (!releaseModel.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Model gate timed out");
            }
            if (index >= responses.length) throw new IllegalStateException("Unexpected model call " + index);
            return result(responses[index]);
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
            if (index >= responses.length) throw new IllegalStateException("Unexpected model call " + index);
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

    private String create(String token, long agent, String key) {
        HttpHeaders headers = headers(token);
        headers.set("Idempotency-Key", key);
        ResponseEntity<JsonNode> response = http.exchange("/api/v1/agents/" + agent + "/tasks", HttpMethod.POST,
                new HttpEntity<>(Map.of("userInput", INPUT), headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).as("HTTP create: %s", response.getBody()).isEqualTo(201);
        return response.getBody().path("data").path("taskId").asText();
    }

    private JsonNode detail(String taskId) {
        return get("/tasks/" + taskId);
    }

    private JsonNode get(String path) {
        ResponseEntity<JsonNode> response = http.exchange("/api/v1" + path, HttpMethod.GET,
                new HttpEntity<>(headers(ownerToken)), JsonNode.class);
        assertThat(response.getStatusCode().value()).as("HTTP GET: %s", response.getBody()).isEqualTo(200);
        return response.getBody().path("data");
    }

    private void terminal(String taskId) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertThat(detail(taskId).path("status").asText()).isEqualTo("COMPLETED"));
    }

    private long sequence(String taskId) {
        return jdbc.queryForObject("SELECT last_event_sequence FROM agent_task WHERE id=?", Long.class, Long.valueOf(taskId));
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return headers;
    }

    private HttpRequest request(String token, String taskId, String query, List<String> lastIds) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/tasks/" + taskId + "/events" + query))
                .GET().timeout(WAIT).header("Accept", "text/event-stream");
        if (token != null) request.header("Authorization", "Bearer " + token);
        lastIds.forEach(value -> request.header("Last-Event-ID", value));
        return request.build();
    }

    private HttpResponse<String> errorRequest(String token, String taskId, String query, List<String> lastIds)
            throws IOException, InterruptedException {
        return streamHttp.send(request(token, taskId, query, lastIds), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws IOException {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).doesNotStartWith("text/event-stream");
    }

    private SseConnection open(String token, String taskId, String query, String lastId) throws Exception {
        HttpResponse<InputStream> response = streamHttp.send(
                request(token, taskId, query, lastId == null ? List.of() : List.of(lastId)),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        SseConnection connection = new SseConnection(response.body());
        opened.add(connection);
        return connection;
    }

    private void assertProtocolAndTrace(String taskId, List<Frame> events) {
        JsonNode trace = get("/tasks/" + taskId + "/trace");
        JsonNode stored = trace.path("events");
        assertThat(events).hasSize(stored.size());
        assertThat(events).hasSize((int) sequence(taskId));
        for (int i = 0; i < events.size(); i++) {
            Frame frame = events.get(i);
            JsonNode event = frame.data();
            JsonNode expected = stored.get(i);
            assertThat(frame.id()).isEqualTo(Long.toString(i + 1L));
            assertThat(frame.event()).isEqualTo(expected.path("eventType").asText());
            assertThat(event.properties().stream().map(Map.Entry::getKey))
                    .containsExactlyInAnyOrder("taskId", "sequenceNo", "eventType", "timestamp", "payload");
            assertThat(event.path("taskId").isTextual()).isTrue();
            assertThat(event.path("taskId").asText()).isEqualTo(taskId);
            assertThat(event.path("sequenceNo").asLong()).isEqualTo(i + 1L);
            assertThat(event.path("eventType").asText()).isEqualTo(frame.event());
            assertThat(event.path("payload")).isEqualTo(expected.path("payload"));
            assertThat(OffsetDateTime.parse(event.path("timestamp").asText()).toInstant())
                    .isEqualTo(OffsetDateTime.parse(expected.path("createdAt").asText()).toInstant());
        }
        assertThat(answer(events)).isEqualTo(trace.path("task").path("finalAnswer").asText());
        assertThat(trace.path("steps")).isNotEmpty();
    }

    private static String answer(List<Frame> frames) {
        return frames.stream().filter(frame -> frame.event().equals("ANSWER_CHUNK"))
                .map(frame -> frame.data().path("payload").path("text").asText()).reduce("", String::concat);
    }

    private static List<Frame> concat(List<Frame> first, List<Frame> second) {
        List<Frame> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private record Frame(String id, String event, JsonNode data) { }

    /** Parses actual HTTP bytes. Reading ahead is deliberately distinct from application acknowledgement. */
    private final class SseConnection implements AutoCloseable {
        private static final Object END = new Object();
        private final InputStream input;
        private final BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        private final AtomicInteger heartbeats = new AtomicInteger();
        private volatile boolean closed;
        private boolean ended;

        private SseConnection(InputStream input) {
            this.input = input;
            Thread.ofVirtual().name("v42-real-http-sse-reader").start(this::read);
        }

        private void read() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String id = null, event = null;
                StringBuilder data = new StringBuilder();
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.isEmpty()) {
                        if (!data.isEmpty()) {
                            received.add(new Frame(id, event, json.readTree(data.toString())));
                        }
                        id = null;
                        event = null;
                        data.setLength(0);
                    } else if (line.startsWith(":")) {
                        heartbeats.incrementAndGet();
                    } else {
                        int colon = line.indexOf(':');
                        String field = colon < 0 ? line : line.substring(0, colon);
                        String value = colon < 0 ? "" : line.substring(colon + 1);
                        if (value.startsWith(" ")) value = value.substring(1);
                        switch (field) {
                            case "id" -> id = value;
                            case "event" -> event = value;
                            case "data" -> {
                                if (!data.isEmpty()) data.append('\n');
                                data.append(value);
                            }
                            default -> { }
                        }
                    }
                }
            } catch (IOException error) {
                if (!closed) received.add(error);
            } finally {
                received.add(END);
            }
        }

        private Frame next() throws Exception {
            if (ended) return null;
            Object value = received.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(value).as("SSE frame or EOF before deadline").isNotNull();
            if (value == END) {
                ended = true;
                return null;
            }
            if (value instanceof IOException error) throw error;
            return (Frame) value;
        }

        private List<Frame> untilEnd() throws Exception {
            List<Frame> result = new ArrayList<>();
            for (Frame frame; (frame = next()) != null;) result.add(frame);
            return result;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            input.close();
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must identify the disposable test database");
        return value;
    }
}
