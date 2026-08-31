package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.chunk.ChunkDraft;
import com.agentflow.knowledge.chunk.DocumentChunker;
import com.agentflow.knowledge.dto.DocumentProcessingResponse;
import com.agentflow.knowledge.dto.KnowledgeChunkResponse;
import com.agentflow.knowledge.model.DocumentFileType;
import com.agentflow.knowledge.model.KnowledgeBase;
import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.model.KnowledgeDocument;
import com.agentflow.knowledge.parser.DocumentParserResolver;
import com.agentflow.knowledge.parser.ParsedDocument;
import com.agentflow.knowledge.repository.KnowledgeBaseMapper;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentMapper;
import com.agentflow.knowledge.storage.DocumentStorage;
import com.agentflow.knowledge.storage.StoredDocument;
import com.agentflow.user.security.AuthenticatedUser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Database-free orchestration tests for owner scope, per-document failure isolation,
 * and the chunk-verification read path.
 */
@ExtendWith(MockitoExtension.class)
class DocumentProcessingServiceTest {

    @BeforeAll
    static void initializeLambdaCaches() {
        initializeLambdaCache(KnowledgeBaseMapper.class, KnowledgeBase.class);
        initializeLambdaCache(KnowledgeDocumentMapper.class, KnowledgeDocument.class);
        initializeLambdaCache(KnowledgeChunkMapper.class, KnowledgeChunk.class);
    }

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Mock
    private DocumentStorage documentStorage;

    @Mock
    private DocumentParserResolver documentParserResolver;

    @Mock
    private DocumentChunker documentChunker;

    @Mock
    private DocumentProcessingTransactionService transactionService;

    @InjectMocks
    private DocumentProcessingService documentProcessingService;

    @Captor
    private ArgumentCaptor<Wrapper<KnowledgeDocument>> documentQueryCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<KnowledgeChunk>> chunkQueryCaptor;

    @Captor
    private ArgumentCaptor<String> parseErrorCaptor;

    @Test
    void shouldProcessOwnedPendingDocumentsUsingTheKnowledgeBaseChunkSettings() throws Exception {
        KnowledgeDocument document = document(301L, "source.md");
        ChunkDraft draft = new ChunkDraft(0, "Refund rules", "Refund", 12, 2);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(knowledgeDocumentMapper.selectList(any())).thenReturn(List.of(document));
        when(transactionService.claimPendingDocument(document)).thenReturn(true);
        when(documentStorage.open(any(StoredDocument.class))).thenReturn(new ByteArrayInputStream(
                "# Refund\nRefund rules".getBytes(StandardCharsets.UTF_8)
        ));
        ParsedDocument parsedDocument = new ParsedDocument("# Refund\nRefund rules", List.of());
        when(documentParserResolver.parse(eq(DocumentFileType.MD), any(InputStream.class)))
                .thenReturn(parsedDocument);
        when(documentChunker.chunk(parsedDocument, 800, 120)).thenReturn(List.of(draft));

        DocumentProcessingResponse response = documentProcessingService.processPending(currentUser(), 201L);

        verify(knowledgeDocumentMapper).selectList(documentQueryCaptor.capture());
        assertThat(documentQueryCaptor.getValue().getSqlSegment()).contains(
                "knowledge_base_id", "user_id", "parse_status", "deleted_at", "created_at", "id"
        );
        verify(documentStorage).open(new StoredDocument("local", "objects/source.md"));
        verify(transactionService).persistChunksAndMarkCompleted(document, List.of(draft));
        assertThat(response).isEqualTo(new DocumentProcessingResponse(1, 1, 1, 0, 0));
    }

    @Test
    void shouldMarkOneUnreadableDocumentFailedAndContinueWithTheNextOne() throws Exception {
        KnowledgeDocument missingDocument = document(301L, "missing.md");
        KnowledgeDocument readableDocument = document(302L, "readable.md");
        ChunkDraft draft = new ChunkDraft(0, "Readable rules", null, 14, 2);
        ParsedDocument parsedDocument = new ParsedDocument("Readable rules", List.of());
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(knowledgeDocumentMapper.selectList(any())).thenReturn(List.of(missingDocument, readableDocument));
        when(transactionService.claimPendingDocument(any())).thenReturn(true);
        when(documentStorage.open(argThat(stored -> stored != null
                && stored.storageObjectKey().equals("objects/missing.md"))))
                .thenThrow(new NoSuchFileException("/internal/source/path"));
        when(documentStorage.open(argThat(stored -> stored != null
                && stored.storageObjectKey().equals("objects/readable.md"))))
                .thenReturn(new ByteArrayInputStream("Readable rules".getBytes(StandardCharsets.UTF_8)));
        when(documentParserResolver.parse(eq(DocumentFileType.MD), any(InputStream.class)))
                .thenReturn(parsedDocument);
        when(documentChunker.chunk(parsedDocument, 800, 120)).thenReturn(List.of(draft));

        DocumentProcessingResponse response = documentProcessingService.processPending(currentUser(), 201L);

        verify(transactionService).markFailed(eq(missingDocument), parseErrorCaptor.capture());
        assertThat(parseErrorCaptor.getValue()).isEqualTo("Source document is unavailable");
        verify(transactionService).persistChunksAndMarkCompleted(readableDocument, List.of(draft));
        assertThat(response).isEqualTo(new DocumentProcessingResponse(2, 2, 1, 1, 0));
    }

