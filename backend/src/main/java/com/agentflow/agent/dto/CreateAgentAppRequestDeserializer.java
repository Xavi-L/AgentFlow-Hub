package com.agentflow.agent.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Set;

/** Local V30 allowlist that keeps every server-owned Agent field out of JSON input. */
public final class CreateAgentAppRequestDeserializer extends StdDeserializer<CreateAgentAppRequest> {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "name",
            "description",
            "systemPrompt",
            "modelProvider",
            "modelName",
            "temperature",
            "topP",
            "maxSteps",
            "maxToolCalls",
            "maxTokens",
            "timeoutSeconds"
    );

    public CreateAgentAppRequestDeserializer() {
        super(CreateAgentAppRequest.class);
    }

    @Override
    public CreateAgentAppRequest deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        JsonNode body = parser.getCodec().readTree(parser);
        if (body == null || !body.isObject()) {
            throw JsonMappingException.from(parser, "agent creation body must be a JSON object");
        }

        Iterator<String> fieldNames = body.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(fieldName)) {
                throw JsonMappingException.from(
                        parser,
                        "agent creation does not allow field: " + fieldName
                );
            }
        }

        return new CreateAgentAppRequest(
                optionalText(body, "name", parser),
                optionalText(body, "description", parser),
                optionalText(body, "systemPrompt", parser),
                optionalText(body, "modelProvider", parser),
                optionalText(body, "modelName", parser),
                optionalDecimal(body, "temperature", parser),
                optionalDecimal(body, "topP", parser),
                optionalInteger(body, "maxSteps", parser),
                optionalInteger(body, "maxToolCalls", parser),
                optionalInteger(body, "maxTokens", parser),
                optionalInteger(body, "timeoutSeconds", parser)
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

    private static BigDecimal optionalDecimal(JsonNode body, String fieldName, JsonParser parser)
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
