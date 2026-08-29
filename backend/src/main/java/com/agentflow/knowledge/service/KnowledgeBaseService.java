package com.agentflow.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.CreateKnowledgeBaseRequest;
import com.agentflow.knowledge.dto.KnowledgeBaseResponse;
import com.agentflow.knowledge.dto.UpdateKnowledgeBaseMetadataRequest;
import com.agentflow.knowledge.model.KnowledgeBase;
import com.agentflow.knowledge.repository.KnowledgeBaseMapper;
import com.agentflow.user.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：知识库元数据的业务编排层。Controller 从 SecurityContext 取得 currentUser 后交给本类，
 * 因而客户端永远不能通过 JSON 指定或覆盖资源所有者。
 *
 * <p>English: Business orchestration for knowledge-base metadata. The controller passes
 * in currentUser from SecurityContext, so JSON can never choose or override an owner.
 */
@Service
public class KnowledgeBaseService {
    private static final String ACTIVE_STATUS = "ACTIVE";
    // V6's single configured collection is text-embedding-v4 at 1024 dimensions.
    // A later model migration must create a separate collection and re-vectorize rather
    // than mixing incompatible vectors into this one.
    private static final String DEFAULT_EMBEDDING_PROVIDER = "dashscope";
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v4";
    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_CHUNK_OVERLAP = 120;
    private static final int MIN_CHUNK_SIZE = 80;
    private static final int MAX_CHUNK_SIZE = 1000;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    /**
     * 中文：将经认证用户固定为 owner，填充安全默认值，再插入一行 ACTIVE 知识库。
     * English: Fixes the authenticated user as owner, fills safe defaults, then inserts
     * one ACTIVE knowledge-base row.
     */
    @Transactional
    public KnowledgeBaseResponse create(
            AuthenticatedUser currentUser,
            CreateKnowledgeBaseRequest request
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(request, "request must not be null");

        int chunkSize = request.chunkSize() == null ? DEFAULT_CHUNK_SIZE : request.chunkSize();
        int chunkOverlap = request.chunkOverlap() == null ? DEFAULT_CHUNK_OVERLAP : request.chunkOverlap();
        validateChunkSettings(chunkSize, chunkOverlap);

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setUserId(currentUser.id());
        knowledgeBase.setName(request.name().trim());
        knowledgeBase.setDescription(normalizeOptional(request.description()));
        knowledgeBase.setEmbeddingProvider(
                normalizeOrDefault(request.embeddingProvider(), DEFAULT_EMBEDDING_PROVIDER)
        );
        knowledgeBase.setEmbeddingModel(normalizeOrDefault(request.embeddingModel(), DEFAULT_EMBEDDING_MODEL));
        knowledgeBase.setChunkSize(chunkSize);
        knowledgeBase.setChunkOverlap(chunkOverlap);
        knowledgeBase.setStatus(ACTIVE_STATUS);
        // 中文：数据库默认值也会保护直连 SQL，但 MyBatis 插入后不会自动回读默认时间。
        // 在应用层同时写入，创建响应才能立即带回可观察的创建/更新时间。
        // English: Database defaults also protect direct SQL, but MyBatis does not read
        // those defaults back automatically after insert. Set them here so the create
        // response immediately contains observable creation/update timestamps.
        OffsetDateTime now = OffsetDateTime.now();
        knowledgeBase.setCreatedAt(now);
        knowledgeBase.setUpdatedAt(now);

        int affectedRows = knowledgeBaseMapper.insert(knowledgeBase);
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one inserted knowledge_base row");
        }
        return KnowledgeBaseResponse.from(knowledgeBase);
    }

    /**
     * 中文：只读取当前用户自己的、尚未软删除的知识库。这里不能改成全表列表，否则已登录用户会
     * 枚举其他人的资源；DISABLED 仍会显示，便于其所有者以后重新启用或管理。
     *
     * <p>English: Reads only the current user's non-deleted knowledge bases. This must
     * not become a whole-table listing, which would let authenticated users enumerate
     * others' resources; DISABLED items remain visible so their owner can later manage
     * or re-enable them.
     */
    @Transactional(readOnly = true)
    public PageResult<KnowledgeBaseResponse> listOwnedBy(
            AuthenticatedUser currentUser,
            PageRequest pageRequest
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");

        Page<KnowledgeBase> databasePage = new Page<>(
                pageRequest.getPage(),
                pageRequest.getPageSize()
        );
        knowledgeBaseMapper.selectPage(
                databasePage,
                Wrappers.<KnowledgeBase>lambdaQuery()
                        .eq(KnowledgeBase::getUserId, currentUser.id())
                        .isNull(KnowledgeBase::getDeletedAt)
                        .orderByDesc(KnowledgeBase::getCreatedAt, KnowledgeBase::getId)
        );

        return PageResult.of(
                databasePage.getRecords().stream().map(KnowledgeBaseResponse::from).toList(),
                pageRequest.getPage(),
                pageRequest.getPageSize(),
                databasePage.getTotal()
        );
    }

    /**
     * 中文：在一条查询中同时限定知识库 ID、当前 JWT owner 与未软删除状态。没有匹配结果时，
     * 不存在、其他 owner 的资源和已软删除资源都统一为 404，不能通过详情接口枚举资源。
     * DISABLED 不是软删除，仍按既有 {@link KnowledgeBaseResponse} 返回其元数据。
     *
     * <p>English: Applies the knowledge-base ID, current JWT owner, and non-deleted state
     * in one query. No match maps missing, foreign-owner, and soft-deleted resources to the
     * same 404, so this detail endpoint cannot enumerate resources. DISABLED is not a soft
     * deletion and its existing metadata remains readable through {@link KnowledgeBaseResponse}.
     */
    @Transactional(readOnly = true)
    public KnowledgeBaseResponse getOwnedById(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");

        return KnowledgeBaseResponse.from(findOwnedNonDeleted(currentUser, knowledgeBaseId));
    }

    /**
     * 中文：更新前先从同一 owner/未删除 scope 读取当前元数据，才能将缺失字段保持不变，也能在
     * 同值 PATCH 时不刷新 updatedAt。实际 UPDATE 仍再次包含相同 scope，防止读取后资源被软删除
     * 或归属发生变化时越界写入。
     *
     * <p>English: Reads current metadata from the same owner/non-deleted scope before
     * updating so absent fields remain unchanged and an equal PATCH leaves updatedAt intact.
     * The actual UPDATE repeats that scope, preventing an out-of-scope write if the resource
     * changes after the read.
     */
    @Transactional
    public KnowledgeBaseResponse updateMetadata(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            UpdateKnowledgeBaseMetadataRequest request
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        validateMetadataUpdateRequest(request);

        KnowledgeBase knowledgeBase = findOwnedNonDeleted(currentUser, knowledgeBaseId);
        String updatedName = request.namePresent()
                ? normalizeRequiredName(request.name())
                : knowledgeBase.getName();
        String updatedDescription = request.descriptionPresent()
                ? normalizeDescription(request.description())
                : knowledgeBase.getDescription();

        if (Objects.equals(knowledgeBase.getName(), updatedName)
                && Objects.equals(knowledgeBase.getDescription(), updatedDescription)) {
            return KnowledgeBaseResponse.from(knowledgeBase);
        }

        OffsetDateTime updatedAt = OffsetDateTime.now();
        int affectedRows = knowledgeBaseMapper.updateMetadataOwned(
                knowledgeBaseId,
                currentUser.id(),
                updatedName,
                updatedDescription,
                updatedAt
        );
        if (affectedRows == 0) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Knowledge base not found");
        }
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one updated knowledge_base row");
        }

        knowledgeBase.setName(updatedName);
        knowledgeBase.setDescription(updatedDescription);
        knowledgeBase.setUpdatedAt(updatedAt);
        return KnowledgeBaseResponse.from(knowledgeBase);
    }

    /**
     * 中文：只软删除当前 owner 尚未删除的知识库；SQL 不按 status 限制，因此 ACTIVE 和 DISABLED
     * 都允许删除。缺失、其他 owner 或已经删除的行都会使 scoped UPDATE 影响 0 行，并统一为 404。
     * 这里不读取知识库、不物理删除，也不触碰文档、chunk、向量或外部存储。
     *
     * <p>English: Soft-deletes only a current owner's non-deleted knowledge base. SQL does
     * not restrict status, so both ACTIVE and DISABLED rows can be deleted. Missing,
     * foreign-owner, and already-deleted rows all produce a zero-row scoped UPDATE and the
     * same 404. This method does not read the resource, physically delete it, or touch
     * documents, chunks, vectors, or external storage.
     */
    @Transactional
    public void softDeleteOwned(AuthenticatedUser currentUser, Long knowledgeBaseId) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");

        int affectedRows = knowledgeBaseMapper.softDeleteOwned(
                knowledgeBaseId,
                currentUser.id(),
                OffsetDateTime.now()
        );
        if (affectedRows == 0) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Knowledge base not found");
        }
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one soft-deleted knowledge_base row");
        }
    }

    private static void validateChunkSettings(int chunkSize, int chunkOverlap) {
        if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    "chunkSize must be between 80 and 1000"
            );
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    "chunkOverlap must be at least 0 and smaller than chunkSize"
            );
        }
    }

    private KnowledgeBase findOwnedNonDeleted(
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
        return knowledgeBase;
    }

    private static void validateMetadataUpdateRequest(UpdateKnowledgeBaseMetadataRequest request) {
        if (!request.hasAnyMetadataField()) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    "At least one of name or description must be provided"
            );
        }
        if (request.namePresent()) {
            normalizeRequiredName(request.name());
        }
        if (request.descriptionPresent()) {
            normalizeDescription(request.description());
        }
    }

    private static String normalizeRequiredName(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "name must not be blank");
        }
        if (normalized.length() > 128) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    "name must not exceed 128 characters"
            );
        }
        return normalized;
    }

    private static String normalizeDescription(String value) {
        String normalized = normalizeOptional(value);
        if (normalized != null && normalized.length() > 4_000) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    "description must not exceed 4000 characters"
            );
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeOrDefault(String value, String defaultValue) {
        String normalized = normalizeOptional(value);
        return normalized == null ? defaultValue : normalized;
    }
}
