package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.time.OffsetDateTime;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.Test;

class KnowledgeBaseMapperTest {

    @Test
    void shouldRegisterV19MetadataUpdateWithOwnerAndSoftDeleteScopeInTheSqlItself() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(KnowledgeBaseMapper.class);

        String statementId = KnowledgeBaseMapper.class.getName() + ".updateMetadataOwned";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();
        BoundSql sql = configuration.getMappedStatement(statementId).getBoundSql(Map.of(
                "knowledgeBaseId", 201L,
                "userId", 101L,
                "name", "Renamed knowledge base",
                "description", "Updated description",
                "updatedAt", OffsetDateTime.parse("2026-08-29T10:00:00+08:00")
        ));

        assertThat(sql.getSql()).contains(
                "UPDATE knowledge_base",
                "SET name = ?",
                "description = ?",
                "updated_at = ?",
                "WHERE id = ?",
                "user_id = ?",
                "deleted_at IS NULL"
        );
        assertThat(sql.getSql()).doesNotContain(
                "status =",
                "embedding_provider",
                "embedding_model",
                "chunk_size",
                "chunk_overlap",
                "metadata",
                "created_at",
                "DELETE"
        );
    }
}
