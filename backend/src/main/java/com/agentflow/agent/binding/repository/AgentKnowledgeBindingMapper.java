package com.agentflow.agent.binding.repository;

import com.agentflow.agent.binding.model.AgentKnowledgeBinding;
import com.agentflow.agent.binding.model.BoundKnowledgeBaseRow;
import com.agentflow.agent.binding.model.ReadyDocumentGenerationRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Owner-scoped persistence and snapshot reads for Agent knowledge bindings. */
@Mapper
public interface AgentKnowledgeBindingMapper extends BaseMapper<AgentKnowledgeBinding> {

    @Select("""
            SELECT knowledge_base_id
            FROM agent_knowledge_binding
            WHERE agent_id = #{agentId}
              AND user_id = #{userId}
            ORDER BY priority ASC, knowledge_base_id ASC
            """)
    List<Long> selectBoundKnowledgeBaseIds(
            @Param("agentId") Long agentId,
            @Param("userId") Long userId
    );

    @Select("""
            <script>
            SELECT id
            FROM knowledge_base
            WHERE user_id = #{userId}
              AND deleted_at IS NULL
              AND status = 'ACTIVE'
              AND id IN
              <foreach collection="knowledgeBaseIds" item="knowledgeBaseId" open="(" separator="," close=")">
                #{knowledgeBaseId}
              </foreach>
            ORDER BY id ASC
            </script>
            """)
    List<Long> selectBindableOwnedKnowledgeBaseIds(
            @Param("userId") Long userId,
            @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds
    );

    @Delete("""
            DELETE FROM agent_knowledge_binding
            WHERE agent_id = #{agentId}
              AND user_id = #{userId}
            """)
    int deleteOwnedByAgent(
            @Param("agentId") Long agentId,
            @Param("userId") Long userId
    );

    @Select("""
            SELECT kb.id AS knowledge_base_id,
                   kb.embedding_provider,
                   kb.embedding_model,
                   kb.chunk_size,
                   kb.chunk_overlap
            FROM agent_knowledge_binding akb
            JOIN knowledge_base kb
              ON kb.id = akb.knowledge_base_id
             AND kb.user_id = akb.user_id
             AND kb.deleted_at IS NULL
             AND kb.status = 'ACTIVE'
            WHERE akb.agent_id = #{agentId}
              AND akb.user_id = #{userId}
            ORDER BY akb.priority ASC, kb.id ASC
            """)
    List<BoundKnowledgeBaseRow> selectActiveBoundKnowledgeBases(
            @Param("agentId") Long agentId,
            @Param("userId") Long userId
    );

    @Select("""
            SELECT kd.knowledge_base_id,
                   kd.id AS document_id,
                   kd.vector_generation,
                   MIN(kc.chunk_strategy_version) AS chunk_strategy_version
            FROM agent_knowledge_binding akb
            JOIN knowledge_base kb
              ON kb.id = akb.knowledge_base_id
             AND kb.user_id = akb.user_id
             AND kb.deleted_at IS NULL
             AND kb.status = 'ACTIVE'
            JOIN knowledge_document kd
              ON kd.knowledge_base_id = kb.id
             AND kd.user_id = kb.user_id
             AND kd.deleted_at IS NULL
             AND kd.parse_status = 'COMPLETED'
            JOIN knowledge_chunk kc
              ON kc.document_id = kd.id
             AND kc.knowledge_base_id = kd.knowledge_base_id
             AND kc.user_id = kd.user_id
             AND kc.vector_generation = kd.vector_generation
            WHERE akb.agent_id = #{agentId}
              AND akb.user_id = #{userId}
            GROUP BY kd.knowledge_base_id, kd.id, kd.vector_generation
            HAVING BOOL_AND(kc.vectorization_status = 'COMPLETED')
               AND COUNT(DISTINCT kc.chunk_strategy_version) = 1
            ORDER BY kd.knowledge_base_id ASC, kd.id ASC
            """)
    List<ReadyDocumentGenerationRow> selectReadyDocumentGenerations(
            @Param("agentId") Long agentId,
            @Param("userId") Long userId
    );
}
