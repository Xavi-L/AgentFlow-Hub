package com.agentflow.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentDecisionParserTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentDecisionParser parser = new AgentDecisionParser(objectMapper);
    private final AgentToolSpec orderTool = new AgentToolSpec(
            270000000000000001L,
            "order_query",
            "Order Query",
            "Query one order",
            objectMapper.createObjectNode().put("type", "object")
    );

    @Test
    void shouldParseAndResolveAnExactToolCallAgainstTheSafeSnapshot() {
        AgentDecision decision = parser.parse("""
                {
                  "type":"TOOL_CALL",
                  "toolCode":"order_query",
                  "arguments":{"orderNo":"order_1024"},
                  "reason":"Need the current order state"
                }
                """, List.of(orderTool));

        assertThat(decision).isInstanceOfSatisfying(ToolCallDecision.class, toolCall -> {
            assertThat(toolCall.toolId()).isEqualTo(270000000000000001L);
            assertThat(toolCall.toolCode()).isEqualTo("order_query");
            assertThat(toolCall.arguments().path("orderNo").textValue()).isEqualTo("order_1024");
            assertThat(toolCall.reason()).isEqualTo("Need the current order state");
        });
    }

    @Test
    void shouldParseAnExactFinalAnswerDecision() {
        AgentDecision decision = parser.parse(
                "{\"type\":\"FINAL_ANSWER\",\"answerDraft\":\"Enough evidence\"}",
                List.of(orderTool)
        );

        assertThat(decision).isEqualTo(new FinalAnswerDecision("Enough evidence"));
    }

    @Test
    void shouldRejectMalformedExtraDuplicateUnknownAndUnavailableDecisionsUniformly() {
        List<String> invalid = List.of(
                "not-json",
                "```json\n{\"type\":\"FINAL_ANSWER\",\"answerDraft\":\"x\"}\n```",
                "{\"type\":\"FINAL_ANSWER\",\"answerDraft\":\"x\"} trailing",
                "{\"type\":\"OTHER\",\"answerDraft\":\"x\"}",
                "{\"type\":\"FINAL_ANSWER\",\"answerDraft\":\"x\",\"extra\":true}",
                "{\"type\":\"FINAL_ANSWER\",\"answerDraft\":\" \"}",
                "{\"type\":7,\"answerDraft\":\"x\"}",
                "{\"type\":\"FINAL_ANSWER\",\"type\":\"FINAL_ANSWER\",\"answerDraft\":\"x\"}",
                "{\"type\":\"TOOL_CALL\",\"toolCode\":\"\",\"arguments\":{},\"reason\":\"x\"}",
                "{\"type\":\"TOOL_CALL\",\"toolCode\":\"missing\",\"arguments\":{},\"reason\":\"x\"}",
                "{\"type\":\"TOOL_CALL\",\"toolCode\":\"order_query\",\"arguments\":[],\"reason\":\"x\"}",
                "{\"type\":\"TOOL_CALL\",\"toolCode\":\"order_query\",\"arguments\":null,\"reason\":\"x\"}",
                "{\"type\":\"TOOL_CALL\",\"toolCode\":\"order_query\",\"arguments\":{},\"reason\":\" \"}",
                "{\"type\":\"TOOL_CALL\",\"toolCode\":\"order_query\",\"arguments\":{},\"reason\":\"x\",\"extra\":1}"
        );

        for (String content : invalid) {
            assertThatThrownBy(() -> parser.parse(content, List.of(orderTool)))
                    .isInstanceOfSatisfying(AgentExecutionException.class, failure -> {
                        assertThat(failure.failureType()).isEqualTo(AgentFailureType.INVALID_DECISION);
                        assertThat(failure.getMessage()).isEqualTo("Model decision is invalid");
                        assertThat(failure).hasNoCause();
                    });
        }
    }
}
