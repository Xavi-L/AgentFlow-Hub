package com.agentflow.acceptance;

import com.agentflow.AgentFlowApplication;
import com.agentflow.agent.engine.AgentDecisionResponseSchema;
import com.agentflow.agent.engine.TaskExecutionProperties;
import com.agentflow.agent.engine.TaskPromptBuilder;
import com.agentflow.agent.engine.TaskTokenEstimator;
import com.agentflow.agent.rag.SnapshotRagResult;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.snapshot.AgentTaskSnapshotResolver;
import com.agentflow.agent.task.execution.TaskExecutionRequest;
import com.agentflow.demo.service.DemoOrderService;
import com.agentflow.demo.service.DemoPaymentLogService;
import com.agentflow.infra.llm.LlmChatRequest;
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
import com.agentflow.tool.BuiltinToolHandler;
import com.agentflow.tool.OrderQueryToolHandler;
import com.agentflow.tool.PaymentLogQueryToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test-source-only V48 faults around the real JWT, task creation/dispatch, snapshot Engine,
 * RAG, ToolRuntime, demo services, recorder and PostgreSQL. No test HTTP API is installed.
 * One disposable database and control directory belong to exactly one acceptance run.
 */
public final class V48FailureRecoveryFixture {
    public static final long OWNER = 480000000000000001L;
    private static final long KB = 480000000000000005L;
    private static final long DOCUMENT = 480000000000000006L;
    private static final long CHUNK = 480000000000000007L;
    private static final long ORDER_TOOL = 270000000000000001L;
    private static final long PAYMENT_TOOL = 280000000000000001L;
    private static final String MODEL = "v48-controlled-model";
    private static final String VECTOR = "48000000-0000-0000-0000-000000000007";
    private static final String CONTENT = "Payment timeout requires checking the order and payment logs.";
    private static final String FINISH = "{\"type\":\"FINISH\",\"answerPlan\":\"Explain recorded facts with available citations\"}";
    private static final String USERNAME = "v48-browser";
    private static final String PASSWORD = "V48-browser-test!";
    private static final List<String> CASE_IDS = List.of(
            "F01_JSON", "F01_SHAPE", "F02", "F03", "F04", "F05_EMBED", "F05_VECTOR",
            "F06_UNKNOWN", "F06_MALFORMED", "B01_PRE", "B01_OVER", "B02", "B03",
            "C01", "C02", "C03", "R01", "R02", "R03", "R04_SUCCESS", "R04_FAILED", "R05");

    private V48FailureRecoveryFixture() { }

