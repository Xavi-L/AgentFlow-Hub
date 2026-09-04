package com.agentflow.agent.trace;

/** The two model calls represented in the V39 Trace schema. */
public enum LlmCallType {
    DECISION(StepType.LLM_DECISION),
    FINAL_GENERATION(StepType.LLM_FINAL_GENERATION);

    private final StepType requiredStepType;

    LlmCallType(StepType requiredStepType) {
        this.requiredStepType = requiredStepType;
    }

    public StepType requiredStepType() {
        return requiredStepType;
    }
}
