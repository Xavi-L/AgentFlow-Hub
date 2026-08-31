package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentMapper;
import com.agentflow.knowledge.vector.ChunkVectorIdentityFactory;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests the V5 short state transitions without a live PostgreSQL instance. */
@ExtendWith(MockitoExtension.class)
class ChunkVectorizationTransactionServiceTest {

    @BeforeAll
    static void initializeLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(),
                "ChunkVectorizationTransactionServiceTest"
        );
        assistant.setCurrentNamespace(KnowledgeChunkMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, KnowledgeChunk.class);
    }

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @InjectMocks
    private ChunkVectorizationTransactionService transactionService;

    @Captor
    private ArgumentCaptor<Wrapper<KnowledgeChunk>> updateCaptor;

    @Test
    void shouldClaimOnlyStillPendingChunkAndRefreshItsContentHash() {
        KnowledgeChunk chunk = chunk();
        String contentHash = ChunkVectorIdentityFactory.contentHash(chunk.getContent());
        when(knowledgeDocumentMapper.selectVectorizableOwnedForChunkClaimForUpdate(
                301L,
                201L,
                101L
        )).thenReturn(documentWithGeneration(0L));
        when(knowledgeChunkMapper.update(org.mockito.ArgumentMatchers.<KnowledgeChunk>isNull(), any())).thenReturn(1);

        boolean claimed = transactionService.claimPendingChunk(chunk, contentHash);

        verify(knowledgeChunkMapper).update(
                org.mockito.ArgumentMatchers.<KnowledgeChunk>isNull(),
                updateCaptor.capture()
        );
        assertThat(claimed).isTrue();
        assertThat(updateCaptor.getValue().getSqlSet()).contains(
                "vectorization_status", "vectorization_error", "vector_id", "content_hash", "updated_at"
        );
        assertThat(updateCaptor.getValue().getSqlSegment()).contains(
                "id", "user_id", "knowledge_base_id", "document_id", "vectorization_status"
        );
    }

    @Test
    void shouldSkipTheChunkUpdateWhenTheLockedParentIsNoLongerVectorizable() {
        KnowledgeChunk chunk = chunk();
        when(knowledgeDocumentMapper.selectVectorizableOwnedForChunkClaimForUpdate(
                301L,
                201L,
                101L
        )).thenReturn(null);

        boolean claimed = transactionService.claimPendingChunk(
                chunk,
                ChunkVectorIdentityFactory.contentHash(chunk.getContent())
        );

        assertThat(claimed).isFalse();
        org.mockito.Mockito.verify(knowledgeDocumentMapper)
                .selectVectorizableOwnedForChunkClaimForUpdate(301L, 201L, 101L);
        org.mockito.Mockito.verifyNoInteractions(knowledgeChunkMapper);
    }

    @Test
    void shouldPersistVectorIdOnlyWhenTheClaimIsStillProcessing() {
        when(knowledgeChunkMapper.update(org.mockito.ArgumentMatchers.<KnowledgeChunk>isNull(), any())).thenReturn(1);

        transactionService.markCompleted(chunk(), "11111111-1111-8111-8111-111111111111");

        verify(knowledgeChunkMapper).update(
                org.mockito.ArgumentMatchers.<KnowledgeChunk>isNull(),
                updateCaptor.capture()
        );
        assertThat(updateCaptor.getValue().getSqlSet()).contains(
                "vectorization_status", "vectorization_error", "vector_id", "updated_at"
        );
        assertThat(updateCaptor.getValue().getSqlSegment()).contains(
                "id", "user_id", "knowledge_base_id", "document_id", "vectorization_status"
        );
    }

    @Test
    void shouldPersistAControlledFailureWithoutLeakingAVectorId() {
        when(knowledgeChunkMapper.update(org.mockito.ArgumentMatchers.<KnowledgeChunk>isNull(), any())).thenReturn(1);

        transactionService.markFailed(chunk(), "Embedding generation failed");

        verify(knowledgeChunkMapper).update(
                org.mockito.ArgumentMatchers.<KnowledgeChunk>isNull(),
                updateCaptor.capture()
        );
        assertThat(updateCaptor.getValue().getSqlSet()).contains(
                "vectorization_status", "vectorization_error", "vector_id", "updated_at"
        );
    }

    @Test
    void shouldKeepProcessingStatusWhenTheExternalOutcomeIsUnknown() {
        when(knowledgeChunkMapper.update(org.mockito.ArgumentMatchers.<KnowledgeChunk>isNull(), any())).thenReturn(1);

        transactionService.markOutcomeUnknown(chunk(), "Vector store upsert failed");

        verify(knowledgeChunkMapper).update(
                org.mockito.ArgumentMatchers.<KnowledgeChunk>isNull(),
                updateCaptor.capture()
        );
        assertThat(updateCaptor.getValue().getSqlSet()).contains("vectorization_error", "updated_at")
                .doesNotContain("vectorization_status", "vector_id");
        assertThat(updateCaptor.getValue().getSqlSegment()).contains(
                "id", "user_id", "knowledge_base_id", "document_id", "vector_generation",
                "vectorization_status"
        );
    }

    private static KnowledgeChunk chunk() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(401L);
        chunk.setUserId(101L);
        chunk.setKnowledgeBaseId(201L);
        chunk.setDocumentId(301L);
        chunk.setVectorGeneration(0L);
        chunk.setChunkIndex(0);
        chunk.setContent("Refund rules");
        return chunk;
    }

    private static com.agentflow.knowledge.model.KnowledgeDocument documentWithGeneration(long generation) {
        com.agentflow.knowledge.model.KnowledgeDocument document =
                new com.agentflow.knowledge.model.KnowledgeDocument();
        document.setVectorGeneration(generation);
        return document;
    }
}
