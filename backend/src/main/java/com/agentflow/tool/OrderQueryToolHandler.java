package com.agentflow.tool;

import com.agentflow.demo.dto.DemoOrderResponse;
import com.agentflow.demo.service.DemoOrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Built-in order_query implementation backed directly by the V26 read-only service. */
@Component
public class OrderQueryToolHandler implements BuiltinToolHandler {
    private final DemoOrderService demoOrderService;
    private final ObjectMapper objectMapper;

    public OrderQueryToolHandler(DemoOrderService demoOrderService, ObjectMapper objectMapper) {
        this.demoOrderService = Objects.requireNonNull(demoOrderService, "demoOrderService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public HandlerResult execute(JsonNode arguments) {
        DemoOrderResponse order = demoOrderService.getByOrderNo(arguments.path("orderNo").textValue());
        String summary = "Order %s is %s with payment status %s, amount %s %s%s."
                .formatted(
                        order.orderNo(),
                        order.status(),
                        order.paymentStatus(),
                        order.amount().toPlainString(),
                        order.currency(),
                        order.errorCode() == null ? "" : " and error code " + order.errorCode()
                );
        return new HandlerResult(summary, objectMapper.valueToTree(order));
    }
}
