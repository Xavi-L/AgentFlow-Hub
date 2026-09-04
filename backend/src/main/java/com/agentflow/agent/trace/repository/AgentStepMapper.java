package com.agentflow.agent.trace.repository;

import com.agentflow.agent.trace.model.AgentStepRecord;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** PostgreSQL persistence and conditional lifecycle transitions for task steps. */
@Mapper
public interface AgentStepMapper {

    @Select("""
            SELECT id
            FROM agent_task
            WHERE id = #{taskId}
              AND status = 'RUNNING'
            FOR UPDATE
            """)
    Long lockRunningTask(@Param("taskId") long taskId);

    @Select("""
            SELECT COALESCE(MAX(step_index), -1) + 1
            FROM agent_step
            WHERE task_id = #{taskId}
            """)
    Integer selectNextStepIndexWhileTaskLocked(@Param("taskId") long taskId);

    @Insert("""
            INSERT INTO agent_step (
                id, task_id, step_index, step_type, status, title, summary,
                error_code, error_message, started_at, ended_at, latency_ms, created_at
            ) VALUES (
                #{id}, #{taskId}, #{stepIndex}, #{stepType}, #{status}, #{title},
                CAST(#{summaryJson,jdbcType=VARCHAR} AS JSONB), #{errorCode}, #{errorMessage},
                #{startedAt}, #{endedAt}, #{latencyMs}, #{createdAt}
            )
            """)
    int insertStep(AgentStepRecord record);

    @Update("""
            UPDATE agent_step
            SET status = 'SUCCESS',
                summary = CAST(#{summaryJson,jdbcType=VARCHAR} AS JSONB),
                error_code = NULL,
                error_message = NULL,
                ended_at = #{endedAt},
                latency_ms = CAST(FLOOR(EXTRACT(EPOCH FROM (#{endedAt} - started_at)) * 1000) AS BIGINT)
            WHERE id = #{stepId}
              AND task_id = #{taskId}
              AND status = 'RUNNING'
              AND #{endedAt} >= started_at
            """)
    int completeRunning(
            @Param("taskId") long taskId,
            @Param("stepId") long stepId,
            @Param("summaryJson") String summaryJson,
            @Param("endedAt") OffsetDateTime endedAt
    );

    @Update("""
            UPDATE agent_step
            SET status = 'FAILED',
                summary = '{}'::jsonb,
                error_code = #{errorCode},
                error_message = #{errorMessage},
                ended_at = #{endedAt},
                latency_ms = CAST(FLOOR(EXTRACT(EPOCH FROM (#{endedAt} - started_at)) * 1000) AS BIGINT)
            WHERE id = #{stepId}
              AND task_id = #{taskId}
              AND status = 'RUNNING'
              AND #{endedAt} >= started_at
            """)
    int failRunning(
            @Param("taskId") long taskId,
            @Param("stepId") long stepId,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("endedAt") OffsetDateTime endedAt
    );

    @Select("""
            SELECT id, task_id, step_index, step_type, status, title,
                   summary::text AS summary_json, error_code, error_message,
                   started_at, ended_at, latency_ms, created_at
            FROM agent_step
            WHERE task_id = #{taskId}
            ORDER BY step_index ASC, id ASC
            """)
    @Options(useCache = false)
    List<AgentStepRecord> selectByTaskIdOrdered(@Param("taskId") long taskId);
}
