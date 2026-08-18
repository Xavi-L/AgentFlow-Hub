package com.agentflow.knowledge.vector;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 中文：V6 的 Qdrant REST 写入适配器。首次写入时它只会创建或验证一个 plain dense-vector
 * collection；随后使用 V5 已派生的 UUID point ID 进行 {@code wait=true} 的幂等 upsert。
 * 搜索、删除、named vectors 和 sparse vectors 仍留给独立切片。
 *
 * <p>English: V6 Qdrant REST write adapter. On the first write it creates or validates
 * one plain dense-vector collection, then performs a {@code wait=true} idempotent upsert
 * using V5's derived UUID point ID. Search, deletion, named vectors, and sparse vectors
 * remain separate slices.
 */
public final class QdrantVectorStoreGateway implements VectorStoreGateway {
    private static final String COSINE_DISTANCE = "Cosine";
    private static final Pattern COLLECTION_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private final QdrantProperties properties;
    private final RestClient restClient;
    private final String collection;
    private boolean collectionReady;

    QdrantVectorStoreGateway(QdrantProperties properties, RestClient restClient) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.collection = requireCollectionName(properties.getCollection());
        requirePositive(properties.getVectorSize(), "Qdrant vectorSize");
    }

    @Override
    public void upsert(VectorStoreRecord record) {
        VectorStoreRecord safeRecord = Objects.requireNonNull(record, "record must not be null");
        if (safeRecord.vector().values().size() != properties.getVectorSize()) {
            throw new IllegalArgumentException(
                    "Embedding vector dimension " + safeRecord.vector().values().size()
                            + " does not match Qdrant vectorSize " + properties.getVectorSize()
            );
        }
        ensureCollection();

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", safeRecord.vectorId());
        point.put("vector", safeRecord.vector().values());
        point.put("payload", safeRecord.payload());

        try {
            restClient.put()
                    .uri("/collections/" + collection + "/points?wait=true")
                    .headers(this::applyApiKeyIfPresent)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("points", List.of(point)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException upsertFailure) {
            throw new IllegalStateException("Qdrant point upsert failed", upsertFailure);
        }
    }

    private synchronized void ensureCollection() {
        if (collectionReady) {
            return;
        }
        try {
            JsonNode response = restClient.get()
                    .uri("/collections/" + collection)
                    .headers(this::applyApiKeyIfPresent)
                    .retrieve()
                    .body(JsonNode.class);
            verifyExistingCollection(response);
        } catch (RestClientResponseException responseFailure) {
            if (responseFailure.getStatusCode().value() != 404) {
                throw new IllegalStateException("Could not inspect Qdrant collection", responseFailure);
            }
            createCollection();
        } catch (RestClientException requestFailure) {
            throw new IllegalStateException("Could not inspect Qdrant collection", requestFailure);
        }
        collectionReady = true;
    }

    private void createCollection() {
        Map<String, Object> vectors = Map.of(
                "size", properties.getVectorSize(),
                "distance", COSINE_DISTANCE
        );
        try {
            restClient.put()
                    .uri("/collections/" + collection)
                    .headers(this::applyApiKeyIfPresent)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("vectors", vectors))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException createFailure) {
            throw new IllegalStateException("Could not create Qdrant collection", createFailure);
        }
    }

    private void verifyExistingCollection(JsonNode response) {
        JsonNode vectors = response == null
                ? null
                : response.path("result").path("config").path("params").path("vectors");
        if (vectors == null || !vectors.isObject()) {
            throw new IllegalStateException("Qdrant collection does not expose a plain vector configuration");
        }
        int actualSize = vectors.path("size").asInt(-1);
        String actualDistance = vectors.path("distance").asText();
        if (actualSize != properties.getVectorSize() || !COSINE_DISTANCE.equalsIgnoreCase(actualDistance)) {
            throw new IllegalStateException(
                    "Qdrant collection configuration does not match the configured model contract"
            );
        }
    }

    private void applyApiKeyIfPresent(org.springframework.http.HttpHeaders headers) {
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.set("api-key", properties.getApiKey().trim());
        }
    }

    private static String requireCollectionName(String value) {
        if (value == null || !COLLECTION_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Qdrant collection must be 1-128 URL-safe characters");
        }
        return value;
    }

    private static void requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
