package com.agentflow.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.agentflow.knowledge.chunk.ChunkDraft;
import com.agentflow.knowledge.model.ChunkVectorizationStatus;
import com.agentflow.knowledge.model.DocumentParseStatus;
import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.model.KnowledgeDocument;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentMapper;
import com.agentflow.knowledge.vector.ChunkVectorIdentityFactory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：V4 处理流程中短小、独立的数据库事务边界。先以条件更新认领 PENDING 文档，再在单独事务
 * 中将全部 chunks 与 COMPLETED 一起提交；失败状态也使用独立事务，避免一篇坏文件影响同批其他文件。
 *
 * <p>English: Short, independent database transaction boundaries for V4. A conditional
 * update first claims a PENDING document; another transaction commits every chunk and
 * COMPLETED together. FAILED is also written independently so one bad file does not
 * affect other documents in the batch.
 */
@Service
public class DocumentProcessingTransactionService {
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    public DocumentProcessingTransactionService(
            KnowledgeDocumentMapper knowledgeDocumentMapper,
            KnowledgeChunkMapper knowledgeChunkMapper
    ) {
        this.knowledgeDocumentMapper = Objects.requireNonNull(
                knowledgeDocumentMapper,
                "knowledgeDocumentMapper must not be null"
        );
        this.knowledgeChunkMapper = Objects.requireNonNull(
                knowledgeChunkMapper,
                "knowledgeChunkMapper must not be null"
        );
    }

    /**
     * Atomically changes only an still-PENDING, non-deleted document to PROCESSING.
     * A concurrent trigger that arrives later receives {@code false} and does no I/O.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimPendingDocument(KnowledgeDocument document) {
        validateDocumentScope(document);
        int affectedRows = knowledgeDocumentMapper.update(
                null,
                Wrappers.<KnowledgeDocument>lambdaUpdate()
                        .set(KnowledgeDocument::getParseStatus, DocumentParseStatus.PROCESSING.name())
                        .set(KnowledgeDocument::getParseError, null)
                        .set(KnowledgeDocument::getUpdatedAt, OffsetDateTime.now())
                        .eq(KnowledgeDocument::getId, document.getId())
                        .eq(KnowledgeDocument::getUserId, document.getUserId())
                        .eq(KnowledgeDocument::getKnowledgeBaseId, document.getKnowledgeBaseId())
                        .eq(KnowledgeDocument::getParseStatus, DocumentParseStatus.PENDING.name())
                        .isNull(KnowledgeDocument::getDeletedAt)
        );
        return affectedRows == 1;
    }

    /**
     * Inserts every draft and transitions the claimed document to COMPLETED atomically.
     * Any insert/update exception rolls back the whole chunk set rather than leaving a
     * partial, apparently-complete document behind.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistChunksAndMarkCompleted(
            KnowledgeDocument document,
            List<ChunkDraft> chunks
    ) {
        validateDocumentScope(document);
        List<ChunkDraft> immutableChunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        if (immutableChunks.isEmpty()) {
            throw new IllegalArgumentException("A completed document must have at least one chunk");
        }

        OffsetDateTime now = OffsetDateTime.now();
        for (ChunkDraft chunkDraft : immutableChunks) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setUserId(document.getUserId());
            chunk.setKnowledgeBaseId(document.getKnowledgeBaseId());
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(chunkDraft.chunkIndex());
            chunk.setContent(chunkDraft.content());
            chunk.setTitlePath(chunkDraft.titlePath());
            chunk.setCharCount(chunkDraft.charCount());
            chunk.setTokenCount(chunkDraft.tokenCount());
            chunk.setVectorizationStatus(ChunkVectorizationStatus.PENDING.name());
            chunk.setContentHash(ChunkVectorIdentityFactory.contentHash(chunkDraft.content()));
            chunk.setCreatedAt(now);
            chunk.setUpdatedAt(now);

            int insertedRows = knowledgeChunkMapper.insert(chunk);
            if (insertedRows != 1) {
                throw new IllegalStateException("Expected exactly one inserted knowledge_chunk row");
            }
        }

        int updatedRows = knowledgeDocumentMapper.update(
                null,
                Wrappers.<KnowledgeDocument>lambdaUpdate()
                        .set(KnowledgeDocument::getParseStatus, DocumentParseStatus.COMPLETED.name())
                        .set(KnowledgeDocument::getParseError, null)
                        .set(KnowledgeDocument::getUpdatedAt, now)
                        .eq(KnowledgeDocument::getId, document.getId())
                        .eq(KnowledgeDocument::getUserId, document.getUserId())
                        .eq(KnowledgeDocument::getKnowledgeBaseId, document.getKnowledgeBaseId())
                        .eq(KnowledgeDocument::getParseStatus, DocumentParseStatus.PROCESSING.name())
                        .isNull(KnowledgeDocument::getDeletedAt)
        );
        if (updatedRows != 1) {
            throw new IllegalStateException("Expected exactly one completed knowledge_document row");
        }
    }

    /** Writes a controlled failure summary only if this worker still owns the claim. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(KnowledgeDocument document, String parseError) {
        validateDocumentScope(document);
        String safeError = Objects.requireNonNull(parseError, "parseError must not be null");
        int updatedRows = knowledgeDocumentMapper.update(
                null,
                Wrappers.<KnowledgeDocument>lambdaUpdate()
                        .set(KnowledgeDocument::getParseStatus, DocumentParseStatus.FAILED.name())
                        .set(KnowledgeDocument::getParseError, safeError)
                        .set(KnowledgeDocument::getUpdatedAt, OffsetDateTime.now())
                        .eq(KnowledgeDocument::getId, document.getId())
                        .eq(KnowledgeDocument::getUserId, document.getUserId())
                        .eq(KnowledgeDocument::getKnowledgeBaseId, document.getKnowledgeBaseId())
                        .eq(KnowledgeDocument::getParseStatus, DocumentParseStatus.PROCESSING.name())
                        .isNull(KnowledgeDocument::getDeletedAt)
        );
        if (updatedRows != 1) {
            throw new IllegalStateException("Expected exactly one failed knowledge_document row");
        }
    }

    private static void validateDocumentScope(KnowledgeDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        if (document.getId() == null
                || document.getUserId() == null
                || document.getKnowledgeBaseId() == null) {
            throw new IllegalArgumentException("Document id, userId, and knowledgeBaseId are required");
        }
    }
}
