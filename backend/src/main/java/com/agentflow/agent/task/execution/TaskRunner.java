package com.agentflow.agent.task.execution;

import com.agentflow.agent.task.recovery.TaskExecutionAdmission;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.task.model.AgentTask;
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

    private final TaskExecutionAdmission admission;
    private final AgentTaskLifecycleTransactionService lifecycleTransactions;
    private final AgentTaskQueryService queryService;
    private final TaskExecutionDelegate executionDelegate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TaskSettlementService settlement;

    public TaskRunner(
            AgentTaskLifecycleTransactionService lifecycleTransactions,
            AgentTaskQueryService queryService,
            TaskExecutionDelegate executionDelegate,
            ObjectMapper objectMapper,
            Clock clock,
            TaskExecutionAdmission admission,
            TaskSettlementService settlement
    ) {
        this.settlement = Objects.requireNonNull(settlement, "settlement must not be null");
        this.admission = Objects.requireNonNull(admission, "admission must not be null");
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
        admission.requireReady();
        AgentTask task = lifecycleTransactions.claim(taskId);
        if (task == null) {
            return;
        }

        Instant deadlineAt = null;
        TaskExecutionOutcome outcome;
        try {
            AgentTaskExecutionSnapshot snapshot = parseAndValidateSnapshot(task);
            deadlineAt = task.getStartedAt().toInstant().plusSeconds(snapshot.agent().timeoutSeconds());
            if (queryService.hasCancellationRequest(taskId)) {
                outcome = TaskExecutionOutcome.cancelled();
            } else if (deadlineReached(deadlineAt)) {
                outcome = TaskExecutionOutcome.timedOut();
            } else {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    throw new IllegalStateException("TaskExecutionDelegate must run outside a database transaction");
                }
                TaskExecutionRequest request = new TaskExecutionRequest(
                        task.getId(), task.getUserId(), task.getAgentId(), task.getUserInput(), snapshot,
                        task.getReservedFinalTokens(), deadlineAt, () -> queryService.hasCancellationRequest(taskId));
                outcome = Objects.requireNonNull(executionDelegate.execute(request),
                        "TaskExecutionDelegate returned null");
            }
        } catch (Exception executionFailure) {
            log.warn("Task {} execution failed before outcome observation; failureType={}",
                    taskId, executionFailure.getClass().getSimpleName());
            outcome = TaskExecutionOutcome.failed("TASK_INTERNAL_ERROR", "Task execution failed");
        }
        Instant observedAt = clock.instant();
        // Freeze the initial deadline arbitration. Persistence backoff must not rewrite execution history.
        if (deadlineAt != null && !observedAt.isBefore(deadlineAt)
                && outcome.resultType() != TaskExecutionResultType.CANCELLED) {
            outcome = TaskExecutionOutcome.timedOut(outcome.decisionTurnsUsed(),
                    outcome.toolCallsUsed(), outcome.tokenUsage());
        }
        settlement.settle(taskId, outcome, observedAt);
    }

    /** An accepted executor job still needs durable refusal if admission closes before its claim. */
    public void runDispatched(long taskId) {
        try {
            run(taskId);
        } catch (RuntimeException claimFailure) {
            log.warn("Task {} could not enter Runner; failureType={}", taskId,
                    claimFailure.getClass().getSimpleName());
            settlement.rejectDispatch(taskId);
        }
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
