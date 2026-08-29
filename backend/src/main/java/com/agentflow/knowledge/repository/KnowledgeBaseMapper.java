package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeBase;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 中文：知识库元数据的数据访问边界。V19 的部分更新显式把路径 ID、current owner 与未软删除
 * 状态放在 UPDATE 的同一 WHERE 子句中，不依赖先前读取结果作为写入授权。
 *
 * <p>English: Data access for knowledge-base metadata. V19's partial update explicitly
 * keeps the path ID, current owner, and non-deleted state in the UPDATE WHERE clause;
 * write authorization does not rely on a previous read.
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

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
}
