package com.agentflow.knowledge.readiness;

import com.agentflow.knowledge.dto.DocumentVectorizationResponse;

/** Internal, owner-scoped aggregate. Configuration and strategy diagnostics stay server-side. */
public record DocumentReadinessRow(
        Long documentId,
        String knowledgeBaseStatus,
        String embeddingProvider,
        String embeddingModel,
        Integer chunkSize,
        Integer chunkOverlap,
        long pending,
        long processing,
        long completed,
        long failed,
        long strategyCount,
        String chunkStrategyVersion
) {
    public DocumentVectorizationResponse vectorization() {
        return new DocumentVectorizationResponse(pending, processing, completed, failed);
    }

    public RetrievalReadiness readiness(String parseStatus) {
        if (!"ACTIVE".equals(knowledgeBaseStatus)) {
            return RetrievalReadiness.NOT_READY;
        }
        if ("FAILED".equals(parseStatus)) {
            return RetrievalReadiness.FAILED;
        }
        if (!"COMPLETED".equals(parseStatus)) {
            return RetrievalReadiness.NOT_READY;
        }
        long total = pending + processing + completed + failed;
        if (KnowledgeReadConfiguration.embeddingProfileCode(embeddingProvider, embeddingModel) == null
                || KnowledgeReadConfiguration.chunkStrategyVersion(chunkSize, chunkOverlap) == null
                || (total > 0 && (strategyCount != 1
                    || !KnowledgeReadConfiguration.CHUNK_STRATEGY_VERSION.equals(chunkStrategyVersion)))) {
            return RetrievalReadiness.FAILED;
        }
        if (pending > 0 || processing > 0) {
            return RetrievalReadiness.INDEXING;
        }
        if (completed > 0 && failed > 0) {
            return RetrievalReadiness.DEGRADED;
        }
        return completed > 0 && completed == total ? RetrievalReadiness.READY : RetrievalReadiness.FAILED;
    }
}
