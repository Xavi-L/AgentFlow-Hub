package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.tool.model.ToolCallLogRecord;
import com.agentflow.tool.repository.ToolCallLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ToolCallLogServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolCallLogMapper mapper;
    private ToolCallLogService service;
    private ToolDefinition definition;

    @BeforeEach
    void setUp() throws Exception {
        mapper = Mockito.mock(ToolCallLogMapper.class);
        service = new ToolCallLogService(
                mapper,
                objectMapper,
                Clock.fixed(Instant.parse("2026-05-01T04:00:01Z"), ZoneOffset.UTC)
        );
        definition = definition();
    }

    @Test
    void shouldInsertAStandaloneRunningSnapshotWithNullTaskAndStep() throws Exception {
        when(mapper.insertCall(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-05-01T04:00:00Z");
        ToolExecutionCommand command = ToolExecutionCommand.standalone(
                definition.id(),
                objectMapper.readTree("{\"orderNo\":\"order_1024\"}")
        );

        Long callId = service.recordRunning(definition, command, startedAt);

        ArgumentCaptor<ToolCallLogRecord> captor = ArgumentCaptor.forClass(ToolCallLogRecord.class);
        verify(mapper).insertCall(captor.capture());
        ToolCallLogRecord record = captor.getValue();
        assertThat(callId).isEqualTo(record.getId()).isPositive();
        assertThat(record.getTaskId()).isNull();
        assertThat(record.getStepId()).isNull();
        assertThat(record.getToolId()).isEqualTo(270000000000000001L);
        assertThat(record.getToolCode()).isEqualTo("order_query");
        assertThat(record.getToolName()).isEqualTo("Order Query");
        assertThat(record.getArgumentsJson()).isEqualTo("{\"orderNo\":\"order_1024\"}");
        assertThat(record.getResultJson()).isNull();
        assertThat(record.getStatus()).isEqualTo("RUNNING");
        assertThat(record.getRetryCount()).isZero();
        assertThat(record.getLatencyMs()).isNull();
        assertThat(record.getStartedAt()).isEqualTo(startedAt);
        assertThat(record.getFinishedAt()).isNull();
    }

    @Test
    void shouldInsertRejectedArgumentsWithAStructuredSafeResult() {
        when(mapper.insertCall(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        ToolExecutionResult rejected = ToolExecutionResult.failure(
                "order_query",
                "TOOL_ARGUMENT_INVALID",
                "Tool arguments are invalid",
                4
        );

        service.recordRejected(
                definition,
                ToolExecutionCommand.standalone(definition.id(), objectMapper.createObjectNode()),
                rejected,
                OffsetDateTime.parse("2026-05-01T04:00:00Z")
        );

        ArgumentCaptor<ToolCallLogRecord> captor = ArgumentCaptor.forClass(ToolCallLogRecord.class);
        verify(mapper).insertCall(captor.capture());
        ToolCallLogRecord record = captor.getValue();
        assertThat(record.getStatus()).isEqualTo("REJECTED");
        assertThat(record.getLatencyMs()).isEqualTo(4);
        assertThat(record.getErrorCode()).isEqualTo("TOOL_ARGUMENT_INVALID");
        assertThat(record.getErrorMessage()).isEqualTo("Tool arguments are invalid");
        assertThat(record.getResultJson()).contains(
                "\"success\":false",
                "\"toolCode\":\"order_query\"",
                "\"errorCode\":\"TOOL_ARGUMENT_INVALID\""
        );
        assertThat(record.getFinishedAt()).isEqualTo(OffsetDateTime.parse("2026-05-01T04:00:01Z"));
    }

    @Test
    void shouldUpdateOnlyARunningRowToSuccessOrFailure() throws Exception {
        when(mapper.updateRunningToTerminal(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        ToolExecutionResult success = ToolExecutionResult.success(
                "order_query",
                "summary",
                objectMapper.readTree("{\"orderNo\":\"order_1024\"}"),
                7
        );

        service.recordSuccess(701L, success);

        ArgumentCaptor<ToolCallLogRecord> captor = ArgumentCaptor.forClass(ToolCallLogRecord.class);
        verify(mapper).updateRunningToTerminal(captor.capture());
        ToolCallLogRecord record = captor.getValue();
        assertThat(record.getId()).isEqualTo(701L);
        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getLatencyMs()).isEqualTo(7);
        assertThat(record.getErrorCode()).isNull();
        assertThat(record.getResultJson()).contains(
                "\"success\":true",
                "\"summary\":\"summary\"",
                "\"orderNo\":\"order_1024\""
        );
    }

    private ToolDefinition definition() throws Exception {
        return new ToolDefinition(
                270000000000000001L,
                "order_query",
                "Order Query",
                "description",
                "BUILTIN",
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                objectMapper.readTree("{\"handler\":\"orderQueryTool\"}"),
                3000,
                0,
                false,
                "MEDIUM",
                "ACTIVE"
        );
    }
}
