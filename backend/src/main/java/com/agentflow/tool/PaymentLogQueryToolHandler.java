package com.agentflow.tool;

import com.agentflow.demo.dto.DemoPaymentLogResponse;
import com.agentflow.demo.service.DemoPaymentLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Built-in payment_log_query implementation backed directly by the V26 read-only service. */
@Component
public class PaymentLogQueryToolHandler implements BuiltinToolHandler {
    private final DemoPaymentLogService demoPaymentLogService;
    private final ObjectMapper objectMapper;

    public PaymentLogQueryToolHandler(
            DemoPaymentLogService demoPaymentLogService,
            ObjectMapper objectMapper
    ) {
        this.demoPaymentLogService = Objects.requireNonNull(
                demoPaymentLogService,
                "demoPaymentLogService must not be null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public HandlerResult execute(JsonNode arguments) {
        String orderNo = textualOrNull(arguments, "orderNo");
        String errorCode = textualOrNull(arguments, "errorCode");
        Integer limit = arguments.has("limit") ? arguments.get("limit").intValue() : null;
        List<DemoPaymentLogResponse> logs = demoPaymentLogService.query(orderNo, errorCode, limit);
        String summary = "Found %d payment log%s matching the supplied filters."
                .formatted(logs.size(), logs.size() == 1 ? "" : "s");
        return new HandlerResult(
                summary,
                objectMapper.valueToTree(new PaymentLogQueryToolData(logs))
        );
    }

    private static String textualOrNull(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        return value == null ? null : value.textValue();
    }
}
