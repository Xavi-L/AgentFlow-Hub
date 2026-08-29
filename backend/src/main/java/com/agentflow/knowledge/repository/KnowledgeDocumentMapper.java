package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeDocument;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 中文：原始文档元数据的数据访问边界。除 BaseMapper 的插入和分页查询外，V21 的单文档详情
 * 在同一条 JOIN 查询中收口文档、当前 owner 与父知识库的可见性，不能先按文档 ID 做全局读取。
 *
 * <p>English: Data-access boundary for source-document metadata. In addition to BaseMapper
 * inserts and pagination, V21 resolves one document's visibility in a single JOIN query over
 * the document, current owner, and parent knowledge base; it must not globally pre-read by
 * document ID.
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /**
     * 中文：只返回当前 owner 可见、且其父知识库也尚未软删除的一条文档。关联中的 user_id
     * 相等条件与 WHERE 中的 current owner 条件共同避免跨 owner 文档或不一致父子行被读取。
     * 不加 status 条件，因此 owner 可读取 ACTIVE 与 DISABLED 知识库下的历史文档。
     *
     * <p>English: Returns one document only when it is visible to the current owner and its
     * parent knowledge base is not soft-deleted. The matching user IDs in the join and the
     * current-owner predicate in the WHERE clause prevent cross-owner or inconsistent parent-child
     * reads. There is intentionally no status predicate, so historical documents remain
     * readable in both ACTIVE and DISABLED knowledge bases.
     */
    @Select("""
            SELECT kd.*
            FROM knowledge_document kd
            INNER JOIN knowledge_base kb
                ON kb.id = kd.knowledge_base_id
                AND kb.user_id = kd.user_id
            WHERE kd.id = #{documentId}
              AND kd.user_id = #{userId}
              AND kd.deleted_at IS NULL
              AND kb.deleted_at IS NULL
            """)
    KnowledgeDocument selectVisibleOwnedById(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId
    );

    /**
     * 中文：仅把当前 owner 可见、父知识库也未软删除的 FAILED 文档重新排入 PENDING。PostgreSQL
     * 的 {@code UPDATE ... RETURNING} 在一条写入中同时检查文档、父知识库、owner 和旧状态，避免
     * 先读取后写入时把刚变为不可见的资源重新排队。这里使用 {@link Select} 是为了映射 RETURNING
     * 返回的转换后行，而不是把实体暴露给 HTTP 层。
     *
     * <p>English: Requeues only a FAILED document visible to the current owner whose parent
     * knowledge base is also not soft-deleted. PostgreSQL {@code UPDATE ... RETURNING} checks
     * document, parent, owner, and prior status in one mutation. {@link Select} maps the
     * transitioned row returned by that statement; the entity remains an internal value.
     */
    @Select("""
            UPDATE knowledge_document kd
            SET parse_status = 'PENDING',
                parse_error = NULL,
                updated_at = #{updatedAt}
            FROM knowledge_base kb
            WHERE kd.id = #{documentId}
              AND kd.user_id = #{userId}
              AND kd.deleted_at IS NULL
              AND kd.parse_status = 'FAILED'
              AND kb.id = kd.knowledge_base_id
              AND kb.user_id = kd.user_id
              AND kb.deleted_at IS NULL
            RETURNING kd.*
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    KnowledgeDocument reprocessFailedVisibleOwned(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId,
            @Param("updatedAt") OffsetDateTime updatedAt
    );
}
