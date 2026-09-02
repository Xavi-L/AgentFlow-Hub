package com.agentflow.agent.binding.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Local allowlist that accepts only knowledgeBaseIds as string-encoded BIGINT values. */
public final class ReplaceAgentKnowledgeBindingsRequestDeserializer
        extends StdDeserializer<ReplaceAgentKnowledgeBindingsRequest> {
    public ReplaceAgentKnowledgeBindingsRequestDeserializer() {
        super(ReplaceAgentKnowledgeBindingsRequest.class);
    }

    @Override
    public ReplaceAgentKnowledgeBindingsRequest deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        return new ReplaceAgentKnowledgeBindingsRequest(
                BindingRequestJson.readIds(parser, "knowledgeBaseIds")
        );
    }
}
