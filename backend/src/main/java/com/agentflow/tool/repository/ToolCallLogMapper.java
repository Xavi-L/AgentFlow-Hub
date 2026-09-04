package com.agentflow.tool.repository;

import com.agentflow.tool.model.ToolCallLogRecord;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tool-call lifecycle persistence plus the V39 task-scoped internal Trace query. */
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

    @Select("""
            SELECT id, task_id, step_id, tool_id, tool_code, tool_name,
                   arguments::text AS arguments_json, result::text AS result_json,
                   status, retry_count, latency_ms, error_code, error_message,
                   started_at, finished_at, created_at
            FROM tool_call_log
            WHERE task_id = #{taskId}
            ORDER BY created_at ASC, id ASC
            """)
    @Options(useCache = false)
    List<ToolCallLogRecord> selectByTaskIdOrdered(@Param("taskId") long taskId);
}
