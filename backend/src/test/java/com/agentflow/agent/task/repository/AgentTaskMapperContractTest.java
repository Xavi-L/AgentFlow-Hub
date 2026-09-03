package com.agentflow.agent.task.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.Test;

class AgentTaskMapperContractTest {

    @Test
    void shouldUseConditionalClaimAndTerminalUpdates() {
        MybatisConfiguration configuration = configuration(AgentTaskMapper.class);

        BoundSql claim = configuration.getMappedStatement(
                AgentTaskMapper.class.getName() + ".claimQueued"
        ).getBoundSql(Map.of("taskId", 1L, "startedAt", "now"));
        BoundSql complete = configuration.getMappedStatement(
                AgentTaskMapper.class.getName() + ".completeRunning"
        ).getBoundSql(Map.ofEntries(
                Map.entry("taskId", 1L),
                Map.entry("terminationReason", "ANSWERED"),
                Map.entry("decisionTurnsUsed", 1),
                Map.entry("toolCallsUsed", 0),
                Map.entry("inputTokens", 10),
                Map.entry("outputTokens", 5),
                Map.entry("totalTokens", 15),
                Map.entry("tokenUsageQuality", "EXACT"),
                Map.entry("finalAnswer", "answer"),
                Map.entry("citations", "[]"),
                Map.entry("completedAt", "now")
        ));

        assertThat(claim.getSql()).contains(
                "status = 'RUNNING'",
                "phase = 'PREPARING'",
                "status = 'QUEUED'",
                "cancel_requested_at IS NULL",
                "version = version + 1",
                "RETURNING *"
        );
        assertThat(complete.getSql()).contains(
                "status = 'COMPLETED'",
                "status = 'RUNNING'",
                "cancel_requested_at IS NULL",
                "version = version + 1"
        );
    }

    @Test
    void shouldAllocateEventSequenceFromTheTaskRowWithoutMaxScan() {
        MybatisConfiguration configuration = configuration(AgentTaskEventMapper.class);
        String sql = configuration.getMappedStatement(
                AgentTaskEventMapper.class.getName() + ".incrementAndGetSequence"
        ).getBoundSql(Map.of("taskId", 1L)).getSql();

        assertThat(sql).contains(
                "UPDATE agent_task",
                "last_event_sequence = last_event_sequence + 1",
                "WHERE id = ?",
                "RETURNING last_event_sequence"
        ).doesNotContainIgnoringCase("max(");
    }

    private static MybatisConfiguration configuration(Class<?> mapper) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(mapper);
        return configuration;
    }
}
