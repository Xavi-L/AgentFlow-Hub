package com.agentflow.agent.task.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Local allowlist prevents coercion, duplicate input fields, and client-owned lifecycle fields. */
public final class CreateAgentTaskRequestDeserializer extends StdDeserializer<CreateAgentTaskRequest> {
    public CreateAgentTaskRequestDeserializer() {
        super(CreateAgentTaskRequest.class);
    }

    @Override
    public CreateAgentTaskRequest deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        if (!parser.isExpectedStartObjectToken()) {
            throw JsonMappingException.from(parser, "task creation body must be a JSON object");
        }
        String userInput = null;
        Long configVersionId = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME || !seen.add(parser.currentName()))
                throw JsonMappingException.from(parser, "task creation fields must be unique");
            String field = parser.currentName();
            JsonToken token = parser.nextToken();
            if ("userInput".equals(field)) {
                if (token == JsonToken.VALUE_STRING) userInput = parser.getText();
                else if (token != JsonToken.VALUE_NULL)
                    throw JsonMappingException.from(parser, "userInput must be a string");
            } else if ("configVersionId".equals(field)) {
                if (token == JsonToken.VALUE_STRING) {
                    String value = parser.getText();
                    if (!value.matches("[1-9][0-9]*"))
                        throw JsonMappingException.from(parser, "configVersionId must be a positive decimal string");
                    try { configVersionId = Long.parseLong(value); }
                    catch (NumberFormatException e) {
                        throw JsonMappingException.from(parser, "configVersionId is outside the supported range");
                    }
                } else if (token != JsonToken.VALUE_NULL)
                    throw JsonMappingException.from(parser, "configVersionId must be a positive decimal string");
            } else throw JsonMappingException.from(parser, "task creation field is unsupported");
        }
        // This DTO is the complete HTTP request body; reject concatenated JSON documents
        // locally rather than changing Jackson behavior for unrelated API requests.
        if (parser.nextToken() != null) {
            throw JsonMappingException.from(parser, "task creation body must contain exactly one JSON object");
        }
        return new CreateAgentTaskRequest(userInput, configVersionId);
    }
}
