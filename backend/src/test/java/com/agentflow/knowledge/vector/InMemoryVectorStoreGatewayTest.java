package com.agentflow.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryVectorStoreGatewayTest {

    @Test
    void shouldReturnOnlyTheRequestedOwnerAndKnowledgeBaseInSimilarityOrder() {
        InMemoryVectorStoreGateway gateway = new InMemoryVectorStoreGateway();
        gateway.upsert(record(
                "6f221541-64ae-8c32-9f22-c44f515cd6a0", 401L, 101L, 201L, List.of(1.0f, 0.0f)
        ));
        gateway.upsert(record(
                "f7dcc320-8d47-8d9a-8407-a58b99f9812b", 402L, 101L, 201L, List.of(0.8f, 0.2f)
        ));
        gateway.upsert(record(
                "fdafcd5a-e66a-8e7e-8a02-0ea311d084a1", 403L, 102L, 201L, List.of(1.0f, 0.0f)
        ));

        List<VectorSearchHit> hits = gateway.search(new VectorSearchRequest(
                new EmbeddingVector(List.of(1.0f, 0.0f)), 101L, 201L, 5
        ));

        assertThat(hits).extracting(VectorSearchHit::chunkId).containsExactly(401L, 402L);
    }

    private static VectorStoreRecord record(
            String vectorId,
            long chunkId,
            long userId,
            long knowledgeBaseId,
            List<Float> vector
    ) {
        return new VectorStoreRecord(
                vectorId,
                new EmbeddingVector(vector),
                Map.of("chunkId", chunkId, "userId", userId, "knowledgeBaseId", knowledgeBaseId)
        );
    }
}
