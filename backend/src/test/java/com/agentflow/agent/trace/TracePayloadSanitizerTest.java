package com.agentflow.agent.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TracePayloadSanitizerTest {
    private static final String REDACTED = "[REDACTED]";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRecursivelyRedactNormalizedSensitiveEndpointAndReasoningKeysWithoutMutatingInput()
            throws Exception {
        JsonNode input = objectMapper.readTree("""
                {
                  "Authorization":"Bearer secret",
                  "proxy-authorization":"proxy secret",
                  "X_API_KEY":"api secret",
                  "clientSecret":"client secret",
                  "access_token":"access secret",
                  "refreshToken":"refresh secret",
                  "Endpoint":"https://internal.example/v1/chat",
                  "base_url":"https://internal.example",
                  "chainOfThought":"private reasoning",
                  "nested":[
                    {"Set_Cookie":"session=secret"},
                    {"reasoning-content":"hidden", "password":"password"}
                  ],
                  "maxTokens":4096,
                  "inputTokens":12,
                  "tokenUsage":{"outputTokens":7,"totalTokens":19}
                }
                """);
        JsonNode original = input.deepCopy();

        JsonNode safe = sanitizer(new TracePayloadProperties()).sanitizeLargeObject(input, "payload");

        assertThat(safe).isNotSameAs(input);
        assertThat(safe.path("Authorization").asText()).isEqualTo(REDACTED);
        assertThat(safe.path("proxy-authorization").asText()).isEqualTo(REDACTED);
        assertThat(safe.path("X_API_KEY").asText()).isEqualTo(REDACTED);
        assertThat(safe.path("clientSecret").asText()).isEqualTo(REDACTED);
        assertThat(safe.path("access_token").asText()).isEqualTo(REDACTED);
        assertThat(safe.path("refreshToken").asText()).isEqualTo(REDACTED);
        assertThat(safe.path("Endpoint").asText()).isEqualTo(REDACTED);
        assertThat(safe.path("base_url").asText()).isEqualTo(REDACTED);
        assertThat(safe.path("chainOfThought").asText()).isEqualTo(REDACTED);
        assertThat(safe.path("nested").path(0).path("Set_Cookie").asText()).isEqualTo(REDACTED);
        assertThat(safe.path("nested").path(1).path("reasoning-content").asText())
                .isEqualTo(REDACTED);
        assertThat(safe.path("nested").path(1).path("password").asText()).isEqualTo(REDACTED);
        assertThat(safe.path("maxTokens").asInt()).isEqualTo(4096);
        assertThat(safe.path("inputTokens").asInt()).isEqualTo(12);
        assertThat(safe.path("tokenUsage").path("outputTokens").asInt()).isEqualTo(7);
        assertThat(safe.path("tokenUsage").path("totalTokens").asInt()).isEqualTo(19);

        assertThat(input).isEqualTo(original);
        assertThat(input.path("Authorization").asText()).isEqualTo("Bearer secret");
        assertThat(input.path("nested").path(0).path("Set_Cookie").asText())
                .isEqualTo("session=secret");
    }

    @Test
    void shouldMeasureTheSerializedChinesePayloadAtTheExactUtf8BoundaryAndFailFastAboveIt()
            throws IOException {
        JsonNode payload = objectMapper.createObjectNode().put("message", "你");
        int serializedUtf8Bytes = objectMapper.writeValueAsBytes(payload).length;

        TracePayloadProperties exactProperties = new TracePayloadProperties();
        exactProperties.setSmallMaxBytes(serializedUtf8Bytes);
        assertThat(sanitizer(exactProperties).sanitizeSmallObject(payload, "summary"))
                .isEqualTo(payload);

        TracePayloadProperties tooSmallProperties = new TracePayloadProperties();
        tooSmallProperties.setSmallMaxBytes(serializedUtf8Bytes - 1);
        assertThatThrownBy(() -> sanitizer(tooSmallProperties).sanitizeSmallObject(payload, "summary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("summary exceeds the configured UTF-8 byte limit");
    }

    @Test
    void shouldRequireDecisionResponsesToBeJsonObjectsAndSanitizeTheirReasoningFields()
            throws Exception {
        TracePayloadSanitizer sanitizer = sanitizer(new TracePayloadProperties());

        String safe = sanitizer.sanitizeDecisionResponse(
                "{\"decision\":\"FINISH\",\"reasoningDetails\":\"private\"}",
                "decision response"
        );
        assertThat(objectMapper.readTree(safe).path("reasoningDetails").asText()).isEqualTo(REDACTED);

        assertThatThrownBy(() -> sanitizer.sanitizeDecisionResponse("[]", "decision response"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("decision response must be a structured JSON object");
        assertThatThrownBy(() -> sanitizer.sanitizeDecisionResponse("\"plain text\"", "decision response"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("decision response must be a structured JSON object");
        assertThatThrownBy(() -> sanitizer.sanitizeDecisionResponse("not-json", "decision response"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("decision response must be a structured JSON object");
    }

    @Test
    void shouldAcceptOnlyTheProviderNeutralLlmChatRequestShape() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {
                  "messages":[
                    {"role":"system","content":"Answer concisely."},
                    {"role":"user","content":"Hello"}
                  ],
                  "provider":"openai-compatible",
                  "model":"chat-model",
                  "requestedModel":"preferred-model",
                  "temperature":0.2,
                  "topP":0.9,
                  "maxOutputTokens":512
                }
                """);

        String safe = sanitizer(new TracePayloadProperties())
                .sanitizeLlmRequestSnapshot(request, "LLM request");

        assertThat(objectMapper.readTree(safe)).isEqualTo(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", "uri", "framework", "config", "analysis"})
    void shouldRejectUnsupportedLlmRequestFields(String fieldName) {
        ObjectNode request = validLlmRequest();
        request.put(fieldName, "must not be persisted");

        assertThatThrownBy(() -> sanitizer(new TracePayloadProperties())
                .sanitizeLlmRequestSnapshot(request, "LLM request"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LLM request contains an unsupported field: " + fieldName);
    }

    @Test
    void shouldRejectNormalizedDuplicateLlmRequestFields() {
        ObjectNode request = validLlmRequest();
        request.put("MODEL", "duplicate-model");

        assertThatThrownBy(() -> sanitizer(new TracePayloadProperties())
                .sanitizeLlmRequestSnapshot(request, "LLM request"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LLM request contains an unsupported field: MODEL");
    }

    @Test
    void shouldRedactEndpointAndCredentialAssignmentFromSafeErrorMessage() {
        String safe = sanitizer(new TracePayloadProperties()).sanitizeErrorMessage(
                "POST https://internal.example/v1/chat failed; Authorization: Bearer top-secret",
                "error"
        );

        assertThat(safe).isEqualTo("POST [REDACTED_ENDPOINT] failed; [REDACTED]");
        assertThat(safe).doesNotContain("internal.example", "Bearer", "top-secret");
    }

    private ObjectNode validLlmRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("messages")
                .addObject()
                .put("role", "user")
                .put("content", "Hello");
        request.put("model", "chat-model");
        return request;
    }

    private TracePayloadSanitizer sanitizer(TracePayloadProperties properties) {
        return new TracePayloadSanitizer(objectMapper, properties);
    }
}
