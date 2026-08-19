package com.agentflow.knowledge.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.chunk.TokenEstimator;
import com.agentflow.knowledge.dto.KnowledgeContextResponse;
import com.agentflow.knowledge.dto.KnowledgeContextSourceResponse;
import com.agentflow.knowledge.dto.KnowledgeRetrievalResponse;
import com.agentflow.knowledge.dto.RetrieveContextTestRequest;
import com.agentflow.knowledge.dto.RetrieveTestRequest;
import com.agentflow.knowledge.dto.RetrievedChunkResponse;
import com.agentflow.user.security.AuthenticatedUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 中文：V8 的确定性 RAG context 装配层。它只消费 V7 已完成 owner scope、PostgreSQL canonical
 * 回读和 vectorId 校验后的结果；本类不 import embedding、Qdrant 或任何 LLM/Prompt 组件。
 *
 * <p>English: V8's deterministic RAG-context assembler. It consumes only V7 results
 * that already completed owner scoping, canonical PostgreSQL re-read, and vectorId
 * verification. This class imports no embedding, Qdrant, LLM, or prompt component.
 */
@Service
public class KnowledgeContextService {
    static final int MAX_CONTEXT_TOKENS = 8_000;

    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final TokenEstimator tokenEstimator;

    public KnowledgeContextService(
            KnowledgeRetrievalService knowledgeRetrievalService,
            TokenEstimator tokenEstimator
    ) {
        this.knowledgeRetrievalService = Objects.requireNonNull(
                knowledgeRetrievalService,
                "knowledgeRetrievalService must not be null"
        );
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator must not be null");
    }

    /**
     * Builds one stable context block per chunk that fits. A chunk that would exceed the
     * remaining budget is counted as skipped and never truncated; later, lower-scored
     * chunks are still considered in their original V7 similarity order.
     */
    public KnowledgeContextResponse retrieveContextTest(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            RetrieveContextTestRequest request
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(request, "request must not be null");
        int maxContextTokens = normalizeMaxContextTokens(request.maxContextTokens());

        // Reuse V7 rather than reproducing query embedding, Qdrant filters, canonical
        // PostgreSQL retrieval, or stale-vector checks in a second code path.
        KnowledgeRetrievalResponse retrieval = knowledgeRetrievalService.retrieveTest(
                currentUser,
                knowledgeBaseId,
                new RetrieveTestRequest(request.query(), request.topK())
        );

        List<String> contextBlocks = new ArrayList<>();
        List<KnowledgeContextSourceResponse> sources = new ArrayList<>();
        int usedContextTokens = 0;
        int skippedChunkCount = 0;

        for (RetrievedChunkResponse chunk : retrieval.items()) {
            String citationId = "S" + (sources.size() + 1);
            KnowledgeContextSourceResponse source = KnowledgeContextSourceResponse.from(citationId, chunk);
            String contextBlock = formatContextBlock(source, chunk.content());
            int blockTokenCount = estimateContextBlockTokens(source, chunk);

            if (blockTokenCount > maxContextTokens - usedContextTokens) {
                skippedChunkCount++;
                continue;
            }

            contextBlocks.add(contextBlock);
            sources.add(source);
            usedContextTokens = Math.addExact(usedContextTokens, blockTokenCount);
        }

        return new KnowledgeContextResponse(
                retrieval.query(),
                retrieval.topK(),
                maxContextTokens,
                usedContextTokens,
                skippedChunkCount,
                String.join("\n\n", contextBlocks),
                sources
        );
    }

    /**
     * V4 persisted each chunk's content estimate at chunking time. Reuse it here so a
     * future replacement of the estimator cannot silently redefine existing chunk body
     * budgets. Only the deterministic V8 wrapper is estimated at assembly time.
     */
    private int estimateContextBlockTokens(
            KnowledgeContextSourceResponse source,
            RetrievedChunkResponse chunk
    ) {
        if (chunk.tokenCount() <= 0) {
            throw new IllegalStateException("Canonical retrieval returned a non-positive chunk tokenCount");
        }
        return Math.addExact(tokenEstimator.estimate(contextHeader(source)), chunk.tokenCount());
    }

    private static String formatContextBlock(KnowledgeContextSourceResponse source, String content) {
        return contextHeader(source) + Objects.requireNonNull(content, "chunk content must not be null");
    }

    private static String contextHeader(KnowledgeContextSourceResponse source) {
        return "[" + source.citationId() + "]\n"
                + "Source: " + source.fileName() + "\n"
                + "Title:" + (source.titlePath().isEmpty() ? "" : " " + source.titlePath()) + "\n"
                + "DocumentId: " + source.documentId() + "\n"
                + "ChunkId: " + source.chunkId() + "\n"
                + "Content:\n";
    }

    private static int normalizeMaxContextTokens(Integer maxContextTokens) {
        if (maxContextTokens == null || maxContextTokens < 1 || maxContextTokens > MAX_CONTEXT_TOKENS) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    "maxContextTokens must be between 1 and " + MAX_CONTEXT_TOKENS
            );
        }
        return maxContextTokens;
    }
}
