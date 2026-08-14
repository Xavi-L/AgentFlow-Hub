package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeChunk;
import java.time.OffsetDateTime;

/**
 * 中文：owner 查看自己文本块时的安全输出。它包含可审阅的正文与切分统计，但不暴露 userId、内部
 * 存储对象键或未来向量实现细节。
 *
 * <p>English: Safe output when an owner views text chunks. It contains reviewable body
 * text and chunk statistics without exposing userId, storage object keys, or future
 * vector implementation details.
 */
public record KnowledgeChunkResponse(
        String id,
        String documentId,
        int chunkIndex,
        String content,
        String titlePath,
        int charCount,
        int tokenCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static KnowledgeChunkResponse from(KnowledgeChunk chunk) {
        return new KnowledgeChunkResponse(
                String.valueOf(chunk.getId()),
                String.valueOf(chunk.getDocumentId()),
                chunk.getChunkIndex(),
                chunk.getContent(),
                chunk.getTitlePath(),
                chunk.getCharCount(),
                chunk.getTokenCount(),
                chunk.getCreatedAt(),
                chunk.getUpdatedAt()
        );
    }
}
