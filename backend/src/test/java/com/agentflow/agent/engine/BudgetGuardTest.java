package com.agentflow.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.infra.llm.LlmTokenUsage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BudgetGuardTest {

    @Test
    void shouldApplyIndependentPerCallCapsAndAccumulateKnownUsage() {
        BudgetGuard guard = guard(6, 4, 8_000, 120, fixedClock());

        assertThat(guard.beginDecisionCall()).isEqualTo(512);
        guard.completeLlmCall(LlmTokenUsage.known(70, 30, 100));
        assertThat(guard.beginFinalAnswerCall()).isEqualTo(2_048);
        guard.completeLlmCall(LlmTokenUsage.known(80, 20, 100));

        assertThat(guard.stepsUsed()).isEqualTo(1);
        assertThat(guard.toolCallsUsed()).isZero();
        assertThat(guard.inputTokens()).isEqualTo(150);
        assertThat(guard.outputTokens()).isEqualTo(50);
        assertThat(guard.totalTokens()).isEqualTo(200);
        assertThat(guard.remainingTokens()).isEqualTo(7_800);
    }

    @Test
    void shouldStopBeforeExternalIoWhenStepOrToolCallLimitsAreExhausted() {
        BudgetGuard stepGuard = guard(2, 1, 8_000, 120, fixedClock());
        stepGuard.beginDecisionCall();
        stepGuard.completeLlmCall(LlmTokenUsage.known(1, 1, 2));
        stepGuard.beginDecisionCall();
        stepGuard.completeLlmCall(LlmTokenUsage.known(1, 1, 2));
        assertFailure(stepGuard::beginDecisionCall, AgentFailureType.STEP_LIMIT_EXCEEDED);

        BudgetGuard toolGuard = guard(2, 1, 8_000, 120, fixedClock());
        toolGuard.beginToolCall();
        toolGuard.completeToolCall();
        assertFailure(toolGuard::beginToolCall, AgentFailureType.TOOL_CALL_LIMIT_EXCEEDED);
        assertThat(toolGuard.toolCallsUsed()).isEqualTo(1);
    }

    @Test
    void shouldCapToRemainingTokensAndStopAllLaterIoAtZero() {
        BudgetGuard guard = guard(2, 1, 256, 120, fixedClock());

        assertThat(guard.beginDecisionCall()).isEqualTo(256);
        guard.completeLlmCall(LlmTokenUsage.known(200, 56, 256));

        assertThat(guard.remainingTokens()).isZero();
        assertFailure(guard::beginToolCall, AgentFailureType.TOKEN_LIMIT_EXCEEDED);
        assertFailure(guard::beginFinalAnswerCall, AgentFailureType.TOKEN_LIMIT_EXCEEDED);
        assertThat(guard.toolCallsUsed()).isZero();
    }

    @Test
    void shouldRejectOverBudgetAndUnknownUsageWithoutTreatingUnknownAsZero() {
        BudgetGuard overBudget = guard(2, 1, 256, 120, fixedClock());
        overBudget.beginDecisionCall();
        assertFailure(
                () -> overBudget.completeLlmCall(LlmTokenUsage.known(200, 57, 257)),
                AgentFailureType.TOKEN_LIMIT_EXCEEDED
        );

        BudgetGuard unknown = guard(2, 1, 256, 120, fixedClock());
        unknown.beginDecisionCall();
        assertFailure(
                () -> unknown.completeLlmCall(LlmTokenUsage.unknown()),
                AgentFailureType.TOKEN_USAGE_UNKNOWN
        );
        assertThat(unknown.totalTokens()).isZero();
    }

    @Test
    void shouldTreatTheExactDeadlineAsExpiredAtEveryBoundary() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T02:00:00Z"));
        BudgetGuard guard = guard(2, 1, 256, 1, clock);
        assertThat(guard.deadlineAt()).isEqualTo(Instant.parse("2026-09-01T02:00:01Z"));

        clock.advanceSeconds(1);

        assertFailure(guard::beginDecisionCall, AgentFailureType.EXECUTION_TIMEOUT);
        assertFailure(guard::beginToolCall, AgentFailureType.EXECUTION_TIMEOUT);
        assertFailure(guard::completeToolCall, AgentFailureType.EXECUTION_TIMEOUT);
    }

    private static BudgetGuard guard(
            int maxSteps,
            int maxToolCalls,
            int maxTokens,
            int timeoutSeconds,
            Clock clock
    ) {
        return new BudgetGuard(new AgentExecutionConfigSnapshot(
                "system",
                "openai-compatible",
                "model",
                new BigDecimal("0.2"),
                new BigDecimal("0.8"),
                maxSteps,
                maxToolCalls,
                maxTokens,
                timeoutSeconds,
                "ACTIVE"
        ), clock);
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-01T02:00:00Z"), ZoneOffset.UTC);
    }

    private static void assertFailure(Runnable invocation, AgentFailureType expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(AgentExecutionException.class, failure -> {
                    assertThat(failure.failureType()).isEqualTo(expected);
                    assertThat(failure).hasNoCause();
                });
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
