package com.agentflow.agent.configversion;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** First publication API only accepts one empty JSON object, without coercion or extra documents. */
@JsonDeserialize(using = PublishAgentConfigVersionRequest.Deserializer.class)
public record PublishAgentConfigVersionRequest() {
    public static class Deserializer extends StdDeserializer<PublishAgentConfigVersionRequest> {
        public Deserializer() { super(PublishAgentConfigVersionRequest.class); }
        @Override public PublishAgentConfigVersionRequest deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (!parser.isExpectedStartObjectToken() || parser.nextToken() != JsonToken.END_OBJECT
                    || parser.nextToken() != null)
                throw JsonMappingException.from(parser, "Publication body must be one empty object");
            return new PublishAgentConfigVersionRequest();
        }
    }
}
