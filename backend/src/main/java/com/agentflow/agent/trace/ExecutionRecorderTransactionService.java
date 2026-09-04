package com.agentflow.agent.trace;

import com.agentflow.agent.task.service.TaskEventAppender;
import com.agentflow.agent.trace.model.AgentStepRecord;
import com.agentflow.agent.trace.model.LlmCallLogRecord;
import com.agentflow.agent.trace.model.RagRetrievalHitLogRecord;
import com.agentflow.agent.trace.model.RagRetrievalLogRecord;
import com.agentflow.agent.trace.repository.AgentStepMapper;
import com.agentflow.agent.trace.repository.LlmCallLogMapper;
import com.agentflow.agent.trace.repository.RagRetrievalHitLogMapper;
import com.agentflow.agent.trace.repository.RagRetrievalLogMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring-managed short-transaction boundary used by lightweight task-bound recorder objects.
 * No external provider, vector-store, or tool-handler I/O belongs in this service.
 */
@Service
public class ExecutionRecorderTransactionService {
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_ERROR_CODE_LENGTH = 64;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final AgentStepMapper stepMapper;
    private final LlmCallLogMapper llmCallMapper;
    private final RagRetrievalLogMapper ragRetrievalMapper;
    private final RagRetrievalHitLogMapper ragHitMapper;
    private final TaskEventAppender eventAppender;
    private final TracePayloadSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ExecutionRecorderTransactionService(
            AgentStepMapper stepMapper,
            LlmCallLogMapper llmCallMapper,
            RagRetrievalLogMapper ragRetrievalMapper,
            RagRetrievalHitLogMapper ragHitMapper,
            TaskEventAppender eventAppender,
            TracePayloadSanitizer sanitizer,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.stepMapper = Objects.requireNonNull(stepMapper, "stepMapper must not be null");
        this.llmCallMapper = Objects.requireNonNull(llmCallMapper, "llmCallMapper must not be null");
        this.ragRetrievalMapper = Objects.requireNonNull(
                ragRetrievalMapper,
                "ragRetrievalMapper must not be null"
        );
        this.ragHitMapper = Objects.requireNonNull(ragHitMapper, "ragHitMapper must not be null");
        this.eventAppender = Objects.requireNonNull(eventAppender, "eventAppender must not be null");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StepHandle startStep(long taskId, StepType type, String title) {
        requirePositive(taskId, "taskId");
        Objects.requireNonNull(type, "type must not be null");
        requireBoundedText(title, MAX_TITLE_LENGTH, "title");

        Long lockedTaskId = stepMapper.lockRunningTask(taskId);
        if (lockedTaskId == null) {
            throw new IllegalStateException("A Trace step requires an existing RUNNING task");
        }
        Integer stepIndex = stepMapper.selectNextStepIndexWhileTaskLocked(taskId);
        if (stepIndex == null || stepIndex < 0) {
            throw new IllegalStateException("The next task step index could not be allocated");
        }

        OffsetDateTime startedAt = now();
        AgentStepRecord row = new AgentStepRecord();
        row.setId(IdWorker.getId());
        row.setTaskId(taskId);
        row.setStepIndex(stepIndex);
        row.setStepType(type.name());
        row.setStatus("RUNNING");
        row.setTitle(title);
        row.setSummaryJson(sanitizer.sanitizeSmallJson(objectMapper.createObjectNode(), "step summary"));
        row.setStartedAt(startedAt);
        row.setCreatedAt(startedAt);
        if (stepMapper.insertStep(row) != 1) {
            throw new IllegalStateException("Expected exactly one RUNNING Agent step to be inserted");
        }
        return new StepHandle(taskId, row.getId(), stepIndex, type);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeStep(StepHandle step, StepSummary summary) {
        Objects.requireNonNull(step, "step must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        String safeSummary = sanitizer.sanitizeSmallJson(summary.value(), "step summary");
        if (stepMapper.completeRunning(step.taskId(), step.stepId(), safeSummary, now()) != 1) {
            throw new IllegalStateException("Expected one RUNNING Agent step to become SUCCESS");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failStep(StepHandle step, String errorCode, String safeMessage) {
        Objects.requireNonNull(step, "step must not be null");
        requireBoundedText(errorCode, MAX_ERROR_CODE_LENGTH, "errorCode");
        requireBoundedText(safeMessage, MAX_ERROR_MESSAGE_LENGTH, "safeMessage");
        String sanitizedMessage = sanitizer.sanitizeErrorMessage(safeMessage, "step error message");
        requireBoundedText(sanitizedMessage, MAX_ERROR_MESSAGE_LENGTH, "sanitized safeMessage");
        if (stepMapper.failRunning(step.taskId(), step.stepId(), errorCode, sanitizedMessage, now()) != 1) {
            throw new IllegalStateException("Expected one RUNNING Agent step to become FAILED");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLlmCall(LlmCallRecord input) {
        Objects.requireNonNull(input, "input must not be null");
        requireBoundedText(input.provider(), 64, "provider");
        requireBoundedText(input.requestedModel(), 128, "requestedModel");
        requireOptionalBoundedText(input.resolvedModel(), 128, "resolvedModel");
        requireOptionalBoundedText(input.finishReason(), 64, "finishReason");
        requireOptionalBoundedText(input.providerRequestId(), 255, "providerRequestId");
        requireOptionalBoundedText(input.errorCode(), MAX_ERROR_CODE_LENGTH, "errorCode");
        requireOptionalBoundedText(input.errorMessage(), MAX_ERROR_MESSAGE_LENGTH, "errorMessage");

        LlmCallLogRecord row = new LlmCallLogRecord();
        row.setId(IdWorker.getId());
        row.setTaskId(input.step().taskId());
        row.setStepId(input.step().stepId());
        row.setCallType(input.callType().name());
        row.setProvider(input.provider());
        row.setRequestedModel(input.requestedModel());
        row.setResolvedModel(input.resolvedModel());
        row.setRequestSnapshotJson(
                sanitizer.sanitizeLlmRequestSnapshot(input.requestSnapshot(), "LLM request snapshot")
        );
        row.setResponseText(safeLlmResponse(input));
        row.setFinishReason(input.finishReason());
        row.setProviderRequestId(input.providerRequestId());
        row.setInputTokens(input.inputTokens());
        row.setOutputTokens(input.outputTokens());
        row.setTotalTokens(input.totalTokens());
        row.setUsageQuality(input.usageQuality().name());
        row.setLatencyMs(input.latencyMs());
        row.setStatus(input.status().name());
        row.setErrorCode(input.errorCode());
        row.setErrorMessage(safeErrorMessage(input.errorMessage(), "LLM error message"));
        row.setCreatedAt(now());
        if (llmCallMapper.insertCall(row) != 1) {
            throw new IllegalStateException("Expected exactly one LLM call log to be inserted");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRagRetrieval(RagRetrievalRecord input) {
        Objects.requireNonNull(input, "input must not be null");
        requireBoundedText(input.embeddingProfileCode(), 128, "embeddingProfileCode");
        requireOptionalBoundedText(input.errorCode(), MAX_ERROR_CODE_LENGTH, "errorCode");
        requireOptionalBoundedText(input.errorMessage(), MAX_ERROR_MESSAGE_LENGTH, "errorMessage");

        OffsetDateTime createdAt = now();
        RagRetrievalLogRecord retrieval = new RagRetrievalLogRecord();
        retrieval.setId(IdWorker.getId());
        retrieval.setTaskId(input.step().taskId());
        retrieval.setStepId(input.step().stepId());
        retrieval.setQuery(sanitizer.sanitizeLargeText(input.query(), "RAG query"));
        retrieval.setEmbeddingProfileCode(input.embeddingProfileCode());
        retrieval.setCorpusSnapshotJson(
                sanitizer.sanitizeLargeJson(input.corpusSnapshot(), "RAG corpus snapshot")
        );
        retrieval.setTopK(input.topK());
        retrieval.setSimilarityThreshold(input.similarityThreshold());
        retrieval.setCandidateCount(input.candidateCount());
        retrieval.setValidHitCount(input.validHitCount());
        retrieval.setStaleHitCount(input.staleHitCount());
        retrieval.setLatencyMs(input.latencyMs());
        retrieval.setStatus(input.status().name());
        retrieval.setErrorCode(input.errorCode());
        retrieval.setErrorMessage(safeErrorMessage(input.errorMessage(), "RAG error message"));
        retrieval.setCreatedAt(createdAt);
        if (ragRetrievalMapper.insertRetrieval(retrieval) != 1) {
            throw new IllegalStateException("Expected exactly one RAG retrieval log to be inserted");
        }

        for (RagRetrievalHitRecord inputHit : input.hits()) {
            requireBoundedText(inputHit.citationId(), 32, "citationId");
            RagRetrievalHitLogRecord hit = new RagRetrievalHitLogRecord();
            hit.setId(IdWorker.getId());
            hit.setRetrievalId(retrieval.getId());
            hit.setRankNo(inputHit.rankNo());
            hit.setCitationId(inputHit.citationId());
            hit.setChunkIdSnapshot(inputHit.chunkIdSnapshot());
            hit.setDocumentIdSnapshot(inputHit.documentIdSnapshot());
            hit.setKnowledgeBaseIdSnapshot(inputHit.knowledgeBaseIdSnapshot());
            hit.setVectorGeneration(inputHit.vectorGeneration());
            hit.setScore(inputHit.score());
            hit.setContentSnapshot(
                    sanitizer.sanitizeRagHitContent(inputHit.contentSnapshot(), "RAG hit content")
            );
            hit.setMetadataSnapshotJson(
                    sanitizer.sanitizeSmallJson(inputHit.metadataSnapshot(), "RAG hit metadata")
            );
            hit.setCreatedAt(createdAt);
            if (ragHitMapper.insertHit(hit) != 1) {
                throw new IllegalStateException("Expected exactly one RAG retrieval hit to be inserted");
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendEvent(long taskId, TaskEventRecord event) {
        requirePositive(taskId, "taskId");
        Objects.requireNonNull(event, "event must not be null");
        JsonNode safePayload = sanitizer.sanitizeSmallObject(event.payload(), "task event payload");
        eventAppender.append(taskId, event.eventType(), safePayload);
    }

    private String safeLlmResponse(LlmCallRecord input) {
        if (input.responseText() == null) {
            return null;
        }
        return input.callType() == LlmCallType.DECISION
                ? sanitizer.sanitizeDecisionResponse(input.responseText(), "LLM decision response")
                : sanitizer.sanitizeLargeText(input.responseText(), "LLM final response");
    }

    private String safeErrorMessage(String errorMessage, String label) {
        if (errorMessage == null) {
            return null;
        }
        String sanitized = sanitizer.sanitizeErrorMessage(errorMessage, label);
        requireBoundedText(sanitized, MAX_ERROR_MESSAGE_LENGTH, "sanitized errorMessage");
        return sanitized;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireBoundedText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + maxLength + " characters");
        }
    }

    private static void requireOptionalBoundedText(String value, int maxLength, String field) {
        if (value != null && (value.isBlank() || value.length() > maxLength)) {
            throw new IllegalArgumentException(field + " must be null or at most " + maxLength + " characters");
        }
    }
}
