package com.agentflow.demo.dto;

import com.agentflow.demo.model.MockOrder;
import java.math.BigDecimal;

/**
 * 中文：模拟订单的安全只读输出。内部数据库 ID、模拟用户编号与审计时间均不进入响应。
 * English: Safe read-only output for a demo order. Internal IDs, demo user numbers, and audit
 * timestamps are deliberately excluded.
 */
public record DemoOrderResponse(
        String orderNo,
        BigDecimal amount,
        String currency,
        String status,
        String paymentStatus,
        String errorCode
) {
    public static DemoOrderResponse from(MockOrder order) {
        return new DemoOrderResponse(
                order.getOrderNo(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getErrorCode()
        );
    }
}
