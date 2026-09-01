package com.agentflow.infra.llm;

/** Stable internal failure categories for later Agent task error mapping. */
public enum LlmFailureType {
    CONFIGURATION,
    TIMEOUT,
    TRANSPORT,
    PROVIDER_REJECTED,
    MALFORMED_RESPONSE
}
