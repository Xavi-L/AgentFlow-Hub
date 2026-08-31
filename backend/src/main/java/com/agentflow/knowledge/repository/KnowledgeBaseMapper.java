package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeBase;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 中文：知识库元数据的数据访问边界。V19 的部分更新与 V20 的软删除都显式把路径 ID、current owner
 * 与未软删除状态放在 UPDATE 的同一 WHERE 子句中，不依赖先前读取结果作为写入授权。
 *
 * <p>English: Data access for knowledge-base metadata. V19's partial update and V20's
 * soft deletion explicitly keep the path ID, current owner, and non-deleted state in the
 * UPDATE WHERE clause; write authorization does not rely on a previous read.
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /** V25 chunk-list visibility lock, acquired before the child document lock. */
    @Select("""
            SELECT *
            FROM knowledge_base
            WHERE id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND deleted_at IS NULL
            FOR SHARE
            """)
    KnowledgeBase selectVisibleOwnedForShare(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    @Update("""
            UPDATE knowledge_base
            SET name = #{name},
                description = #{description},
                updated_at = #{updatedAt}
            WHERE id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND deleted_at IS NULL
            """)
    int updateMetadataOwned(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId,
            @Param("name") String name,
            @Param("description") String description,
            @Param("updatedAt") OffsetDateTime updatedAt
    );

    /**
     * 中文：只把当前 owner 尚未删除的行标记为软删除。deletedAt 参数在两个写入位置复用，保证
     * deleted_at 和 updated_at 来自同一个服务端时间；没有 status 条件，因此 ACTIVE 与 DISABLED
     * 都可以删除。
     *
     * <p>English: Marks only a current owner's non-deleted row as deleted. The same
     * deletedAt parameter is written to both columns, so deleted_at and updated_at share
     * one server-side timestamp. There is no status condition, so ACTIVE and DISABLED rows
     * can both be deleted.
     */
    @Update("""
            UPDATE knowledge_base
            SET deleted_at = #{deletedAt},
                updated_at = #{deletedAt}
            WHERE id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND deleted_at IS NULL
            """)
    int softDeleteOwned(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId,
            @Param("deletedAt") OffsetDateTime deletedAt
    );
}
