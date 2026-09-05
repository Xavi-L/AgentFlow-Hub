package com.agentflow.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

class QdrantVectorStoreGatewayTest {

    @Test
    void snapshotQueryFiltersExactDocumentGenerationPairsAndRequestsContentHash() {
        QdrantProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(properties, builder.build());
        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3"))
                .andRespond(withSuccess(collectionResponse(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3/points/query"))
                .andExpect(content().json("""
                        {
                          "query":[0.125,-0.5,0.75],
                          "filter":{
                            "must":[
                              {"key":"userId","match":{"value":101}},
                              {"key":"knowledgeBaseId","match":{"value":201}}
                            ],
                            "should":[
                              {"must":[{"key":"documentId","match":{"value":301}},
                                       {"key":"vectorGeneration","match":{"value":7}}]},
                              {"must":[{"key":"documentId","match":{"value":302}},
                                       {"key":"vectorGeneration","match":{"value":8}}]}
                            ]
                          },
                          "limit":20,
                          "with_payload":["chunkId","contentHash"],
                          "with_vector":false
                        }
                        """))
                .andRespond(withSuccess("""
                        {"result":{"points":[
                          {"id":"6f221541-64ae-8c32-9f22-c44f515cd6a0","score":0.9,
                           "payload":{"chunkId":401,"contentHash":"snapshot-hash"}}
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        List<VectorSearchHit> hits = gateway.search(new VectorSearchRequest(
                new EmbeddingVector(List.of(0.125f, -0.5f, 0.75f)), 101L, 201L, 20,
                List.of(new VectorSearchRequest.DocumentGeneration(301, 7),
                        new VectorSearchRequest.DocumentGeneration(302, 8))));
        assertThat(hits).containsExactly(new VectorSearchHit(
                "6f221541-64ae-8c32-9f22-c44f515cd6a0", 401, 0.9, "snapshot-hash"));
        server.verify();
    }

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

    @Test
    void shouldQueryOnlyTheCurrentOwnerAndKnowledgeBaseThenReturnPointLocators() {
        QdrantProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(properties, builder.build());

        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(collectionResponse(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3/points/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "test-qdrant-key"))
                .andExpect(content().json("""
                        {
                          "query":[0.125,-0.5,0.75],
                          "filter":{"must":[
                            {"key":"userId","match":{"value":101}},
                            {"key":"knowledgeBaseId","match":{"value":201}}
                          ]},
                          "limit":3,
                          "with_payload":["chunkId"],
                          "with_vector":false
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "result":{"points":[
                            {"id":"6f221541-64ae-8c32-9f22-c44f515cd6a0","score":0.91,
                             "payload":{"chunkId":401}}
                          ]},
                          "status":"ok"
                        }
                        """, MediaType.APPLICATION_JSON));

        List<VectorSearchHit> hits = gateway.search(new VectorSearchRequest(
                new EmbeddingVector(List.of(0.125f, -0.5f, 0.75f)),
                101L,
                201L,
                3
        ));

        assertThat(hits).containsExactly(new VectorSearchHit(
                "6f221541-64ae-8c32-9f22-c44f515cd6a0", 401L, 0.91
        ));
        server.verify();
    }

    @Test
    void shouldNotCreateACollectionWhenAReadOnlySearchFindsNone() {
        QdrantProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(properties, builder.build());

        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> gateway.search(new VectorSearchRequest(
                new EmbeddingVector(List.of(0.125f, -0.5f, 0.75f)), 101L, 201L, 3
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist for retrieval");
        server.verify();
    }

    @Test
    void shouldDeleteOnlyTheCurrentOwnerKnowledgeBaseAndDocumentAndWaitForCompletion() {
        QdrantProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(properties, builder.build());

        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("api-key", "test-qdrant-key"))
                .andRespond(withSuccess(collectionResponse(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3/points/delete?wait=true"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "test-qdrant-key"))
                .andExpect(content().json("""
                        {
                          "filter":{"must":[
                            {"key":"userId","match":{"value":101}},
                            {"key":"knowledgeBaseId","match":{"value":201}},
                            {"key":"documentId","match":{"value":301}}
                          ]}
                        }
                        """))
                .andRespond(withSuccess(
                        "{\"result\":{\"status\":\"completed\"},\"status\":\"ok\"}",
                        MediaType.APPLICATION_JSON
                ));

        gateway.deleteByDocumentScope(new VectorDocumentScope(101L, 201L, 301L));

        server.verify();
    }

    @Test
    void shouldTreatAConfirmedAbsentCollectionAsAnIdempotentDeleteNoOp() {
        QdrantProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(properties, builder.build());

        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        gateway.deleteByDocumentScope(new VectorDocumentScope(101L, 201L, 301L));

        server.verify();
    }

    @Test
    void shouldFenceReprocessDeletionToOneExactGeneration() {
        QdrantProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(properties, builder.build());

        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3"))
                .andRespond(withSuccess(collectionResponse(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3/points/delete?wait=true"))
                .andExpect(content().json("""
                        {"filter":{"must":[
                          {"key":"userId","match":{"value":101}},
                          {"key":"knowledgeBaseId","match":{"value":201}},
                          {"key":"documentId","match":{"value":301}},
                          {"key":"vectorGeneration","match":{"value":7}}
                        ]}}
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        gateway.deleteByDocumentScope(VectorDocumentScope.forGenerationCleanup(101L, 201L, 301L, 7L));
        server.verify();
    }

    @Test
    void shouldIncludeLegacyMissingGenerationOnlyForGenerationZero() {
        QdrantProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(properties, builder.build());

        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3"))
                .andRespond(withSuccess(collectionResponse(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3/points/delete?wait=true"))
                .andExpect(content().json("""
                        {"filter":{
                          "must":[
                            {"key":"userId","match":{"value":101}},
                            {"key":"knowledgeBaseId","match":{"value":201}},
                            {"key":"documentId","match":{"value":301}}
                          ],
                          "should":[
                            {"key":"vectorGeneration","match":{"value":0}},
                            {"is_empty":{"key":"vectorGeneration"}}
                          ]
                        }}
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        gateway.deleteByDocumentScope(VectorDocumentScope.forGenerationCleanup(101L, 201L, 301L, 0L));
        server.verify();
    }

    @Test
    void shouldPropagateRemoteDeletionFailuresForLaterCompensation() {
        QdrantProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(properties, builder.build());

        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(collectionResponse(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3/points/delete?wait=true"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> gateway.deleteByDocumentScope(new VectorDocumentScope(101L, 201L, 301L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Qdrant point deletion failed")
                .hasCauseInstanceOf(RestClientResponseException.class);
        server.verify();
    }

    @Test
    void shouldClassifyAnUpsertTransportTimeoutAsOutcomeUnknown() {
        QdrantProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(properties, builder.build());

        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3"))
                .andRespond(withSuccess(collectionResponse(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/agentflow_chunks_te_v4_3/points?wait=true"))
                .andRespond(request -> {
                    throw new ResourceAccessException("read timed out");
                });

        assertThatThrownBy(() -> gateway.upsert(record()))
                .isInstanceOf(VectorStoreOutcomeUnknownException.class)
                .hasMessageContaining("outcome is unknown")
                .hasCauseInstanceOf(ResourceAccessException.class);
        server.verify();
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

    private static String collectionResponse() {
        return """
                {"result":{"config":{"params":{"vectors":{"size":3,"distance":"Cosine"}}}}}
                """;
    }
}
