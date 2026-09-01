package com.agentflow.agent.engine;

/** Stable internal V36 failure categories; these are not public HTTP error codes. */
public enum AgentFailureType {
    INVALID_COMMAND,
    AGENT_NOT_FOUND,
    AGENT_DISABLED,
    INVALID_AGENT_CONFIG,
    INVALID_DECISION,
    STEP_LIMIT_EXCEEDED,
    TOOL_CALL_LIMIT_EXCEEDED,
    TOKEN_LIMIT_EXCEEDED,
    TOKEN_USAGE_UNKNOWN,
    EXECUTION_TIMEOUT,
    LLM_FAILURE,
    TOOL_FAILURE
}
