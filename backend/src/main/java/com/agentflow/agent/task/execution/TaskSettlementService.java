package com.agentflow.agent.task.execution;

import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.TaskStatus;
import com.agentflow.agent.task.recovery.TaskExecutionAdmission;
import com.agentflow.agent.task.service.AgentTaskLifecycleTransactionService;
import com.agentflow.agent.task.service.AgentTaskQueryService;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

/** Bounded, in-Runner persistence retries. This service has no execution or provider dependency. */
@Service
public class TaskSettlementService {
    private static final Logger log = LoggerFactory.getLogger(TaskSettlementService.class);
    private static final long[] BACKOFF_MS = {100, 500};
    public static final String FAILURE_CODE = "TASK_SETTLEMENT_PERSIST_FAILED";
    private final AgentTaskLifecycleTransactionService lifecycle;
    private final AgentTaskQueryService query;
    private final TaskExecutionAdmission admission;
    private final Sleeper sleeper;

    @Autowired
    public TaskSettlementService(AgentTaskLifecycleTransactionService lifecycle,
            AgentTaskQueryService query, TaskExecutionAdmission admission) {
        this(lifecycle, query, admission, Thread::sleep);
    }

    TaskSettlementService(AgentTaskLifecycleTransactionService lifecycle,
            AgentTaskQueryService query, TaskExecutionAdmission admission, Sleeper sleeper) {
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.query = Objects.requireNonNull(query);
        this.admission = Objects.requireNonNull(admission);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public boolean settle(long taskId, TaskExecutionOutcome observed, Instant observedAt) {
        Objects.requireNonNull(observed);
        Objects.requireNonNull(observedAt);
        return persist(taskId, () -> lifecycle.settleObserved(taskId, observed, observedAt), false);
    }

    /** Includes work already committed/queued when runtime admission is closed. */
    public boolean rejectDispatch(long taskId) {
        return persist(taskId, () -> lifecycle.markDispatchRejected(taskId), true);
    }

    private boolean persist(long taskId, Persistence write, boolean dispatchRejection) {
        // Cancellation interrupts waiting on business work, not a bounded attempt to save its facts.
        boolean interrupted = Thread.interrupted();
        try {
            for (int attempt = 1; attempt <= 3; attempt++) {
                String stage = "READ_BACK";
                try {
                    AgentTask current = query.findById(taskId);
                    if (terminal(current)) return true;
                    if (current == null) throw new IllegalStateException("Settlement task is missing");
                    if (dispatchRejection && !TaskStatus.QUEUED.name().equals(current.getStatus())) {
                        throw new IllegalStateException("Dispatch rejection found a claimed task");
                    }
                    stage = dispatchRejection ? "DISPATCH_REJECTION" : "TERMINAL_TRANSACTION";
                    if (write.save()) return true;
                    // A conditional refusal can only mean another transaction won. Never invent a failure outcome.
                    stage = "CONDITIONAL_READ_BACK";
                    if (terminal(query.findById(taskId))) return true;
                    throw new IllegalStateException("Conditional settlement left a nonterminal task");
                } catch (RuntimeException failure) {
                    // A transaction exception can mean COMMIT succeeded but its acknowledgement was lost.
                    // Read even after the final attempt or a non-transient exception before declaring failure.
                    if (!"READ_BACK".equals(stage)) {
                        try {
                            if (terminal(query.findById(taskId))) return true;
                        } catch (RuntimeException readFailure) {
                            if (!transientDatabaseFailure(readFailure)) {
                                degrade(taskId, attempt, "COMMIT_READ_BACK", readFailure);
                                return false;
                            }
                            stage = "COMMIT_READ_BACK";
                        }
                    }
                    if (attempt == 3 || !transientDatabaseFailure(failure)) {
                        degrade(taskId, attempt, stage, failure);
                        return false;
                    }
                    try {
                        sleeper.sleep(BACKOFF_MS[attempt - 1]);
                    } catch (InterruptedException stopped) {
                        interrupted = true;
                        degrade(taskId, attempt, "BACKOFF_INTERRUPTED", stopped);
                        return false;
                    }
                }
            }
            throw new IllegalStateException("Unreachable settlement loop");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static boolean terminal(AgentTask task) {
        return task != null && TaskStatus.valueOf(task.getStatus()).isTerminal();
    }

    /** PostgreSQL's explicit SQLSTATE wins over a generic translated wrapper. */
    static boolean transientDatabaseFailure(Throwable failure) {
        boolean translatedTransient = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                return state.startsWith("08") || state.startsWith("40") || state.equals("55P03")
                        || state.equals("57P01") || state.equals("57P02") || state.equals("57P03")
                        || state.equals("53300") || state.equals("57014");
            }
            translatedTransient |= cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof SQLTransientException || cause instanceof SQLRecoverableException;
        }
        return translatedTransient;
    }

    private void degrade(long taskId, int attempts, String stage, Throwable failure) {
        String diagnostic = FAILURE_CODE + ":task=" + taskId + ":attempts=" + attempts + ":stage=" + stage;
        admission.failSettlement(diagnostic);
        // Never log JDBC messages, SQL arguments, result text, prompts or credentials.
        log.error("{} failureType={}", diagnostic, failure.getClass().getSimpleName());
    }

    @FunctionalInterface interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }
    @FunctionalInterface private interface Persistence { boolean save(); }
}
