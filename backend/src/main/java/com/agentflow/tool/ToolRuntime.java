package com.agentflow.tool;

/** Unified entry point for executing a persisted tool definition. */
public interface ToolRuntime {
    ToolExecutionResult execute(ToolExecutionCommand command);
}
