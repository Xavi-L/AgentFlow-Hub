package com.agentflow.knowledge.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.model.KnowledgeDocumentDeletionTask;
import com.agentflow.knowledge.storage.DocumentStorage;
import com.agentflow.knowledge.storage.StoredDocument;
import com.agentflow.knowledge.vector.VectorDocumentScope;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.user.security.AuthenticatedUser;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 中文：V24 当前 owner 的同步文档删除编排。它先持久化准入任务，再在事务外按固定顺序清理
 * vectors、源文件和 chunks；只有三个步骤都完成才回到 Controller 的 HTTP 200。
 *
 * <p>English: V24's synchronous current-owner document-deletion orchestration. It persists
 * admission first, then clears vectors, the source object, and chunks outside database
 * transactions in that order; only completion of all three lets the controller return HTTP 200.
 */
@Service
public class KnowledgeDocumentDeletionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentDeletionService.class);
    private static final String VECTOR_DELETION_FAILURE = "Vector deletion failed";
    private static final String SOURCE_DELETION_FAILURE = "Source document deletion failed";

    private final KnowledgeDocumentDeletionTransactionService transactionService;
    private final VectorStoreGateway vectorStoreGateway;
    private final DocumentStorage documentStorage;

    public KnowledgeDocumentDeletionService(
            KnowledgeDocumentDeletionTransactionService transactionService,
            VectorStoreGateway vectorStoreGateway,
            DocumentStorage documentStorage
    ) {
        this.transactionService = Objects.requireNonNull(
                transactionService,
                "transactionService must not be null"
        );
        this.vectorStoreGateway = Objects.requireNonNull(
                vectorStoreGateway,
                "vectorStoreGateway must not be null"
        );
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage must not be null");
    }

    public void deleteOwned(AuthenticatedUser currentUser, Long documentId) {
        KnowledgeDocumentDeletionTask task = transactionService.admitOrResumeOwned(
                currentUser,
                documentId
        );

        if (task.getVectorsDeletedAt() == null) {
            deleteVectorsOrThrow(task);
            transactionService.markVectorsDeleted(task);
        }

        if (task.getSourceDeletedAt() == null) {
            deleteSourceOrThrow(task);
            transactionService.markSourceDeleted(task);
        }

        // This is a short PostgreSQL transaction: physical chunk removal and the final task
        // marker commit together. The source document row is deliberately retained soft-deleted.
        transactionService.deleteChunksAndMarkCompleted(task);
    }

    private void deleteVectorsOrThrow(KnowledgeDocumentDeletionTask task) {
        try {
            vectorStoreGateway.deleteByDocumentScope(new VectorDocumentScope(
                    task.getUserId(),
                    task.getKnowledgeBaseId(),
                    task.getDocumentId()
            ));
        } catch (RuntimeException externalFailure) {
            throw unavailableAfterRecording(task, VECTOR_DELETION_FAILURE, externalFailure);
        }
    }

    private void deleteSourceOrThrow(KnowledgeDocumentDeletionTask task) {
        try {
            documentStorage.delete(new StoredDocument(task.getStorageBucket(), task.getStorageObjectKey()));
        } catch (IOException | RuntimeException externalFailure) {
            throw unavailableAfterRecording(task, SOURCE_DELETION_FAILURE, externalFailure);
        }
    }

    private BusinessException unavailableAfterRecording(
            KnowledgeDocumentDeletionTask task,
            String failureSummary,
            Exception externalFailure
    ) {
        try {
            transactionService.recordExternalFailure(task, failureSummary);
        } catch (RuntimeException taskWriteFailure) {
            // The original external failure remains the client result. Do not expose storage or
            // provider details; preserve both causes in server logs for operational repair.
            log.error("Could not record retryable document-deletion failure for taskId={}",
                    task.getId(), taskWriteFailure);
        }
        log.warn("Document deletion external step failed for taskId={}", task.getId(), externalFailure);
        return new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_DELETION_UNAVAILABLE);
    }
}