    public static void main(String[] args) {
        if (!System.getProperty("spring.datasource.url", "").matches(
                "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agentflow_v48_browser")) {
            throw new IllegalArgumentException("V48 requires its disposable loopback agentflow_v48_browser database");
        }
        controlDirectory();
        SpringApplication.run(new Class<?>[] {AgentFlowApplication.class, ControlledProviders.class}, args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ControlledProviders {
        @Bean
        FixtureState failureRecoveryState(JdbcTemplate jdbc, ObjectMapper json, TaskExecutionProperties properties) {
            return new FixtureState(jdbc, json, properties);
        }

        @Bean @Primary
        LlmGateway failureRecoveryLlm(FixtureState state) {
            return state::chat;
        }

        @Bean @Primary
        EmbeddingGateway failureRecoveryEmbeddings(FixtureState state) {
            return request -> state.embed(request.content());
        }

        @Bean @Primary
        VectorStoreGateway failureRecoveryVectors(FixtureState state) {
            return new VectorStoreGateway() {
                @Override public void upsert(VectorStoreRecord record) {
                    throw new UnsupportedOperationException("V48 pre-seeds READY knowledge; no vector writes are expected");
                }
                @Override public void deleteByDocumentScope(VectorDocumentScope scope) {
                    throw new UnsupportedOperationException("V48 does not delete knowledge");
                }
                @Override public List<VectorSearchHit> search(VectorSearchRequest request) {
                    return state.search(request);
                }
            };
        }

        @Bean @Primary
        OrderQueryToolHandler failureRecoveryOrderHandler(
                DemoOrderService orders, ObjectMapper json, FixtureState state) {
            return new OrderQueryToolHandler(orders, json) {
                @Override public HandlerResult execute(JsonNode arguments) {
                    return state.handler("order_query", () -> super.execute(arguments));
                }
            };
        }

        @Bean @Primary
        PaymentLogQueryToolHandler failureRecoveryPaymentHandler(
                DemoPaymentLogService payments, ObjectMapper json, FixtureState state) {
            return new PaymentLogQueryToolHandler(payments, json) {
                @Override public HandlerResult execute(JsonNode arguments) {
                    return state.handler("payment_log_query", () -> super.execute(arguments));
                }
            };
        }

        @Bean
        ApplicationRunner seedFailureRecoveryFixture(FixtureState state, PasswordEncoder encoder) {
            return args -> state.seed(encoder);
        }
    }

    /** File telemetry is independent of the production recorder; it never writes task rows. */
    static final class FixtureState {
        private final JdbcTemplate jdbc;
        private final ObjectMapper json;
        private final TaskExecutionProperties properties;
        private final Map<String, CaseSpec> cases = new LinkedHashMap<>();
        private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
        private final Map<String, String> taskIds = new ConcurrentHashMap<>();
        private final ThreadLocal<String> retrievalCase = new ThreadLocal<>();
        private final Path control = controlDirectory();

        FixtureState(JdbcTemplate jdbc, ObjectMapper json, TaskExecutionProperties properties) {
            this.jdbc = jdbc;
            this.json = json;
            this.properties = properties;
            for (int i = 0; i < CASE_IDS.size(); i++) {
                String caseId = CASE_IDS.get(i);
                cases.put(caseId, specification(caseId, 480000000000000100L + i));
            }
        }

        void seed(PasswordEncoder encoder) throws IOException {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class) != 0L
                    || Files.exists(control.resolve("manifest.json")) || Files.exists(control.resolve("cases"))) {
                throw new IllegalStateException("V48 requires a fresh disposable database and unused control directory");
            }
            Files.createDirectories(control);
            jdbc.update("INSERT INTO app_user(id,username,email,password_hash,display_name) VALUES (?,?,?,?,?)",
                    OWNER, USERNAME, "v48-browser@example.test", encoder.encode(PASSWORD), "V48 Browser");
            jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,'V48 payment evidence')", KB, OWNER);
            jdbc.update("""
                    INSERT INTO knowledge_document(id,user_id,knowledge_base_id,file_name,file_type,mime_type,file_size,
                      storage_bucket,storage_object_key,parse_status,vector_generation)
                    VALUES (?,?,?,'payment.txt','TXT','text/plain',100,'test','v48-ready-payment','COMPLETED',1)
                    """, DOCUMENT, OWNER, KB);
            jdbc.update("""
                    INSERT INTO knowledge_chunk(id,user_id,knowledge_base_id,document_id,chunk_index,content,char_count,
                      token_count,vectorization_status,content_hash,vector_id,vector_generation,chunk_strategy_version)
                    VALUES (?,?,?,?,0,?,?,15,'COMPLETED',?,?,1,'structured-token-v1')
                    """, CHUNK, OWNER, KB, DOCUMENT, CONTENT, CONTENT.length(),
                    ChunkVectorIdentityFactory.contentHash(CONTENT), VECTOR);
            ObjectNode manifest = json.createObjectNode().put("schemaVersion", "v48-failure-recovery-v1")
                    .put("ownerId", Long.toString(OWNER)).put("username", USERNAME).put("password", PASSWORD)
                    .put("knowledgeBaseId", Long.toString(KB)).put("documentId", Long.toString(DOCUMENT))
                    .put("chunkId", Long.toString(CHUNK)).put("model", MODEL);
            ObjectNode manifestCases = manifest.putObject("cases");
            int index = 0;
            for (CaseSpec spec : cases.values()) {
                Files.createDirectories(caseDirectory(spec.id()));
                jdbc.update("""
                        INSERT INTO agent_app(id,user_id,name,description,system_prompt,model_provider,model_name,
                          max_steps,max_tool_calls,max_tokens,timeout_seconds)
                        VALUES (?,?,?,?,?,'openai-compatible',?,?,?,?,?)
                        """, spec.agentId(), OWNER, spec.agentName(), "V48 controlled case " + spec.id(),
                        "Explain recorded payment facts. This Agent is used only for the bounded V48 acceptance case.",
                        MODEL, spec.maxSteps(), spec.maxToolCalls(), spec.maxTokens(), spec.timeoutSeconds());
                long binding = 480000000000001000L + index++ * 3L;
                jdbc.update("INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id) VALUES (?,?,?,?)",
                        binding, OWNER, spec.agentId(), KB);
                jdbc.update("INSERT INTO agent_tool_binding(id,user_id,agent_id,tool_id) VALUES (?,?,?,?),(?,?,?,?)",
                        binding + 1, OWNER, spec.agentId(), ORDER_TOOL,
                        binding + 2, OWNER, spec.agentId(), PAYMENT_TOOL);
                ObjectNode value = manifestCases.putObject(spec.id()).put("agentId", Long.toString(spec.agentId()))
                        .put("agentName", spec.agentName()).put("userInput", spec.userInput())
                        .put("status", spec.status()).put("reason", spec.reason())
                        .put("decisions", spec.decisions()).put("tools", spec.tools())
                        .put("handlers", spec.handlers()).put("finals", spec.finals())
                        .put("maxSteps", spec.maxSteps()).put("maxToolCalls", spec.maxToolCalls())
                        .put("maxTokens", spec.maxTokens()).put("timeoutSeconds", spec.timeoutSeconds())
                        .put("knowledgeBaseId", Long.toString(KB)).put("documentId", Long.toString(DOCUMENT))
                        .put("chunkId", Long.toString(CHUNK));
                if (spec.errorCode() == null) value.putNull("errorCode"); else value.put("errorCode", spec.errorCode());
                if (spec.gate() == null) value.putNull("gate"); else value.put("gate", spec.gate());
                if (spec.answer() == null) value.putNull("answer"); else value.put("answer", spec.answer());
            }
            Files.writeString(control.resolve("manifest.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(control.resolve("manifest.json"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Files.writeString(control.resolve("failure-recovery-ready"), "V48\n", StandardOpenOption.CREATE_NEW);
            System.out.println("V48_FAILURE_RECOVERY_READY cases=" + cases.size() + " username=" + USERNAME);
        }

        LlmChatResult chat(LlmChatRequest request) {
            assertNoTransaction();
            JsonNode payload = readJson(request.messages().getLast().content());
            CaseSpec spec = caseForInput(payload.path("userTask").asText());
            String type = payload.has("answerPlan") ? "FINAL_GENERATION" : "DECISION";
            int ordinal = next(spec.id(), type);
            String callId = spec.id() + ":" + type + ":" + ordinal;
            int inputEstimate = TaskTokenEstimator.inputTokens(request.messages())
                    + (request.responseSchema() == null ? 0
                    : TaskTokenEstimator.textTokens(json.valueToTree(request.responseSchema()).toString()));
            ObjectNode metadata = json.createObjectNode().put("type", type).put("ordinal", ordinal)
                    .put("maxOutputTokens", request.maxOutputTokens()).put("inputEstimate", inputEstimate);
            emit(spec.id(), "llm.start", type, "ENTER", callId, metadata);
            boolean ended = false;
            try {
                if (!MODEL.equals(request.modelName())) throw new IllegalStateException("Unexpected V48 model");
                int expected = "DECISION".equals(type) ? spec.decisions() : spec.finals();
                if (ordinal > expected) throw new IllegalStateException("Unexpected extra " + type + " call for " + spec.id());
                String stage = "DECISION".equals(type) ? "decision" : "final";
                if (stage.equals(spec.gate())) awaitGate(spec, stage, callId);
                String response = response(spec, type, ordinal);
                LlmTokenUsage usage = spec.id().equals("B01_OVER")
                        ? LlmTokenUsage.known(50000, 20, 50020) : LlmTokenUsage.known(10, 5, 15);
                emit(spec.id(), "llm.end", type, "EXIT", callId, metadata.deepCopy()
                        .put("responseAvailable", true).put("inputTokens", usage.inputTokens())
                        .put("outputTokens", usage.outputTokens()).put("totalTokens", usage.totalTokens()));
                ended = true;
                return new LlmChatResult(response, MODEL, "stop", usage, callId, 1);
            } finally {
                if (!ended) emit(spec.id(), "llm.end", type, "EXIT", callId,
                        metadata.deepCopy().put("responseAvailable", false).put("error", "CONTROLLED_CALL_ABORTED"));
                finishGate(spec, "DECISION".equals(type) ? "decision" : "final", callId);
            }
        }

        EmbeddingVector embed(String input) {
            assertNoTransaction();
            CaseSpec spec = caseForInput(input);
            retrievalCase.set(spec.id());
            int ordinal = next(spec.id(), "EMBEDDING");
            String callId = spec.id() + ":EMBEDDING:" + ordinal;
            emit(spec.id(), "embedding.start", "EMBEDDING", "ENTER", callId, null);
            boolean success = false;
            try {
                if (ordinal != 1) throw new IllegalStateException("Unexpected extra embedding call");
                if (spec.id().equals("F05_EMBED")) throw new IllegalStateException("Controlled V48 embedding failure");
                success = true;
                return new EmbeddingVector(Collections.nCopies(1024, 0.1f));
            } finally {
                emit(spec.id(), "embedding.end", "EMBEDDING", "EXIT", callId,
                        json.createObjectNode().put("success", success));
            }
        }

        List<VectorSearchHit> search(VectorSearchRequest request) {
            assertNoTransaction();
            String caseId = retrievalCase.get();
            CaseSpec spec = requiredCase(caseId);
            int ordinal = next(caseId, "VECTOR");
            String callId = caseId + ":VECTOR:" + ordinal;
            emit(caseId, "vector.start", "VECTOR", "ENTER", callId, null);
            boolean success = false;
            int hitCount = 0;
            try {
                if (ordinal != 1 || request.userId() != OWNER || request.knowledgeBaseId() != KB
                        || !request.documents().equals(List.of(new VectorSearchRequest.DocumentGeneration(DOCUMENT, 1)))) {
                    throw new IllegalStateException("Unexpected V48 retrieval snapshot or repeated vector call");
                }
                if (caseId.equals("F05_VECTOR")) throw new IllegalStateException("Controlled V48 vector failure");
                if (caseId.equals("B01_PRE")) recordPreflight(spec);
                if (caseId.equals("C01") || caseId.equals("B01_PRE")) {
                    success = true;
                    return List.of();
                }
                hitCount = 1;
                success = true;
                return List.of(new VectorSearchHit(VECTOR, CHUNK, 0.9, ChunkVectorIdentityFactory.contentHash(CONTENT)));
            } finally {
                emit(caseId, "vector.end", "VECTOR", "EXIT", callId,
                        json.createObjectNode().put("success", success).put("hitCount", hitCount));
                retrievalCase.remove();
            }
        }

        BuiltinToolHandler.HandlerResult handler(String toolCode, Supplier<BuiltinToolHandler.HandlerResult> normal) {
            assertNoTransaction();
            // ToolRuntime already names its worker with the task ID. Read it, rather than
            // change tool arguments or replace the production Runtime/router for correlation.
            String threadName = Thread.currentThread().getName();
            if (!threadName.matches("agent-tool-[0-9]+")) throw new IllegalStateException("Expected a task-scoped tool worker");
            long taskId = Long.parseLong(threadName.substring("agent-tool-".length()));
            String input = jdbc.queryForObject("SELECT user_input FROM agent_task WHERE id=? AND user_id=?",
                    String.class, taskId, OWNER);
            CaseSpec spec = caseForInput(input);
            if (!Long.toString(taskId).equals(taskIds.get(spec.id()))) throw new IllegalStateException("Tool task correlation changed");
            int ordinal = next(spec.id(), "TOOL_HANDLER");
            String callId = spec.id() + ":TOOL_HANDLER:" + ordinal;
            ObjectNode metadata = json.createObjectNode().put("toolCode", toolCode).put("ordinal", ordinal);
            emit(spec.id(), "handler.enter", "TOOL_HANDLER", "ENTER", callId, metadata);
            boolean success = false;
            try {
                if (ordinal > spec.handlers()) throw new IllegalStateException("Unexpected extra handler call");
                if (spec.id().equals("F04")) throw new IllegalStateException("Controlled V48 handler failure");
                BuiltinToolHandler.HandlerResult result = normal.get();
                success = true;
                return result;
            } finally {
                emit(spec.id(), "handler.exit", "TOOL_HANDLER", "EXIT", callId, metadata.put("success", success));
            }
        }

        private String response(CaseSpec spec, String type, int ordinal) {
            if (type.equals("FINAL_GENERATION")) {
                return switch (spec.id()) {
                    case "F06_UNKNOWN" -> "Unsupported payment evidence [S999].";
                    case "F06_MALFORMED" -> "Malformed payment evidence [[S1]].";
                    case "B02", "B03" -> "Late result must never be published [S1].";
                    default -> {
                        if (spec.answer() == null) throw new IllegalStateException("No final response scripted for " + spec.id());
                        yield spec.answer();
                    }
                };
            }
            return switch (spec.id()) {
                case "F01_JSON", "R01", "R02", "R03", "R04_FAILED" -> "not valid decision JSON";
                case "F01_SHAPE" -> "{\"type\":\"UNSUPPORTED_DECISION\",\"answerPlan\":\"Reject this object\"}";
                case "F02", "F04", "C03" -> tool("order_query", false);
                case "F03" -> tool("order_query", true);
                case "C01" -> ordinal == 1 ? tool("order_query", false) : FINISH;
                case "C02" -> tool(ordinal <= 2 ? "order_query" : "payment_log_query", false);
                case "F06_UNKNOWN", "F06_MALFORMED", "B01_OVER", "B02", "B03", "R04_SUCCESS", "R05" -> FINISH;
                default -> throw new IllegalStateException("No decision scripted for " + spec.id());
            };
        }

        private void awaitGate(CaseSpec spec, String stage, String callId) {
            Path directory = caseDirectory(spec.id());
            writeSignal(directory.resolve(stage + ".entered"), callId);
            emit(spec.id(), "gate.entered", "GATE", "ENTER", callId, json.createObjectNode().put("gate", stage));
            long expires = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            boolean interruptRecorded = false;
            while (!Files.exists(directory.resolve(stage + ".release"))) {
                if (System.nanoTime() >= expires) throw new IllegalStateException("V48 gate deadline exceeded for " + spec.id());
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    if (!interruptRecorded) {
                        emit(spec.id(), "gate.interrupted", "GATE", "INTERRUPTED", callId,
                                json.createObjectNode().put("gate", stage));
                        interruptRecorded = true;
                    }
                    if (!spec.id().equals("B02") && !spec.id().equals("B03")) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("V48 gate interrupted", interrupted);
                    }
                    // Deliberately emulate an uncooperative provider until the test releases
                    // its late result. Future cancellation must keep that result unpublished.
                }
            }
        }

