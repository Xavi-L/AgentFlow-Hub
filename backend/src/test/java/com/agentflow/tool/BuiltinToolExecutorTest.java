package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class BuiltinToolExecutorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRouteOnlyTheExplicitOrderCodeAndHandlerPair() throws Exception {
        OrderQueryToolHandler orderHandler = Mockito.mock(OrderQueryToolHandler.class);
        PaymentLogQueryToolHandler paymentHandler = Mockito.mock(PaymentLogQueryToolHandler.class);
        JsonNode arguments = objectMapper.readTree("{\"orderNo\":\"order_1024\"}");
        BuiltinToolHandler.HandlerResult expected = new BuiltinToolHandler.HandlerResult(
                "summary",
                objectMapper.readTree("{\"orderNo\":\"order_1024\"}")
        );
        when(orderHandler.execute(arguments)).thenReturn(expected);

        BuiltinToolHandler.HandlerResult result = new BuiltinToolExecutor(
                orderHandler,
                paymentHandler
        ).execute(
                definition("order_query", "orderQueryTool"),
                arguments
        );

        assertThat(result).isSameAs(expected);
        verify(orderHandler).execute(arguments);
        verifyNoInteractions(paymentHandler);
    }

    @Test
    void shouldRouteOnlyTheExplicitPaymentLogCodeAndHandlerPair() throws Exception {
        OrderQueryToolHandler orderHandler = Mockito.mock(OrderQueryToolHandler.class);
        PaymentLogQueryToolHandler paymentHandler = Mockito.mock(PaymentLogQueryToolHandler.class);
        JsonNode arguments = objectMapper.readTree("{\"errorCode\":\"E_PAY_TIMEOUT\"}");
        BuiltinToolHandler.HandlerResult expected = new BuiltinToolHandler.HandlerResult(
                "summary",
                objectMapper.readTree("{\"logs\":[]}")
        );
        when(paymentHandler.execute(arguments)).thenReturn(expected);

        BuiltinToolHandler.HandlerResult result = new BuiltinToolExecutor(
                orderHandler,
                paymentHandler
        ).execute(
                definition("payment_log_query", "paymentLogQueryTool"),
                arguments
        );

        assertThat(result).isSameAs(expected);
        verify(paymentHandler).execute(arguments);
        verifyNoInteractions(orderHandler);
    }

    @Test
    void shouldRejectAnUnknownDatabaseHandlerWithoutDynamicBeanOrClassExecution() throws Exception {
        OrderQueryToolHandler orderHandler = Mockito.mock(OrderQueryToolHandler.class);
        PaymentLogQueryToolHandler paymentHandler = Mockito.mock(PaymentLogQueryToolHandler.class);

        assertThatThrownBy(() -> new BuiltinToolExecutor(orderHandler, paymentHandler).execute(
                definition("order_query", "java.lang.Runtime"),
                objectMapper.createObjectNode()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowlist");

        verifyNoInteractions(orderHandler, paymentHandler);
    }

    @Test
    void shouldRejectEvenAnAllowedHandlerWhenTheToolCodeIsNotAllowlisted() throws Exception {
        OrderQueryToolHandler orderHandler = Mockito.mock(OrderQueryToolHandler.class);
        PaymentLogQueryToolHandler paymentHandler = Mockito.mock(PaymentLogQueryToolHandler.class);

        assertThatThrownBy(() -> new BuiltinToolExecutor(orderHandler, paymentHandler).execute(
                definition("payment_log_query", "orderQueryTool"),
                objectMapper.createObjectNode()
        )).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(orderHandler, paymentHandler);
    }

    private ToolDefinition definition(String code, String handler) throws Exception {
        return new ToolDefinition(
                270000000000000001L,
                code,
                "Order Query",
                "description",
                "BUILTIN",
                objectMapper.readTree("{\"type\":\"object\"}"),
                objectMapper.createObjectNode(),
                objectMapper.readTree("{\"handler\":\"" + handler + "\",\"readonly\":true}"),
                3000,
                0,
                false,
                "MEDIUM",
                "ACTIVE"
        );
    }
}
