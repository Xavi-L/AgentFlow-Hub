package com.agentflow.agent.task.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V22TaskRecoveryMigrationContractTest {
    @Test
    void appendsNullableRecoveryAndOnlyTheInterruptedFailedRecordLatencyException() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V22__add_task_recovery_metadata.sql"));
        assertThat(sql).contains("ADD COLUMN recovery_metadata JSONB",
                "recovery_metadata IS NULL OR jsonb_typeof(recovery_metadata) = 'object'",
                "DROP CONSTRAINT ck_agent_step_lifecycle", "DROP CONSTRAINT ck_tool_call_log_terminal_fields",
                "status = 'FAILED' AND ended_at IS NOT NULL",
                "latency_ms IS NOT NULL OR error_code = 'TASK_RESTART_INTERRUPTED'",
                "latency_ms IS NOT NULL OR (status = 'FAILED' AND error_code = 'TASK_RESTART_INTERRUPTED')",
                "status = 'SUCCESS' AND started_at IS NOT NULL AND finished_at IS NOT NULL AND latency_ms IS NOT NULL");
        assertThat(sql).doesNotContain("UPDATE agent_task", "DEFAULT '{}'", "CREATE TABLE");
    }
}
