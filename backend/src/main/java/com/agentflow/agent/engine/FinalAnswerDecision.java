package com.agentflow.agent.engine;

/** A model decision that enough evidence exists to run final-answer generation. */
public record FinalAnswerDecision(String answerPlan) implements AgentDecision {
    public FinalAnswerDecision {
        if (answerPlan == null || answerPlan.isBlank()) {
            throw new IllegalArgumentException("answerPlan must not be blank");
        }
    }
}
