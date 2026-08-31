package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V13ToolMigrationContractTest {

    @Test
    void shouldCreateOnlyTheRegistryAndCallLogWithTheRequiredIndexes() throws Exception {
        String sql = migrationSql();

        assertThat(sql).contains(
                "CREATE TABLE tool_definition",
                "CREATE TABLE tool_call_log",
                "CONSTRAINT uk_tool_definition_tool_code UNIQUE (tool_code)",
                "CREATE INDEX idx_tool_call_task",
                "ON tool_call_log (task_id)",
                "CREATE INDEX idx_tool_call_step",
                "ON tool_call_log (step_id)",
                "CREATE INDEX idx_tool_call_tool",
                "ON tool_call_log (tool_id, created_at DESC)"
        );
        assertThat(sql).doesNotContain(
                "CREATE TABLE agent_task",
                "CREATE TABLE agent_step",
                "FOREIGN KEY (task_id)",
                "FOREIGN KEY (step_id)",
                "ALTER TABLE mock_order",
                "ALTER TABLE mock_payment_log"
        );
    }

    @Test
    void shouldSeedExactlyTheNarrowOrderQueryDefinition() throws Exception {
        String sql = migrationSql();

        assertThat(count(sql, "INSERT INTO tool_definition")).isEqualTo(1);
        assertThat(sql).contains(
                "270000000000000001",
                "'order_query'",
                "'BUILTIN'",
                "\"handler\": \"orderQueryTool\"",
                "\"readonly\": true",
                "3000",
                "'MEDIUM'",
                "'ACTIVE'",
                "\"required\": [\"orderNo\"]",
                "\"additionalProperties\": false",
                "\"minLength\": 1",
                "\"maxLength\": 64"
        );
        assertThat(sql).doesNotContain(
                "'payment_log_query'",
                "'report_generate'",
                "'ticket_query'",
                "'knowledge_search'"
        );
    }

    @Test
    void shouldConstrainRunningAndTerminalLogShapesWhileOnlyReservingPendingAndTimeout() throws Exception {
        String sql = migrationSql();

        assertThat(sql).contains(
                "'PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT', 'REJECTED'",
                "CONSTRAINT ck_tool_call_log_retry_count_nonnegative",
                "CONSTRAINT ck_tool_call_log_latency_nonnegative",
                "CONSTRAINT ck_tool_call_log_terminal_fields",
                "status = 'RUNNING'",
                "status = 'SUCCESS'",
                "status IN ('FAILED', 'TIMEOUT', 'REJECTED')"
        );
    }

    private static String migrationSql() throws IOException {
        try (InputStream input = V13ToolMigrationContractTest.class.getResourceAsStream(
                "/db/migration/V13__create_tool_definition_and_tool_call_log.sql"
        )) {
            if (input == null) {
                throw new IOException("V13 migration resource is missing");
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
