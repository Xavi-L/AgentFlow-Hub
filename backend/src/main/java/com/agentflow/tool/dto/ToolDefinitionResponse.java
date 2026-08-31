package com.agentflow.tool.dto;

import com.agentflow.tool.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

/** Safe public projection of a global tool definition; internal config and audit fields stay out. */
public record ToolDefinitionResponse(
        String id,
        String toolCode,
        String name,
        String description,
        String type,
        JsonNode inputSchema,
        JsonNode outputSchema,
        int timeoutMs,
        int retryCount,
        boolean requiresConfirmation,
        String permissionLevel,
        String status
) {
    public static ToolDefinitionResponse from(ToolDefinition tool) {
        return new ToolDefinitionResponse(
                tool.id().toString(),
                tool.toolCode(),
                tool.name(),
                tool.description(),
                tool.type(),
                tool.inputSchema(),
                tool.outputSchema(),
                tool.timeoutMs(),
                tool.retryCount(),
                tool.requiresConfirmation(),
                tool.permissionLevel(),
                tool.status()
        );
    }
}
