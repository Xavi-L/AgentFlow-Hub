package com.agentflow.tool.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Parseable test bodies reach ToolRuntime even when arguments is absent, so rejection is logged. */
public record ToolTestRequest(JsonNode arguments) {
}
