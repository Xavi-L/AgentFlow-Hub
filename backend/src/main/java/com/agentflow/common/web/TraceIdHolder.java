package com.agentflow.common.web;

import java.util.Objects;

/**
 * 中文：保存“当前请求”的 traceId。Web 容器会并发处理请求，所以不能使用普通 static String；
 * {@link ThreadLocal} 为每条请求线程提供彼此隔离的存储槽。
 *
 * <p>English: Stores the trace ID for the current request. A web container handles requests
 * concurrently, so a plain static String would be unsafe; {@link ThreadLocal} provides an
 * isolated storage slot for each request thread.
 */
public final class TraceIdHolder {
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    /**
     * 中文：工具类不需要实例状态，因此禁止 new。
     * English: This utility owns no instance state, so construction is prohibited.
     */
    private TraceIdHolder() {
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(Objects.requireNonNull(traceId, "traceId must not be null"));
    }

    public static String getTraceId()  {
        return TRACE_ID.get();
    }

    public static void clear() {
        // 中文：必须 remove 而不是 set(null)，这样 ThreadLocalMap 的条目也能被释放。
        // English: remove releases the ThreadLocalMap entry instead of merely storing null.
        TRACE_ID.remove();
    }
}
