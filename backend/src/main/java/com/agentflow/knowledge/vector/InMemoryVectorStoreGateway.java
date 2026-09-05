package com.agentflow.knowledge.vector;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Development-only vector-store adapter. {@link ConcurrentMap#put(Object, Object)} has
 * the same overwrite semantics V5 requires from a future Qdrant upsert, without hiding
 * missing Qdrant configuration behind a fake network client.
 */
public final class InMemoryVectorStoreGateway implements VectorStoreGateway {
    private final ConcurrentMap<String, VectorStoreRecord> records = new ConcurrentHashMap<>();

    @Override
    public void upsert(VectorStoreRecord record) {
        VectorStoreRecord safeRecord = Objects.requireNonNull(record, "record must not be null");
        records.put(safeRecord.vectorId(), safeRecord);
    }

    @Override
    public void deleteByDocumentScope(VectorDocumentScope scope) {
        VectorDocumentScope safeScope = Objects.requireNonNull(scope, "scope must not be null");
        records.entrySet().removeIf(entry -> matchesDocumentScope(entry.getValue(), safeScope));
    }

    @Override
    public List<VectorSearchHit> search(VectorSearchRequest request) {
        VectorSearchRequest safeRequest = Objects.requireNonNull(request, "request must not be null");
        return records.values().stream()
                .filter(record -> matchesScope(record, safeRequest))
                .map(record -> toSearchHit(record, safeRequest.vector()))
                .sorted(Comparator.comparingDouble(VectorSearchHit::score)
                        .reversed()
                        .thenComparing(VectorSearchHit::vectorId))
                .limit(safeRequest.limit())
                .toList();
    }

    private static boolean matchesScope(VectorStoreRecord record, VectorSearchRequest request) {
        return payloadLongEquals(record, "userId", request.userId())
                && payloadLongEquals(record, "knowledgeBaseId", request.knowledgeBaseId())
                && (request.documents().isEmpty() || request.documents().stream().anyMatch(document ->
                        payloadLongEquals(record, "documentId", document.documentId())
                                && payloadLongEquals(record, "vectorGeneration", document.vectorGeneration())));
    }

    private static boolean matchesDocumentScope(VectorStoreRecord record, VectorDocumentScope scope) {
        boolean scoped = payloadLongEquals(record, "userId", scope.userId())
                && payloadLongEquals(record, "knowledgeBaseId", scope.knowledgeBaseId())
                && payloadLongEquals(record, "documentId", scope.documentId());
        if (!scoped || !scope.generationFenced()) {
            return scoped;
        }
        Object generation = record.payload().get("vectorGeneration");
        if (generation instanceof Number number) {
            return number.longValue() == scope.vectorGeneration();
        }
        return scope.includeLegacyMissingGeneration() && generation == null;
    }

    private static VectorSearchHit toSearchHit(VectorStoreRecord record, EmbeddingVector queryVector) {
        Object chunkId = record.payload().get("chunkId");
        if (!(chunkId instanceof Number number) || number.longValue() <= 0) {
            throw new IllegalStateException("In-memory vector record is missing a positive chunkId payload");
        }
        return new VectorSearchHit(
                record.vectorId(),
                number.longValue(),
                cosineSimilarity(queryVector, record.vector()),
                record.payload().get("contentHash") instanceof String hash ? hash : null
        );
    }

    private static boolean payloadLongEquals(VectorStoreRecord record, String key, long expected) {
        Object value = record.payload().get(key);
        return value instanceof Number number && number.longValue() == expected;
    }

    private static double cosineSimilarity(EmbeddingVector left, EmbeddingVector right) {
        if (left.values().size() != right.values().size()) {
            throw new IllegalArgumentException("Vector dimensions must match for in-memory search");
        }
        double dotProduct = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.values().size(); index++) {
            float leftValue = left.values().get(index);
            float rightValue = right.values().get(index);
            dotProduct += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            throw new IllegalArgumentException("Vectors must have a non-zero norm for cosine search");
        }
        return dotProduct / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
