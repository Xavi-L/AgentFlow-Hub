package com.agentflow.knowledge.chat;

/** Internal marker for an answer that cannot be safely tied back to V8 source markers. */
public final class CitationValidationException extends RuntimeException {
    CitationValidationException(String message) {
        super(message);
    }
}
