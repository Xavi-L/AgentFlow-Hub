package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deliberately small JSON-Schema validator. In addition to the V27 object/string/integer subset
 * and V28 top-level required-only anyOf, V29 supports only homogeneous string arrays described by
 * one items schema plus minItems/maxItems. It is not a general JSON-Schema engine.
 */
@Component
public class ToolArgumentValidator {

    public void validate(JsonNode schema, JsonNode arguments) {
        JsonNode schemaObject = requireSchemaObject(schema);
        validateSupportedSchemaShape("arguments", schemaObject, true);
        validateNode("arguments", schemaObject, arguments);
        validateTopLevelAnyOf("arguments", schemaObject, arguments);
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
            case "array" -> validateArray(path, schema, value);
            default -> throw invalidDefinition(
                    path + " schema type is unsupported in V29: " + typeNode.textValue()
            );
        }
    }

    private void validateSupportedSchemaShape(String path, JsonNode schema, boolean topLevel) {
        JsonNode anyOfNode = schema.get("anyOf");
        if (anyOfNode != null) {
            if (!topLevel) {
                throw invalidDefinition(path + ".anyOf is supported only at the top level in V29");
            }
            JsonNode typeNode = schema.get("type");
            if (typeNode == null || !typeNode.isTextual() || !"object".equals(typeNode.textValue())) {
                throw invalidDefinition(path + ".anyOf requires a top-level object schema in V29");
            }
            validateRequiredOnlyBranches(path, schema, anyOfNode);
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode != null && typeNode.isTextual() && "array".equals(typeNode.textValue())) {
            JsonNode itemSchema = requireHomogeneousStringItemsSchema(path, schema);
            validateSupportedSchemaShape(path + ".items", itemSchema, false);
            validateArrayBounds(path, schema);
        }

        JsonNode propertiesNode = schema.get("properties");
        if (propertiesNode == null) {
            return;
        }
        if (!propertiesNode.isObject()) {
            throw invalidDefinition(path + ".properties must be an object");
        }
        Iterator<Map.Entry<String, JsonNode>> properties = propertiesNode.properties().iterator();
        while (properties.hasNext()) {
            Map.Entry<String, JsonNode> property = properties.next();
            validateSupportedSchemaShape(
                    path + ".properties." + property.getKey(),
                    requireSchemaObject(property.getValue()),
                    false
            );
        }
    }

    private void validateRequiredOnlyBranches(String path, JsonNode schema, JsonNode anyOfNode) {
        if (!anyOfNode.isArray() || anyOfNode.isEmpty()) {
            throw invalidDefinition(path + ".anyOf must be a nonempty array in V29");
        }

        JsonNode propertiesNode = schema.get("properties");
        if (propertiesNode == null || !propertiesNode.isObject()) {
            throw invalidDefinition(path + ".properties must declare anyOf required fields");
        }

        for (int index = 0; index < anyOfNode.size(); index++) {
            JsonNode branch = anyOfNode.get(index);
            String branchPath = path + ".anyOf[" + index + "]";
            if (!branch.isObject() || branch.size() != 1 || !branch.has("required")) {
                throw invalidDefinition(branchPath + " must contain only required in V29");
            }
            Set<String> branchRequired = requiredFields(branchPath, branch.get("required"));
            if (branchRequired.isEmpty()) {
                throw invalidDefinition(branchPath + ".required must not be empty");
            }
            for (String requiredField : branchRequired) {
                if (!propertiesNode.has(requiredField)) {
                    throw invalidDefinition(
                            branchPath + ".required field is not declared in properties: " + requiredField
                    );
                }
            }
        }
    }

    private void validateTopLevelAnyOf(String path, JsonNode schema, JsonNode value) {
        JsonNode anyOfNode = schema.get("anyOf");
        if (anyOfNode == null) {
            return;
        }

        for (int index = 0; index < anyOfNode.size(); index++) {
            Set<String> branchRequired = requiredFields(
                    path + ".anyOf[" + index + "]",
                    anyOfNode.get(index).get("required")
            );
            boolean branchMatches = branchRequired.stream().allMatch(requiredField -> {
                JsonNode requiredValue = value.get(requiredField);
                return requiredValue != null && !requiredValue.isNull();
            });
            if (branchMatches) {
                return;
            }
        }
        throw invalidArguments(path + " must satisfy at least one anyOf required branch");
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
                throw invalidDefinition(path + ".additionalProperties must be boolean in the supported subset");
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
        // A positive minimum represents a business identifier in the current schemas. Treating a
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

    private void validateArray(String path, JsonNode schema, JsonNode value) {
        if (value == null || !value.isArray()) {
            throw invalidArguments(path + " must be an array");
        }

        JsonNode itemSchema = requireHomogeneousStringItemsSchema(path, schema);
        ArrayBounds bounds = validateArrayBounds(path, schema);
        if (bounds.minimum() != null && value.size() < bounds.minimum()) {
            throw invalidArguments(path + " has fewer items than minItems");
        }
        if (bounds.maximum() != null && value.size() > bounds.maximum()) {
            throw invalidArguments(path + " has more items than maxItems");
        }
        for (int index = 0; index < value.size(); index++) {
            validateString(path + "[" + index + "]", itemSchema, value.get(index));
        }
    }

    private static JsonNode requireHomogeneousStringItemsSchema(String path, JsonNode schema) {
        if (schema.has("contains")) {
            throw invalidDefinition(path + ".contains is unsupported in V29");
        }
        if (schema.has("uniqueItems")) {
            throw invalidDefinition(path + ".uniqueItems is unsupported in V29");
        }

        JsonNode itemsNode = schema.get("items");
        if (itemsNode == null || !itemsNode.isObject()) {
            throw invalidDefinition(path + ".items must be one schema object in V29");
        }
        JsonNode itemType = itemsNode.get("type");
        if (itemType == null || !itemType.isTextual() || !"string".equals(itemType.textValue())) {
            throw invalidDefinition(path + ".items must declare one homogeneous string schema in V29");
        }
        return itemsNode;
    }

    private static ArrayBounds validateArrayBounds(String path, JsonNode schema) {
        Long minimum = optionalNonnegativeLong(path, schema, "minItems");
        Long maximum = optionalNonnegativeLong(path, schema, "maxItems");
        if (minimum != null && maximum != null && minimum > maximum) {
            throw invalidDefinition(path + " minItems must not exceed maxItems");
        }
        return new ArrayBounds(minimum, maximum);
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

    private record ArrayBounds(Long minimum, Long maximum) {
    }
}

final class ToolArgumentValidationException extends RuntimeException {
    ToolArgumentValidationException(String message) {
        super(message);
    }
}
