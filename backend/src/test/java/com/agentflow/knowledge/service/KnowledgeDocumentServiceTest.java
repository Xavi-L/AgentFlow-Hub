package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.model.DocumentFileType;
import com.agentflow.knowledge.model.KnowledgeBase;
import com.agentflow.knowledge.model.KnowledgeDocument;
import com.agentflow.knowledge.repository.KnowledgeBaseMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentMapper;
import com.agentflow.knowledge.storage.DocumentStorage;
import com.agentflow.knowledge.storage.DocumentUploadLimitProperties;
import com.agentflow.knowledge.storage.StoredDocument;
import com.agentflow.user.security.AuthenticatedUser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 中文：不依赖真实 PostgreSQL 的文档接入 Service 测试。它验证 owner 边界、PENDING 元数据、
 * 文件校验、分页范围以及文件系统与数据库之间的最小补偿逻辑。
 *
 * <p>English: Database-free document-ingestion service tests. They verify owner
 * boundaries, PENDING metadata, file validation, scoped pagination, and the minimal
 * compensation between filesystem storage and database metadata.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceTest {

    @BeforeAll
    static void initializeLambdaCaches() {
        initializeLambdaCache(KnowledgeBaseMapper.class, KnowledgeBase.class);
        initializeLambdaCache(KnowledgeDocumentMapper.class, KnowledgeDocument.class);
    }

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Mock
    private DocumentStorage documentStorage;

    /** Uses the same Spring multipart property binding as production with its 20 MB default. */
    @Spy
    private DocumentUploadLimitProperties documentUploadLimitProperties =
            new DocumentUploadLimitProperties();

    @InjectMocks
    private KnowledgeDocumentService knowledgeDocumentService;

    @Captor
    private ArgumentCaptor<KnowledgeBase> knowledgeBaseCaptor;

    @Captor
    private ArgumentCaptor<KnowledgeDocument> documentCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<KnowledgeBase>> knowledgeBaseQueryCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<KnowledgeDocument>> documentQueryCaptor;

    @Captor
    private ArgumentCaptor<OffsetDateTime> reprocessUpdatedAtCaptor;

    @Test
    void shouldStoreAnOwnedMarkdownDocumentAsPending() throws Exception {
        AuthenticatedUser currentUser = currentUser();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(201L, "ACTIVE"));
        StoredDocument storedDocument = storedDocument();
        when(documentStorage.store(
                eq(101L),
                eq(201L),
                eq(DocumentFileType.MD),
                org.mockito.ArgumentMatchers.<InputStream>any()
        )).thenReturn(storedDocument);
        when(knowledgeDocumentMapper.insert(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            KnowledgeDocument document = invocation.getArgument(0);
            document.setId(301L);
            return 1;
        });

        KnowledgeDocumentResponse response = knowledgeDocumentService.upload(
                currentUser,
                201L,
                uploadedFile("refund-rules.MD", "# Refund rules\n")
        );

        verify(knowledgeBaseMapper).selectOne(knowledgeBaseQueryCaptor.capture());
        verify(knowledgeDocumentMapper).insert(documentCaptor.capture());
        KnowledgeDocument persisted = documentCaptor.getValue();
        assertThat(knowledgeBaseQueryCaptor.getValue().getSqlSegment())
                .contains("id", "user_id", "deleted_at");
        assertThat(persisted.getUserId()).isEqualTo(101L);
        assertThat(persisted.getKnowledgeBaseId()).isEqualTo(201L);
        assertThat(persisted.getFileName()).isEqualTo("refund-rules.MD");
        assertThat(persisted.getFileType()).isEqualTo("MD");
        assertThat(persisted.getMimeType()).isEqualTo("text/markdown");
        assertThat(persisted.getFileSize()).isEqualTo(15L);
        assertThat(persisted.getStorageBucket()).isEqualTo("local");
        assertThat(persisted.getStorageObjectKey()).doesNotContain("refund-rules.MD");
        assertThat(persisted.getParseStatus()).isEqualTo("PENDING");
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isEqualTo(persisted.getCreatedAt());
        assertThat(response.id()).isEqualTo("301");
        assertThat(response.knowledgeBaseId()).isEqualTo("201");
        assertThat(response.parseStatus()).isEqualTo("PENDING");
    }

    @Test
    void shouldRejectAnUnsupportedOrEmptyFileBeforeWritingIt() throws Exception {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(201L, "ACTIVE"));

        assertThatThrownBy(() -> knowledgeDocumentService.upload(
                currentUser(),
                201L,
                uploadedFile("manual.pdf", "not supported")
        )).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_TYPE_UNSUPPORTED));

        assertThatThrownBy(() -> knowledgeDocumentService.upload(
                currentUser(),
                201L,
                uploadedFile("empty.md", "")
        )).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_EMPTY));

        verify(documentStorage, never()).store(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                any(DocumentFileType.class),
                org.mockito.ArgumentMatchers.<InputStream>any()
        );
        verify(knowledgeDocumentMapper, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void shouldRejectAMissingFileBeforeWritingIt() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(201L, "ACTIVE"));

        assertThatThrownBy(() -> knowledgeDocumentService.upload(currentUser(), 201L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_REQUIRED));

        verify(knowledgeDocumentMapper, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void shouldTreatMissingOrAnotherUsersKnowledgeBaseAsNotFoundBeforeStorage() throws Exception {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> knowledgeDocumentService.upload(
                currentUser(),
                999L,
                uploadedFile("refund-rules.md", "rules")
        )).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_NOT_FOUND))
                .hasMessage("Knowledge base not found");

        verify(knowledgeBaseMapper).selectOne(knowledgeBaseQueryCaptor.capture());
        assertThat(knowledgeBaseQueryCaptor.getValue().getSqlSegment())
                .contains("id", "user_id", "deleted_at");
        verify(documentStorage, never()).store(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                any(DocumentFileType.class),
                org.mockito.ArgumentMatchers.<InputStream>any()
        );
        verify(knowledgeDocumentMapper, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void shouldRejectUploadToADisabledKnowledgeBaseWithoutWritingAFile() throws Exception {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(201L, "DISABLED"));

        assertThatThrownBy(() -> knowledgeDocumentService.upload(
                currentUser(),
                201L,
                uploadedFile("refund-rules.md", "rules")
        )).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_ACTIVE));

        verify(documentStorage, never()).store(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                any(DocumentFileType.class),
                org.mockito.ArgumentMatchers.<InputStream>any()
        );
    }

    @Test
    void shouldDeleteTheStoredFileWhenMetadataInsertFails() throws Exception {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(201L, "ACTIVE"));
        StoredDocument storedDocument = storedDocument();
        when(documentStorage.store(
                eq(101L), eq(201L), eq(DocumentFileType.TXT),
                org.mockito.ArgumentMatchers.<InputStream>any()
        )).thenReturn(storedDocument);
        when(knowledgeDocumentMapper.insert(any(KnowledgeDocument.class))).thenReturn(0);

        assertThatThrownBy(() -> knowledgeDocumentService.upload(
                currentUser(),
                201L,
                uploadedFile("rules.txt", "rules")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected exactly one inserted knowledge_document row");

        verify(documentStorage).delete(storedDocument);
    }

    @Test
    void shouldNotInsertMetadataWhenStorageFails() throws Exception {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(201L, "ACTIVE"));
        when(documentStorage.store(
                eq(101L), eq(201L), eq(DocumentFileType.TXT),
                org.mockito.ArgumentMatchers.<InputStream>any()
        )).thenThrow(new IOException("disk unavailable"));

        assertThatThrownBy(() -> knowledgeDocumentService.upload(
                currentUser(),
                201L,
                uploadedFile("rules.txt", "rules")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to store uploaded document");

        verify(knowledgeDocumentMapper, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void shouldDeleteTheStoredFileWhenAnEnclosingTransactionRollsBackAfterTheInsert()
            throws Exception {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(201L, "ACTIVE"));
        StoredDocument storedDocument = storedDocument();
        when(documentStorage.store(
                eq(101L), eq(201L), eq(DocumentFileType.TXT),
                org.mockito.ArgumentMatchers.<InputStream>any()
        )).thenReturn(storedDocument);
        when(knowledgeDocumentMapper.insert(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            invocation.<KnowledgeDocument>getArgument(0).setId(301L);
            return 1;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            knowledgeDocumentService.upload(currentUser(), 201L, uploadedFile("rules.txt", "rules"));

            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            );
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(documentStorage).delete(storedDocument);
    }

    @Test
    void shouldUseTheSameConfiguredMultipartLimitForTheDefensiveServiceCheck()
            throws Exception {
        documentUploadLimitProperties.setMaxFileSize(DataSize.ofBytes(4));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(201L, "ACTIVE"));

        assertThatThrownBy(() -> knowledgeDocumentService.upload(
                currentUser(),
                201L,
                uploadedFile("rules.txt", "five!")
        )).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_TOO_LARGE));

        verify(documentStorage, never()).store(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                any(DocumentFileType.class),
                org.mockito.ArgumentMatchers.<InputStream>any()
        );
    }

    @Test
    void shouldReadOneVisibleOwnedDocumentThroughTheJointVisibilityQuery() {
        KnowledgeDocument persisted = document(301L, 201L, "refund-rules.md", "MD");
        persisted.setParseStatus("FAILED");
        persisted.setStorageBucket("local");
        persisted.setStorageObjectKey("users/101/knowledge-bases/201/documents/opaque-object.md");
        persisted.setParseError("Internal parser diagnostic");
        when(knowledgeDocumentMapper.selectVisibleOwnedById(301L, 101L)).thenReturn(persisted);

        KnowledgeDocumentResponse response = knowledgeDocumentService.getOwnedById(
                currentUser(),
                301L
        );

        assertThat(response.id()).isEqualTo("301");
        assertThat(response.knowledgeBaseId()).isEqualTo("201");
        assertThat(response.fileName()).isEqualTo("refund-rules.md");
        assertThat(response.fileType()).isEqualTo("MD");
        assertThat(response.fileSize()).isEqualTo(5L);
        assertThat(response.parseStatus()).isEqualTo("FAILED");
        assertThat(response.createdAt()).isEqualTo(persisted.getCreatedAt());
        assertThat(response.updatedAt()).isEqualTo(persisted.getUpdatedAt());
        verify(knowledgeDocumentMapper).selectVisibleOwnedById(301L, 101L);
        verify(knowledgeBaseMapper, never()).selectOne(any());
    }

    @Test
    void shouldReadAVisibleDocumentWithoutRequiringAnActiveKnowledgeBase() {
        // The mapper owns parent visibility in its JOIN. No separate status fetch is allowed,
        // so a matching row from a DISABLED (but not deleted) knowledge base stays readable.
        when(knowledgeDocumentMapper.selectVisibleOwnedById(301L, 101L)).thenReturn(
                document(301L, 201L, "historical-rules.txt", "TXT")
        );

        KnowledgeDocumentResponse response = knowledgeDocumentService.getOwnedById(
                currentUser(),
                301L
        );

        assertThat(response.fileName()).isEqualTo("historical-rules.txt");
        verify(knowledgeDocumentMapper).selectVisibleOwnedById(301L, 101L);
        verify(knowledgeBaseMapper, never()).selectOne(any());
    }

    @ParameterizedTest(name = "{0} maps to the same document-not-found response")
    @ValueSource(strings = {
            "missing document",
            "another owner's document",
            "soft-deleted document",
            "document whose parent knowledge base is soft-deleted"
    })
    void shouldMapEveryInvisibleDocumentScopeMissToTheSameNotFound(String invisibleCause) {
        // Each cause becomes the same null result after the mapper's single scoped JOIN.
        when(knowledgeDocumentMapper.selectVisibleOwnedById(301L, 101L)).thenReturn(null);

        assertThatThrownBy(() -> knowledgeDocumentService.getOwnedById(currentUser(), 301L))
                .as(invisibleCause)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_NOT_FOUND))
                .hasMessage("Document not found");

        verify(knowledgeDocumentMapper).selectVisibleOwnedById(301L, 101L);
        verify(knowledgeBaseMapper, never()).selectOne(any());
    }

    @Test
    void shouldReprocessAFailedVisibleOwnedDocumentToPendingWithItsDiagnosticCleared() {
        KnowledgeDocument failedDocument = document(301L, 201L, "refund-rules.md", "MD");
        failedDocument.setParseStatus("FAILED");
        failedDocument.setParseError("Internal parser diagnostic");
        when(knowledgeDocumentMapper.reprocessFailedVisibleOwned(
                eq(301L),
                eq(101L),
                org.mockito.ArgumentMatchers.any(OffsetDateTime.class)
        )).thenAnswer(invocation -> {
            OffsetDateTime updatedAt = invocation.getArgument(2);
            failedDocument.setParseStatus("PENDING");
            failedDocument.setParseError(null);
            failedDocument.setUpdatedAt(updatedAt);
            return failedDocument;
        });

        KnowledgeDocumentResponse response = knowledgeDocumentService.reprocessOwnedFailed(
                currentUser(),
                301L
        );

        verify(knowledgeDocumentMapper).reprocessFailedVisibleOwned(
                eq(301L),
                eq(101L),
                reprocessUpdatedAtCaptor.capture()
        );
        assertThat(reprocessUpdatedAtCaptor.getValue()).isNotNull();
        assertThat(failedDocument.getParseStatus()).isEqualTo("PENDING");
        assertThat(failedDocument.getParseError()).isNull();
        assertThat(response.id()).isEqualTo("301");
        assertThat(response.parseStatus()).isEqualTo("PENDING");
        assertThat(response.updatedAt()).isEqualTo(reprocessUpdatedAtCaptor.getValue());
        verify(knowledgeDocumentMapper, never()).selectVisibleOwnedById(any(), any());
        verify(knowledgeBaseMapper, never()).selectOne(any());
        verifyNoInteractions(documentStorage);
    }

    @Test
    void shouldAllowReprocessingAFailedDocumentWhoseVisibleParentKnowledgeBaseIsDisabled() {
        // The dedicated joined UPDATE owns parent visibility. A DISABLED parent is still visible
        // to its owner, so this service must not use the upload-only ACTIVE precondition.
        KnowledgeDocument requeuedDocument = document(301L, 201L, "historical-rules.txt", "TXT");
        requeuedDocument.setParseStatus("PENDING");
        requeuedDocument.setParseError(null);
        when(knowledgeDocumentMapper.reprocessFailedVisibleOwned(
                eq(301L),
                eq(101L),
                org.mockito.ArgumentMatchers.any(OffsetDateTime.class)
        )).thenReturn(requeuedDocument);

        KnowledgeDocumentResponse response = knowledgeDocumentService.reprocessOwnedFailed(
                currentUser(),
                301L
        );

        assertThat(response.fileName()).isEqualTo("historical-rules.txt");
        assertThat(response.parseStatus()).isEqualTo("PENDING");
        verify(knowledgeDocumentMapper).reprocessFailedVisibleOwned(
                eq(301L),
                eq(101L),
                org.mockito.ArgumentMatchers.any(OffsetDateTime.class)
        );
        verify(knowledgeDocumentMapper, never()).selectVisibleOwnedById(any(), any());
        verify(knowledgeBaseMapper, never()).selectOne(any());
    }

    @ParameterizedTest(name = "{0} maps to the same document-not-found response when reprocessing")
    @ValueSource(strings = {
            "missing document",
            "another owner's document",
            "soft-deleted document",
            "document whose parent knowledge base is soft-deleted"
    })
    void shouldMapEveryInvisibleDocumentScopeMissToTheSameNotFoundWhenReprocessing(
            String invisibleCause
    ) {
        // The conditional UPDATE and its fallback SELECT both use the same owner-and-parent scope.
        when(knowledgeDocumentMapper.reprocessFailedVisibleOwned(
                eq(301L),
                eq(101L),
                org.mockito.ArgumentMatchers.any(OffsetDateTime.class)
        )).thenReturn(null);
        when(knowledgeDocumentMapper.selectVisibleOwnedById(301L, 101L)).thenReturn(null);

        assertThatThrownBy(() -> knowledgeDocumentService.reprocessOwnedFailed(currentUser(), 301L))
                .as(invisibleCause)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_NOT_FOUND))
                .hasMessage("Document not found");

        verify(knowledgeDocumentMapper).reprocessFailedVisibleOwned(
                eq(301L),
                eq(101L),
                org.mockito.ArgumentMatchers.any(OffsetDateTime.class)
        );
        verify(knowledgeDocumentMapper).selectVisibleOwnedById(301L, 101L);
        verify(knowledgeBaseMapper, never()).selectOne(any());
        verifyNoInteractions(documentStorage);
    }

    @ParameterizedTest(name = "visible {0} document cannot be reprocessed")
    @ValueSource(strings = {"PENDING", "PROCESSING", "COMPLETED"})
    void shouldRejectEachVisibleNonFailedDocumentStatusFromReprocessing(String parseStatus) {
        KnowledgeDocument visibleDocument = document(301L, 201L, "refund-rules.md", "MD");
        visibleDocument.setParseStatus(parseStatus);
        when(knowledgeDocumentMapper.reprocessFailedVisibleOwned(
                eq(301L),
                eq(101L),
                org.mockito.ArgumentMatchers.any(OffsetDateTime.class)
        )).thenReturn(null);
        when(knowledgeDocumentMapper.selectVisibleOwnedById(301L, 101L)).thenReturn(visibleDocument);

        assertThatThrownBy(() -> knowledgeDocumentService.reprocessOwnedFailed(currentUser(), 301L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT))
                .hasMessage("Document is not eligible for reprocessing");

        verify(knowledgeDocumentMapper).reprocessFailedVisibleOwned(
                eq(301L),
                eq(101L),
                org.mockito.ArgumentMatchers.any(OffsetDateTime.class)
        );
        verify(knowledgeDocumentMapper).selectVisibleOwnedById(301L, 101L);
        verify(knowledgeBaseMapper, never()).selectOne(any());
        verifyNoInteractions(documentStorage);
    }

    @Test
    void shouldListOnlyTheCurrentOwnersDocumentsAfterCheckingTheParentKnowledgeBase() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(201L, "DISABLED"));
        when(knowledgeDocumentMapper.selectPage(
                org.mockito.ArgumentMatchers.<IPage<KnowledgeDocument>>any(),
                org.mockito.ArgumentMatchers.<Wrapper<KnowledgeDocument>>any()
        )).thenAnswer(invocation -> {
            IPage<KnowledgeDocument> page = invocation.getArgument(0);
            page.setRecords(List.of(
                    document(302L, 201L, "newer.md", "MD"),
                    document(301L, 201L, "older.txt", "TXT")
            ));
            page.setTotal(3L);
            return page;
        });

        PageResult<KnowledgeDocumentResponse> result = knowledgeDocumentService.listOwnedByKnowledgeBase(
                currentUser(),
                201L,
                new PageRequest(1, 2)
        );

        verify(knowledgeDocumentMapper).selectPage(
                org.mockito.ArgumentMatchers.<IPage<KnowledgeDocument>>any(),
                documentQueryCaptor.capture()
        );
        assertThat(documentQueryCaptor.getValue().getSqlSegment()).contains(
                "knowledge_base_id", "user_id", "deleted_at", "created_at", "id"
        );
        assertThat(result.getItems()).extracting(KnowledgeDocumentResponse::id)
                .containsExactly("302", "301");
        assertThat(result.getItems()).extracting(KnowledgeDocumentResponse::fileType)
                .containsExactly("MD", "TXT");
        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.isHasNext()).isTrue();
    }

    private static void initializeLambdaCache(Class<?> mapperType, Class<?> entityType) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(),
                "KnowledgeDocumentServiceTest"
        );
        assistant.setCurrentNamespace(mapperType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static KnowledgeBase knowledgeBase(Long id, String status) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(id);
        knowledgeBase.setUserId(101L);
        knowledgeBase.setName("Payment knowledge base");
        knowledgeBase.setStatus(status);
        return knowledgeBase;
    }

    private static KnowledgeDocument document(Long id, Long knowledgeBaseId, String name, String type) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-14T12:00:00+08:00");
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setUserId(101L);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setFileName(name);
        document.setFileType(type);
        document.setMimeType("TXT".equals(type) ? "text/plain" : "text/markdown");
        document.setFileSize(5L);
        document.setParseStatus("PENDING");
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        return document;
    }

    private static StoredDocument storedDocument() {
        return new StoredDocument(
                "local",
                "users/101/knowledge-bases/201/documents/opaque-object.md"
        );
    }

    private static MockMultipartFile uploadedFile(String filename, String content) {
        return new MockMultipartFile(
                "file",
                filename,
                "application/octet-stream",
                content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
