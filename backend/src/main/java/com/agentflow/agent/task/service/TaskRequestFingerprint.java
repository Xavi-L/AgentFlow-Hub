package com.agentflow.agent.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Fixed-version canonical request fingerprint used by the task idempotency boundary. */
@Component
public class TaskRequestFingerprint {
    public static final String VERSION = "agent-task-request-v1";

    private final ObjectWriter canonicalWriter;

    public TaskRequestFingerprint(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.canonicalWriter = objectMapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .without(SerializationFeature.INDENT_OUTPUT);
    }

    public Fingerprint calculate(long agentId, String originalInput) {
        if (agentId <= 0) {
            throw new IllegalArgumentException("agentId must be positive");
        }
        Objects.requireNonNull(originalInput, "originalInput must not be null");
        Map<String, String> canonicalRequest = new TreeMap<>();
        canonicalRequest.put("version", VERSION);
        canonicalRequest.put("agentId", Long.toString(agentId));
        canonicalRequest.put("input", originalInput);
        try {
            String canonicalJson = canonicalWriter.writeValueAsString(canonicalRequest);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String sha256 = HexFormat.of().formatHex(
                    digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8))
            );
            return new Fingerprint(canonicalJson, sha256);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Unable to fingerprint Agent task request", ex);
        }
    }

    public record Fingerprint(String canonicalJson, String sha256) {
    }
}
