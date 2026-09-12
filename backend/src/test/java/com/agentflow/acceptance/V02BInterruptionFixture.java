package com.agentflow.acceptance;

import static com.agentflow.acceptance.V02ATaskRecoveryFixture.*;

import com.agentflow.AgentFlowApplication;
import com.agentflow.agent.engine.TaskExternalCallDeadline;
import com.agentflow.agent.task.recovery.TaskExecutionAdmission;
import com.agentflow.agent.trace.repository.AgentStepMapper;
import com.agentflow.demo.service.DemoOrderService;
import com.agentflow.infra.llm.LlmChatResult;
import com.agentflow.infra.llm.LlmGateway;
import com.agentflow.infra.llm.LlmTokenUsage;
import com.agentflow.knowledge.vector.EmbeddingGateway;
import com.agentflow.knowledge.vector.EmbeddingVector;
import com.agentflow.knowledge.vector.VectorDocumentScope;
import com.agentflow.knowledge.vector.VectorSearchHit;
import com.agentflow.knowledge.vector.VectorSearchRequest;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.knowledge.vector.VectorStoreRecord;
import com.agentflow.tool.OrderQueryToolHandler;
import com.agentflow.tool.model.ToolCallLogRecord;
import com.agentflow.tool.repository.ToolCallLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
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

/** Test-source only: real Engine/transactions and independent HTTP; never a production fault API. */
public final class V02BInterruptionFixture {
    private V02BInterruptionFixture() { }

    public static void main(String[] args) {
        if (System.getProperty("v02a.control-dir") == null || !System.getProperty("spring.datasource.url", "")
                .matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agentflow_v02a")) {
            throw new IllegalArgumentException("Requires isolated loopback disposable fixture database/control directory");
        }
        SpringApplication.run(new Class<?>[] {AgentFlowApplication.class, ControlledConfiguration.class}, args);
    }

