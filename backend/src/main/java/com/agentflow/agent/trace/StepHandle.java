package com.agentflow.agent.trace;

import java.util.Objects;

/** Opaque task-bound identity returned after a RUNNING step has committed. */
public record StepHandle(long taskId, long stepId, int stepIndex, StepType stepType) {
    public StepHandle {
        if (taskId <= 0 || stepId <= 0 || stepIndex < 0) {
            throw new IllegalArgumentException("Step handle IDs and index are invalid");
        }
        Objects.requireNonNull(stepType, "stepType must not be null");
    }
}
