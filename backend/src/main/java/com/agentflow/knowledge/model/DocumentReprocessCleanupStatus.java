package com.agentflow.knowledge.model;

/** Durable states for V25's external-vector cleanup and final database transaction. */
public enum DocumentReprocessCleanupStatus {
    VECTOR_DELETING,
    VECTOR_DELETE_RETRYABLE,
    READY_TO_FINALIZE,
    COMPLETED
}
