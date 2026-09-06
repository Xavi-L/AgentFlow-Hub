package com.agentflow.agent.task.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.agentflow.agent.task.dto.SafeTaskEventResponse;
import com.agentflow.agent.task.dto.TaskEventBatch;
import com.agentflow.agent.task.model.TaskStatus;
import com.agentflow.agent.task.service.TaskEventGapException;
import com.agentflow.agent.task.service.TaskEventQueryService;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Deterministic socket readiness/failure tests complement the real HTTP acceptance. */
class TaskSseTransportTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final TaskEventQueryService events = mock(TaskEventQueryService.class);
    private final TaskSseProperties limits = new TaskSseProperties();
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final AsyncContext async = mock(AsyncContext.class);
    private final Output output = new Output();
    private TaskSseService service;

    @AfterEach void stop() { if (service != null) service.shutdown(); }

    @Test void shouldStopReadingAndReleaseStalledConnectionWithoutBlockingAnyWriterThread() throws Exception {
        limits.setSendTimeoutMs(100);
        limits.setConnectionTimeoutMs(3000);
        output.ready = false;
        when(events.findOwnedBatch(anyLong(), anyLong(), anyLong(), anyInt()))
                .thenReturn(batch(TaskStatus.RUNNING, 1, event(1, "x")));
        start();
        output.listener.onWritePossible();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(service.activeConnectionCount()).isZero());
        verify(events, times(1)).findOwnedBatch(1, 2, 0, 64);
        verify(async).complete();
        assertThat(output.bytes.size()).isZero();
        output.listener.onError(new IOException("duplicate disconnect notification"));
        assertThat(service.activeConnectionCount()).isZero();
        verify(async, times(1)).complete();
    }

    @Test void shouldReleaseOnWriteFailureAndNeverCancelOrAdvanceTaskExecution() throws Exception {
        output.failure = true;
        when(events.findOwnedBatch(anyLong(), anyLong(), anyLong(), anyInt()))
                .thenReturn(batch(TaskStatus.RUNNING, 1, event(1, "x")));
        start();
        output.listener.onWritePossible();
        assertThat(service.activeConnectionCount()).isZero();
        verify(async).complete();
        verify(events).findOwnedBatch(1, 2, 0, 64);
    }

    @Test void shouldRefreshSlowClientDeadlineWhenEachWriteMakesProgress() throws Exception {
        limits.setSendTimeoutMs(400);
        limits.setPollIntervalMs(10000);
        output.pauseAfterWrite = true;
        when(events.findOwnedBatch(anyLong(), anyLong(), anyLong(), anyInt()))
                .thenReturn(batch(TaskStatus.COMPLETED, 1, event(1, "x".repeat(16000))));
        start();
        output.listener.onWritePossible();
        Thread.sleep(250);
        output.ready = true;
        output.listener.onWritePossible();
        Thread.sleep(250);
        // More than 400ms elapsed for the whole batch, but no 400ms interval without progress.
        assertThat(service.activeConnectionCount()).isEqualTo(1);
        output.pauseAfterWrite = false;
        output.ready = true;
        output.listener.onWritePossible();
        assertThat(service.activeConnectionCount()).isZero();
        verify(async).complete();
    }

    @Test void shouldResumeAtLastWholeFrameWhenTheByteCapCutsABatchShort() throws Exception {
        limits.setMaxPendingBytes(1024);
        limits.setPollIntervalMs(10);
        var first = event(1, "a".repeat(500));
        var second = event(2, "b".repeat(500));
        when(events.findOwnedBatch(1, 2, 0, 64)).thenReturn(batch(TaskStatus.COMPLETED, 2, first, second));
        when(events.findOwnedBatch(1, 2, 1, 64)).thenReturn(batch(TaskStatus.COMPLETED, 2, second));
        start();
        output.listener.onWritePossible();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(service.activeConnectionCount()).isZero());
        verify(events).findOwnedBatch(1, 2, 1, 64);
        assertThat(output.text().lines().filter(line -> line.startsWith("id: ")).toList())
                .containsExactly("id: 1", "id: 2");
    }

    @Test void shouldCompleteTerminalEmptyReplayAndDisposeItsScheduledRead() throws Exception {
        when(events.findOwnedBatch(anyLong(), anyLong(), anyLong(), anyInt()))
                .thenReturn(batch(TaskStatus.COMPLETED, 0));
        start();
        output.listener.onWritePossible();
        assertThat(service.activeConnectionCount()).isZero();
        assertThat(output.text()).isEqualTo(": connected\n\n");
        verify(async).complete();
        verify(events, after(100).times(1)).findOwnedBatch(1, 2, 0, 64);
    }

    @Test void shouldReportGapWithoutAnIdOrSendingPastTheMissingSequence() throws Exception {
        limits.setPollIntervalMs(10);
        when(events.findOwnedBatch(1, 2, 0, 64)).thenReturn(batch(TaskStatus.RUNNING, 1, event(1, "a")));
        when(events.findOwnedBatch(1, 2, 1, 64)).thenThrow(new TaskEventGapException(2, 3L, 3));
        start();
        output.listener.onWritePossible();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(service.activeConnectionCount()).isZero());
        assertThat(output.text()).contains("id: 1\n", "event: STREAM_ERROR\n", "\"lastSentSequence\":1")
                .doesNotContain("id: 2\n", "id: 3\n");
    }

    @Test void shouldFitOnlyWholeFramesAndRoundTripUnicodeNewlinesAndJsonLookingAnswerText() throws Exception {
        var encoder = new TaskSseEncoder(json);
        String text = "{\"token\":\"literal\"}\r\ndata: fake\n  中文😀 ";
        var one = batch(TaskStatus.COMPLETED, 2, event(1, text), event(2, "next"));
        var frames = encoder.batch(one, 100000);
        var bounded = encoder.batch(one, frames.getFirst().bytes().length);
        assertThat(bounded).hasSize(1);
        String wire = new String(bounded.getFirst().bytes(), StandardCharsets.UTF_8);
        assertThat(wire.lines().filter(line -> line.startsWith("data: "))).hasSize(1);
        String data = wire.lines().filter(line -> line.startsWith("data: ")).findFirst().orElseThrow().substring(6);
        assertThat(json.readTree(data).path("payload").path("text").asText()).isEqualTo(text);
        assertThatThrownBy(() -> encoder.batch(one, 8)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_SSE_EVENT_TOO_LARGE));
    }

    @Test void shouldReleaseReservedSlotIfFirstFrameCannotFitBeforeStartingAsync() throws Exception {
        limits.setMaxPendingBytes(1024);
        when(events.findOwnedBatch(anyLong(), anyLong(), anyLong(), anyInt()))
                .thenReturn(batch(TaskStatus.COMPLETED, 1, event(1, "x".repeat(2000))));
        service = new TaskSseService(events, new TaskSseEncoder(json), limits);
        assertThatThrownBy(() -> service.open(1, 2, 0, request, response))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_SSE_EVENT_TOO_LARGE));
        assertThat(service.activeConnectionCount()).isZero();
        verifyNoInteractions(request, response);
    }

    @Test void shouldResolveDefaultHeaderAndMatchingNumericCursors() {
        assertThat(TaskSseCursor.resolve(null, List.of())).isZero();
        assertThat(TaskSseCursor.resolve(null, List.of("8"))).isEqualTo(8);
        assertThat(TaskSseCursor.resolve(List.of("008"), List.of("8"))).isEqualTo(8);
        assertThat(TaskSseCursor.resolve(List.of("9223372036854775807"), null)).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> TaskSseCursor.resolve(List.of("1"), List.of("2")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> TaskSseCursor.resolve(List.of("1", "1"), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> TaskSseCursor.resolve(null, List.of("1", "1")))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " 1", "1 ", "-1", "+1", "1.0", "1e2", "1,1", "１", "9223372036854775808", "00000000000000000000"})
    void shouldRejectMalformedAndOverflowingCursor(String cursor) {
        assertThatThrownBy(() -> TaskSseCursor.resolve(List.of(cursor), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COMMON_PARAM_INVALID));
    }

    private void start() throws IOException {
        when(request.startAsync(request, response)).thenReturn(async);
        when(response.getOutputStream()).thenReturn(output);
        service = new TaskSseService(events, new TaskSseEncoder(json), limits);
        service.open(1, 2, 0, request, response);
    }

    private TaskEventBatch batch(TaskStatus status, long last, SafeTaskEventResponse... values) {
        return new TaskEventBatch(status, last, List.of(values));
    }

    private SafeTaskEventResponse event(long sequence, String text) {
        return new SafeTaskEventResponse("999", "2", sequence, "ANSWER_CHUNK",
                json.createObjectNode().put("chunkIndex", sequence - 1).put("text", text), OffsetDateTime.now());
    }

    private static class Output extends ServletOutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile boolean ready = true;
        boolean pauseAfterWrite;
        boolean failure;
        WriteListener listener;
        @Override public boolean isReady() { return ready; }
        @Override public void setWriteListener(WriteListener listener) { this.listener = listener; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            super.write(bytes, offset, length);
            if (pauseAfterWrite) ready = false;
        }
        @Override public void write(int b) throws IOException {
            if (!ready) throw new AssertionError("Write attempted without readiness");
            if (failure) throw new IOException("Controlled broken socket");
            bytes.write(b);
        }
        String text() { return bytes.toString(StandardCharsets.UTF_8); }
    }
}
