package com.agentflow.agent.task.recovery;

import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.TaskEventType;
import com.agentflow.agent.task.model.TaskStatus;
import com.agentflow.agent.task.service.TaskEventAppender;
import com.agentflow.agent.trace.model.LlmCallLogRecord;
import com.agentflow.agent.trace.repository.LlmCallLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Settles one confirmed former execution without invoking any executor or external dependency. */
@Service
public class TaskRecoveryTransactionService {
    private final TaskRecoveryMapper recoveryMapper;
    private final LlmCallLogMapper llmCalls;
    private final TaskEventAppender eventAppender;
    private final ObjectMapper json;
    private final Clock clock;

    public TaskRecoveryTransactionService(TaskRecoveryMapper recoveryMapper, LlmCallLogMapper llmCalls,
                                          TaskEventAppender eventAppender, ObjectMapper json, Clock clock) {
        this.recoveryMapper = recoveryMapper;
        this.llmCalls = llmCalls;
        this.eventAppender = eventAppender;
        this.json = json;
        this.clock = clock;
    }

    /** The startup coordinator owns the closed admission gate and JVM process lock. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public boolean recover(long taskId, String recoveryRunId) {
        if (taskId <= 0) throw new IllegalArgumentException("taskId must be positive");
        UUID.fromString(recoveryRunId);
        AgentTask task = recoveryMapper.lockTask(taskId);
        if (task == null || TaskStatus.valueOf(task.getStatus()).isTerminal()) return false;

        boolean queued = "QUEUED".equals(task.getStatus());
        if (queued && (recoveryMapper.hasExecutionEvidence(taskId)
                || task.getDecisionTurnsUsed() != 0 || task.getToolCallsUsed() != 0
                || task.getInputTokens() != 0 || task.getOutputTokens() != 0 || task.getTotalTokens() != 0)) {
            throw invariant(taskId, "QUEUED_HAS_EXECUTION_EVIDENCE");
        }
        if (task.getRecoveryMetadata() != null || recoveryMapper.hasTerminalPublicationEvidence(taskId)) {
            throw invariant(taskId, "NONTERMINAL_TASK_HAS_TERMINAL_PUBLICATION");
        }
        if (recoveryMapper.hasPendingToolCalls(taskId)) {
            throw invariant(taskId, "UNEXPECTED_PENDING_TOOL_RECORD");
        }

        RecordedUsage usage = aggregate(taskId, llmCalls.selectByTaskIdOrdered(taskId));
        if (task.getTotalTokens() != 0 && (task.getInputTokens() != usage.inputTokens()
                || task.getOutputTokens() != usage.outputTokens() || task.getTotalTokens() != usage.totalTokens())) {
            throw invariant(taskId, "TASK_USAGE_CONFLICTS_WITH_RECORDED_CALLS");
        }

        OffsetDateTime recoveredAt = OffsetDateTime.now(clock);
        String reasonCode = queued ? "TASK_RESTART_DISPATCH_LOST" : "TASK_RESTART_INTERRUPTED";
        boolean cancelled = task.getCancelRequestedAt() != null;
        String status = cancelled ? "CANCELLED" : "FAILED";
        String terminationReason = cancelled ? "USER_CANCELLED" : "SYSTEM_ERROR";
        String completeness = queued ? "COMPLETE" : "UNCONFIRMED";
        ObjectNode metadata = json.createObjectNode()
                .put("schemaVersion", "task-recovery-v1")
                .put("mode", "CONTROLLED_SINGLE_HOST")
                .put("recoveryRunId", recoveryRunId)
                .put("recoveredAt", recoveredAt.toString())
                .put("previousStatus", task.getStatus())
                .put("reasonCode", reasonCode)
                .put("executionOutcome", queued ? "NOT_STARTED" : "UNKNOWN")
                .put("recordCompleteness", completeness)
                .put("counterCompleteness", completeness)
                .put("recordedLlmCalls", usage.recordCount())
                .put("recordedToolCalls", recoveryMapper.countToolCalls(taskId));
        metadata.set("recordedUsage", usageNode(usage.inputTokens(), usage.outputTokens(), usage.quality()));
        metadata.set("previousTaskUsage", usageNode(task.getInputTokens(), task.getOutputTokens(),
                task.getTokenUsageQuality()));
        if (queued && cancelled) metadata.put("queuedCancellationAnomaly", true);

        // These mapper calls and the original event appender share this one physical transaction.
        // An invariant violation, zero-row conditional update or event failure rolls all of them back.
        recoveryMapper.finishRunningSteps(taskId, recoveredAt);
        recoveryMapper.finishRunningToolCalls(taskId, recoveredAt);
        if (recoveryMapper.settleTask(taskId, task.getStatus(), task.getVersion(), task.getCancelRequestedAt(),
                status, terminationReason, usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                cancelled ? null : reasonCode,
                cancelled ? null : queued ? "Queued dispatch was lost when the local process exited"
                        : "Local execution process interrupted; final outcome unconfirmed",
                recoveredAt, metadata.toString()) != 1) {
            throw invariant(taskId, "CONDITIONAL_SETTLEMENT_DID_NOT_UPDATE_ONE_TASK");
        }

        ObjectNode payload = json.createObjectNode().put("status", status).put("terminationReason", terminationReason);
        if (!cancelled) payload.put("errorCode", reasonCode);
        ObjectNode summary = payload.putObject("recovery");
        for (String field : List.of("schemaVersion", "recoveryRunId", "previousStatus", "reasonCode",
                "recordCompleteness", "counterCompleteness")) {
            summary.set(field, metadata.get(field));
        }
        eventAppender.append(taskId, cancelled ? TaskEventType.TASK_CANCELLED : TaskEventType.TASK_FAILED, payload);
        return true;
    }

    private ObjectNode usageNode(int input, int output, String quality) {
        return json.createObjectNode().put("inputTokens", input).put("outputTokens", output)
                .put("totalTokens", Math.addExact(input, output)).put("tokenUsageQuality", quality);
    }

    private RecordedUsage aggregate(long taskId, List<LlmCallLogRecord> records) {
        Set<Long> ids = new HashSet<>();
        int input = 0;
        int output = 0;
        boolean exact = false;
        boolean estimated = false;
        try {
            for (LlmCallLogRecord row : records) {
                if (row.getId() == null || row.getTaskId() == null || row.getTaskId() != taskId) {
                    throw invariant(taskId, "CALL_RECORD_OWNERSHIP_INVALID");
                }
                if (!ids.add(row.getId())) continue;
                if ("UNKNOWN".equals(row.getUsageQuality())) {
                    if (row.getInputTokens() != null || row.getOutputTokens() != null || row.getTotalTokens() != null) {
                        throw invariant(taskId, "UNKNOWN_CALL_HAS_TOKEN_VALUES");
                    }
                    continue;
                }
                if (row.getInputTokens() == null || row.getOutputTokens() == null || row.getTotalTokens() == null
                        || row.getInputTokens() < 0 || row.getOutputTokens() < 0
                        || Math.addExact(row.getInputTokens(), row.getOutputTokens()) != row.getTotalTokens()) {
                    throw invariant(taskId, "CALL_USAGE_INVALID");
                }
                switch (row.getUsageQuality()) {
                    case "EXACT" -> exact = true;
                    case "ESTIMATED" -> estimated = true;
                    case "MIXED" -> { exact = true; estimated = true; }
                    default -> throw invariant(taskId, "CALL_USAGE_QUALITY_INVALID");
                }
                input = Math.addExact(input, row.getInputTokens());
                output = Math.addExact(output, row.getOutputTokens());
            }
            Math.addExact(input, output);
        } catch (ArithmeticException ex) {
            throw invariant(taskId, "RECORDED_USAGE_OVERFLOW");
        }
        return new RecordedUsage(input, output, exact && estimated ? "MIXED"
                : exact ? "EXACT" : estimated ? "ESTIMATED" : "UNKNOWN", ids.size());
    }

    private static TaskRecoveryInvariantException invariant(long taskId, String reason) {
        return new TaskRecoveryInvariantException(taskId, reason);
    }

    private record RecordedUsage(int inputTokens, int outputTokens, String quality, int recordCount) {
        int totalTokens() { return Math.addExact(inputTokens, outputTokens); }
    }
}
