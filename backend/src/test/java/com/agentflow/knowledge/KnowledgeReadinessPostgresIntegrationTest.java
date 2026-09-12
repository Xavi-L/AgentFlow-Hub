package com.agentflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

import com.agentflow.agent.binding.repository.AgentKnowledgeBindingMapper;
import com.agentflow.agent.snapshot.AgentTaskSnapshotResolver;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.infra.llm.LlmGateway;
import com.agentflow.knowledge.vector.EmbeddingGateway;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.user.model.AppUser;
import com.agentflow.user.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V44 real JWT HTTP + PostgreSQL read-model acceptance. Provider edges are mocked and
 * must remain unused. Opt in only against a disposable database: fixtures delete rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "agentflow.postgres.integration", matches = "true")
@Import(KnowledgeReadinessPostgresIntegrationTest.QueryConfiguration.class)
class KnowledgeReadinessPostgresIntegrationTest {
    private static final long OWNER = 4401, OTHER = 4402, KB = 4410, OTHER_KB = 4420;
    private static final long SECOND_KB = 4430, AGENT = 4490, DOCUMENT = 45000;
    private static final String STRATEGY = "structured-token-v1";
    private static final String PROFILE = "dashscope-te-v4-1024-cosine";
    private static final String AGGREGATE =
            "com.agentflow.knowledge.repository.KnowledgeDocumentReadMapper.selectReadinessByDocumentIds";

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private AgentKnowledgeBindingMapper bindings;
    @Autowired private AgentTaskSnapshotResolver snapshots;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ReadQueryProbe probe;
    @MockBean private LlmGateway llm;
    @MockBean private EmbeddingGateway embeddings;
    @MockBean private VectorStoreGateway vectors;
    private String ownerToken;
    private String otherToken;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> requiredProperty("agentflow.postgres.url"));
        r.add("spring.datasource.username", () -> requiredProperty("agentflow.postgres.user"));
        r.add("spring.datasource.password", () -> System.getProperty("agentflow.postgres.password", ""));
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        r.add("spring.datasource.hikari.minimum-idle", () -> "1");
        r.add("mybatis-plus.configuration.log-impl", () -> "org.apache.ibatis.logging.nologging.NoLoggingImpl");
        r.add("logging.level.com.agentflow", () -> "WARN");
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
        jdbc.update("""
                INSERT INTO app_user(id,username,email,password_hash,display_name) VALUES
                  (?,'v44-owner','v44-owner@example.test','hash','V44 Owner'),
                  (?,'v44-other','v44-other@example.test','hash','V44 Other')
                """, OWNER, OTHER);
        jdbc.update("""
                INSERT INTO knowledge_base(id,user_id,name) VALUES
                  (?,?,'V44 Knowledge'), (?,?,'Other owner'), (?,?,'Second knowledge')
                """, KB, OWNER, OTHER_KB, OTHER, SECOND_KB, OWNER);
        jdbc.update("""
                INSERT INTO agent_app(id,user_id,name,system_prompt,model_provider,model_name,
                  max_steps,max_tool_calls,max_tokens,timeout_seconds)
                VALUES (?,?,'V44 Agent','Explain facts','openai-compatible','controlled-model',6,4,50000,120)
                """, AGENT, OWNER);
        jdbc.update("INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id) VALUES (4491,?,?,?)",
                OWNER, AGENT, KB);
        ownerToken = token(OWNER);
        otherToken = token(OTHER);
        reset(llm, embeddings, vectors);
        probe.reset();
    }

    @AfterEach
    void noProviderCalls() {
        probe.release();
        verifyNoInteractions(llm, embeddings, vectors);
    }

    @Test
    void shouldExposeFiveStatesAndCurrentGenerationCountsThroughListAndDetail() {
        record Case(String parse, String[] statuses, String expected) { }
        List<Case> cases = List.of(
                new Case("PENDING", new String[]{"COMPLETED"}, "NOT_READY"),
                new Case("PROCESSING", new String[]{"FAILED"}, "NOT_READY"),
                new Case("REPROCESSING", new String[]{"COMPLETED"}, "NOT_READY"),
                new Case("FAILED", new String[]{"PENDING"}, "FAILED"),
                new Case("COMPLETED", new String[]{}, "FAILED"),
                new Case("COMPLETED", new String[]{"PENDING"}, "INDEXING"),
                new Case("COMPLETED", new String[]{"PROCESSING"}, "INDEXING"),
                new Case("COMPLETED", new String[]{"PENDING", "PROCESSING", "COMPLETED", "FAILED"}, "INDEXING"),
                new Case("COMPLETED", new String[]{"COMPLETED", "FAILED"}, "DEGRADED"),
                new Case("COMPLETED", new String[]{"FAILED", "FAILED"}, "FAILED"),
                new Case("COMPLETED", new String[]{"COMPLETED", "COMPLETED"}, "READY")
        );
        for (int i = 0; i < cases.size(); i++) {
            long id = DOCUMENT + i;
            Case scenario = cases.get(i);
            document(id, OWNER, KB, scenario.parse(), 3);
            for (int j = 0; j < scenario.statuses().length; j++) {
                chunk(id, j, OWNER, KB, 3, scenario.statuses()[j], STRATEGY);
            }
            // A stale successful chunk cannot rescue a zero/all-failed current generation.
            // A stale failed or unsupported chunk cannot poison a ready current generation.
            chunk(id, 90, OWNER, KB, 2, "COMPLETED", "old-strategy");
            chunk(id, 91, OWNER, KB, 2, "FAILED", "old-strategy");
        }
        JsonNode page = data(get(ownerToken, listPath(KB) + "?pageSize=100"));
        assertThat(page.path("total").asLong()).isEqualTo(cases.size());
        assertThat(page.path("items")).hasSize(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            long id = DOCUMENT + i;
            Case scenario = cases.get(i);
            JsonNode detail = data(get(ownerToken, detailPath(id)));
            assertThat(detail).isEqualTo(item(page, id));
            assertThat(detail.path("retrievalReadiness").asText()).isEqualTo(scenario.expected());
            assertThat(detail.path("vectorGeneration").asLong()).isEqualTo(3);
            assertThat(detail.path("vectorGeneration").isIntegralNumber()).isTrue();
            for (String status : List.of("PENDING", "PROCESSING", "COMPLETED", "FAILED")) {
                long expected = List.of(scenario.statuses()).stream().filter(status::equals).count();
                assertThat(detail.path("vectorization").path(status.toLowerCase()).asLong()).isEqualTo(expected);
            }
            assertSafeOriginalFields(detail, id, scenario.parse());
        }
        assertThat(bindings.selectReadyDocumentGenerations(AGENT, OWNER))
                .extracting(row -> row.getDocumentId()).containsExactly(DOCUMENT + cases.size() - 1);
        assertThat(snapshots.resolve(OWNER, AGENT).retrieval().knowledgeBases().getFirst().documents())
                .extracting(row -> row.documentId()).containsExactly(String.valueOf(DOCUMENT + cases.size() - 1));
    }

    @Test
    void shouldResolveConfigurationIndependentlyAndFailUnsupportedCompletedDocuments() {
        document(DOCUMENT, OWNER, KB, "COMPLETED", 1);
        chunk(DOCUMENT, 0, OWNER, KB, 1, "COMPLETED", STRATEGY);
        assertConfiguration(KB, PROFILE, STRATEGY);
        jdbc.update("UPDATE knowledge_base SET embedding_provider='legacy',embedding_model='old' WHERE id=?", KB);
        assertConfiguration(KB, null, STRATEGY);
        assertReadiness(DOCUMENT, "FAILED");
        assertBindingError(ErrorCode.AGENT_BINDING_INVALID);
        jdbc.update("UPDATE knowledge_base SET embedding_provider='dashscope',embedding_model='text-embedding-v4',"
                + "chunk_size=900 WHERE id=?", KB);
        assertConfiguration(KB, PROFILE, null);
        assertReadiness(DOCUMENT, "FAILED");
        assertBindingError(ErrorCode.AGENT_BINDING_INVALID);
        jdbc.update("UPDATE knowledge_base SET embedding_model='legacy',chunk_overlap=100 WHERE id=?", KB);
        assertConfiguration(KB, null, null);
        assertReadiness(DOCUMENT, "FAILED");
        // Parsing is not yet complete, so unsupported configuration does not invent a parse failure.
        jdbc.update("UPDATE knowledge_document SET parse_status='PENDING' WHERE id=?", DOCUMENT);
        assertReadiness(DOCUMENT, "NOT_READY");
    }

    @Test
    void shouldRejectMixedAndUniformUnsupportedChunkStrategiesBeforeIndexingOrAdmission() {
        document(DOCUMENT, OWNER, KB, "COMPLETED", 1);
        chunk(DOCUMENT, 0, OWNER, KB, 1, "COMPLETED", STRATEGY);
        chunk(DOCUMENT, 1, OWNER, KB, 1, "PENDING", "unsupported-v2");
        assertReadiness(DOCUMENT, "FAILED");
        assertThat(bindings.selectReadyDocumentGenerations(AGENT, OWNER)).isEmpty();
        assertBindingError(ErrorCode.RAG_KNOWLEDGE_NOT_READY);
        jdbc.update("UPDATE knowledge_chunk SET vectorization_status='COMPLETED',vector_id=? WHERE document_id=?",
                "44000000-0000-0000-0000-000000000001", DOCUMENT);
        assertReadiness(DOCUMENT, "FAILED");
        assertThat(bindings.selectReadyDocumentGenerations(AGENT, OWNER)).isEmpty();
        jdbc.update("UPDATE knowledge_chunk SET chunk_strategy_version='unsupported-v2' WHERE document_id=?", DOCUMENT);
        assertReadiness(DOCUMENT, "FAILED");
        // V37's SQL is a candidate query; its resolver rejects an unsupported uniform strategy.
        assertThat(bindings.selectReadyDocumentGenerations(AGENT, OWNER)).hasSize(1);
        assertBindingError(ErrorCode.AGENT_BINDING_INVALID);
    }

    @Test
    void shouldScopeOwnersParentsAndDeletionWhileKeepingDisabledManagementVisible() {
        document(DOCUMENT, OWNER, KB, "COMPLETED", 1);
        chunk(DOCUMENT, 0, OWNER, KB, 1, "COMPLETED", STRATEGY);
        document(DOCUMENT + 1, OTHER, OTHER_KB, "COMPLETED", 1);
        chunk(DOCUMENT + 1, 0, OTHER, OTHER_KB, 1, "FAILED", STRATEGY);
        document(DOCUMENT + 2, OWNER, SECOND_KB, "COMPLETED", 1);
        chunk(DOCUMENT + 2, 0, OWNER, SECOND_KB, 1, "FAILED", STRATEGY);
        assertThat(data(get(ownerToken, listPath(KB))).path("items")).hasSize(1);
        assertReadiness(DOCUMENT, "READY");
        assertError(get(null, detailPath(DOCUMENT)), 401);
        assertError(get(otherToken, detailPath(DOCUMENT)), 404);
        assertError(get(otherToken, listPath(KB)), 404);
        assertError(get(ownerToken, detailPath(DOCUMENT + 1)), 404);
        assertError(get(ownerToken, "/knowledge-bases/" + OTHER_KB), 404);
        assertError(get(ownerToken, detailPath(999999)), 404);
        jdbc.update("UPDATE knowledge_base SET status='DISABLED' WHERE id=?", KB);
        assertConfiguration(KB, PROFILE, STRATEGY);
        assertReadiness(DOCUMENT, "NOT_READY");
        assertThat(data(get(ownerToken, listPath(KB))).path("items")).hasSize(1);
        assertBindingError(ErrorCode.RAG_KNOWLEDGE_NOT_READY);
        jdbc.update("UPDATE knowledge_document SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", DOCUMENT);
        assertError(get(ownerToken, detailPath(DOCUMENT)), 404);
        assertThat(data(get(ownerToken, listPath(KB))).path("total").asLong()).isZero();
        jdbc.update("UPDATE knowledge_base SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", SECOND_KB);
        assertError(get(ownerToken, detailPath(DOCUMENT + 2)), 404);
        assertError(get(ownerToken, listPath(SECOND_KB)), 404);
        assertError(get(ownerToken, "/knowledge-bases/" + SECOND_KB), 404);
        assertThat(data(get(ownerToken, "/knowledge-bases")).path("items")).hasSize(1);
    }

    @Test
    void shouldAggregateOnceForOnlyTheReturnedPageAndSkipEmptyPages() {
        for (int i = 0; i < 7; i++) {
            document(DOCUMENT + i, OWNER, KB, "COMPLETED", 1);
            chunk(DOCUMENT + i, 0, OWNER, KB, 1, "COMPLETED", STRATEGY);
        }
        probe.reset();
        JsonNode single = data(get(ownerToken, listPath(KB) + "?pageSize=1"));
        int singleQueryCount = probe.knowledgeSql.size();
        assertThat(probe.batches).containsExactly(List.of(DOCUMENT + 6));
        assertThat(single.path("hasNext").asBoolean()).isTrue();
        probe.reset();
        JsonNode page = data(get(ownerToken, listPath(KB) + "?page=2&pageSize=3"));
        assertThat(page.path("total").asLong()).isEqualTo(7);
        assertThat(ids(page)).containsExactly(DOCUMENT + 3, DOCUMENT + 2, DOCUMENT + 1);
        assertThat(probe.batches).containsExactly(ids(page));
        assertThat(probe.knowledgeSql).hasSize(singleQueryCount);
        assertThat(singleQueryCount).isBetween(3, 4);
        probe.reset();
        JsonNode empty = data(get(ownerToken, listPath(KB) + "?page=10&pageSize=3"));
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("total").asLong()).isEqualTo(7);
        assertThat(probe.batches).isEmpty();
    }

    enum SnapshotChange { GENERATION, PARSE, CHUNK_STATUS, CONFIGURATION, DOCUMENT_DELETE, PARENT_DELETE }

    @ParameterizedTest
    @EnumSource(SnapshotChange.class)
    void shouldKeepListAndDetailInOneSnapshotAcrossCommittedConcurrentChanges(SnapshotChange change) throws Exception {
        for (boolean list : List.of(false, true)) {
            jdbc.update("DELETE FROM knowledge_chunk");
            jdbc.update("DELETE FROM knowledge_document");
            jdbc.update("UPDATE knowledge_base SET deleted_at=NULL,chunk_size=800 WHERE id=?", KB);
            document(DOCUMENT, OWNER, KB, "COMPLETED", 1);
            chunk(DOCUMENT, 0, OWNER, KB, 1, "COMPLETED", STRATEGY);
            String path = list ? listPath(KB) : detailPath(DOCUMENT);
            QueryGate gate = probe.pauseNextAggregate();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var response = pool.submit(() -> get(ownerToken, path));
                try {
                    assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        switch (change) {
                            case GENERATION -> {
                                jdbc.update("UPDATE knowledge_document SET vector_generation=2 WHERE id=?", DOCUMENT);
                                chunk(DOCUMENT, 1, OWNER, KB, 2, "PENDING", STRATEGY);
                            }
                            case PARSE -> jdbc.update("UPDATE knowledge_document SET parse_status='REPROCESSING' WHERE id=?", DOCUMENT);
                            case CHUNK_STATUS -> jdbc.update("UPDATE knowledge_chunk SET vectorization_status='FAILED',"
                                    + "vector_id=NULL,vectorization_error='controlled failure' WHERE document_id=?", DOCUMENT);
                            case CONFIGURATION -> jdbc.update("UPDATE knowledge_base SET chunk_size=900 WHERE id=?", KB);
                            case DOCUMENT_DELETE -> jdbc.update("UPDATE knowledge_document SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", DOCUMENT);
                            case PARENT_DELETE -> jdbc.update("UPDATE knowledge_base SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", KB);
                        }
                    });
                } finally {
                    gate.release.countDown();
                }
                JsonNode oldResponse = data(response.get(10, TimeUnit.SECONDS));
                JsonNode oldDocument = list ? item(oldResponse, DOCUMENT) : oldResponse;
                assertThat(oldDocument.path("retrievalReadiness").asText()).isEqualTo("READY");
                assertThat(oldDocument.path("vectorGeneration").asLong()).isEqualTo(1);
                assertThat(oldDocument.path("parseStatus").asText()).isEqualTo("COMPLETED");
                assertThat(oldDocument.path("vectorization").path("completed").asLong()).isEqualTo(1);
                assertThat(oldDocument.path("vectorization").path("pending").asLong()).isZero();
                if (list) assertThat(oldResponse.path("total").asLong()).isEqualTo(1);
            }
            if (change == SnapshotChange.PARENT_DELETE || change == SnapshotChange.DOCUMENT_DELETE) {
                assertError(get(ownerToken, detailPath(DOCUMENT)), 404);
                if (change == SnapshotChange.PARENT_DELETE) assertError(get(ownerToken, listPath(KB)), 404);
                else assertThat(data(get(ownerToken, listPath(KB))).path("total").asLong()).isZero();
            } else {
                JsonNode fresh = data(get(ownerToken, path));
                JsonNode freshDocument = list ? item(fresh, DOCUMENT) : fresh;
                assertThat(freshDocument.path("retrievalReadiness").asText()).isEqualTo(switch (change) {
                    case GENERATION -> "INDEXING";
                    case PARSE -> "NOT_READY";
                    default -> "FAILED";
                });
                if (change == SnapshotChange.GENERATION) {
                    assertThat(freshDocument.path("vectorGeneration").asLong()).isEqualTo(2);
                    assertThat(freshDocument.path("vectorization").path("completed").asLong()).isZero();
                    assertThat(freshDocument.path("vectorization").path("pending").asLong()).isEqualTo(1);
                }
            }
        }
    }

    private void document(long id, long userId, long knowledgeBaseId, String parse, long generation) {
        jdbc.update("""
                INSERT INTO knowledge_document(id,user_id,knowledge_base_id,file_name,file_type,mime_type,file_size,
                  storage_bucket,storage_object_key,parse_status,parse_error,vector_generation,created_at)
                VALUES (?,?,?,?,'TXT','text/plain',100,'private-fixture',?,?,'internal-parser-diagnostic',?,
                  TIMESTAMPTZ '2026-09-06 00:00:00Z')
                """, id, userId, knowledgeBaseId, "document-" + id + ".txt", "internal-object-" + id, parse, generation);
    }

    private void chunk(long documentId, int index, long userId, long knowledgeBaseId, long generation,
            String status, String strategy) {
        jdbc.update("""
                INSERT INTO knowledge_chunk(id,user_id,knowledge_base_id,document_id,chunk_index,content,char_count,
                  token_count,vectorization_status,vectorization_error,content_hash,vector_id,vector_generation,
                  chunk_strategy_version)
                VALUES (?,?,?,?,?,'controlled content',18,4,?,?,?, ?,?,?)
                """, documentId * 100 + index, userId, knowledgeBaseId, documentId, index, status,
                "FAILED".equals(status) ? "internal-vector-error" : null, "a".repeat(64),
                "COMPLETED".equals(status) ? "44000000-0000-0000-0000-000000000001" : null, generation, strategy);
    }

    private void assertConfiguration(long knowledgeBaseId, String profile, String strategy) {
        JsonNode detail = data(get(ownerToken, "/knowledge-bases/" + knowledgeBaseId));
        JsonNode page = data(get(ownerToken, "/knowledge-bases"));
        assertThat(item(page, knowledgeBaseId)).isEqualTo(detail);
        assertThat(detail.has("embeddingProfileCode")).isTrue();
        assertThat(detail.has("chunkStrategyVersion")).isTrue();
        if (profile == null) assertThat(detail.path("embeddingProfileCode").isNull()).isTrue();
        else assertThat(detail.path("embeddingProfileCode").asText()).isEqualTo(profile);
        if (strategy == null) assertThat(detail.path("chunkStrategyVersion").isNull()).isTrue();
        else assertThat(detail.path("chunkStrategyVersion").asText()).isEqualTo(strategy);
        assertThat(detail.path("id").isTextual()).isTrue();
        assertThat(detail.toString()).doesNotContain("userId", "metadata", "deletedAt");
    }

    private void assertReadiness(long documentId, String expected) {
        JsonNode detail = data(get(ownerToken, detailPath(documentId)));
        assertThat(detail.path("retrievalReadiness").asText()).isEqualTo(expected);
        assertThat(item(data(get(ownerToken, listPath(KB))), documentId)).isEqualTo(detail);
    }

    private void assertBindingError(ErrorCode expected) {
        assertThatThrownBy(() -> snapshots.resolve(OWNER, AGENT)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }

    private static void assertSafeOriginalFields(JsonNode detail, long documentId, String parse) {
        assertThat(detail.path("id").isTextual()).isTrue();
        assertThat(detail.path("id").asText()).isEqualTo(String.valueOf(documentId));
        assertThat(detail.path("knowledgeBaseId").asText()).isEqualTo(String.valueOf(KB));
        assertThat(detail.path("fileName").asText()).isEqualTo("document-" + documentId + ".txt");
        assertThat(detail.path("fileType").asText()).isEqualTo("TXT");
        assertThat(detail.path("fileSize").asLong()).isEqualTo(100);
        assertThat(detail.path("parseStatus").asText()).isEqualTo(parse);
        assertThat(detail.path("createdAt").asText()).isNotBlank();
        assertThat(detail.path("updatedAt").asText()).isNotBlank();
        assertThat(detail.toString()).doesNotContain("userId", "storageBucket", "storageObjectKey", "parseError",
                "vectorizationError", "contentHash", "vectorId", "internal-", "private-fixture");
    }

    private String token(long userId) {
        AppUser user = new AppUser();
        user.setId(userId);
        return jwt.issueAccessToken(user).value();
    }

    private ResponseEntity<JsonNode> get(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return http.exchange("/api/v1" + path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private static String listPath(long id) { return "/knowledge-bases/" + id + "/documents"; }
    private static String detailPath(long id) { return "/documents/" + id; }

    private static JsonNode data(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).as("HTTP response: %s", response.getBody()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("OK");
        return response.getBody().path("data");
    }

    private static void assertError(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).as("HTTP response: %s", response.getBody()).isEqualTo(status);
    }

    private static JsonNode item(JsonNode page, long id) {
        return StreamSupport.stream(page.path("items").spliterator(), false)
                .filter(row -> row.path("id").asText().equals(String.valueOf(id)))
                .findFirst().orElseThrow(() -> new AssertionError("Missing item " + id + " in " + page));
    }

    private static List<Long> ids(JsonNode page) {
        return StreamSupport.stream(page.path("items").spliterator(), false)
                .map(row -> row.path("id").asLong()).toList();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must identify a disposable database");
        return value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QueryConfiguration {
        @Bean ReadQueryProbe readinessQueryProbe() { return new ReadQueryProbe(); }
    }

    static class QueryGate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    /** Pauses at a real SQL boundary after metadata/page reads; never fakes a query result. */
    @Intercepts({
            @Signature(type = Executor.class, method = "query", args = {
                    MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
    })
    static class ReadQueryProbe implements Interceptor {
        final List<List<Long>> batches = new CopyOnWriteArrayList<>();
        final List<String> knowledgeSql = new CopyOnWriteArrayList<>();
        private final AtomicReference<QueryGate> nextGate = new AtomicReference<>();
        private volatile QueryGate activeGate;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (invocation.getTarget() instanceof StatementHandler statement) {
                String sql = statement.getBoundSql().getSql().toLowerCase();
                if (sql.startsWith("select") && (sql.contains("knowledge_document") || sql.contains("knowledge_base"))) {
                    knowledgeSql.add(sql);
                }
            } else if (((MappedStatement) invocation.getArgs()[0]).getId().equals(AGGREGATE)) {
                assertThat(((Executor) invocation.getTarget()).getTransaction().getConnection().getTransactionIsolation())
                        .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                Map<?, ?> params = (Map<?, ?>) invocation.getArgs()[1];
                List<Long> ids = new ArrayList<>();
                for (Object id : (List<?>) params.get("documentIds")) ids.add(((Number) id).longValue());
                batches.add(List.copyOf(ids));
                QueryGate gate = nextGate.getAndSet(null);
                if (gate != null) {
                    gate.entered.countDown();
                    if (!gate.release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Concurrent writer did not release aggregate");
                }
            }
            return invocation.proceed();
        }

        QueryGate pauseNextAggregate() {
            QueryGate gate = new QueryGate();
            activeGate = gate;
            assertThat(nextGate.compareAndSet(null, gate)).isTrue();
            return gate;
        }

        void release() {
            if (activeGate != null) activeGate.release.countDown();
            QueryGate pending = nextGate.getAndSet(null);
            if (pending != null) pending.release.countDown();
        }

        void reset() {
            release();
            batches.clear();
            knowledgeSql.clear();
        }
    }
}
