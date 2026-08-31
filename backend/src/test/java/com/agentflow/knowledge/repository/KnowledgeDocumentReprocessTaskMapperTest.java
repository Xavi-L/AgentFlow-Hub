package com.agentflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.Test;

/** SQL-shape evidence that V25 never identifies a task by document scope alone. */
class KnowledgeDocumentReprocessTaskMapperTest {
    @Test
    void shouldLockTheSingleActiveTaskWithinTheFullOwnerScope() {
        MybatisConfiguration configuration = configuration();
        BoundSql sql = configuration.getMappedStatement(
                KnowledgeDocumentReprocessTaskMapper.class.getName()
                        + ".selectActiveByDocumentScopeForUpdate"
        ).getBoundSql(scope());

        assertThat(sql.getSql()).contains(
                "knowledge_document_reprocess_task",
                "document_id = ?",
                "knowledge_base_id = ?",
                "user_id = ?",
                "completed_at IS NULL",
                "FOR UPDATE"
        );
    }

    @Test
    void shouldFenceEveryStepByTaskIdFullScopeAndPriorCleanupStatus() {
        MybatisConfiguration configuration = configuration();
        Map<String, Object> parameters = new LinkedHashMap<>(scope());
        parameters.put("taskId", 501L);
        parameters.put("completedAt", OffsetDateTime.parse("2026-08-31T00:00:00+08:00"));
        BoundSql vectorSql = configuration.getMappedStatement(
                KnowledgeDocumentReprocessTaskMapper.class.getName() + ".markVectorsDeleted"
        ).getBoundSql(parameters);
        assertThat(vectorSql.getSql()).contains(
                "id = ?", "document_id = ?", "knowledge_base_id = ?", "user_id = ?",
                "cleanup_status = 'VECTOR_DELETING'", "vectors_deleted_at IS NULL", "completed_at IS NULL"
        );

        BoundSql finalSql = configuration.getMappedStatement(
                KnowledgeDocumentReprocessTaskMapper.class.getName() + ".markCompleted"
        ).getBoundSql(parameters);
        assertThat(finalSql.getSql()).contains(
                "cleanup_status = 'COMPLETED'",
                "chunks_deleted_at = ?",
                "completed_at = ?",
                "cleanup_status = 'READY_TO_FINALIZE'",
                "vectors_deleted_at IS NOT NULL"
        );
    }

    private static MybatisConfiguration configuration() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(KnowledgeDocumentReprocessTaskMapper.class);
        return configuration;
    }

    private static Map<String, Object> scope() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("documentId", 301L);
        values.put("knowledgeBaseId", 201L);
        values.put("userId", 101L);
        return values;
    }
}
