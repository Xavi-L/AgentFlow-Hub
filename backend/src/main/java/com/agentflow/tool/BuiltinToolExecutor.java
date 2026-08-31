package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Explicit V27 allowlist router. Database config selects only among code-owned cases; it cannot
 * name an arbitrary Spring bean or Java class and no reflection is used.
 */
@Component
public class BuiltinToolExecutor {
    static final String ORDER_QUERY_CODE = "order_query";
    static final String ORDER_QUERY_HANDLER = "orderQueryTool";

    private final OrderQueryToolHandler orderQueryToolHandler;

    public BuiltinToolExecutor(OrderQueryToolHandler orderQueryToolHandler) {
        this.orderQueryToolHandler = Objects.requireNonNull(
                orderQueryToolHandler,
                "orderQueryToolHandler must not be null"
        );
    }

    public BuiltinToolHandler.HandlerResult execute(ToolDefinition tool, JsonNode arguments) {
        if (!"BUILTIN".equals(tool.type())) {
            throw new IllegalStateException("V27 supports only BUILTIN tool definitions");
        }
        JsonNode handlerNode = tool.config().get("handler");
        String handler = handlerNode != null && handlerNode.isTextual()
                ? handlerNode.textValue()
                : null;
        if (ORDER_QUERY_CODE.equals(tool.toolCode()) && ORDER_QUERY_HANDLER.equals(handler)) {
            return orderQueryToolHandler.execute(arguments);
        }
        throw new IllegalStateException("Tool definition is not on the V27 built-in allowlist");
    }
}