    @Test
    void shouldSkipADocumentAlreadyClaimedByAnotherTriggerWithoutReadingIt() throws Exception {
        KnowledgeDocument document = document(301L, "source.md");
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase());
        when(knowledgeDocumentMapper.selectList(any())).thenReturn(List.of(document));
        when(transactionService.claimPendingDocument(document)).thenReturn(false);

        DocumentProcessingResponse response = documentProcessingService.processPending(currentUser(), 201L);

        verify(documentStorage, never()).open(any(StoredDocument.class));
        verify(transactionService, never()).persistChunksAndMarkCompleted(any(), any());
        assertThat(response).isEqualTo(new DocumentProcessingResponse(1, 0, 0, 0, 1));
    }

    @Test
    void shouldHideAnUnownedKnowledgeBaseBeforeLookingUpPendingDocuments() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> documentProcessingService.processPending(currentUser(), 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_NOT_FOUND));

        verify(knowledgeDocumentMapper, never()).selectList(any());
    }

    @Test
    void shouldListOnlyAnOwnedDocumentsChunksInStableIndexOrder() {
        KnowledgeDocument document = document(301L, "source.md");
        document.setParseStatus("COMPLETED");
        when(knowledgeBaseMapper.selectVisibleOwnedForShare(201L, 101L)).thenReturn(knowledgeBase());
        when(knowledgeDocumentMapper.selectVisibleOwnedInKnowledgeBaseForShare(301L, 201L, 101L))
                .thenReturn(document);
        when(knowledgeChunkMapper.selectVisibleCompletedDocumentPage(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChunk>>any(),
                eq(301L), eq(201L), eq(101L)
        )).thenAnswer(invocation -> {
            Page<KnowledgeChunk> page = invocation.getArgument(0);
            page.setRecords(List.of(chunk(401L, 0, "first"), chunk(402L, 1, "second")));
            page.setTotal(2L);
            return page;
        });

        PageResult<KnowledgeChunkResponse> page = documentProcessingService.listOwnedDocumentChunks(
                currentUser(),
                201L,
                301L,
                new PageRequest(1, 20)
        );

        verify(knowledgeChunkMapper).selectVisibleCompletedDocumentPage(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChunk>>any(),
                eq(301L), eq(201L), eq(101L)
        );
        assertThat(page.getItems()).extracting(KnowledgeChunkResponse::content)
                .containsExactly("first", "second");
        assertThat(page.getItems()).extracting(KnowledgeChunkResponse::chunkIndex)
                .containsExactly(0, 1);
    }

    @ParameterizedTest(name = "{0} document returns a canonical empty chunk page")
    @ValueSource(strings = {"PENDING", "PROCESSING", "FAILED", "REPROCESSING"})
    void shouldHideChunksUnlessTheDocumentIsCompleted(String parseStatus) {
        KnowledgeDocument document = document(301L, "source.md");
        document.setParseStatus(parseStatus);
        when(knowledgeBaseMapper.selectVisibleOwnedForShare(201L, 101L)).thenReturn(knowledgeBase());
        when(knowledgeDocumentMapper.selectVisibleOwnedInKnowledgeBaseForShare(301L, 201L, 101L))
                .thenReturn(document);

        PageResult<KnowledgeChunkResponse> page = documentProcessingService.listOwnedDocumentChunks(
                currentUser(), 201L, 301L, new PageRequest(2, 20)
        );

        assertThat(page.getItems()).isEmpty();
        assertThat(page.getTotal()).isZero();
        assertThat(page.getPage()).isEqualTo(2);
        verify(knowledgeChunkMapper, never()).selectVisibleCompletedDocumentPage(any(), any(), any(), any());
    }

    private static void initializeLambdaCache(Class<?> mapperType, Class<?> entityType) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(),
                "DocumentProcessingServiceTest"
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
        knowledgeBase.setName("Payment knowledge base");
        knowledgeBase.setStatus("ACTIVE");
        knowledgeBase.setChunkSize(800);
        knowledgeBase.setChunkOverlap(120);
        return knowledgeBase;
    }

    private static KnowledgeDocument document(Long id, String objectKeySuffix) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setUserId(101L);
        document.setKnowledgeBaseId(201L);
        document.setFileName("refund-rules.md");
        document.setFileType("MD");
        document.setStorageBucket("local");
        document.setStorageObjectKey("objects/" + objectKeySuffix);
        document.setParseStatus("PENDING");
        document.setVectorGeneration(0L);
        document.setCreatedAt(OffsetDateTime.parse("2026-08-14T12:00:00+08:00"));
        return document;
    }

    private static KnowledgeChunk chunk(Long id, int index, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setUserId(101L);
        chunk.setKnowledgeBaseId(201L);
        chunk.setDocumentId(301L);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setCharCount(content.codePointCount(0, content.length()));
        chunk.setTokenCount(1);
        chunk.setCreatedAt(OffsetDateTime.parse("2026-08-14T12:00:00+08:00"));
        chunk.setUpdatedAt(chunk.getCreatedAt());
        return chunk;
    }
}
