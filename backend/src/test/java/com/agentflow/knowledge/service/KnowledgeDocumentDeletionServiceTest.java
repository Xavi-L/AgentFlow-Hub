package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.model.KnowledgeDocumentDeletionTask;
import com.agentflow.knowledge.storage.DocumentStorage;
import com.agentflow.knowledge.storage.StoredDocument;
import com.agentflow.knowledge.vector.VectorDocumentScope;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.user.security.AuthenticatedUser;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies V24's transaction-external side-effect order and controlled retry boundary. */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentDeletionServiceTest {

    @Mock
    private KnowledgeDocumentDeletionTransactionService transactionService;

    @Mock
    private VectorStoreGateway vectorStoreGateway;

    @Mock
    private DocumentStorage documentStorage;

    @InjectMocks
    private KnowledgeDocumentDeletionService deletionService;

    @Test
    void shouldDeleteVectorsThenSourceThenChunksAndOnlyUseServerOwnedTaskScope() throws Exception {
        KnowledgeDocumentDeletionTask task = pendingTask();
        when(transactionService.admitOrResumeOwned(currentUser(), 301L)).thenReturn(task);

        deletionService.deleteOwned(currentUser(), 301L);

        InOrder order = inOrder(transactionService, vectorStoreGateway, documentStorage);
        order.verify(transactionService).admitOrResumeOwned(currentUser(), 301L);
        order.verify(vectorStoreGateway).deleteByDocumentScope(new VectorDocumentScope(101L, 201L, 301L));
        order.verify(transactionService).markVectorsDeleted(task);
        order.verify(documentStorage).delete(new StoredDocument(
                "local",
                "users/101/knowledge-bases/201/documents/opaque-document.md"
        ));
        order.verify(transactionService).markSourceDeleted(task);
        order.verify(transactionService).deleteChunksAndMarkCompleted(task);
    }

    @Test
    void shouldResumeOnlyTheUnfinishedStorageAndChunkStepsAfterVectorsWereRecorded() throws Exception {
        KnowledgeDocumentDeletionTask task = pendingTask();
        task.setVectorsDeletedAt(OffsetDateTime.parse("2026-08-30T12:00:00+08:00"));
        when(transactionService.admitOrResumeOwned(currentUser(), 301L)).thenReturn(task);

        deletionService.deleteOwned(currentUser(), 301L);

        verifyNoInteractions(vectorStoreGateway);
        verify(documentStorage).delete(any(StoredDocument.class));
        verify(transactionService, never()).markVectorsDeleted(task);
        verify(transactionService).markSourceDeleted(task);
        verify(transactionService).deleteChunksAndMarkCompleted(task);
    }

    @Test
    void shouldPersistAControlledFailureAndReturn503WhenVectorDeletionFails() {
        KnowledgeDocumentDeletionTask task = pendingTask();
        when(transactionService.admitOrResumeOwned(currentUser(), 301L)).thenReturn(task);
        RuntimeException remoteFailure = new IllegalStateException("Qdrant unavailable");
        org.mockito.Mockito.doThrow(remoteFailure).when(vectorStoreGateway)
                .deleteByDocumentScope(any(VectorDocumentScope.class));

        assertThatThrownBy(() -> deletionService.deleteOwned(currentUser(), 301L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_DELETION_UNAVAILABLE));

        verify(transactionService).recordExternalFailure(task, "Vector deletion failed");
        verify(transactionService, never()).markVectorsDeleted(task);
        verifyNoInteractions(documentStorage);
        verify(transactionService, never()).deleteChunksAndMarkCompleted(task);
    }

    @Test
    void shouldPersistAControlledFailureAndReturn503WhenSourceDeletionFails() throws Exception {
        KnowledgeDocumentDeletionTask task = pendingTask();
        when(transactionService.admitOrResumeOwned(currentUser(), 301L)).thenReturn(task);
        org.mockito.Mockito.doThrow(new IOException("disk unavailable")).when(documentStorage)
                .delete(any(StoredDocument.class));

        assertThatThrownBy(() -> deletionService.deleteOwned(currentUser(), 301L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_DELETION_UNAVAILABLE));

        verify(vectorStoreGateway).deleteByDocumentScope(new VectorDocumentScope(101L, 201L, 301L));
        verify(transactionService).markVectorsDeleted(task);
        verify(transactionService).recordExternalFailure(task, "Source document deletion failed");
        verify(transactionService, never()).markSourceDeleted(task);
        verify(transactionService, never()).deleteChunksAndMarkCompleted(task);
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static KnowledgeDocumentDeletionTask pendingTask() {
        KnowledgeDocumentDeletionTask task = new KnowledgeDocumentDeletionTask();
        task.setId(401L);
        task.setUserId(101L);
        task.setKnowledgeBaseId(201L);
        task.setDocumentId(301L);
        task.setStorageBucket("local");
        task.setStorageObjectKey("users/101/knowledge-bases/201/documents/opaque-document.md");
        task.setRetryCount(0);
        return task;
    }
}
