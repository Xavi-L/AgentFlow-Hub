package com.agentflow.agent.task.recovery;

import com.agentflow.agent.task.model.AgentTask;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Startup-only persistence. Every settlement write joins the caller's physical transaction. */
@Mapper
public interface TaskRecoveryMapper {
    @Select("""
            SELECT id FROM agent_task
            WHERE status IN ('QUEUED', 'RUNNING') AND id > #{afterId}
            ORDER BY id LIMIT #{limit}
            """)
    @Options(useCache = false)
    List<Long> selectCandidateIds(@Param("afterId") long afterId, @Param("limit") int limit);

    @Select("SELECT EXISTS (SELECT 1 FROM agent_task WHERE status IN ('QUEUED', 'RUNNING'))")
    @Options(useCache = false)
    boolean hasCandidates();

    @Select("SELECT * FROM agent_task WHERE id = #{taskId} FOR UPDATE")
    @Options(useCache = false)
    AgentTask lockTask(@Param("taskId") long taskId);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM agent_step WHERE task_id = #{taskId})
                OR EXISTS (SELECT 1 FROM llm_call_log WHERE task_id = #{taskId})
                OR EXISTS (SELECT 1 FROM rag_retrieval_log WHERE task_id = #{taskId})
                OR EXISTS (SELECT 1 FROM tool_call_log WHERE task_id = #{taskId})
                OR EXISTS (SELECT 1 FROM agent_task_event
                           WHERE task_id = #{taskId} AND event_type <> 'TASK_CREATED')
            """)
    boolean hasExecutionEvidence(@Param("taskId") long taskId);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM agent_task_event WHERE task_id = #{taskId}
                AND event_type IN ('ANSWER_CHUNK', 'TASK_COMPLETED', 'TASK_FAILED', 'TASK_CANCELLED', 'TASK_TIMED_OUT'))
            """)
    boolean hasTerminalPublicationEvidence(@Param("taskId") long taskId);

    @Select("SELECT EXISTS (SELECT 1 FROM tool_call_log WHERE task_id = #{taskId} AND status = 'PENDING')")
    boolean hasPendingToolCalls(@Param("taskId") long taskId);

    @Select("SELECT COUNT(*) FROM tool_call_log WHERE task_id = #{taskId}")
    int countToolCalls(@Param("taskId") long taskId);

    @Update("""
            UPDATE agent_step
            SET status = 'FAILED', error_code = 'TASK_RESTART_INTERRUPTED',
                error_message = 'Local execution process interrupted; external outcome unconfirmed',
                ended_at = #{recoveredAt}
            WHERE task_id = #{taskId} AND status = 'RUNNING'
            """)
    int finishRunningSteps(@Param("taskId") long taskId, @Param("recoveredAt") OffsetDateTime recoveredAt);

    @Update("""
            UPDATE tool_call_log
            SET status = 'FAILED', error_code = 'TASK_RESTART_INTERRUPTED',
                error_message = 'Local execution process interrupted; external outcome unconfirmed',
                result = COALESCE(result, '{"errorCode":"TASK_RESTART_INTERRUPTED","externalOutcome":"UNKNOWN"}'::jsonb),
                finished_at = #{recoveredAt}
            WHERE task_id = #{taskId} AND status = 'RUNNING'
            """)
    int finishRunningToolCalls(@Param("taskId") long taskId, @Param("recoveredAt") OffsetDateTime recoveredAt);

    @Update("""
            UPDATE agent_task
            SET status = #{status}, phase = NULL, termination_reason = #{terminationReason},
                input_tokens = #{inputTokens}, output_tokens = #{outputTokens}, total_tokens = #{totalTokens},
                token_usage_quality = 'UNKNOWN', final_answer = NULL, citations = '[]'::jsonb,
                error_code = #{errorCode}, error_message = #{errorMessage},
                completed_at = #{recoveredAt}, updated_at = #{recoveredAt}, version = version + 1,
                recovery_metadata = CAST(#{recoveryMetadata,jdbcType=VARCHAR} AS JSONB)
            WHERE id = #{taskId} AND status = #{previousStatus} AND version = #{previousVersion}
              AND status IN ('QUEUED', 'RUNNING')
              AND cancel_requested_at IS NOT DISTINCT FROM #{cancelRequestedAt,jdbcType=TIMESTAMP_WITH_TIMEZONE}
            """)
    int settleTask(@Param("taskId") long taskId, @Param("previousStatus") String previousStatus,
                   @Param("previousVersion") int previousVersion,
                   @Param("cancelRequestedAt") OffsetDateTime cancelRequestedAt,
                   @Param("status") String status, @Param("terminationReason") String terminationReason,
                   @Param("inputTokens") int inputTokens, @Param("outputTokens") int outputTokens,
                   @Param("totalTokens") int totalTokens, @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage, @Param("recoveredAt") OffsetDateTime recoveredAt,
                   @Param("recoveryMetadata") String recoveryMetadata);
}
