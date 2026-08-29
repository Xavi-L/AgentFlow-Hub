package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
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

    @Test
    void shouldRegisterV24ProcessingProbeAndPhysicalChunkDeleteWithTheFullScope() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(KnowledgeChunkMapper.class);

        Map<String, Long> parameters = Map.of(
                "documentId", 301L,
                "knowledgeBaseId", 201L,
                "userId", 101L
        );
        BoundSql processingSql = configuration.getMappedStatement(
                KnowledgeChunkMapper.class.getName() + ".hasProcessingChunkByDocumentScope"
        ).getBoundSql(parameters);
        assertThat(processingSql.getSql()).contains(
                "SELECT EXISTS",
                "FROM knowledge_chunk",
                "document_id = ?",
                "knowledge_base_id = ?",
                "user_id = ?",
                "vectorization_status = 'PROCESSING'"
        );

        BoundSql deleteSql = configuration.getMappedStatement(
                KnowledgeChunkMapper.class.getName() + ".deleteByDocumentScope"
        ).getBoundSql(parameters);
        assertThat(deleteSql.getSql()).contains(
                "DELETE FROM knowledge_chunk",
                "document_id = ?",
                "knowledge_base_id = ?",
                "user_id = ?"
        );
    }
}
