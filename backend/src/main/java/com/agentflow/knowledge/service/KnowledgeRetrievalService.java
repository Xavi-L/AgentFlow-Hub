package com.agentflow.knowledge.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.KnowledgeRetrievalResponse;
import com.agentflow.knowledge.dto.RetrieveTestRequest;
import com.agentflow.knowledge.dto.RetrievedChunkResponse;
import com.agentflow.knowledge.model.KnowledgeBase;
import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.repository.KnowledgeBaseMapper;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.vector.EmbeddingGateway;
import com.agentflow.knowledge.vector.EmbeddingRequest;
import com.agentflow.knowledge.vector.EmbeddingVector;
import com.agentflow.knowledge.vector.VectorSearchHit;
import com.agentflow.knowledge.vector.VectorSearchRequest;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.user.security.AuthenticatedUser;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 中文：V7 的最小语义检索编排。先由当前知识库的固定 embedding 契约生成 query vector，再由
 * VectorStoreGateway 以服务端固定的 user/knowledge-base scope 检索。向量库只返回定位元数据；
 * 本类必须从 PostgreSQL 回读与验证当前 chunk/vectorId，才返回可审阅正文。
 *
 * <p>English: V7's minimum semantic-retrieval orchestration. It embeds a query under
 * the current knowledge-base contract, searches through a server-fixed owner/knowledge
 * base scope, then re-reads and validates current chunks/vectorIds from PostgreSQL
 * before returning reviewable content. The vector store never becomes content authority.
 */
@Service
public class KnowledgeRetrievalService {
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    private static final int MAX_QUERY_LENGTH = 1_000;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStoreGateway vectorStoreGateway;

    public KnowledgeRetrievalService(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeChunkMapper knowledgeChunkMapper,
            EmbeddingGateway embeddingGateway,
            VectorStoreGateway vectorStoreGateway
    ) {
        this.knowledgeBaseMapper = Objects.requireNonNull(
                knowledgeBaseMapper,
                "knowledgeBaseMapper must not be null"
        );
        this.knowledgeChunkMapper = Objects.requireNonNull(
                knowledgeChunkMapper,
                "knowledgeChunkMapper must not be null"
        );
        this.embeddingGateway = Objects.requireNonNull(embeddingGateway, "embeddingGateway must not be null");
        this.vectorStoreGateway = Objects.requireNonNull(
                vectorStoreGateway,
                "vectorStoreGateway must not be null"
        );
    }

    public KnowledgeRetrievalResponse retrieveTest(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            RetrieveTestRequest request
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(request, "request must not be null");
        validatePositiveId(knowledgeBaseId, "knowledgeBaseId");
        String query = normalizeQuery(request.query());
        int topK = normalizeTopK(request.topK());

        KnowledgeBase knowledgeBase = requireActiveOwnedKnowledgeBase(currentUser, knowledgeBaseId);
        EmbeddingVector queryVector = embeddingGateway.embed(new EmbeddingRequest(
                query,
                knowledgeBase.getEmbeddingProvider(),
                knowledgeBase.getEmbeddingModel()
        ));
        List<VectorSearchHit> vectorHits = vectorStoreGateway.search(new VectorSearchRequest(
                queryVector,
                currentUser.id(),
                knowledgeBaseId,
                topK
        ));
        List<KnowledgeChunk> canonicalChunks = selectCanonicalChunks(currentUser, knowledgeBaseId, vectorHits);
        return new KnowledgeRetrievalResponse(query, topK, restoreVerifiedSimilarityOrder(vectorHits, canonicalChunks));
    }

    private List<KnowledgeChunk> selectCanonicalChunks(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            List<VectorSearchHit> vectorHits
    ) {
        if (vectorHits.isEmpty()) {
            return List.of();
        }
        List<Long> chunkIds = vectorHits.stream().map(VectorSearchHit::chunkId).distinct().toList();
        return knowledgeChunkMapper.selectRetrievableChunks(knowledgeBaseId, currentUser.id(), chunkIds);
    }

    private static List<RetrievedChunkResponse> restoreVerifiedSimilarityOrder(
            List<VectorSearchHit> vectorHits,
            List<KnowledgeChunk> canonicalChunks
    ) {
        Map<Long, KnowledgeChunk> chunksById = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : canonicalChunks) {
            if (chunk.getId() != null) {
                chunksById.put(chunk.getId(), chunk);
            }
        }

        List<RetrievedChunkResponse> results = new ArrayList<>();
        for (VectorSearchHit hit : vectorHits) {
            KnowledgeChunk chunk = chunksById.get(hit.chunkId());
            // A stale vector, a deleted/reprocessed chunk, or an unexpected payload must
            // simply not be returned. PostgreSQL's current vectorId is the final identity
            // check, in addition to the Qdrant payload filter and canonical SQL scope.
            if (chunk != null && hit.vectorId().equals(chunk.getVectorId())) {
                results.add(RetrievedChunkResponse.from(results.size() + 1, hit.score(), chunk));
            }
        }
        return List.copyOf(results);
    }

    private KnowledgeBase requireActiveOwnedKnowledgeBase(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId
    ) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(
                Wrappers.<KnowledgeBase>lambdaQuery()
                        .eq(KnowledgeBase::getId, knowledgeBaseId)
                        .eq(KnowledgeBase::getUserId, currentUser.id())
                        .isNull(KnowledgeBase::getDeletedAt)
        );
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Knowledge base not found");
        }
        if (!ACTIVE_STATUS.equals(knowledgeBase.getStatus())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_ACTIVE);
        }
        return knowledgeBase;
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "query must not be blank");
        }
        String normalized = query.trim();
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    "query must not exceed " + MAX_QUERY_LENGTH + " characters"
            );
        }
        return normalized;
    }

    private static int normalizeTopK(Integer topK) {
        int normalized = topK == null ? DEFAULT_TOP_K : topK;
        if (normalized < 1 || normalized > MAX_TOP_K) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    "topK must be between 1 and " + MAX_TOP_K
            );
        }
        return normalized;
    }

    private static void validatePositiveId(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    fieldName + " must be a positive integer"
            );
        }
    }
}
