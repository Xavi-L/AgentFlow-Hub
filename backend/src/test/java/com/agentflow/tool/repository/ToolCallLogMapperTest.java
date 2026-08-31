package com.agentflow.tool.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.tool.model.ToolCallLogRecord;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.time.OffsetDateTime;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;

class ToolCallLogMapperTest {

    @Test
    void shouldInsertJsonSnapshotsAndNullableStandaloneParentIds() {
        MybatisConfiguration configuration = configuration();
        MappedStatement statement = configuration.getMappedStatement(
                ToolCallLogMapper.class.getName() + ".insertCall"
        );
        BoundSql sql = statement.getBoundSql(record());

        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.INSERT);
        assertThat(sql.getSql()).contains(
                "INSERT INTO tool_call_log",
                "task_id",
                "step_id",
                "tool_code",
                "tool_name",
                "CAST(? AS JSONB)",
                "retry_count",
                "started_at",
                "finished_at"
        );
        assertThat(sql.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly(
                        "id",
                        "taskId",
                        "stepId",
                        "toolId",
                        "toolCode",
                        "toolName",
                        "argumentsJson",
                        "resultJson",
                        "status",
                        "retryCount",
                        "latencyMs",
                        "errorCode",
                        "errorMessage",
                        "startedAt",
                        "finishedAt",
                        "createdAt"
                );
    }

    @Test
    void shouldAllowOnlyRunningRowsToBecomeTerminal() {
        MybatisConfiguration configuration = configuration();
        MappedStatement statement = configuration.getMappedStatement(
                ToolCallLogMapper.class.getName() + ".updateRunningToTerminal"
        );
        BoundSql sql = statement.getBoundSql(record());

        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.UPDATE);
        assertThat(sql.getSql()).contains(
                "UPDATE tool_call_log",
                "SET result = CAST(? AS JSONB)",
                "status = ?",
                "latency_ms = ?",
                "finished_at = ?",
                "WHERE id = ?",
                "status = 'RUNNING'"
        );
        assertThat(sql.getSql()).doesNotContain("retry_count =");
    }

    private static MybatisConfiguration configuration() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(ToolCallLogMapper.class);
        return configuration;
    }

    private static ToolCallLogRecord record() {
        ToolCallLogRecord record = new ToolCallLogRecord();
        record.setId(701L);
        record.setToolId(270000000000000001L);
        record.setToolCode("order_query");
        record.setToolName("Order Query");
        record.setArgumentsJson("{\"orderNo\":\"order_1024\"}");
        record.setResultJson("{\"success\":true}");
        record.setStatus("SUCCESS");
        record.setRetryCount(0);
        record.setLatencyMs(5);
        record.setStartedAt(OffsetDateTime.parse("2026-05-01T04:00:00Z"));
        record.setFinishedAt(OffsetDateTime.parse("2026-05-01T04:00:00.005Z"));
        record.setCreatedAt(OffsetDateTime.parse("2026-05-01T04:00:00Z"));
        return record;
    }
}
