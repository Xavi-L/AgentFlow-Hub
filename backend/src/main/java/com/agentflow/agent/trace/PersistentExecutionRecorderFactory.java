package com.agentflow.agent.trace;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PersistentExecutionRecorderFactory implements ExecutionRecorderFactory {
    private final ExecutionRecorderTransactionService transactions;

    public PersistentExecutionRecorderFactory(ExecutionRecorderTransactionService transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    @Override
    public ExecutionRecorder open(long taskId) {
        return new PersistentExecutionRecorder(taskId, transactions);
    }
}
