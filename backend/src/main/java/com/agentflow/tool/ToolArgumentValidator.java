package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deliberately small V27 JSON-Schema validator. It supports object, string and integer types,
 * required fields, string lengths, numeric bounds and additionalProperties=false. Unsupported
 * composition, format and custom keywords are outside this slice.
 */
@Component
public class ToolArgumentValidator {

    public void validate(JsonNode schema, JsonNode arguments) {
        validateNode("arguments", requireSchemaObject(schema), arguments);
    }

    private void validateNode(String path, JsonNode schema, JsonNode value) {
        JsonNode typeNode = schema.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            throw invalidDefinition(path + " schema must declare one supported textual type");
        }

        switch (typeNode.textValue()) {
            case "object" -> validateObject(path, schema, value);
            case "string" -> validateString(path, schema, value);
            case "integer" -> validateInteger(path, schema, value);
            default -> throw invalidDefinition(
                    path + " schema type is unsupported in V27: " + typeNode.textValue()
            );
        }
    }

    private void validateObject(String path, JsonNode schema, JsonNode value) {
        if (value == null || !value.isObject()) {
            throw invalidArguments(path + " must be an object");
        }

        JsonNode propertiesNode = schema.get("properties");
        if (propertiesNode != null && !propertiesNode.isObject()) {
            throw invalidDefinition(path + ".properties must be an object");
        }

        Set<String> requiredFields = requiredFields(path, schema.get("required"));
        for (String requiredField : requiredFields) {
            JsonNode requiredValue = value.get(requiredField);
            if (requiredValue == null || requiredValue.isNull()) {
                throw invalidArguments(path + "." + requiredField + " is required");
            }
        }

        boolean rejectAdditional = false;
        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null) {
            if (!additionalProperties.isBoolean()) {
                throw invalidDefinition(path + ".additionalProperties must be boolean in V27");
            }
            rejectAdditional = !additionalProperties.booleanValue();
        }

        Iterator<Map.Entry<String, JsonNode>> fields = value.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode propertySchema = propertiesNode == null ? null : propertiesNode.get(field.getKey());
            if (propertySchema == null) {
                if (rejectAdditional) {
                    throw invalidArguments(path + "." + field.getKey() + " is not allowed");
                }
                continue;
            }
            validateNode(path + "." + field.getKey(), requireSchemaObject(propertySchema), field.getValue());
        }
    }

    private void validateString(String path, JsonNode schema, JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalidArguments(path + " must be a string");
        }

        String text = value.textValue();
        int length = text.codePointCount(0, text.length());
        Long minimumLength = optionalNonnegativeLong(path, schema, "minLength");
        Long maximumLength = optionalNonnegativeLong(path, schema, "maxLength");
        if (minimumLength != null && maximumLength != null && minimumLength > maximumLength) {
            throw invalidDefinition(path + " minLength must not exceed maxLength");
        }
        if (minimumLength != null && length < minimumLength) {
            throw invalidArguments(path + " is shorter than minLength");
        }
        // A positive minimum represents a business identifier in the V27 schemas. Treating a
        // whitespace-only value as empty keeps rejection in the schema-validation stage rather
        // than misclassifying it as an executor failure.
        if (minimumLength != null && minimumLength > 0 && text.isBlank()) {
            throw invalidArguments(path + " must not be blank");
        }
        if (maximumLength != null && length > maximumLength) {
            throw invalidArguments(path + " is longer than maxLength");
        }
    }

    private void validateInteger(String path, JsonNode schema, JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalidArguments(path + " must be an integer");
        }

        long integerValue = value.longValue();
        Long minimum = optionalLong(path, schema, "minimum");
        Long maximum = optionalLong(path, schema, "maximum");
        if (minimum != null && maximum != null && minimum > maximum) {
            throw invalidDefinition(path + " minimum must not exceed maximum");
        }
        if (minimum != null && integerValue < minimum) {
            throw invalidArguments(path + " is below minimum");
        }
        if (maximum != null && integerValue > maximum) {
            throw invalidArguments(path + " is above maximum");
        }
    }

    private static Set<String> requiredFields(String path, JsonNode requiredNode) {
        Set<String> required = new HashSet<>();
        if (requiredNode == null) {
            return required;
        }
        if (!requiredNode.isArray()) {
            throw invalidDefinition(path + ".required must be an array");
        }
        for (JsonNode item : requiredNode) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw invalidDefinition(path + ".required entries must be nonblank strings");
            }
            required.add(item.textValue());
        }
        return required;
    }

    private static Long optionalNonnegativeLong(String path, JsonNode schema, String field) {
        Long value = optionalLong(path, schema, field);
        if (value != null && value < 0) {
            throw invalidDefinition(path + "." + field + " must be nonnegative");
        }
        return value;
    }

    private static Long optionalLong(String path, JsonNode schema, String field) {
        JsonNode value = schema.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalidDefinition(path + "." + field + " must be an integer");
        }
        return value.longValue();
    }

    private static JsonNode requireSchemaObject(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            throw invalidDefinition("tool schema must be an object");
        }
        return schema;
    }

    private static ToolArgumentValidationException invalidArguments(String message) {
        return new ToolArgumentValidationException(message);
    }

    private static IllegalStateException invalidDefinition(String message) {
        return new IllegalStateException(message);
    }
}

final class ToolArgumentValidationException extends RuntimeException {
    ToolArgumentValidationException(String message) {
        super(message);
    }
}
