package com.agentflow.acceptance;

import com.agentflow.AgentFlowApplication;
import com.agentflow.agent.task.dispatch.TaskDispatcher;
import com.agentflow.agent.task.execution.TaskExecutionDelegate;
import com.agentflow.agent.task.execution.TaskExecutionOutcome;
import com.agentflow.agent.task.execution.TaskExecutionRequest;
import com.agentflow.agent.task.execution.TaskRunner;
import com.agentflow.agent.task.service.AgentTaskApplicationService;
import com.agentflow.agent.task.service.AgentTaskLifecycleTransactionService;
import com.agentflow.agent.task.service.CreateAgentTaskCommand;
import com.agentflow.agent.trace.LlmCallRecord;
import com.agentflow.demo.service.DemoOrderService;
import com.agentflow.infra.llm.LlmChatResult;
import com.agentflow.infra.llm.LlmGateway;
import com.agentflow.infra.llm.LlmTokenUsage;
import com.agentflow.knowledge.vector.EmbeddingGateway;
import com.agentflow.knowledge.vector.EmbeddingVector;
import com.agentflow.knowledge.vector.ChunkVectorIdentityFactory;
import com.agentflow.knowledge.vector.VectorDocumentScope;
import com.agentflow.knowledge.vector.VectorSearchHit;
import com.agentflow.knowledge.vector.VectorSearchRequest;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.knowledge.vector.VectorStoreRecord;
import com.agentflow.tool.OrderQueryToolHandler;
import com.agentflow.tool.ToolExecutionCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Test-only control points; all tasks enter real HTTP/JWT and the normal dispatcher/Engine. */
public final class V02ATaskRecoveryFixture {
    static final long OWNER = 620000000000000001L;
    static final long OTHER = 620000000000000002L;
    static final long AGENT = 620000000000000100L;
    static final long TOOL = 270000000000000001L;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final Path CONTROL = Path.of(System.getProperty("v02a.control-dir", "/V02A_FIXTURE_NOT_CONFIGURED"));
    static final String LABEL = System.getProperty("v02a.run-label");
    static final HttpClient HTTP = HttpClient.newHttpClient();

    private V02ATaskRecoveryFixture() { }

    public static void main(String[] args) {
        if (System.getProperty("v02a.control-dir") == null) throw new IllegalArgumentException("V02A control directory is required");
        if (!System.getProperty("spring.datasource.url", "").matches(
                "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agentflow_v02a")) {
            throw new IllegalArgumentException("Requires disposable loopback agentflow_v02a database");
        }
        SpringApplication.run(new Class<?>[] {AgentFlowApplication.class, ControlledConfiguration.class}, args);
    }

    static void signal(String name, Object value) {
        try {
            Path file = CONTROL.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(value) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ex) { throw new IllegalStateException("Fixture evidence failed", ex); }
    }

