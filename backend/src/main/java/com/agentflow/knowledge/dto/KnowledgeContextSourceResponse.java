package com.agentflow.knowledge.dto;

import java.util.Objects;

/**
 * 中文：一个实际进入 V8 context 的稳定来源标记。{@code citationId} 与 context 中的 {@code [S#]}
 * 一一对应；它不是模型生成的文本，因此 V9 可将它原样交给 ChatGateway 并核对答案引用。
 *
 * <p>English: A stable source marker for one chunk actually included in V8 context.
 * {@code citationId} maps one-to-one to {@code [S#]} in context. It is not model-generated
 * text, so V9 can pass it to ChatGateway unchanged and reconcile answer citations later.
 */
public record KnowledgeContextSourceResponse(
        String citationId,
        String chunkId,
        String documentId,
        String fileName,
        String titlePath,
        double score
) {
    public KnowledgeContextSourceResponse {
        citationId = requireNonBlank(citationId, "citationId");
        chunkId = requireNonBlank(chunkId, "chunkId");
        documentId = requireNonBlank(documentId, "documentId");
        fileName = requireNonBlank(fileName, "fileName");
        // TXT chunks have no Markdown heading. Keep that fact observable as an empty
        // string instead of inventing a synthetic title or omitting the JSON field.
        titlePath = titlePath == null ? "" : titlePath;
    }

    public static KnowledgeContextSourceResponse from(String citationId, RetrievedChunkResponse chunk) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        return new KnowledgeContextSourceResponse(
                citationId,
                chunk.chunkId(),
                chunk.documentId(),
                chunk.fileName(),
                chunk.titlePath(),
                chunk.score()
        );
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
