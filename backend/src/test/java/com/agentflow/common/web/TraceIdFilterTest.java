package com.agentflow.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {
    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void shouldReuseIncomingTraceIdAndClearHolderAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "af-manual-test-001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdSeenInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                traceIdSeenInsideChain.set(TraceIdHolder.getTraceId()));

        assertThat(traceIdSeenInsideChain).hasValue("af-manual-test-001");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo("af-manual-test-001");
        assertThat(TraceIdHolder.getTraceId()).isNull();
    }

    @Test
    void shouldGenerateDocumentedTraceIdWhenHeaderIsMissing() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (request, servletResponse) -> {
        });

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .matches("af-\\d{8}-[0-9a-f]{8}");
        assertThat(TraceIdHolder.getTraceId()).isNull();
    }
}
