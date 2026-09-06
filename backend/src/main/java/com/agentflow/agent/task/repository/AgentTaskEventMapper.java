package com.agentflow.agent.task.repository;

import com.agentflow.agent.task.model.AgentTaskEvent;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Durable event writes and ordered reads; only TaskEventAppender allocates sequences. */
@Mapper
public interface AgentTaskEventMapper {

    @Select("""
            UPDATE agent_task
            SET last_event_sequence = last_event_sequence + 1
            WHERE id = #{taskId}
            RETURNING last_event_sequence
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long incrementAndGetSequence(@Param("taskId") long taskId);

    @Insert("""
            INSERT INTO agent_task_event (
                id, task_id, sequence_no, event_type, payload, created_at
            ) VALUES (
                #{event.id}, #{event.taskId}, #{event.sequenceNo}, #{event.eventType},
                CAST(#{event.payload,jdbcType=VARCHAR} AS JSONB), #{event.createdAt}
            )
            """)
    int insertEvent(@Param("event") AgentTaskEvent event);

    @Select("""
            SELECT id, task_id, sequence_no, event_type, payload::text AS payload, created_at
            FROM agent_task_event
            WHERE task_id = #{taskId}
              AND sequence_no > #{afterSequence}
            ORDER BY sequence_no ASC, id ASC
            """)
    @Options(useCache = false)
    List<AgentTaskEvent> selectByTaskIdAfterSequence(
            @Param("taskId") long taskId, @Param("afterSequence") long afterSequence
    );
}
