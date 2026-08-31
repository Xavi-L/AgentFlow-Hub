package com.agentflow.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V16AgentAppMigrationContractTest {

    @Test
    void shouldCreateOnlyTheAgentRootResourceWithoutFutureM4TablesOrPromptVersionId() throws Exception {
        String sql = migrationSql();

        assertThat(count(sql, "CREATE TABLE")).isEqualTo(1);
        assertThat(sql).contains("CREATE TABLE agent_app");
        assertThat(sql).doesNotContain(
                "current_prompt_version_id",
                "CREATE TABLE agent_prompt_version",
                "CREATE TABLE agent_knowledge_binding",
                "CREATE TABLE agent_tool_binding",
                "CREATE TABLE agent_task",
                "CREATE TABLE agent_step",
                "CREATE TABLE llm_call_log",
                "CREATE TABLE rag_retrieval_log"
        );
    }

    @Test
    void shouldFreezeTheServerOwnedColumnsDefaultsAndForeignKey() throws Exception {
        String sql = migrationSql();

        assertThat(sql).contains(
                "user_id BIGINT NOT NULL",
                "name VARCHAR(128) NOT NULL",
                "description TEXT",
                "system_prompt TEXT NOT NULL",
                "model_provider VARCHAR(64) NOT NULL",
                "model_name VARCHAR(128) NOT NULL",
                "temperature NUMERIC(4,3) NOT NULL DEFAULT 0.2",
                "top_p NUMERIC(4,3) NOT NULL DEFAULT 0.8",
                "max_steps INT NOT NULL DEFAULT 6",
                "max_tool_calls INT NOT NULL DEFAULT 4",
                "max_tokens INT NOT NULL DEFAULT 8000",
                "timeout_seconds INT NOT NULL DEFAULT 120",
                "status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'",
                "config JSONB NOT NULL DEFAULT '{}'::jsonb",
                "FOREIGN KEY (user_id) REFERENCES app_user (id)"
        );
    }

    @Test
    void shouldConstrainTextBudgetsStatusAndJsonShapeAtTheDatabaseBoundary() throws Exception {
        String sql = migrationSql();

        assertThat(sql).contains(
                "char_length(btrim(name)) > 0",
                "char_length(description) <= 4000",
                "char_length(btrim(system_prompt)) > 0",
                "char_length(system_prompt) <= 20000",
                "char_length(btrim(model_provider)) > 0",
                "char_length(btrim(model_name)) > 0",
                "temperature BETWEEN 0 AND 2",
                "top_p > 0 AND top_p <= 1",
                "max_steps BETWEEN 1 AND 20",
                "max_tool_calls BETWEEN 0 AND 20 AND max_tool_calls <= max_steps",
                "max_tokens BETWEEN 256 AND 100000",
                "timeout_seconds BETWEEN 1 AND 600",
                "status IN ('ACTIVE', 'DISABLED')",
                "jsonb_typeof(config) = 'object'"
        );
    }

    @Test
    void shouldIndexTheExactVisibleOwnerSortWithoutForcingUniqueNames() throws Exception {
        String sql = migrationSql();

        assertThat(sql).contains(
                "CREATE INDEX idx_agent_app_user_created",
                "ON agent_app (user_id, created_at DESC, id DESC)",
                "WHERE deleted_at IS NULL"
        ).doesNotContain(
                "UNIQUE (user_id, name)",
                "UNIQUE (name)",
                "idx_agent_app_user_status"
        );
    }

    private static String migrationSql() throws IOException {
        try (InputStream input = V16AgentAppMigrationContractTest.class.getResourceAsStream(
                "/db/migration/V16__create_agent_app.sql"
        )) {
            if (input == null) {
                throw new IOException("V16 migration resource is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
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
