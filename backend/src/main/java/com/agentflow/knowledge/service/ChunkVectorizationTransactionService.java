package com.agentflow.knowledge.service;

import com.agentflow.knowledge.model.ChunkVectorizationStatus;
import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：V5 向量化的短事务边界。外部 embedding/Qdrant 调用永远不持有数据库事务；V24 先锁定
 * 可见且 COMPLETED 的父文档，再以状态条件更新认领仍为 PENDING 的 chunk，成功或失败各自独立提交。
 *
 * <p>English: Short V5 transaction boundaries. External embedding/vector-store calls
 * never hold a database transaction; V24 first locks a visible, COMPLETED parent document,
 * then a conditional update claims only still-PENDING chunks, while success and failure each
 * commit independently.
 */
@Service
public class ChunkVectorizationTransactionService {
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    public ChunkVectorizationTransactionService(
            KnowledgeChunkMapper knowledgeChunkMapper,
            KnowledgeDocumentMapper knowledgeDocumentMapper
    ) {
        this.knowledgeChunkMapper = Objects.requireNonNull(
                knowledgeChunkMapper,
                "knowledgeChunkMapper must not be null"
        );
        this.knowledgeDocumentMapper = Objects.requireNonNull(
                knowledgeDocumentMapper,
                "knowledgeDocumentMapper must not be null"
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimPendingChunk(KnowledgeChunk chunk, String contentHash) {
        validateChunkScope(chunk);
        validateContentHash(contentHash);

        // V24 deletion admission locks this exact parent document row before it checks
        // PROCESSING chunks and writes deleted_at. Taking the same lock here closes the
        // stale-candidate race: a chunk read before deletion cannot become PROCESSING and
        // later upsert a point after the document's V23 deletion has completed.
        if (knowledgeDocumentMapper.selectVectorizableOwnedForChunkClaimForUpdate(
                chunk.getDocumentId(),
                chunk.getKnowledgeBaseId(),
                chunk.getUserId()
        ) == null) {
            return false;
        }

        int affectedRows = knowledgeChunkMapper.update(
                null,
                Wrappers.<KnowledgeChunk>lambdaUpdate()
                        .set(KnowledgeChunk::getVectorizationStatus, ChunkVectorizationStatus.PROCESSING.name())
                        .set(KnowledgeChunk::getVectorizationError, null)
                        .set(KnowledgeChunk::getVectorId, null)
                        .set(KnowledgeChunk::getContentHash, contentHash)
                        .set(KnowledgeChunk::getUpdatedAt, OffsetDateTime.now())
                        .eq(KnowledgeChunk::getId, chunk.getId())
                        .eq(KnowledgeChunk::getUserId, chunk.getUserId())
                        .eq(KnowledgeChunk::getKnowledgeBaseId, chunk.getKnowledgeBaseId())
                        .eq(KnowledgeChunk::getDocumentId, chunk.getDocumentId())
                        .eq(KnowledgeChunk::getVectorizationStatus, ChunkVectorizationStatus.PENDING.name())
        );
        return affectedRows == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(KnowledgeChunk chunk, String vectorId) {
        validateChunkScope(chunk);
        validateVectorId(vectorId);
        int affectedRows = knowledgeChunkMapper.update(
                null,
                Wrappers.<KnowledgeChunk>lambdaUpdate()
                        .set(KnowledgeChunk::getVectorizationStatus, ChunkVectorizationStatus.COMPLETED.name())
                        .set(KnowledgeChunk::getVectorizationError, null)
                        .set(KnowledgeChunk::getVectorId, vectorId)
                        .set(KnowledgeChunk::getUpdatedAt, OffsetDateTime.now())
                        .eq(KnowledgeChunk::getId, chunk.getId())
                        .eq(KnowledgeChunk::getUserId, chunk.getUserId())
                        .eq(KnowledgeChunk::getKnowledgeBaseId, chunk.getKnowledgeBaseId())
                        .eq(KnowledgeChunk::getDocumentId, chunk.getDocumentId())
                        .eq(KnowledgeChunk::getVectorizationStatus, ChunkVectorizationStatus.PROCESSING.name())
        );
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one completed knowledge_chunk row");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(KnowledgeChunk chunk, String vectorizationError) {
        validateChunkScope(chunk);
        String safeError = Objects.requireNonNull(vectorizationError, "vectorizationError must not be null");
        if (safeError.isBlank()) {
            throw new IllegalArgumentException("vectorizationError must not be blank");
        }
        int affectedRows = knowledgeChunkMapper.update(
                null,
                Wrappers.<KnowledgeChunk>lambdaUpdate()
                        .set(KnowledgeChunk::getVectorizationStatus, ChunkVectorizationStatus.FAILED.name())
                        .set(KnowledgeChunk::getVectorizationError, safeError)
                        .set(KnowledgeChunk::getVectorId, null)
                        .set(KnowledgeChunk::getUpdatedAt, OffsetDateTime.now())
                        .eq(KnowledgeChunk::getId, chunk.getId())
                        .eq(KnowledgeChunk::getUserId, chunk.getUserId())
                        .eq(KnowledgeChunk::getKnowledgeBaseId, chunk.getKnowledgeBaseId())
                        .eq(KnowledgeChunk::getDocumentId, chunk.getDocumentId())
                        .eq(KnowledgeChunk::getVectorizationStatus, ChunkVectorizationStatus.PROCESSING.name())
        );
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one failed knowledge_chunk row");
        }
    }

    private static void validateChunkScope(KnowledgeChunk chunk) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        if (chunk.getId() == null
                || chunk.getUserId() == null
                || chunk.getKnowledgeBaseId() == null
                || chunk.getDocumentId() == null) {
            throw new IllegalArgumentException("Chunk id, userId, knowledgeBaseId, and documentId are required");
        }
    }

    private static void validateContentHash(String contentHash) {
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 hex string");
        }
    }

    private static void validateVectorId(String vectorId) {
        if (vectorId == null || vectorId.length() != 36) {
            throw new IllegalArgumentException("vectorId must be a UUID");
        }
        try {
            java.util.UUID.fromString(vectorId);
        } catch (IllegalArgumentException invalidUuid) {
            throw new IllegalArgumentException("vectorId must be a UUID", invalidUuid);
        }
    }
}
