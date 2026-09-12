package com.agentflow.agent.configversion;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentConfigVersionMapper {
    String COLUMNS = "id,user_id,agent_id,schema_version,hash_algorithm_version,config_hash,config_json::text AS config_json,created_at";

    @Insert("""
            INSERT INTO agent_config_version(id,user_id,agent_id,schema_version,hash_algorithm_version,config_hash,config_json,created_at)
            VALUES(#{v.id},#{v.userId},#{v.agentId},#{v.schemaVersion},#{v.hashAlgorithmVersion},#{v.configHash},
                   CAST(#{v.configJson} AS jsonb),#{v.createdAt})
            ON CONFLICT (user_id,agent_id,schema_version,config_hash) DO NOTHING
            """)
    int insert(@Param("v") AgentConfigVersion version);

    @Select("SELECT " + COLUMNS + " FROM agent_config_version WHERE user_id=#{userId} AND agent_id=#{agentId} AND id=#{id}")
    AgentConfigVersion selectOwned(@Param("userId") long userId, @Param("agentId") long agentId, @Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM agent_config_version WHERE user_id=#{userId} AND agent_id=#{agentId}"
            + " AND schema_version='agent-config-v1' AND config_hash=#{hash}")
    AgentConfigVersion selectContent(@Param("userId") long userId, @Param("agentId") long agentId, @Param("hash") String hash);

    @Select("SELECT " + COLUMNS + " FROM agent_config_version WHERE user_id=#{userId} AND agent_id=#{agentId}"
            + " ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<AgentConfigVersion> selectPage(@Param("userId") long userId, @Param("agentId") long agentId,
            @Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM agent_config_version WHERE user_id=#{userId} AND agent_id=#{agentId}")
    long count(@Param("userId") long userId, @Param("agentId") long agentId);
}
