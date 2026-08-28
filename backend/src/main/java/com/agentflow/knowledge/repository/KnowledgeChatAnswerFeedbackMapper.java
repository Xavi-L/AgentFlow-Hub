package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedback;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * 中文：V12 不可变回答反馈的数据访问边界。answer_id 的唯一约束和 PostgreSQL 的 ON CONFLICT 保证
 * 并发重试不产生第二条事件；每次读取和写入均把 owner、知识库和 answerId 放在同一 SQL 范围内。
 *
 * <p>English: Data access for V12 immutable answer feedback. The answer_id uniqueness
 * constraint plus PostgreSQL ON CONFLICT prevents concurrent retries from creating a second
 * event; every read and write binds owner, knowledge base, and answerId in one SQL scope.</p>
 */
@Mapper
public interface KnowledgeChatAnswerFeedbackMapper extends BaseMapper<KnowledgeChatAnswerFeedback> {

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