        private void finishGate(CaseSpec spec, String stage, String callId) {
            if (stage.equals(spec.gate()) && Files.exists(caseDirectory(spec.id()).resolve(stage + ".entered"))) {
                Thread worker = Thread.currentThread();
                Thread.ofVirtual().name("v48-gate-exit-" + spec.id()).start(() -> {
                    try {
                        // A finally-block signal alone precedes the provider's actual return.
                        // Join its production FutureTask worker so the browser's second read
                        // happens after even an uncooperative late provider really exited.
                        worker.join(5_000);
                        if (worker.isAlive()) {
                            emit(spec.id(), "gate.exit_failed", "GATE", "FAILED", callId,
                                    json.createObjectNode().put("gate", stage).put("error", "WORKER_EXIT_TIMEOUT"));
                            writeSignal(caseDirectory(spec.id()).resolve(stage + ".exit-failed"), "WORKER_EXIT_TIMEOUT");
                            throw new IllegalStateException("V48 provider worker did not exit");
                        }
                        emit(spec.id(), "gate.exited", "GATE", "EXIT", callId,
                                json.createObjectNode().put("gate", stage));
                        writeSignal(caseDirectory(spec.id()).resolve(stage + ".exited"), callId);
                    } catch (InterruptedException interrupted) {
                        emit(spec.id(), "gate.exit_failed", "GATE", "FAILED", callId,
                                json.createObjectNode().put("gate", stage).put("error", "EXIT_OBSERVER_INTERRUPTED"));
                        writeSignal(caseDirectory(spec.id()).resolve(stage + ".exit-failed"), "EXIT_OBSERVER_INTERRUPTED");
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("V48 gate exit observer interrupted", interrupted);
                    }
                });
            }
        }

