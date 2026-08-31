package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.model.KnowledgeDocument;
import com.agentflow.knowledge.model.KnowledgeDocumentDeletionTask;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentDeletionTaskMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentMapper;
import com.agentflow.user.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for V24's short PostgreSQL admission/completion transaction boundaries. */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentDeletionTransactionServiceTest {

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Mock
    private KnowledgeDocumentDeletionTaskMapper deletionTaskMapper;

    @InjectMocks
    private KnowledgeDocumentDeletionTransactionService transactionService;

    @Test
    void shouldSoftDeleteAndCreateTheTaskAtomicallyAfterTheLockedProcessingChecks() {
        KnowledgeDocument document = visibleDocument("COMPLETED", false);
        when(knowledgeDocumentMapper.selectOwnedWithLiveParentForDeletionForUpdate(301L, 101L))
                .thenReturn(document);
        when(knowledgeChunkMapper.hasProcessingChunkByDocumentScope(301L, 201L, 101L)).thenReturn(false);
        when(knowledgeDocumentMapper.softDeleteOwnedWithLiveParent(eq(301L), eq(101L), any()))
                .thenReturn(1);
        when(deletionTaskMapper.insert(any(KnowledgeDocumentDeletionTask.class))).thenAnswer(invocation -> {
            invocation.<KnowledgeDocumentDeletionTask>getArgument(0).setId(401L);
            return 1;
        });

        KnowledgeDocumentDeletionTask task = transactionService.admitOrResumeOwned(currentUser(), 301L);

        InOrder order = inOrder(knowledgeDocumentMapper, knowledgeChunkMapper, deletionTaskMapper);
        order.verify(knowledgeDocumentMapper).selectOwnedWithLiveParentForDeletionForUpdate(301L, 101L);
        order.verify(knowledgeChunkMapper).hasProcessingChunkByDocumentScope(301L, 201L, 101L);
        order.verify(knowledgeDocumentMapper).softDeleteOwnedWithLiveParent(eq(301L), eq(101L), any());
        order.verify(deletionTaskMapper).insert(any(KnowledgeDocumentDeletionTask.class));
        assertThat(task.getId()).isEqualTo(401L);
        assertThat(task.getUserId()).isEqualTo(101L);
        assertThat(task.getKnowledgeBaseId()).isEqualTo(201L);
        assertThat(task.getDocumentId()).isEqualTo(301L);
        assertThat(task.getStorageBucket()).isEqualTo("local");
        assertThat(task.getStorageObjectKey()).isEqualTo("users/101/knowledge-bases/201/documents/opaque.md");
        assertThat(task.getRetryCount()).isZero();
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isEqualTo(task.getCreatedAt());
        assertThat(task.getLastAttemptedAt()).isEqualTo(task.getCreatedAt());
    }

    @ParameterizedTest(name = "{0} document is eligible for V24 deletion")
    @ValueSource(strings = {"PENDING", "FAILED", "COMPLETED"})
    void shouldAdmitEveryNonProcessingDocumentState(String parseStatus) {
        when(knowledgeDocumentMapper.selectOwnedWithLiveParentForDeletionForUpdate(301L, 101L))
                .thenReturn(visibleDocument(parseStatus, false));
        when(knowledgeChunkMapper.hasProcessingChunkByDocumentScope(301L, 201L, 101L)).thenReturn(false);
        when(knowledgeDocumentMapper.softDeleteOwnedWithLiveParent(eq(301L), eq(101L), any()))
                .thenReturn(1);
        when(deletionTaskMapper.insert(any(KnowledgeDocumentDeletionTask.class))).thenAnswer(invocation -> {
            invocation.<KnowledgeDocumentDeletionTask>getArgument(0).setId(401L);
            return 1;
        });

        KnowledgeDocumentDeletionTask task = transactionService.admitOrResumeOwned(currentUser(), 301L);

        assertThat(task.getId()).isEqualTo(401L);
        verify(knowledgeDocumentMapper).softDeleteOwnedWithLiveParent(eq(301L), eq(101L), any());
        verify(deletionTaskMapper).insert(any(KnowledgeDocumentDeletionTask.class));
    }

    @ParameterizedTest(name = "{0} document conflicts with V24 deletion")
    @ValueSource(strings = {"PROCESSING", "REPROCESSING"})
    void shouldRejectAWorkerOwnedDocumentWithoutSoftDeletingOrCreatingATask(String parseStatus) {
        when(knowledgeDocumentMapper.selectOwnedWithLiveParentForDeletionForUpdate(301L, 101L))
                .thenReturn(visibleDocument(parseStatus, false));

        assertThatThrownBy(() -> transactionService.admitOrResumeOwned(currentUser(), 301L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_DELETION_CONFLICT));

        verify(knowledgeDocumentMapper).selectOwnedWithLiveParentForDeletionForUpdate(301L, 101L);
        verifyNoInteractions(knowledgeChunkMapper, deletionTaskMapper);
        verify(knowledgeDocumentMapper, never()).softDeleteOwnedWithLiveParent(any(), any(), any());
    }

    @Test
    void shouldRejectWhenAnyScopedChunkIsStillProcessing() {
        when(knowledgeDocumentMapper.selectOwnedWithLiveParentForDeletionForUpdate(301L, 101L))
                .thenReturn(visibleDocument("FAILED", false));
        when(knowledgeChunkMapper.hasProcessingChunkByDocumentScope(301L, 201L, 101L)).thenReturn(true);

        assertThatThrownBy(() -> transactionService.admitOrResumeOwned(currentUser(), 301L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_DELETION_CONFLICT));

        verify(knowledgeDocumentMapper, never()).softDeleteOwnedWithLiveParent(any(), any(), any());
        verifyNoInteractions(deletionTaskMapper);
    }

    @Test
    void shouldResumeOnlyAnIncompleteTaskForAnAlreadySoftDeletedDocument() {
        KnowledgeDocument deletedDocument = visibleDocument("COMPLETED", true);
        KnowledgeDocumentDeletionTask task = pendingTask();
        when(knowledgeDocumentMapper.selectOwnedWithLiveParentForDeletionForUpdate(301L, 101L))
                .thenReturn(deletedDocument);
        when(deletionTaskMapper.selectIncompleteByDocumentScopeForUpdate(301L, 201L, 101L))
                .thenReturn(task);
        when(deletionTaskMapper.recordAttempt(eq(401L), eq(301L), eq(201L), eq(101L), any()))
                .thenReturn(1);

        KnowledgeDocumentDeletionTask resumed = transactionService.admitOrResumeOwned(currentUser(), 301L);

        assertThat(resumed).isSameAs(task);
        assertThat(task.getLastAttemptedAt()).isNotNull();
        verifyNoInteractions(knowledgeChunkMapper);
        verify(knowledgeDocumentMapper, never()).softDeleteOwnedWithLiveParent(any(), any(), any());
        verify(deletionTaskMapper, never()).insert(any(KnowledgeDocumentDeletionTask.class));
    }

    @Test
    void shouldKeepMissingDeletedTasksIndistinguishableFromAnyOtherMissingDocument() {
        when(knowledgeDocumentMapper.selectOwnedWithLiveParentForDeletionForUpdate(301L, 101L))
                .thenReturn(visibleDocument("COMPLETED", true));
        when(deletionTaskMapper.selectIncompleteByDocumentScopeForUpdate(301L, 201L, 101L))
                .thenReturn(null);

        assertThatThrownBy(() -> transactionService.admitOrResumeOwned(currentUser(), 301L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_NOT_FOUND))
                .hasMessage("Document not found");
    }

    @Test
    void shouldPhysicallyDeleteChunksAndCompleteTheTaskInTheSameShortBoundary() {
        KnowledgeDocumentDeletionTask task = pendingTask();
        task.setVectorsDeletedAt(OffsetDateTime.parse("2026-08-30T12:00:00+08:00"));
        task.setSourceDeletedAt(OffsetDateTime.parse("2026-08-30T12:01:00+08:00"));
        when(deletionTaskMapper.markChunksDeletedAndCompleted(
                eq(401L), eq(301L), eq(201L), eq(101L), any()
        )).thenReturn(1);

        transactionService.deleteChunksAndMarkCompleted(task);

        InOrder order = inOrder(knowledgeChunkMapper, deletionTaskMapper);
        order.verify(knowledgeChunkMapper).deleteByDocumentScope(301L, 201L, 101L);
        order.verify(deletionTaskMapper).markChunksDeletedAndCompleted(
                eq(401L), eq(301L), eq(201L), eq(101L), any()
        );
        assertThat(task.getChunksDeletedAt()).isNotNull();
        assertThat(task.getCompletedAt()).isEqualTo(task.getChunksDeletedAt());
    }

    @Test
    void shouldRecordOnlyTheControlledFailureSummaryAndIncrementTheRetryCount() {
        KnowledgeDocumentDeletionTask task = pendingTask();
        when(deletionTaskMapper.recordFailure(
                eq(401L), eq(301L), eq(201L), eq(101L), eq("Vector deletion failed"), any()
        )).thenReturn(1);

        transactionService.recordExternalFailure(task, "Vector deletion failed");

        assertThat(task.getFailureSummary()).isEqualTo("Vector deletion failed");
        assertThat(task.getRetryCount()).isEqualTo(1);
        assertThat(task.getLastFailedAt()).isNotNull();
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static KnowledgeDocument visibleDocument(String parseStatus, boolean deleted) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(301L);
        document.setUserId(101L);
        document.setKnowledgeBaseId(201L);
        document.setParseStatus(parseStatus);
        document.setStorageBucket("local");
        document.setStorageObjectKey("users/101/knowledge-bases/201/documents/opaque.md");
        if (deleted) {
            document.setDeletedAt(OffsetDateTime.parse("2026-08-30T11:00:00+08:00"));
        }
        return document;
    }

    private static KnowledgeDocumentDeletionTask pendingTask() {
        KnowledgeDocumentDeletionTask task = new KnowledgeDocumentDeletionTask();
        task.setId(401L);
        task.setUserId(101L);
        task.setKnowledgeBaseId(201L);
        task.setDocumentId(301L);
        task.setStorageBucket("local");
        task.setStorageObjectKey("users/101/knowledge-bases/201/documents/opaque.md");
        task.setRetryCount(0);
        return task;
    }
}
