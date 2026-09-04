package com.agentflow.agent.trace;

/** Semantic execution steps persisted by the task-scoped Trace recorder. */
public enum StepType {
    PRE_RETRIEVAL,
    LLM_DECISION,
    TOOL_CALL,
    LLM_FINAL_GENERATION
}
