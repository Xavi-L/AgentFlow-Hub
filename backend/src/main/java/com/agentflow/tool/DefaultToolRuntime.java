package com.agentflow.tool;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.ToolSnapshot;
import com.agentflow.agent.engine.TaskExternalCallDeadline;
import com.agentflow.agent.engine.TaskExecutionProperties;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Synchronous runtime established in V27: resolve a visible database definition, validate
 * arguments, write a durable lifecycle log, route through the explicit built-in allowlist and
 * standardize output. Persistent task calls enforce snapshot identity and a bounded wait;
 * standalone administrative calls retain their original synchronous behavior. No automatic retry.
 */
@Service
public class DefaultToolRuntime implements ToolRuntime {
    private static final Logger log = LoggerFactory.getLogger(DefaultToolRuntime.class);

    private final ToolDefinitionService toolDefinitionService;
    private final ToolArgumentValidator toolArgumentValidator;
    private final BuiltinToolExecutor builtinToolExecutor;
    private final ToolCallLogService toolCallLogService;
    private final Clock clock;
    private final TaskExternalCallDeadline externalCalls;

    public DefaultToolRuntime(
            ToolDefinitionService toolDefinitionService,
            ToolArgumentValidator toolArgumentValidator,
            BuiltinToolExecutor builtinToolExecutor,
            ToolCallLogService toolCallLogService,
            Clock clock
    ) {
        this(toolDefinitionService, toolArgumentValidator, builtinToolExecutor, toolCallLogService, clock,
                new TaskExternalCallDeadline(new TaskExecutionProperties()));
    }

    @Autowired
    public DefaultToolRuntime(ToolDefinitionService toolDefinitionService,
            ToolArgumentValidator toolArgumentValidator, BuiltinToolExecutor builtinToolExecutor,
            ToolCallLogService toolCallLogService, Clock clock, TaskExternalCallDeadline externalCalls) {
        this.externalCalls = Objects.requireNonNull(externalCalls);
        this.toolDefinitionService = Objects.requireNonNull(
                toolDefinitionService,
                "toolDefinitionService must not be null"
        );
        this.toolArgumentValidator = Objects.requireNonNull(
                toolArgumentValidator,
                "toolArgumentValidator must not be null"
        );
        this.builtinToolExecutor = Objects.requireNonNull(
                builtinToolExecutor,
                "builtinToolExecutor must not be null"
        );
        this.toolCallLogService = Objects.requireNonNull(
                toolCallLogService,
                "toolCallLogService must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.taskId() != null) {
            if (command.taskScope() == null) {
                throw new IllegalArgumentException("Persistent tool execution requires a complete task snapshot");
            }
            return executeTask(command);
        }
        return executeStandalone(command);
    }

