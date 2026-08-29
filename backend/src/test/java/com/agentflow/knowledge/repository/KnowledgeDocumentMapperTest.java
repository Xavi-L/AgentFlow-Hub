package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.time.OffsetDateTime;
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

    @Test
    void shouldRegisterV22FailedReprocessAsOneParentScopedConditionalReturningUpdate() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(KnowledgeDocumentMapper.class);

        String statementId = KnowledgeDocumentMapper.class.getName()
                + ".reprocessFailedVisibleOwned";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();
        assertThat(configuration.getMappedStatement(statementId).isFlushCacheRequired()).isTrue();
        assertThat(configuration.getMappedStatement(statementId).isUseCache()).isFalse();
        BoundSql sql = configuration.getMappedStatement(statementId).getBoundSql(Map.of(
                "documentId", 301L,
                "userId", 101L,
                "updatedAt", OffsetDateTime.parse("2026-08-29T12:00:00+08:00")
        ));

        assertThat(sql.getSql()).contains(
                "UPDATE knowledge_document kd",
                "SET parse_status = 'PENDING'",
                "parse_error = NULL",
                "updated_at = ?",
                "FROM knowledge_base kb",
                "WHERE kd.id = ?",
                "kd.user_id = ?",
                "kd.deleted_at IS NULL",
                "kd.parse_status = 'FAILED'",
                "kb.id = kd.knowledge_base_id",
                "kb.user_id = kd.user_id",
                "kb.deleted_at IS NULL",
                "RETURNING kd.*"
        );
        assertThat(sql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("updatedAt", "documentId", "userId");
        assertThat(sql.getSql()).doesNotContain(
                "kb.status",
                "knowledge_chunk",
                "vector",
                "DELETE"
        );
    }

    @Test
    void shouldRegisterV24DeletionAdmissionLockAndVectorClaimLockSeparately() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(KnowledgeDocumentMapper.class);

        String admissionStatementId = KnowledgeDocumentMapper.class.getName()
                + ".selectOwnedWithLiveParentForDeletionForUpdate";
        BoundSql admissionSql = configuration.getMappedStatement(admissionStatementId).getBoundSql(Map.of(
                "documentId", 301L,
                "userId", 101L
        ));
        assertThat(admissionSql.getSql()).contains(
                "FROM knowledge_document kd",
                "INNER JOIN knowledge_base kb",
                "kd.id = ?",
                "kd.user_id = ?",
                "kb.deleted_at IS NULL",
                "FOR UPDATE OF kd"
        );
        assertThat(admissionSql.getSql()).doesNotContain("kd.deleted_at IS NULL");
        assertThat(admissionSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("documentId", "userId");

        String claimStatementId = KnowledgeDocumentMapper.class.getName()
                + ".selectVectorizableOwnedForChunkClaimForUpdate";
        BoundSql claimSql = configuration.getMappedStatement(claimStatementId).getBoundSql(Map.of(
                "documentId", 301L,
                "knowledgeBaseId", 201L,
                "userId", 101L
        ));
        assertThat(claimSql.getSql()).contains(
                "kd.id = ?",
                "kd.knowledge_base_id = ?",
                "kd.user_id = ?",
                "kd.parse_status = 'COMPLETED'",
                "kd.deleted_at IS NULL",
                "kb.deleted_at IS NULL",
                "FOR UPDATE OF kd"
        );
        assertThat(claimSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("documentId", "knowledgeBaseId", "userId");
    }

    @Test
    void shouldRegisterV24SoftDeleteAsOneScopedLiveParentMutation() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(KnowledgeDocumentMapper.class);

        String statementId = KnowledgeDocumentMapper.class.getName()
                + ".softDeleteOwnedWithLiveParent";
        BoundSql sql = configuration.getMappedStatement(statementId).getBoundSql(Map.of(
                "documentId", 301L,
                "userId", 101L,
                "deletedAt", OffsetDateTime.parse("2026-08-30T12:00:00+08:00")
        ));

        assertThat(sql.getSql()).contains(
                "UPDATE knowledge_document kd",
                "SET deleted_at = ?",
                "updated_at = ?",
                "FROM knowledge_base kb",
                "kd.id = ?",
                "kd.user_id = ?",
                "kd.deleted_at IS NULL",
                "kb.id = kd.knowledge_base_id",
                "kb.user_id = kd.user_id",
                "kb.deleted_at IS NULL"
        );
        assertThat(sql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("deletedAt", "deletedAt", "documentId", "userId");
    }
}
