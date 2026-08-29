package com.agentflow.knowledge.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.model.DocumentParseStatus;
import com.agentflow.knowledge.model.KnowledgeDocument;
import com.agentflow.knowledge.model.KnowledgeDocumentDeletionTask;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentDeletionTaskMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentMapper;
import com.agentflow.user.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：V24 删除准入和每个 PostgreSQL 状态写入的短事务边界。向量库和文件存储绝不在这里
 * 调用：它们由编排层在事务外执行，随后把完成或失败证据写回本类。
 *
 * <p>English: V24's short transaction boundary for deletion admission and PostgreSQL state
 * writes. It never calls the vector store or source storage; the orchestration layer invokes
 * those external systems outside transactions and returns durable completion/failure evidence
 * here afterward.
 */
@Service
public class KnowledgeDocumentDeletionTransactionService {
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocumentDeletionTaskMapper deletionTaskMapper;

    public KnowledgeDocumentDeletionTransactionService(
            KnowledgeDocumentMapper knowledgeDocumentMapper,
            KnowledgeChunkMapper knowledgeChunkMapper,
            KnowledgeDocumentDeletionTaskMapper deletionTaskMapper
    ) {
        this.knowledgeDocumentMapper = Objects.requireNonNull(
                knowledgeDocumentMapper,
                "knowledgeDocumentMapper must not be null"
        );
        this.knowledgeChunkMapper = Objects.requireNonNull(
                knowledgeChunkMapper,
                "knowledgeChunkMapper must not be null"
        );
        this.deletionTaskMapper = Objects.requireNonNull(
                deletionTaskMapper,
                "deletionTaskMapper must not be null"
        );
    }

