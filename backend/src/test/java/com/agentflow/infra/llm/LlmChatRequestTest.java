package com.agentflow.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmChatRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyConstructorsKeepTheirOriginalSerializedShape() {
        LlmChatRequest plain = new LlmChatRequest("openai-compatible", "model", messages(),
                BigDecimal.ZERO, BigDecimal.ONE, 512);
        JsonNode plainJson = mapper.valueToTree(plain);
        assertThat(plainJson.size()).isEqualTo(6);
        assertThat(plainJson.has("responseSchema")).isFalse();
        assertThat(plainJson.has("responseFormat")).isFalse();
        assertThat(plainJson.has("thinkingMode")).isFalse();

        LlmResponseSchema schema = schema();
        LlmChatRequest structured = new LlmChatRequest("openai-compatible", "model", messages(),
                BigDecimal.ZERO, BigDecimal.ONE, 512, schema);
        JsonNode structuredJson = mapper.valueToTree(structured);
        assertThat(structuredJson.size()).isEqualTo(7);
        assertThat(structuredJson.path("responseSchema")).isEqualTo(mapper.valueToTree(schema));
        assertThat(structuredJson.has("responseFormat")).isFalse();
        assertThat(structuredJson.has("thinkingMode")).isFalse();
    }

    @Test
    void explicitModesAreRecordedWithoutInventingAResponseSchema() {
        JsonNode json = mapper.valueToTree(request(null, "json_object", "disabled"));
        assertThat(json.path("responseFormat").asText()).isEqualTo("json_object");
        assertThat(json.path("thinkingMode").asText()).isEqualTo("disabled");
        assertThat(json.has("responseSchema")).isFalse();
    }

    @Test
    void rejectsUnsupportedModeValuesAndConflictingResponseConstraints() {
        for (String format : List.of("", "JSON_OBJECT", "json_schema", "text")) {
            assertThatThrownBy(() -> request(null, format, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("responseFormat");
        }
        for (String mode : List.of("", "DISABLED", "enabled", "automatic")) {
            assertThatThrownBy(() -> request(null, null, mode))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thinkingMode");
        }
        assertThatThrownBy(() -> request(schema(), "json_object", "disabled"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mutually exclusive");
    }

    private LlmChatRequest request(LlmResponseSchema schema, String format, String thinkingMode) {
        return new LlmChatRequest("openai-compatible", "model", messages(),
                BigDecimal.ZERO, BigDecimal.ONE, 8192, schema, format, thinkingMode);
    }

    private LlmResponseSchema schema() {
        return new LlmResponseSchema("decision_v1", mapper.createObjectNode().put("type", "object"));
    }

    private List<LlmMessage> messages() {
        return List.of(new LlmMessage(LlmMessageRole.USER, "Return JSON"));
    }
}
