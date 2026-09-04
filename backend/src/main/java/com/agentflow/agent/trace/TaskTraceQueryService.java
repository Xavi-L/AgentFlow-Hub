package com.agentflow.agent.trace;

import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.repository.AgentTaskMapper;
import com.agentflow.agent.trace.dto.TaskTraceView;
import com.agentflow.agent.trace.model.AgentStepRecord;
import com.agentflow.agent.trace.model.LlmCallLogRecord;
import com.agentflow.agent.trace.model.RagRetrievalHitLogRecord;
import com.agentflow.agent.trace.model.RagRetrievalLogRecord;
import com.agentflow.agent.trace.repository.AgentStepMapper;
import com.agentflow.agent.trace.repository.LlmCallLogMapper;
import com.agentflow.agent.trace.repository.RagRetrievalHitLogMapper;
import com.agentflow.agent.trace.repository.RagRetrievalLogMapper;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.tool.model.ToolCallLogRecord;
import com.agentflow.tool.repository.ToolCallLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owner-scoped, repeatable-read aggregation skeleton for internal task Trace consumers. */
@Service
public class TaskTraceQueryService {
    private final AgentTaskMapper taskMapper;
    private final AgentStepMapper stepMapper;
    private final LlmCallLogMapper llmCallMapper;
    private final RagRetrievalLogMapper ragRetrievalMapper;
    private final RagRetrievalHitLogMapper ragHitMapper;
    private final ToolCallLogMapper toolCallMapper;
    private final ObjectMapper objectMapper;

    public TaskTraceQueryService(
            AgentTaskMapper taskMapper,
            AgentStepMapper stepMapper,
            LlmCallLogMapper llmCallMapper,
            RagRetrievalLogMapper ragRetrievalMapper,
            RagRetrievalHitLogMapper ragHitMapper,
            ToolCallLogMapper toolCallMapper,
            ObjectMapper objectMapper
    ) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.stepMapper = Objects.requireNonNull(stepMapper, "stepMapper must not be null");
        this.llmCallMapper = Objects.requireNonNull(llmCallMapper, "llmCallMapper must not be null");
        this.ragRetrievalMapper = Objects.requireNonNull(
                ragRetrievalMapper,
                "ragRetrievalMapper must not be null"
        );
        this.ragHitMapper = Objects.requireNonNull(ragHitMapper, "ragHitMapper must not be null");
        this.toolCallMapper = Objects.requireNonNull(toolCallMapper, "toolCallMapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public TaskTraceView findOwnedTrace(long userId, long taskId) {
        if (userId <= 0 || taskId <= 0) {
            throw new IllegalArgumentException("userId and taskId must be positive");
        }
        AgentTask task = taskMapper.selectOwnedById(taskId, userId);
        if (task == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent task not found");
        }

        List<AgentStepRecord> steps = stepMapper.selectByTaskIdOrdered(taskId);
        Map<Long, List<TaskTraceView.LlmCall>> llmByStep = groupLlmCalls(taskId);
        Map<Long, List<TaskTraceView.RagRetrieval>> ragByStep = groupRagRetrievals(taskId);
        Map<Long, List<TaskTraceView.ToolCall>> toolByStep = groupToolCalls(taskId);

        List<TaskTraceView.Step> resultSteps = new ArrayList<>(steps.size());
        for (AgentStepRecord step : steps) {
            resultSteps.add(new TaskTraceView.Step(
                    step.getId(),
                    step.getStepIndex(),
                    step.getStepType(),
                    step.getStatus(),
                    step.getTitle(),
                    parseRequiredJson(step.getSummaryJson(), "agent_step.summary"),
                    step.getErrorCode(),
                    step.getErrorMessage(),
                    step.getStartedAt(),
                    step.getEndedAt(),
                    step.getLatencyMs(),
                    step.getCreatedAt(),
                    llmByStep.getOrDefault(step.getId(), List.of()),
                    ragByStep.getOrDefault(step.getId(), List.of()),
                    toolByStep.getOrDefault(step.getId(), List.of())
            ));
        }
        return new TaskTraceView(taskId, task.getStatus(), resultSteps);
    }

