package com.agentflow.agent.configversion;

import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.stereotype.Service;

/** Bounded database-only retries happen outside the failed transaction, never around provider calls. */
@Service
public class AgentConfigVersionService {
    private final AgentConfigVersionTransactions transactions;
    public AgentConfigVersionService(AgentConfigVersionTransactions transactions) { this.transactions = transactions; }

    public Publication publish(long owner, long agentId) {
        for (int attempt = 0; ; attempt++) {
            try {
                var published = transactions.capture(owner, agentId);
                return new Publication(transactions.response(published.version()), published.created());
            } catch (CannotSerializeTransactionException e) {
                if (attempt >= 2) throw e;
            }
        }
    }
    public record Publication(AgentConfigVersionResponse version, boolean created) { }
}
