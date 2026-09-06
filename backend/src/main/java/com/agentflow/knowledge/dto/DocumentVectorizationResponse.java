package com.agentflow.knowledge.dto;

/** Counts only persisted chunks in the document's current vector generation. */
public record DocumentVectorizationResponse(long pending, long processing, long completed, long failed) {
}
