package com.agentflow.tool.repository;

import com.agentflow.tool.model.ToolDefinitionRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** Database reads for global ACTIVE, non-deleted tool definitions. */
@Mapper
public interface ToolDefinitionMapper {

    @Results(id = "toolDefinitionResult", value = {
            @Result(id = true, property = "id", column = "id"),
            @Result(property = "toolCode", column = "tool_code"),
            @Result(property = "inputSchemaJson", column = "input_schema_json"),
            @Result(property = "outputSchemaJson", column = "output_schema_json"),
            @Result(property = "configJson", column = "config_json"),
            @Result(property = "timeoutMs", column = "timeout_ms"),
            @Result(property = "retryCount", column = "retry_count"),
            @Result(property = "requiresConfirmation", column = "requires_confirmation"),
            @Result(property = "permissionLevel", column = "permission_level")
    })
    @Select("""
            SELECT id,
                   tool_code,
                   name,
                   description,
                   type,
                   input_schema::text AS input_schema_json,
                   output_schema::text AS output_schema_json,
                   config::text AS config_json,
                   timeout_ms,
                   retry_count,
                   requires_confirmation,
                   permission_level,
                   status
            FROM tool_definition
            WHERE id = #{toolId}
              AND status = 'ACTIVE'
              AND deleted_at IS NULL
            """)
    ToolDefinitionRow selectActiveById(@Param("toolId") Long toolId);

    @ResultMap("toolDefinitionResult")
    @Select("""
            SELECT id,
                   tool_code,
                   name,
                   description,
                   type,
                   input_schema::text AS input_schema_json,
                   output_schema::text AS output_schema_json,
                   config::text AS config_json,
                   timeout_ms,
                   retry_count,
                   requires_confirmation,
                   permission_level,
                   status
            FROM tool_definition
            WHERE status = 'ACTIVE'
              AND deleted_at IS NULL
            ORDER BY created_at ASC, id ASC
            """)
    List<ToolDefinitionRow> selectAllActive();
}
