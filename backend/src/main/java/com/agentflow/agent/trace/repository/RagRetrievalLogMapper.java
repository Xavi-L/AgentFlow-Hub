package com.agentflow.agent.trace.repository;

import com.agentflow.agent.trace.model.RagRetrievalLogRecord;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RagRetrievalLogMapper {

    @Insert("""
            INSERT INTO rag_retrieval_log (
                id, task_id, step_id, query, embedding_profile_code, corpus_snapshot,
                top_k, similarity_threshold, candidate_count, valid_hit_count, stale_hit_count,
                latency_ms, status, error_code, error_message, created_at
            ) VALUES (
                #{id}, #{taskId}, #{stepId}, #{query}, #{embeddingProfileCode},
                CAST(#{corpusSnapshotJson,jdbcType=VARCHAR} AS JSONB), #{topK},
                #{similarityThreshold}, #{candidateCount}, #{validHitCount}, #{staleHitCount},
                #{latencyMs}, #{status}, #{errorCode}, #{errorMessage}, #{createdAt}
            )
            """)
    int insertRetrieval(RagRetrievalLogRecord record);

    @Select("""
            SELECT id, task_id, step_id, query, embedding_profile_code,
                   corpus_snapshot::text AS corpus_snapshot_json, top_k, similarity_threshold,
                   candidate_count, valid_hit_count, stale_hit_count, latency_ms, status,
                   error_code, error_message, created_at
            FROM rag_retrieval_log
            WHERE task_id = #{taskId}
            ORDER BY created_at ASC, id ASC
            """)
    @Options(useCache = false)
    List<RagRetrievalLogRecord> selectByTaskIdOrdered(@Param("taskId") long taskId);
}
