package com.agentflow.acceptance;

import com.agentflow.AgentFlowApplication;
import com.agentflow.agent.task.sse.TaskSseService;
import com.agentflow.infra.llm.LlmChatResult;
import com.agentflow.infra.llm.LlmGateway;
import com.agentflow.infra.llm.LlmTokenUsage;
import com.agentflow.knowledge.vector.ChunkVectorIdentityFactory;
import com.agentflow.knowledge.vector.EmbeddingGateway;
import com.agentflow.knowledge.vector.EmbeddingVector;
import com.agentflow.knowledge.vector.VectorDocumentScope;
import com.agentflow.knowledge.vector.VectorSearchHit;
import com.agentflow.knowledge.vector.VectorSearchRequest;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.knowledge.vector.VectorStoreRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test-source-only browser fixture: real HTTP/JWT, Runner, snapshot execution, RAG,
 * ToolRuntime, recorder and PostgreSQL. Only model and vector boundaries are controlled.
 * A local release file gates the second decision; no test HTTP endpoint is introduced.
 * Launch through scripts/v43-browser-backend.sh against its fresh disposable cluster.
 */
public final class V43BrowserFixture {
    // Intentionally exceed Number.MAX_SAFE_INTEGER to exercise browser ID preservation.
    public static final long OWNER = 430000000000000001L;
    public static final long AGENT = 430000000000000003L;
    private static final long KB = 430000000000000005L;
    private static final long DOCUMENT = 430000000000000006L;
    private static final long CHUNK = 430000000000000007L;
    private static final long ORDER_TOOL = 270000000000000001L;
    private static final String VECTOR = "43000000-0000-0000-0000-000000000007";
    private static final String CONTENT = "Payment timeout requires checking the order and payment logs.";
    public static final String ANSWER = "订单 order_1024 支付超时，请核对订单及支付日志。[S1]\n"
            + "This answer uses the persisted knowledge citation and recorded order_query result.";

    private V43BrowserFixture() { }

