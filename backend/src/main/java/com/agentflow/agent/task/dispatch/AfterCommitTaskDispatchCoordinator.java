package com.agentflow.agent.task.dispatch;

import com.agentflow.agent.task.execution.TaskSettlementService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Registers dispatch only after the task and TASK_CREATED event have committed. */
@Component
public class AfterCommitTaskDispatchCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AfterCommitTaskDispatchCoordinator.class);

    private final TaskDispatcher taskDispatcher;
    private final TaskSettlementService settlement;

    public AfterCommitTaskDispatchCoordinator(
            TaskDispatcher taskDispatcher,
            TaskSettlementService settlement
    ) {
        this.taskDispatcher = Objects.requireNonNull(taskDispatcher, "taskDispatcher must not be null");
        this.settlement = Objects.requireNonNull(
                settlement,
                "settlement must not be null"
        );
    }

    public void dispatchAfterCommit(long taskId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Task dispatch must be registered inside the creation transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    taskDispatcher.dispatch(taskId);
                } catch (RuntimeException dispatchFailure) {
                    compensateRejectedDispatch(taskId, dispatchFailure);
                }
            }
        });
    }

    private void compensateRejectedDispatch(long taskId, RuntimeException dispatchFailure) {
        log.warn("Task {} dispatch was rejected; persisting terminal compensation", taskId);
        settlement.rejectDispatch(taskId);
    }
}
