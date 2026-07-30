package com.agentflow.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 中文：每个 HTTP 请求只执行一次的链路标识过滤器。执行顺序是：读取/生成 traceId，
 * 写入当前请求上下文和响应头，放行请求，最后无条件清理上下文。
 *
 * <p>English: A once-per-request correlation filter. Its lifecycle is: read or generate
 * a trace ID, expose it through request context and the response header, continue the
 * filter chain, and always clear the context afterward.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);

        if (traceId == null || traceId.isBlank()) {
            traceId = generateTraceId();
        } else {
            // 中文：去掉意外的首尾空白，使响应头和响应体使用完全相同的规范值。
            // English: Trim accidental surrounding whitespace so header and body agree.
            traceId = traceId.trim();
        }

        try {
            TraceIdHolder.setTraceId(traceId);
            response.setHeader(TRACE_ID_HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            TraceIdHolder.clear();
        }
    }

    private String generateTraceId() {
        String date = LocalDate.now().format(DATE_FORMATTER);
        String random = UUID.randomUUID().toString().substring(0, 8);
        return "af-" + date + "-" + random;
    }
}
