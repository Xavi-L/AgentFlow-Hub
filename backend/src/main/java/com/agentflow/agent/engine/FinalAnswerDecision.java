package com.agentflow.agent.engine;

/** A model decision that enough evidence exists to run final-answer generation. */
public record FinalAnswerDecision(String answerDraft) implements AgentDecision {
    public FinalAnswerDecision {
        if (answerDraft == null || answerDraft.isBlank()) {
            throw new IllegalArgumentException("answerDraft must not be blank");
        }
    }
}
