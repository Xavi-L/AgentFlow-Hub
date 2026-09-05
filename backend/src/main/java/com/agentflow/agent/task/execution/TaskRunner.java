package com.agentflow.agent.task.execution;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.TaskStatus;
import com.agentflow.agent.task.service.AgentTaskLifecycleTransactionService;
import com.agentflow.agent.task.service.AgentTaskQueryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Claims one task, invokes the snapshot Engine outside JDBC transactions, and owns
 * the conditional terminal transition and atomic answer publication.
 */
@Component
public class TaskRunner {
    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final AgentTaskLifecycleTransactionService lifecycleTransactions;
    private final AgentTaskQueryService queryService;
    private final TaskExecutionDelegate executionDelegate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TaskRunner(
            AgentTaskLifecycleTransactionService lifecycleTransactions,
            AgentTaskQueryService queryService,
            TaskExecutionDelegate executionDelegate,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.lifecycleTransactions = Objects.requireNonNull(
                lifecycleTransactions,
                "lifecycleTransactions must not be null"
        );
        this.queryService = Objects.requireNonNull(queryService, "queryService must not be null");
        this.executionDelegate = Objects.requireNonNull(
                executionDelegate,
                "executionDelegate must not be null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void run(long taskId) {
        AgentTask task = lifecycleTransactions.claim(taskId);
        if (task == null) {
            return;
        }

        Instant deadlineAt = null;
        TaskExecutionOutcome observedOutcome = null;
        try {
            AgentTaskExecutionSnapshot snapshot = parseAndValidateSnapshot(task);
            deadlineAt = task.getStartedAt().toInstant().plusSeconds(snapshot.agent().timeoutSeconds());
            if (finishCancellationIfRequested(taskId, TaskTokenUsage.UNKNOWN_ZERO, 0, 0)) {
                return;
            }
            if (deadlineReached(deadlineAt)) {
                TaskExecutionOutcome timeout = TaskExecutionOutcome.timedOut();
                if (!lifecycleTransactions.timeOut(taskId, timeout)) {
                    settleLostRace(taskId, timeout);
                }
                return;
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("TaskExecutionDelegate must run outside a database transaction");
            }

            TaskExecutionRequest request = new TaskExecutionRequest(
                    task.getId(),
                    task.getUserId(),
                    task.getAgentId(),
                    task.getUserInput(),
                    snapshot,
                    task.getReservedFinalTokens(),
                    deadlineAt,
                    () -> queryService.hasCancellationRequest(taskId)
            );
            TaskExecutionOutcome outcome = Objects.requireNonNull(
                    executionDelegate.execute(request),
                    "TaskExecutionDelegate returned null"
            );
            observedOutcome = outcome;
            settle(taskId, deadlineAt, outcome);
        } catch (Exception executionFailure) {
            log.warn("Task {} execution failed before a terminal transition", taskId, executionFailure);
            settleUnexpectedFailure(taskId, deadlineAt, observedOutcome);
        }
    }

    private void settle(long taskId, Instant deadlineAt, TaskExecutionOutcome outcome) {
        if (finishCancellationIfRequested(
                taskId,
                outcome.tokenUsage(),
                outcome.decisionTurnsUsed(),
                outcome.toolCallsUsed()
        )) {
            return;
        }
        if (deadlineReached(deadlineAt)) {
            if (!lifecycleTransactions.timeOut(taskId, asTimedOut(outcome))) {
                settleLostRace(taskId, outcome);
            }
            return;
        }

        boolean transitioned = switch (outcome.resultType()) {
            case COMPLETED -> lifecycleTransactions.complete(taskId, outcome);
            case FAILED -> lifecycleTransactions.fail(taskId, outcome);
            case TIMED_OUT -> lifecycleTransactions.timeOut(taskId, outcome);
            case CANCELLED -> lifecycleTransactions.finishCancellation(taskId, outcome);
        };
        if (!transitioned) {
            settleLostRace(taskId, outcome);
        }
    }

    private void settleUnexpectedFailure(long taskId, Instant deadlineAt, TaskExecutionOutcome observed) {
        TaskTokenUsage usage = observed == null ? TaskTokenUsage.UNKNOWN_ZERO : observed.tokenUsage();
        int decisions = observed == null ? 0 : observed.decisionTurnsUsed();
        int tools = observed == null ? 0 : observed.toolCallsUsed();
        try {
            if (finishCancellationIfRequested(taskId, usage, decisions, tools)) {
                return;
            }
            if (deadlineAt != null && deadlineReached(deadlineAt)) {
                TaskExecutionOutcome timeout = TaskExecutionOutcome.timedOut(decisions, tools, usage);
                if (!lifecycleTransactions.timeOut(taskId, timeout)) {
                    settleLostRace(taskId, timeout);
                }
                return;
            }
            if (!lifecycleTransactions.fail(
                    taskId,
                    TaskExecutionOutcome.failed(
                            com.agentflow.agent.task.model.TaskTerminationReason.SYSTEM_ERROR,
                            "TASK_INTERNAL_ERROR", "Task execution failed", decisions, tools, usage)
            )) {
                finishCancellationIfRequested(taskId, usage, decisions, tools);
            }
        } catch (RuntimeException terminalFailure) {
            log.error("Task {} could not persist its failure terminal state", taskId, terminalFailure);
        }
    }

    private void settleLostRace(long taskId, TaskExecutionOutcome outcome) {
        if (finishCancellationIfRequested(
                taskId,
                outcome.tokenUsage(),
                outcome.decisionTurnsUsed(),
                outcome.toolCallsUsed()
        )) {
            return;
        }
        AgentTask current = queryService.findById(taskId);
        if (current != null && TaskStatus.RUNNING.name().equals(current.getStatus())) {
            if (!lifecycleTransactions.fail(
                    taskId,
                    TaskExecutionOutcome.failed(
                            com.agentflow.agent.task.model.TaskTerminationReason.SYSTEM_ERROR,
                            "TASK_INTERNAL_ERROR",
                            "Task execution outcome could not be applied",
                            outcome.decisionTurnsUsed(), outcome.toolCallsUsed(), outcome.tokenUsage()
                    )
            )) {
                finishCancellationIfRequested(taskId, outcome.tokenUsage(),
                        outcome.decisionTurnsUsed(), outcome.toolCallsUsed());
            }
        }
    }

    private boolean finishCancellationIfRequested(
            long taskId,
            TaskTokenUsage usage,
            int decisionTurnsUsed,
            int toolCallsUsed
    ) {
        if (!queryService.hasCancellationRequest(taskId)) {
            return false;
        }
        lifecycleTransactions.finishCancellation(
                taskId,
                TaskExecutionOutcome.cancelled(decisionTurnsUsed, toolCallsUsed, usage)
        );
        return true;
    }

    private static TaskExecutionOutcome asTimedOut(TaskExecutionOutcome outcome) {
        return TaskExecutionOutcome.timedOut(
                outcome.decisionTurnsUsed(),
                outcome.toolCallsUsed(),
                outcome.tokenUsage()
        );
    }

    private AgentTaskExecutionSnapshot parseAndValidateSnapshot(AgentTask task) {
        try {
            AgentTaskExecutionSnapshot snapshot = objectMapper.readValue(
                    task.getExecutionSnapshot(),
                    AgentTaskExecutionSnapshot.class
            );
            if (snapshot.agent() == null
                    || !Long.toString(task.getAgentId()).equals(snapshot.agent().agentId())
                    || !"ACTIVE".equals(snapshot.agent().status())
                    || !Objects.equals(task.getMaxDecisionTurns(), snapshot.agent().maxDecisionTurns())
                    || !Objects.equals(task.getMaxToolCalls(), snapshot.agent().maxToolCalls())
                    || !Objects.equals(task.getMaxTotalTokens(), snapshot.agent().maxTotalTokens())
                    || task.getReservedFinalTokens() == null
                    || task.getReservedFinalTokens() < 1
                    || task.getReservedFinalTokens() >= task.getMaxTotalTokens()
                    || snapshot.agent().timeoutSeconds() < 1) {
                throw new IllegalStateException("Persisted execution snapshot does not match task budgets");
            }
            return snapshot;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Persisted execution snapshot is invalid", ex);
        }
    }

    private boolean deadlineReached(Instant deadlineAt) {
        try {
            return !clock.instant().isBefore(deadlineAt);
        } catch (DateTimeException ex) {
            return true;
        }
    }
}
