package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeChunk;

/**
 * Canonical PostgreSQL chunk content paired with an uncalibrated vector similarity score.
 * V8 also consumes the source file name and the stable V4 estimated token count from this
 * already verified retrieval result; neither value comes from Qdrant payload.
 */
public record RetrievedChunkResponse(
        int rank,
        double score,
        String chunkId,
        String documentId,
        String fileName,
        int chunkIndex,
        String titlePath,
        int tokenCount,
        String content
) {
    public static RetrievedChunkResponse from(int rank, double score, KnowledgeChunk chunk) {
        return new RetrievedChunkResponse(
                rank,
                score,
                String.valueOf(chunk.getId()),
                String.valueOf(chunk.getDocumentId()),
                chunk.getDocumentFileName(),
                chunk.getChunkIndex(),
                chunk.getTitlePath(),
                chunk.getTokenCount(),
                chunk.getContent()
        );
    }
}
