package com.agentflow.agent.engine;

/** Strictly parsed model action for one decision round. */
public sealed interface AgentDecision permits ToolCallDecision, FinalAnswerDecision {
}
