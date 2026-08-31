package com.agentflow.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.chunk.ChunkDraft;
import com.agentflow.knowledge.chunk.DocumentChunker;
import com.agentflow.knowledge.chunk.DocumentChunkingException;
import com.agentflow.knowledge.dto.DocumentProcessingResponse;
import com.agentflow.knowledge.dto.KnowledgeChunkResponse;
import com.agentflow.knowledge.model.DocumentFileType;
import com.agentflow.knowledge.model.DocumentParseStatus;
import com.agentflow.knowledge.model.KnowledgeBase;
import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.model.KnowledgeDocument;
import com.agentflow.knowledge.parser.DocumentParseException;
import com.agentflow.knowledge.parser.DocumentParserResolver;
import com.agentflow.knowledge.parser.ParsedDocument;
import com.agentflow.knowledge.repository.KnowledgeBaseMapper;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentMapper;
import com.agentflow.knowledge.storage.DocumentStorage;
import com.agentflow.knowledge.storage.StoredDocument;
import com.agentflow.user.security.AuthenticatedUser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：V4 文本处理的 owner-scoped 编排层。它只处理已存在的 PENDING TXT/MD，读取受控的原始
 * 存储对象，生成稳定文本块，然后由独立事务服务提交 chunks 与状态。它不调用 embedding、Qdrant、
 * 队列或自动重试。
 *
 * <p>English: Owner-scoped orchestration for V4 text processing. It processes only
 * existing PENDING TXT/MD documents, reads controlled source objects, creates stable
 * text chunks, and asks an independent transaction service to commit chunks and state.
 * It does not call embeddings, Qdrant, queues, or automatic retries.
 */
