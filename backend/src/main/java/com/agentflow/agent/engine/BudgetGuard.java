package com.agentflow.agent.engine;

import com.agentflow.infra.llm.LlmTokenUsage;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * In-memory V36 budget accounting. It checks the overall deadline only at call boundaries;
 * interrupting a currently blocked synchronous call belongs to a later worker slice.
 */
public final class BudgetGuard {
    public static final int THINKING_MAX_OUTPUT_TOKENS = 512;
    public static final int FINAL_ANSWER_MAX_OUTPUT_TOKENS = 2_048;

    private final int maxSteps;
    private final int maxToolCalls;
    private final int maxTokens;
    private final Clock clock;
    private final Instant startedAt;
    private final Instant deadlineAt;

    private int stepsUsed;
    private int toolCallsUsed;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;

    public BudgetGuard(AgentExecutionConfigSnapshot snapshot, Clock clock) {
        this(snapshot, clock, Objects.requireNonNull(clock, "clock must not be null").instant());
    }

    public BudgetGuard(
            AgentExecutionConfigSnapshot snapshot,
            Clock clock,
            Instant startedAt
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.deadlineAt = startedAt.plusSeconds(snapshot.timeoutSeconds());
        this.maxSteps = snapshot.maxSteps();
        this.maxToolCalls = snapshot.maxToolCalls();
        this.maxTokens = snapshot.maxTokens();
    }

    /** Reserves one model decision round and returns its remaining-budget-aware output cap. */
    public int beginDecisionCall() {
        ensureExternalCallAllowed();
        if (stepsUsed >= maxSteps) {
            throw failure(
                    AgentFailureType.STEP_LIMIT_EXCEEDED,
                    "Agent decision-step budget is exhausted"
            );
        }
        stepsUsed++;
        return outputLimit(THINKING_MAX_OUTPUT_TOKENS);
    }

    /** Returns the final generation cap without consuming a decision step. */
    public int beginFinalAnswerCall() {
        ensureExternalCallAllowed();
        return outputLimit(FINAL_ANSWER_MAX_OUTPUT_TOKENS);
    }

    /** Records provider-measured usage and rejects unknown or over-budget results. */
    public void completeLlmCall(LlmTokenUsage usage) {
        checkDeadline();
        if (usage == null || !usage.known()) {
            throw failure(
                    AgentFailureType.TOKEN_USAGE_UNKNOWN,
                    "LLM token usage is unavailable"
            );
        }

        long nextTotal = totalTokens + usage.totalTokens();
        if (nextTotal > maxTokens) {
            throw failure(
                    AgentFailureType.TOKEN_LIMIT_EXCEEDED,
                    "Agent token budget is exhausted"
            );
        }
        inputTokens += usage.inputTokens();
        outputTokens += usage.outputTokens();
        totalTokens = nextTotal;
    }

    /** Accepts one already parsed and available TOOL_CALL immediately before runtime I/O. */
    public void beginToolCall() {
        ensureExternalCallAllowed();
        if (toolCallsUsed >= maxToolCalls) {
            throw failure(
                    AgentFailureType.TOOL_CALL_LIMIT_EXCEEDED,
                    "Agent tool-call budget is exhausted"
            );
        }
        toolCallsUsed++;
    }

    /** Applies the V36 post-tool boundary deadline check. */
    public void completeToolCall() {
        checkDeadline();
    }

    /** Checks the deadline and confirms some measured token budget remains before more I/O. */
    public void checkBoundary() {
        ensureExternalCallAllowed();
    }

    /** Checks only time, for choosing timeout over a caught provider/tool failure. */
    public void checkDeadline() {
        if (!clock.instant().isBefore(deadlineAt)) {
            throw failure(
                    AgentFailureType.EXECUTION_TIMEOUT,
                    "Agent execution deadline is exceeded"
            );
        }
    }

    public int stepsUsed() {
        return stepsUsed;
    }

    public int toolCallsUsed() {
        return toolCallsUsed;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long totalTokens() {
        return totalTokens;
    }

    public int remainingTokens() {
        return maxTokens - Math.toIntExact(totalTokens);
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant deadlineAt() {
        return deadlineAt;
    }

    private void ensureExternalCallAllowed() {
        checkDeadline();
        if (remainingTokens() < 1) {
            throw failure(
                    AgentFailureType.TOKEN_LIMIT_EXCEEDED,
                    "Agent token budget is exhausted"
            );
        }
    }

    private int outputLimit(int perCallLimit) {
        return Math.min(perCallLimit, remainingTokens());
    }

    private static AgentExecutionException failure(AgentFailureType type, String message) {
        return new AgentExecutionException(type, message);
    }
}
