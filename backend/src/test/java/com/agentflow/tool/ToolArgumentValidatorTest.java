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
    void shouldAcceptEitherOrBothTopLevelRequiredOnlyAnyOfBranches() throws Exception {
        assertThatCode(() -> validator.validate(
                paymentLogSchema(),
                json("{\"orderNo\":\"order_1024\"}")
        )).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(
                paymentLogSchema(),
                json("{\"errorCode\":\"E_PAY_TIMEOUT\",\"limit\":20}")
        )).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(
                paymentLogSchema(),
                json("{\"orderNo\":\"order_1024\",\"errorCode\":\"E_PAY_TIMEOUT\",\"limit\":1}")
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectPaymentLogArgumentsWhenNeitherAnyOfBranchMatches() throws Exception {
        assertPaymentInvalid(json("{}"));
        assertPaymentInvalid(json("{\"limit\":10}"));
    }

    @Test
    void shouldStillValidateEveryProvidedPaymentLogPropertyBeforeExecution() throws Exception {
        assertPaymentInvalid(json("{\"orderNo\":\"   \"}"));
        assertPaymentInvalid(json("{\"errorCode\":1024}"));
        assertPaymentInvalid(json("{\"errorCode\":\"E_PAY_TIMEOUT\",\"limit\":0}"));
        assertPaymentInvalid(json("{\"errorCode\":\"E_PAY_TIMEOUT\",\"limit\":21}"));
        assertPaymentInvalid(json("{\"errorCode\":\"E_PAY_TIMEOUT\",\"extra\":true}"));
        assertPaymentInvalid(json("{\"orderNo\":\"" + "a".repeat(65) + "\"}"));
    }

    @Test
    void shouldFailClosedForNestedOrNonRequiredOnlyAnyOfDefinitions() throws Exception {
        JsonNode nestedAnyOf = json("""
                {
                  "type":"object",
                  "properties":{
                    "filter":{
                      "type":"object",
                      "properties":{"orderNo":{"type":"string"}},
                      "anyOf":[{"required":["orderNo"]}]
                    }
                  }
                }
                """);
        JsonNode expandedBranch = json("""
                {
                  "type":"object",
                  "properties":{"orderNo":{"type":"string"}},
                  "anyOf":[{"required":["orderNo"],"type":"object"}]
                }
                """);

        assertThatThrownBy(() -> validator.validate(nestedAnyOf, json("{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top level");
        assertThatThrownBy(() -> validator.validate(expandedBranch, json("{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only required");
    }

    @Test
    void shouldFailClosedForUnsupportedTypesInsteadOfSilentlyAcceptingThem() throws Exception {
        JsonNode unsupported = json("{\"type\":\"number\"}");

        assertThatThrownBy(() -> validator.validate(unsupported, json("1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported in V28");
    }

    private void assertInvalid(JsonNode arguments) throws JsonProcessingException {
        assertThatThrownBy(() -> validator.validate(orderSchema(), arguments))
                .isInstanceOf(ToolArgumentValidationException.class);
    }

    private void assertPaymentInvalid(JsonNode arguments) throws JsonProcessingException {
        assertThatThrownBy(() -> validator.validate(paymentLogSchema(), arguments))
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

    private JsonNode paymentLogSchema() throws JsonProcessingException {
        return json("""
                {
                  "type": "object",
                  "properties": {
                    "orderNo": {"type": "string", "minLength": 1, "maxLength": 64},
                    "errorCode": {"type": "string", "minLength": 1, "maxLength": 64},
                    "limit": {"type": "integer", "minimum": 1, "maximum": 20, "default": 10}
                  },
                  "anyOf": [
                    {"required": ["orderNo"]},
                    {"required": ["errorCode"]}
                  ],
                  "additionalProperties": false
                }
                """);
    }

    private JsonNode json(String value) throws JsonProcessingException {
        return objectMapper.readTree(value);
    }
}
