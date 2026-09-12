package com.agentflow.agent.configversion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Frozen cross-language bytes for configuration provenance, not arbitrary mapper output. */
public final class ConfigCanonicalJson {
    public static final String ALGORITHM_VERSION = "config-canonical-json-v1";

    private ConfigCanonicalJson() { }

    public static String canonicalJson(JsonNode value) {
        if (value == null) throw new IllegalArgumentException("JSON value is required");
        StringBuilder out = new StringBuilder();
        append(value, out);
        return out.toString();
    }

    public static String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Preserve the original snapshot; discard only the frozen non-execution field list. */
    public static JsonNode effectiveProjection(JsonNode snapshot) {
        if (snapshot == null || !snapshot.isObject()) {
            throw new IllegalArgumentException("Execution snapshot must be an object");
        }
        ObjectNode effective = snapshot.deepCopy();
        if (effective.get("agent") instanceof ObjectNode agent) {
            agent.remove(List.of("agentId", "status"));
        }
        if (effective.get("executionSettings") instanceof ObjectNode settings) {
            settings.remove("sources");
        }
        if (effective.path("tools").isArray()) {
            for (JsonNode tool : effective.path("tools")) {
                if (tool instanceof ObjectNode object) object.remove("inputSchemaHash");
            }
        }
        // inputSchemaHash is a redundant legacy fingerprint. Its original bytes remain in the
        // snapshot for ToolRuntime hard validation; canonicalize the full schema here instead.
        // Tool names and descriptions enter the actual model prompt; they are execution values.
        // Array order is retained, including the resolved priority of tools and knowledge bases.
        return effective;
    }

    public static String effectiveConfigHash(JsonNode snapshot) {
        return sha256(effectiveProjection(snapshot));
    }

    private static void append(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(ConfigCanonicalJson::compareCodePoints);
            out.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) out.append(',');
                first = false;
                string(key, out);
                out.append(':');
                append(node.get(key), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                append(node.get(i), out);
            }
            out.append(']');
        } else if (node.isTextual()) {
            string(node.textValue(), out);
        } else if (node.isNumber()) {
            if (node.isFloatingPointNumber()
                    && ("NaN".equals(node.asText()) || node.asText().contains("Infinity"))) {
                throw new IllegalArgumentException("Non-finite JSON number");
            }
            BigDecimal number = node.decimalValue();
            out.append(number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString());
        } else if (node.isNull()) {
            out.append("null");
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else {
            throw new IllegalArgumentException("Unsupported JSON value");
        }
    }

    private static int compareCodePoints(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ac = a.codePointAt(i), bc = b.codePointAt(j);
            if (ac != bc) return Integer.compare(ac, bc);
            i += Character.charCount(ac);
            j += Character.charCount(bc);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit(c >>> 4, 16))
                                .append(Character.forDigit(c & 15, 16));
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                            throw new IllegalArgumentException("Unpaired Unicode surrogate");
                        }
                        out.append(c).append(value.charAt(++i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw new IllegalArgumentException("Unpaired Unicode surrogate");
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
