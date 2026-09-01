package com.agentflow.agent.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Mutable, execution-local context; no state is persisted by V36. */
public final class AgentExecutionContext {
    private final AgentExecutionCommand command;
    private final AgentExecutionConfigSnapshot configSnapshot;
    private final List<AgentToolSpec> availableTools;
    private final List<AgentObservation> observations = new ArrayList<>();
    private final BudgetGuard budgetGuard;

    public AgentExecutionContext(
            AgentExecutionCommand command,
            AgentExecutionConfigSnapshot configSnapshot,
            List<AgentToolSpec> availableTools,
            BudgetGuard budgetGuard
    ) {
        this.command = Objects.requireNonNull(command, "command must not be null");
        this.configSnapshot = Objects.requireNonNull(configSnapshot, "configSnapshot must not be null");
        this.availableTools = List.copyOf(
                Objects.requireNonNull(availableTools, "availableTools must not be null")
        );
        this.budgetGuard = Objects.requireNonNull(budgetGuard, "budgetGuard must not be null");
    }

    public AgentExecutionCommand command() {
        return command;
    }

    public AgentExecutionConfigSnapshot configSnapshot() {
        return configSnapshot;
    }

    public List<AgentToolSpec> availableTools() {
        return availableTools;
    }

    public List<AgentObservation> observations() {
        return List.copyOf(observations);
    }

    public void appendObservation(AgentObservation observation) {
        observations.add(Objects.requireNonNull(observation, "observation must not be null"));
    }

    public BudgetGuard budgetGuard() {
        return budgetGuard;
    }

    public Instant startedAt() {
        return budgetGuard.startedAt();
    }

    public Instant deadlineAt() {
        return budgetGuard.deadlineAt();
    }
}
