package com.agentflow.agent.binding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V17AgentExecutionBindingsMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V17__create_agent_execution_bindings.sql"
    );

    @Test
    void shouldCreateOnlyTheM4bDependenciesInDependencyOrder() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains(
                "UNIQUE (id, user_id)",
                "ADD COLUMN chunk_strategy_version VARCHAR(64)",
                "SET chunk_strategy_version = 'structured-token-v1'",
                "CREATE TABLE agent_knowledge_binding",
                "FOREIGN KEY (agent_id, user_id) REFERENCES agent_app (id, user_id)",
                "FOREIGN KEY (knowledge_base_id, user_id) REFERENCES knowledge_base (id, user_id)",
                "UNIQUE (agent_id, knowledge_base_id)",
                "CREATE TABLE agent_tool_binding",
                "FOREIGN KEY (tool_id) REFERENCES tool_definition (id)",
                "UNIQUE (agent_id, tool_id)"
        ).doesNotContain(
                "CREATE TABLE agent_task",
                "CREATE TABLE agent_step",
                "CREATE TABLE agent_task_event",
                "CREATE TABLE llm_call_log",
                "CREATE TABLE rag_retrieval_log"
        );
        assertThat(sql.indexOf("ADD CONSTRAINT uk_agent_app_id_user"))
                .isLessThan(sql.indexOf("CREATE TABLE agent_knowledge_binding"));
        assertThat(sql.indexOf("chunk_strategy_version"))
                .isLessThan(sql.indexOf("CREATE TABLE agent_knowledge_binding"));
    }
}
