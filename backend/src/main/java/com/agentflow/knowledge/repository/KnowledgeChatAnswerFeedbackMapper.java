package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedback;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackCoverage;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackCoverageItem;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackSummary;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackStatus;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * 中文：V12–V17 不可变回答反馈的数据访问边界。answer_id 的唯一约束和 PostgreSQL 的 ON CONFLICT
 * 保证并发重试不产生第二条事件；每次读取和写入均把 owner、知识库和 answerId 放在同一 SQL 范围内。
 *
 * <p>English: Data access for V12–V17 immutable answer feedback. The answer_id uniqueness
 * constraint plus PostgreSQL ON CONFLICT prevents concurrent retries from creating a second
 * event; every read and write binds owner, knowledge base, and answerId in one SQL scope.</p>
 */
@Mapper
public interface KnowledgeChatAnswerFeedbackMapper extends BaseMapper<KnowledgeChatAnswerFeedback> {

    /**
     * Reads V17 submission-state rows from scoped immutable parent answers. The parent-driven
     * LEFT JOIN retains answers without feedback; the existing V11 answer-list index supports
     * the same owner/knowledge-base scope and created-at/id ordering.
     */
    @Results(id = "knowledgeChatAnswerFeedbackCoverageItemResult", value = {
            @Result(property = "answerId", column = "answer_id"),
            @Result(property = "submitted", column = "submitted"),
            @Result(property = "answerCreatedAt", column = "answer_created_at")
    })
    @Select("""
            SELECT a.id AS answer_id,
                   f.id IS NOT NULL AS submitted,
                   a.created_at AS answer_created_at
            FROM knowledge_chat_answer a
            LEFT JOIN knowledge_chat_answer_feedback f ON f.answer_id = a.id
            WHERE a.knowledge_base_id = #{knowledgeBaseId}
              AND a.user_id = #{userId}
            ORDER BY a.created_at DESC, a.id DESC
            """)
    Page<KnowledgeChatAnswerFeedbackCoverageItem> selectCoverageLedgerPageOwnedByKnowledgeBase(
            @Param("page") Page<KnowledgeChatAnswerFeedbackCoverageItem> page,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    /**
     * Aggregates V16 raw coverage from scoped immutable parent answers. The parent-driven LEFT
     * JOIN deliberately retains answers without feedback; no existence pre-check can distinguish
     * an empty, foreign-owner, or wrong-knowledge-base scope from its zero aggregate row.
     */
    @Results(id = "knowledgeChatAnswerFeedbackCoverageResult", value = {
            @Result(property = "answerCount", column = "answer_count"),
            @Result(property = "submittedCount", column = "submitted_count"),
            @Result(property = "unsubmittedCount", column = "unsubmitted_count")
    })
    @Select("""
            SELECT COUNT(a.id) AS answer_count,
                   COUNT(f.id) AS submitted_count,
                   COUNT(a.id) FILTER (WHERE f.id IS NULL) AS unsubmitted_count
            FROM knowledge_chat_answer a
            LEFT JOIN knowledge_chat_answer_feedback f ON f.answer_id = a.id
            WHERE a.knowledge_base_id = #{knowledgeBaseId}
              AND a.user_id = #{userId}
            """)
    KnowledgeChatAnswerFeedbackCoverage selectCoverageOwnedByKnowledgeBase(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    /**
     * Aggregates the V15 raw event counts from submitted feedback rows scoped through immutable
     * parent answers. PostgreSQL aggregates return one zero-count row for an empty, foreign, or
     * wrong-knowledge-base scope, so this deliberately performs no existence pre-check.
     */
    @Results(id = "knowledgeChatAnswerFeedbackSummaryResult", value = {
            @Result(property = "submittedCount", column = "submitted_count"),
            @Result(property = "helpfulCount", column = "helpful_count"),
            @Result(property = "notHelpfulCount", column = "not_helpful_count")
    })
    @Select("""
            SELECT COUNT(*) AS submitted_count,
                   COUNT(*) FILTER (WHERE f.verdict = 'HELPFUL') AS helpful_count,
                   COUNT(*) FILTER (WHERE f.verdict = 'NOT_HELPFUL') AS not_helpful_count
            FROM knowledge_chat_answer_feedback f
            INNER JOIN knowledge_chat_answer a ON a.id = f.answer_id
            WHERE a.knowledge_base_id = #{knowledgeBaseId}
              AND a.user_id = #{userId}
            """)
    KnowledgeChatAnswerFeedbackSummary selectSummaryOwnedByKnowledgeBase(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    /**
     * Reads the V14 ledger from submitted feedback events and scopes every row through its
     * immutable parent answer. It intentionally does not pre-check knowledge-base existence:
     * empty, foreign-owner, and wrong-knowledge-base ranges all produce an empty page.
     */
    @Results(id = "knowledgeChatAnswerFeedbackLedgerResult", value = {
            @Result(id = true, property = "id", column = "id"),
            @Result(property = "answerId", column = "answer_id"),
            @Result(property = "verdict", column = "verdict"),
            @Result(property = "createdAt", column = "created_at")
    })
    @Select("""
            SELECT f.id,
                   f.answer_id,
                   f.verdict,
                   f.created_at
            FROM knowledge_chat_answer_feedback f
            JOIN knowledge_chat_answer a ON a.id = f.answer_id
            WHERE a.knowledge_base_id = #{knowledgeBaseId}
              AND a.user_id = #{userId}
            ORDER BY f.created_at DESC, f.id DESC
            """)
    Page<KnowledgeChatAnswerFeedback> selectPageOwnedByKnowledgeBase(
            @Param("page") Page<KnowledgeChatAnswerFeedback> page,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    /**
     * Reads the V13 status from the scoped parent answer. A returned row with a null feedbackId
     * means that the answer is visible to the current owner but no V12 event has been submitted.
     */
    @Results(id = "knowledgeChatAnswerFeedbackStatusResult", value = {
            @Result(property = "answerId", column = "answer_id"),
            @Result(property = "feedbackId", column = "feedback_id"),
            @Result(property = "verdict", column = "verdict"),
            @Result(property = "createdAt", column = "created_at")
    })
    @Select("""
            SELECT a.id AS answer_id,
                   f.id AS feedback_id,
                   f.verdict,
                   f.created_at
            FROM knowledge_chat_answer a
            LEFT JOIN knowledge_chat_answer_feedback f ON f.answer_id = a.id
            WHERE a.id = #{answerId}
              AND a.knowledge_base_id = #{knowledgeBaseId}
              AND a.user_id = #{userId}
            """)
    KnowledgeChatAnswerFeedbackStatus selectStatusOwnedByAnswerId(
            @Param("answerId") Long answerId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    @Results(id = "knowledgeChatAnswerFeedbackResult", value = {
            @Result(id = true, property = "id", column = "id"),
            @Result(property = "answerId", column = "answer_id"),
            @Result(property = "verdict", column = "verdict"),
            @Result(property = "createdAt", column = "created_at")
    })
    @Select("""
            SELECT f.id,
                   f.answer_id,
                   f.verdict,
                   f.created_at
            FROM knowledge_chat_answer_feedback f
            JOIN knowledge_chat_answer a ON a.id = f.answer_id
            WHERE f.answer_id = #{answerId}
              AND a.knowledge_base_id = #{knowledgeBaseId}
              AND a.user_id = #{userId}
            """)
    KnowledgeChatAnswerFeedback selectOwnedByAnswerId(
            @Param("answerId") Long answerId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    @Insert("""
            INSERT INTO knowledge_chat_answer_feedback (
                id,
                answer_id,
                verdict,
                created_at
            )
            SELECT
                #{feedback.id},
                #{feedback.answerId},
                #{feedback.verdict},
                #{feedback.createdAt}
            FROM knowledge_chat_answer
            WHERE id = #{feedback.answerId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
            ON CONFLICT (answer_id) DO NOTHING
            """)
    int insertIfAbsent(
            @Param("feedback") KnowledgeChatAnswerFeedback feedback,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );
}
