package com.agentflow.agent.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Local V32 allowlist for partial updates of public Agent configuration only. */
public final class UpdateAgentAppRequestDeserializer extends StdDeserializer<UpdateAgentAppRequest> {

    public UpdateAgentAppRequestDeserializer() {
        super(UpdateAgentAppRequest.class);
    }

    @Override
    public UpdateAgentAppRequest deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        JsonNode body = parser.getCodec().readTree(parser);
        if (body == null || !body.isObject()) {
            throw JsonMappingException.from(parser, "agent update body must be a JSON object");
        }

        Set<String> presentFields = new HashSet<>();
        Iterator<String> fieldNames = body.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!UpdateAgentAppRequest.CONFIG_FIELDS.contains(fieldName)) {
                throw JsonMappingException.from(
                        parser,
                        "agent update does not allow field: " + fieldName
                );
            }
            presentFields.add(fieldName);
        }

        return new UpdateAgentAppRequest(
                presentFields,
                nullableText(body, "name", parser),
                nullableText(body, "description", parser),
                nullableText(body, "systemPrompt", parser),
                nullableText(body, "modelProvider", parser),
                nullableText(body, "modelName", parser),
                nullableDecimal(body, "temperature", parser),
                nullableDecimal(body, "topP", parser),
                nullableInteger(body, "maxSteps", parser),
                nullableInteger(body, "maxToolCalls", parser),
                nullableInteger(body, "maxTokens", parser),
                nullableInteger(body, "timeoutSeconds", parser)
        );
    }

    private static String nullableText(JsonNode body, String fieldName, JsonParser parser)
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

    private static BigDecimal nullableDecimal(JsonNode body, String fieldName, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = body.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw JsonMappingException.from(parser, fieldName + " must be a number");
        }
        return value.decimalValue();
    }

    private static Integer nullableInteger(JsonNode body, String fieldName, JsonParser parser)
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
