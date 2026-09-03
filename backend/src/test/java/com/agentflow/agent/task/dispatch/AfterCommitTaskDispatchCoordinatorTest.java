package com.agentflow.agent.task.dispatch;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.agent.task.service.AgentTaskLifecycleTransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class AfterCommitTaskDispatchCoordinatorTest {
    @Mock
    private TaskDispatcher dispatcher;
    @Mock
    private AgentTaskLifecycleTransactionService lifecycleTransactions;

    private AfterCommitTaskDispatchCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new AfterCommitTaskDispatchCoordinator(dispatcher, lifecycleTransactions);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void shouldNotDispatchWhenTheCreationTransactionRollsBack() {
        coordinator.dispatchAfterCommit(41L);

        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
        );

        verify(dispatcher, never()).dispatch(41L);
        verify(lifecycleTransactions, never()).markDispatchRejected(41L);
    }

    @Test
    void shouldDispatchOnlyFromAfterCommit() {
        coordinator.dispatchAfterCommit(42L);
        verify(dispatcher, never()).dispatch(42L);

        TransactionSynchronizationManager.getSynchronizations().forEach(
                TransactionSynchronization::afterCommit
        );

        verify(dispatcher).dispatch(42L);
    }

    @Test
    void shouldPersistAConditionalFailureWhenDispatchIsRejected() {
        org.mockito.Mockito.doThrow(new TaskDispatchRejectedException("full", new RuntimeException()))
                .when(dispatcher).dispatch(43L);
        when(lifecycleTransactions.markDispatchRejected(43L)).thenReturn(true);
        coordinator.dispatchAfterCommit(43L);

        TransactionSynchronizationManager.getSynchronizations().forEach(
                TransactionSynchronization::afterCommit
        );

        verify(lifecycleTransactions).markDispatchRejected(43L);
    }
}
