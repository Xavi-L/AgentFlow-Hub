package com.agentflow.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;

/** Optional per-call JSON Schema constraint, independent of a provider SDK. */
public record LlmResponseSchema(String name, JsonNode schema) {
    public LlmResponseSchema {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Response schema name is invalid");
        }
        if (schema == null || !schema.isObject()
                || schema.toString().getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IllegalArgumentException("Response schema must be an object within 64 KiB");
        }
        schema = schema.deepCopy();
    }

    @Override
    public JsonNode schema() {
        return schema.deepCopy();
    }
}
