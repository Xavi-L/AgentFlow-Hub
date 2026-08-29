package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeDocumentDeletionTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 中文：V24 删除补偿任务的数据访问边界。所有方法都带完整 owner/知识库/文档三元范围；它不
 * 接受客户端传来的 locator、步骤状态或失败详情。
 *
 * <p>English: Data access for V24 deletion-compensation tasks. Every mutation keeps the complete
 * owner/knowledge-base/document scope and never accepts a client-provided locator, step state,
 * or failure detail.
 */
@Mapper
public interface KnowledgeDocumentDeletionTaskMapper extends BaseMapper<KnowledgeDocumentDeletionTask> {

    /** Locks only an unfinished task after the admission transaction already locked its document. */
    @Select("""
            SELECT *
            FROM knowledge_document_deletion_task
            WHERE document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND completed_at IS NULL
            FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    KnowledgeDocumentDeletionTask selectIncompleteByDocumentScopeForUpdate(
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    @Select("""
            SELECT *
            FROM knowledge_document_deletion_task
            WHERE document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
            """)
    KnowledgeDocumentDeletionTask selectByDocumentScope(
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    @Update("""
            UPDATE knowledge_document_deletion_task
            SET last_attempted_at = #{attemptedAt},
                updated_at = #{attemptedAt}
            WHERE id = #{taskId}
              AND document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND completed_at IS NULL
            """)
    int recordAttempt(
            @Param("taskId") Long taskId,
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId,
            @Param("attemptedAt") OffsetDateTime attemptedAt
    );

    @Update("""
            UPDATE knowledge_document_deletion_task
            SET vectors_deleted_at = #{completedAt},
                failure_summary = NULL,
                updated_at = #{completedAt}
            WHERE id = #{taskId}
              AND document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
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
            UPDATE knowledge_document_deletion_task
            SET source_deleted_at = #{completedAt},
                failure_summary = NULL,
                updated_at = #{completedAt}
            WHERE id = #{taskId}
              AND document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND source_deleted_at IS NULL
              AND completed_at IS NULL
            """)
    int markSourceDeleted(
            @Param("taskId") Long taskId,
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId,
            @Param("completedAt") OffsetDateTime completedAt
    );

    @Update("""
            UPDATE knowledge_document_deletion_task
            SET failure_summary = #{failureSummary},
                retry_count = retry_count + 1,
                last_failed_at = #{failedAt},
                updated_at = #{failedAt}
            WHERE id = #{taskId}
              AND document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND completed_at IS NULL
            """)
    int recordFailure(
            @Param("taskId") Long taskId,
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId,
            @Param("failureSummary") String failureSummary,
            @Param("failedAt") OffsetDateTime failedAt
    );

    @Update("""
            UPDATE knowledge_document_deletion_task
            SET chunks_deleted_at = #{completedAt},
                completed_at = #{completedAt},
                failure_summary = NULL,
                updated_at = #{completedAt}
            WHERE id = #{taskId}
              AND document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
              AND vectors_deleted_at IS NOT NULL
              AND source_deleted_at IS NOT NULL
              AND chunks_deleted_at IS NULL
              AND completed_at IS NULL
            """)
    int markChunksDeletedAndCompleted(
            @Param("taskId") Long taskId,
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId,
            @Param("completedAt") OffsetDateTime completedAt
    );
}
