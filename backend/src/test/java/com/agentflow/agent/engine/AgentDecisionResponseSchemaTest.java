package com.agentflow.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.ToolSnapshot;
import com.agentflow.infra.llm.LlmResponseSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentDecisionResponseSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesEachFrozenToolSchemaInsideExactlyOneExistingActionBranch() throws Exception {
        List<ToolSnapshot> tools = tools();
        LlmResponseSchema response = AgentDecisionResponseSchema.fromTools(mapper, tools);
        JsonNode schema = response.schema();

        assertThat(response.name()).isEqualTo("agent_decision_v1");
        assertThat(fields(schema)).containsExactlyInAnyOrder("type", "oneOf");
        assertThat(schema.path("type").textValue()).isEqualTo("object");
        JsonNode branches = schema.path("oneOf");
        assertThat(branches.size()).isEqualTo(3);
        for (int index = 0; index < tools.size(); index++) {
            JsonNode branch = branches.get(index);
            assertThat(fields(branch)).containsExactlyInAnyOrder("type", "properties", "required", "additionalProperties");
            assertThat(branch.path("type").textValue()).isEqualTo("object");
            assertThat(branch.path("additionalProperties")).isEqualTo(mapper.getNodeFactory().booleanNode(false));
            JsonNode properties = branch.path("properties");
            assertThat(fields(properties)).containsExactlyInAnyOrder("type", "toolCode", "arguments", "reason");
            assertThat(properties.path("type").path("const").textValue()).isEqualTo("CALL_TOOL");
            assertThat(properties.path("toolCode").path("const").textValue()).isEqualTo(tools.get(index).toolCode());
            assertThat(properties.path("arguments")).isEqualTo(tools.get(index).inputSchema());
            assertThat(branch.path("required")).isEqualTo(mapper.readTree("[\"type\",\"toolCode\",\"arguments\",\"reason\"]"));
            assertThat(properties.path("reason").path("maxLength").intValue()).isEqualTo(256);
        }
        JsonNode orderArguments = branches.get(0).path("properties").path("arguments");
        assertThat(orderArguments.path("required")).isEqualTo(mapper.readTree("[\"orderNo\"]"));
        JsonNode paymentArguments = branches.get(1).path("properties").path("arguments");
        assertThat(paymentArguments.path("anyOf")).isEqualTo(mapper.readTree("""
                [{"required":["orderNo"]},{"required":["errorCode"]}]
                """));
        assertThat(paymentArguments.has("required")).isFalse();
        assertThat(paymentArguments.path("properties").path("limit").path("default").intValue()).isEqualTo(10);
        assertFinishBranch(branches.get(2));
        assertThat(schema.toString()).doesNotContain("\"nullable\"", "\"null\"");
    }

    @Test
    void keepsExistingSingleObjectExamplesCompatibleWithTheStrictParser() throws Exception {
        var parser = new AgentDecisionParser(mapper);
        List<AgentToolSpec> allowed = tools().stream().map(tool -> new AgentToolSpec(
                Long.parseLong(tool.toolId()), tool.toolCode(), tool.name(), tool.description(), tool.inputSchema())).toList();
        String order = """
                {"type":"CALL_TOOL","toolCode":"order_query","arguments":{"orderNo":"order_1024"},"reason":"Read order"}
                """;
        String payment = """
                {"type":"CALL_TOOL","toolCode":"payment_log_query","arguments":{"errorCode":"E_PAY_TIMEOUT"},"reason":"Read logs"}
                """;
        String finish = "{\"type\":\"FINISH\",\"answerPlan\":\"Summarize the observed evidence\"}";

        assertThat(parser.parse(order, allowed)).isInstanceOf(ToolCallDecision.class);
        assertThat(parser.parse(payment, allowed)).isInstanceOfSatisfying(ToolCallDecision.class, call -> {
            assertThat(call.arguments().has("orderNo")).isFalse();
            assertThat(call.arguments().has("limit")).isFalse();
            assertThat(call.arguments().path("errorCode").textValue()).isEqualTo("E_PAY_TIMEOUT");
        });
        assertThat(parser.parse(finish, allowed)).isEqualTo(new FinalAnswerDecision("Summarize the observed evidence"));
        for (String invalid : List.of("```json\n" + order + "```", order + payment)) {
            assertThatThrownBy(() -> parser.parse(invalid, allowed))
                    .isInstanceOfSatisfying(AgentExecutionException.class, error ->
                            assertThat(error.failureType()).isEqualTo(AgentFailureType.INVALID_DECISION));
        }
    }

    @Test
    void exposesOnlyFinishWithoutToolsAndDefensivelyCopiesSchemaConstructionAndReads() {
        JsonNode emptyToolsSchema = AgentDecisionResponseSchema.fromTools(mapper, List.of()).schema();
        assertThat(emptyToolsSchema.path("oneOf").size()).isEqualTo(1);
        assertFinishBranch(emptyToolsSchema.path("oneOf").get(0));

        ObjectNode source = (ObjectNode) emptyToolsSchema.deepCopy();
        LlmResponseSchema frozen = new LlmResponseSchema("empty_tools-v1", source);
        source.remove("oneOf");
        assertThat(frozen.schema().path("oneOf").size()).isEqualTo(1);
        ObjectNode exposed = (ObjectNode) frozen.schema();
        ((ObjectNode) exposed.path("oneOf").get(0).path("properties")).put("extra", true);
        assertFinishBranch(frozen.schema().path("oneOf").get(0));
    }

    private void assertFinishBranch(JsonNode finish) {
        assertThat(fields(finish.path("properties"))).containsExactlyInAnyOrder("type", "answerPlan");
        assertThat(finish.path("properties").path("type").path("const").textValue()).isEqualTo("FINISH");
        assertThat(finish.path("properties").path("answerPlan").path("type").textValue()).isEqualTo("string");
        assertThat(finish.path("properties").path("answerPlan").path("maxLength").intValue()).isEqualTo(2048);
        assertThat(finish.path("required")).isEqualTo(mapper.createArrayNode().add("type").add("answerPlan"));
        assertThat(finish.path("additionalProperties")).isEqualTo(mapper.getNodeFactory().booleanNode(false));
    }

    private List<ToolSnapshot> tools() throws Exception {
        // Frozen copies of the existing V13/V14 input contracts, including payment's optional filters.
        JsonNode order = mapper.readTree("""
                {"type":"object","properties":{"orderNo":{"type":"string",
                 "description":"Demo order number, for example order_1024","minLength":1,"maxLength":64}},
                 "required":["orderNo"],"additionalProperties":false}
                """);
        JsonNode payment = mapper.readTree("""
                {"type":"object","properties":{"orderNo":{"type":"string","minLength":1,"maxLength":64},
                 "errorCode":{"type":"string","minLength":1,"maxLength":64},
                 "limit":{"type":"integer","minimum":1,"maximum":20,"default":10}},
                 "anyOf":[{"required":["orderNo"]},{"required":["errorCode"]}],"additionalProperties":false}
                """);
        return List.of(
                new ToolSnapshot("270000000000000001", "order_query", "Order Query", "Read order", order, "order-hash", "v1", 5000),
                new ToolSnapshot("280000000000000001", "payment_log_query", "Payment Log Query", "Read logs", payment, "payment-hash", "v1", 5000));
    }

    private Set<String> fields(JsonNode object) {
        Set<String> names = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
