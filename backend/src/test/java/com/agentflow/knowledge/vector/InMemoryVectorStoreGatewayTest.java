package com.agentflow.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryVectorStoreGatewayTest {

    @Test
    void shouldReturnOnlyTheRequestedOwnerAndKnowledgeBaseInSimilarityOrder() {
        InMemoryVectorStoreGateway gateway = new InMemoryVectorStoreGateway();
        gateway.upsert(record(
                "6f221541-64ae-8c32-9f22-c44f515cd6a0", 401L, 101L, 201L, 301L, List.of(1.0f, 0.0f)
        ));
        gateway.upsert(record(
                "f7dcc320-8d47-8d9a-8407-a58b99f9812b", 402L, 101L, 201L, 301L, List.of(0.8f, 0.2f)
        ));
        gateway.upsert(record(
                "fdafcd5a-e66a-8e7e-8a02-0ea311d084a1", 403L, 102L, 201L, 301L, List.of(1.0f, 0.0f)
        ));

        List<VectorSearchHit> hits = gateway.search(new VectorSearchRequest(
                new EmbeddingVector(List.of(1.0f, 0.0f)), 101L, 201L, 5
        ));

        assertThat(hits).extracting(VectorSearchHit::chunkId).containsExactly(401L, 402L);
    }

    @Test
    void shouldDeleteOnlyTheExactDocumentScopeAndAllowRepeatedDeletion() {
        InMemoryVectorStoreGateway gateway = new InMemoryVectorStoreGateway();
        gateway.upsert(record(
                "6f221541-64ae-8c32-9f22-c44f515cd6a0", 401L, 101L, 201L, 301L, List.of(1.0f, 0.0f)
        ));
        gateway.upsert(record(
                "f7dcc320-8d47-8d9a-8407-a58b99f9812b", 402L, 101L, 201L, 301L, List.of(0.8f, 0.2f)
        ));
        gateway.upsert(record(
                "fdafcd5a-e66a-8e7e-8a02-0ea311d084a1", 403L, 101L, 201L, 302L, List.of(1.0f, 0.0f)
        ));
        gateway.upsert(record(
                "a1a97a45-770d-8d74-a7cc-733b43da5f97", 404L, 102L, 201L, 301L, List.of(1.0f, 0.0f)
        ));
        gateway.upsert(record(
                "60b13238-6011-8518-99f0-e4720a4c1c54", 405L, 101L, 202L, 301L, List.of(1.0f, 0.0f)
        ));

        VectorDocumentScope scope = new VectorDocumentScope(101L, 201L, 301L);
        gateway.deleteByDocumentScope(scope);
        gateway.deleteByDocumentScope(scope);

        assertThat(searchChunkIds(gateway, 101L, 201L)).containsExactly(403L);
        assertThat(searchChunkIds(gateway, 102L, 201L)).containsExactly(404L);
        assertThat(searchChunkIds(gateway, 101L, 202L)).containsExactly(405L);
    }

    @Test
    void shouldRejectNonPositiveDocumentScopeIdentifiers() {
        assertThatThrownBy(() -> new VectorDocumentScope(0L, 201L, 301L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must be positive");
        assertThatThrownBy(() -> new VectorDocumentScope(-1L, 201L, 301L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must be positive");
        assertThatThrownBy(() -> new VectorDocumentScope(101L, 0L, 301L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("knowledgeBaseId must be positive");
        assertThatThrownBy(() -> new VectorDocumentScope(101L, -1L, 301L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("knowledgeBaseId must be positive");
        assertThatThrownBy(() -> new VectorDocumentScope(101L, 201L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("documentId must be positive");
        assertThatThrownBy(() -> new VectorDocumentScope(101L, 201L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("documentId must be positive");
    }

    @Test
    void shouldDeleteOnlyTheRequestedGenerationAndKeepLaterVectors() {
        InMemoryVectorStoreGateway gateway = new InMemoryVectorStoreGateway();
        gateway.upsert(recordWithGeneration(
                "6f221541-64ae-8c32-9f22-c44f515cd6a0", 401L, 0L
        ));
        gateway.upsert(recordWithGeneration(
                "f7dcc320-8d47-8d9a-8407-a58b99f9812b", 402L, 1L
        ));
        gateway.upsert(record(
                "fdafcd5a-e66a-8e7e-8a02-0ea311d084a1", 403L, 101L, 201L, 301L, List.of(1.0f, 0.0f)
        ));

        gateway.deleteByDocumentScope(VectorDocumentScope.forGenerationCleanup(101L, 201L, 301L, 0L));

        assertThat(searchChunkIds(gateway, 101L, 201L)).containsExactly(402L);
    }

    private static List<Long> searchChunkIds(InMemoryVectorStoreGateway gateway, long userId, long knowledgeBaseId) {
        return gateway.search(new VectorSearchRequest(
                new EmbeddingVector(List.of(1.0f, 0.0f)), userId, knowledgeBaseId, 10
        )).stream().map(VectorSearchHit::chunkId).toList();
    }

    private static VectorStoreRecord record(
            String vectorId,
            long chunkId,
            long userId,
            long knowledgeBaseId,
            long documentId,
            List<Float> vector
    ) {
        return new VectorStoreRecord(
                vectorId,
                new EmbeddingVector(vector),
                Map.of(
                        "chunkId", chunkId,
                        "userId", userId,
                        "knowledgeBaseId", knowledgeBaseId,
                        "documentId", documentId
                )
        );
    }

    private static VectorStoreRecord recordWithGeneration(String vectorId, long chunkId, long generation) {
        return new VectorStoreRecord(
                vectorId,
                new EmbeddingVector(List.of(1.0f, 0.0f)),
                Map.of(
                        "chunkId", chunkId,
                        "userId", 101L,
                        "knowledgeBaseId", 201L,
                        "documentId", 301L,
                        "vectorGeneration", generation
                )
        );
    }
}
