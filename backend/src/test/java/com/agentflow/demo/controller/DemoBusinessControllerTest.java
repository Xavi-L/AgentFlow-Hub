package com.agentflow.demo.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.demo.dto.DemoOrderResponse;
import com.agentflow.demo.dto.DemoPaymentLogResponse;
import com.agentflow.demo.service.DemoOrderService;
import com.agentflow.demo.service.DemoPaymentLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 中文：V26 HTTP 外壳测试验证窄 DTO、参数绑定和统一错误响应。完整 JWT 安全链由
 * {@code SecurityConfig} 与手工 HTTP 验收覆盖，本测试不把 standalone MockMvc 当成登录验收。
 *
 * <p>English: V26 HTTP-envelope tests cover narrow DTOs, parameter binding, and unified errors.
 * The complete JWT chain remains the responsibility of {@code SecurityConfig} and manual HTTP
 * acceptance; standalone MockMvc is not presented as authentication proof.
 */
class DemoBusinessControllerTest {

    @Test
    void shouldReturnOnlyTheSafeDemoOrderFields() throws Exception {
        DemoOrderService orderService = Mockito.mock(DemoOrderService.class);
        DemoPaymentLogService paymentLogService = Mockito.mock(DemoPaymentLogService.class);
        when(orderService.getByOrderNo("order_1024")).thenReturn(orderResponse());

        mockMvc(orderService, paymentLogService).perform(MockMvcRequestBuilders.get(
                        "/api/v1/demo/orders/{orderNo}", "order_1024"
                ).header("X-Trace-Id", "af-test-demo-order"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Demo order retrieved"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.length()"
                ).value(6))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.orderNo"
                ).value("order_1024"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.amount"
                ).value(199.0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.status"
                ).value("CREATED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.paymentStatus"
                ).value("PAY_FAILED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.errorCode"
                ).value("E_PAY_TIMEOUT"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.id"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.userNo"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.createdAt"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.updatedAt"
                ).doesNotExist());

        verify(orderService).getByOrderNo("order_1024");
        verifyNoInteractions(paymentLogService);
    }

    @Test
    void shouldMapAMissingDemoOrderToTheCommon404() throws Exception {
        DemoOrderService orderService = Mockito.mock(DemoOrderService.class);
        DemoPaymentLogService paymentLogService = Mockito.mock(DemoPaymentLogService.class);
        when(orderService.getByOrderNo("order_missing"))
                .thenThrow(new BusinessException(ErrorCode.COMMON_NOT_FOUND));

        mockMvc(orderService, paymentLogService).perform(MockMvcRequestBuilders.get(
                        "/api/v1/demo/orders/{orderNo}", "order_missing"
                ).header("X-Trace-Id", "af-test-demo-order-not-found"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_NOT_FOUND"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Resource not found"));

        verify(orderService).getByOrderNo("order_missing");
    }

    @Test
    void shouldQueryPaymentLogsByOrderNumberWithTheServiceDefaultLimit() throws Exception {
        DemoOrderService orderService = Mockito.mock(DemoOrderService.class);
        DemoPaymentLogService paymentLogService = Mockito.mock(DemoPaymentLogService.class);
        when(paymentLogService.query("order_1024", null, null)).thenReturn(List.of(paymentLogResponse()));

        mockMvc(orderService, paymentLogService).perform(MockMvcRequestBuilders.get(
                        "/api/v1/demo/payment-logs"
                ).queryParam("orderNo", "order_1024")
                        .header("X-Trace-Id", "af-test-demo-payment-log-order"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Demo payment logs retrieved"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.length()"
                ).value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].length()"
                ).value(6))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].orderNo"
                ).value("order_1024"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].level"
                ).value("ERROR"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].occurredAt"
                ).value("2026-05-01T12:00:00+08:00"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].id"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].createdAt"
                ).doesNotExist());

        verify(paymentLogService).query("order_1024", null, null);
        verifyNoInteractions(orderService);
    }

    @Test
    void shouldBindErrorCodeAndExplicitLimit() throws Exception {
        DemoOrderService orderService = Mockito.mock(DemoOrderService.class);
        DemoPaymentLogService paymentLogService = Mockito.mock(DemoPaymentLogService.class);
        when(paymentLogService.query(null, "E_PAY_TIMEOUT", 10)).thenReturn(List.of(paymentLogResponse()));

        mockMvc(orderService, paymentLogService).perform(MockMvcRequestBuilders.get(
                        "/api/v1/demo/payment-logs"
                ).queryParam("errorCode", "E_PAY_TIMEOUT")
                        .queryParam("limit", "10")
                        .header("X-Trace-Id", "af-test-demo-payment-log-error"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].errorCode"
                ).value("E_PAY_TIMEOUT"));

        verify(paymentLogService).query(null, "E_PAY_TIMEOUT", 10);
    }

    @Test
    void shouldReturnAnEmptyArrayWhenNoPaymentLogsMatch() throws Exception {
        DemoOrderService orderService = Mockito.mock(DemoOrderService.class);
        DemoPaymentLogService paymentLogService = Mockito.mock(DemoPaymentLogService.class);
        when(paymentLogService.query("order_missing", null, null)).thenReturn(List.of());

        mockMvc(orderService, paymentLogService).perform(MockMvcRequestBuilders.get(
                        "/api/v1/demo/payment-logs"
                ).queryParam("orderNo", "order_missing")
                        .header("X-Trace-Id", "af-test-demo-payment-log-empty"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data")
                        .isArray())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.length()"
                ).value(0));

        verify(paymentLogService).query("order_missing", null, null);
    }

    @Test
    void shouldMapMissingFiltersToTheCommon400() throws Exception {
        DemoOrderService orderService = Mockito.mock(DemoOrderService.class);
        DemoPaymentLogService paymentLogService = Mockito.mock(DemoPaymentLogService.class);
        when(paymentLogService.query(null, null, null))
                .thenThrow(new BusinessException(ErrorCode.COMMON_PARAM_INVALID));

        mockMvc(orderService, paymentLogService).perform(MockMvcRequestBuilders.get(
                        "/api/v1/demo/payment-logs"
                ).header("X-Trace-Id", "af-test-demo-payment-log-missing-filter"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_PARAM_INVALID"));

        verify(paymentLogService).query(null, null, null);
    }

    @Test
    void shouldMapANonNumericLimitTo400BeforeCallingTheService() throws Exception {
        DemoOrderService orderService = Mockito.mock(DemoOrderService.class);
        DemoPaymentLogService paymentLogService = Mockito.mock(DemoPaymentLogService.class);

        mockMvc(orderService, paymentLogService).perform(MockMvcRequestBuilders.get(
                        "/api/v1/demo/payment-logs"
                ).queryParam("orderNo", "order_1024")
                        .queryParam("limit", "not-a-number")
                        .header("X-Trace-Id", "af-test-demo-payment-log-limit-type"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_PARAM_INVALID"));

        verifyNoInteractions(orderService, paymentLogService);
    }

    private static DemoOrderResponse orderResponse() {
        return new DemoOrderResponse(
                "order_1024",
                new BigDecimal("199.00"),
                "CNY",
                "CREATED",
                "PAY_FAILED",
                "E_PAY_TIMEOUT"
        );
    }

    private static DemoPaymentLogResponse paymentLogResponse() {
        return new DemoPaymentLogResponse(
                "order_1024",
                "pay-trace-1024",
                "ERROR",
                "E_PAY_TIMEOUT",
                "Payment gateway response timeout after 3000ms",
                OffsetDateTime.parse("2026-05-01T12:00:00+08:00")
        );
    }

    private static MockMvc mockMvc(
            DemoOrderService orderService,
            DemoPaymentLogService paymentLogService
    ) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders
                .standaloneSetup(new DemoBusinessController(orderService, paymentLogService))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new TraceIdFilter())
                .build();
    }
}
