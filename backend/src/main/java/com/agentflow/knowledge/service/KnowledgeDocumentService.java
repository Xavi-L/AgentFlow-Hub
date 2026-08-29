package com.agentflow.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.model.DocumentFileType;
import com.agentflow.knowledge.model.KnowledgeBase;
import com.agentflow.knowledge.model.KnowledgeDocument;
import com.agentflow.knowledge.repository.KnowledgeBaseMapper;
import com.agentflow.knowledge.repository.KnowledgeDocumentMapper;
import com.agentflow.knowledge.storage.DocumentStorage;
import com.agentflow.knowledge.storage.DocumentUploadLimitProperties;
import com.agentflow.knowledge.storage.StoredDocument;
import com.agentflow.user.security.AuthenticatedUser;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * 中文：文档接入的业务编排层。它先确认路径中的知识库归当前 JWT 用户所有，再校验文件、写入
 * 原始文件、创建 PENDING 元数据。解析、清洗、chunk、embedding 与检索明确不属于本类。
 *
 * <p>English: Business orchestration for document ingestion. It first confirms that
 * the path knowledge base belongs to the current JWT user, then validates and stores
 * the source file and creates PENDING metadata. Parsing, cleaning, chunks, embeddings,
 * and retrieval are deliberately outside this class.
 */