@Service
public class DocumentProcessingService {
    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);
    private static final int MAX_PARSE_ERROR_LENGTH = 500;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final DocumentStorage documentStorage;
    private final DocumentParserResolver documentParserResolver;
    private final DocumentChunker documentChunker;
    private final DocumentProcessingTransactionService transactionService;

    public DocumentProcessingService(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeDocumentMapper knowledgeDocumentMapper,
            KnowledgeChunkMapper knowledgeChunkMapper,
            DocumentStorage documentStorage,
            DocumentParserResolver documentParserResolver,
            DocumentChunker documentChunker,
            DocumentProcessingTransactionService transactionService
    ) {
        this.knowledgeBaseMapper = Objects.requireNonNull(knowledgeBaseMapper, "knowledgeBaseMapper must not be null");
        this.knowledgeDocumentMapper = Objects.requireNonNull(
                knowledgeDocumentMapper,
                "knowledgeDocumentMapper must not be null"
        );
        this.knowledgeChunkMapper = Objects.requireNonNull(knowledgeChunkMapper, "knowledgeChunkMapper must not be null");
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage must not be null");
        this.documentParserResolver = Objects.requireNonNull(
                documentParserResolver,
                "documentParserResolver must not be null"
        );
        this.documentChunker = Objects.requireNonNull(documentChunker, "documentChunker must not be null");
        this.transactionService = Objects.requireNonNull(transactionService, "transactionService must not be null");
    }

    /**
     * Processes the currently PENDING documents for one owner-scoped knowledge base.
     * The outer loop is intentionally non-transactional: reading/decoding may take
     * time, while each database state transition remains short and independent.
     *
     * <p>A disabled knowledge base cannot accept new V3 uploads, but its owner may
     * finish already accepted PENDING documents here. This operation never adds new
     * source material or makes an unowned resource visible.
     */
    public DocumentProcessingResponse processPending(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        validatePositiveId(knowledgeBaseId, "knowledgeBaseId");
        KnowledgeBase knowledgeBase = requireOwnedKnowledgeBase(currentUser, knowledgeBaseId);

        List<KnowledgeDocument> pendingDocuments = knowledgeDocumentMapper.selectList(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeDocument::getUserId, currentUser.id())
                        .eq(KnowledgeDocument::getParseStatus, DocumentParseStatus.PENDING.name())
                        .isNull(KnowledgeDocument::getDeletedAt)
                        .orderByAsc(KnowledgeDocument::getCreatedAt, KnowledgeDocument::getId)
        );

        int claimed = 0;
        int completed = 0;
        int failed = 0;
        int skipped = 0;
        for (KnowledgeDocument document : pendingDocuments) {
            if (!transactionService.claimPendingDocument(document)) {
                skipped++;
                continue;
            }
            claimed++;
            try {
                List<ChunkDraft> chunks = parseAndChunk(document, knowledgeBase);
                transactionService.persistChunksAndMarkCompleted(document, chunks);
                completed++;
            } catch (Exception processingFailure) {
                String safeError = summarizeFailure(processingFailure);
                log.warn("Document processing failed for documentId={}", document.getId(), processingFailure);
                transactionService.markFailed(document, safeError);
                failed++;
            }
        }
        return new DocumentProcessingResponse(
                pendingDocuments.size(),
                claimed,
                completed,
                failed,
                skipped
        );
    }

    /**
     * Provides a direct, owner-scoped verification route for already-persisted chunks.
     * A PENDING/FAILED document simply returns an empty chunk page; parse errors remain
     * an internal operational detail and are not returned to HTTP clients.
     */
    @Transactional
    public PageResult<KnowledgeChunkResponse> listOwnedDocumentChunks(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            Long documentId,
            PageRequest pageRequest
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");
        validatePositiveId(knowledgeBaseId, "knowledgeBaseId");
        validatePositiveId(documentId, "documentId");
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectVisibleOwnedForShare(
                knowledgeBaseId,
                currentUser.id()
        );
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Knowledge base not found");
        }
        KnowledgeDocument document = knowledgeDocumentMapper.selectVisibleOwnedInKnowledgeBaseForShare(
                documentId,
                knowledgeBaseId,
                currentUser.id()
        );
        if (document == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Document not found");
        }
        if (!DocumentParseStatus.COMPLETED.name().equals(document.getParseStatus())) {
            return PageResult.of(
                    List.of(),
                    pageRequest.getPage(),
                    pageRequest.getPageSize(),
                    0
            );
        }

        Page<KnowledgeChunk> databasePage = new Page<>(pageRequest.getPage(), pageRequest.getPageSize());
        knowledgeChunkMapper.selectVisibleCompletedDocumentPage(
                databasePage,
                documentId,
                knowledgeBaseId,
                currentUser.id()
        );
        return PageResult.of(
                databasePage.getRecords().stream().map(KnowledgeChunkResponse::from).toList(),
                pageRequest.getPage(),
                pageRequest.getPageSize(),
                databasePage.getTotal()
        );
    }

    private List<ChunkDraft> parseAndChunk(KnowledgeDocument document, KnowledgeBase knowledgeBase)
            throws IOException {
        DocumentFileType fileType;
        try {
            fileType = DocumentFileType.valueOf(document.getFileType().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new DocumentChunkingException("Document has an unsupported stored file type");
        }

        Integer chunkSize = knowledgeBase.getChunkSize();
        Integer chunkOverlap = knowledgeBase.getChunkOverlap();
        if (chunkSize == null || chunkOverlap == null) {
            throw new DocumentChunkingException("Knowledge base chunk settings are unavailable");
        }

        try (InputStream content = documentStorage.open(new StoredDocument(
                document.getStorageBucket(),
                document.getStorageObjectKey()
        ))) {
            ParsedDocument parsedDocument = documentParserResolver.parse(fileType, content);
            return documentChunker.chunk(parsedDocument, chunkSize, chunkOverlap);
        }
    }

    private KnowledgeBase requireOwnedKnowledgeBase(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId
    ) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(
                Wrappers.<KnowledgeBase>lambdaQuery()
                        .eq(KnowledgeBase::getId, knowledgeBaseId)
                        .eq(KnowledgeBase::getUserId, currentUser.id())
                        .isNull(KnowledgeBase::getDeletedAt)
        );
        if (knowledgeBase == null) {
            // Missing, unowned, and soft-deleted knowledge bases share one response to
            // prevent authenticated users from enumerating somebody else's resources.
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Knowledge base not found");
        }
        return knowledgeBase;
    }

    private void requireOwnedDocument(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            Long documentId
    ) {
        KnowledgeDocument document = knowledgeDocumentMapper.selectOne(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getId, documentId)
                        .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeDocument::getUserId, currentUser.id())
                        .isNull(KnowledgeDocument::getDeletedAt)
        );
        if (document == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Document not found");
        }
    }

    private static void validatePositiveId(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    fieldName + " must be a positive integer"
            );
        }
    }

    private static String summarizeFailure(Exception failure) {
        String message;
        if (failure instanceof DocumentParseException || failure instanceof DocumentChunkingException) {
            message = failure.getMessage();
        } else if (failure instanceof NoSuchFileException) {
            message = "Source document is unavailable";
        } else if (failure instanceof IOException) {
            message = "Source document cannot be read";
        } else {
            message = "Document processing failed";
        }
        if (message == null || message.isBlank()) {
            return "Document processing failed";
        }
        return message.length() <= MAX_PARSE_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_PARSE_ERROR_LENGTH);
    }
}
