package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedback;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMapping;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;

class KnowledgeChatAnswerFeedbackMapperTest {

    @Test
    void shouldRegisterTheParentAnswerDrivenV13LeftJoinStatusStatement() {
        MybatisConfiguration configuration = new MybatisConfiguration();

        configuration.addMapper(KnowledgeChatAnswerFeedbackMapper.class);

        String mapperName = KnowledgeChatAnswerFeedbackMapper.class.getName();
        String statusSelectId = mapperName + ".selectStatusOwnedByAnswerId";
        assertThat(configuration.hasStatement(statusSelectId, false)).isTrue();
        MappedStatement statusSelect = configuration.getMappedStatement(statusSelectId);
        BoundSql statusSql = statusSelect.getBoundSql(Map.of(
                "answerId", 501L,
                "knowledgeBaseId", 201L,
                "userId", 101L
        ));
        assertThat(statusSql.getSql()).contains(
                "FROM knowledge_chat_answer a",
                "LEFT JOIN knowledge_chat_answer_feedback f",
                "a.id",
                "a.knowledge_base_id",
                "a.user_id"
        );
        assertThat(statusSelect.getResultMaps().getFirst().getType())
                .isEqualTo(KnowledgeChatAnswerFeedbackStatus.class);
        assertThat(statusSelect.getResultMaps().getFirst().getResultMappings())
                .extracting(ResultMapping::getProperty)
                .contains("answerId", "feedbackId", "verdict", "createdAt");
    }

    @Test
    void shouldRegisterScopedReadAndAtomicInsertIfAbsentStatements() {
        MybatisConfiguration configuration = new MybatisConfiguration();

        configuration.addMapper(KnowledgeChatAnswerFeedbackMapper.class);

        String mapperName = KnowledgeChatAnswerFeedbackMapper.class.getName();
        String scopedSelectId = mapperName + ".selectOwnedByAnswerId";
        assertThat(configuration.hasStatement(scopedSelectId, false)).isTrue();
        MappedStatement scopedSelect = configuration.getMappedStatement(scopedSelectId);
        BoundSql scopedSelectSql = scopedSelect.getBoundSql(Map.of(
                "answerId", 501L,
                "knowledgeBaseId", 201L,
                "userId", 101L
        ));
        assertThat(scopedSelectSql.getSql()).contains(
                "JOIN knowledge_chat_answer",
                "knowledge_base_id",
                "user_id"
        );
        assertThat(scopedSelect.getResultMaps().getFirst().getResultMappings())
                .extracting(ResultMapping::getProperty)
                .contains("answerId", "verdict", "createdAt");

        KnowledgeChatAnswerFeedback feedback = new KnowledgeChatAnswerFeedback();
        feedback.setId(701L);
        feedback.setAnswerId(501L);
        feedback.setVerdict("HELPFUL");
        feedback.setCreatedAt(OffsetDateTime.parse("2026-08-27T10:30:00+08:00"));
        String insertId = mapperName + ".insertIfAbsent";
        assertThat(configuration.hasStatement(insertId, false)).isTrue();
        BoundSql insertSql = configuration.getMappedStatement(insertId).getBoundSql(Map.of(
                "feedback", feedback,
                "knowledgeBaseId", 201L,
                "userId", 101L
        ));
        assertThat(insertSql.getSql()).contains(
                "INSERT INTO knowledge_chat_answer_feedback",
                "SELECT",
                "FROM knowledge_chat_answer",
                "knowledge_base_id",
                "user_id",
                "ON CONFLICT (answer_id) DO NOTHING"
        );
    }
}