        private void recordPreflight(CaseSpec spec) {
            Map<String, Object> task = taskRow(spec);
            AgentTaskExecutionSnapshot snapshot;
            try {
                snapshot = json.readValue(task.get("execution_snapshot").toString(), AgentTaskExecutionSnapshot.class);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read the frozen V48 budget", error);
            }
            int reserve = ((Number) task.get("reserved_final_tokens")).intValue();
            TaskExecutionRequest request = new TaskExecutionRequest(((Number) task.get("id")).longValue(), OWNER,
                    spec.agentId(), task.get("user_input").toString(), snapshot, reserve,
                    Instant.now().plusSeconds(spec.timeoutSeconds()), () -> false);
            SnapshotRagResult empty = new SnapshotRagResult("", List.of(), 0, 0,
                    AgentTaskSnapshotResolver.EMBEDDING_PROFILE_CODE);
            TaskPromptBuilder prompts = new TaskPromptBuilder(json);
            int input = TaskTokenEstimator.inputTokens(prompts.decision(request, empty, List.of(),
                    snapshot.agent().maxDecisionTurns(), snapshot.agent().maxToolCalls()));
            if (properties.isDecisionJsonSchemaEnabled()) input += TaskTokenEstimator.textTokens(json.valueToTree(
                    AgentDecisionResponseSchema.fromTools(json, snapshot.tools())).toString());
            int finalInput = TaskTokenEstimator.inputTokens(prompts.finalAnswer(request, empty, List.of(),
                    "Answer from the available evidence and state any limitations"));
            long available = (long) snapshot.agent().maxTotalTokens() - input - finalInput - reserve;
            if (available >= 1) throw new IllegalStateException("V48 B01_PRE does not exhaust the frozen budget");
            emit(spec.id(), "budget.preflight", "BUDGET", "CHECK", spec.id() + ":BUDGET:1",
                    json.createObjectNode().put("inputEstimate", input).put("finalInputEstimate", finalInput)
                            .put("reservedFinalTokens", reserve).put("maxTotalTokens", snapshot.agent().maxTotalTokens())
                            .put("availableDecisionOutput", available));
        }

