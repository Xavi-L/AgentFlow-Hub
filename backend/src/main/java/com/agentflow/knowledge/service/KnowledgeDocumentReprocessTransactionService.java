package com.agentflow.knowledge.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.model.DocumentParseStatus;
import com.agentflow.knowledge.model.DocumentReprocessCleanupStatus;
import com.agentflow.knowledge.model.KnowledgeDocument;
import com.agentflow.knowledge.model.KnowledgeDocumentReprocessTask;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentReprocessTaskMapper;
import com.agentflow.user.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short PostgreSQL transactions for V25; no external gateway is called here. */
@Service
public class KnowledgeDocumentReprocessTransactionService {
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentReprocessTaskMapper taskMapper;

    public KnowledgeDocumentReprocessTransactionService(
            KnowledgeDocumentMapper documentMapper,
            KnowledgeChunkMapper chunkMapper,
            KnowledgeDocumentReprocessTaskMapper taskMapper
    ) {
        this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper must not be null");
        this.chunkMapper = Objects.requireNonNull(chunkMapper, "chunkMapper must not be null");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReprocessAdmission admitOrResumeCompletedOwned(
            AuthenticatedUser currentUser,
            Long documentId
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        validatePositive(documentId, "documentId");

        KnowledgeDocument document = documentMapper.selectVisibleOwnedForReprocessForUpdate(
                documentId,
                currentUser.id()
        );
        if (document == null) {
            throw documentNotFound();
        }

        if (DocumentParseStatus.COMPLETED.name().equals(document.getParseStatus())) {
            return admitCompleted(document, currentUser.id());
        }
        if (DocumentParseStatus.REPROCESSING.name().equals(document.getParseStatus())) {
            return resumeReprocessing(document, currentUser.id());
        }
        throw conflict();
    }

    private ReprocessAdmission admitCompleted(KnowledgeDocument document, long userId) {
        if (chunkMapper.hasProcessingChunkByDocumentScope(
                document.getId(),
                document.getKnowledgeBaseId(),
                userId
        )) {
            throw conflict();
        }
        Long sourceGeneration = document.getVectorGeneration();
        if (sourceGeneration == null || sourceGeneration < 0 || sourceGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("Document vector generation cannot be advanced");
        }

        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeDocument transitioned = documentMapper.transitionCompletedToReprocessing(
                document.getId(),
                document.getKnowledgeBaseId(),
                userId,
                sourceGeneration,
                now
        );
        if (transitioned == null) {
            if (documentMapper.selectVisibleOwnedById(document.getId(), userId) == null) {
                throw documentNotFound();
            }
            throw new IllegalStateException("Could not admit completed document for reprocessing");
        }

        KnowledgeDocumentReprocessTask task = new KnowledgeDocumentReprocessTask();
        task.setUserId(userId);
        task.setKnowledgeBaseId(document.getKnowledgeBaseId());
        task.setDocumentId(document.getId());
        task.setSourceVectorGeneration(sourceGeneration);
        task.setCleanupStatus(DocumentReprocessCleanupStatus.VECTOR_DELETING.name());
        task.setRetryCount(0);
        task.setLastAttemptedAt(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        if (taskMapper.insert(task) != 1) {
            throw new IllegalStateException("Expected exactly one inserted reprocess task");
        }
        return new ReprocessAdmission(task, true);
    }

    private ReprocessAdmission resumeReprocessing(KnowledgeDocument document, long userId) {
        KnowledgeDocumentReprocessTask task = taskMapper.selectActiveByDocumentScopeForUpdate(
                document.getId(),
                document.getKnowledgeBaseId(),
                userId
        );
        if (task == null) {
            throw conflict();
        }
        requireMatchingGeneration(document, task);

        DocumentReprocessCleanupStatus status = parseStatus(task.getCleanupStatus());
        if (status == DocumentReprocessCleanupStatus.READY_TO_FINALIZE) {
            return new ReprocessAdmission(task, false);
        }
        if (status != DocumentReprocessCleanupStatus.VECTOR_DELETE_RETRYABLE) {
            throw conflict();
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (taskMapper.claimVectorRetry(
                task.getId(), task.getDocumentId(), task.getKnowledgeBaseId(), task.getUserId(), now
        ) != 1) {
            throw conflict();
        }
        task.setCleanupStatus(DocumentReprocessCleanupStatus.VECTOR_DELETING.name());
        task.setFailureSummary(null);
        task.setLastAttemptedAt(now);
        task.setUpdatedAt(now);
        return new ReprocessAdmission(task, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markVectorsDeleted(KnowledgeDocumentReprocessTask task) {
        validateTask(task);
        OffsetDateTime now = OffsetDateTime.now();
        if (taskMapper.markVectorsDeleted(
                task.getId(), task.getDocumentId(), task.getKnowledgeBaseId(), task.getUserId(), now
        ) != 1) {
            throw new IllegalStateException("Could not record reprocess vector cleanup");
        }
        task.setCleanupStatus(DocumentReprocessCleanupStatus.READY_TO_FINALIZE.name());
        task.setVectorsDeletedAt(now);
        task.setFailureSummary(null);
        task.setUpdatedAt(now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordVectorFailure(KnowledgeDocumentReprocessTask task, String failureSummary) {
        validateTask(task);
        String safeSummary = Objects.requireNonNull(failureSummary, "failureSummary must not be null");
        if (safeSummary.isBlank() || safeSummary.length() > 500) {
            throw new IllegalArgumentException("failureSummary must contain at most 500 characters");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (taskMapper.recordVectorFailure(
                task.getId(), task.getDocumentId(), task.getKnowledgeBaseId(), task.getUserId(), safeSummary, now
        ) != 1) {
            throw new IllegalStateException("Could not record reprocess vector failure");
        }
        task.setCleanupStatus(DocumentReprocessCleanupStatus.VECTOR_DELETE_RETRYABLE.name());
        task.setFailureSummary(safeSummary);
        task.setRetryCount((task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1);
        task.setLastFailedAt(now);
        task.setUpdatedAt(now);
    }

    /** Locks document before task, then commits chunks + PENDING + task completion together. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KnowledgeDocumentResponse deleteChunksAndRequeue(KnowledgeDocumentReprocessTask task) {
        validateTask(task);
        KnowledgeDocument document = documentMapper.selectReprocessScopeForUpdate(
                task.getDocumentId(), task.getKnowledgeBaseId(), task.getUserId()
        );
        if (document == null) {
            throw conflict();
        }
        KnowledgeDocumentReprocessTask lockedTask = taskMapper.selectExactForUpdate(
                task.getId(), task.getDocumentId(), task.getKnowledgeBaseId(), task.getUserId()
        );
        if (lockedTask == null
                || !DocumentReprocessCleanupStatus.READY_TO_FINALIZE.name().equals(lockedTask.getCleanupStatus())
                || lockedTask.getVectorsDeletedAt() == null
                || lockedTask.getCompletedAt() != null) {
            throw conflict();
        }
        requireMatchingGeneration(document, lockedTask);

        chunkMapper.deleteByDocumentScope(
                task.getDocumentId(), task.getKnowledgeBaseId(), task.getUserId()
        );
        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeDocument pending = documentMapper.transitionReprocessingToPending(
                task.getDocumentId(),
                task.getKnowledgeBaseId(),
                task.getUserId(),
                task.getSourceVectorGeneration() + 1,
                now
        );
        if (pending == null) {
            throw conflict();
        }
        if (taskMapper.markCompleted(
                task.getId(), task.getDocumentId(), task.getKnowledgeBaseId(), task.getUserId(), now
        ) != 1) {
            throw new IllegalStateException("Could not complete reprocess task");
        }
        return KnowledgeDocumentResponse.from(pending);
    }

    private static void requireMatchingGeneration(
            KnowledgeDocument document,
            KnowledgeDocumentReprocessTask task
    ) {
        if (document.getVectorGeneration() == null
                || task.getSourceVectorGeneration() == null
                || task.getSourceVectorGeneration() == Long.MAX_VALUE
                || document.getVectorGeneration() != task.getSourceVectorGeneration() + 1) {
            throw new IllegalStateException("Reprocess vector generation invariant failed");
        }
    }

    private static DocumentReprocessCleanupStatus parseStatus(String status) {
        try {
            return DocumentReprocessCleanupStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalStateException("Unknown reprocess cleanup status", invalid);
        }
    }

    private static void validateTask(KnowledgeDocumentReprocessTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (task.getId() == null || task.getDocumentId() == null || task.getKnowledgeBaseId() == null
                || task.getUserId() == null || task.getSourceVectorGeneration() == null) {
            throw new IllegalArgumentException("Task id, scope, and source vector generation are required");
        }
    }

    private static void validatePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, fieldName + " must be a positive integer");
        }
    }

    private static BusinessException documentNotFound() {
        return new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Document not found");
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT);
    }

    public record ReprocessAdmission(KnowledgeDocumentReprocessTask task, boolean vectorDeletionRequired) {
        public ReprocessAdmission {
            Objects.requireNonNull(task, "task must not be null");
        }
    }
}
