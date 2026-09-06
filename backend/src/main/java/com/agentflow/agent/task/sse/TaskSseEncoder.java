package com.agentflow.agent.task.sse;

import com.agentflow.agent.task.dto.SafeTaskEventResponse;
import com.agentflow.agent.task.dto.TaskEventBatch;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Compact single-line JSON protects SSE framing while preserving answer string contents. */
@Component
public class TaskSseEncoder {
    private final ObjectWriter json;

    public TaskSseEncoder(ObjectMapper mapper) {
        this.json = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
    }

    List<Frame> batch(TaskEventBatch batch, int maxBytes) {
        List<Frame> frames = new ArrayList<>();
        int bytes = 0;
        for (SafeTaskEventResponse event : batch.events()) {
            Frame frame = new Frame(event.sequenceNo(), utf8("id: " + event.sequenceNo()
                    + "\nevent: " + event.eventType() + "\ndata: " + serialize(Map.of(
                    "taskId", event.taskId(), "sequenceNo", event.sequenceNo(), "eventType", event.eventType(),
                    "timestamp", event.createdAt().toString(), "payload", event.payload())) + "\n\n"));
            if (frame.bytes().length > maxBytes) {
                throw new BusinessException(ErrorCode.TASK_SSE_EVENT_TOO_LARGE);
            }
            if (frame.bytes().length > maxBytes - bytes) break;
            frames.add(frame);
            bytes += frame.bytes().length;
        }
        return List.copyOf(frames);
    }

    Frame error(Map<String, Object> data) {
        return new Frame(null, utf8("event: STREAM_ERROR\ndata: " + serialize(data) + "\n\n"));
    }

    static Frame comment(String text) {
        return new Frame(null, utf8(": " + text + "\n\n"));
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize safe SSE event", ex);
        }
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    record Frame(Long sequence, byte[] bytes) { }
}
