package com.agentflow.knowledge.vector;

/** The request may have reached the vector store, so database state must keep deletion gated. */
public class VectorStoreOutcomeUnknownException extends RuntimeException {
    public VectorStoreOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