    /** The owned gateway work stays alive until HTTP really exits, even after its waiter is interrupted. */
    static JsonNode stubbornExternal(String endpoint, JsonNode request) {
        String key = request.path("case").asText() + "-" + request.path("type").asText("tool");
        signal("b-boundaries/" + key + ".entered", Map.of("at", Instant.now().toString()));
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon().name("v02b-controlled-http").start(() -> {
            try { result.complete(external(endpoint, request)); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        boolean interrupted = false;
        try {
            for (;;) {
                try { return result.get(); }
                catch (InterruptedException ignored) { interrupted = true; }
                catch (ExecutionException error) { throw new IllegalStateException(error.getCause()); }
            }
        } finally {
            signal("b-boundaries/" + key + ".exited", Map.of("at", Instant.now().toString()));
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ControlledConfiguration extends V02ATaskRecoveryFixture.ControlledConfiguration {
        @org.springframework.beans.factory.annotation.Autowired
        ApplicationContext fixtureContext;

        @Bean
        static BeanPostProcessor settlementControlPoints(org.springframework.beans.factory.ObjectProvider<JdbcTemplate> jdbc) {
            Map<Long, AtomicInteger> attempts = new ConcurrentHashMap<>();
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    String type = org.springframework.aop.support.AopUtils.getTargetClass(bean).getSimpleName();
                    if (type.equals("TaskSettlementService")) {
                        ProxyFactory proxy = new ProxyFactory(bean);
                        proxy.setProxyTargetClass(true);
                        proxy.addAdvice((MethodInterceptor) invocation -> {
                            Object value = invocation.proceed();
                            String method = invocation.getMethod().getName();
                            if (method.equals("settle") || method.equals("rejectDispatch")) {
                                signal("b-settled/" + invocation.getArguments()[0] + "-" + method + ".returned",
                                        Map.of("at", Instant.now().toString(), "result", value));
                            }
                            return value;
                        });
                        return proxy.getProxy();
                    }
                    if (!type.equals("AgentTaskLifecycleTransactionService")) return bean;
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        if (!invocation.getMethod().getName().equals("settleObserved")) return invocation.proceed();
                        long task = (Long) invocation.getArguments()[0];
                        int attempt = attempts.computeIfAbsent(task, ignored -> new AtomicInteger()).incrementAndGet();
                        String input = jdbc.getObject().queryForObject("SELECT user_input FROM agent_task WHERE id=?", String.class, task);
                        String spec = read(CONTROL.resolve("b-settlement/" + input));
                        signal("b-attempts/" + task + "-" + attempt + ".entered", Map.of("at", Instant.now().toString(),
                                "taskId", Long.toString(task), "attempt", attempt, "observedAt", invocation.getArguments()[2].toString()));
                        if (spec.equals("RETRY_GATE") && attempt == 2) gate(task + "-retry");
                        if (spec.equals("FIRST_GATE") && attempt == 1) gate(task + "-first-settlement");
                        Object value = invocation.proceed();
                        signal("b-attempts/" + task + "-" + attempt + ".committed", Map.of("at", Instant.now().toString(), "result", String.valueOf(value)));
                        if (spec.equals("LOST_COMMIT_RESPONSE") && attempt == 1) {
                            throw new org.springframework.dao.DataAccessResourceFailureException("V02B controlled COMMIT receipt lost after transaction returned");
                        }
                        return value;
                    });
                    return proxy.getProxy();
                }
            };
        }

        @Override @Bean @Primary
        LlmGateway recoveryLlm(JdbcTemplate jdbc) {
            return request -> {
                JsonNode payload;
                try { payload = JSON.readTree(request.messages().getLast().content()); }
                catch (Exception error) { throw new IllegalArgumentException(error); }
                String input = payload.path("userTask").asText();
                String id = jdbc.queryForObject("SELECT id::text FROM agent_task WHERE user_input=?", String.class, input);
                JsonNode result = stubbornExternal("/llm", JSON.createObjectNode().put("taskId", id).put("case", input)
                        .put("type", payload.has("answerPlan") ? "FINAL_GENERATION" : "DECISION"));
                return new LlmChatResult(result.path("content").asText(), "v02b-controlled", "stop",
                        LlmTokenUsage.known(10, 5, 15), result.path("requestId").asText(), 1);
            };
        }

        @Override @Bean @Primary
        EmbeddingGateway recoveryEmbeddings() { return request -> {
            stubbornExternal("/boundary", JSON.createObjectNode().put("case", request.content()).put("type", "EMBEDDING"));
            return new EmbeddingVector(java.util.Collections.nCopies(1024, 0.1f));
        }; }

        @Override @Bean @Primary
        VectorStoreGateway recoveryVectors(JdbcTemplate jdbc) {
            return new VectorStoreGateway() {
                @Override public void upsert(VectorStoreRecord record) { throw new AssertionError("Vector write forbidden"); }
                @Override public void deleteByDocumentScope(VectorDocumentScope scope) { throw new AssertionError("Vector write forbidden"); }
                @Override public List<VectorSearchHit> search(VectorSearchRequest request) {
                    String input = jdbc.queryForObject("SELECT user_input FROM agent_task WHERE status='RUNNING' ORDER BY started_at DESC LIMIT 1", String.class);
                    stubbornExternal("/boundary", JSON.createObjectNode().put("case", input).put("type", "VECTOR"));
                    return List.of();
                }
            };
        }

        @Override @Bean @Primary
        OrderQueryToolHandler recoveryOrder(DemoOrderService orders, ObjectMapper mapper) {
            return new OrderQueryToolHandler(orders, mapper) {
                @Override public HandlerResult execute(JsonNode arguments) {
                    String input = arguments.path("orderNo").asText();
                    JsonNode result = stubbornExternal("/tool", JSON.createObjectNode().put("case", input).put("type", "tool"));
                    if (input.startsWith("B10_")) {
                        boolean interrupted = Thread.interrupted();
                        try {
                            long task = fixtureContext.getBean(JdbcTemplate.class).queryForObject(
                                    "SELECT id FROM agent_task WHERE user_input=?", Long.class, input);
                            signal("b-handler-late/" + input + ".json", lateWrites(fixtureContext, task));
                        } finally { if (interrupted) Thread.currentThread().interrupt(); }
                    }
                    return new HandlerResult("Controlled read-only order result", result);
                }
            };
        }

        @Bean
        ApplicationRunner bCommands(ApplicationContext context) {
            return args -> Thread.ofVirtual().name("v02b-test-commands").start(() -> {
                try {
                    var directory = CONTROL.resolve("b-commands");
                    Files.createDirectories(directory);
                    for (;;) {
                        try (var files = Files.list(directory)) {
                            for (var path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                                if (Files.exists(java.nio.file.Path.of(path + ".result"))) continue;
                                JsonNode request = JSON.readTree(Files.readString(path));
                                var result = JSON.createObjectNode();
                                String action = request.path("action").asText();
                                if (action.equals("state")) {
                                    var boundary = context.getBean(TaskExternalCallDeadline.class);
                                    var admission = context.getBean(TaskExecutionAdmission.class);
                                    var health = context.getBean("taskExecutionHealthIndicator", org.springframework.boot.actuate.health.HealthIndicator.class).health();
                                    result.put("activeWorkCount", boundary.activeWorkCount()).put("availablePermits", boundary.availablePermits())
                                            .put("capacity", boundary.capacity()).put("admissionReady", admission.isReady()).put("diagnostic", admission.diagnostic())
                                            .put("taskExecutionHealthStatus", health.getStatus().getCode());
                                } else if (action.equals("late-record-writes")) {
                                    long task = request.path("taskId").asLong();
                                    result = lateWrites(context, task);
                                } else throw new IllegalArgumentException("Unknown B command");
                                signal("b-commands/" + path.getFileName() + ".result", result);
                            }
                        }
                        Thread.sleep(10);
                    }
                } catch (Exception error) { signal("b-commands-failure.json", Map.of("error", error.toString())); }
            });
        }
    }

    static com.fasterxml.jackson.databind.node.ObjectNode lateWrites(ApplicationContext context, long task) {
        var result = JSON.createObjectNode();
        var logs = context.getBean(ToolCallLogMapper.class);
        var record = logs.selectByTaskIdOrdered(task).getFirst();
        record.setStatus("SUCCESS"); record.setResultJson("{\"late\":true}");
        record.setFinishedAt(OffsetDateTime.now()); record.setErrorCode(null); record.setErrorMessage(null);
        result.put("toolRowsUpdated", logs.updateRunningToTerminal(record));
        result.put("stepRowsUpdated", context.getBean(AgentStepMapper.class).completeRunning(task, record.getStepId(), "{\"late\":true}", OffsetDateTime.now()));
        return result;
    }
}
