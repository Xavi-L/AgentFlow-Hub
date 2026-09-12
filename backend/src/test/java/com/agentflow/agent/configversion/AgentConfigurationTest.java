package com.agentflow.agent.configversion;

import static org.assertj.core.api.Assertions.*;

import com.agentflow.agent.task.dto.CreateAgentTaskRequest;
import com.agentflow.agent.task.dto.TaskConfigurationResponse;
import com.agentflow.agent.task.model.AgentTask;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentConfigurationTest {
    private final ObjectMapper json = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test void completeNullOverridesSurviveTheApplicationNonNullSerializer() {
        var tree = json.valueToTree(configuration("  中文\n系统  ", List.of(), List.of()));
        assertThat(tree.path("systemPrompt").textValue()).isEqualTo("  中文\n系统  ");
        assertThat(tree.path("executionSettingsOverrides").size()).isEqualTo(5);
        tree.path("executionSettingsOverrides").forEach(value -> assertThat(value.isNull()).isTrue());
    }

    @Test void rowOrderIsIgnoredButPriorityRemainsAnExecutionChoice() {
        var a = new AgentConfiguration.KnowledgeBinding("10", 0);
        var b = new AgentConfiguration.KnowledgeBinding("2", 1);
        var original = configuration("system", List.of(a, b), List.of());
        var reordered = configuration("system", List.of(b, a), List.of());
        assertThat(original.knowledgeBindings().getFirst().knowledgeBaseId()).isEqualTo("2");
        assertThat(original.orderedKnowledgeIds()).containsExactly(10L, 2L);
        assertThat(hash(original)).isEqualTo(hash(reordered));
        assertThat(hash(configuration("system", List.of(new AgentConfiguration.KnowledgeBinding("10", 1),
                new AgentConfiguration.KnowledgeBinding("2", 0)), List.of()))).isNotEqualTo(hash(original));
    }

    @Test void selectedConfigurationDoesNotCopyAnyMutableDraftFields() {
        var config = configuration(" preserved ", List.of(), List.of(new AgentConfiguration.ToolBinding("12", false, 0)));
        var agent = config.toExecutionAgent(9, "ACTIVE");
        assertThat(agent.getSystemPrompt()).isEqualTo(" preserved ");
        assertThat(agent.getName()).isNull();
        assertThat(agent.getDecisionMaxOutputTokens()).isNull();
        assertThat(config.orderedEnabledToolIds()).isEmpty();
        assertThat(hash(config)).isNotEqualTo(hash(configuration("preserved", List.of(), List.of())));
    }

    @Test void knownCredentialFixturesAreRejectedWithoutEchoingTheirValues() {
        for (String value : List.of("api_key=top-secret", "Bearer test-token", "{\"password\":\"top-secret\"}",
                "https://user:top-secret@example.test", "eyJhbGciOiJIUzI1NiJ9.e30.signature")) {
            assertThatThrownBy(() -> AgentConfigVersionTransactions.requireNoCredentials(value))
                    .isInstanceOf(com.agentflow.common.error.BusinessException.class)
                    .hasMessage("Configuration must not contain credentials");
        }
        assertThatCode(() -> AgentConfigVersionTransactions.requireNoCredentials("  Preserve 中文\n {\"b\":1,\"a\":2}  "))
                .doesNotThrowAnyException();
    }

    @Test void publicationAcceptsOnlyOneEmptyObject() throws Exception {
        assertThat(json.readValue("{}", PublishAgentConfigVersionRequest.class)).isNotNull();
        for (String body : List.of("[]", "{\"config\":{}}", "{}{}", "{\"x\":1,\"x\":2}"))
            assertThatThrownBy(() -> json.readValue(body, PublishAgentConfigVersionRequest.class))
                    .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test void optionalVersionIsAnExactPositiveStringWithoutCoercionOrDuplicateFields() throws Exception {
        assertThat(json.readValue("{\"userInput\":\" x \",\"configVersionId\":\"12\"}", CreateAgentTaskRequest.class))
                .isEqualTo(new CreateAgentTaskRequest(" x ", 12L));
        assertThat(json.readValue("{\"userInput\":\"x\",\"configVersionId\":null}", CreateAgentTaskRequest.class))
                .isEqualTo(new CreateAgentTaskRequest("x"));
        for (String id : List.of("12", "true", "\"0\"", "\" 12\"", "\"01\"", "\"9223372036854775808\""))
            assertThatThrownBy(() -> json.readValue("{\"userInput\":\"x\",\"configVersionId\":" + id + "}",
                    CreateAgentTaskRequest.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> json.readValue("{\"userInput\":\"x\",\"configVersionId\":null,\"configVersionId\":\"1\"}",
                CreateAgentTaskRequest.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test void historicalConfigurationIdentityRemainsAbsent() {
        var task = new AgentTask();
        task.setExecutionSnapshot("{\"snapshotVersion\":\"agent-task-snapshot-v1\"}");
        assertThat(TaskConfigurationResponse.from(task)).isNull();
    }

    private String hash(AgentConfiguration config) { return ConfigCanonicalJson.sha256(json.valueToTree(config)); }
    private AgentConfiguration configuration(String prompt, List<AgentConfiguration.KnowledgeBinding> kb,
            List<AgentConfiguration.ToolBinding> tools) {
        return new AgentConfiguration(prompt, new AgentConfiguration.Model("openai-compatible", "controlled-model",
                new BigDecimal("0.2"), new BigDecimal("0.8")), new AgentConfiguration.Budgets(6, 4, 8000, 120),
                new AgentConfiguration.Overrides(null, null, null, null, null), kb, tools);
    }
}
