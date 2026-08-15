package com.agentflow.knowledge.vector;

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
}
