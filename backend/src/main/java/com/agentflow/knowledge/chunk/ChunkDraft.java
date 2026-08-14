package com.agentflow.knowledge.chunk;

import java.util.Objects;

/**
 * 中文：尚未入库的确定性 chunk。它不含数据库 ID，便于先在内存中完整生成、再在一个数据库事务中
 * 全部落库。
 *
 * <p>English: A deterministic chunk before persistence. It has no database ID, so all
 * chunks can be built in memory and then stored together in one database transaction.
 */
public record ChunkDraft(
        int chunkIndex,
        String content,
        String titlePath,
        int charCount,
        int tokenCount
) {
    /** Matches the V4 {@code knowledge_chunk.title_path VARCHAR(512)} contract. */
    static final int MAX_TITLE_PATH_CODE_POINTS = 512;

    public ChunkDraft {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }
        content = Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("Chunk content must not be blank");
        }
        titlePath = titlePath == null || titlePath.isBlank() ? null : truncateTitlePath(titlePath.strip());
        if (charCount <= 0 || tokenCount <= 0) {
            throw new IllegalArgumentException("Chunk charCount and tokenCount must be positive");
        }
    }

    private static String truncateTitlePath(String titlePath) {
        int codePointCount = titlePath.codePointCount(0, titlePath.length());
        if (codePointCount <= MAX_TITLE_PATH_CODE_POINTS) {
            return titlePath;
        }
        int prefixEndOffset = titlePath.offsetByCodePoints(0, MAX_TITLE_PATH_CODE_POINTS - 1);
        return titlePath.substring(0, prefixEndOffset) + "…";
    }
}
