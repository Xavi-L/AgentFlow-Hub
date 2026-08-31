package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.demo.dto.DemoOrderResponse;
import com.agentflow.demo.service.DemoOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OrderQueryToolHandlerTest {

    @Test
    void shouldDelegateToTheV26ServiceAndReturnOnlyItsSixSafeFields() throws Exception {
        DemoOrderService demoOrderService = Mockito.mock(DemoOrderService.class);
        when(demoOrderService.getByOrderNo("order_1024")).thenReturn(new DemoOrderResponse(
                "order_1024",
                new BigDecimal("199.00"),
                "CNY",
                "CREATED",
                "PAY_FAILED",
                "E_PAY_TIMEOUT"
        ));
        ObjectMapper objectMapper = new ObjectMapper();
        OrderQueryToolHandler handler = new OrderQueryToolHandler(demoOrderService, objectMapper);

        BuiltinToolHandler.HandlerResult result = handler.execute(
                objectMapper.readTree("{\"orderNo\":\"order_1024\"}")
        );

        assertThat(result.summary()).contains(
                "order_1024",
                "CREATED",
                "PAY_FAILED",
                "199.00 CNY",
                "E_PAY_TIMEOUT"
        );
        assertThat(result.data().fieldNames()).toIterable().containsExactlyInAnyOrder(
                "orderNo",
                "amount",
                "currency",
                "status",
                "paymentStatus",
                "errorCode"
        );
        assertThat(result.data().path("amount").decimalValue()).isEqualByComparingTo("199.00");
        assertThat(result.data().path("paymentStatus").textValue()).isEqualTo("PAY_FAILED");
        assertThat(result.data().has("id")).isFalse();
        assertThat(result.data().has("userNo")).isFalse();
        assertThat(result.data().has("createdAt")).isFalse();
        verify(demoOrderService).getByOrderNo("order_1024");
    }
}
