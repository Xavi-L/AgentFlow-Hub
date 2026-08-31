package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentReprocessTransactionServiceTest {
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeChunkMapper chunkMapper;
    @Mock private KnowledgeDocumentReprocessTaskMapper taskMapper;

    @Test
    void shouldAdmitCompletedDocumentAndPersistGenerationFenceTogether() {
        KnowledgeDocument completed = document(DocumentParseStatus.COMPLETED, 4L);
        KnowledgeDocument reprocessing = document(DocumentParseStatus.REPROCESSING, 5L);
        when(documentMapper.selectVisibleOwnedForReprocessForUpdate(301L, 101L)).thenReturn(completed);
        when(chunkMapper.hasProcessingChunkByDocumentScope(301L, 201L, 101L)).thenReturn(false);
        when(documentMapper.transitionCompletedToReprocessing(
                eq(301L), eq(201L), eq(101L), eq(4L), any()
        )).thenReturn(reprocessing);
        when(taskMapper.insert(any(KnowledgeDocumentReprocessTask.class))).thenAnswer(invocation -> {
            KnowledgeDocumentReprocessTask task = invocation.getArgument(0);
            task.setId(501L);
            return 1;
        });

        var admission = service().admitOrResumeCompletedOwned(user(), 301L);

        assertThat(admission.vectorDeletionRequired()).isTrue();
        assertThat(admission.task().getSourceVectorGeneration()).isEqualTo(4L);
        assertThat(admission.task().getCleanupStatus())
                .isEqualTo(DocumentReprocessCleanupStatus.VECTOR_DELETING.name());
        verify(taskMapper).insert(any(KnowledgeDocumentReprocessTask.class));
    }

    @Test
    void shouldRejectCompletedDocumentWhileAnyScopedChunkIsProcessing() {
        when(documentMapper.selectVisibleOwnedForReprocessForUpdate(301L, 101L))
                .thenReturn(document(DocumentParseStatus.COMPLETED, 0L));
        when(chunkMapper.hasProcessingChunkByDocumentScope(301L, 201L, 101L)).thenReturn(true);

        assertConflict(() -> service().admitOrResumeCompletedOwned(user(), 301L));

        verify(documentMapper, never()).transitionCompletedToReprocessing(any(), any(), any(), any(), any());
        verify(taskMapper, never()).insert(any(KnowledgeDocumentReprocessTask.class));
    }

    @Test
    void shouldReturnUniform404IfTheParentBecomesInvisibleAtAdmissionMutation() {
        KnowledgeDocument completed = document(DocumentParseStatus.COMPLETED, 0L);
        when(documentMapper.selectVisibleOwnedForReprocessForUpdate(301L, 101L)).thenReturn(completed);
        when(chunkMapper.hasProcessingChunkByDocumentScope(301L, 201L, 101L)).thenReturn(false);
        when(documentMapper.transitionCompletedToReprocessing(
                eq(301L), eq(201L), eq(101L), eq(0L), any()
        )).thenReturn(null);
        when(documentMapper.selectVisibleOwnedById(301L, 101L)).thenReturn(null);

        assertThatThrownBy(() -> service().admitOrResumeCompletedOwned(user(), 301L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.COMMON_NOT_FOUND);
    }

    @Test
    void shouldClaimOnlyRecordedRetryableTaskOnRepeatedPost() {
        KnowledgeDocument document = document(DocumentParseStatus.REPROCESSING, 5L);
        KnowledgeDocumentReprocessTask task = task(DocumentReprocessCleanupStatus.VECTOR_DELETE_RETRYABLE, 4L);
        when(documentMapper.selectVisibleOwnedForReprocessForUpdate(301L, 101L)).thenReturn(document);
        when(taskMapper.selectActiveByDocumentScopeForUpdate(301L, 201L, 101L)).thenReturn(task);
        when(taskMapper.claimVectorRetry(eq(501L), eq(301L), eq(201L), eq(101L), any())).thenReturn(1);

        var admission = service().admitOrResumeCompletedOwned(user(), 301L);

        assertThat(admission.vectorDeletionRequired()).isTrue();
        assertThat(task.getCleanupStatus()).isEqualTo(DocumentReprocessCleanupStatus.VECTOR_DELETING.name());
    }

    @Test
    void shouldKeepUnfinishedVectorDeletionAsSingleFlightConflict() {
        when(documentMapper.selectVisibleOwnedForReprocessForUpdate(301L, 101L))
                .thenReturn(document(DocumentParseStatus.REPROCESSING, 5L));
        when(taskMapper.selectActiveByDocumentScopeForUpdate(301L, 201L, 101L))
                .thenReturn(task(DocumentReprocessCleanupStatus.VECTOR_DELETING, 4L));

        assertConflict(() -> service().admitOrResumeCompletedOwned(user(), 301L));

        verify(taskMapper, never()).claimVectorRetry(any(), any(), any(), any(), any());
    }

    @Test
    void shouldDeleteChunksRequeueDocumentAndCompleteTaskInOneFinalCall() {
        KnowledgeDocumentReprocessTask task = task(DocumentReprocessCleanupStatus.READY_TO_FINALIZE, 4L);
        task.setVectorsDeletedAt(java.time.OffsetDateTime.now());
        KnowledgeDocument reprocessing = document(DocumentParseStatus.REPROCESSING, 5L);
        KnowledgeDocument pending = document(DocumentParseStatus.PENDING, 5L);
        when(documentMapper.selectReprocessScopeForUpdate(301L, 201L, 101L)).thenReturn(reprocessing);
        when(taskMapper.selectExactForUpdate(501L, 301L, 201L, 101L)).thenReturn(task);
        when(documentMapper.transitionReprocessingToPending(
                eq(301L), eq(201L), eq(101L), eq(5L), any()
        )).thenReturn(pending);
        when(taskMapper.markCompleted(eq(501L), eq(301L), eq(201L), eq(101L), any())).thenReturn(1);

        KnowledgeDocumentResponse response = service().deleteChunksAndRequeue(task);

        assertThat(response.parseStatus()).isEqualTo("PENDING");
        verify(chunkMapper).deleteByDocumentScope(301L, 201L, 101L);
        verify(taskMapper).markCompleted(eq(501L), eq(301L), eq(201L), eq(101L), any());
    }

    @Test
    void shouldReturn409ToACompetingFinalizerThatAlreadyLostCompletion() {
        KnowledgeDocument reprocessing = document(DocumentParseStatus.REPROCESSING, 5L);
        KnowledgeDocumentReprocessTask completedTask = task(DocumentReprocessCleanupStatus.COMPLETED, 4L);
        completedTask.setVectorsDeletedAt(java.time.OffsetDateTime.now());
        completedTask.setCompletedAt(java.time.OffsetDateTime.now());
        when(documentMapper.selectReprocessScopeForUpdate(301L, 201L, 101L)).thenReturn(reprocessing);
        when(taskMapper.selectExactForUpdate(501L, 301L, 201L, 101L)).thenReturn(completedTask);

        assertConflict(() -> service().deleteChunksAndRequeue(completedTask));

        verify(chunkMapper, never()).deleteByDocumentScope(any(), any(), any());
    }

    @Test
    void shouldRecordRetryableVectorFailureWithControlledSummary() {
        KnowledgeDocumentReprocessTask task = task(DocumentReprocessCleanupStatus.VECTOR_DELETING, 4L);
        when(taskMapper.recordVectorFailure(
                eq(501L), eq(301L), eq(201L), eq(101L), eq("Vector deletion failed"), any()
        )).thenReturn(1);

        service().recordVectorFailure(task, "Vector deletion failed");

        assertThat(task.getCleanupStatus())
                .isEqualTo(DocumentReprocessCleanupStatus.VECTOR_DELETE_RETRYABLE.name());
        assertThat(task.getRetryCount()).isEqualTo(1);
    }

    private KnowledgeDocumentReprocessTransactionService service() {
        return new KnowledgeDocumentReprocessTransactionService(documentMapper, chunkMapper, taskMapper);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT);
    }

    private static KnowledgeDocument document(DocumentParseStatus status, long generation) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(301L);
        document.setUserId(101L);
        document.setKnowledgeBaseId(201L);
        document.setFileName("rules.md");
        document.setFileType("MD");
        document.setFileSize(193L);
        document.setParseStatus(status.name());
        document.setVectorGeneration(generation);
        return document;
    }

    private static KnowledgeDocumentReprocessTask task(DocumentReprocessCleanupStatus status, long generation) {
        KnowledgeDocumentReprocessTask task = new KnowledgeDocumentReprocessTask();
        task.setId(501L);
        task.setUserId(101L);
        task.setKnowledgeBaseId(201L);
        task.setDocumentId(301L);
        task.setSourceVectorGeneration(generation);
        task.setCleanupStatus(status.name());
        task.setRetryCount(0);
        return task;
    }

    private static AuthenticatedUser user() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }
}
