package com.agentflow.knowledge.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/** Local allowlist so V9/V10 stay strict even though the application's global Jackson mode is lenient. */
public final class ChatTestRequestDeserializer extends StdDeserializer<ChatTestRequest> {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "query",
            "topK",
            "maxContextTokens",
            "maxAnswerTokens"
    );

    public ChatTestRequestDeserializer() {
        super(ChatTestRequest.class);
    }

    @Override
    public ChatTestRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode body = parser.getCodec().readTree(parser);
        if (body == null || !body.isObject()) {
            throw JsonMappingException.from(parser, "chat request body must be a JSON object");
        }

        Iterator<String> fieldNames = body.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(fieldName)) {
                throw JsonMappingException.from(parser, "chat request does not allow field: " + fieldName);
            }
        }

        return new ChatTestRequest(
                optionalText(body, "query", parser),
                optionalInteger(body, "topK", parser),
                optionalInteger(body, "maxContextTokens", parser),
                optionalInteger(body, "maxAnswerTokens", parser)
        );
    }

    private static String optionalText(JsonNode body, String fieldName, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = body.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw JsonMappingException.from(parser, fieldName + " must be a string");
        }
        return value.textValue();
    }

    private static Integer optionalInteger(JsonNode body, String fieldName, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = body.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw JsonMappingException.from(parser, fieldName + " must be an integer");
        }
        return value.intValue();
    }
}
