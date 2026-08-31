package com.agentflow.tool.repository;

import com.agentflow.tool.model.ToolCallLogRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/** Write-only V27 persistence boundary for tool-call lifecycle rows. */
@Mapper
public interface ToolCallLogMapper {

    @Insert("""
            INSERT INTO tool_call_log (
                id,
                task_id,
                step_id,
                tool_id,
                tool_code,
                tool_name,
                arguments,
                result,
                status,
                retry_count,
                latency_ms,
                error_code,
                error_message,
                started_at,
                finished_at,
                created_at
            ) VALUES (
                #{id},
                #{taskId},
                #{stepId},
                #{toolId},
                #{toolCode},
                #{toolName},
                CAST(#{argumentsJson,jdbcType=VARCHAR} AS JSONB),
                CAST(#{resultJson,jdbcType=VARCHAR} AS JSONB),
                #{status},
                #{retryCount},
                #{latencyMs},
                #{errorCode},
                #{errorMessage},
                #{startedAt},
                #{finishedAt},
                #{createdAt}
            )
            """)
    int insertCall(ToolCallLogRecord record);

    @Update("""
            UPDATE tool_call_log
            SET result = CAST(#{resultJson,jdbcType=VARCHAR} AS JSONB),
                status = #{status},
                latency_ms = #{latencyMs},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                finished_at = #{finishedAt}
            WHERE id = #{id}
              AND status = 'RUNNING'
            """)
    int updateRunningToTerminal(ToolCallLogRecord record);
}