    static synchronized void runnerReceipt(long taskId) {
        try {
            Files.writeString(CONTROL.resolve("runner-receipts.jsonl"), JSON.writeValueAsString(Map.of(
                    "taskId", Long.toString(taskId), "pid", ProcessHandle.current().pid(), "at", Instant.now().toString())) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    static void gate(String name) {
        signal("gates/" + name + ".reached", Map.of("at", Instant.now().toString(), "pid", ProcessHandle.current().pid()));
        while (!Files.exists(CONTROL.resolve("gates/" + name + ".allow"))) {
            try { Thread.sleep(10); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Fixture gate interrupted", ex); }
        }
        signal("gates/" + name + ".continued", Map.of("at", Instant.now().toString()));
    }

    static JsonNode external(String endpoint, JsonNode request) {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                    URI.create(System.getProperty("v02a.external-url") + endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Controlled external HTTP failed");
            return JSON.readTree(response.body());
        } catch (Exception ex) { throw new IllegalStateException("Controlled external boundary interrupted", ex); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ControlledConfiguration {
        @Bean
        static BeanPostProcessor recoveryControlPoints(org.springframework.beans.factory.ObjectProvider<JdbcTemplate> jdbcProvider) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    String type = org.springframework.aop.support.AopUtils.getTargetClass(bean).getSimpleName();
                    if (!(bean instanceof TaskRunner || bean instanceof TaskExecutionDelegate
                            || type.equals("DefaultToolRuntime") || type.equals("ExecutionRecorderTransactionService")
                            || type.equals("TaskRecoveryTransactionService"))) return bean;
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(!(bean instanceof TaskExecutionDelegate));
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        String method = invocation.getMethod().getName();
                        Object[] args = invocation.getArguments();
                        Long taskId = null;
                        if (bean instanceof TaskRunner && method.equals("run")) { taskId = (Long) args[0]; runnerReceipt(taskId); }
                        if (bean instanceof TaskExecutionDelegate && method.equals("execute")) taskId = ((TaskExecutionRequest) args[0]).taskId();
                        if (method.equals("recordLlmCall")) taskId = ((LlmCallRecord) args[0]).step().taskId();
                        if (type.equals("DefaultToolRuntime") && method.equals("execute")) taskId = ((ToolExecutionCommand) args[0]).taskId();
                        if (type.equals("TaskRecoveryTransactionService") && method.equals("recover")) taskId = (Long) args[0];
                        String id = taskId == null ? null : Long.toString(taskId);
                        String spec = id == null ? "" : read(CONTROL.resolve("task-controls/" + id));
                        if (id != null && spec.isEmpty() && !type.equals("TaskRecoveryTransactionService")) {
                            String input = jdbcProvider.getObject().queryForObject("SELECT user_input FROM agent_task WHERE id=?", String.class, taskId);
                            spec = read(CONTROL.resolve("case-controls/" + input));
                        }
                        if (bean instanceof TaskRunner && spec.equals("QUEUED")) gate(id + "-queued");
                        if (bean instanceof TaskExecutionDelegate && spec.equals("CLAIMED")) gate(id + "-claimed");
                        if (type.equals("TaskRecoveryTransactionService") && method.equals("recover")) {
                            signal("recovery/" + LABEL + "-" + id + ".entered", Map.of("taskId", id, "at", Instant.now().toString()));
                        }
                        Object value = invocation.proceed();
                        if (method.equals("recordLlmCall") && spec.equals("FINAL_LOG")
                                && ((LlmCallRecord) args[0]).callType().name().equals("FINAL_GENERATION")) gate(id + "-final-log");
                        if (type.equals("DefaultToolRuntime") && method.equals("execute") && spec.equals("TOOL_LOG")) gate(id + "-tool-log");
                        if (type.equals("TaskRecoveryTransactionService") && method.equals("recover") && Boolean.TRUE.equals(value)) {
                            signal("recovery/" + LABEL + "-" + id + ".committed", Map.of("taskId", id, "at", Instant.now().toString()));
                            if (read(CONTROL.resolve("recovery-after-commit")).equals(id)) gate(LABEL + "-after-commit");
                            if (read(CONTROL.resolve("recovery-lost-response")).equals(id)) {
                                throw new org.springframework.dao.DataAccessResourceFailureException("V02A simulated receipt loss after committed recovery");
                            }
                        }
                        return value;
                    });
                    return proxy.getProxy();
                }
            };
        }

        @Bean @Primary
        LlmGateway recoveryLlm(JdbcTemplate jdbc) {
            return request -> {
                JsonNode payload;
                try { payload = JSON.readTree(request.messages().getLast().content()); }
                catch (Exception ex) { throw new IllegalArgumentException(ex); }
                String input = payload.path("userTask").asText();
                String id = jdbc.queryForObject("SELECT id::text FROM agent_task WHERE user_input=?", String.class, input);
                JsonNode result = external("/llm", JSON.createObjectNode().put("taskId", id)
                        .put("case", input).put("type", payload.has("answerPlan") ? "FINAL_GENERATION" : "DECISION"));
                return new LlmChatResult(result.path("content").asText(), "v02a-controlled", "stop",
                        result.path("unknownUsage").asBoolean() ? LlmTokenUsage.unknown() : LlmTokenUsage.known(10, 5, 15),
                        result.path("requestId").asText(), 1);
            };
        }

        @Bean @Primary
        EmbeddingGateway recoveryEmbeddings() { return request -> {
            external("/boundary", JSON.createObjectNode().put("case", request.content()).put("type", "EMBEDDING"));
            return new EmbeddingVector(java.util.Collections.nCopies(1024, 0.1f));
        }; }
        @Bean @Primary
        VectorStoreGateway recoveryVectors(JdbcTemplate jdbc) {
            return new VectorStoreGateway() {
                @Override public void upsert(VectorStoreRecord record) { throw new AssertionError("Vector forbidden"); }
                @Override public void deleteByDocumentScope(VectorDocumentScope scope) { throw new AssertionError("Vector forbidden"); }
                @Override public List<VectorSearchHit> search(VectorSearchRequest request) {
                    String input = jdbc.queryForObject("SELECT user_input FROM agent_task WHERE status='RUNNING' ORDER BY started_at DESC LIMIT 1", String.class);
                    external("/boundary", JSON.createObjectNode().put("case", input).put("type", "VECTOR"));
                    return List.of();
                }
            };
        }

        @Bean @Primary
        OrderQueryToolHandler recoveryOrder(DemoOrderService orders, ObjectMapper mapper) {
            return new OrderQueryToolHandler(orders, mapper) {
                @Override public HandlerResult execute(JsonNode arguments) {
                    JsonNode result = external("/tool", JSON.createObjectNode().put("case", arguments.path("orderNo").asText()));
                    return new HandlerResult("Controlled read-only order result", result);
                }
            };
        }

        @Bean
        ApplicationRunner seedRecoveryFixture(JdbcTemplate jdbc, PasswordEncoder encoder, ApplicationContext context) {
            return args -> {
                if (jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id=?", Integer.class, OWNER) == 0) {
                    jdbc.update("INSERT INTO app_user(id,username,email,password_hash,display_name) VALUES (?,?,?,?,?),(?,?,?,?,?)",
                            OWNER, "v02a-fixture", "v02a@example.test", encoder.encode("V02a-fixture-test!"), "V02A Fixture",
                            OTHER, "v02a-other", "v02a-other@example.test", encoder.encode("V02a-fixture-test!"), "Other owner");
                    jdbc.update("""
                            INSERT INTO agent_app(id,user_id,name,description,system_prompt,model_provider,model_name,
                              max_steps,max_tool_calls,max_tokens,timeout_seconds,model_call_timeout_seconds)
                            VALUES (?,?,'V02A restart acceptance','Controlled process acceptance',
                              'Explain recorded local facts.','openai-compatible','v02a-controlled',8,4,8000,600,600)
                            """, AGENT, OWNER);
                    jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,'V02A ready controlled corpus')", AGENT + 2, OWNER);
                    jdbc.update("""
                            INSERT INTO knowledge_document(id,user_id,knowledge_base_id,file_name,file_type,mime_type,file_size,
                              storage_bucket,storage_object_key,parse_status,vector_generation)
                            VALUES (?,?,?,'v02a.txt','TXT','text/plain',24,'test','v02a-ready','COMPLETED',1)
                            """, AGENT + 3, OWNER, AGENT + 2);
                    jdbc.update("""
                            INSERT INTO knowledge_chunk(id,user_id,knowledge_base_id,document_id,chunk_index,content,char_count,
                              token_count,vectorization_status,content_hash,vector_id,vector_generation,chunk_strategy_version)
                            VALUES (?,?,?,?,0,'Controlled local corpus',23,6,'COMPLETED',?,'62000000-0000-0000-0000-000000000104',1,'structured-token-v1')
                            """, AGENT + 4, OWNER, AGENT + 2, AGENT + 3, ChunkVectorIdentityFactory.contentHash("Controlled local corpus"));
                    jdbc.update("INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id) VALUES (?,?,?,?)", AGENT + 5, OWNER, AGENT, AGENT + 2);
                    jdbc.update("INSERT INTO agent_tool_binding(id,user_id,agent_id,tool_id) VALUES (?,?,?,?)",
                            AGENT + 1, OWNER, AGENT, TOOL);
                }
                signal("processes/" + LABEL + ".ready", Map.of("pid", ProcessHandle.current().pid(), "at", Instant.now().toString()));
                Thread.ofVirtual().name("v02a-test-commands").start(() -> commands(context));
            };
        }
    }

