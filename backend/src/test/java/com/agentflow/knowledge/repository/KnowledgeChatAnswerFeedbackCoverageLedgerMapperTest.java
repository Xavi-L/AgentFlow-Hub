package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackCoverageItem;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMapping;
import org.junit.jupiter.api.Test;

class KnowledgeChatAnswerFeedbackCoverageLedgerMapperTest {

    @Test
    void shouldRegisterTheV17ParentAnswerDrivenCoverageLedgerWithOnlyThreeColumns() {
        MybatisConfiguration configuration = new MybatisConfiguration();

        configuration.addMapper(KnowledgeChatAnswerFeedbackMapper.class);

        String mapperName = KnowledgeChatAnswerFeedbackMapper.class.getName();
        String statementId = mapperName + ".selectCoverageLedgerPageOwnedByKnowledgeBase";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();
        MappedStatement statement = configuration.getMappedStatement(statementId);
        BoundSql sql = statement.getBoundSql(Map.of(
                "page", new Page<KnowledgeChatAnswerFeedbackCoverageItem>(1, 20),
                "knowledgeBaseId", 201L,
                "userId", 101L
        ));

        assertThat(sql.getSql()).contains(
                "SELECT a.id AS answer_id",
                "f.id IS NOT NULL AS submitted",
                "a.created_at AS answer_created_at",
                "FROM knowledge_chat_answer a",
                "LEFT JOIN knowledge_chat_answer_feedback f ON f.answer_id = a.id",
                "a.knowledge_base_id",
                "a.user_id",
                "ORDER BY a.created_at DESC, a.id DESC"
        );
        assertThat(sql.getSql()).doesNotContain(
                "INNER JOIN",
                "f.verdict",
                "f.created_at",
                "query",
                "citation_ids_json",
                "sources_snapshot_json",
                "GROUP BY",
                "INSERT",
                "UPDATE",
                "DELETE"
        );
        assertThat(statement.getResultMaps().getFirst().getType())
                .isEqualTo(KnowledgeChatAnswerFeedbackCoverageItem.class);
        assertThat(statement.getResultMaps().getFirst().getResultMappings())
                .extracting(ResultMapping::getProperty)
                .containsExactly("answerId", "submitted", "answerCreatedAt");
    }
}
