package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DefaultToolRuntimeTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolDefinitionService definitionService;
    private BuiltinToolExecutor executor;
    private ToolCallLogService logService;
    private DefaultToolRuntime runtime;
    private ToolDefinition definition;

    @BeforeEach
    void setUp() throws Exception {
        definitionService = Mockito.mock(ToolDefinitionService.class);
        executor = Mockito.mock(BuiltinToolExecutor.class);
        logService = Mockito.mock(ToolCallLogService.class);
        runtime = new DefaultToolRuntime(
                definitionService,
                new ToolArgumentValidator(),
                executor,
                logService,
                Clock.fixed(Instant.parse("2026-05-01T04:00:00Z"), ZoneOffset.UTC)
        );
        definition = definition();
    }

    @Test
    void shouldExecuteAndCompleteOneRunningLog() throws Exception {
        JsonNode arguments = objectMapper.readTree("{\"orderNo\":\"order_1024\"}");
        JsonNode data = objectMapper.readTree("{\"orderNo\":\"order_1024\"}");
        when(definitionService.findActiveById(definition.id())).thenReturn(Optional.of(definition));
        when(logService.recordRunning(any(), any(), any())).thenReturn(501L);
        when(executor.execute(definition, arguments)).thenReturn(
                new BuiltinToolHandler.HandlerResult("safe summary", data)
        );

        ToolExecutionResult result = runtime.execute(ToolExecutionCommand.standalone(definition.id(), arguments));

        assertThat(result.success()).isTrue();
        assertThat(result.toolCode()).isEqualTo("order_query");
        assertThat(result.summary()).isEqualTo("safe summary");
        assertThat(result.data()).isEqualTo(data);
        assertThat(result.errorCode()).isNull();
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        verify(logService).recordRunning(any(), any(), any());
        verify(logService).recordSuccess(501L, result);
        verify(logService, never()).recordFailed(any(), any());
    }

    @Test
    void shouldRejectResolvedInvalidArgumentsWithoutCreatingARunningLog() throws Exception {
        JsonNode arguments = objectMapper.readTree("{\"orderNo\":\"   \",\"extra\":true}");
        ToolExecutionCommand command = ToolExecutionCommand.standalone(definition.id(), arguments);
        when(definitionService.findActiveById(definition.id())).thenReturn(Optional.of(definition));

        assertThatThrownBy(() -> runtime.execute(command))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.TOOL_ARGUMENT_INVALID));

        ArgumentCaptor<ToolExecutionResult> resultCaptor = ArgumentCaptor.forClass(ToolExecutionResult.class);
        verify(logService).recordRejected(any(), any(), resultCaptor.capture(), any());
        assertThat(resultCaptor.getValue().errorCode()).isEqualTo("TOOL_ARGUMENT_INVALID");
        assertThat(resultCaptor.getValue().errorMessage()).isEqualTo("Tool arguments are invalid");
        verify(logService, never()).recordRunning(any(), any(), any());
        verifyNoInteractions(executor);
    }

    @Test
    void shouldRejectPaymentLimitOnlyBeforeCreatingARunningLog() throws Exception {
        ToolDefinition paymentDefinition = paymentDefinition();
        JsonNode arguments = objectMapper.readTree("{\"limit\":10}");
        ToolExecutionCommand command = ToolExecutionCommand.standalone(paymentDefinition.id(), arguments);
        when(definitionService.findActiveById(paymentDefinition.id()))
                .thenReturn(Optional.of(paymentDefinition));

        assertThatThrownBy(() -> runtime.execute(command))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.TOOL_ARGUMENT_INVALID));

        ArgumentCaptor<ToolExecutionResult> resultCaptor = ArgumentCaptor.forClass(ToolExecutionResult.class);
        verify(logService).recordRejected(any(), any(), resultCaptor.capture(), any());
        assertThat(resultCaptor.getValue().toolCode()).isEqualTo("payment_log_query");
        assertThat(resultCaptor.getValue().errorCode()).isEqualTo("TOOL_ARGUMENT_INVALID");
        verify(logService, never()).recordRunning(any(), any(), any());
        verifyNoInteractions(executor);
    }

    @Test
    void shouldReturnToolNotFoundWithoutWritingALog() {
        when(definitionService.findActiveById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runtime.execute(ToolExecutionCommand.standalone(
                999L,
                objectMapper.createObjectNode()
        ))).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode())
                .isEqualTo(ErrorCode.TOOL_NOT_FOUND));

        verifyNoInteractions(logService, executor);
    }

    @Test
    void shouldPersistCommonNotFoundAsFailedAndPreserveTheHttpFacingError() throws Exception {
        JsonNode arguments = objectMapper.readTree("{\"orderNo\":\"order_missing\"}");
        when(definitionService.findActiveById(definition.id())).thenReturn(Optional.of(definition));
        when(logService.recordRunning(any(), any(), any())).thenReturn(502L);
        when(executor.execute(definition, arguments))
                .thenThrow(new BusinessException(ErrorCode.COMMON_NOT_FOUND));

        assertThatThrownBy(() -> runtime.execute(ToolExecutionCommand.standalone(definition.id(), arguments)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_NOT_FOUND));

        ArgumentCaptor<ToolExecutionResult> resultCaptor = ArgumentCaptor.forClass(ToolExecutionResult.class);
        verify(logService).recordFailed(org.mockito.ArgumentMatchers.eq(502L), resultCaptor.capture());
        assertThat(resultCaptor.getValue().errorCode()).isEqualTo("COMMON_NOT_FOUND");
        assertThat(resultCaptor.getValue().errorMessage()).isEqualTo("Resource not found");
    }

    @Test
    void shouldHideUnknownExecutorDetailsBehindTheStableToolFailure() throws Exception {
        JsonNode arguments = objectMapper.readTree("{\"orderNo\":\"order_1024\"}");
        when(definitionService.findActiveById(definition.id())).thenReturn(Optional.of(definition));
        when(logService.recordRunning(any(), any(), any())).thenReturn(503L);
        when(executor.execute(definition, arguments))
                .thenThrow(new IllegalStateException("database password must not leak"));

        assertThatThrownBy(() -> runtime.execute(ToolExecutionCommand.standalone(definition.id(), arguments)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOOL_EXECUTION_FAILED);
                    assertThat(ex.getMessage()).doesNotContain("password");
                });

        ArgumentCaptor<ToolExecutionResult> resultCaptor = ArgumentCaptor.forClass(ToolExecutionResult.class);
        verify(logService).recordFailed(org.mockito.ArgumentMatchers.eq(503L), resultCaptor.capture());
        assertThat(resultCaptor.getValue().errorCode()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(resultCaptor.getValue().errorMessage()).isEqualTo("Tool execution failed");
        assertThat(resultCaptor.getValue().errorMessage()).doesNotContain("password");
    }

    @Test
    void shouldNotMisclassifySuccessLogFinalizationFailureAsAHandlerFailure() throws Exception {
        JsonNode arguments = objectMapper.readTree("{\"orderNo\":\"order_1024\"}");
        JsonNode data = objectMapper.readTree("{\"orderNo\":\"order_1024\"}");
        when(definitionService.findActiveById(definition.id())).thenReturn(Optional.of(definition));
        when(logService.recordRunning(any(), any(), any())).thenReturn(504L);
        when(executor.execute(definition, arguments)).thenReturn(
                new BuiltinToolHandler.HandlerResult("safe summary", data)
        );
        doThrow(new IllegalStateException("terminal log update failed"))
                .when(logService).recordSuccess(org.mockito.ArgumentMatchers.eq(504L), any());

        assertThatThrownBy(() -> runtime.execute(ToolExecutionCommand.standalone(definition.id(), arguments)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("terminal log update failed");

        verify(logService, never()).recordFailed(any(), any());
    }

    private ToolDefinition definition() throws Exception {
        return new ToolDefinition(
                270000000000000001L,
                "order_query",
                "Order Query",
                "description",
                "BUILTIN",
                objectMapper.readTree("""
                        {
                          "type": "object",
                          "properties": {
                            "orderNo": {"type": "string", "minLength": 1, "maxLength": 64}
                          },
                          "required": ["orderNo"],
                          "additionalProperties": false
                        }
                        """),
                objectMapper.createObjectNode(),
                objectMapper.readTree("{\"handler\":\"orderQueryTool\",\"readonly\":true}"),
                3000,
                0,
                false,
                "MEDIUM",
                "ACTIVE"
        );
    }

    private ToolDefinition paymentDefinition() throws Exception {
        return new ToolDefinition(
                280000000000000001L,
                "payment_log_query",
                "Payment Log Query",
                "description",
                "BUILTIN",
                objectMapper.readTree("""
                        {
                          "type": "object",
                          "properties": {
                            "orderNo": {"type": "string", "minLength": 1, "maxLength": 64},
                            "errorCode": {"type": "string", "minLength": 1, "maxLength": 64},
                            "limit": {"type": "integer", "minimum": 1, "maximum": 20, "default": 10}
                          },
                          "anyOf": [
                            {"required": ["orderNo"]},
                            {"required": ["errorCode"]}
                          ],
                          "additionalProperties": false
                        }
                        """),
                objectMapper.createObjectNode(),
                objectMapper.readTree("{\"handler\":\"paymentLogQueryTool\",\"readonly\":true}"),
                5000,
                0,
                false,
                "MEDIUM",
                "ACTIVE"
        );
    }
}
