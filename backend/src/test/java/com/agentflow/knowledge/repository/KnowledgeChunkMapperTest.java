package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;

class KnowledgeChunkMapperTest {

    @Test
    void shouldRegisterTheV7CanonicalRetrievalStatement() {
        MybatisConfiguration configuration = new MybatisConfiguration();

        configuration.addMapper(KnowledgeChunkMapper.class);

        assertThat(configuration.hasStatement(
                KnowledgeChunkMapper.class.getName() + ".selectRetrievableChunks",
                false
        )).isTrue();
    }
}