    private Map<Long, List<TaskTraceView.LlmCall>> groupLlmCalls(long taskId) {
        Map<Long, List<TaskTraceView.LlmCall>> grouped = new HashMap<>();
        for (LlmCallLogRecord row : llmCallMapper.selectByTaskIdOrdered(taskId)) {
            TaskTraceView.LlmCall view = new TaskTraceView.LlmCall(
                    row.getId(), row.getCallType(), row.getProvider(), row.getRequestedModel(),
                    row.getResolvedModel(), parseRequiredJson(row.getRequestSnapshotJson(), "llm request snapshot"),
                    row.getResponseText(), row.getFinishReason(), row.getProviderRequestId(),
                    row.getInputTokens(), row.getOutputTokens(), row.getTotalTokens(), row.getUsageQuality(),
                    row.getLatencyMs(), row.getStatus(), row.getErrorCode(), row.getErrorMessage(), row.getCreatedAt()
            );
            grouped.computeIfAbsent(row.getStepId(), ignored -> new ArrayList<>()).add(view);
        }
        return grouped;
    }

    private Map<Long, List<TaskTraceView.RagRetrieval>> groupRagRetrievals(long taskId) {
        Map<Long, List<TaskTraceView.RagHit>> hitsByRetrieval = new HashMap<>();
        for (RagRetrievalHitLogRecord row : ragHitMapper.selectByTaskIdOrdered(taskId)) {
            TaskTraceView.RagHit view = new TaskTraceView.RagHit(
                    row.getId(), row.getRankNo(), row.getCitationId(), row.getChunkIdSnapshot(),
                    row.getDocumentIdSnapshot(), row.getKnowledgeBaseIdSnapshot(), row.getVectorGeneration(),
                    row.getScore(), row.getContentSnapshot(),
                    parseRequiredJson(row.getMetadataSnapshotJson(), "RAG hit metadata"), row.getCreatedAt()
            );
            hitsByRetrieval.computeIfAbsent(row.getRetrievalId(), ignored -> new ArrayList<>()).add(view);
        }

        Map<Long, List<TaskTraceView.RagRetrieval>> grouped = new HashMap<>();
        for (RagRetrievalLogRecord row : ragRetrievalMapper.selectByTaskIdOrdered(taskId)) {
            TaskTraceView.RagRetrieval view = new TaskTraceView.RagRetrieval(
                    row.getId(), row.getQuery(), row.getEmbeddingProfileCode(),
                    parseRequiredJson(row.getCorpusSnapshotJson(), "RAG corpus snapshot"), row.getTopK(),
                    row.getSimilarityThreshold(), row.getCandidateCount(), row.getValidHitCount(),
                    row.getStaleHitCount(), row.getLatencyMs(), row.getStatus(), row.getErrorCode(),
                    row.getErrorMessage(), row.getCreatedAt(),
                    hitsByRetrieval.getOrDefault(row.getId(), List.of())
            );
            grouped.computeIfAbsent(row.getStepId(), ignored -> new ArrayList<>()).add(view);
        }
        return grouped;
    }

    private Map<Long, List<TaskTraceView.ToolCall>> groupToolCalls(long taskId) {
        Map<Long, List<TaskTraceView.ToolCall>> grouped = new HashMap<>();
        for (ToolCallLogRecord row : toolCallMapper.selectByTaskIdOrdered(taskId)) {
            if (row.getStepId() == null) {
                throw new IllegalStateException("A task-scoped tool log has no step association");
            }
            TaskTraceView.ToolCall view = new TaskTraceView.ToolCall(
                    row.getId(), row.getToolId(), row.getToolCode(), row.getToolName(),
                    parseRequiredJson(row.getArgumentsJson(), "tool arguments"),
                    parseOptionalJson(row.getResultJson(), "tool result"), row.getStatus(), row.getRetryCount(),
                    row.getLatencyMs(), row.getErrorCode(), row.getErrorMessage(), row.getStartedAt(),
                    row.getFinishedAt(), row.getCreatedAt()
            );
            grouped.computeIfAbsent(row.getStepId(), ignored -> new ArrayList<>()).add(view);
        }
        return grouped;
    }

    private JsonNode parseRequiredJson(String serialized, String label) {
        if (serialized == null) {
            throw new IllegalStateException(label + " is unexpectedly null");
        }
        return parseJson(serialized, label);
    }

    private JsonNode parseOptionalJson(String serialized, String label) {
        return serialized == null ? null : parseJson(serialized, label);
    }

    private JsonNode parseJson(String serialized, String label) {
        try {
            JsonNode parsed = objectMapper.readTree(serialized);
            if (parsed == null) {
                throw new IllegalStateException(label + " is invalid");
            }
            return parsed;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(label + " is invalid", ex);
        }
    }
}
