package com.agentflow.agent.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.agent.task.dto.SafeTaskEventProjector;
import com.agentflow.agent.task.dto.SafeTaskPayloadProjector;
import com.agentflow.agent.task.model.AgentTaskEvent;
import com.agentflow.agent.trace.dto.TaskTraceView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicTaskTraceProjectorTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final SafeTaskPayloadProjector payloads = new SafeTaskPayloadProjector(objectMapper);
    private final PublicTaskTraceProjector traces = new PublicTaskTraceProjector(objectMapper, payloads);

    @Test
    void shouldMaskNestedSecretsAndStringifyBusinessIdsWithoutChangingUsageCounters() {
        JsonNode result = payloads.parse("""
                {"stepId":9007199254740993,"chunk_id":9007199254740994,"documentIdSnapshot":91,
                 "knowledgeBaseIds":[92,93],"totalTokens":120,"maxOutputTokens":300,
                 "sequenceNo":8,"vectorGeneration":2,"valid":1,
                 "nested":{"api_key":"key","Reasoning_Content":"private","base-url":"https://private"},
                 "responseText":"{\\"toolId\\":94,\\"analysis\\":\\"private\\",\\"result\\":\\"ok\\"}"}
                """);
        assertThat(result.path("stepId").textValue()).isEqualTo("9007199254740993");
        assertThat(result.path("chunk_id").textValue()).isEqualTo("9007199254740994");
        assertThat(result.path("documentIdSnapshot").textValue()).isEqualTo("91");
        assertThat(result.path("knowledgeBaseIds").get(0).textValue()).isEqualTo("92");
        assertThat(result.path("totalTokens").isInt()).isTrue();
        assertThat(result.path("sequenceNo").isInt()).isTrue();
        assertThat(result.path("vectorGeneration").isInt()).isTrue();
        assertThat(result.path("valid").isInt()).isTrue();
        assertThat(result.toString()).doesNotContain("private");
        assertThat(payloads.parse(result.path("responseText").textValue()).path("toolId").textValue())
                .isEqualTo("94");
        assertThatThrownBy(() -> payloads.parse("{} trailing"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payloads.projectErrorMessage("Failed to connect https://internal.example/v1"))
                .isEqualTo("Failed to connect [REDACTED]");
        JsonNode diagnostics = payloads.parse("""
                {"nested":{"errorMessage":"Failed jdbc:postgresql://internal/db"},
                 "sourceUrl":"https://public.example/source"}
                """);
        assertThat(diagnostics.path("nested").path("errorMessage").textValue())
                .isEqualTo("Failed [REDACTED]");
        assertThat(diagnostics.path("sourceUrl").textValue()).isEqualTo("https://public.example/source");
    }

    @Test
    void shouldAllowlistSnapshotStructureIncludingNestedRuntimeAndToolConfiguration() {
        JsonNode result = traces.executionSnapshot("""
                {"snapshotVersion":"v1","unexpectedRoot":"private",
                 "agent":{"agentId":91,"systemPrompt":"Use evidence","apiKey":"private"},
                 "runtime":{"decisionProtocolVersion":"v1","applicationRevision":"rev","config":{"secret":"private"}},
                 "chatModel":{"profileCode":"test","model":"model","endpoint":"https://private","credentials":"private"},
                 "retrieval":{"topK":3,"knowledgeBases":[{"knowledgeBaseId":92,"documents":[{"documentId":93,"vectorGeneration":4,"storageObjectKey":"private"}]}]},
                 "tools":[{"toolId":94,"toolCode":"query","config":{"endpoint":"https://private"},"inputSchema":{"type":"object","apiKey":"private"}}]}
                """);
        assertThat(result.path("agent").path("agentId").textValue()).isEqualTo("91");
        assertThat(result.path("retrieval").path("knowledgeBases").get(0).path("documents")
                .get(0).path("documentId").textValue()).isEqualTo("93");
        assertThat(result.path("tools").get(0).path("toolId").textValue()).isEqualTo("94");
        assertThat(result.path("runtime").has("config")).isFalse();
        assertThat(result.path("chatModel").has("endpoint")).isFalse();
        assertThat(result.path("tools").get(0).has("config")).isFalse();
        assertThat(result.toString()).doesNotContain("private", "unexpectedRoot", "storageObjectKey");
    }

    @Test
    void shouldExposeImmutableTypedStepsWithStringIdsAndAllowlistedLlmRequests() {
        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("messages").addObject().put("role", "USER").put("content", "question")
                .put("internalMetadata", "private");
        request.put("customProviderConfig", "private");
        var call = new TaskTraceView.LlmCall(9007199254740993L, "DECISION", "test", "model", "model",
                request, "{\"action\":\"FINISH\",\"reasoning\":\"private\"}", "stop", "provider-id",
                5, 3, 8, "REPORTED", 2, "SUCCESS", null, null, OffsetDateTime.now());
        var step = new TaskTraceView.Step(9007199254740994L, 0, "LLM_DECISION", "SUCCESS", "Decision",
                objectMapper.createObjectNode().put("stepId", 9007199254740994L), null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), 2L, OffsetDateTime.now(), List.of(call),
                List.of(), List.of());
        var result = traces.steps(new TaskTraceView(91, "RUNNING", List.of(step)));
        assertThat(result.getFirst().id()).isEqualTo("9007199254740994");
        assertThat(result.getFirst().llmCalls().getFirst().id()).isEqualTo("9007199254740993");
        assertThat(result.getFirst().llmCalls().getFirst().requestSnapshot().toString()).doesNotContain("private");
        assertThat(result.getFirst().llmCalls().getFirst().requestSnapshot().has("responseFormat")).isFalse();
        assertThat(result.getFirst().llmCalls().getFirst().requestSnapshot().has("thinkingMode")).isFalse();
        assertThat(result.getFirst().llmCalls().getFirst().responseText()).doesNotContain("private");
        assertThat(result.getFirst().summary().path("stepId").textValue()).isEqualTo("9007199254740994");
        ((ObjectNode) result.getFirst().summary()).put("mutated", true);
        assertThat(result.getFirst().summary().has("mutated")).isFalse();
    }

    @Test
    void shouldPublishTheSafeNamedResponseSchemaWithoutAdditionalWrapperConfiguration() throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("messages").addObject().put("role", "USER").put("content", "question");
        request.set("responseSchema", objectMapper.readTree("""
                {"name":"agent_decision-v1","schema":{"type":"object","additionalProperties":false,
                  "properties":{"type":{"enum":["CALL_TOOL","FINISH"]}},
                  "metadata":{"secret":"schema-private","items":[{"api_key":"nested-private"}]}},
                 "providerConfiguration":"wrapper-private"}
                """));
        JsonNode original = request.deepCopy();
        var call = new TaskTraceView.LlmCall(101L, "DECISION", "openai-compatible", "model", "model",
                request, "{\"type\":\"FINISH\"}", "stop", "provider-id",
                5, 3, 8, "EXACT", 2, "SUCCESS", null, null, OffsetDateTime.now());
        var step = new TaskTraceView.Step(102L, 0, "LLM_DECISION", "SUCCESS", "Decision",
                objectMapper.createObjectNode(), null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                2L, OffsetDateTime.now(), List.of(call), List.of(), List.of());

        var projectedCall = traces.steps(new TaskTraceView(91, "RUNNING", List.of(step)))
                .getFirst().llmCalls().getFirst();
        JsonNode responseSchema = projectedCall.requestSnapshot().path("responseSchema");
        assertThat(responseSchema.size()).isEqualTo(2);
        assertThat(responseSchema.path("name").textValue()).isEqualTo("agent_decision-v1");
        assertThat(responseSchema.path("schema").path("properties").path("type").path("enum").get(0).textValue())
                .isEqualTo("CALL_TOOL");
        assertThat(responseSchema.path("schema").path("additionalProperties").booleanValue()).isFalse();
        assertThat(responseSchema.path("schema").path("metadata").path("secret").textValue()).isEqualTo("[REDACTED]");
        assertThat(responseSchema.path("schema").path("metadata").path("items").get(0).path("api_key").textValue())
                .isEqualTo("[REDACTED]");
        assertThat(responseSchema.toString()).doesNotContain("private", "providerConfiguration");
        ((ObjectNode) responseSchema).put("name", "changed");
        assertThat(projectedCall.requestSnapshot().path("responseSchema").path("name").textValue())
                .isEqualTo("agent_decision-v1");
        assertThat(request).isEqualTo(original);
    }

    @Test
    void shouldExposeJsonObjectAndDisabledThinkingWithoutExposingNestedThinkingContent() {
        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("messages").addObject().put("role", "USER").put("content",
                "{\"result\":\"ok\",\"nested\":[{\"thinking\":\"private\",\"api_key\":\"secret-key\"}]}");
        request.put("responseFormat", "json_object").put("thinkingMode", "disabled");
        request.putObject("providerConfiguration").put("thinking", "private");
        JsonNode original = request.deepCopy();

        JsonNode safe = projectRequest(request, "DECISION");

        assertThat(safe.path("responseFormat").textValue()).isEqualTo("json_object");
        assertThat(safe.path("thinkingMode").textValue()).isEqualTo("disabled");
        assertThat(safe.has("responseSchema")).isFalse();
        assertThat(safe.toString()).doesNotContain("private", "secret-key", "providerConfiguration");
        assertThat(request).isEqualTo(original);

        request.remove("responseFormat");
        JsonNode finalRequest = projectRequest(request, "FINAL_GENERATION");
        assertThat(finalRequest.path("thinkingMode").textValue()).isEqualTo("disabled");
        assertThat(finalRequest.has("responseFormat")).isFalse();
        assertThat(finalRequest.has("responseSchema")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"responseFormat", "thinkingMode"})
    void shouldRejectMalformedFormatAndThinkingValuesInStoredRequests(String field) throws Exception {
        for (String invalid : List.of("null", "{}", "[]", "true", "7", "\"\"", "\"enabled\"",
                "\"JSON_OBJECT\"", "\"DISABLED\"", "\"disabled \"", "\"json_schema\"")) {
            ObjectNode request = objectMapper.createObjectNode();
            request.putArray("messages").addObject().put("role", "USER").put("content", "question");
            request.set(field, objectMapper.readTree(invalid));
            assertThatThrownBy(() -> projectRequest(request, "DECISION"))
                    .as("%s=%s", field, invalid).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void shouldRejectConflictingResponseFormatsInStoredRequests() {
        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("messages").addObject().put("role", "USER").put("content", "question");
        request.put("responseFormat", "json_object");
        request.putObject("responseSchema").put("name", "decision").putObject("schema");

        assertThatThrownBy(() -> projectRequest(request, "DECISION"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LLM request responseFormat and responseSchema are mutually exclusive");
    }

    @Test
    void shouldKeepAnswerChunksExactlyConsistentWithFinalAnswerWhileMaskingEventMetadata() throws Exception {
        var events = new SafeTaskEventProjector(payloads, objectMapper);
        List<String> chunks = List.of("{\"password\":\"example\",\"id\":91}", "\napi_key=example", "{\"part\":", "\"value\"}");
        StringBuilder answer = new StringBuilder();
        for (String chunk : chunks) {
            AgentTaskEvent event = new AgentTaskEvent();
            event.setId(9007199254740993L);
            event.setTaskId(91L);
            event.setSequenceNo(2L);
            event.setEventType("ANSWER_CHUNK");
            event.setPayload(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("text", chunk).put("chunkIndex", 0).put("stepId", 92L).put("apiKey", "secret-value")));
            var result = events.toResponse(event);
            answer.append(result.payload().path("text").textValue());
            assertThat(result.id()).isEqualTo("9007199254740993");
            assertThat(result.payload().has("stepId")).isFalse();
            assertThat(result.payload().has("apiKey")).isFalse();
        }
        assertThat(answer.toString()).isEqualTo(String.join("", chunks));
    }

    private JsonNode projectRequest(ObjectNode request, String callType) {
        var call = new TaskTraceView.LlmCall(101L, callType, "openai-compatible", "model", "model",
                request, "answer", "stop", "provider-id", 5, 3, 8, "EXACT", 2,
                "SUCCESS", null, null, OffsetDateTime.now());
        var step = new TaskTraceView.Step(102L, 0, "LLM_DECISION", "SUCCESS", "Decision",
                objectMapper.createObjectNode(), null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                2L, OffsetDateTime.now(), List.of(call), List.of(), List.of());
        return traces.steps(new TaskTraceView(91, "RUNNING", List.of(step)))
                .getFirst().llmCalls().getFirst().requestSnapshot();
    }
}
