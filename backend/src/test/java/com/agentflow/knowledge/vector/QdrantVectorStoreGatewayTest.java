package com.agentflow.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class QdrantVectorStoreGatewayTest {

    @Test
    void shouldCreateTheConfiguredCollectionThenUpsertTheStablePointId() {
        QdrantProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(properties, builder.build());

        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("api-key", "test-qdrant-key"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"vectors":{"size":3,"distance":"Cosine"}}
                        """))
                .andRespond(withSuccess("{\"result\":true,\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3/points?wait=true"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {
                          "points":[{
                            "id":"6f221541-64ae-8c32-9f22-c44f515cd6a0",
                            "vector":[0.125,-0.5,0.75],
                            "payload":{"chunkId":401,"contentHash":"hash"}
                          }]
                        }
                        """))
                .andRespond(withSuccess("{" + "\"result\":{\"status\":\"completed\"},\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        gateway.upsert(record());

        server.verify();
    }

    @Test
    void shouldRejectAVectorThatCannotFitTheConfiguredCollection() {
        QdrantProperties properties = properties();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(
                properties,
                RestClient.builder().baseUrl(properties.getBaseUrl()).build()
        );
        VectorStoreRecord wrongDimension = new VectorStoreRecord(
                "6f221541-64ae-8c32-9f22-c44f515cd6a0",
                new EmbeddingVector(List.of(0.1f, 0.2f)),
                Map.of("chunkId", 401L)
        );

        assertThatThrownBy(() -> gateway.upsert(wrongDimension))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match Qdrant vectorSize 3");
    }

    private static QdrantProperties properties() {
        QdrantProperties properties = new QdrantProperties();
        properties.setBaseUrl("http://qdrant.test");
        properties.setApiKey("test-qdrant-key");
        properties.setCollection("agentflow_chunks_te_v4_3");
        properties.setVectorSize(3);
        return properties;
    }

    private static VectorStoreRecord record() {
        return new VectorStoreRecord(
                "6f221541-64ae-8c32-9f22-c44f515cd6a0",
                new EmbeddingVector(List.of(0.125f, -0.5f, 0.75f)),
                Map.of("chunkId", 401L, "contentHash", "hash")
        );
    }
}
