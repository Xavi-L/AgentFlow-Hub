package com.agentflow.agent.trace.repository;

import com.agentflow.agent.trace.model.RagRetrievalHitLogRecord;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RagRetrievalHitLogMapper {

    @Insert("""
            INSERT INTO rag_retrieval_hit (
                id, retrieval_id, rank_no, citation_id, chunk_id_snapshot,
                document_id_snapshot, knowledge_base_id_snapshot, vector_generation,
                score, content_snapshot, metadata_snapshot, created_at
            ) VALUES (
                #{id}, #{retrievalId}, #{rankNo}, #{citationId}, #{chunkIdSnapshot},
                #{documentIdSnapshot}, #{knowledgeBaseIdSnapshot}, #{vectorGeneration},
                #{score}, #{contentSnapshot},
                CAST(#{metadataSnapshotJson,jdbcType=VARCHAR} AS JSONB), #{createdAt}
            )
            """)
    int insertHit(RagRetrievalHitLogRecord record);

    @Select("""
            SELECT h.id, h.retrieval_id, h.rank_no, h.citation_id, h.chunk_id_snapshot,
                   h.document_id_snapshot, h.knowledge_base_id_snapshot, h.vector_generation,
                   h.score, h.content_snapshot, h.metadata_snapshot::text AS metadata_snapshot_json,
                   h.created_at
            FROM rag_retrieval_hit h
            INNER JOIN rag_retrieval_log r ON r.id = h.retrieval_id
            WHERE r.task_id = #{taskId}
            ORDER BY h.retrieval_id ASC, h.rank_no ASC, h.id ASC
            """)
    @Options(useCache = false)
    List<RagRetrievalHitLogRecord> selectByTaskIdOrdered(@Param("taskId") long taskId);
}
