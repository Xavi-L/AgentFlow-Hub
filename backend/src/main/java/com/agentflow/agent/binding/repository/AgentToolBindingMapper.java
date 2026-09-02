package com.agentflow.agent.binding.repository;

import com.agentflow.agent.binding.model.AgentToolBinding;
import com.agentflow.agent.binding.model.BoundToolDefinitionRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** Owner-scoped persistence and safe V0.1 snapshot reads for Agent tool bindings. */
@Mapper
public interface AgentToolBindingMapper extends BaseMapper<AgentToolBinding> {

    @Select("""
            SELECT tool_id
            FROM agent_tool_binding
            WHERE agent_id = #{agentId}
              AND user_id = #{userId}
              AND enabled = TRUE
            ORDER BY priority ASC, tool_id ASC
            """)
    List<Long> selectBoundToolIds(
            @Param("agentId") Long agentId,
            @Param("userId") Long userId
    );

    @Select("""
            <script>
            SELECT id
            FROM tool_definition
            WHERE id IN
              <foreach collection="toolIds" item="toolId" open="(" separator="," close=")">
                #{toolId}
              </foreach>
              AND tool_code IN ('order_query', 'payment_log_query')
              AND type = 'BUILTIN'
              AND status = 'ACTIVE'
              AND deleted_at IS NULL
            ORDER BY id ASC
            </script>
            """)
    List<Long> selectBindableV01ToolIds(@Param("toolIds") List<Long> toolIds);

    @Delete("""
            DELETE FROM agent_tool_binding
            WHERE agent_id = #{agentId}
              AND user_id = #{userId}
            """)
    int deleteOwnedByAgent(
            @Param("agentId") Long agentId,
            @Param("userId") Long userId
    );

    @Results({
            @Result(property = "toolId", column = "tool_id"),
            @Result(property = "toolCode", column = "tool_code"),
            @Result(property = "inputSchemaJson", column = "input_schema_json"),
            @Result(property = "configJson", column = "config_json"),
            @Result(property = "timeoutMs", column = "timeout_ms")
    })
    @Select("""
            SELECT td.id AS tool_id,
                   td.tool_code,
                   td.name,
                   td.description,
                   td.input_schema::text AS input_schema_json,
                   td.config::text AS config_json,
                   td.timeout_ms
            FROM agent_tool_binding atb
            JOIN tool_definition td
              ON td.id = atb.tool_id
             AND td.tool_code IN ('order_query', 'payment_log_query')
             AND td.type = 'BUILTIN'
             AND td.status = 'ACTIVE'
             AND td.deleted_at IS NULL
            WHERE atb.agent_id = #{agentId}
              AND atb.user_id = #{userId}
              AND atb.enabled = TRUE
            ORDER BY atb.priority ASC, td.id ASC
            """)
    List<BoundToolDefinitionRow> selectSnapshotTools(
            @Param("agentId") Long agentId,
            @Param("userId") Long userId
    );
}
