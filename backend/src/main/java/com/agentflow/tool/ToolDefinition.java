package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Runtime view of one persisted tool definition. The internal config is available only to the
 * executor and is never serialized directly by the HTTP layer.
 */
public record ToolDefinition(
        Long id,
        String toolCode,
        String name,
        String description,
        String type,
        JsonNode inputSchema,
        JsonNode outputSchema,
        JsonNode config,
        int timeoutMs,
        int retryCount,
        boolean requiresConfirmation,
        String permissionLevel,
        String status
) {
}