    public static void main(String[] args) {
        String url = System.getProperty("spring.datasource.url", "");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agentflow_v43_browser")) {
            throw new IllegalArgumentException("V43 fixture requires its disposable loopback agentflow_v43_browser database");
        }
        SpringApplication.run(new Class<?>[] {AgentFlowApplication.class, ControlledProviders.class}, args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ControlledProviders {
        @Bean(destroyMethod = "shutdownNow")
        ScheduledExecutorService browserConnectionProbe(TaskSseService sse) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("v43-browser-sse-probe").factory());
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    Files.writeString(controlDirectory().resolve("sse-active"), Integer.toString(sse.activeConnectionCount()));
                } catch (java.io.IOException error) {
                    throw new IllegalStateException("V43 connection probe failed", error);
                }
            }, 0, 100, TimeUnit.MILLISECONDS);
            return scheduler;
        }

        @Bean
        @Primary
        LlmGateway browserLlm(ObjectMapper json) {
            return request -> {
                assertNoTransaction();
                try {
                    var payload = json.readTree(request.messages().getLast().content());
                    String content;
                    if (payload.has("answerPlan")) {
                        content = ANSWER;
                    } else if (payload.path("observations").isEmpty()) {
                        content = "{\"type\":\"CALL_TOOL\",\"toolCode\":\"order_query\","
                                + "\"arguments\":{\"orderNo\":\"order_1024\"},\"reason\":\"Check payment facts\"}";
                    } else {
                        Path control = controlDirectory();
                        Files.writeString(control.resolve("model-waiting"), payload.path("userTask").asText());
                        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
                        while (!Files.exists(control.resolve("release-model"))) {
                            if (System.nanoTime() > deadline) throw new IllegalStateException("V43 model gate timed out");
                            Thread.sleep(50);
                        }
                        content = "{\"type\":\"FINISH\",\"answerPlan\":\"Explain recorded payment facts with citation S1\"}";
                    }
                    return new LlmChatResult(content, "v43-controlled-model", "stop",
                            LlmTokenUsage.known(10, 5, 15), "v43-local-fixture", 1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("V43 model call interrupted", interrupted);
                } catch (java.io.IOException error) {
                    throw new IllegalStateException("V43 model fixture failed", error);
                }
            };
        }

        @Bean
        @Primary
        EmbeddingGateway browserEmbeddings() {
            return request -> {
                assertNoTransaction();
                return new EmbeddingVector(Collections.nCopies(1024, 0.1f));
            };
        }

        @Bean
        @Primary
        VectorStoreGateway browserVectors() {
            return new VectorStoreGateway() {
                @Override public void upsert(VectorStoreRecord record) {
                    throw new UnsupportedOperationException("V43 does not upload or vectorize knowledge");
                }
                @Override public void deleteByDocumentScope(VectorDocumentScope scope) {
                    throw new UnsupportedOperationException("V43 does not delete knowledge");
                }
                @Override public List<VectorSearchHit> search(VectorSearchRequest request) {
                    assertNoTransaction();
                    if (request.userId() != OWNER || !request.documents().equals(
                            List.of(new VectorSearchRequest.DocumentGeneration(DOCUMENT, 1)))) {
                        throw new IllegalArgumentException("Unexpected V43 retrieval snapshot");
                    }
                    return List.of(new VectorSearchHit(VECTOR, CHUNK, 0.9,
                            ChunkVectorIdentityFactory.contentHash(CONTENT)));
                }
            };
        }

        @Bean
        ApplicationRunner seedBrowserFixture(JdbcTemplate jdbc, PasswordEncoder encoder) {
            return args -> {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class) != 0L) {
                    throw new IllegalStateException("V43 fixture requires a fresh disposable database");
                }
                jdbc.update("INSERT INTO app_user(id,username,email,password_hash,display_name) VALUES (?,?,?,?,?)",
                        OWNER, "v43-browser", "v43-browser@example.test", encoder.encode("V43-browser-test!"), "V43 Browser");
                jdbc.update("""
                        INSERT INTO agent_app(id,user_id,name,description,system_prompt,model_provider,model_name,
                          max_steps,max_tool_calls,max_tokens,timeout_seconds)
                        VALUES (?,?,'Payment investigation','Preconfigured V43 controlled-provider acceptance Agent',
                          'Explain recorded payment facts','openai-compatible','v43-controlled-model',6,4,50000,300)
                        """, AGENT, OWNER);
                jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,'Payment knowledge')", KB, OWNER);
                jdbc.update("""
                        INSERT INTO knowledge_document(id,user_id,knowledge_base_id,file_name,file_type,mime_type,file_size,
                          storage_bucket,storage_object_key,parse_status,vector_generation)
                        VALUES (?,?,?,'payment.txt','TXT','text/plain',100,'test','v43','COMPLETED',1)
                        """, DOCUMENT, OWNER, KB);
                jdbc.update("""
                        INSERT INTO knowledge_chunk(id,user_id,knowledge_base_id,document_id,chunk_index,content,char_count,
                          token_count,vectorization_status,content_hash,vector_id,vector_generation,chunk_strategy_version)
                        VALUES (?,?,?,?,0,?,?,15,'COMPLETED',?,?,1,'structured-token-v1')
                        """, CHUNK, OWNER, KB, DOCUMENT, CONTENT, CONTENT.length(),
                        ChunkVectorIdentityFactory.contentHash(CONTENT), VECTOR);
                jdbc.update("INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id) VALUES (?,?,?,?)",
                        430000000000000010L, OWNER, AGENT, KB);
                jdbc.update("INSERT INTO agent_tool_binding(id,user_id,agent_id,tool_id) VALUES (?,?,?,?)",
                        430000000000000011L, OWNER, AGENT, ORDER_TOOL);
                Files.writeString(controlDirectory().resolve("backend-ready"), Long.toString(AGENT));
                System.out.println("V43_BROWSER_READY agent=" + AGENT + " username=v43-browser");
            };
        }
    }

    private static Path controlDirectory() {
        String value = System.getProperty("v43.control-dir");
        if (value == null || value.isBlank()) throw new IllegalStateException("v43.control-dir is required");
        return Path.of(value);
    }

    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("External provider boundary must run outside a database transaction");
        }
    }
}
