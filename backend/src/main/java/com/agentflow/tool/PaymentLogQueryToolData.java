package com.agentflow.tool;

import com.agentflow.demo.dto.DemoPaymentLogResponse;
import java.util.List;
import java.util.Objects;

/** Safe payment_log_query data wrapper containing only the V26 public log DTO. */
public record PaymentLogQueryToolData(List<DemoPaymentLogResponse> logs) {
    public PaymentLogQueryToolData {
        logs = List.copyOf(Objects.requireNonNull(logs, "logs must not be null"));
    }
}
