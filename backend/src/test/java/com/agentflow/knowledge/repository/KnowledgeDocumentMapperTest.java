package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.junit.jupiter.api.Test;

/** Verifies the V21 visibility contract in the registered SQL, without a live database. */
class KnowledgeDocumentMapperTest {

    @Test
    void shouldRegisterDocumentDetailAsOneOwnerAndParentScopedJoin() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(KnowledgeDocumentMapper.class);

        String statementId = KnowledgeDocumentMapper.class.getName() + ".selectVisibleOwnedById";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();
        BoundSql sql = configuration.getMappedStatement(statementId).getBoundSql(Map.of(
                "documentId", 301L,
                "userId", 101L
        ));

        assertThat(sql.getSql()).contains(
                "SELECT kd.*",
                "FROM knowledge_document kd",
                "INNER JOIN knowledge_base kb",
                "kb.id = kd.knowledge_base_id",
                "kb.user_id = kd.user_id",
                "WHERE kd.id = ?",
                "kd.user_id = ?",
                "kd.deleted_at IS NULL",
                "kb.deleted_at IS NULL"
        );
        assertThat(sql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("documentId", "userId");
        assertThat(sql.getSql()).doesNotContain(
                "kb.status",
                "kd.parse_status",
                "UPDATE",
                "DELETE"
        );
    }
}
