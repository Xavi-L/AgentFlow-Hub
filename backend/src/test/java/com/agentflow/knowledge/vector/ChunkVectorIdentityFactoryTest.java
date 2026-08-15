package com.agentflow.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.knowledge.model.KnowledgeChunk;
import org.junit.jupiter.api.Test;

class ChunkVectorIdentityFactoryTest {

    @Test
    void shouldHashExactUtf8ContentAndDeriveAStableScopeAwareUuid() {
        KnowledgeChunk first = chunk(101L, 201L, 301L, 0, "退款规则：原路退回");

        ChunkVectorIdentity firstIdentity = ChunkVectorIdentityFactory.create(first);
        ChunkVectorIdentity repeatedIdentity = ChunkVectorIdentityFactory.create(first);
        ChunkVectorIdentity sameTextDifferentDocument = ChunkVectorIdentityFactory.create(
                chunk(101L, 201L, 302L, 0, "退款规则：原路退回")
        );

        assertThat(firstIdentity.contentHash())
                .isEqualTo("cedb037e64b1ab45d186676269f3787678162a29218e0c837939ddc1479f9778");
        assertThat(firstIdentity.vectorId()).isEqualTo(repeatedIdentity.vectorId());
        assertThat(firstIdentity.vectorId()).isNotEqualTo(sameTextDifferentDocument.vectorId());
        assertThat(firstIdentity.vectorId()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
        );
    }

    @Test
    void shouldChangeBothHashAndVectorIdWhenContentChanges() {
        ChunkVectorIdentity original = ChunkVectorIdentityFactory.create(
                chunk(101L, 201L, 301L, 0, "Refund rules")
        );
        ChunkVectorIdentity changed = ChunkVectorIdentityFactory.create(
                chunk(101L, 201L, 301L, 0, "Refund rules updated")
        );

        assertThat(changed.contentHash()).isNotEqualTo(original.contentHash());
        assertThat(changed.vectorId()).isNotEqualTo(original.vectorId());
    }

    private static KnowledgeChunk chunk(
            Long userId,
            Long knowledgeBaseId,
            Long documentId,
            int chunkIndex,
            String content
    ) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setUserId(userId);
        chunk.setKnowledgeBaseId(knowledgeBaseId);
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        return chunk;
    }
}
