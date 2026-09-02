package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.agentflow.knowledge.chunk.ChunkDraft;
import com.agentflow.knowledge.model.ChunkVectorizationStatus;
import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.model.KnowledgeDocument;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentMapper;
import com.agentflow.knowledge.vector.ChunkVectorIdentityFactory;
import java.time.OffsetDateTime;
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

/** Tests the short persistence transactions without depending on a live PostgreSQL. */
@ExtendWith(MockitoExtension.class)
class DocumentProcessingTransactionServiceTest {

    @BeforeAll
    static void initializeLambdaCaches() {
        initializeLambdaCache(KnowledgeDocumentMapper.class, KnowledgeDocument.class);
        initializeLambdaCache(KnowledgeChunkMapper.class, KnowledgeChunk.class);
    }

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @InjectMocks
    private DocumentProcessingTransactionService transactionService;

    @Captor
    private ArgumentCaptor<KnowledgeChunk> chunkCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<KnowledgeDocument>> documentUpdateCaptor;

    @Test
    void shouldAtomicallyClaimOnlyAPendingDocument() {
        when(knowledgeDocumentMapper.update(
                org.mockito.ArgumentMatchers.<KnowledgeDocument>isNull(),
                any()
        )).thenReturn(1);

        boolean claimed = transactionService.claimPendingDocument(document());

        verify(knowledgeDocumentMapper).update(
                org.mockito.ArgumentMatchers.<KnowledgeDocument>isNull(),
                documentUpdateCaptor.capture()
        );
        assertThat(claimed).isTrue();
        assertThat(documentUpdateCaptor.getValue().getSqlSet()).contains(
                "parse_status", "parse_error", "updated_at"
        );
        assertThat(documentUpdateCaptor.getValue().getSqlSegment()).contains(
                "id", "user_id", "knowledge_base_id", "parse_status", "deleted_at"
        );
    }

    @Test
    void shouldPersistEveryChunkBeforeMarkingTheDocumentCompleted() {
        when(knowledgeChunkMapper.insert(any(KnowledgeChunk.class))).thenAnswer(invocation -> {
            KnowledgeChunk chunk = invocation.getArgument(0);
            chunk.setId(401L + chunk.getChunkIndex());
            return 1;
        });
        when(knowledgeDocumentMapper.update(
                org.mockito.ArgumentMatchers.<KnowledgeDocument>isNull(),
                any()
        )).thenReturn(1);

        transactionService.persistChunksAndMarkCompleted(document(), List.of(
                new ChunkDraft(0, "first chunk", "Payment", 11, 2),
                new ChunkDraft(1, "second chunk", "Payment / Refund", 12, 2)
        ));

        verify(knowledgeChunkMapper, org.mockito.Mockito.times(2)).insert(chunkCaptor.capture());
        assertThat(chunkCaptor.getAllValues()).extracting(KnowledgeChunk::getDocumentId)
                .containsOnly(301L);
        assertThat(chunkCaptor.getAllValues()).extracting(KnowledgeChunk::getKnowledgeBaseId)
                .containsOnly(201L);
        assertThat(chunkCaptor.getAllValues()).extracting(KnowledgeChunk::getUserId)
                .containsOnly(101L);
        assertThat(chunkCaptor.getAllValues()).extracting(KnowledgeChunk::getChunkIndex)
                .containsExactly(0, 1);
        assertThat(chunkCaptor.getAllValues()).extracting(KnowledgeChunk::getVectorizationStatus)
                .containsOnly(ChunkVectorizationStatus.PENDING.name());
        assertThat(chunkCaptor.getAllValues()).extracting(KnowledgeChunk::getVectorGeneration)
                .containsOnly(0L);
        assertThat(chunkCaptor.getAllValues()).extracting(KnowledgeChunk::getChunkStrategyVersion)
                .containsOnly(DocumentProcessingTransactionService.CHUNK_STRATEGY_VERSION);
        assertThat(chunkCaptor.getAllValues()).extracting(KnowledgeChunk::getContentHash)
                .containsExactly(
                        ChunkVectorIdentityFactory.contentHash("first chunk"),
                        ChunkVectorIdentityFactory.contentHash("second chunk")
                );
        verify(knowledgeDocumentMapper).update(
                org.mockito.ArgumentMatchers.<KnowledgeDocument>isNull(),
                documentUpdateCaptor.capture()
        );
        assertThat(documentUpdateCaptor.getValue().getSqlSet()).contains("parse_status", "parse_error");
        assertThat(documentUpdateCaptor.getValue().getSqlSegment()).contains("parse_status", "deleted_at");
    }

    @Test
    void shouldNotMarkCompletedWhenAChunkInsertDoesNotAffectExactlyOneRow() {
        when(knowledgeChunkMapper.insert(any(KnowledgeChunk.class))).thenReturn(0);

        assertThatThrownBy(() -> transactionService.persistChunksAndMarkCompleted(
                document(),
                List.of(new ChunkDraft(0, "only chunk", null, 10, 2))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected exactly one inserted knowledge_chunk row");

        verify(knowledgeDocumentMapper, never()).update(
                org.mockito.ArgumentMatchers.<KnowledgeDocument>isNull(),
                any()
        );
    }

    @Test
    void shouldMarkOnlyTheClaimedDocumentAsFailed() {
        when(knowledgeDocumentMapper.update(
                org.mockito.ArgumentMatchers.<KnowledgeDocument>isNull(),
                any()
        )).thenReturn(1);

        transactionService.markFailed(document(), "Document content is not valid UTF-8");

        verify(knowledgeDocumentMapper).update(
                org.mockito.ArgumentMatchers.<KnowledgeDocument>isNull(),
                documentUpdateCaptor.capture()
        );
        assertThat(documentUpdateCaptor.getValue().getSqlSet()).contains("parse_status", "parse_error");
        assertThat(documentUpdateCaptor.getValue().getSqlSegment()).contains("parse_status", "deleted_at");
    }

    private static void initializeLambdaCache(Class<?> mapperType, Class<?> entityType) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(),
                "DocumentProcessingTransactionServiceTest"
        );
        assistant.setCurrentNamespace(mapperType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }

    private static KnowledgeDocument document() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(301L);
        document.setUserId(101L);
        document.setKnowledgeBaseId(201L);
        document.setFileName("refund-rules.md");
        document.setFileType("MD");
        document.setParseStatus("PENDING");
        document.setVectorGeneration(0L);
        document.setCreatedAt(OffsetDateTime.parse("2026-08-14T12:00:00+08:00"));
        return document;
    }
}
