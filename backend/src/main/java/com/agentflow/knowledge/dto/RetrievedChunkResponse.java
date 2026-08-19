package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeChunk;

/** Canonical PostgreSQL chunk content paired with an uncalibrated vector similarity score. */
public record RetrievedChunkResponse(
        int rank,
        double score,
        String chunkId,
        String documentId,
        int chunkIndex,
        String titlePath,
        String content
) {
    public static RetrievedChunkResponse from(int rank, double score, KnowledgeChunk chunk) {
        return new RetrievedChunkResponse(
                rank,
                score,
                String.valueOf(chunk.getId()),
                String.valueOf(chunk.getDocumentId()),
                chunk.getChunkIndex(),
                chunk.getTitlePath(),
                chunk.getContent()
        );
    }
}
