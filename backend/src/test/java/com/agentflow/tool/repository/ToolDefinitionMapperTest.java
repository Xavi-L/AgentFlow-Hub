package com.agentflow.tool.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.tool.model.ToolDefinitionRow;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;

class ToolDefinitionMapperTest {

    @Test
    void shouldScopeSingleDefinitionLoadingToActiveAndNotDeleted() {
        MybatisConfiguration configuration = configuration();
        MappedStatement statement = configuration.getMappedStatement(
                ToolDefinitionMapper.class.getName() + ".selectActiveById"
        );
        BoundSql sql = statement.getBoundSql(Map.of("toolId", 270000000000000001L));

        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        assertThat(sql.getSql()).contains(
                "FROM tool_definition",
                "WHERE id = ?",
                "status = 'ACTIVE'",
                "deleted_at IS NULL",
                "input_schema::text AS input_schema_json",
                "config::text AS config_json"
        );
        assertThat(sql.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("toolId");
        assertThat(statement.getResultMaps().getFirst().getType()).isEqualTo(ToolDefinitionRow.class);
        assertThat(statement.getResultMaps().getFirst().getResultMappings())
                .extracting(ResultMapping::getProperty)
                .contains(
                        "id",
                        "toolCode",
                        "inputSchemaJson",
                        "outputSchemaJson",
                        "configJson",
                        "timeoutMs",
                        "retryCount",
                        "requiresConfirmation",
                        "permissionLevel"
                );
    }

    @Test
    void shouldListOnlyGlobalVisibleDefinitionsInStableOrderWithoutOwnerInput() {
        MybatisConfiguration configuration = configuration();
        MappedStatement statement = configuration.getMappedStatement(
                ToolDefinitionMapper.class.getName() + ".selectAllActive"
        );
        BoundSql sql = statement.getBoundSql(null);

        assertThat(sql.getSql()).contains(
                "FROM tool_definition",
                "WHERE status = 'ACTIVE'",
                "deleted_at IS NULL",
                "ORDER BY created_at ASC, id ASC"
        );
        assertThat(sql.getSql()).doesNotContain("user_id", "owner_id", "INSERT", "UPDATE", "DELETE FROM");
        assertThat(sql.getParameterMappings()).isEmpty();
    }

    private static MybatisConfiguration configuration() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(ToolDefinitionMapper.class);
        return configuration;
    }
}
