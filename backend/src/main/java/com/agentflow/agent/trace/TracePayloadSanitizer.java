package com.agentflow.agent.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Recursive exact-key redaction and fail-fast serialized UTF-8 size enforcement. */
@Component
public class TracePayloadSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization",
            "proxyauthorization",
            "cookie",
            "setcookie",
            "apikey",
            "xapikey",
            "password",
            "secret",
            "clientsecret",
            "accesstoken",
            "refreshtoken",
            "endpoint",
            "baseurl",
            "chainofthought",
            "cot",
            "reasoning",
            "reasoningcontent",
            "reasoningdetails",
            "analysis",
            "analysiscontent",
            "analysisdetails",
            "thought",
            "thoughts",
            "thinking"
    );
    private static final Set<String> LLM_REQUEST_FIELDS = Set.of(
            "messages",
            "modelprovider",
            "modelname",
            "provider",
            "model",
            "requestedmodel",
            "temperature",
            "topp",
            "maxoutputtokens"
    );
    private static final Set<String> LLM_MESSAGE_FIELDS = Set.of("role", "content");
    private static final Set<String> LLM_MESSAGE_ROLES = Set.of("SYSTEM", "USER", "ASSISTANT");
    private static final Pattern SENSITIVE_KEY_VALUE = Pattern.compile(
            "(?is)\\b(?:authorization|proxy[-_ ]authorization|cookie|set[-_ ]cookie|"
                    + "api[-_ ]?key|x[-_ ]api[-_ ]key|password|secret|client[-_ ]secret|"
                    + "access[-_ ]token|refresh[-_ ]token)\\b\\s*[:=].*$"
    );
    private static final Pattern ENDPOINT_VALUE = Pattern.compile("(?i)\\b(?:https?|jdbc):\\S+");

    private final ObjectMapper objectMapper;
    private final TracePayloadProperties properties;

    public TracePayloadSanitizer(ObjectMapper objectMapper, TracePayloadProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        properties.validate();
    }

    public JsonNode sanitizeSmallObject(JsonNode value, String label) {
        return sanitizeObject(value, properties.getSmallMaxBytes(), label);
    }

    public JsonNode sanitizeLargeObject(JsonNode value, String label) {
        return sanitizeObject(value, properties.getLargeMaxBytes(), label);
    }

    public String sanitizeToolJson(Object value, String label) {
        return sanitizeToJson(objectMapper.valueToTree(value), properties.getToolMaxBytes(), label);
    }

    public String sanitizeSmallJson(JsonNode value, String label) {
        return sanitizeToJson(requireObject(value, label), properties.getSmallMaxBytes(), label);
    }

    public String sanitizeLargeJson(JsonNode value, String label) {
        return sanitizeToJson(requireObject(value, label), properties.getLargeMaxBytes(), label);
    }

    /**
     * Projects a provider-neutral request snapshot through an explicit allowlist. Framework
     * clients, headers, endpoints, credentials, and arbitrary configuration fields are rejected.
     */
    public String sanitizeLlmRequestSnapshot(JsonNode value, String label) {
        ObjectNode input = (ObjectNode) requireObject(value, label);
        ObjectNode projected = objectMapper.createObjectNode();
        Set<String> seen = new HashSet<>();
        boolean hasMessages = false;
        for (Map.Entry<String, JsonNode> field : input.properties()) {
            String normalized = normalizeKey(field.getKey());
            if (!LLM_REQUEST_FIELDS.contains(normalized) || !seen.add(normalized)) {
                throw new IllegalArgumentException(label + " contains an unsupported field: " + field.getKey());
            }
            JsonNode safeValue = switch (normalized) {
                case "messages" -> {
                    hasMessages = true;
                    yield sanitizeLlmMessages(field.getValue(), label);
                }
                case "modelprovider", "modelname", "provider", "model", "requestedmodel" ->
                        requireNonBlankText(field.getValue(), label + "." + field.getKey());
                case "temperature", "topp" -> requireNumber(field.getValue(), label + "." + field.getKey());
                case "maxoutputtokens" -> requirePositiveInteger(
                        field.getValue(),
                        label + "." + field.getKey()
                );
                default -> throw new IllegalStateException("Unhandled LLM request snapshot field");
            };
            projected.set(field.getKey(), safeValue);
        }
        if (!hasMessages) {
            throw new IllegalArgumentException(label + " must contain messages");
        }
        return sanitizeToJson(projected, properties.getLargeMaxBytes(), label);
    }

    public String sanitizeDecisionResponse(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException(label + " must be a structured JSON object");
            }
            return sanitizeToJson(parsed, properties.getLargeMaxBytes(), label);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(label + " must be a structured JSON object", ex);
        }
    }

    public String sanitizeLargeText(String value, String label) {
        return sanitizeText(value, properties.getLargeMaxBytes(), label);
    }

    public String sanitizeRagHitContent(String value, String label) {
        return sanitizeText(value, properties.getRagHitContentMaxBytes(), label);
    }

    /** Redacts recognized credential assignments and endpoints from an already-safe error message. */
    public String sanitizeErrorMessage(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " must be a single safe line");
        }
        String withoutEndpoint = ENDPOINT_VALUE.matcher(value).replaceAll("[REDACTED_ENDPOINT]");
        return SENSITIVE_KEY_VALUE.matcher(withoutEndpoint).replaceAll("[REDACTED]");
    }

    private JsonNode sanitizeObject(JsonNode value, int maxBytes, String label) {
        JsonNode safe = sanitize(requireObject(value, label));
        requireWithinLimit(safe, maxBytes, label);
        return safe;
    }

    private String sanitizeToJson(JsonNode value, int maxBytes, String label) {
        JsonNode safe = sanitize(Objects.requireNonNull(value, label + " must not be null"));
        byte[] serialized = writeBytes(safe, label);
        if (serialized.length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds the configured UTF-8 byte limit");
        }
        return new String(serialized, StandardCharsets.UTF_8);
    }

    private String sanitizeText(String value, int maxBytes, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        JsonNode structured = parseStructuredText(value);
        if (structured != null) {
            JsonNode safeStructured = sanitize(structured);
            byte[] structuredBytes = writeBytes(safeStructured, label);
            if (structuredBytes.length > maxBytes) {
                throw new IllegalArgumentException(label + " exceeds the configured UTF-8 byte limit");
            }
            return new String(structuredBytes, StandardCharsets.UTF_8);
        }
        JsonNode safeValue = new TextNode(value);
        byte[] serialized = writeBytes(safeValue, label);
        if (serialized.length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds the configured UTF-8 byte limit");
        }
        return value;
    }

    private JsonNode parseStructuredText(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed != null && (parsed.isObject() || parsed.isArray()) ? parsed : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private ArrayNode sanitizeLlmMessages(JsonNode value, String label) {
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException(label + ".messages must be a non-empty array");
        }
        ArrayNode messages = objectMapper.createArrayNode();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(label + ".messages entries must be objects");
            }
            ObjectNode message = objectMapper.createObjectNode();
            Set<String> seen = new HashSet<>();
            for (Map.Entry<String, JsonNode> field : item.properties()) {
                String normalized = normalizeKey(field.getKey());
                if (!LLM_MESSAGE_FIELDS.contains(normalized) || !seen.add(normalized)) {
                    throw new IllegalArgumentException(
                            label + ".messages contains an unsupported field: " + field.getKey()
                    );
                }
                if ("role".equals(normalized)) {
                    JsonNode role = requireNonBlankText(field.getValue(), label + ".messages.role");
                    if (!LLM_MESSAGE_ROLES.contains(role.textValue().toUpperCase(Locale.ROOT))) {
                        throw new IllegalArgumentException(label + ".messages.role is unsupported");
                    }
                    message.set(field.getKey(), role);
                } else {
                    JsonNode content = requireNonBlankText(field.getValue(), label + ".messages.content");
                    message.put(
                            field.getKey(),
                            sanitizeText(content.textValue(), properties.getLargeMaxBytes(), label + ".messages.content")
                    );
                }
            }
            if (!seen.equals(LLM_MESSAGE_FIELDS)) {
                throw new IllegalArgumentException(label + ".messages entries require role and content");
            }
            messages.add(message);
        }
        return messages;
    }

    private static JsonNode requireNonBlankText(JsonNode value, String label) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(label + " must be non-blank text");
        }
        return value.deepCopy();
    }

    private static JsonNode requireNumber(JsonNode value, String label) {
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException(label + " must be numeric");
        }
        return value.deepCopy();
    }

    private static JsonNode requirePositiveInteger(JsonNode value, String label) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new IllegalArgumentException(label + " must be a positive integer");
        }
        return value.deepCopy();
    }

    private void requireWithinLimit(JsonNode value, int maxBytes, String label) {
        if (writeBytes(value, label).length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds the configured UTF-8 byte limit");
        }
    }

    private byte[] writeBytes(JsonNode value, String label) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(label + " could not be serialized", ex);
        }
    }

    private JsonNode sanitize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                result.set(
                        field.getKey(),
                        isSensitive(field.getKey()) ? TextNode.valueOf(REDACTED) : sanitize(field.getValue())
                );
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(sanitize(item)));
            return result;
        }
        return value.deepCopy();
    }

    private static JsonNode requireObject(JsonNode value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (!value.isObject()) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
        return value;
    }

    private static boolean isSensitive(String fieldName) {
        return SENSITIVE_KEYS.contains(normalizeKey(fieldName));
    }

    private static String normalizeKey(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
