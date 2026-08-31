package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ToolArgumentValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolArgumentValidator validator = new ToolArgumentValidator();

    @Test
    void shouldAcceptTheSingleRequiredOrderNumber() throws Exception {
        assertThatCode(() -> validator.validate(orderSchema(), json("{\"orderNo\":\"order_1024\"}")))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMissingNullAndNonObjectArguments() throws Exception {
        assertInvalid(null);
        assertInvalid(json("null"));
        assertInvalid(json("{}"));
        assertInvalid(json("[]"));
    }

    @Test
    void shouldRejectWrongStringTypeEmptyAndWhitespaceOnlyValues() throws Exception {
        assertInvalid(json("{\"orderNo\":1024}"));
        assertInvalid(json("{\"orderNo\":\"\"}"));
        assertInvalid(json("{\"orderNo\":\"   \"}"));
    }

    @Test
    void shouldApplyStringMaximumByUnicodeCodePoint() throws Exception {
        String sixtyFour = "a".repeat(64);
        assertThatCode(() -> validator.validate(orderSchema(), json("{\"orderNo\":\"" + sixtyFour + "\"}")))
                .doesNotThrowAnyException();
        assertInvalid(json("{\"orderNo\":\"" + sixtyFour + "a\"}"));
    }

    @Test
    void shouldRejectAdditionalFieldsWhenThePersistedSchemaForbidsThem() throws Exception {
        assertInvalid(json("{\"orderNo\":\"order_1024\",\"userNo\":\"hidden\"}"));
    }

    @Test
    void shouldSupportNestedObjectsAndIntegerBounds() throws Exception {
        JsonNode schema = json("""
                {
                  "type": "object",
                  "properties": {
                    "filter": {
                      "type": "object",
                      "properties": {
                        "limit": {"type": "integer", "minimum": 1, "maximum": 20}
                      },
                      "required": ["limit"],
                      "additionalProperties": false
                    }
                  },
                  "required": ["filter"],
                  "additionalProperties": false
                }
                """);

        assertThatCode(() -> validator.validate(schema, json("{\"filter\":{\"limit\":20}}")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(schema, json("{\"filter\":{\"limit\":0}}")))
                .isInstanceOf(ToolArgumentValidationException.class);
        assertThatThrownBy(() -> validator.validate(schema, json("{\"filter\":{\"limit\":21}}")))
                .isInstanceOf(ToolArgumentValidationException.class);
        assertThatThrownBy(() -> validator.validate(schema, json("{\"filter\":{\"limit\":1.5}}")))
                .isInstanceOf(ToolArgumentValidationException.class);
    }

    @Test
    void shouldFailClosedForUnsupportedTypesInsteadOfSilentlyAcceptingThem() throws Exception {
        JsonNode unsupported = json("{\"type\":\"number\"}");

        assertThatThrownBy(() -> validator.validate(unsupported, json("1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported in V27");
    }

    private void assertInvalid(JsonNode arguments) throws JsonProcessingException {
        assertThatThrownBy(() -> validator.validate(orderSchema(), arguments))
                .isInstanceOf(ToolArgumentValidationException.class);
    }

    private JsonNode orderSchema() throws JsonProcessingException {
        return json("""
                {
                  "type": "object",
                  "properties": {
                    "orderNo": {"type": "string", "minLength": 1, "maxLength": 64}
                  },
                  "required": ["orderNo"],
                  "additionalProperties": false
                }
                """);
    }

    private JsonNode json(String value) throws JsonProcessingException {
        return objectMapper.readTree(value);
    }
}
