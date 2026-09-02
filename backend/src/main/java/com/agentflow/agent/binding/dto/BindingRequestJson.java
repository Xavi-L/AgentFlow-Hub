package com.agentflow.agent.binding.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Shared strict parser for the two V37 full-replacement binding requests. */
final class BindingRequestJson {
    private BindingRequestJson() {
    }

    static List<Long> readIds(JsonParser parser, String fieldName) throws java.io.IOException {
        JsonNode body = parser.getCodec().readTree(parser);
        if (body == null || !body.isObject()) {
            throw JsonMappingException.from(parser, "binding body must be a JSON object");
        }
        Iterator<String> fields = body.fieldNames();
        while (fields.hasNext()) {
            String candidate = fields.next();
            if (!fieldName.equals(candidate)) {
                throw JsonMappingException.from(parser, "binding does not allow field: " + candidate);
            }
        }
        JsonNode ids = body.get(fieldName);
        if (ids == null || !ids.isArray()) {
            throw JsonMappingException.from(parser, fieldName + " must be an array");
        }
        List<Long> result = new ArrayList<>();
        for (JsonNode id : ids) {
            if (!id.isTextual() || !id.textValue().matches("[1-9][0-9]{0,18}")) {
                throw JsonMappingException.from(parser, fieldName + " must contain positive BIGINT strings");
            }
            try {
                result.add(Long.parseLong(id.textValue()));
            } catch (NumberFormatException ex) {
                throw JsonMappingException.from(parser, fieldName + " contains an out-of-range BIGINT", ex);
            }
        }
        return List.copyOf(result);
    }
}
