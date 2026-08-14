package com.agentflow.knowledge.chunk;

/** A half-open UTF-16 String range occupied by one lightweight estimated token. */
public record TokenSpan(int startOffset, int endOffset) {
    public TokenSpan {
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("A token span must be non-empty and non-negative");
        }
    }
}
