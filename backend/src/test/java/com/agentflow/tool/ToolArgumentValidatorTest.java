package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
    void shouldAcceptRequiredOnlyAndCompleteReportArguments() throws Exception {
        assertThatCode(() -> validator.validate(
                reportSchema(),
                json("{\"title\":\"支付失败报告\",\"summary\":\"订单支付失败。\"}")
        )).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(
                reportSchema(),
                json("""
                        {
                          "title":"支付失败报告",
                          "summary":"订单支付失败。",
                          "rootCause":"支付网关响应超时。",
                          "suggestions":["检查网关状态。","确认订单未重复扣款。"]
                        }
                        """)
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectEveryInvalidReportStringBeforeExecution() throws Exception {
        assertReportInvalid(json("{\"summary\":\"订单支付失败。\"}"));
        assertReportInvalid(json("{\"title\":\"报告\"}"));
        assertReportInvalid(json("{\"title\":null,\"summary\":\"结论\"}"));
        assertReportInvalid(json("{\"title\":29,\"summary\":\"结论\"}"));
        assertReportInvalid(json("{\"title\":\"报告\",\"summary\":\"\"}"));
        assertReportInvalid(json("{\"title\":\"报告\",\"summary\":\"结论\",\"rootCause\":\"   \"}"));
        assertReportInvalid(json("{\"title\":\"" + "a".repeat(256) + "\",\"summary\":\"结论\"}"));
        assertReportInvalid(json("{\"title\":\"报告\",\"summary\":\"" + "a".repeat(4001) + "\"}"));
        assertReportInvalid(json("{\"title\":\"报告\",\"summary\":\"结论\",\"extra\":true}"));
    }

    @Test
    void shouldRejectInvalidSuggestionArraysAndElementsBeforeExecution() throws Exception {
        assertReportInvalid(json("{\"title\":\"报告\",\"summary\":\"结论\",\"suggestions\":[]}"));
        assertReportInvalid(json("{\"title\":\"报告\",\"summary\":\"结论\",\"suggestions\":\"检查\"}"));
        assertReportInvalid(json("{\"title\":\"报告\",\"summary\":\"结论\",\"suggestions\":[1]}"));
        assertReportInvalid(json("{\"title\":\"报告\",\"summary\":\"结论\",\"suggestions\":[\"\"]}"));
        assertReportInvalid(json("{\"title\":\"报告\",\"summary\":\"结论\",\"suggestions\":[\"   \"]}"));
        assertReportInvalid(json(
                "{\"title\":\"报告\",\"summary\":\"结论\",\"suggestions\":[\""
                        + "a".repeat(1001)
                        + "\"]}"
        ));

        com.fasterxml.jackson.databind.node.ObjectNode tooMany = objectMapper.createObjectNode()
                .put("title", "报告")
                .put("summary", "结论");
        for (int index = 0; index < 21; index++) {
            tooMany.withArray("suggestions").add("建议" + index);
        }
        assertReportInvalid(tooMany);
    }

    @Test
    void shouldFailClosedForUnsupportedArrayDefinitions() throws Exception {
        List<String> unsupportedSchemas = List.of(
                "{\"type\":\"array\"}",
                "{\"type\":\"array\",\"items\":[{\"type\":\"string\"}]}",
                "{\"type\":\"array\",\"items\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}",
                "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"contains\":{\"type\":\"string\"}}",
                "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"uniqueItems\":true}",
                "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":2,\"maxItems\":1}"
        );

        for (String unsupportedSchema : unsupportedSchemas) {
            assertThatThrownBy(() -> validator.validate(json(unsupportedSchema), json("[]")))
                    .isInstanceOf(IllegalStateException.class);
        }
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
                .hasMessageContaining("unsupported in V29");
    }

    private void assertInvalid(JsonNode arguments) throws JsonProcessingException {
        assertThatThrownBy(() -> validator.validate(orderSchema(), arguments))
                .isInstanceOf(ToolArgumentValidationException.class);
    }

    private void assertPaymentInvalid(JsonNode arguments) throws JsonProcessingException {
        assertThatThrownBy(() -> validator.validate(paymentLogSchema(), arguments))
                .isInstanceOf(ToolArgumentValidationException.class);
    }

    private void assertReportInvalid(JsonNode arguments) throws JsonProcessingException {
        assertThatThrownBy(() -> validator.validate(reportSchema(), arguments))
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

    private JsonNode reportSchema() throws JsonProcessingException {
        return json("""
                {
                  "type": "object",
                  "properties": {
                    "title": {"type": "string", "minLength": 1, "maxLength": 255},
                    "summary": {"type": "string", "minLength": 1, "maxLength": 4000},
                    "rootCause": {"type": "string", "minLength": 1, "maxLength": 4000},
                    "suggestions": {
                      "type": "array",
                      "minItems": 1,
                      "maxItems": 20,
                      "items": {"type": "string", "minLength": 1, "maxLength": 1000}
                    }
                  },
                  "required": ["title", "summary"],
                  "additionalProperties": false
                }
                """);
    }

    private JsonNode json(String value) throws JsonProcessingException {
        return objectMapper.readTree(value);
    }
}
