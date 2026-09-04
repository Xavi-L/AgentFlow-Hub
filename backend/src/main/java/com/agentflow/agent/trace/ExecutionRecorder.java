package com.agentflow.agent.trace;

/** Task-bound persistence boundary for committed execution facts. */
public interface ExecutionRecorder {
    StepHandle startStep(StepType type, String title);

    void completeStep(StepHandle step, StepSummary summary);

    void failStep(StepHandle step, String errorCode, String safeMessage);

    void recordLlmCall(LlmCallRecord record);

    void recordRagRetrieval(RagRetrievalRecord record);

    void appendEvent(TaskEventRecord event);
}
