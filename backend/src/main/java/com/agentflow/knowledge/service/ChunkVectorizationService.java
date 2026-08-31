package com.agentflow.knowledge.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.ChunkVectorizationResponse;
import com.agentflow.knowledge.model.ChunkVectorizationStatus;
import com.agentflow.knowledge.model.KnowledgeBase;
import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.repository.KnowledgeBaseMapper;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.vector.ChunkVectorIdentity;
import com.agentflow.knowledge.vector.ChunkVectorIdentityFactory;
import com.agentflow.knowledge.vector.EmbeddingGateway;
import com.agentflow.knowledge.vector.EmbeddingRequest;
import com.agentflow.knowledge.vector.EmbeddingVector;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.knowledge.vector.VectorStoreRecord;
import com.agentflow.knowledge.vector.VectorStoreOutcomeUnknownException;
import com.agentflow.user.security.AuthenticatedUser;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 中文：已完成文本块的 V5 同步向量化编排。它先在短事务中认领一个 PENDING chunk，随后在事务外调用
 * provider-neutral gateway 和幂等 upsert，最后在另一短事务中写回状态。它不搜索、不 rerank、
 * 不创建异步任务，也不修改 document parse 状态。
 *
 * <p>English: V5 synchronous vectorization orchestration for completed text chunks.
 * It claims one PENDING chunk in a short transaction, calls provider-neutral gateways
 * and an idempotent upsert outside a transaction, then writes state in another short
 * transaction. It does not search, rerank, create async tasks, or change document
 * parse status.
 */
@Service
public class ChunkVectorizationService {
    private static final Logger log = LoggerFactory.getLogger(ChunkVectorizationService.class);
    private static final String EMBEDDING_FAILURE = "Embedding generation failed";
    private static final String VECTOR_STORE_FAILURE = "Vector store upsert failed";
    private static final String STATUS_UPDATE_FAILURE = "Vectorization status update failed";

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStoreGateway vectorStoreGateway;
    private final ChunkVectorizationTransactionService transactionService;

    public ChunkVectorizationService(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeChunkMapper knowledgeChunkMapper,
            EmbeddingGateway embeddingGateway,
            VectorStoreGateway vectorStoreGateway,
            ChunkVectorizationTransactionService transactionService
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
        this.transactionService = Objects.requireNonNull(
                transactionService,
                "transactionService must not be null"
        );
    }

    /**
     * Vectorizes only chunks that are still PENDING. The candidate query filters completed,
     * non-deleted sources, and V24's locked claim gate revalidates that same parent immediately
     * before a chunk becomes PROCESSING; a stale candidate therefore never reaches external I/O.
     */
    public ChunkVectorizationResponse vectorizePending(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        validatePositiveId(knowledgeBaseId, "knowledgeBaseId");
        KnowledgeBase knowledgeBase = requireOwnedKnowledgeBase(currentUser, knowledgeBaseId);
        List<KnowledgeChunk> candidates = knowledgeChunkMapper.selectVectorizationCandidates(
                knowledgeBaseId,
                currentUser.id()
        );

        int claimed = 0;
        int completed = 0;
        int failed = 0;
        int skipped = 0;
        for (KnowledgeChunk chunk : candidates) {
            if (!ChunkVectorizationStatus.PENDING.name().equals(chunk.getVectorizationStatus())) {
                skipped++;
                continue;
            }

            ChunkVectorIdentity identity = ChunkVectorIdentityFactory.create(chunk);
            if (!transactionService.claimPendingChunk(chunk, identity.contentHash())) {
                skipped++;
                continue;
            }
            claimed++;

            EmbeddingVector embedding;
            try {
                embedding = embeddingGateway.embed(new EmbeddingRequest(
                        chunk.getContent(),
                        knowledgeBase.getEmbeddingProvider(),
                        knowledgeBase.getEmbeddingModel()
                ));
            } catch (RuntimeException embeddingFailure) {
                log.warn("Embedding failed for chunkId={}", chunk.getId(), embeddingFailure);
                transactionService.markFailed(chunk, EMBEDDING_FAILURE);
                failed++;
                continue;
            }

            try {
                vectorStoreGateway.upsert(new VectorStoreRecord(
                        identity.vectorId(),
                        embedding,
                        payloadFor(chunk, knowledgeBase, identity)
                ));
            } catch (VectorStoreOutcomeUnknownException outcomeUnknown) {
                log.warn("Vector-store upsert outcome unknown for chunkId={}", chunk.getId(), outcomeUnknown);
                transactionService.markOutcomeUnknown(chunk, VECTOR_STORE_FAILURE);
                failed++;
                continue;
            } catch (RuntimeException vectorStoreFailure) {
                log.warn("Vector-store upsert failed for chunkId={}", chunk.getId(), vectorStoreFailure);
                transactionService.markFailed(chunk, VECTOR_STORE_FAILURE);
                failed++;
                continue;
            }

            try {
                transactionService.markCompleted(chunk, identity.vectorId());
                completed++;
            } catch (RuntimeException statusUpdateFailure) {
                // The stable vectorId means a later, explicitly designed retry can safely
                // upsert this same point after a database-side completion failure.
                log.warn("Could not mark vectorization complete for chunkId={}", chunk.getId(), statusUpdateFailure);
                transactionService.markOutcomeUnknown(chunk, STATUS_UPDATE_FAILURE);
                failed++;
            }
        }
        return new ChunkVectorizationResponse(candidates.size(), claimed, completed, failed, skipped);
    }

    private static Map<String, Object> payloadFor(
            KnowledgeChunk chunk,
            KnowledgeBase knowledgeBase,
            ChunkVectorIdentity identity
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunkId", requirePositive(chunk.getId(), "chunkId"));
        payload.put("documentId", requirePositive(chunk.getDocumentId(), "documentId"));
        payload.put("knowledgeBaseId", requirePositive(chunk.getKnowledgeBaseId(), "knowledgeBaseId"));
        payload.put("userId", requirePositive(chunk.getUserId(), "userId"));
        payload.put("chunkIndex", Objects.requireNonNull(chunk.getChunkIndex(), "chunkIndex must not be null"));
        payload.put("vectorGeneration", requireGeneration(chunk.getVectorGeneration()));
        payload.put("contentHash", identity.contentHash());
        payload.put("embeddingProvider", requireNonBlank(knowledgeBase.getEmbeddingProvider(), "embeddingProvider"));
        payload.put("embeddingModel", requireNonBlank(knowledgeBase.getEmbeddingModel(), "embeddingModel"));
        if (chunk.getTitlePath() != null) {
            payload.put("titlePath", chunk.getTitlePath());
        }
        return payload;
    }

    private KnowledgeBase requireOwnedKnowledgeBase(
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
            // Missing, unowned, and soft-deleted knowledge bases stay indistinguishable
            // to prevent authenticated owner enumeration.
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Knowledge base not found");
        }
        return knowledgeBase;
    }

    private static void validatePositiveId(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    fieldName + " must be a positive integer"
            );
        }
    }

    private static Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be a positive integer");
        }
        return value;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static Long requireGeneration(Long value) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException("vectorGeneration must not be negative");
        }
        return value;
    }
}