    /**
     * Locks the document row shared with V24's vector-claim gate. A new task is created in the
     * same transaction as document soft deletion; an already soft-deleted document may resume
     * only a still-incomplete task owned by the same caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KnowledgeDocumentDeletionTask admitOrResumeOwned(
            AuthenticatedUser currentUser,
            Long documentId
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        validatePositive(documentId, "documentId");

        KnowledgeDocument document = knowledgeDocumentMapper.selectOwnedWithLiveParentForDeletionForUpdate(
                documentId,
                currentUser.id()
        );
        if (document == null) {
            throw documentNotFound();
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (document.getDeletedAt() != null) {
            KnowledgeDocumentDeletionTask task = deletionTaskMapper.selectIncompleteByDocumentScopeForUpdate(
                    document.getId(),
                    document.getKnowledgeBaseId(),
                    currentUser.id()
            );
            if (task == null) {
                // Fully completed tasks leave their soft-deleted document invisible. A retry is
                // meaningful only while a durable task still has unfinished cleanup work.
                throw documentNotFound();
            }
            if (deletionTaskMapper.recordAttempt(
                    task.getId(),
                    task.getDocumentId(),
                    task.getKnowledgeBaseId(),
                    task.getUserId(),
                    now
            ) != 1) {
                throw documentNotFound();
            }
            task.setLastAttemptedAt(now);
            task.setUpdatedAt(now);
            return task;
        }

        if (DocumentParseStatus.PROCESSING.name().equals(document.getParseStatus())
                || knowledgeChunkMapper.hasProcessingChunkByDocumentScope(
                        document.getId(),
                        document.getKnowledgeBaseId(),
                        currentUser.id()
                )) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_DELETION_CONFLICT);
        }

        int softDeletedRows = knowledgeDocumentMapper.softDeleteOwnedWithLiveParent(
                document.getId(),
                currentUser.id(),
                now
        );
        if (softDeletedRows == 0) {
            throw documentNotFound();
        }
        if (softDeletedRows != 1) {
            throw new IllegalStateException("Expected exactly one soft-deleted knowledge_document row");
        }

        KnowledgeDocumentDeletionTask task = new KnowledgeDocumentDeletionTask();
        task.setUserId(currentUser.id());
        task.setKnowledgeBaseId(document.getKnowledgeBaseId());
        task.setDocumentId(document.getId());
        task.setStorageBucket(document.getStorageBucket());
        task.setStorageObjectKey(document.getStorageObjectKey());
        task.setRetryCount(0);
        task.setLastAttemptedAt(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        int insertedRows = deletionTaskMapper.insert(task);
        if (insertedRows != 1) {
            throw new IllegalStateException("Expected exactly one inserted knowledge_document_deletion_task row");
        }
        return task;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markVectorsDeleted(KnowledgeDocumentDeletionTask task) {
        markStep(task, DeletionStep.VECTORS, OffsetDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSourceDeleted(KnowledgeDocumentDeletionTask task) {
        markStep(task, DeletionStep.SOURCE, OffsetDateTime.now());
    }

    /**
     * Physically deletes the scoped chunks and marks the final two task fields in one short
     * transaction. The source document metadata remains the soft-deleted audit row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteChunksAndMarkCompleted(KnowledgeDocumentDeletionTask task) {
        validateTaskScope(task);
        knowledgeChunkMapper.deleteByDocumentScope(
                task.getDocumentId(),
                task.getKnowledgeBaseId(),
                task.getUserId()
        );

        OffsetDateTime now = OffsetDateTime.now();
        int completedRows = deletionTaskMapper.markChunksDeletedAndCompleted(
                task.getId(),
                task.getDocumentId(),
                task.getKnowledgeBaseId(),
                task.getUserId(),
                now
        );
        if (completedRows == 1) {
            task.setChunksDeletedAt(now);
            task.setCompletedAt(now);
            task.setFailureSummary(null);
            task.setUpdatedAt(now);
            return;
        }

        KnowledgeDocumentDeletionTask current = deletionTaskMapper.selectByDocumentScope(
                task.getDocumentId(),
                task.getKnowledgeBaseId(),
                task.getUserId()
        );
        if (current != null && current.getCompletedAt() != null) {
            // A concurrent same-owner retry finished the idempotent cleanup first.
            task.setChunksDeletedAt(current.getChunksDeletedAt());
            task.setCompletedAt(current.getCompletedAt());
            task.setFailureSummary(current.getFailureSummary());
            task.setUpdatedAt(current.getUpdatedAt());
            return;
        }
        throw new IllegalStateException("Could not complete knowledge_document_deletion_task");
    }

    /** Records a controlled, retryable external failure without exposing provider detail to HTTP. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordExternalFailure(KnowledgeDocumentDeletionTask task, String failureSummary) {
        validateTaskScope(task);
        if (failureSummary == null || failureSummary.isBlank() || failureSummary.length() > 500) {
            throw new IllegalArgumentException("failureSummary must contain at most 500 characters");
        }
        OffsetDateTime now = OffsetDateTime.now();
        int affectedRows = deletionTaskMapper.recordFailure(
                task.getId(),
                task.getDocumentId(),
                task.getKnowledgeBaseId(),
                task.getUserId(),
                failureSummary,
                now
        );
        if (affectedRows == 1) {
            task.setFailureSummary(failureSummary);
            task.setLastFailedAt(now);
            task.setUpdatedAt(now);
            task.setRetryCount((task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1);
        }
    }

    private void markStep(
            KnowledgeDocumentDeletionTask task,
            DeletionStep step,
            OffsetDateTime completedAt
    ) {
        validateTaskScope(task);
        int affectedRows = switch (step) {
            case VECTORS -> deletionTaskMapper.markVectorsDeleted(
                    task.getId(),
                    task.getDocumentId(),
                    task.getKnowledgeBaseId(),
                    task.getUserId(),
                    completedAt
            );
            case SOURCE -> deletionTaskMapper.markSourceDeleted(
                    task.getId(),
                    task.getDocumentId(),
                    task.getKnowledgeBaseId(),
                    task.getUserId(),
                    completedAt
            );
        };
        if (affectedRows == 1) {
            if (step == DeletionStep.VECTORS) {
                task.setVectorsDeletedAt(completedAt);
            } else {
                task.setSourceDeletedAt(completedAt);
            }
            task.setFailureSummary(null);
            task.setUpdatedAt(completedAt);
            return;
        }

        KnowledgeDocumentDeletionTask current = deletionTaskMapper.selectByDocumentScope(
                task.getDocumentId(),
                task.getKnowledgeBaseId(),
                task.getUserId()
        );
        boolean alreadyCompleted = current != null
                && (step == DeletionStep.VECTORS
                ? current.getVectorsDeletedAt() != null
                : current.getSourceDeletedAt() != null);
        if (!alreadyCompleted) {
            throw new IllegalStateException("Could not record knowledge_document_deletion_task step");
        }
        if (step == DeletionStep.VECTORS) {
            task.setVectorsDeletedAt(current.getVectorsDeletedAt());
        } else {
            task.setSourceDeletedAt(current.getSourceDeletedAt());
        }
        task.setFailureSummary(current.getFailureSummary());
        task.setUpdatedAt(current.getUpdatedAt());
    }

    private static void validateTaskScope(KnowledgeDocumentDeletionTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (task.getId() == null
                || task.getDocumentId() == null
                || task.getKnowledgeBaseId() == null
                || task.getUserId() == null) {
            throw new IllegalArgumentException("Task id, documentId, knowledgeBaseId, and userId are required");
        }
    }

    private static void validatePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    fieldName + " must be a positive integer"
            );
        }
    }

    private static BusinessException documentNotFound() {
        return new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Document not found");
    }

    private enum DeletionStep {
        VECTORS,
        SOURCE
    }
}
