package com.agentflow.agent.task.model;

public enum TaskTerminationReason {
    ANSWERED,
    MAX_DECISION_TURNS,
    MAX_TOOL_CALLS,
    TOKEN_BUDGET_EXHAUSTED,
    DEADLINE_EXCEEDED,
    USER_CANCELLED,
    SYSTEM_ERROR
}
