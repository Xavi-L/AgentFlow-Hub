package com.agentflow.agent.configversion;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record AgentConfigVersionResponse(String configVersionId, String agentId, String schemaVersion,
        String hashAlgorithmVersion, String configHash, JsonNode config, OffsetDateTime createdAt) {
    public AgentConfigVersionResponse { config = config.deepCopy(); }
    @Override public JsonNode config() { return config.deepCopy(); }
}
