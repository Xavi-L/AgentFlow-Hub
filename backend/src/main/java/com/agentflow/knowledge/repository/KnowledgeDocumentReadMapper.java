package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.readiness.DocumentReadinessRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** One batch aggregate for at most the current page, including zero-chunk documents. */
@Mapper
public interface KnowledgeDocumentReadMapper {
    @Select("""
            <script>
            SELECT kd.id AS document_id,
                   kb.status AS knowledge_base_status,
                   kb.embedding_provider, kb.embedding_model, kb.chunk_size, kb.chunk_overlap,
                   COUNT(kc.id) FILTER (WHERE kc.vectorization_status = 'PENDING') AS pending,
                   COUNT(kc.id) FILTER (WHERE kc.vectorization_status = 'PROCESSING') AS processing,
                   COUNT(kc.id) FILTER (WHERE kc.vectorization_status = 'COMPLETED') AS completed,
                   COUNT(kc.id) FILTER (WHERE kc.vectorization_status = 'FAILED') AS failed,
                   COUNT(DISTINCT kc.chunk_strategy_version) AS strategy_count,
                   MIN(kc.chunk_strategy_version) AS chunk_strategy_version
              FROM knowledge_document kd
              JOIN knowledge_base kb ON kb.id = kd.knowledge_base_id
                                    AND kb.user_id = kd.user_id
                                    AND kb.deleted_at IS NULL
              LEFT JOIN knowledge_chunk kc ON kc.document_id = kd.id
                                          AND kc.knowledge_base_id = kd.knowledge_base_id
                                          AND kc.user_id = kd.user_id
                                          AND kc.vector_generation = kd.vector_generation
             WHERE kd.user_id = #{userId}
               AND kd.deleted_at IS NULL
               AND kd.id IN
               <foreach collection="documentIds" item="documentId" open="(" separator="," close=")">
                 #{documentId}
               </foreach>
             GROUP BY kd.id, kb.id
            </script>
            """)
    List<DocumentReadinessRow> selectReadinessByDocumentIds(
            @Param("userId") Long userId,
            @Param("documentIds") List<Long> documentIds
    );
}
