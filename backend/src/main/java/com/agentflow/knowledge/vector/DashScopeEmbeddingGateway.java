package com.agentflow.knowledge.vector;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 中文：DashScope OpenAI-compatible {@code /embeddings} 的 V6 适配器。它只处理一条 chunk
 * 的 dense float embedding；DashScope 的 sparse 输出、query instruction 和批处理属于后续检索
 * /吞吐量切片，不能改变当前 Gateway 的最小契约。
 *
 * <p>English: V6 adapter for DashScope's OpenAI-compatible {@code /embeddings} endpoint.
 * It handles one chunk's dense float embedding only. DashScope sparse output, query
 * instructions, and batching belong to later retrieval/throughput slices and do not
 * change the current minimal Gateway contract.
 */
public final class DashScopeEmbeddingGateway implements EmbeddingGateway {
    static final String PROVIDER = "dashscope";

    private final DashScopeEmbeddingProperties properties;
    private final RestClient restClient;

    DashScopeEmbeddingGateway(DashScopeEmbeddingProperties properties, RestClient restClient) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    }

    @Override
    public EmbeddingVector embed(EmbeddingRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireConfiguredKey();
        requireConfiguredModel(request);
        int dimensions = requirePositive(properties.getDimensions(), "DashScope dimensions");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("input", request.content());
        body.put("dimensions", dimensions);
        body.put("encoding_format", "float");

        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .headers(headers -> headers.setBearerAuth(properties.getApiKey().trim()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException requestFailure) {
            throw new IllegalStateException("DashScope embedding request failed", requestFailure);
        }

        JsonNode embedding = requireSingleEmbedding(response);
        if (embedding.size() != dimensions) {
            throw new IllegalStateException(
                    "DashScope returned " + embedding.size() + " dimensions; expected " + dimensions
            );
        }

        List<Float> values = new ArrayList<>(dimensions);
        for (JsonNode value : embedding) {
            if (!value.isNumber() || !Float.isFinite(value.floatValue())) {
                throw new IllegalStateException("DashScope response contains a non-finite embedding value");
            }
            values.add(value.floatValue());
        }
        return new EmbeddingVector(values);
    }

    private void requireConfiguredModel(EmbeddingRequest request) {
        if (!PROVIDER.equals(request.provider())) {
            throw new IllegalArgumentException(
                    "DashScope gateway only supports embeddingProvider=" + PROVIDER
            );
        }
        String configuredModel = requireNonBlank(properties.getModel(), "DashScope model");
        if (!configuredModel.equals(request.model())) {
            throw new IllegalArgumentException(
                    "DashScope gateway only supports configured embeddingModel=" + configuredModel
            );
        }
    }

    private void requireConfiguredKey() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY is not configured");
        }
    }

    private static JsonNode requireSingleEmbedding(JsonNode response) {
        if (response == null || !response.path("data").isArray() || response.path("data").size() != 1) {
            throw new IllegalStateException("DashScope response must contain exactly one embedding item");
        }
        JsonNode embedding = response.path("data").get(0).path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new IllegalStateException("DashScope response does not contain a dense embedding");
        }
        return embedding;
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
