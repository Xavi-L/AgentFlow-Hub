package com.agentflow.tool;

/** Safe internal task-tool diagnostics, separate from public standalone HTTP errors. */
public final class ToolTaskExecutionException extends RuntimeException {
    private final String errorCode;

    public ToolTaskExecutionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
