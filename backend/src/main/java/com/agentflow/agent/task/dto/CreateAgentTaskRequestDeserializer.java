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
        boolean inputPresent = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME
                    || !"userInput".equals(parser.currentName()) || inputPresent) {
                throw JsonMappingException.from(parser, "task creation allows only one userInput field");
            }
            inputPresent = true;
            JsonToken valueToken = parser.nextToken();
            if (valueToken == JsonToken.VALUE_STRING) {
                userInput = parser.getText();
            } else if (valueToken != JsonToken.VALUE_NULL) {
                throw JsonMappingException.from(parser, "userInput must be a string");
            }
        }
        // This DTO is the complete HTTP request body; reject concatenated JSON documents
        // locally rather than changing Jackson behavior for unrelated API requests.
        if (parser.nextToken() != null) {
            throw JsonMappingException.from(parser, "task creation body must contain exactly one JSON object");
        }
        return new CreateAgentTaskRequest(userInput);
    }
}