    @Override
    public void validateTaskSnapshot(ToolExecutionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.taskId() == null || command.taskScope() == null) {
            throw new IllegalArgumentException("Persistent tool execution requires a complete task snapshot");
        }
        checkTaskBoundary(command);
        List<ToolSnapshot> matches = command.taskScope().snapshot().tools().stream()
                .filter(tool -> command.toolId().toString().equals(tool.toolId()))
                .toList();
        if (matches.size() != 1) {
            throw taskFailure("TOOL_SNAPSHOT_MISMATCH",
                    "Tool is not uniquely present in the execution snapshot");
        }
        ToolDefinition current = toolDefinitionService.findActiveById(command.toolId()).orElse(null);
        validateTaskTool(current, matches.getFirst());
        try {
            toolArgumentValidator.validate(matches.getFirst().inputSchema(), command.arguments());
        } catch (ToolArgumentValidationException ex) {
            throw new BusinessException(ErrorCode.TOOL_ARGUMENT_INVALID);
        }
        checkTaskBoundary(command);
    }

    private ToolExecutionResult executeStandalone(ToolExecutionCommand command) {
        long startedNanos = System.nanoTime();
        OffsetDateTime startedAt = OffsetDateTime.now(clock);

        ToolDefinition tool = toolDefinitionService.findActiveById(command.toolId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND));

        try {
            toolArgumentValidator.validate(tool.inputSchema(), command.arguments());
        } catch (ToolArgumentValidationException ex) {
            ToolExecutionResult rejected = ToolExecutionResult.failure(
                    tool.toolCode(),
                    ErrorCode.TOOL_ARGUMENT_INVALID.getCode(),
                    ErrorCode.TOOL_ARGUMENT_INVALID.getMessage(),
                    elapsedMillis(startedNanos)
            );
            toolCallLogService.recordRejected(tool, command, rejected, startedAt);
            throw new BusinessException(ErrorCode.TOOL_ARGUMENT_INVALID);
        }

        Long callId = toolCallLogService.recordRunning(tool, command, startedAt);
        BuiltinToolHandler.HandlerResult handlerResult;
        try {
            handlerResult = builtinToolExecutor.execute(
                    tool,
                    command.arguments()
            );
        } catch (BusinessException ex) {
            ToolExecutionResult failed = ToolExecutionResult.failure(
                    tool.toolCode(),
                    ex.getErrorCode().getCode(),
                    ex.getErrorCode().getMessage(),
                    elapsedMillis(startedNanos)
            );
            toolCallLogService.recordFailed(callId, failed);
            throw ex;
        } catch (Exception ex) {
            log.error("Built-in tool execution failed: toolCode={}, callId={}", tool.toolCode(), callId, ex);
            ToolExecutionResult failed = ToolExecutionResult.failure(
                    tool.toolCode(),
                    ErrorCode.TOOL_EXECUTION_FAILED.getCode(),
                    ErrorCode.TOOL_EXECUTION_FAILED.getMessage(),
                    elapsedMillis(startedNanos)
            );
            toolCallLogService.recordFailed(callId, failed);
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED);
        }

        ToolExecutionResult success = ToolExecutionResult.success(
                tool.toolCode(),
                handlerResult.summary(),
                handlerResult.data(),
                elapsedMillis(startedNanos)
        );
        // Audit finalization is outside the handler catch boundary. If this write fails, the
        // handler did not fail and must not be misreported or followed by a contradictory FAILED
        // update attempt.
        toolCallLogService.recordSuccess(callId, success);
        return success;
    }

    private ToolExecutionResult executeTask(ToolExecutionCommand command) {
        long startedNanos = System.nanoTime();
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        List<ToolSnapshot> matches = command.taskScope().snapshot().tools().stream()
                .filter(tool -> command.toolId().toString().equals(tool.toolId()))
                .toList();
        ToolDefinition current = toolDefinitionService.findActiveById(command.toolId()).orElse(null);
        if (matches.size() != 1) {
            ToolTaskExecutionException failure = taskFailure("TOOL_SNAPSHOT_MISMATCH",
                    "Tool is not uniquely present in the execution snapshot");
            if (current != null) {
                rejectTask(current, command, failure, startedAt, startedNanos);
            }
            throw failure;
        }
        ToolSnapshot frozen = matches.getFirst();
        ToolDefinition executionTool = frozenDefinition(command.toolId(), frozen, current);
        try {
            checkTaskBoundary(command);
            validateTaskTool(current, frozen);
            toolArgumentValidator.validate(frozen.inputSchema(), command.arguments());
        } catch (ToolArgumentValidationException ex) {
            BusinessException failure = new BusinessException(ErrorCode.TOOL_ARGUMENT_INVALID);
            rejectTask(executionTool, command, failure, startedAt, startedNanos);
            throw failure;
        } catch (RuntimeException ex) {
            rejectTask(executionTool, command, ex, startedAt, startedNanos);
            throw ex;
        }

        Long callId = toolCallLogService.recordRunning(executionTool, command, startedAt);
        BuiltinToolHandler.HandlerResult handlerResult;
        try {
            checkTaskBoundary(command);
            handlerResult = executeWithinTaskDeadline(command, executionTool);
            checkTaskBoundary(command);
        } catch (RuntimeException ex) {
            toolCallLogService.recordFailed(callId, failureResult(executionTool, ex, startedNanos));
            // Cancellation/deadline signals thrown by the shared boundary must retain identity.
            throw ex;
        }
        ToolExecutionResult success = ToolExecutionResult.success(executionTool.toolCode(),
                handlerResult.summary(), handlerResult.data(), elapsedMillis(startedNanos));
        toolCallLogService.recordSuccess(callId, success);
        return success;
    }

    private BuiltinToolHandler.HandlerResult executeWithinTaskDeadline(
            ToolExecutionCommand command, ToolDefinition tool
    ) {
        var toolDeadline = clock.instant().plusMillis(tool.timeoutMs());
        var deadline = toolDeadline.isBefore(command.taskScope().deadlineAt())
                ? toolDeadline : command.taskScope().deadlineAt();
        return externalCalls.call(() -> {
            Thread worker = Thread.currentThread();
            String previousName = worker.getName();
            worker.setName("agent-tool-" + command.taskId());
            try {
                checkTaskBoundary(command);
                try {
                    return builtinToolExecutor.execute(tool, command.arguments());
                } catch (BusinessException ex) {
                    throw ex;
                } catch (Exception ex) {
                    // Only safe identifiers are diagnostic; raw handler/provider responses are not logged.
                    log.error("Task tool execution failed: taskId={}, toolCode={}",
                            command.taskId(), tool.toolCode());
                    throw taskFailure("TOOL_EXECUTION_FAILED", "Tool execution failed");
                }
            } finally {
                worker.setName(previousName);
            }
        }, deadline, clock, () -> checkTaskBoundary(command), ignored -> { },
                () -> taskFailure("TOOL_TIMEOUT", "Tool deadline exceeded"),
                () -> taskFailure("AGENT_EXECUTION_INTERRUPTED", "Task tool execution was interrupted"));
    }

    private void checkTaskBoundary(ToolExecutionCommand command) {
        command.taskScope().checkBoundary().run();
        if (!clock.instant().isBefore(command.taskScope().deadlineAt())) {
            throw taskFailure("TOOL_TIMEOUT", "Task deadline exceeded");
        }
    }

    private static void validateTaskTool(ToolDefinition current, ToolSnapshot frozen) {
        if (current == null || !"ACTIVE".equals(current.status())) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND);
        }
        String expectedHandler = TASK_HANDLERS.get(frozen.toolCode());
        JsonNode config = current.config();
        if (!"BUILTIN".equals(current.type())
                || !current.toolCode().equals(frozen.toolCode())
                || expectedHandler == null
                || config == null || !config.isObject()
                || !expectedHandler.equals(config.path("handler").asText())
                || !config.path("readonly").asBoolean(false)
                || current.requiresConfirmation()
                || current.timeoutMs() <= 0 || frozen.timeoutMs() <= 0
                || !"builtin-v1".equals(frozen.implementationVersion())
                || (config.has("implementationVersion")
                    && !"builtin-v1".equals(config.path("implementationVersion").asText()))
                || current.inputSchema() == null || !current.inputSchema().isObject()
                || !ToolSchemaFingerprint.sha256(frozen.inputSchema()).equals(frozen.inputSchemaHash())
                || !ToolSchemaFingerprint.sha256(current.inputSchema()).equals(frozen.inputSchemaHash())) {
            throw taskFailure("TOOL_SNAPSHOT_MISMATCH", "Tool no longer matches the execution snapshot");
        }
    }

    private static final Map<String, String> TASK_HANDLERS = Map.of(
            "order_query", "orderQueryTool",
            "payment_log_query", "paymentLogQueryTool"
    );

    private static ToolDefinition frozenDefinition(Long toolId, ToolSnapshot frozen, ToolDefinition current) {
        var config = JsonNodeFactory.instance.objectNode();
        String handler = TASK_HANDLERS.get(frozen.toolCode());
        if (handler != null) {
            config.put("handler", handler);
        }
        config.put("readonly", true);
        config.put("implementationVersion", frozen.implementationVersion());
        int timeoutMs = current == null ? frozen.timeoutMs()
                : Math.min(frozen.timeoutMs(), current.timeoutMs());
        return new ToolDefinition(toolId, frozen.toolCode(), frozen.name(), frozen.description(),
                "BUILTIN", frozen.inputSchema(), JsonNodeFactory.instance.objectNode(), config,
                timeoutMs, 0, false, "MEDIUM", "ACTIVE");
    }

    private void rejectTask(ToolDefinition tool, ToolExecutionCommand command, RuntimeException failure,
                            OffsetDateTime startedAt, long startedNanos) {
        toolCallLogService.recordRejected(tool, command, failureResult(tool, failure, startedNanos), startedAt);
    }

    private static ToolExecutionResult failureResult(ToolDefinition tool, RuntimeException failure,
                                                     long startedNanos) {
        String code;
        String message;
        if (failure instanceof BusinessException business) {
            code = business.getErrorCode().getCode();
            message = business.getErrorCode().getMessage();
        } else if (failure instanceof ToolTaskExecutionException task) {
            code = task.errorCode();
            message = task.getMessage();
        } else {
            code = "TASK_BOUNDARY_ABORTED";
            message = "Task boundary interrupted tool execution";
        }
        return ToolExecutionResult.failure(tool.toolCode(), code, message, elapsedMillis(startedNanos));
    }

    private static ToolTaskExecutionException taskFailure(String code, String message) {
        return new ToolTaskExecutionException(code, message);
    }

    private static int elapsedMillis(long startedNanos) {
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        return (int) Math.min(Integer.MAX_VALUE, elapsedMillis);
    }
}
