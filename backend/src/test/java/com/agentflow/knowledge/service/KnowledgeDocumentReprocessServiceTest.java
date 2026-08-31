package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.model.DocumentReprocessCleanupStatus;
import com.agentflow.knowledge.model.KnowledgeDocumentReprocessTask;
import com.agentflow.knowledge.vector.VectorDocumentScope;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.user.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentReprocessServiceTest {
    @Mock private KnowledgeDocumentService documentService;
    @Mock private KnowledgeDocumentReprocessTransactionService transactionService;
    @Mock private VectorStoreGateway vectorStoreGateway;

    @Test
    void shouldKeepV22FailedToPendingPrimitiveUntouched() {
        KnowledgeDocumentResponse pending = response();
        when(documentService.reprocessOwnedFailed(user(), 301L)).thenReturn(pending);

        assertThat(service().reprocessOwned(user(), 301L)).isSameAs(pending);

        verify(transactionService, never()).admitOrResumeCompletedOwned(user(), 301L);
        verify(vectorStoreGateway, never()).deleteByDocumentScope(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldDeleteOnlyTheOldGenerationThenFinalizeAsPending() {
        KnowledgeDocumentReprocessTask task = task(DocumentReprocessCleanupStatus.VECTOR_DELETING);
        KnowledgeDocumentResponse pending = response();
        when(documentService.reprocessOwnedFailed(user(), 301L)).thenThrow(conflict());
        when(transactionService.admitOrResumeCompletedOwned(user(), 301L))
                .thenReturn(new KnowledgeDocumentReprocessTransactionService.ReprocessAdmission(task, true));
        when(transactionService.deleteChunksAndRequeue(task)).thenReturn(pending);

        assertThat(service().reprocessOwned(user(), 301L)).isSameAs(pending);

        verify(vectorStoreGateway).deleteByDocumentScope(
                VectorDocumentScope.forGenerationCleanup(101L, 201L, 301L, 4L)
        );
        verify(transactionService).markVectorsDeleted(task);
        verify(transactionService).deleteChunksAndRequeue(task);
    }

    @Test
    void shouldResumeReadyTaskWithoutRepeatingVectorDeletion() {
        KnowledgeDocumentReprocessTask task = task(DocumentReprocessCleanupStatus.READY_TO_FINALIZE);
        when(documentService.reprocessOwnedFailed(user(), 301L)).thenThrow(conflict());
        when(transactionService.admitOrResumeCompletedOwned(user(), 301L))
                .thenReturn(new KnowledgeDocumentReprocessTransactionService.ReprocessAdmission(task, false));
        when(transactionService.deleteChunksAndRequeue(task)).thenReturn(response());

        service().reprocessOwned(user(), 301L);

        verify(vectorStoreGateway, never()).deleteByDocumentScope(org.mockito.ArgumentMatchers.any());
        verify(transactionService, never()).markVectorsDeleted(task);
        verify(transactionService).deleteChunksAndRequeue(task);
    }

    @Test
    void shouldPersistControlledFailureAndReturn503WhenVectorDeletionFails() {
        KnowledgeDocumentReprocessTask task = task(DocumentReprocessCleanupStatus.VECTOR_DELETING);
        when(documentService.reprocessOwnedFailed(user(), 301L)).thenThrow(conflict());
        when(transactionService.admitOrResumeCompletedOwned(user(), 301L))
                .thenReturn(new KnowledgeDocumentReprocessTransactionService.ReprocessAdmission(task, true));
        org.mockito.Mockito.doThrow(new IllegalStateException("provider detail"))
                .when(vectorStoreGateway)
                .deleteByDocumentScope(VectorDocumentScope.forGenerationCleanup(101L, 201L, 301L, 4L));

        assertThatThrownBy(() -> service().reprocessOwned(user(), 301L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_REPROCESS_UNAVAILABLE);

        verify(transactionService).recordVectorFailure(task, "Vector deletion failed");
        verify(transactionService, never()).markVectorsDeleted(task);
        verify(transactionService, never()).deleteChunksAndRequeue(task);
    }

    private KnowledgeDocumentReprocessService service() {
        return new KnowledgeDocumentReprocessService(documentService, transactionService, vectorStoreGateway);
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT);
    }

    private static AuthenticatedUser user() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static KnowledgeDocumentReprocessTask task(DocumentReprocessCleanupStatus status) {
        KnowledgeDocumentReprocessTask task = new KnowledgeDocumentReprocessTask();
        task.setId(501L);
        task.setUserId(101L);
        task.setKnowledgeBaseId(201L);
        task.setDocumentId(301L);
        task.setSourceVectorGeneration(4L);
        task.setCleanupStatus(status.name());
        task.setRetryCount(0);
        return task;
    }

    private static KnowledgeDocumentResponse response() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-31T00:00:00+08:00");
        return new KnowledgeDocumentResponse("301", "201", "rules.md", "MD", 193L, "PENDING", now, now);
    }
}
