package com.agentflow.agent.repository;

import com.agentflow.agent.model.AgentApp;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Agent root-resource persistence with an explicit current-owner list scope. */
@Mapper
public interface AgentAppMapper extends BaseMapper<AgentApp> {

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
