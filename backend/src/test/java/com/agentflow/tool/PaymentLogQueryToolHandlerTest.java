package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.demo.dto.DemoPaymentLogResponse;
import com.agentflow.demo.service.DemoPaymentLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

class PaymentLogQueryToolHandlerTest {

    @ParameterizedTest
    @MethodSource("filterCombinations")
    void shouldDelegateEverySupportedFilterCombinationDirectlyToTheV26Service(
            String argumentsJson,
            String orderNo,
            String errorCode,
            Integer limit
    ) throws Exception {
        DemoPaymentLogService service = Mockito.mock(DemoPaymentLogService.class);
        when(service.query(orderNo, errorCode, limit)).thenReturn(List.of());
        ObjectMapper objectMapper = objectMapper();
        PaymentLogQueryToolHandler handler = new PaymentLogQueryToolHandler(service, objectMapper);

        BuiltinToolHandler.HandlerResult result = handler.execute(objectMapper.readTree(argumentsJson));

        assertThat(result.summary()).isEqualTo("Found 0 payment logs matching the supplied filters.");
        assertThat(result.data()).isEqualTo(objectMapper.readTree("{\"logs\":[]}"));
        verify(service).query(orderNo, errorCode, limit);
    }

    @Test
    void shouldWrapOnlyTheSixSafeV26LogFields() throws Exception {
        DemoPaymentLogService service = Mockito.mock(DemoPaymentLogService.class);
        DemoPaymentLogResponse log = new DemoPaymentLogResponse(
                "order_1024",
                "pay-trace-1024",
                "ERROR",
                "E_PAY_TIMEOUT",
                "Payment gateway response timeout after 3000ms",
                OffsetDateTime.parse("2026-05-01T12:00:00+08:00")
        );
        when(service.query("order_1024", "E_PAY_TIMEOUT", 10)).thenReturn(List.of(log));
        ObjectMapper objectMapper = objectMapper();
        PaymentLogQueryToolHandler handler = new PaymentLogQueryToolHandler(service, objectMapper);

        BuiltinToolHandler.HandlerResult result = handler.execute(objectMapper.readTree("""
                {"orderNo":"order_1024","errorCode":"E_PAY_TIMEOUT","limit":10}
                """));

        assertThat(result.summary()).isEqualTo("Found 1 payment log matching the supplied filters.");
        assertThat(result.data().fieldNames()).toIterable().containsExactly("logs");
        assertThat(result.data().path("logs")).hasSize(1);
        assertThat(result.data().path("logs").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder(
                        "orderNo",
                        "traceId",
                        "level",
                        "errorCode",
                        "message",
                        "occurredAt"
                );
        assertThat(result.data().path("logs").get(0).path("occurredAt").textValue())
                .isEqualTo("2026-05-01T12:00:00+08:00");
        assertThat(result.data().path("logs").get(0).has("id")).isFalse();
        assertThat(result.data().path("logs").get(0).has("createdAt")).isFalse();
        verify(service).query("order_1024", "E_PAY_TIMEOUT", 10);
    }

    private static Stream<Arguments> filterCombinations() {
        return Stream.of(
                Arguments.of("{\"orderNo\":\"order_1024\"}", "order_1024", null, null),
                Arguments.of("{\"errorCode\":\"E_PAY_TIMEOUT\",\"limit\":5}", null, "E_PAY_TIMEOUT", 5),
                Arguments.of(
                        "{\"orderNo\":\"order_1024\",\"errorCode\":\"E_PAY_TIMEOUT\",\"limit\":20}",
                        "order_1024",
                        "E_PAY_TIMEOUT",
                        20
                )
        );
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