        private CaseSpec caseForInput(String input) {
            if (input == null || !input.startsWith("V48:")) throw new IllegalArgumentException("V48 case prefix is required");
            int end = input.indexOf(':', 4);
            if (end < 0) throw new IllegalArgumentException("V48 case prefix is incomplete");
            CaseSpec spec = requiredCase(input.substring(4, end));
            if (!spec.userInput().equals(input)) throw new IllegalArgumentException("V48 task input differs from its case manifest");
            taskIds.computeIfAbsent(spec.id(), ignored -> taskRow(spec).get("id").toString());
            return spec;
        }

        private Map<String, Object> taskRow(CaseSpec spec) {
            List<Map<String, Object>> tasks = jdbc.queryForList(
                    "SELECT id,user_input,execution_snapshot,reserved_final_tokens FROM agent_task WHERE user_id=? AND agent_id=?",
                    OWNER, spec.agentId());
            if (tasks.size() != 1 || !spec.userInput().equals(tasks.getFirst().get("user_input"))) {
                throw new IllegalStateException("V48 case must have exactly one task with its manifest input: " + spec.id());
            }
            return tasks.getFirst();
        }

        private CaseSpec requiredCase(String id) {
            CaseSpec spec = cases.get(id);
            if (spec == null) throw new IllegalArgumentException("Unknown V48 case " + id);
            return spec;
        }

