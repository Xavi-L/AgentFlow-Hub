package com.agentflow.agent.task.repository;

import com.agentflow.agent.task.model.AgentTask;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** PostgreSQL state transitions for the M4C task root. */
@Mapper
public interface AgentTaskMapper {

    @Insert("""
            INSERT INTO agent_task (
                id, user_id, agent_id, client_request_id, request_fingerprint,
                status, phase, termination_reason, user_input, execution_snapshot,
                max_decision_turns, max_tool_calls, max_total_tokens, reserved_final_tokens,
                decision_turns_used, tool_calls_used, input_tokens, output_tokens, total_tokens,
                token_usage_quality, final_answer, citations, error_code, error_message,
                cancel_requested_at, started_at, completed_at, last_event_sequence,
                created_at, updated_at, version
            ) VALUES (
                #{task.id}, #{task.userId}, #{task.agentId}, #{task.clientRequestId},
                #{task.requestFingerprint}, #{task.status}, #{task.phase},
                #{task.terminationReason}, #{task.userInput},
                CAST(#{task.executionSnapshot,jdbcType=VARCHAR} AS JSONB),
                #{task.maxDecisionTurns}, #{task.maxToolCalls}, #{task.maxTotalTokens},
                #{task.reservedFinalTokens}, #{task.decisionTurnsUsed}, #{task.toolCallsUsed},
                #{task.inputTokens}, #{task.outputTokens}, #{task.totalTokens},
                #{task.tokenUsageQuality}, #{task.finalAnswer},
                CAST(#{task.citations,jdbcType=VARCHAR} AS JSONB),
                #{task.errorCode}, #{task.errorMessage}, #{task.cancelRequestedAt},
                #{task.startedAt}, #{task.completedAt}, #{task.lastEventSequence},
                #{task.createdAt}, #{task.updatedAt}, #{task.version}
            )
            """)
    int insertTask(@Param("task") AgentTask task);

    @Select("""
            SELECT *
            FROM agent_task
            WHERE user_id = #{userId}
              AND client_request_id = #{clientRequestId}
            """)
    AgentTask selectByUserAndClientRequestId(
            @Param("userId") long userId,
            @Param("clientRequestId") String clientRequestId
    );

    @Select("""
            SELECT *
            FROM agent_task
            WHERE id = #{taskId}
            """)
    AgentTask selectById(@Param("taskId") long taskId);

