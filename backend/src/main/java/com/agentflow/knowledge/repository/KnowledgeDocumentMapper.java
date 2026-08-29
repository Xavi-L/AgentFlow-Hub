package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeDocument;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 中文：原始文档元数据的数据访问边界。除 BaseMapper 的插入和分页查询外，V21 的单文档详情
 * 与 V24 的删除/向量 claim 都在 JOIN 查询中收口文档、当前 owner 与父知识库的可见性；删除和
 * claim 不能先按文档 ID 做无范围的全局读取。
 *
 * <p>English: Data-access boundary for source-document metadata. In addition to BaseMapper
 * inserts and pagination, V21 and V24 resolve document visibility through one JOIN over the
 * document, current owner, and parent knowledge base; deletion/claim paths must not globally
 * pre-read by document ID.
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
     * 中文：V24 删除准入先锁住当前 owner、父知识库仍可见的 document 行。这里故意不限制
     * {@code kd.deleted_at}，因为同一 owner 的未完成删除任务必须从已经软删除的文档快照恢复；
     * 调用方会把“无未完成任务”的这种情况仍统一映射为 404。
     *
     * <p>English: V24 admission first locks a document row owned by the current user whose parent
     * is still visible. {@code kd.deleted_at} is intentionally not filtered: a same-owner,
     * unfinished task must resume from the already soft-deleted document snapshot, while callers
     * still map a missing unfinished task to the uniform 404.
     */
    @Select("""
            SELECT kd.*
            FROM knowledge_document kd
            INNER JOIN knowledge_base kb
                ON kb.id = kd.knowledge_base_id
                AND kb.user_id = kd.user_id
            WHERE kd.id = #{documentId}
              AND kd.user_id = #{userId}
              AND kb.deleted_at IS NULL
            FOR UPDATE OF kd
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    KnowledgeDocument selectOwnedWithLiveParentForDeletionForUpdate(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId
    );

    /**
     * Locks and revalidates the parent document inside V5's short vector-claim transaction.
     * The V24 deletion admission locks the same {@code kd} row before checking for PROCESSING
     * chunks and before soft deletion, so either ordering observes the other side's committed
     * state before any external embedding/vector-store call begins.
     */
    @Select("""
            SELECT kd.*
            FROM knowledge_document kd
            INNER JOIN knowledge_base kb
                ON kb.id = kd.knowledge_base_id
                AND kb.user_id = kd.user_id
            WHERE kd.id = #{documentId}
              AND kd.knowledge_base_id = #{knowledgeBaseId}
              AND kd.user_id = #{userId}
              AND kd.parse_status = 'COMPLETED'
              AND kd.deleted_at IS NULL
              AND kb.deleted_at IS NULL
            FOR UPDATE OF kd
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    KnowledgeDocument selectVectorizableOwnedForChunkClaimForUpdate(
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    /**
     * The actual V24 soft-delete mutation repeats the owner and live-parent predicates after the
     * caller acquired the document lock. This keeps a parent that became invisible at the write
     * boundary indistinguishable from every other 404 scope miss.
     */
    @Update("""
            UPDATE knowledge_document kd
            SET deleted_at = #{deletedAt},
                updated_at = #{deletedAt}
            FROM knowledge_base kb
            WHERE kd.id = #{documentId}
              AND kd.user_id = #{userId}
              AND kd.deleted_at IS NULL
              AND kb.id = kd.knowledge_base_id
              AND kb.user_id = kd.user_id
              AND kb.deleted_at IS NULL
            """)
    int softDeleteOwnedWithLiveParent(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId,
            @Param("deletedAt") OffsetDateTime deletedAt
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
