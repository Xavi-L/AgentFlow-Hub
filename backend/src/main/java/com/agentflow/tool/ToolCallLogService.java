package com.agentflow.tool;

import com.agentflow.agent.trace.TracePayloadProperties;
import com.agentflow.agent.trace.TracePayloadSanitizer;
import com.agentflow.tool.model.ToolCallLogRecord;
import com.agentflow.tool.repository.ToolCallLogMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits each tool-call lifecycle write independently from handler transactions. */
@Service
public class ToolCallLogService {
    private final ToolCallLogMapper toolCallLogMapper;
    private final ObjectMapper objectMapper;
    private final TracePayloadSanitizer sanitizer;
    private final Clock clock;

    @Autowired
    public ToolCallLogService(
            ToolCallLogMapper toolCallLogMapper,
            ObjectMapper objectMapper,
            TracePayloadSanitizer sanitizer,
            Clock clock
    ) {
        this.toolCallLogMapper = Objects.requireNonNull(
                toolCallLogMapper,
                "toolCallLogMapper must not be null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Retains the V27 unit-construction seam while applying V39's default payload limits. */
    public ToolCallLogService(
            ToolCallLogMapper toolCallLogMapper,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this(
                toolCallLogMapper,
                objectMapper,
                new TracePayloadSanitizer(objectMapper, new TracePayloadProperties()),
                clock
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordRunning(
            ToolDefinition tool,
            ToolExecutionCommand command,
            OffsetDateTime startedAt
    ) {
        ToolCallLogRecord record = baseRecord(tool, command, startedAt);
        record.setStatus("RUNNING");
        insertExactlyOne(record);
        return record.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(
            ToolDefinition tool,
            ToolExecutionCommand command,
            ToolExecutionResult result,
            OffsetDateTime startedAt
    ) {
        ToolCallLogRecord record = baseRecord(tool, command, startedAt);
        record.setResultJson(sanitizer.sanitizeToolJson(result, "tool result"));
        record.setStatus("REJECTED");
        record.setLatencyMs(result.latencyMs());
        record.setErrorCode(result.errorCode());
        record.setErrorMessage(result.errorMessage());
        record.setFinishedAt(OffsetDateTime.now(clock));
        insertExactlyOne(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long callId, ToolExecutionResult result) {
        updateTerminal(callId, "SUCCESS", result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailed(Long callId, ToolExecutionResult result) {
        updateTerminal(callId, "FAILED", result);
    }

    private ToolCallLogRecord baseRecord(
            ToolDefinition tool,
            ToolExecutionCommand command,
            OffsetDateTime startedAt
    ) {
        ToolCallLogRecord record = new ToolCallLogRecord();
        record.setId(IdWorker.getId());
        record.setTaskId(command.taskId());
        record.setStepId(command.stepId());
        record.setToolId(tool.id());
        record.setToolCode(tool.toolCode());
        record.setToolName(tool.name());
        record.setArgumentsJson(sanitizer.sanitizeToolJson(safeArguments(command.arguments()), "tool arguments"));
        record.setRetryCount(0);
        record.setStartedAt(startedAt);
        record.setCreatedAt(startedAt);
        return record;
    }

    private void updateTerminal(Long callId, String status, ToolExecutionResult result) {
        ToolCallLogRecord record = new ToolCallLogRecord();
        record.setId(callId);
        record.setResultJson(sanitizer.sanitizeToolJson(result, "tool result"));
        record.setStatus(status);
        record.setLatencyMs(result.latencyMs());
        record.setErrorCode(result.errorCode());
        record.setErrorMessage(result.errorMessage());
        record.setFinishedAt(OffsetDateTime.now(clock));
        int affectedRows = toolCallLogMapper.updateRunningToTerminal(record);
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected one RUNNING tool_call_log row to become terminal");
        }
    }

    private void insertExactlyOne(ToolCallLogRecord record) {
        int affectedRows = toolCallLogMapper.insertCall(record);
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one tool_call_log row to be inserted");
        }
    }

    private JsonNode safeArguments(JsonNode arguments) {
        if (arguments != null && arguments.isObject()) {
            return arguments;
        }
        ObjectNode omitted = objectMapper.createObjectNode();
        omitted.put("snapshotOmitted", true);
        omitted.put("reason", "NON_OBJECT_ARGUMENTS");
        omitted.put("originalType", arguments == null ? "MISSING" : arguments.getNodeType().name());
        return omitted;
    }
}