    @Select("""
            UPDATE agent_task
            SET status = 'RUNNING',
                phase = 'PREPARING',
                started_at = #{startedAt},
                updated_at = #{startedAt},
                version = version + 1
            WHERE id = #{taskId}
              AND status = 'QUEUED'
              AND cancel_requested_at IS NULL
            RETURNING *
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    AgentTask claimQueued(
            @Param("taskId") long taskId,
            @Param("startedAt") OffsetDateTime startedAt
    );

    @Update("""
            UPDATE agent_task
            SET phase = #{phase},
                updated_at = #{updatedAt},
                version = version + 1
            WHERE id = #{taskId}
              AND status = 'RUNNING'
              AND cancel_requested_at IS NULL
              AND phase <> #{phase}
            """)
    int changeRunningPhase(
            @Param("taskId") long taskId,
            @Param("phase") String phase,
            @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Update("""
            UPDATE agent_task
            SET status = 'FAILED',
                phase = NULL,
                termination_reason = 'SYSTEM_ERROR',
                error_code = 'TASK_DISPATCH_REJECTED',
                error_message = 'Task dispatch was rejected',
                completed_at = #{completedAt},
                updated_at = #{completedAt},
                version = version + 1
            WHERE id = #{taskId}
              AND status = 'QUEUED'
              AND cancel_requested_at IS NULL
            """)
    int failQueuedDispatch(
            @Param("taskId") long taskId,
            @Param("completedAt") OffsetDateTime completedAt
    );

    @Select("""
            UPDATE agent_task
            SET status = 'CANCELLED',
                phase = NULL,
                termination_reason = 'USER_CANCELLED',
                cancel_requested_at = #{cancelledAt},
                completed_at = #{cancelledAt},
                updated_at = #{cancelledAt},
                version = version + 1
            WHERE id = #{taskId}
              AND user_id = #{userId}
              AND status = 'QUEUED'
              AND cancel_requested_at IS NULL
            RETURNING *
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    AgentTask cancelQueuedOwned(
            @Param("taskId") long taskId,
            @Param("userId") long userId,
            @Param("cancelledAt") OffsetDateTime cancelledAt
    );

    @Select("""
            UPDATE agent_task
            SET cancel_requested_at = #{requestedAt},
                updated_at = #{requestedAt},
                version = version + 1
            WHERE id = #{taskId}
              AND user_id = #{userId}
              AND status = 'RUNNING'
              AND cancel_requested_at IS NULL
            RETURNING *
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    AgentTask requestRunningCancellationOwned(
            @Param("taskId") long taskId,
            @Param("userId") long userId,
            @Param("requestedAt") OffsetDateTime requestedAt
    );

    @Select("""
            SELECT *
            FROM agent_task
            WHERE id = #{taskId}
              AND user_id = #{userId}
            """)
    AgentTask selectOwnedById(
            @Param("taskId") long taskId,
            @Param("userId") long userId
    );

    @Update("""
            UPDATE agent_task
            SET status = 'COMPLETED',
                phase = NULL,
                termination_reason = #{terminationReason},
                decision_turns_used = #{decisionTurnsUsed},
                tool_calls_used = #{toolCallsUsed},
                input_tokens = #{inputTokens},
                output_tokens = #{outputTokens},
                total_tokens = #{totalTokens},
                token_usage_quality = #{tokenUsageQuality},
                final_answer = #{finalAnswer},
                citations = CAST(#{citations,jdbcType=VARCHAR} AS JSONB),
                error_code = NULL,
                error_message = NULL,
                completed_at = #{completedAt},
                updated_at = #{completedAt},
                version = version + 1
            WHERE id = #{taskId}
              AND status = 'RUNNING'
              AND cancel_requested_at IS NULL
            """)
    int completeRunning(
            @Param("taskId") long taskId,
            @Param("terminationReason") String terminationReason,
            @Param("decisionTurnsUsed") int decisionTurnsUsed,
            @Param("toolCallsUsed") int toolCallsUsed,
            @Param("inputTokens") int inputTokens,
            @Param("outputTokens") int outputTokens,
            @Param("totalTokens") int totalTokens,
            @Param("tokenUsageQuality") String tokenUsageQuality,
            @Param("finalAnswer") String finalAnswer,
            @Param("citations") String citations,
            @Param("completedAt") OffsetDateTime completedAt
    );

    @Update("""
            UPDATE agent_task
            SET status = 'FAILED',
                phase = NULL,
                termination_reason = #{terminationReason},
                decision_turns_used = #{decisionTurnsUsed},
                tool_calls_used = #{toolCallsUsed},
                input_tokens = #{inputTokens},
                output_tokens = #{outputTokens},
                total_tokens = #{totalTokens},
                token_usage_quality = #{tokenUsageQuality},
                final_answer = NULL,
                citations = '[]'::jsonb,
                error_code = #{errorCode},
                error_message = #{errorMessage},
                completed_at = #{completedAt},
                updated_at = #{completedAt},
                version = version + 1
            WHERE id = #{taskId}
              AND status = 'RUNNING'
              AND cancel_requested_at IS NULL
            """)
    int failRunning(
            @Param("taskId") long taskId,
            @Param("terminationReason") String terminationReason,
            @Param("decisionTurnsUsed") int decisionTurnsUsed,
            @Param("toolCallsUsed") int toolCallsUsed,
            @Param("inputTokens") int inputTokens,
            @Param("outputTokens") int outputTokens,
            @Param("totalTokens") int totalTokens,
            @Param("tokenUsageQuality") String tokenUsageQuality,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") OffsetDateTime completedAt
    );

    @Update("""
            UPDATE agent_task
            SET status = 'TIMED_OUT',
                phase = NULL,
                termination_reason = 'DEADLINE_EXCEEDED',
                decision_turns_used = #{decisionTurnsUsed},
                tool_calls_used = #{toolCallsUsed},
                input_tokens = #{inputTokens},
                output_tokens = #{outputTokens},
                total_tokens = #{totalTokens},
                token_usage_quality = #{tokenUsageQuality},
                final_answer = NULL,
                citations = '[]'::jsonb,
                error_code = NULL,
                error_message = NULL,
                completed_at = #{completedAt},
                updated_at = #{completedAt},
                version = version + 1
            WHERE id = #{taskId}
              AND status = 'RUNNING'
              AND cancel_requested_at IS NULL
            """)
    int timeOutRunning(
            @Param("taskId") long taskId,
            @Param("decisionTurnsUsed") int decisionTurnsUsed,
            @Param("toolCallsUsed") int toolCallsUsed,
            @Param("inputTokens") int inputTokens,
            @Param("outputTokens") int outputTokens,
            @Param("totalTokens") int totalTokens,
            @Param("tokenUsageQuality") String tokenUsageQuality,
            @Param("completedAt") OffsetDateTime completedAt
    );

    @Update("""
            UPDATE agent_task
            SET status = 'CANCELLED',
                phase = NULL,
                termination_reason = 'USER_CANCELLED',
                decision_turns_used = #{decisionTurnsUsed},
                tool_calls_used = #{toolCallsUsed},
                input_tokens = #{inputTokens},
                output_tokens = #{outputTokens},
                total_tokens = #{totalTokens},
                token_usage_quality = #{tokenUsageQuality},
                final_answer = NULL,
                citations = '[]'::jsonb,
                error_code = NULL,
                error_message = NULL,
                completed_at = #{completedAt},
                updated_at = #{completedAt},
                version = version + 1
            WHERE id = #{taskId}
              AND status = 'RUNNING'
              AND cancel_requested_at IS NOT NULL
            """)
    int finishRunningCancellation(
            @Param("taskId") long taskId,
            @Param("decisionTurnsUsed") int decisionTurnsUsed,
            @Param("toolCallsUsed") int toolCallsUsed,
            @Param("inputTokens") int inputTokens,
            @Param("outputTokens") int outputTokens,
            @Param("totalTokens") int totalTokens,
            @Param("tokenUsageQuality") String tokenUsageQuality,
            @Param("completedAt") OffsetDateTime completedAt
    );
}
