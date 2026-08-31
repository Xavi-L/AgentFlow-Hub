package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.ChunkVectorizationResponse;
import com.agentflow.knowledge.model.ChunkVectorizationStatus;
import com.agentflow.knowledge.model.KnowledgeBase;
import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.repository.KnowledgeBaseMapper;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.vector.ChunkVectorIdentity;
import com.agentflow.knowledge.vector.ChunkVectorIdentityFactory;
import com.agentflow.knowledge.vector.EmbeddingGateway;
import com.agentflow.knowledge.vector.EmbeddingRequest;
import com.agentflow.knowledge.vector.EmbeddingVector;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.knowledge.vector.VectorStoreRecord;
import com.agentflow.knowledge.vector.VectorStoreOutcomeUnknownException;
import com.agentflow.user.security.AuthenticatedUser;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Database-free V5 orchestration tests for scope, idempotency, and per-chunk failures. */
@ExtendWith(MockitoExtension.class)
class ChunkVectorizationServiceTest {

    @BeforeAll
    static void initializeLambdaCaches() {
        initializeLambdaCache(KnowledgeBaseMapper.class, KnowledgeBase.class);
    }

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Mock
    private EmbeddingGateway embeddingGateway;

    @Mock
    private VectorStoreGateway vectorStoreGateway;

    @Mock
    private ChunkVectorizationTransactionService transactionService;

    @InjectMocks
    private ChunkVectorizationService chunkVectorizationService;

    @Captor
    private ArgumentCaptor<EmbeddingRequest> embeddingRequestCaptor;

    @Captor
    private ArgumentCaptor<VectorStoreRecord> vectorRecordCaptor;