@Service
public class KnowledgeDocumentService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);

    private static final String ACTIVE_KNOWLEDGE_BASE_STATUS = "ACTIVE";
    private static final String PENDING_PARSE_STATUS = "PENDING";
    private static final int MAX_FILE_NAME_LENGTH = 255;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final DocumentStorage documentStorage;
    private final DocumentUploadLimitProperties documentUploadLimitProperties;

    public KnowledgeDocumentService(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeDocumentMapper knowledgeDocumentMapper,
            DocumentStorage documentStorage,
            DocumentUploadLimitProperties documentUploadLimitProperties
    ) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.documentStorage = documentStorage;
        this.documentUploadLimitProperties = Objects.requireNonNull(
                documentUploadLimitProperties,
                "documentUploadLimitProperties must not be null"
        );
    }

    /**
     * 中文：上传一个当前用户拥有的 ACTIVE 知识库文档。数据库事务不能回滚本地文件系统，所以若
     * 元数据写入失败，本方法会尽力删除刚落盘的文件，避免留下可访问但没有数据库记录的孤儿文件。
     *
     * <p>English: Uploads a document to an ACTIVE knowledge base owned by the current
     * user. A database transaction cannot roll back a local filesystem, so if metadata
     * insertion fails this method attempts to delete the just-stored file and prevent
     * an orphan with no database record.
     */
    @Transactional
    public KnowledgeDocumentResponse upload(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            MultipartFile file
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        validateKnowledgeBaseId(knowledgeBaseId);

        KnowledgeBase knowledgeBase = requireOwnedKnowledgeBase(currentUser, knowledgeBaseId);
        if (!ACTIVE_KNOWLEDGE_BASE_STATUS.equals(knowledgeBase.getStatus())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_ACTIVE);
        }

        ValidatedUpload upload = validateFile(file);
        StoredDocument storedDocument = storeFile(currentUser.id(), knowledgeBase.getId(), upload, file);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setUserId(currentUser.id());
        document.setKnowledgeBaseId(knowledgeBase.getId());
        document.setFileName(upload.fileName());
        document.setFileType(upload.fileType().name());
        document.setMimeType(upload.fileType().getMimeType());
        document.setFileSize(upload.fileSize());
        document.setStorageBucket(storedDocument.storageBucket());
        document.setStorageObjectKey(storedDocument.storageObjectKey());
        document.setParseStatus(PENDING_PARSE_STATUS);
        OffsetDateTime now = OffsetDateTime.now();
        document.setCreatedAt(now);
        document.setUpdatedAt(now);

        try {
            int affectedRows = knowledgeDocumentMapper.insert(document);
            if (affectedRows != 1) {
                throw new IllegalStateException("Expected exactly one inserted knowledge_document row");
            }
        } catch (RuntimeException ex) {
            deleteAfterFailedMetadataInsert(storedDocument);
            throw ex;
        }

        registerRollbackCleanup(storedDocument);

        return KnowledgeDocumentResponse.from(document);
    }

    /**
     * 中文：读取当前用户可见的一条文档元数据。Mapper 在同一条 JOIN 查询中限定文档 ID、owner、
     * 文档未软删除和父知识库未软删除；没有匹配行时，不存在、跨 owner、文档已删除和父知识库
     * 已删除都统一为 Document not found。这里不读取原始文件，也不触发解析、状态转换或清理。
     *
     * <p>English: Reads one document metadata record visible to the current user. The mapper
     * keeps the document ID, owner, non-deleted document, and non-deleted parent knowledge base
     * in one JOIN query. A miss uniformly maps absent, cross-owner, deleted-document, and
     * deleted-parent cases to Document not found. This method neither opens the source file nor
     * triggers parsing, state changes, or cleanup.
     */
    @Transactional(readOnly = true)
    public KnowledgeDocumentResponse getOwnedById(
            AuthenticatedUser currentUser,
            Long documentId
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");

        KnowledgeDocument document = knowledgeDocumentMapper.selectVisibleOwnedById(
                documentId,
                currentUser.id()
        );
        if (document == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Document not found");
        }
        return KnowledgeDocumentResponse.from(document);
    }

    /**
     * 中文：分页查看当前用户拥有的知识库中的非软删除文档。禁用知识库仍可查看历史资料，但不能
     * 再接受新文件；这样与现有知识库列表“owner 可管理 DISABLED 项”的语义保持一致。
     *
     * <p>English: Pages through non-deleted documents in the current user's knowledge
     * base. A disabled knowledge base still exposes its historical documents to its
     * owner but accepts no new uploads, consistent with the existing owner-management
     * semantics for DISABLED knowledge bases.
     */
    @Transactional(readOnly = true)
    public PageResult<KnowledgeDocumentResponse> listOwnedByKnowledgeBase(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            PageRequest pageRequest
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");
        validateKnowledgeBaseId(knowledgeBaseId);
        requireOwnedKnowledgeBase(currentUser, knowledgeBaseId);

        Page<KnowledgeDocument> databasePage = new Page<>(
                pageRequest.getPage(),
                pageRequest.getPageSize()
        );
        knowledgeDocumentMapper.selectPage(
                databasePage,
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeDocument::getUserId, currentUser.id())
                        .isNull(KnowledgeDocument::getDeletedAt)
                        .orderByDesc(KnowledgeDocument::getCreatedAt, KnowledgeDocument::getId)
        );

        return PageResult.of(
                databasePage.getRecords().stream().map(KnowledgeDocumentResponse::from).toList(),
                pageRequest.getPage(),
                pageRequest.getPageSize(),
                databasePage.getTotal()
        );
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
            // 中文：不存在、非 owner、软删除统一使用同一个 404，防止资源枚举。
            // English: Missing, non-owner, and soft-deleted resources share one 404 to
            // prevent resource enumeration.
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Knowledge base not found");
        }
        return knowledgeBase;
    }

    private ValidatedUpload validateFile(MultipartFile file) {
        if (file == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_REQUIRED);
        }
        if (file.isEmpty() || file.getSize() <= 0) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_EMPTY);
        }
        if (file.getSize() > documentUploadLimitProperties.getMaxFileSize().toBytes()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_TOO_LARGE);
        }

        String fileName = normalizeFileName(file.getOriginalFilename());
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0 || extensionStart == fileName.length() - 1) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_NAME_INVALID);
        }

        String baseName = fileName.substring(0, extensionStart).trim();
        if (baseName.isEmpty()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_NAME_INVALID);
        }
        String extension = fileName.substring(extensionStart + 1).toLowerCase(Locale.ROOT);
        DocumentFileType fileType = DocumentFileType.fromExtension(extension)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.KNOWLEDGE_DOCUMENT_FILE_TYPE_UNSUPPORTED
                ));
        return new ValidatedUpload(fileName, fileType, file.getSize());
    }

    private static String normalizeFileName(String originalFilename) {
        if (originalFilename == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_NAME_INVALID);
        }
        // Browser clients normally send a basename, but normalize both path separators
        // defensively. The normalized display name is never used as a physical path.
        String normalizedSeparators = originalFilename.replace('\\', '/');
        String fileName = normalizedSeparators.substring(
                normalizedSeparators.lastIndexOf('/') + 1
        ).trim();
        if (fileName.isEmpty()
                || fileName.length() > MAX_FILE_NAME_LENGTH
                || fileName.indexOf((char) 0) >= 0
                || fileName.chars().anyMatch(character -> Character.isISOControl((char) character))) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_NAME_INVALID);
        }
        return fileName;
    }

    private StoredDocument storeFile(
            long userId,
            long knowledgeBaseId,
            ValidatedUpload upload,
            MultipartFile file
    ) {
        try (InputStream content = file.getInputStream()) {
            return documentStorage.store(userId, knowledgeBaseId, upload.fileType(), content);
        } catch (IOException ex) {
            // Do not place the filesystem path or original filename in the client response.
            throw new IllegalStateException("Failed to store uploaded document", ex);
        }
    }

    private void deleteAfterFailedMetadataInsert(StoredDocument storedDocument) {
        try {
            documentStorage.delete(storedDocument);
        } catch (Exception cleanupFailure) {
            // The original insert failure remains the request outcome; retain cleanup
            // evidence in server logs without exposing storage details to the client.
            log.error("Failed to remove orphaned document after metadata insert failure", cleanupFailure);
        }
    }

    /**
     * 中文：本地文件系统不属于 JDBC 事务。insert 成功但外层事务在本方法返回后回滚时，Spring 会
     * 在 afterCompletion 回调本清理逻辑；直接 new Service 的单元测试没有事务同步时则安全跳过。
     *
     * <p>English: A local filesystem does not participate in a JDBC transaction. If an
     * insert succeeds but an enclosing transaction rolls back after this method returns,
     * Spring invokes this cleanup from afterCompletion. Plain unit tests that construct
     * the Service directly have no transaction synchronization and safely skip it.
     */
    private void registerRollbackCleanup(StoredDocument storedDocument) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteAfterFailedMetadataInsert(storedDocument);
                }
            }
        });
    }

    private static void validateKnowledgeBaseId(Long knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId <= 0) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    "knowledgeBaseId must be a positive integer"
            );
        }
    }

    private record ValidatedUpload(
            String fileName,
            DocumentFileType fileType,
            long fileSize
    ) {
    }
}
