package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** Standard result envelope shared by successful execution and durable failure snapshots. */
public record ToolExecutionResult(
        boolean success,
        String toolCode,
        String summary,
        JsonNode data,
        String errorCode,
        String errorMessage,
        int latencyMs
) {
    public static ToolExecutionResult success(
            String toolCode,
            String summary,
            JsonNode data,
            int latencyMs
    ) {
        return new ToolExecutionResult(
                true,
                toolCode,
                summary,
                data,
                null,
                null,
                latencyMs
        );
    }

    public static ToolExecutionResult failure(
            String toolCode,
            String errorCode,
            String errorMessage,
            int latencyMs
    ) {
        return new ToolExecutionResult(
                false,
                toolCode,
                null,
                null,
                errorCode,
                errorMessage,
                latencyMs
        );
    }
}
