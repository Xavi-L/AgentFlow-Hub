package com.agentflow.knowledge.vector;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
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
 * 中文：V6/V7/V23 的 Qdrant REST 适配器。首次写入时它只会创建或验证一个 plain dense-vector
 * collection；随后使用 V5 已派生的 UUID point ID 进行 {@code wait=true} 的幂等 upsert。V7
 * 通过 Qdrant Query Points API 作 scoped dense retrieval。V23 使用完整文档范围删除 point，
 * 且不会创建 collection；rerank、named vectors 和 sparse vectors 仍留给独立切片。
 *
 * <p>English: V6/V7/V23 Qdrant REST adapter. On the first write it creates or validates one
 * plain dense-vector collection, then performs a {@code wait=true} idempotent upsert
 * using V5's derived UUID point ID. V7 uses Qdrant Query Points for scoped dense
 * retrieval. V23 deletes points by a complete document scope without creating a collection;
 * reranking, named vectors, and sparse vectors remain deferred.
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

    @Override
    public List<VectorSearchHit> search(VectorSearchRequest request) {
        VectorSearchRequest safeRequest = Objects.requireNonNull(request, "request must not be null");
        if (safeRequest.vector().values().size() != properties.getVectorSize()) {
            throw new IllegalArgumentException(
                    "Query vector dimension " + safeRequest.vector().values().size()
                            + " does not match Qdrant vectorSize " + properties.getVectorSize()
            );
        }
        ensureExistingCollectionForSearch();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", safeRequest.vector().values());
        body.put("filter", scopeFilter(safeRequest));
        body.put("limit", safeRequest.limit());
        body.put("with_payload", List.of("chunkId"));
        body.put("with_vector", false);

        try {
            JsonNode response = restClient.post()
                    .uri("/collections/" + collection + "/points/query")
                    .headers(this::applyApiKeyIfPresent)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parseSearchHits(response);
        } catch (RestClientException searchFailure) {
            throw new IllegalStateException("Qdrant point query failed", searchFailure);
        }
    }

    @Override
    public void deleteByDocumentScope(VectorDocumentScope scope) {
        VectorDocumentScope safeScope = Objects.requireNonNull(scope, "scope must not be null");
        if (!ensureExistingCollectionForDeletion()) {
            return;
        }

        try {
            restClient.post()
                    .uri("/collections/" + collection + "/points/delete?wait=true")
                    .headers(this::applyApiKeyIfPresent)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("filter", documentScopeFilter(safeScope)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException deletionFailure) {
            throw new IllegalStateException("Qdrant point deletion failed", deletionFailure);
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

    /** A V7 read must never create a collection as a side effect. */
    private synchronized void ensureExistingCollectionForSearch() {
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
            if (responseFailure.getStatusCode().value() == 404) {
                throw new IllegalStateException("Qdrant collection does not exist for retrieval", responseFailure);
            }
            throw new IllegalStateException("Could not inspect Qdrant collection", responseFailure);
        } catch (RestClientException requestFailure) {
            throw new IllegalStateException("Could not inspect Qdrant collection", requestFailure);
        }
        collectionReady = true;
    }

    /** A V23 delete never creates a collection; a confirmed absence is an idempotent no-op. */
    private synchronized boolean ensureExistingCollectionForDeletion() {
        if (collectionReady) {
            return true;
        }
        try {
            JsonNode response = restClient.get()
                    .uri("/collections/" + collection)
                    .headers(this::applyApiKeyIfPresent)
                    .retrieve()
                    .body(JsonNode.class);
            verifyExistingCollection(response);
        } catch (RestClientResponseException responseFailure) {
            if (responseFailure.getStatusCode().value() == 404) {
                return false;
            }
            throw new IllegalStateException("Could not inspect Qdrant collection", responseFailure);
        } catch (RestClientException requestFailure) {
            throw new IllegalStateException("Could not inspect Qdrant collection", requestFailure);
        }
        collectionReady = true;
        return true;
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

    private static Map<String, Object> scopeFilter(VectorSearchRequest request) {
        return Map.of("must", List.of(
                matchFilter("userId", request.userId()),
                matchFilter("knowledgeBaseId", request.knowledgeBaseId())
        ));
    }

    private static Map<String, Object> documentScopeFilter(VectorDocumentScope scope) {
        return Map.of("must", List.of(
                matchFilter("userId", scope.userId()),
                matchFilter("knowledgeBaseId", scope.knowledgeBaseId()),
                matchFilter("documentId", scope.documentId())
        ));
    }

    private static Map<String, Object> matchFilter(String key, long value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private static List<VectorSearchHit> parseSearchHits(JsonNode response) {
        JsonNode result = response == null ? null : response.path("result");
        JsonNode points = result != null && result.isArray() ? result : result == null ? null : result.path("points");
        if (points == null || !points.isArray()) {
            throw new IllegalStateException("Qdrant query response does not contain result points");
        }

        List<VectorSearchHit> hits = new ArrayList<>(points.size());
        for (JsonNode point : points) {
            JsonNode chunkId = point.path("payload").path("chunkId");
            JsonNode score = point.path("score");
            if (!chunkId.isIntegralNumber() || chunkId.longValue() <= 0) {
                throw new IllegalStateException("Qdrant query result has no positive chunkId payload");
            }
            if (!score.isNumber() || !Double.isFinite(score.doubleValue())) {
                throw new IllegalStateException("Qdrant query result has a non-finite score");
            }
            hits.add(new VectorSearchHit(point.path("id").asText(), chunkId.longValue(), score.doubleValue()));
        }
        return List.copyOf(hits);
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
