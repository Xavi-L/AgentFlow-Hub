package com.agentflow.agent.engine;

/** Safe internal signal; provider and tool exception messages never become user diagnostics. */
final class TaskExecutionAbort extends RuntimeException {
    private final String code;

    TaskExecutionAbort(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    String code() { return code; }
}
