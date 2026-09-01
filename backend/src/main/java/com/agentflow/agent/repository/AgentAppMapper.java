package com.agentflow.agent.repository;

import com.agentflow.agent.model.AgentApp;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Agent root-resource persistence with explicit current-owner visibility scopes. */
@Mapper
public interface AgentAppMapper extends BaseMapper<AgentApp> {

    /**
     * Selects the complete public configuration only inside the requested ID, owner, and
     * non-deleted scope. Internal owner/config/deletion fields are not projected.
     */
    @Select("""
            SELECT id,
                   name,
                   description,
                   system_prompt,
                   model_provider,
                   model_name,
                   temperature,
                   top_p,
                   max_steps,
                   max_tool_calls,
                   max_tokens,
                   timeout_seconds,
                   status,
                   created_at,
                   updated_at
            FROM agent_app
            WHERE id = #{agentId}
              AND user_id = #{userId}
              AND deleted_at IS NULL
            """)
    AgentApp selectVisibleOwnedById(
            @Param("agentId") Long agentId,
            @Param("userId") Long userId
    );

    /**
     * The owner and live-row predicates are part of the SQL itself. The projection deliberately
     * excludes system_prompt, config, deleted_at, and execution budgets from list reads.
     */
    @Select("""
            SELECT id,
                   name,
                   description,
                   model_provider,
                   model_name,
                   status,
                   created_at,
                   updated_at
            FROM agent_app
            WHERE user_id = #{userId}
              AND deleted_at IS NULL
            ORDER BY created_at DESC, id DESC
            """)
    IPage<AgentApp> selectVisibleOwnedPage(
            Page<AgentApp> page,
            @Param("userId") Long userId
    );
}
