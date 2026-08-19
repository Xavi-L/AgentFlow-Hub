package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMapping;
import org.junit.jupiter.api.Test;

class KnowledgeChunkMapperTest {

    @Test
    void shouldRegisterTheV7V8CanonicalRetrievalStatementWithSourceFileProjection() {
        MybatisConfiguration configuration = new MybatisConfiguration();

        configuration.addMapper(KnowledgeChunkMapper.class);

        String statementId = KnowledgeChunkMapper.class.getName() + ".selectRetrievableChunks";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();

        MappedStatement statement = configuration.getMappedStatement(statementId);
        assertThat(statement.getResultMaps().getFirst().getResultMappings())
                .extracting(ResultMapping::getProperty)
                .contains("documentFileName");
    }
}
