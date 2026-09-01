package com.agentflow.agent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.agent.model.AgentApp;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.junit.jupiter.api.Test;

class AgentAppMapperTest {

    @Test
    void shouldLockTheSameIdOwnerAndLiveScopeBeforeMergingAnUpdate() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AgentAppMapper.class);

        String statementId = AgentAppMapper.class.getName() + ".selectVisibleOwnedByIdForUpdate";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();
        BoundSql sql = configuration.getMappedStatement(statementId).getBoundSql(Map.of(
                "agentId", 301L,
                "userId", 101L
        ));

        assertThat(sql.getSql()).contains(
                "FROM agent_app",
                "WHERE id = ?",
                "AND user_id = ?",
                "AND deleted_at IS NULL",
                "FOR UPDATE"
        ).doesNotContain(
                "status =",
                "JOIN",
                "agent_tool_binding",
                "agent_knowledge_binding"
        );
        assertThat(sql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("agentId", "userId");
    }

    @Test
    void shouldUpdateOnlyPublicConfigAndTimestampInsideTheSameOwnerLiveScope() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AgentAppMapper.class);
        AgentApp agent = updateRow();

        String statementId = AgentAppMapper.class.getName() + ".updateConfigOwned";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();
        BoundSql boundSql = configuration.getMappedStatement(statementId).getBoundSql(Map.of(
                "agentId", 301L,
                "userId", 101L,
                "agent", agent
        ));
        String sql = boundSql.getSql();
        String setClause = sql.substring(sql.indexOf("SET"), sql.indexOf("WHERE"));

        assertThat(sql).contains(
                "UPDATE agent_app",
                "WHERE id = ?",
                "AND user_id = ?",
                "AND deleted_at IS NULL"
        ).doesNotContain("JOIN", "DELETE FROM");
        assertThat(setClause).contains(
                "name = ?",
                "description = ?",
                "system_prompt = ?",
                "model_provider = ?",
                "model_name = ?",
                "temperature = ?",
                "top_p = ?",
                "max_steps = ?",
                "max_tool_calls = ?",
                "max_tokens = ?",
                "timeout_seconds = ?",
                "updated_at = ?"
        ).doesNotContain(
                "id =",
                "user_id =",
                "status =",
                "config =",
                "created_at =",
                "deleted_at ="
        );
        assertThat(boundSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly(
                        "agent.name",
                        "agent.description",
                        "agent.systemPrompt",
                        "agent.modelProvider",
                        "agent.modelName",
                        "agent.temperature",
                        "agent.topP",
                        "agent.maxSteps",
                        "agent.maxToolCalls",
                        "agent.maxTokens",
                        "agent.timeoutSeconds",
                        "agent.updatedAt",
                        "agentId",
                        "userId"
                );
    }

    @Test
    void shouldSoftDeleteOnlyInsideTheIdOwnerLiveScopeWithOneTimestampParameter() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AgentAppMapper.class);
        OffsetDateTime deletedAt = OffsetDateTime.parse("2026-09-01T10:30:00+08:00");

        String statementId = AgentAppMapper.class.getName() + ".softDeleteOwned";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();
        BoundSql boundSql = configuration.getMappedStatement(statementId).getBoundSql(Map.of(
                "agentId", 301L,
                "userId", 101L,
                "deletedAt", deletedAt
        ));
        String sql = boundSql.getSql();
        String setClause = sql.substring(sql.indexOf("SET"), sql.indexOf("WHERE"));

        assertThat(sql).contains(
                "UPDATE agent_app",
                "SET deleted_at = ?",
                "updated_at = ?",
                "WHERE id = ?",
                "AND user_id = ?",
                "AND deleted_at IS NULL"
        ).doesNotContain(
                "DELETE FROM",
                "FOR UPDATE",
                "status =",
                "JOIN",
                "tool_",
                "knowledge_",
                "task",
                "step",
                "trace"
        );
        assertThat(setClause).doesNotContain(
                "name =",
                "description =",
                "system_prompt =",
                "model_provider =",
                "model_name =",
                "temperature =",
                "top_p =",
                "max_steps =",
                "max_tool_calls =",
                "max_tokens =",
                "timeout_seconds =",
                "user_id =",
                "config =",
                "created_at ="
        );
        assertThat(boundSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("deletedAt", "deletedAt", "agentId", "userId");
    }

    @Test
    void shouldKeepIdOwnerAndLiveVisibilityInTheDetailSql() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AgentAppMapper.class);

        String statementId = AgentAppMapper.class.getName() + ".selectVisibleOwnedById";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();
        BoundSql sql = configuration.getMappedStatement(statementId).getBoundSql(Map.of(
                "agentId", 301L,
                "userId", 101L
        ));

        assertThat(sql.getSql()).contains(
                "FROM agent_app",
                "WHERE id = ?",
                "AND user_id = ?",
                "AND deleted_at IS NULL"
        ).doesNotContain(
                "status =",
                "JOIN",
                "UPDATE",
                "agent_tool_binding",
                "agent_knowledge_binding"
        );
        assertThat(sql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("agentId", "userId");
    }

    @Test
    void shouldProjectOnlyTheCompletePublicDetailColumns() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AgentAppMapper.class);

        BoundSql sql = configuration.getMappedStatement(
                AgentAppMapper.class.getName() + ".selectVisibleOwnedById"
        ).getBoundSql(Map.of("agentId", 301L, "userId", 101L));
        String projection = sql.getSql().substring(0, sql.getSql().indexOf("FROM agent_app"));

        assertThat(projection).contains(
                "id",
                "name",
                "description",
                "system_prompt",
                "model_provider",
                "model_name",
                "temperature",
                "top_p",
                "max_steps",
                "max_tool_calls",
                "max_tokens",
                "timeout_seconds",
                "status",
                "created_at",
                "updated_at"
        ).doesNotContain(
                "SELECT *",
                "user_id",
                "config",
                "deleted_at",
                "current_prompt_version_id",
                "knowledge_base_id",
                "tool_id"
        );
    }

    @Test
    void shouldKeepOwnerVisibilityAndNewestFirstOrderingInTheListSql() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AgentAppMapper.class);

        String statementId = AgentAppMapper.class.getName() + ".selectVisibleOwnedPage";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();
        BoundSql sql = configuration.getMappedStatement(statementId).getBoundSql(Map.of(
                "userId", 101L
        ));

        assertThat(sql.getSql()).contains(
                "FROM agent_app",
                "WHERE user_id = ?",
                "AND deleted_at IS NULL",
                "ORDER BY created_at DESC, id DESC"
        ).doesNotContain(
                "status =",
                "JOIN",
                "agent_tool_binding",
                "agent_knowledge_binding"
        );
        assertThat(sql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("userId");
    }

    @Test
    void shouldProjectOnlyPublicSummaryColumns() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AgentAppMapper.class);

        BoundSql sql = configuration.getMappedStatement(
                AgentAppMapper.class.getName() + ".selectVisibleOwnedPage"
        ).getBoundSql(Map.of("userId", 101L));
        String projection = sql.getSql().substring(0, sql.getSql().indexOf("FROM agent_app"));

        assertThat(projection).contains(
                "id",
                "name",
                "description",
                "model_provider",
                "model_name",
                "status",
                "created_at",
                "updated_at"
        ).doesNotContain(
                "SELECT *",
                "user_id",
                "system_prompt",
                "temperature",
                "top_p",
                "max_steps",
                "max_tool_calls",
                "max_tokens",
                "timeout_seconds",
                "config",
                "deleted_at"
        );
    }

    private static AgentApp updateRow() {
        AgentApp row = new AgentApp();
        row.setName("Updated Agent");
        row.setDescription("Updated description");
        row.setSystemPrompt("Updated prompt");
        row.setModelProvider("openai-compatible");
        row.setModelName("qwen3");
        row.setTemperature(new BigDecimal("0.3"));
        row.setTopP(new BigDecimal("0.9"));
        row.setMaxSteps(8);
        row.setMaxToolCalls(5);
        row.setMaxTokens(9_000);
        row.setTimeoutSeconds(180);
        row.setUpdatedAt(OffsetDateTime.parse("2026-09-01T10:10:00+08:00"));
        return row;
    }
}
