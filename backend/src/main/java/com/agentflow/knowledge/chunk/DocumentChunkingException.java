package com.agentflow.knowledge.chunk;

/** A controlled, non-sensitive parsing-pipeline failure caused by unusable text. */
public class DocumentChunkingException extends IllegalArgumentException {
    public DocumentChunkingException(String message) {
        super(message);
    }
}
