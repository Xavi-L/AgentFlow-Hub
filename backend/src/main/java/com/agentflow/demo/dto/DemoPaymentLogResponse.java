package com.agentflow.demo.dto;

import com.agentflow.demo.model.MockPaymentLog;
import java.time.OffsetDateTime;

/**
 * 中文：模拟支付日志的安全只读输出。{@code occurredAt} 是业务事件时间；数据库 ID 与
 * {@code createdAt} 审计时间不向客户端公开。
 *
 * <p>English: Safe read-only output for a demo payment log. {@code occurredAt} is the business
 * event time; the database ID and {@code createdAt} audit timestamp remain internal.
 */
public record DemoPaymentLogResponse(
        String orderNo,
        String traceId,
        String level,
        String errorCode,
        String message,
        OffsetDateTime occurredAt
) {
    public static DemoPaymentLogResponse from(MockPaymentLog log) {
        return new DemoPaymentLogResponse(
                log.getOrderNo(),
                log.getTraceId(),
                log.getLogLevel(),
                log.getErrorCode(),
                log.getMessage(),
                log.getOccurredAt()
        );
    }
}
