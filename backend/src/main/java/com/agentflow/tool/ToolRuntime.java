package com.agentflow.tool;

/** Unified entry point for executing a persisted tool definition. */
public interface ToolRuntime {
    ToolExecutionResult execute(ToolExecutionCommand command);

    /** Recheck task authorization before reusing an observation; creates no actual-call log. */
    default void validateTaskSnapshot(ToolExecutionCommand command) {
        throw new UnsupportedOperationException("Task snapshot validation is not configured");
    }
}
