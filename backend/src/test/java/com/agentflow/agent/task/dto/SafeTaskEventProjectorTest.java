package com.agentflow.agent.task.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.agent.task.model.AgentTaskEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SafeTaskEventProjectorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SafeTaskEventProjector projector =
            new SafeTaskEventProjector(new SafeTaskPayloadProjector(json), json);

    @ParameterizedTest
    @MethodSource("publicEventPayloads")
    void shouldKeepOnlyTheFrozenBusinessFieldsForEveryExistingEventType(String type, String expectedJson)
            throws Exception {
        JsonNode expected = json.readTree(expectedJson);
        ObjectNode persisted = expected.deepCopy();
        if (persisted.has("stepId")) persisted.put("stepId", 9007199254740993L);
        persisted.putObject("extraMetadata").put("secret", "private").put("arbitrary", "private");
        persisted.put("reasoning", "private");
        persisted.put("endpoint", "https://private.example");
        persisted.put("apiKey", "private");

        SafeTaskEventResponse result = projector.toResponse(event(type, persisted.toString()));

        assertThat(result.eventType()).isEqualTo(type);
        assertThat(result.payload()).isEqualTo(expected);
        assertThat(result.payload().toString()).doesNotContain("private", "extraMetadata", "endpoint");
        ((ObjectNode) result.payload()).put("mutated", true);
        assertThat(result.payload().has("mutated")).isFalse();
    }

    @Test
    void shouldRejectUnknownTypesOrUnstructuredPayloadRatherThanPublishAnUnfrozenProtocol() {
        assertThatThrownBy(() -> projector.toResponse(event("INTERNAL_DEBUG", "{\"secret\":\"private\"}")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> projector.toResponse(event("TASK_CREATED", "[]")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> projector.toResponse(event("TASK_CREATED", "{\"status\":{\"unfrozen\":true}}")))
                .isInstanceOf(IllegalStateException.class);
    }

    private AgentTaskEvent event(String type, String payload) {
        AgentTaskEvent event = new AgentTaskEvent();
        event.setId(101L);
        event.setTaskId(91L);
        event.setSequenceNo(3L);
        event.setEventType(type);
        event.setPayload(payload);
        return event;
    }

    static Stream<Arguments> publicEventPayloads() {
        return Stream.of(
                Arguments.of("TASK_CREATED", "{\"status\":\"QUEUED\"}"),
                Arguments.of("TASK_STARTED", "{\"status\":\"RUNNING\",\"phase\":\"PREPARING\"}"),
                Arguments.of("PHASE_CHANGED", "{\"phase\":\"DECIDING\"}"),
                Arguments.of("RAG_FINISHED", """
                        {"stepId":"9007199254740993","validHitCount":2,"candidateCount":3,"staleHitCount":1}
                        """),
                Arguments.of("DECISION_FINISHED", """
                        {"stepId":"9007199254740993","totalTokens":80,"usageQuality":"EXACT","decisionType":"CALL_TOOL"}
                        """),
                Arguments.of("TOOL_STARTED", """
                        {"stepId":"9007199254740993","toolCode":"query_order","reused":false}
                        """),
                Arguments.of("TOOL_FINISHED", """
                        {"stepId":"9007199254740993","toolCode":"query_order","reused":false,"status":"FAILED","errorCode":"AGENT_TOOL_FAILED"}
                        """),
                Arguments.of("FINAL_GENERATION_STARTED", "{\"maxOutputTokens\":2048}"),
                Arguments.of("ANSWER_CHUNK", "{\"chunkIndex\":0,\"text\":\"answer [S1].\"}"),
                Arguments.of("TASK_COMPLETED", "{\"status\":\"COMPLETED\",\"terminationReason\":\"ANSWERED\"}"),
                Arguments.of("TASK_FAILED", """
                        {"status":"FAILED","terminationReason":"SYSTEM_ERROR","errorCode":"TASK_DISPATCH_REJECTED"}
                        """),
                Arguments.of("TASK_CANCELLED", "{\"status\":\"CANCELLED\",\"terminationReason\":\"USER_CANCELLED\"}"),
                Arguments.of("TASK_TIMED_OUT", "{\"status\":\"TIMED_OUT\",\"terminationReason\":\"DEADLINE_EXCEEDED\"}")
        );
    }
}