    static String read(Path path) {
        try { return Files.exists(path) ? Files.readString(path).trim() : ""; }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    /** Local file commands only; never installed into production HTTP. */
    static void commands(ApplicationContext context) {
        Path directory = CONTROL.resolve("commands");
        try {
            Files.createDirectories(directory);
            while (true) {
                try (var files = Files.list(directory)) {
                    for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                        Path done = Path.of(path + ".result");
                        if (Files.exists(done)) continue;
                        JsonNode command = JSON.readTree(Files.readString(path));
                        var result = JSON.createObjectNode();
                        try {
                            long task = command.path("taskId").asLong();
                            String action = command.path("action").asText();
                            switch (action) {
                                case "internal-create" -> context.getBean(AgentTaskApplicationService.class).createTask(
                                        new CreateAgentTaskCommand(OWNER, AGENT, command.path("key").asText(), command.path("key").asText()));
                                case "internal-dispatch" -> context.getBean(TaskDispatcher.class).dispatch(task);
                                case "internal-claim" -> context.getBean(AgentTaskLifecycleTransactionService.class).claim(task);
                                case "internal-run" -> context.getBean(TaskRunner.class).run(task);
                                case "internal-cancel" -> context.getBean(AgentTaskLifecycleTransactionService.class).requestCancellation(OWNER, task);
                                case "terminal" -> {
                                    var lifecycle = context.getBean(AgentTaskLifecycleTransactionService.class);
                                    switch (command.path("status").asText()) {
                                        case "COMPLETED" -> lifecycle.complete(task, TaskExecutionOutcome.completed("Controlled fixture terminal preservation"));
                                        case "FAILED" -> lifecycle.fail(task, TaskExecutionOutcome.failed("CONTROLLED_FAILURE", "Controlled terminal preservation"));
                                        case "TIMED_OUT" -> lifecycle.timeOut(task, TaskExecutionOutcome.timedOut());
                                        case "CANCELLED" -> { lifecycle.requestCancellation(OWNER, task); lifecycle.finishCancellation(task, TaskExecutionOutcome.cancelled()); }
                                        default -> throw new IllegalArgumentException("Unsupported terminal fixture");
                                    }
                                }
                                default -> throw new IllegalArgumentException("Unsupported fixture command");
                            }
                            result.put("accepted", true);
                        } catch (Exception ex) {
                            result.put("accepted", false).put("exception", ex.getClass().getName()).put("message", ex.getMessage());
                        }
                        signal("commands/" + path.getFileName() + ".result", result);
                    }
                }
                Thread.sleep(20);
            }
        } catch (Exception ex) { signal("commands-failure.json", Map.of("error", ex.toString())); }
    }
}
