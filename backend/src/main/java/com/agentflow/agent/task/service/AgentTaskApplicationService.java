package com.agentflow.agent.task.service;

import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Non-transactional idempotency orchestrator around the isolated creation transaction. */
@Service
public class AgentTaskApplicationService {
    private static final int MAX_CLIENT_REQUEST_ID_LENGTH = 128;

    private final TaskRequestFingerprint fingerprintFactory;
    private final AgentTaskQueryService queryService;
    private final AgentTaskCreationTransactionService creationTransactionService;

    public AgentTaskApplicationService(
            TaskRequestFingerprint fingerprintFactory,
            AgentTaskQueryService queryService,
            AgentTaskCreationTransactionService creationTransactionService
    ) {
        this.fingerprintFactory = Objects.requireNonNull(
                fingerprintFactory,
                "fingerprintFactory must not be null"
        );
        this.queryService = Objects.requireNonNull(queryService, "queryService must not be null");
        this.creationTransactionService = Objects.requireNonNull(
                creationTransactionService,
                "creationTransactionService must not be null"
        );
    }

    public AgentTask createTask(CreateAgentTaskCommand command) {
        validate(command);
        String fingerprint = fingerprintFactory.calculate(command.agentId(), command.userInput()).sha256();

        AgentTask existing = queryService.findByUserAndClientRequestId(
                command.userId(),
                command.clientRequestId()
        );
        if (existing != null) {
            return sameRequestOrConflict(existing, fingerprint);
        }

        try {
            return creationTransactionService.createNew(command, fingerprint);
        } catch (DataIntegrityViolationException ex) {
            // The failed PostgreSQL transaction has already unwound through its proxy here.
            // Recovery must use AgentTaskQueryService's independent REQUIRES_NEW transaction.
            AgentTask concurrentWinner = queryService.findByUserAndClientRequestId(
                    command.userId(),
                    command.clientRequestId()
            );
            if (concurrentWinner == null) {
                throw ex;
            }
            return sameRequestOrConflict(concurrentWinner, fingerprint);
        }
    }

    private static AgentTask sameRequestOrConflict(AgentTask existing, String fingerprint) {
        if (fingerprint.equals(existing.getRequestFingerprint())) {
            return existing;
        }
        throw new BusinessException(
                ErrorCode.TASK_IDEMPOTENCY_CONFLICT,
                "clientRequestId was already used for a different Agent task request"
        );
    }

    private static void validate(CreateAgentTaskCommand command) {
        if (command == null) {
            throw invalid("command must not be null");
        }
        if (command.userId() <= 0) {
            throw invalid("userId must be positive");
        }
        if (command.agentId() <= 0) {
            throw invalid("agentId must be positive");
        }
        if (command.clientRequestId() == null
                || command.clientRequestId().isBlank()
                || command.clientRequestId().length() > MAX_CLIENT_REQUEST_ID_LENGTH) {
            throw invalid("clientRequestId must contain 1 to 128 characters");
        }
        if (command.userInput() == null || command.userInput().isBlank()) {
            throw invalid("userInput must not be blank");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.COMMON_PARAM_INVALID, message);
    }
}
