package com.agentflow.agent.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V18AgentTaskMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V18__create_agent_task_and_event.sql"
    );

    @Test
    void shouldCreateTheCompleteM4cTaskAndEventContract() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains(
                "CREATE TABLE agent_task",
                "client_request_id VARCHAR(128) NOT NULL",
                "request_fingerprint CHAR(64) NOT NULL",
                "execution_snapshot JSONB NOT NULL",
                "reserved_final_tokens INT NOT NULL",
                "last_event_sequence BIGINT NOT NULL DEFAULT 0",
                "version INT NOT NULL DEFAULT 0",
                "FOREIGN KEY (agent_id, user_id) REFERENCES agent_app (id, user_id)",
                "UNIQUE (user_id, client_request_id)",
                "max_tool_calls < max_decision_turns",
                "total_tokens = input_tokens + output_tokens",
                "jsonb_typeof(execution_snapshot) = 'object'",
                "jsonb_typeof(citations) = 'array'",
                "CREATE TABLE agent_task_event",
                "UNIQUE (task_id, sequence_no)",
                "jsonb_typeof(payload) = 'object'",
                "'TASK_CREATED'",
                "'TASK_TIMED_OUT'"
        );
        assertThat(sql).contains("'TIMED_OUT'", "'DEADLINE_EXCEEDED'")
                .doesNotContain("'TIMEOUT'");
    }
}
