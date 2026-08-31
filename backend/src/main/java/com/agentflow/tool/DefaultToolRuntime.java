package com.agentflow.tool;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Synchronous runtime established in V27: resolve a visible database definition, validate
 * arguments, write a durable lifecycle log, route through the explicit built-in allowlist and
 * standardize output. Timeout and retry values remain persisted configuration only.
 */
@Service
public class DefaultToolRuntime implements ToolRuntime {
    private static final Logger log = LoggerFactory.getLogger(DefaultToolRuntime.class);

    private final ToolDefinitionService toolDefinitionService;
    private final ToolArgumentValidator toolArgumentValidator;
    private final BuiltinToolExecutor builtinToolExecutor;
    private final ToolCallLogService toolCallLogService;
    private final Clock clock;

    public DefaultToolRuntime(
            ToolDefinitionService toolDefinitionService,
            ToolArgumentValidator toolArgumentValidator,
            BuiltinToolExecutor builtinToolExecutor,
            ToolCallLogService toolCallLogService,
            Clock clock
    ) {
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
                    ex.getMessage(),
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

    private static int elapsedMillis(long startedNanos) {
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        return (int) Math.min(Integer.MAX_VALUE, elapsedMillis);
    }
}