    @Test
    void shouldVectorizePendingChunkWithStableIdentityAndQdrantReadyPayload() {
        KnowledgeChunk chunk = pendingChunk(401L, 301L, 0, "Refund rules");
        ChunkVectorIdentity identity = ChunkVectorIdentityFactory.create(chunk);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(knowledgeChunkMapper.selectVectorizationCandidates(201L, 101L)).thenReturn(List.of(chunk));
        when(transactionService.claimPendingChunk(chunk, identity.contentHash())).thenReturn(true);
        when(embeddingGateway.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f, 0.2f, 0.3f)));

        ChunkVectorizationResponse response = chunkVectorizationService.vectorizePending(currentUser(), 201L);

        verify(embeddingGateway).embed(embeddingRequestCaptor.capture());
        assertThat(embeddingRequestCaptor.getValue()).isEqualTo(
                new EmbeddingRequest("Refund rules", "dashscope", "text-embedding-v4")
        );
        verify(vectorStoreGateway).upsert(vectorRecordCaptor.capture());
        VectorStoreRecord record = vectorRecordCaptor.getValue();
        assertThat(record.vectorId()).isEqualTo(identity.vectorId());
        assertThat(record.payload()).containsEntry("chunkId", 401L)
                .containsEntry("documentId", 301L)
                .containsEntry("knowledgeBaseId", 201L)
                .containsEntry("userId", 101L)
                .containsEntry("chunkIndex", 0)
                .containsEntry("vectorGeneration", 0L)
                .containsEntry("contentHash", identity.contentHash())
                .containsEntry("embeddingProvider", "dashscope")
                .containsEntry("embeddingModel", "text-embedding-v4")
                .doesNotContainKey("content");
        verify(transactionService).markCompleted(chunk, identity.vectorId());
        assertThat(response).isEqualTo(new ChunkVectorizationResponse(1, 1, 1, 0, 0));
    }

    @Test
    void shouldSkipAlreadyCompletedChunkWithoutCallingExternalGatewaysAgain() {
        KnowledgeChunk chunk = pendingChunk(401L, 301L, 0, "Refund rules");
        chunk.setVectorizationStatus(ChunkVectorizationStatus.COMPLETED.name());
        chunk.setVectorId(ChunkVectorIdentityFactory.create(chunk).vectorId());
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(knowledgeChunkMapper.selectVectorizationCandidates(201L, 101L)).thenReturn(List.of(chunk));

        ChunkVectorizationResponse response = chunkVectorizationService.vectorizePending(currentUser(), 201L);

        verify(transactionService, never()).claimPendingChunk(any(), any());
        verify(embeddingGateway, never()).embed(any());
        verify(vectorStoreGateway, never()).upsert(any());
        assertThat(response).isEqualTo(new ChunkVectorizationResponse(1, 0, 0, 0, 1));
    }

    @Test
    void shouldSkipStalePendingCandidateWhenV24ClaimGateRejectsItWithoutExternalIo() {
        KnowledgeChunk chunk = pendingChunk(401L, 301L, 0, "Refund rules");
        ChunkVectorIdentity identity = ChunkVectorIdentityFactory.create(chunk);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(knowledgeChunkMapper.selectVectorizationCandidates(201L, 101L)).thenReturn(List.of(chunk));
        when(transactionService.claimPendingChunk(chunk, identity.contentHash())).thenReturn(false);

        ChunkVectorizationResponse response = chunkVectorizationService.vectorizePending(currentUser(), 201L);

        verify(transactionService).claimPendingChunk(chunk, identity.contentHash());
        verify(embeddingGateway, never()).embed(any());
        verify(vectorStoreGateway, never()).upsert(any());
        verify(transactionService, never()).markCompleted(any(), any());
        verify(transactionService, never()).markFailed(any(), any());
        assertThat(response).isEqualTo(new ChunkVectorizationResponse(1, 0, 0, 0, 1));
    }

    @Test
    void shouldRecordOneEmbeddingFailureAndContinueWithTheNextPendingChunk() {
        KnowledgeChunk failingChunk = pendingChunk(401L, 301L, 0, "Bad provider input");
        KnowledgeChunk completedChunk = pendingChunk(402L, 301L, 1, "Refund rules");
        ChunkVectorIdentity failingIdentity = ChunkVectorIdentityFactory.create(failingChunk);
        ChunkVectorIdentity completedIdentity = ChunkVectorIdentityFactory.create(completedChunk);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(knowledgeChunkMapper.selectVectorizationCandidates(201L, 101L))
                .thenReturn(List.of(failingChunk, completedChunk));
        when(transactionService.claimPendingChunk(failingChunk, failingIdentity.contentHash())).thenReturn(true);
        when(transactionService.claimPendingChunk(completedChunk, completedIdentity.contentHash())).thenReturn(true);
        when(embeddingGateway.embed(any()))
                .thenThrow(new IllegalStateException("provider endpoint unavailable"))
                .thenReturn(new EmbeddingVector(List.of(0.1f, 0.2f, 0.3f)));

        ChunkVectorizationResponse response = chunkVectorizationService.vectorizePending(currentUser(), 201L);

        verify(transactionService).markFailed(failingChunk, "Embedding generation failed");
        verify(transactionService).markCompleted(completedChunk, completedIdentity.vectorId());
        assertThat(response).isEqualTo(new ChunkVectorizationResponse(2, 2, 1, 1, 0));
    }

    @Test
    void shouldHideMissingOrUnownedKnowledgeBases() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> chunkVectorizationService.vectorizePending(currentUser(), 201L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.COMMON_NOT_FOUND);

        verify(knowledgeChunkMapper, never()).selectVectorizationCandidates(any(), any());
    }

    @Test
    void shouldKeepProcessingBarrierWhenVectorUpsertOutcomeIsUnknown() {
        KnowledgeChunk chunk = pendingChunk(401L, 301L, 0, "Refund rules");
        ChunkVectorIdentity identity = ChunkVectorIdentityFactory.create(chunk);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(knowledgeChunkMapper.selectVectorizationCandidates(201L, 101L)).thenReturn(List.of(chunk));
        when(transactionService.claimPendingChunk(chunk, identity.contentHash())).thenReturn(true);
        when(embeddingGateway.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f, 0.2f, 0.3f)));
        org.mockito.Mockito.doThrow(new VectorStoreOutcomeUnknownException("timeout", new RuntimeException()))
                .when(vectorStoreGateway).upsert(any());

        ChunkVectorizationResponse response = chunkVectorizationService.vectorizePending(currentUser(), 201L);

        verify(transactionService).markOutcomeUnknown(chunk, "Vector store upsert failed");
        verify(transactionService, never()).markFailed(chunk, "Vector store upsert failed");
        assertThat(response).isEqualTo(new ChunkVectorizationResponse(1, 1, 0, 1, 0));
    }

    private static void initializeLambdaCache(Class<?> mapperType, Class<?> entityType) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(),
                "ChunkVectorizationServiceTest"
        );
        assistant.setCurrentNamespace(mapperType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static KnowledgeBase knowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(201L);
        knowledgeBase.setUserId(101L);
        knowledgeBase.setEmbeddingProvider("dashscope");
        knowledgeBase.setEmbeddingModel("text-embedding-v4");
        return knowledgeBase;
    }

    private static KnowledgeChunk pendingChunk(Long id, Long documentId, int chunkIndex, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setUserId(101L);
        chunk.setKnowledgeBaseId(201L);
        chunk.setDocumentId(documentId);
        chunk.setVectorGeneration(0L);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        chunk.setTitlePath("Payment / Refund");
        chunk.setVectorizationStatus(ChunkVectorizationStatus.PENDING.name());
        chunk.setContentHash(ChunkVectorIdentityFactory.contentHash(content));
        return chunk;
    }
}
