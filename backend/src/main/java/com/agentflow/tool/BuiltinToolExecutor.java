package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Explicit built-in allowlist router. Database config selects only among code-owned cases; it
 * cannot name an arbitrary Spring bean or Java class and no reflection is used.
 */
@Component
public class BuiltinToolExecutor {
    static final String ORDER_QUERY_CODE = "order_query";
    static final String ORDER_QUERY_HANDLER = "orderQueryTool";
    static final String PAYMENT_LOG_QUERY_CODE = "payment_log_query";
    static final String PAYMENT_LOG_QUERY_HANDLER = "paymentLogQueryTool";
    static final String REPORT_GENERATE_CODE = "report_generate";
    static final String REPORT_GENERATE_HANDLER = "reportGenerateTool";

    private final OrderQueryToolHandler orderQueryToolHandler;
    private final PaymentLogQueryToolHandler paymentLogQueryToolHandler;
    private final ReportGenerateToolHandler reportGenerateToolHandler;

    public BuiltinToolExecutor(
            OrderQueryToolHandler orderQueryToolHandler,
            PaymentLogQueryToolHandler paymentLogQueryToolHandler,
            ReportGenerateToolHandler reportGenerateToolHandler
    ) {
        this.orderQueryToolHandler = Objects.requireNonNull(
                orderQueryToolHandler,
                "orderQueryToolHandler must not be null"
        );
        this.paymentLogQueryToolHandler = Objects.requireNonNull(
                paymentLogQueryToolHandler,
                "paymentLogQueryToolHandler must not be null"
        );
        this.reportGenerateToolHandler = Objects.requireNonNull(
                reportGenerateToolHandler,
                "reportGenerateToolHandler must not be null"
        );
    }

    public BuiltinToolHandler.HandlerResult execute(ToolDefinition tool, JsonNode arguments) {
        if (!"BUILTIN".equals(tool.type())) {
            throw new IllegalStateException("The built-in executor supports only BUILTIN definitions");
        }
        JsonNode handlerNode = tool.config().get("handler");
        String handler = handlerNode != null && handlerNode.isTextual()
                ? handlerNode.textValue()
                : null;
        if (ORDER_QUERY_CODE.equals(tool.toolCode()) && ORDER_QUERY_HANDLER.equals(handler)) {
            return orderQueryToolHandler.execute(arguments);
        }
        if (PAYMENT_LOG_QUERY_CODE.equals(tool.toolCode())
                && PAYMENT_LOG_QUERY_HANDLER.equals(handler)) {
            return paymentLogQueryToolHandler.execute(arguments);
        }
        if (REPORT_GENERATE_CODE.equals(tool.toolCode())
                && REPORT_GENERATE_HANDLER.equals(handler)) {
            return reportGenerateToolHandler.execute(arguments);
        }
        throw new IllegalStateException("Tool definition is not on the built-in allowlist");
    }
}
