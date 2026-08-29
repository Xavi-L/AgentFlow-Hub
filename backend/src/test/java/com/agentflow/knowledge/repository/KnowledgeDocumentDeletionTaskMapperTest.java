package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.time.OffsetDateTime;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.junit.jupiter.api.Test;

/** SQL-shape checks for V24's server-scoped durable deletion task. */
class KnowledgeDocumentDeletionTaskMapperTest {

    @Test
    void shouldLockOnlyAnUnfinishedTaskWithinTheCompleteOwnerScope() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(KnowledgeDocumentDeletionTaskMapper.class);

        BoundSql sql = configuration.getMappedStatement(
                KnowledgeDocumentDeletionTaskMapper.class.getName()
                        + ".selectIncompleteByDocumentScopeForUpdate"
        ).getBoundSql(scope());

        assertThat(sql.getSql()).contains(
                "FROM knowledge_document_deletion_task",
                "document_id = ?",
                "knowledge_base_id = ?",
                "user_id = ?",
                "completed_at IS NULL",
                "FOR UPDATE"
        );
        assertThat(sql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("documentId", "knowledgeBaseId", "userId");
    }

    @Test
    void shouldRecordOnlyControlledStepCompletionAndRetryFields() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(KnowledgeDocumentDeletionTaskMapper.class);

        Map<String, Object> parameters = new java.util.LinkedHashMap<>(scope());
        parameters.put("taskId", 401L);
        parameters.put("completedAt", OffsetDateTime.parse("2026-08-30T12:00:00+08:00"));
        BoundSql completionSql = configuration.getMappedStatement(
                KnowledgeDocumentDeletionTaskMapper.class.getName() + ".markChunksDeletedAndCompleted"
        ).getBoundSql(parameters);
        assertThat(completionSql.getSql()).contains(
                "chunks_deleted_at = ?",
                "completed_at = ?",
                "vectors_deleted_at IS NOT NULL",
                "source_deleted_at IS NOT NULL",
                "chunks_deleted_at IS NULL",
                "completed_at IS NULL"
        );

        parameters.put("failureSummary", "Vector deletion failed");
        parameters.put("failedAt", OffsetDateTime.parse("2026-08-30T12:05:00+08:00"));
        BoundSql failureSql = configuration.getMappedStatement(
                KnowledgeDocumentDeletionTaskMapper.class.getName() + ".recordFailure"
        ).getBoundSql(parameters);
        assertThat(failureSql.getSql()).contains(
                "failure_summary = ?",
                "retry_count = retry_count + 1",
                "last_failed_at = ?",
                "completed_at IS NULL"
        );
    }

    private static Map<String, Object> scope() {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("documentId", 301L);
        values.put("knowledgeBaseId", 201L);
        values.put("userId", 101L);
        return values;
    }
}
