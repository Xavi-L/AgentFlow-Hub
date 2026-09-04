package com.agentflow.agent.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class V19AgentExecutionTraceMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V19__create_agent_execution_trace.sql"
    );

    @Test
    void shouldCreateTheFourTaskScopedTraceTablesAndStepKeys() throws IOException {
        String sql = normalizedMigrationSql();

        assertThat(count(sql, "CREATE TABLE")).isEqualTo(4);
        assertThat(sql).contains(
                "CREATE TABLE agent_step",
                "CREATE TABLE llm_call_log",
                "CREATE TABLE rag_retrieval_log",
                "CREATE TABLE rag_retrieval_hit",
                "CONSTRAINT uk_agent_step_task_index",
                "UNIQUE (task_id, step_index)",
                "CONSTRAINT uk_agent_step_id_task",
                "UNIQUE (id, task_id)",
                "CONSTRAINT uk_agent_step_id_task_type",
                "UNIQUE (id, task_id, step_type)",
                "FOREIGN KEY (task_id) REFERENCES agent_task (id)",
                "jsonb_typeof(summary) = 'object'",
                "step_index >= 0",
                "latency_ms >= 0"
        );
    }

    @Test
    void shouldBindLlmCallTypeAndEverySpecializedLogToTheSameTaskStep() throws IOException {
        String sql = normalizedMigrationSql();

        assertThat(sql).contains(
                "expected_step_type VARCHAR(32) GENERATED ALWAYS AS",
                "WHEN 'DECISION' THEN 'LLM_DECISION'",
                "WHEN 'FINAL_GENERATION' THEN 'LLM_FINAL_GENERATION'",
                "CONSTRAINT fk_llm_call_log_step_task",
                "FOREIGN KEY (step_id, task_id)",
                "REFERENCES agent_step (id, task_id)",
                "CONSTRAINT fk_llm_call_log_step_task_type",
                "FOREIGN KEY (step_id, task_id, expected_step_type)",
                "REFERENCES agent_step (id, task_id, step_type)",
                "CONSTRAINT fk_rag_retrieval_log_step_task",
                "CONSTRAINT fk_rag_retrieval_hit_retrieval",
                "FOREIGN KEY (retrieval_id) REFERENCES rag_retrieval_log (id)"
        );
    }

    @Test
    void shouldAttachToolCallsOnlyAsStandaloneOrACompleteTaskStepPair() throws IOException {
        String sql = normalizedMigrationSql();

        assertThat(sql).contains(
                "ALTER TABLE tool_call_log",
                "CONSTRAINT ck_tool_call_log_task_step_pair",
                "task_id IS NULL AND step_id IS NULL",
                "task_id IS NOT NULL AND step_id IS NOT NULL",
                "CONSTRAINT fk_tool_call_log_step_task",
                "FOREIGN KEY (step_id, task_id)",
                "REFERENCES agent_step (id, task_id)",
                "CREATE INDEX idx_tool_call_log_task_created_id",
                "ON tool_call_log (task_id, created_at, id)",
                "CREATE INDEX idx_llm_call_log_task_created_id",
                "CREATE INDEX idx_rag_retrieval_log_task_created_id"
        );
    }

    @Test
    void shouldKeepRagSourceIdsAsSnapshotsAndAvoidASecondEventSequenceAllocator() throws IOException {
        String sql = normalizedMigrationSql();

        assertThat(sql).contains(
                "chunk_id_snapshot BIGINT NOT NULL",
                "document_id_snapshot BIGINT NOT NULL",
                "knowledge_base_id_snapshot BIGINT NOT NULL",
                "vector_generation BIGINT NOT NULL",
                "content_snapshot TEXT NOT NULL",
                "metadata_snapshot JSONB NOT NULL"
        ).doesNotContain(
                "REFERENCES knowledge_chunk",
                "REFERENCES knowledge_document",
                "REFERENCES knowledge_base",
                "FOREIGN KEY (chunk_id_snapshot)",
                "FOREIGN KEY (document_id_snapshot)",
                "FOREIGN KEY (knowledge_base_id_snapshot)"
        );

        assertThat(Pattern.compile("(?i)MAX\\s*\\(\\s*sequence")
                .matcher(sql)
                .find()).isFalse();
    }

    private static String normalizedMigrationSql() throws IOException {
        return Files.readString(MIGRATION).replaceAll("\\s+", " ").trim();
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
