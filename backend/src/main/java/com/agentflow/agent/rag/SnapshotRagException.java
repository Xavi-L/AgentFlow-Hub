package com.agentflow.agent.rag;

/** Stable task-level RAG failure without leaking provider response or document content. */
public final class SnapshotRagException extends RuntimeException {
    public SnapshotRagException(String message) {
        super(message);
    }

    public String errorCode() {
        return "RAG_RETRIEVAL_FAILED";
    }
}
