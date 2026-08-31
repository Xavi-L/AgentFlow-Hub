package com.agentflow.knowledge.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.model.KnowledgeDocumentReprocessTask;
import com.agentflow.knowledge.vector.VectorDocumentScope;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.user.security.AuthenticatedUser;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Non-transactional V25 orchestration. It deliberately has no DocumentStorage dependency. */
@Service
public class KnowledgeDocumentReprocessService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentReprocessService.class);
    private static final String VECTOR_DELETION_FAILURE = "Vector deletion failed";

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeDocumentReprocessTransactionService transactionService;
    private final VectorStoreGateway vectorStoreGateway;

    public KnowledgeDocumentReprocessService(
            KnowledgeDocumentService knowledgeDocumentService,
            KnowledgeDocumentReprocessTransactionService transactionService,
            VectorStoreGateway vectorStoreGateway
    ) {
        this.knowledgeDocumentService = Objects.requireNonNull(
                knowledgeDocumentService, "knowledgeDocumentService must not be null"
        );
        this.transactionService = Objects.requireNonNull(transactionService, "transactionService must not be null");
        this.vectorStoreGateway = Objects.requireNonNull(vectorStoreGateway, "vectorStoreGateway must not be null");
    }

    public KnowledgeDocumentResponse reprocessOwned(AuthenticatedUser currentUser, Long documentId) {
        try {
            return knowledgeDocumentService.reprocessOwnedFailed(currentUser, documentId);
        } catch (BusinessException v22Result) {
            if (v22Result.getErrorCode() != ErrorCode.KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT) {
                throw v22Result;
            }
        }

        KnowledgeDocumentReprocessTransactionService.ReprocessAdmission admission =
                transactionService.admitOrResumeCompletedOwned(currentUser, documentId);
        KnowledgeDocumentReprocessTask task = admission.task();
        if (admission.vectorDeletionRequired()) {
            try {
                vectorStoreGateway.deleteByDocumentScope(VectorDocumentScope.forGenerationCleanup(
                        task.getUserId(),
                        task.getKnowledgeBaseId(),
                        task.getDocumentId(),
                        task.getSourceVectorGeneration()
                ));
            } catch (RuntimeException externalFailure) {
                try {
                    transactionService.recordVectorFailure(task, VECTOR_DELETION_FAILURE);
                } catch (RuntimeException taskWriteFailure) {
                    log.error("Could not record reprocess failure for taskId={}", task.getId(), taskWriteFailure);
                }
                log.warn("Reprocess vector cleanup failed for taskId={}", task.getId(), externalFailure);
                throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_REPROCESS_UNAVAILABLE);
            }
            transactionService.markVectorsDeleted(task);
        }
        return transactionService.deleteChunksAndRequeue(task);
    }
}