        private int next(String id, String kind) {
            return counters.computeIfAbsent(id + ":" + kind, ignored -> new AtomicInteger()).incrementAndGet();
        }

        private synchronized void emit(String caseId, String event, String kind, String stage,
                String callId, ObjectNode details) {
            ObjectNode record = json.createObjectNode().put("event", event).put("kind", kind).put("stage", stage)
                    .put("caseId", caseId).put("at", Instant.now().toString()).put("callId", callId);
            String taskId = taskIds.get(caseId);
            if (taskId != null) record.put("taskId", taskId);
            if (details != null) record.setAll(details);
            try {
                Files.writeString(caseDirectory(caseId).resolve("calls.jsonl"), json.writeValueAsString(record) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException error) {
                throw new IllegalStateException("V48 independent telemetry write failed", error);
            }
        }

        private JsonNode readJson(String content) {
            try {
                return json.readTree(content);
            } catch (IOException error) {
                throw new IllegalStateException("V48 expected a production prompt payload", error);
            }
        }

        private Path caseDirectory(String caseId) { return control.resolve("cases").resolve(caseId); }
    }

    private static CaseSpec specification(String id, long agentId) {
        String status = "FAILED", reason = "SYSTEM_ERROR", error = "AGENT_INVALID_DECISION", gate = null;
        int decisions = 1, tools = 0, handlers = 0, finals = 0;
        int steps = 6, maxTools = 4, tokens = 50000, timeout = 120;
        String answer = null;
        switch (id) {
            case "F01_JSON", "F01_SHAPE" -> { }
            case "F02" -> { decisions = 3; tools = handlers = 1; error = "AGENT_DUPLICATE_TOOL_LOOP"; }
            case "F03" -> { tools = 1; error = "TOOL_ARGUMENT_INVALID"; }
            case "F04" -> { tools = handlers = 1; error = "TOOL_EXECUTION_FAILED"; }
            case "F05_EMBED", "F05_VECTOR" -> { decisions = 0; error = "RAG_RETRIEVAL_FAILED"; }
            case "F06_UNKNOWN", "F06_MALFORMED" -> { finals = 1; error = "AGENT_INVALID_CITATION"; }
            case "B01_PRE" -> { decisions = 0; tokens = 256; reason = "TOKEN_BUDGET_EXHAUSTED"; error = "AGENT_TOKEN_BUDGET_EXHAUSTED"; }
            case "B01_OVER" -> { reason = "TOKEN_BUDGET_EXHAUSTED"; error = "AGENT_TOKEN_BUDGET_EXHAUSTED"; }
            case "B02" -> { status = "TIMED_OUT"; reason = "DEADLINE_EXCEEDED"; error = null; finals = 1; gate = "final"; timeout = 10; }
            case "B03" -> { status = "CANCELLED"; reason = "USER_CANCELLED"; error = null; finals = 1; gate = "final"; }
            case "C01" -> { status = "COMPLETED"; reason = "ANSWERED"; error = null; decisions = 2; tools = handlers = finals = 1; }
            case "C02" -> { status = "COMPLETED"; reason = "MAX_DECISION_TURNS"; error = null; decisions = 4; tools = handlers = 2; finals = 1; steps = 4; maxTools = 3; }
            case "C03" -> { status = "COMPLETED"; reason = "MAX_TOOL_CALLS"; error = null; tools = handlers = finals = 1; steps = 3; maxTools = 1; }
            case "R01", "R02", "R03", "R04_FAILED" -> gate = "decision";
            case "R04_SUCCESS", "R05" -> { status = "COMPLETED"; reason = "ANSWERED"; error = null; finals = 1; gate = "final"; }
            default -> throw new IllegalArgumentException("Unspecified V48 case " + id);
        }
        if (status.equals("COMPLETED")) {
            answer = id.equals("C01")
                    ? "订单 order_1024 的工具记录显示支付失败。本次检索没有可用知识证据。"
                    : "订单 order_1024 的支付问题应结合订单及支付日志核对。[S1]\nV48 " + id + " controlled evidence.";
        }
        return new CaseSpec(id, agentId, status, reason, error, decisions, tools, handlers, finals,
                gate, steps, maxTools, tokens, timeout, answer);
    }

    private record CaseSpec(String id, long agentId, String status, String reason, String errorCode,
            int decisions, int tools, int handlers, int finals, String gate, int maxSteps, int maxToolCalls,
            int maxTokens, int timeoutSeconds, String answer) {
        String agentName() { return "V48 " + id; }
        String userInput() { return "V48:" + id + ":Explain why order_1024 payment failed."; }
    }

    private static String tool(String code, boolean invalidArguments) {
        return "{\"type\":\"CALL_TOOL\",\"toolCode\":\"" + code + "\",\"arguments\":"
                + (invalidArguments ? "{}" : "{\"orderNo\":\"order_1024\"}")
                + ",\"reason\":\"Check recorded payment facts\"}";
    }

    private static Path controlDirectory() {
        String configured = System.getProperty("v48.control-dir");
        if (configured == null || configured.isBlank()) throw new IllegalArgumentException("v48.control-dir is required");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static void writeSignal(Path path, String value) {
        try {
            Files.writeString(path, value + "\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException error) {
            throw new IllegalStateException("V48 gate signal write failed", error);
        }
    }

    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("External provider/handler boundary must run outside a database transaction");
        }
    }
}
