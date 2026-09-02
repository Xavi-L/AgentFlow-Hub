package com.agentflow.agent.binding.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.Test;

class AgentBindingMapperTest {

    @Test
    void shouldValidateKnowledgeBasesInsideOwnerLiveActiveScope() {
        MybatisConfiguration configuration = configuration(AgentKnowledgeBindingMapper.class);
        BoundSql sql = configuration.getMappedStatement(
                AgentKnowledgeBindingMapper.class.getName() + ".selectBindableOwnedKnowledgeBaseIds"
        ).getBoundSql(Map.of("userId", 101L, "knowledgeBaseIds", List.of(201L, 202L)));

        assertThat(sql.getSql()).contains(
                "FROM knowledge_base",
                "user_id = ?",
                "deleted_at IS NULL",
                "status = 'ACTIVE'",
                "id IN"
        );
    }

    @Test
    void shouldResolveOnlyReadyCurrentGenerationDocumentsFromBoundActiveKnowledge() {
        MybatisConfiguration configuration = configuration(AgentKnowledgeBindingMapper.class);
        BoundSql sql = configuration.getMappedStatement(
                AgentKnowledgeBindingMapper.class.getName() + ".selectReadyDocumentGenerations"
        ).getBoundSql(Map.of("agentId", 301L, "userId", 101L));

        assertThat(sql.getSql()).contains(
                "FROM agent_knowledge_binding",
                "kb.deleted_at IS NULL",
                "kb.status = 'ACTIVE'",
                "kd.deleted_at IS NULL",
                "kd.parse_status = 'COMPLETED'",
                "kc.vector_generation = kd.vector_generation",
                "BOOL_AND(kc.vectorization_status = 'COMPLETED')",
                "COUNT(DISTINCT kc.chunk_strategy_version) = 1",
                "akb.agent_id = ?",
                "akb.user_id = ?"
        );
    }

    @Test
    void shouldAllowOnlyTheTwoV01BuiltinTools() {
        MybatisConfiguration configuration = configuration(AgentToolBindingMapper.class);
        BoundSql sql = configuration.getMappedStatement(
                AgentToolBindingMapper.class.getName() + ".selectBindableV01ToolIds"
        ).getBoundSql(Map.of("toolIds", List.of(270000000000000001L, 290000000000000001L)));

        assertThat(sql.getSql()).contains(
                "FROM tool_definition",
                "tool_code IN ('order_query', 'payment_log_query')",
                "type = 'BUILTIN'",
                "status = 'ACTIVE'",
                "deleted_at IS NULL"
        );
    }

    @Test
    void shouldScopeReplacementDeletesByAgentAndOwner() {
        MybatisConfiguration knowledge = configuration(AgentKnowledgeBindingMapper.class);
        MybatisConfiguration tools = configuration(AgentToolBindingMapper.class);

        assertThat(knowledge.getMappedStatement(
                AgentKnowledgeBindingMapper.class.getName() + ".deleteOwnedByAgent"
        ).getBoundSql(Map.of("agentId", 301L, "userId", 101L)).getSql()).contains(
                "DELETE FROM agent_knowledge_binding",
                "agent_id = ?",
                "user_id = ?"
        );
        assertThat(tools.getMappedStatement(
                AgentToolBindingMapper.class.getName() + ".deleteOwnedByAgent"
        ).getBoundSql(Map.of("agentId", 301L, "userId", 101L)).getSql()).contains(
                "DELETE FROM agent_tool_binding",
                "agent_id = ?",
                "user_id = ?"
        );
    }

    private static MybatisConfiguration configuration(Class<?> mapper) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(mapper);
        return configuration;
    }
}
