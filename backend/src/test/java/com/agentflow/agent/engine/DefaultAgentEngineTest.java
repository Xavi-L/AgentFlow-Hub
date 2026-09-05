package com.agentflow.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.agent.model.AgentApp;
import com.agentflow.agent.repository.AgentAppMapper;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.infra.llm.LlmChatRequest;
import com.agentflow.infra.llm.LlmChatResult;
import com.agentflow.infra.llm.LlmGateway;
import com.agentflow.infra.llm.LlmTokenUsage;
import com.agentflow.tool.ToolDefinition;
import com.agentflow.tool.ToolDefinitionService;
import com.agentflow.tool.ToolExecutionCommand;
import com.agentflow.tool.ToolExecutionResult;
import com.agentflow.tool.ToolRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultAgentEngineTest {
    private static final long USER_ID = 101L;
    private static final long AGENT_ID = 301L;
    private static final String USER_INPUT = "Diagnose order_1024 payment failure";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentAppMapper agentAppMapper;
    private ToolDefinitionService toolDefinitionService;
    private LlmGateway llmGateway;
    private ToolRuntime toolRuntime;
    private MutableClock clock;
    private DefaultAgentEngine engine;
    private AgentApp activeAgent;
    private ToolDefinition orderTool;
    private ToolDefinition paymentTool;

    @BeforeEach
    void setUp() throws Exception {
        agentAppMapper = mock(AgentAppMapper.class);
        toolDefinitionService = mock(ToolDefinitionService.class);
        llmGateway = mock(LlmGateway.class);
        toolRuntime = mock(ToolRuntime.class);
        clock = new MutableClock(Instant.parse("2026-09-01T02:00:00Z"));
        engine = new DefaultAgentEngine(
                agentAppMapper,
                toolDefinitionService,
                llmGateway,
                toolRuntime,
                new AgentPromptBuilder(objectMapper),
                new AgentDecisionParser(objectMapper),
                clock
        );
        activeAgent = agent("ACTIVE", 6, 4, 1_000, 120);
        orderTool = tool(
                270000000000000001L,
                "order_query",
                "Order Query",
                "Query the current order state",
                "secretOrderHandler"
        );
        paymentTool = tool(
                280000000000000001L,
                "payment_log_query",
                "Payment Log Query",
                "Query payment error logs",
                "secretPaymentHandler"
        );
        when(agentAppMapper.selectVisibleOwnedById(AGENT_ID, USER_ID)).thenReturn(activeAgent);
        when(toolDefinitionService.listActive()).thenReturn(List.of(orderTool, paymentTool));
    }

    @Test
    void shouldRunOrderThenPaymentThenFinalAnswerFromOneImmutableConfigSnapshot() throws Exception {
        when(toolDefinitionService.listActive()).thenReturn(List.of(
                orderTool,
                paymentTool,
                nonBuiltinTool()
        ));
        List<LlmChatResult> script = List.of(
                result("""
                        {"type":"CALL_TOOL","toolCode":"order_query",\
                        "arguments":{"orderNo":"order_1024"},"reason":"Check order"}
                        """, 10),
                result("""
                        {"type":"CALL_TOOL","toolCode":"payment_log_query",\
                        "arguments":{"errorCode":"E_PAY_TIMEOUT"},"reason":"Check logs"}
                        """, 10),
                result("{\"type\":\"FINISH\",\"answerPlan\":\"The evidence is sufficient\"}", 10),
                result("Payment failed because the gateway timed out. Retry after checking gateway health.", 10)
        );
        AtomicInteger responseIndex = new AtomicInteger();
        when(llmGateway.chat(any())).thenAnswer(invocation -> {
            int index = responseIndex.getAndIncrement();
            if (index == 0) {
                activeAgent.setSystemPrompt("mutated prompt must not leak");
                activeAgent.setModelName("mutated-model");
                activeAgent.setTemperature(new BigDecimal("1.7"));
                activeAgent.setMaxTokens(100_000);
            }
            return script.get(index);
        });
        when(toolRuntime.execute(any())).thenAnswer(invocation -> {
            ToolExecutionCommand command = invocation.getArgument(0);
            if (command.toolId().equals(orderTool.id())) {
                return ToolExecutionResult.success(
                        "order_query",
                        "Order is PAY_FAILED with E_PAY_TIMEOUT",
                        objectMapper.readTree("""
                                {"orderNo":"order_1024","paymentStatus":"PAY_FAILED",\
                                "errorCode":"E_PAY_TIMEOUT"}
                                """),
                        3
                );
            }
            return ToolExecutionResult.success(
                    "payment_log_query",
                    "Payment gateway response timed out",
                    objectMapper.readTree("{\"logs\":[{\"errorCode\":\"E_PAY_TIMEOUT\"}]}"),
                    4
            );
        });

        AgentExecutionResult execution = engine.execute(command());

        assertThat(execution.finalAnswer())
                .isEqualTo("Payment failed because the gateway timed out. Retry after checking gateway health.");
        assertThat(execution.stepsUsed()).isEqualTo(3);
        assertThat(execution.toolCallsUsed()).isEqualTo(2);
        assertThat(execution.inputTokens()).isEqualTo(24);
        assertThat(execution.outputTokens()).isEqualTo(16);
        assertThat(execution.totalTokens()).isEqualTo(40);

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmGateway, times(4)).chat(requestCaptor.capture());
        List<LlmChatRequest> requests = requestCaptor.getAllValues();
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.modelProvider()).isEqualTo("openai-compatible");
            assertThat(request.modelName()).isEqualTo("model-a");
            assertThat(request.temperature()).isEqualByComparingTo("0.2");
            assertThat(request.topP()).isEqualByComparingTo("0.8");
            assertThat(request.messages().get(0).content()).isEqualTo("Configured system prompt");
        });
        assertThat(requests).extracting(LlmChatRequest::maxOutputTokens)
                .containsExactly(512, 512, 512, 970);

        JsonNode firstThinking = userPayload(requests.get(0));
        assertThat(firstThinking.path("userInput").textValue()).isEqualTo(USER_INPUT);
        assertThat(firstThinking.path("observations")).isEmpty();
        assertThat(firstThinking.path("availableTools")).hasSize(2);
        JsonNode firstTool = firstThinking.path("availableTools").get(0);
        assertThat(firstTool.fieldNames()).toIterable().containsExactly(
                "toolId", "toolCode", "name", "description", "inputSchema"
        );
        assertThat(requests.get(0).messages().get(2).content()).doesNotContain(
                "secretOrderHandler",
                "secretPaymentHandler",
                "config",
                "permissionLevel",
                "retryCount",
                "timeoutMs",
                "outputSchema"
        );

        JsonNode secondThinking = userPayload(requests.get(1));
        assertThat(secondThinking.path("observations")).hasSize(1);
        assertThat(secondThinking.path("observations").get(0).path("summary").textValue())
                .isEqualTo("Order is PAY_FAILED with E_PAY_TIMEOUT");
        assertThat(secondThinking.path("observations").get(0).path("data").path("orderNo").textValue())
                .isEqualTo("order_1024");

        JsonNode thirdThinking = userPayload(requests.get(2));
        assertThat(thirdThinking.path("observations")).hasSize(2);
        assertThat(thirdThinking.path("observations").get(1).path("data").path("logs")).hasSize(1);

        JsonNode finalPayload = userPayload(requests.get(3));
        assertThat(finalPayload.path("answerPlan").textValue()).isEqualTo("The evidence is sufficient");
        assertThat(finalPayload.path("observations")).hasSize(2);
        assertThat(finalPayload.has("availableTools")).isFalse();

        ArgumentCaptor<ToolExecutionCommand> toolCaptor = ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(toolRuntime, times(2)).execute(toolCaptor.capture());
        assertThat(toolCaptor.getAllValues()).extracting(ToolExecutionCommand::toolId)
                .containsExactly(orderTool.id(), paymentTool.id());
        assertThat(toolCaptor.getAllValues()).allSatisfy(toolCommand -> {
            assertThat(toolCommand.taskId()).isNull();
            assertThat(toolCommand.stepId()).isNull();
        });
        assertThat(toolCaptor.getAllValues().get(0).arguments().path("orderNo").textValue())
                .isEqualTo("order_1024");
    }

    @Test
    void shouldRejectEveryScopedMissWithTheSameFailureBeforeLoadingTools() {
        when(agentAppMapper.selectVisibleOwnedById(any(), any())).thenReturn(null);
        List<AgentExecutionCommand> misses = List.of(
                new AgentExecutionCommand(101L, 301L, "input"),
                new AgentExecutionCommand(102L, 301L, "input"),
                new AgentExecutionCommand(101L, 302L, "input")
        );

        for (AgentExecutionCommand miss : misses) {
            assertFailure(() -> engine.execute(miss), AgentFailureType.AGENT_NOT_FOUND, "Agent is unavailable");
        }

        verifyNoInteractions(toolDefinitionService, llmGateway, toolRuntime);
    }

    @Test
    void shouldRejectInvalidCommandsBeforeAnyRepositoryOrExternalCall() {
        List<AgentExecutionCommand> invalid = java.util.Arrays.asList(
                null,
                new AgentExecutionCommand(null, AGENT_ID, "input"),
                new AgentExecutionCommand(0L, AGENT_ID, "input"),
                new AgentExecutionCommand(USER_ID, null, "input"),
                new AgentExecutionCommand(USER_ID, -1L, "input"),
                new AgentExecutionCommand(USER_ID, AGENT_ID, null),
                new AgentExecutionCommand(USER_ID, AGENT_ID, "  ")
        );

        for (AgentExecutionCommand command : invalid) {
            assertFailure(
                    () -> engine.execute(command),
                    AgentFailureType.INVALID_COMMAND,
                    "Agent execution command is invalid"
            );
        }

        verifyNoInteractions(agentAppMapper, toolDefinitionService, llmGateway, toolRuntime);
    }

    @Test
    void shouldRejectDisabledBeforeAnyToolRegistryLlmOrToolCall() {
        when(agentAppMapper.selectVisibleOwnedById(AGENT_ID, USER_ID))
                .thenReturn(agent("DISABLED", 6, 4, 1_000, 120));

        assertFailure(() -> engine.execute(command()), AgentFailureType.AGENT_DISABLED, "Agent is disabled");

        verifyNoInteractions(toolDefinitionService, llmGateway, toolRuntime);
    }

    @Test
    void shouldGenerateAnAnswerWithoutCallingAnyTool() {
        when(toolDefinitionService.listActive()).thenReturn(List.of());
        when(llmGateway.chat(any())).thenReturn(
                result("{\"type\":\"FINISH\",\"answerPlan\":\"Answer directly\"}", 10),
                result("Direct final answer", 10)
        );

        AgentExecutionResult execution = engine.execute(command());

        assertThat(execution.finalAnswer()).isEqualTo("Direct final answer");
        assertThat(execution.stepsUsed()).isEqualTo(1);
        assertThat(execution.toolCallsUsed()).isZero();
        assertThat(execution.totalTokens()).isEqualTo(20);
        verifyNoInteractions(toolRuntime);
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmGateway, times(2)).chat(captor.capture());
        assertThat(captor.getAllValues()).extracting(LlmChatRequest::maxOutputTokens)
                .containsExactly(512, 990);
    }

    @Test
    void shouldRejectInvalidJsonUnknownToolsAndNonObjectArgumentsWithoutToolIo() {
        List<String> invalid = List.of(
                "prefix {\"type\":\"FINISH\",\"answerPlan\":\"x\"}",
                "{\"type\":\"CALL_TOOL\",\"toolCode\":\"unknown\",\"arguments\":{},\"reason\":\"x\"}",
                "{\"type\":\"CALL_TOOL\",\"toolCode\":\"order_query\",\"arguments\":[],\"reason\":\"x\"}"
        );

        for (String content : invalid) {
            reset(llmGateway);
            when(llmGateway.chat(any())).thenReturn(result(content, 10));
            assertFailure(
                    () -> engine.execute(command()),
                    AgentFailureType.INVALID_DECISION,
                    "Model decision is invalid"
            );
            verify(llmGateway).chat(any());
        }
        verifyNoInteractions(toolRuntime);
    }

    @Test
    void shouldSendObjectArgumentsThroughToolRuntimeAndSanitizeSchemaRejection() {
        String secret = "prompt-body secret-handler internal-database";
        when(llmGateway.chat(any())).thenReturn(result("""
                {"type":"CALL_TOOL","toolCode":"order_query",\
                "arguments":{"orderNo":""},"reason":"Try query"}
                """, 10));
        when(toolRuntime.execute(any()))
                .thenThrow(new BusinessException(ErrorCode.TOOL_ARGUMENT_INVALID, secret));

        assertThatThrownBy(() -> engine.execute(command()))
                .isInstanceOfSatisfying(AgentExecutionException.class, failure -> {
                    assertThat(failure.failureType()).isEqualTo(AgentFailureType.TOOL_FAILURE);
                    assertThat(failure.getMessage()).isEqualTo("Tool execution failed");
                    assertThat(failure.getMessage()).doesNotContain(secret);
                    assertThat(failure).hasNoCause();
                });
        verify(toolRuntime).execute(any());
        verify(llmGateway).chat(any());
    }

    @Test
    void shouldRejectLegacyEqualDecisionAndToolLimitsBeforeExternalIo() {
        when(agentAppMapper.selectVisibleOwnedById(AGENT_ID, USER_ID))
                .thenReturn(agent("ACTIVE", 1, 1, 1_000, 120));

        assertFailure(
                () -> engine.execute(command()),
                AgentFailureType.INVALID_AGENT_CONFIG,
                "Agent execution configuration is invalid"
        );
        verifyNoInteractions(llmGateway, toolRuntime);
    }

    @Test
    void shouldIncrementAcceptedToolCallBeforeRuntimeAndStopAtZeroToolBudget() {
        when(agentAppMapper.selectVisibleOwnedById(AGENT_ID, USER_ID))
                .thenReturn(agent("ACTIVE", 1, 0, 1_000, 120));
        when(llmGateway.chat(any())).thenReturn(result("""
                {"type":"CALL_TOOL","toolCode":"order_query",\
                "arguments":{"orderNo":"order_1024"},"reason":"Query"}
                """, 10));

        assertFailure(
                () -> engine.execute(command()),
                AgentFailureType.TOOL_CALL_LIMIT_EXCEEDED,
                "Agent tool-call budget is exhausted"
        );
        verify(llmGateway).chat(any());
        verify(toolRuntime, never()).execute(any());
    }

    @Test
    void shouldUseRemainingTokenCapAndStopToolIoWhenTheFirstCallConsumesAllTokens() {
        when(agentAppMapper.selectVisibleOwnedById(AGENT_ID, USER_ID))
                .thenReturn(agent("ACTIVE", 2, 1, 256, 120));
        when(llmGateway.chat(any())).thenReturn(new LlmChatResult(
                "{\"type\":\"CALL_TOOL\",\"toolCode\":\"order_query\",\"arguments\":{},\"reason\":\"x\"}",
                "resolved",
                "stop",
                LlmTokenUsage.known(200, 56, 256),
                "request-id",
                1
        ));

        assertFailure(
                () -> engine.execute(command()),
                AgentFailureType.TOKEN_LIMIT_EXCEEDED,
                "Agent token budget is exhausted"
        );
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmGateway).chat(captor.capture());
        assertThat(captor.getValue().maxOutputTokens()).isEqualTo(256);
        verify(toolRuntime, never()).execute(any());
    }

    @Test
    void shouldStopImmediatelyWhenProviderUsageIsUnknown() {
        when(llmGateway.chat(any())).thenReturn(new LlmChatResult(
                "{\"type\":\"FINISH\",\"answerPlan\":\"x\"}",
                null,
                null,
                LlmTokenUsage.unknown(),
                null,
                1
        ));

        assertFailure(
                () -> engine.execute(command()),
                AgentFailureType.TOKEN_USAGE_UNKNOWN,
                "LLM token usage is unavailable"
        );
        verify(llmGateway).chat(any());
        verifyNoInteractions(toolRuntime);
    }

    @Test
    void shouldCheckTheDeadlineAfterToolIoAndMakeNoLaterLlmCall() throws Exception {
        when(agentAppMapper.selectVisibleOwnedById(AGENT_ID, USER_ID))
                .thenReturn(agent("ACTIVE", 2, 1, 1_000, 1));
        when(llmGateway.chat(any())).thenReturn(result("""
                {"type":"CALL_TOOL","toolCode":"order_query",\
                "arguments":{"orderNo":"order_1024"},"reason":"Query"}
                """, 10));
        when(toolRuntime.execute(any())).thenAnswer(invocation -> {
            clock.advanceSeconds(1);
            return ToolExecutionResult.success(
                    "order_query",
                    "Order found",
                    objectMapper.readTree("{\"orderNo\":\"order_1024\"}"),
                    1
            );
        });

        assertFailure(
                () -> engine.execute(command()),
                AgentFailureType.EXECUTION_TIMEOUT,
                "Agent execution deadline is exceeded"
        );
        verify(llmGateway).chat(any());
        verify(toolRuntime).execute(any());
    }

    @Test
    void shouldCheckTheDeadlineAfterLlmIoAndSkipDecisionAndToolProcessing() {
        when(agentAppMapper.selectVisibleOwnedById(AGENT_ID, USER_ID))
                .thenReturn(agent("ACTIVE", 2, 1, 1_000, 1));
        when(llmGateway.chat(any())).thenAnswer(invocation -> {
            clock.advanceSeconds(1);
            return result("""
                    {"type":"CALL_TOOL","toolCode":"order_query",\
                    "arguments":{"orderNo":"order_1024"},"reason":"Query"}
                    """, 10);
        });

        assertFailure(
                () -> engine.execute(command()),
                AgentFailureType.EXECUTION_TIMEOUT,
                "Agent execution deadline is exceeded"
        );
        verify(llmGateway).chat(any());
        verifyNoInteractions(toolRuntime);
    }

    @Test
    void shouldSanitizeLlmFailuresWithoutPromptResponseOrProviderCause() {
        String secret = "configured prompt model response provider-url api-key";
        when(llmGateway.chat(any())).thenThrow(new RuntimeException(secret));

        assertThatThrownBy(() -> engine.execute(command()))
                .isInstanceOfSatisfying(AgentExecutionException.class, failure -> {
                    assertThat(failure.failureType()).isEqualTo(AgentFailureType.LLM_FAILURE);
                    assertThat(failure.getMessage()).isEqualTo("LLM call failed");
                    assertThat(failure.getMessage()).doesNotContain(secret, USER_INPUT);
                    assertThat(failure).hasNoCause();
                });
        verifyNoInteractions(toolRuntime);
    }

    @Test
    void shouldRejectInvalidPersistedExecutionConfigBeforeLoadingTools() {
        activeAgent.setMaxSteps(null);

        assertFailure(
                () -> engine.execute(command()),
                AgentFailureType.INVALID_AGENT_CONFIG,
                "Agent execution configuration is invalid"
        );
        verifyNoInteractions(toolDefinitionService, llmGateway, toolRuntime);
    }

    private AgentExecutionCommand command() {
        return new AgentExecutionCommand(USER_ID, AGENT_ID, USER_INPUT);
    }

    private JsonNode userPayload(LlmChatRequest request) throws Exception {
        return objectMapper.readTree(request.messages().get(2).content());
    }

    private LlmChatResult result(String content, int totalTokens) {
        int inputTokens = totalTokens * 3 / 5;
        int outputTokens = totalTokens - inputTokens;
        return new LlmChatResult(
                content,
                "resolved-model",
                "stop",
                LlmTokenUsage.known(inputTokens, outputTokens, totalTokens),
                "request-id",
                1
        );
    }

    private AgentApp agent(
            String status,
            int maxSteps,
            int maxToolCalls,
            int maxTokens,
            int timeoutSeconds
    ) {
        AgentApp agent = new AgentApp();
        agent.setId(AGENT_ID);
        agent.setSystemPrompt("Configured system prompt");
        agent.setModelProvider("openai-compatible");
        agent.setModelName("model-a");
        agent.setTemperature(new BigDecimal("0.2"));
        agent.setTopP(new BigDecimal("0.8"));
        agent.setMaxSteps(maxSteps);
        agent.setMaxToolCalls(maxToolCalls);
        agent.setMaxTokens(maxTokens);
        agent.setTimeoutSeconds(timeoutSeconds);
        agent.setStatus(status);
        return agent;
    }

    private ToolDefinition tool(
            Long id,
            String code,
            String name,
            String description,
            String secretHandler
    ) throws Exception {
        return new ToolDefinition(
                id,
                code,
                name,
                description,
                "BUILTIN",
                objectMapper.readTree("""
                        {"type":"object","properties":{"orderNo":{"type":"string"}},\
                        "additionalProperties":false}
                        """),
                objectMapper.readTree("{\"type\":\"object\"}"),
                objectMapper.readTree("{\"handler\":\"" + secretHandler + "\",\"connection\":\"secret\"}"),
                3_000,
                2,
                false,
                "INTERNAL_PERMISSION",
                "ACTIVE"
        );
    }

    private ToolDefinition nonBuiltinTool() throws Exception {
        return new ToolDefinition(
                999L,
                "remote_internal",
                "Remote Internal",
                "Not executable by the V36 built-in runtime",
                "HTTP",
                objectMapper.readTree("{\"type\":\"object\"}"),
                objectMapper.readTree("{\"type\":\"object\"}"),
                objectMapper.readTree("{\"endpoint\":\"http://internal.invalid\"}"),
                3_000,
                0,
                false,
                "INTERNAL_PERMISSION",
                "ACTIVE"
        );
    }

    private static void assertFailure(
            Runnable invocation,
            AgentFailureType expectedType,
            String expectedMessage
    ) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(AgentExecutionException.class, failure -> {
                    assertThat(failure.failureType()).isEqualTo(expectedType);
                    assertThat(failure.getMessage()).isEqualTo(expectedMessage);
                    assertThat(failure).hasNoCause();
                });
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
