package com.agentflow.agent.binding.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Local allowlist that accepts only toolIds as string-encoded BIGINT values. */
public final class ReplaceAgentToolBindingsRequestDeserializer
        extends StdDeserializer<ReplaceAgentToolBindingsRequest> {
    public ReplaceAgentToolBindingsRequestDeserializer() {
        super(ReplaceAgentToolBindingsRequest.class);
    }

    @Override
    public ReplaceAgentToolBindingsRequest deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        return new ReplaceAgentToolBindingsRequest(BindingRequestJson.readIds(parser, "toolIds"));
    }
}
