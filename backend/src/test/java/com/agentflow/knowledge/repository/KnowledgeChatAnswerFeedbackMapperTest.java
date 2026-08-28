package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedback;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackSummary;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackStatus;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.OffsetDateTime;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMapping;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;

class KnowledgeChatAnswerFeedbackMapperTest {

    @Test
    void shouldRegisterTheV15RawEventSummaryThroughAnInnerJoinToScopedParentAnswers() {
        MybatisConfiguration configuration = new MybatisConfiguration();

        configuration.addMapper(KnowledgeChatAnswerFeedbackMapper.class);

        String mapperName = KnowledgeChatAnswerFeedbackMapper.class.getName();
        String summarySelectId = mapperName + ".selectSummaryOwnedByKnowledgeBase";
        assertThat(configuration.hasStatement(summarySelectId, false)).isTrue();
        MappedStatement summarySelect = configuration.getMappedStatement(summarySelectId);
        BoundSql summarySql = summarySelect.getBoundSql(Map.of(
                "knowledgeBaseId", 201L,
                "userId", 101L
        ));
        assertThat(summarySql.getSql()).contains(
                "SELECT COUNT(*) AS submitted_count",
                "COUNT(*) FILTER (WHERE f.verdict = 'HELPFUL') AS helpful_count",
                "COUNT(*) FILTER (WHERE f.verdict = 'NOT_HELPFUL') AS not_helpful_count",
                "FROM knowledge_chat_answer_feedback f",
                "INNER JOIN knowledge_chat_answer a ON a.id = f.answer_id",
                "a.knowledge_base_id",
                "a.user_id"
        );
        assertThat(summarySql.getSql()).doesNotContain(
                "LEFT JOIN",
                "GROUP BY",
                "ORDER BY",
                "INSERT",
                "UPDATE",
                "DELETE"
        );
        assertThat(summarySelect.getResultMaps().getFirst().getType())
                .isEqualTo(KnowledgeChatAnswerFeedbackSummary.class);
        assertThat(summarySelect.getResultMaps().getFirst().getResultMappings())
                .extracting(ResultMapping::getProperty)
                .containsExactly("submittedCount", "helpfulCount", "notHelpfulCount");
    }

    @Test
    void shouldRegisterTheV14SubmittedEventLedgerWithParentOwnerAndKnowledgeBaseScope() {
        MybatisConfiguration configuration = new MybatisConfiguration();

        configuration.addMapper(KnowledgeChatAnswerFeedbackMapper.class);

        String mapperName = KnowledgeChatAnswerFeedbackMapper.class.getName();
        String ledgerSelectId = mapperName + ".selectPageOwnedByKnowledgeBase";
        assertThat(configuration.hasStatement(ledgerSelectId, false)).isTrue();
        MappedStatement ledgerSelect = configuration.getMappedStatement(ledgerSelectId);
        BoundSql ledgerSql = ledgerSelect.getBoundSql(Map.of(
                "page", new Page<KnowledgeChatAnswerFeedback>(1, 20),
                "knowledgeBaseId", 201L,
                "userId", 101L
        ));
        assertThat(ledgerSql.getSql()).contains(
                "SELECT f.id",
                "f.answer_id",
                "f.verdict",
                "f.created_at",
                "FROM knowledge_chat_answer_feedback f",
                "JOIN knowledge_chat_answer a ON a.id = f.answer_id",
                "a.knowledge_base_id",
                "a.user_id",
                "ORDER BY f.created_at DESC, f.id DESC"
        );
        assertThat(ledgerSql.getSql()).doesNotContain(
                "LEFT JOIN",
                "query",
                "citation_ids_json",
                "sources_snapshot_json"
        );
        assertThat(ledgerSelect.getResultMaps().getFirst().getResultMappings())
                .extracting(ResultMapping::getProperty)
                .containsExactly("id", "answerId", "verdict", "createdAt");
    }

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
