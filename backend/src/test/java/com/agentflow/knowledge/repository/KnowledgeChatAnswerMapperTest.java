package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMapping;
import org.junit.jupiter.api.Test;

class KnowledgeChatAnswerMapperTest {

    @Test
    void shouldRegisterOneOwnerAndKnowledgeBaseScopedAnswerLookup() {
        MybatisConfiguration configuration = new MybatisConfiguration();

        configuration.addMapper(KnowledgeChatAnswerMapper.class);

        String statementId = KnowledgeChatAnswerMapper.class.getName() + ".selectOwnedById";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();

        MappedStatement statement = configuration.getMappedStatement(statementId);
        BoundSql boundSql = statement.getBoundSql(Map.of(
                "answerId", 501L,
                "knowledgeBaseId", 201L,
                "userId", 101L
        ));
        assertThat(boundSql.getSql()).contains("knowledge_base_id", "user_id");
        assertThat(statement.getResultMaps().getFirst().getResultMappings())
                .extracting(ResultMapping::getProperty)
                .contains("knowledgeBaseId", "sourcesSnapshotJson", "citationIdsJson");
    }
}
