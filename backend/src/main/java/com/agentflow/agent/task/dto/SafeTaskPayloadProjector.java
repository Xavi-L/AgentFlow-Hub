package com.agentflow.agent.task.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Public read projection, including structured JSON embedded in persisted text fields. */
@Component
public class SafeTaskPayloadProjector {
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "proxyauthorization", "cookie", "setcookie", "apikey", "xapikey",
            "password", "secret", "clientsecret", "accesstoken", "refreshtoken", "credentials",
            "endpoint", "baseurl", "jdbcurl", "connectionstring", "headers", "runtimeconfig",
            "chainofthought", "cot", "reasoning", "reasoningcontent", "reasoningdetails",
            "analysis", "analysiscontent", "analysisdetails", "thought", "thoughts", "thinking"
    );
    private static final Pattern BUSINESS_ID = Pattern.compile(
            "(?:^id$|^ids$|Id(?:s)?(?:Snapshot)?$|(?:_|-)ids?(?:_|-)?(?:snapshot)?$)"
    );
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?im)\\b(?:authorization|proxy[-_ ]authorization|cookie|set[-_ ]cookie|"
                    + "api[-_ ]?key|x[-_ ]api[-_ ]key|password|secret|client[-_ ]secret|"
                    + "access[-_ ]token|refresh[-_ ]token)\\b\\s*[:=][^\\r\\n]*"
    );
    private static final Pattern DIAGNOSTIC_ENDPOINT = Pattern.compile("(?i)\\b(?:https?|jdbc):\\S+");
    private final ObjectMapper objectMapper;

    public SafeTaskPayloadProjector(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public JsonNode parse(String serialized) {
        if (serialized == null) {
            return NullNode.getInstance();
        }
        try {
            JsonNode parsed = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(serialized);
            if (parsed == null) {
                throw new IllegalStateException("Persisted task payload is empty");
            }
            return project(parsed);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Persisted task payload is invalid JSON", ex);
        }
    }

    /** Explicit public recovery projection; future persistence metadata is not exposed automatically. */
    public JsonNode projectRecovery(String serialized) {
        if (serialized == null) return null;
        JsonNode source = parse(serialized);
        if (!source.isObject()) throw new IllegalStateException("Persisted task recovery must be an object");
        ObjectNode result = pick(source, "schemaVersion", "mode", "recoveryRunId", "recoveredAt",
                "previousStatus", "reasonCode", "executionOutcome", "recordCompleteness", "counterCompleteness",
                "recordedLlmCalls", "recordedToolCalls", "queuedCancellationAnomaly");
        for (String field : java.util.List.of("recordedUsage", "previousTaskUsage")) {
            if (source.has(field)) result.set(field, pick(source.get(field),
                    "inputTokens", "outputTokens", "totalTokens", "tokenUsageQuality"));
        }
        return result;
    }

    private ObjectNode pick(JsonNode source, String... fields) {
        ObjectNode result = objectMapper.createObjectNode();
        for (String field : fields) if (source.has(field)) result.set(field, source.get(field).deepCopy());
        return result;
    }

    public JsonNode project(JsonNode value) {
        if (value == null || value.isNull()) {
            return NullNode.getInstance();
        }
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                String key = field.getKey();
                JsonNode safe;
                if (SENSITIVE_KEYS.contains(normalize(key))) {
                    safe = TextNode.valueOf("[REDACTED]");
                } else if ("errormessage".equals(normalize(key)) && field.getValue().isTextual()) {
                    safe = TextNode.valueOf(projectErrorMessage(field.getValue().textValue()));
                } else {
                    safe = project(field.getValue());
                }
                result.set(key, BUSINESS_ID.matcher(key).find() ? stringifyId(safe) : safe);
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(project(item)));
            return result;
        }
        if (value.isTextual()) {
            return TextNode.valueOf(projectText(value.textValue()));
        }
        return value.deepCopy();
    }

    /** Keeps text fields textual while safely projecting any embedded structured payload. */
    public String projectText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode parsed = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(value);
                if (parsed != null && parsed.isContainerNode()) {
                    return project(parsed).toString();
                }
            } catch (JsonProcessingException ignored) {
                // Ordinary text can start with a brace without being a JSON document.
            }
        }
        return CREDENTIAL_ASSIGNMENT.matcher(value).replaceAll("[REDACTED]");
    }

    /** Diagnostic errors must not reveal runtime addresses; business source URLs remain intact. */
    public String projectErrorMessage(String value) {
        String safe = projectText(value);
        return safe == null ? null : DIAGNOSTIC_ENDPOINT.matcher(safe).replaceAll("[REDACTED]");
    }

    private JsonNode stringifyId(JsonNode value) {
        if (value.isIntegralNumber()) {
            return TextNode.valueOf(value.asText());
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(stringifyId(item)));
            return result;
        }
        return value;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
