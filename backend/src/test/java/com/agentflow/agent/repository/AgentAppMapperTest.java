package com.agentflow.agent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.junit.jupiter.api.Test;

class AgentAppMapperTest {

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
}
