package com.agentflow.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.AgentSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.ChatModelSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.DocumentGenerationSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.KnowledgeBaseSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.RetrievalSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.RuntimeSnapshot;
import com.agentflow.agent.snapshot.AgentTaskSnapshotResolver;
import com.agentflow.agent.task.execution.TaskExecutionRequest;
import com.agentflow.agent.trace.RagRetrievalHitRecord;
import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.vector.ChunkVectorIdentityFactory;
import com.agentflow.knowledge.vector.EmbeddingGateway;
import com.agentflow.knowledge.vector.EmbeddingVector;
import com.agentflow.knowledge.vector.VectorSearchHit;
import com.agentflow.knowledge.vector.VectorSearchRequest;
import com.agentflow.knowledge.vector.VectorSearchRequest.DocumentGeneration;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SnapshotRagServiceTest {
    private final KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
    private final EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
    private final VectorStoreGateway vectors = mock(VectorStoreGateway.class);
    private final SnapshotRagService service = new SnapshotRagService(chunks, embeddings, vectors);

    @BeforeEach
    void embeddingProfile() {
        when(embeddings.embed(any())).thenReturn(new EmbeddingVector(Collections.nCopies(1024, 1.0f)));
    }

    @Test
    void freezesCorpusAndKeepsOnlyCanonicalCurrentScopedHashMatchedContent() {
        KnowledgeChunk valid = chunk(401, 301, 7, "Canonical PostgreSQL content");
        KnowledgeChunk replacedGeneration = chunk(402, 301, 8, "replacement must not leak");
        KnowledgeChunk unboundDocument = chunk(403, 399, 7, "unbound must not leak");
        KnowledgeChunk wrongOwner = chunk(404, 301, 7, "foreign owner must not leak");
        wrongOwner.setUserId(999L);
        KnowledgeChunk changedContent = chunk(405, 301, 7, "old content");
        changedContent.setContent("mutated without vectorization");
        KnowledgeChunk missingPayloadHash = chunk(406, 301, 7, "unproven vector payload");
        List<KnowledgeChunk> rows = List.of(valid, replacedGeneration, unboundDocument, wrongOwner,
                changedContent, missingPayloadHash);
        List<VectorSearchHit> hits = new ArrayList<>(rows.stream().limit(5).map(SnapshotRagServiceTest::hit).toList());
        hits.add(new VectorSearchHit(missingPayloadHash.getVectorId(), 406, 0.8));
        when(vectors.search(any())).thenReturn(hits);
        when(chunks.selectSnapshotRetrievableChunks(anyLong(), anyLong(), anyList(), anyString(), anyList()))
                .thenReturn(rows);

        SnapshotRagResult result = service.retrieve(request(corpus()), () -> { });

        assertThat(result.hits()).extracting(RagRetrievalHitRecord::chunkIdSnapshot).containsExactly(401L);
        assertThat(result.hits().getFirst().citationId()).isEqualTo("S1");
        assertThat(result.evidence()).contains("UNTRUSTED", "[S1]", "Canonical PostgreSQL content")
                .doesNotContain("must not leak", "mutated", "unproven");
        assertThat(result.candidateCount()).isEqualTo(6);
        assertThat(result.staleHitCount()).isEqualTo(5);
        ArgumentCaptor<VectorSearchRequest> search = ArgumentCaptor.forClass(VectorSearchRequest.class);
        verify(vectors).search(search.capture());
        assertThat(search.getValue().userId()).isEqualTo(101);
        assertThat(search.getValue().knowledgeBaseId()).isEqualTo(201);
        assertThat(search.getValue().documents()).containsExactly(new DocumentGeneration(301, 7));
        assertThat(search.getValue().limit()).isEqualTo(20);
    }

    @Test
    void revokedOrCleanedGenerationIsEmptyEvidenceAndDoesNotFallback() {
        when(vectors.search(any())).thenReturn(List.of(hit(chunk(401, 301, 7, "revoked"))));
        when(chunks.selectSnapshotRetrievableChunks(anyLong(), anyLong(), anyList(), anyString(), anyList()))
                .thenReturn(List.of());
        SnapshotRagResult result = service.retrieve(request(corpus()), () -> { });
        assertThat(result.hits()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.staleHitCount()).isEqualTo(1);
    }

    @Test
    void boundsEvidenceAndTraceContentAtUnicodeCodePointBoundaries() {
        List<KnowledgeChunk> rows = java.util.stream.LongStream.range(401, 411)
                .mapToObj(id -> chunk(id, 301, 7, "资料😀".repeat(2000))).toList();
        when(vectors.search(any())).thenReturn(rows.stream().map(SnapshotRagServiceTest::hit).toList());
        when(chunks.selectSnapshotRetrievableChunks(anyLong(), anyLong(), anyList(), anyString(), anyList()))
                .thenReturn(rows);
        SnapshotRagResult result = service.retrieve(request(new RetrievalSnapshot(corpus().knowledgeBases(), 10,
                BigDecimal.ZERO, false)), () -> { });
        assertThat(result.evidence().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(SnapshotRagService.MAX_EVIDENCE_BYTES);
        assertThat(result.hits()).isNotEmpty();
        for (RagRetrievalHitRecord hit : result.hits()) {
            assertThat(hit.contentSnapshot().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(SnapshotRagService.MAX_CONTENT_BYTES);
            assertThat(hit.contentSnapshot()).doesNotContain("�");
            assertThat(Character.isHighSurrogate(hit.contentSnapshot().charAt(hit.contentSnapshot().length() - 1)))
                    .isFalse();
            assertThat(hit.metadataSnapshot().path("contentTruncated").asBoolean()).isTrue();
        }
    }

    @Test
    void boundedMultiKnowledgeBaseSearchVisitsEveryScopeBeforeSelectingGlobalTopK() {
        List<KnowledgeBaseSnapshot> knowledgeBases = new ArrayList<>();
        java.util.Map<Long, List<KnowledgeChunk>> rowsByKnowledgeBase = new java.util.HashMap<>();
        for (long kbId = 201; kbId <= 220; kbId++) {
            long documentId = kbId + 100;
            knowledgeBases.add(new KnowledgeBaseSnapshot(Long.toString(kbId),
                    AgentTaskSnapshotResolver.EMBEDDING_PROFILE_CODE,
                    AgentTaskSnapshotResolver.CHUNK_STRATEGY_VERSION,
                    List.of(new DocumentGenerationSnapshot(Long.toString(documentId), 7))));
            List<KnowledgeChunk> rows = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                KnowledgeChunk row = chunk(kbId * 10 + index, documentId, 7, "Evidence " + kbId + "/" + index);
                row.setKnowledgeBaseId(kbId);
                rows.add(row);
            }
            rowsByKnowledgeBase.put(kbId, rows);
        }
        when(vectors.search(any())).thenAnswer(invocation -> {
            VectorSearchRequest search = invocation.getArgument(0);
            assertThat(search.limit()).isEqualTo(10);
            return rowsByKnowledgeBase.get(search.knowledgeBaseId()).stream().map(chunk ->
                    new VectorSearchHit(chunk.getVectorId(), chunk.getId(),
                            (search.knowledgeBaseId() - 200) / 20.0, chunk.getContentHash())).toList();
        });
        when(chunks.selectSnapshotRetrievableChunks(anyLong(), anyLong(), anyList(), anyString(), anyList()))
                .thenAnswer(invocation -> rowsByKnowledgeBase.get(invocation.<Long>getArgument(1)));

        SnapshotRagResult result = service.retrieve(request(new RetrievalSnapshot(knowledgeBases, 10,
                BigDecimal.ZERO, false)), () -> { });

        assertThat(result.candidateCount()).isEqualTo(SnapshotRagService.MAX_CANDIDATES);
        assertThat(result.hits()).hasSize(10)
                .allSatisfy(hit -> assertThat(hit.knowledgeBaseIdSnapshot()).isEqualTo(220));
        verify(vectors, org.mockito.Mockito.times(20)).search(any());
    }

    @Test
    void duplicateLocatorsKeepHighestValidScoreWithoutDuplicatingCitations() {
        KnowledgeChunk row = chunk(401, 301, 7, "canonical");
        when(vectors.search(any())).thenReturn(List.of(
                new VectorSearchHit(row.getVectorId(), 401, 0.1, row.getContentHash()),
                new VectorSearchHit(row.getVectorId(), 401, 0.9, row.getContentHash()),
                new VectorSearchHit(row.getVectorId(), 401, 0.8, row.getContentHash())));
        when(chunks.selectSnapshotRetrievableChunks(anyLong(), anyLong(), anyList(), anyString(), anyList()))
                .thenReturn(List.of(row));
        SnapshotRagResult result = service.retrieve(request(corpus()), () -> { });
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().getFirst().score()).isEqualByComparingTo("0.9");
        assertThat(result.candidateCount()).isEqualTo(3);
        assertThat(result.staleHitCount()).isZero();
    }

    @Test
    void noCorpusMakesNoExternalCallsAndProfileMismatchFailsClosed() {
        SnapshotRagResult empty = service.retrieve(request(new RetrievalSnapshot(List.of(), 5,
                BigDecimal.ZERO, false)), () -> { });
        assertThat(empty.hits()).isEmpty();
        verifyNoInteractions(vectors, chunks);
        when(embeddings.embed(any())).thenReturn(new EmbeddingVector(List.of(1.0f)));
        assertThatThrownBy(() -> service.retrieve(request(corpus()), () -> { }))
                .isInstanceOf(SnapshotRagException.class).hasMessageContaining("frozen profile");
        verifyNoInteractions(vectors, chunks);
    }

    @Test
    void postEmbeddingCancellationPreventsVectorSearchEvenWhenProviderFails() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger checks = new AtomicInteger();
        RuntimeException cancellation = new IllegalStateException("shared cancellation boundary");
        when(embeddings.embed(any())).thenAnswer(invocation -> {
            cancelled.set(true);
            throw new IllegalStateException("provider secret");
        });
        assertThatThrownBy(() -> service.retrieve(request(corpus()), () -> {
            checks.incrementAndGet();
            if (cancelled.get()) {
                throw cancellation;
            }
        })).isSameAs(cancellation);
        assertThat(checks.get()).isEqualTo(3);
        verifyNoInteractions(vectors, chunks);
    }

    @Test
    void vectorFailureProducesSafeStableErrorAndChecksBoundaryAfterFailure() {
        AtomicInteger checks = new AtomicInteger();
        when(vectors.search(any())).thenThrow(new IllegalStateException("provider credential or raw body"));
        assertThatThrownBy(() -> service.retrieve(request(corpus()), checks::incrementAndGet))
                .isInstanceOf(SnapshotRagException.class)
                .hasMessage("Snapshot vector search failed")
                .hasNoCause();
        assertThat(checks.get()).isEqualTo(5);
        verifyNoInteractions(chunks);
    }

    private static RetrievalSnapshot corpus() {
        return new RetrievalSnapshot(List.of(new KnowledgeBaseSnapshot("201",
                AgentTaskSnapshotResolver.EMBEDDING_PROFILE_CODE, AgentTaskSnapshotResolver.CHUNK_STRATEGY_VERSION,
                List.of(new DocumentGenerationSnapshot("301", 7)))), 5, new BigDecimal("0.2"), false);
    }

    private static TaskExecutionRequest request(RetrievalSnapshot retrieval) {
        AgentTaskExecutionSnapshot snapshot = new AgentTaskExecutionSnapshot("agent-task-snapshot-v1",
                new AgentSnapshot("201", "frozen prompt", "ACTIVE", 6, 4, 8000, 120),
                new RuntimeSnapshot("agent-decision-json-v2", "agent-runtime-rules-v2", "test"),
                new ChatModelSnapshot("model", "openai-compatible", "model", BigDecimal.ZERO,
                        BigDecimal.ONE, 32768, true), retrieval, List.of());
        return new TaskExecutionRequest(1, 101, 201, "Question", snapshot, Instant.now().plusSeconds(120), () -> false);
    }

    private static KnowledgeChunk chunk(long id, long documentId, long generation, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setUserId(101L);
        chunk.setKnowledgeBaseId(201L);
        chunk.setDocumentId(documentId);
        chunk.setVectorGeneration(generation);
        chunk.setChunkIndex((int) id);
        chunk.setChunkStrategyVersion(AgentTaskSnapshotResolver.CHUNK_STRATEGY_VERSION);
        chunk.setVectorizationStatus("COMPLETED");
        chunk.setContent(content);
        chunk.setDocumentFileName("rules.md");
        var identity = ChunkVectorIdentityFactory.create(chunk);
        chunk.setContentHash(identity.contentHash());
        chunk.setVectorId(identity.vectorId());
        return chunk;
    }

    private static VectorSearchHit hit(KnowledgeChunk chunk) {
        return new VectorSearchHit(chunk.getVectorId(), chunk.getId(), 0.8, chunk.getContentHash());
    }
}
