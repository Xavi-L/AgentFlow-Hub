package com.agentflow.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.infra.llm.LlmMessage;
import com.agentflow.infra.llm.LlmMessageRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentPromptBuilderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentPromptBuilder promptBuilder = new AgentPromptBuilder(objectMapper);

    @Test
    void shouldPartitionThinkingDataAndExposeOnlyFiveSafeToolFields() throws Exception {
        AgentExecutionContext context = context();
        context.appendObservation(new AgentObservation(
                "order_query",
                "Order is PAY_FAILED",
                objectMapper.readTree("{\"orderNo\":\"order_1024\",\"errorCode\":\"E_PAY_TIMEOUT\"}")
        ));

        List<LlmMessage> messages = promptBuilder.buildThinkingMessages(context);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).role()).isEqualTo(LlmMessageRole.SYSTEM);
        assertThat(messages.get(0).content()).isEqualTo("Configured system prompt");
        assertThat(messages.get(1).role()).isEqualTo(LlmMessageRole.SYSTEM);
        assertThat(messages.get(1).content()).contains(
                "Return exactly one JSON object",
                "CALL_TOOL",
                "FINISH"
        );
        assertThat(messages.get(2).role()).isEqualTo(LlmMessageRole.USER);

        JsonNode payload = objectMapper.readTree(messages.get(2).content());
        assertThat(payload.path("userInput").textValue()).isEqualTo("Diagnose order_1024");
        assertThat(payload.path("availableTools")).hasSize(1);
        JsonNode tool = payload.path("availableTools").get(0);
        assertThat(tool.fieldNames()).toIterable().containsExactly(
                "toolId", "toolCode", "name", "description", "inputSchema"
        );
        assertThat(tool.path("toolCode").textValue()).isEqualTo("order_query");
        assertThat(messages.get(2).content()).doesNotContain(
                "handler", "config", "permissionLevel", "retryCount", "timeoutMs", "outputSchema"
        );

        JsonNode observation = payload.path("observations").get(0);
        assertThat(observation.path("type").textValue()).isEqualTo("TOOL_RESULT");
        assertThat(observation.path("summary").textValue()).isEqualTo("Order is PAY_FAILED");
        assertThat(observation.path("data").path("errorCode").textValue())
                .isEqualTo("E_PAY_TIMEOUT");
    }

    @Test
    void shouldBuildASeparateFinalAnswerPayloadWithoutTheToolRegistry() throws Exception {
        AgentExecutionContext context = context();

        List<LlmMessage> messages = promptBuilder.buildFinalAnswerMessages(
                context,
                "Enough evidence to answer"
        );

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).content()).isEqualTo("Configured system prompt");
        assertThat(messages.get(1).content()).contains("Return only the final user-facing answer");
        JsonNode payload = objectMapper.readTree(messages.get(2).content());
        assertThat(payload.path("userInput").textValue()).isEqualTo("Diagnose order_1024");
        assertThat(payload.path("answerPlan").textValue()).isEqualTo("Enough evidence to answer");
        assertThat(payload.has("availableTools")).isFalse();
        assertThat(payload.path("observations")).isEmpty();
    }

    @Test
    void shouldDefensivelyCopyToolSchemasAndObservationData() throws Exception {
        JsonNode schema = objectMapper.readTree("{\"type\":\"object\"}");
        AgentToolSpec tool = new AgentToolSpec(1L, "code", "name", "description", schema);
        ((com.fasterxml.jackson.databind.node.ObjectNode) schema).put("leaked", true);
        JsonNode returnedSchema = tool.inputSchema();
        ((com.fasterxml.jackson.databind.node.ObjectNode) returnedSchema).put("mutated", true);

        JsonNode data = objectMapper.readTree("{\"safe\":true}");
        AgentObservation observation = new AgentObservation("code", "summary", data);
        ((com.fasterxml.jackson.databind.node.ObjectNode) data).put("leaked", true);
        JsonNode returnedData = observation.data();
        ((com.fasterxml.jackson.databind.node.ObjectNode) returnedData).put("mutated", true);

        assertThat(tool.inputSchema().has("leaked")).isFalse();
        assertThat(tool.inputSchema().has("mutated")).isFalse();
        assertThat(observation.data().has("leaked")).isFalse();
        assertThat(observation.data().has("mutated")).isFalse();
    }

    private AgentExecutionContext context() throws Exception {
        AgentExecutionConfigSnapshot snapshot = new AgentExecutionConfigSnapshot(
                "Configured system prompt",
                "openai-compatible",
                "model-a",
                new BigDecimal("0.2"),
                new BigDecimal("0.8"),
                6,
                4,
                8_000,
                120,
                "ACTIVE"
        );
        AgentToolSpec tool = new AgentToolSpec(
                270000000000000001L,
                "order_query",
                "Order Query",
                "Query an order",
                objectMapper.readTree("{\"type\":\"object\",\"properties\":{}}")
        );
        Clock clock = Clock.fixed(Instant.parse("2026-09-01T02:00:00Z"), ZoneOffset.UTC);
        return new AgentExecutionContext(
                new AgentExecutionCommand(101L, 301L, "Diagnose order_1024"),
                snapshot,
                List.of(tool),
                new BudgetGuard(snapshot, clock)
        );
    }
}
