package com.agentflow.agent.trace.repository;

import com.agentflow.agent.trace.model.LlmCallLogRecord;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LlmCallLogMapper {

    @Insert("""
            INSERT INTO llm_call_log (
                id, task_id, step_id, call_type, provider, requested_model, resolved_model,
                request_snapshot, response_text, finish_reason, provider_request_id,
                input_tokens, output_tokens, total_tokens, usage_quality, latency_ms,
                status, error_code, error_message, created_at
            ) VALUES (
                #{id}, #{taskId}, #{stepId}, #{callType}, #{provider}, #{requestedModel},
                #{resolvedModel}, CAST(#{requestSnapshotJson,jdbcType=VARCHAR} AS JSONB),
                #{responseText}, #{finishReason}, #{providerRequestId}, #{inputTokens},
                #{outputTokens}, #{totalTokens}, #{usageQuality}, #{latencyMs}, #{status},
                #{errorCode}, #{errorMessage}, #{createdAt}
            )
            """)
    int insertCall(LlmCallLogRecord record);

    @Select("""
            SELECT id, task_id, step_id, call_type, provider, requested_model, resolved_model,
                   request_snapshot::text AS request_snapshot_json, response_text,
                   finish_reason, provider_request_id, input_tokens, output_tokens, total_tokens,
                   usage_quality, latency_ms, status, error_code, error_message, created_at
            FROM llm_call_log
            WHERE task_id = #{taskId}
            ORDER BY created_at ASC, id ASC
            """)
    @Options(useCache = false)
    List<LlmCallLogRecord> selectByTaskIdOrdered(@Param("taskId") long taskId);
}
