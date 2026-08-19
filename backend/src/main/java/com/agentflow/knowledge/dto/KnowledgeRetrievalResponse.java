package com.agentflow.knowledge.dto;

import java.util.List;
import java.util.Objects;

/** Observable V7 retrieval result; it is not an answer-generation or reranking result. */
public record KnowledgeRetrievalResponse(
        String query,
        int topK,
        List<RetrievedChunkResponse> items
) {
    public KnowledgeRetrievalResponse {
        query = Objects.requireNonNull(query, "query must not be null");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
