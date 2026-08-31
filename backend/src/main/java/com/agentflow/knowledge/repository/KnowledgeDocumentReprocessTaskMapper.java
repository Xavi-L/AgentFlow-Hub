package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeDocumentReprocessTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Scope-complete persistence boundary for V25's independent durable task. */
@Mapper
public interface KnowledgeDocumentReprocessTaskMapper extends BaseMapper<KnowledgeDocumentReprocessTask> {
    @Select("""
            SELECT *
            FROM knowledge_document_reprocess_task
            WHERE document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND completed_at IS NULL
            FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    KnowledgeDocumentReprocessTask selectActiveByDocumentScopeForUpdate(
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    @Select("""
            SELECT *
            FROM knowledge_document_reprocess_task
            WHERE id = #{taskId}
              AND document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
            FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    KnowledgeDocumentReprocessTask selectExactForUpdate(
            @Param("taskId") Long taskId,
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    @Update("""
            UPDATE knowledge_document_reprocess_task
            SET cleanup_status = 'VECTOR_DELETING',
                failure_summary = NULL,
                last_attempted_at = #{attemptedAt},
                updated_at = #{attemptedAt}
            WHERE id = #{taskId}
              AND document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND cleanup_status = 'VECTOR_DELETE_RETRYABLE'
              AND completed_at IS NULL
            """)
    int claimVectorRetry(
            @Param("taskId") Long taskId,
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId,
            @Param("attemptedAt") OffsetDateTime attemptedAt
    );

    @Update("""
            UPDATE knowledge_document_reprocess_task
            SET cleanup_status = 'READY_TO_FINALIZE',
                vectors_deleted_at = #{completedAt},
                failure_summary = NULL,
                updated_at = #{completedAt}
            WHERE id = #{taskId}
              AND document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND cleanup_status = 'VECTOR_DELETING'
              AND vectors_deleted_at IS NULL
              AND completed_at IS NULL
            """)
    int markVectorsDeleted(
            @Param("taskId") Long taskId,
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId,
            @Param("completedAt") OffsetDateTime completedAt
    );

    @Update("""
            UPDATE knowledge_document_reprocess_task
            SET cleanup_status = 'VECTOR_DELETE_RETRYABLE',
                failure_summary = #{failureSummary},
                retry_count = retry_count + 1,
                last_failed_at = #{failedAt},
                updated_at = #{failedAt}
            WHERE id = #{taskId}
              AND document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND cleanup_status = 'VECTOR_DELETING'
              AND vectors_deleted_at IS NULL
              AND completed_at IS NULL
            """)
    int recordVectorFailure(
            @Param("taskId") Long taskId,
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId,
            @Param("failureSummary") String failureSummary,
            @Param("failedAt") OffsetDateTime failedAt
    );

    @Update("""
            UPDATE knowledge_document_reprocess_task
            SET cleanup_status = 'COMPLETED',
                chunks_deleted_at = #{completedAt},
                completed_at = #{completedAt},
                failure_summary = NULL,
                updated_at = #{completedAt}
            WHERE id = #{taskId}
              AND document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND cleanup_status = 'READY_TO_FINALIZE'
              AND vectors_deleted_at IS NOT NULL
              AND chunks_deleted_at IS NULL
              AND completed_at IS NULL
            """)
    int markCompleted(
            @Param("taskId") Long taskId,
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId,
            @Param("completedAt") OffsetDateTime completedAt
    );
}
