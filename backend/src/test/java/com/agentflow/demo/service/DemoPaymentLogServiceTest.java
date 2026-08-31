package com.agentflow.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.demo.dto.DemoPaymentLogResponse;
import com.agentflow.demo.model.MockPaymentLog;
import com.agentflow.demo.repository.MockPaymentLogMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DemoPaymentLogServiceTest {

    @Mock
    private MockPaymentLogMapper mockPaymentLogMapper;

    @InjectMocks
    private DemoPaymentLogService demoPaymentLogService;

    @Test
    void shouldTrimBothFiltersUseTheDefaultLimitAndMapOnlySafeLogFields() {
        MockPaymentLog log = paymentLog();
        when(mockPaymentLogMapper.selectByFilters("order_1024", "E_PAY_TIMEOUT", 10))
                .thenReturn(List.of(log));

        List<DemoPaymentLogResponse> responses = demoPaymentLogService.query(
                "  order_1024  ",
                "  E_PAY_TIMEOUT  ",
                null
        );

        verify(mockPaymentLogMapper).selectByFilters("order_1024", "E_PAY_TIMEOUT", 10);
        verifyNoMoreInteractions(mockPaymentLogMapper);
        assertThat(responses).hasSize(1);
        DemoPaymentLogResponse response = responses.getFirst();
        assertThat(response.orderNo()).isEqualTo("order_1024");
        assertThat(response.traceId()).isEqualTo("pay-trace-1024");
        assertThat(response.level()).isEqualTo("ERROR");
        assertThat(response.errorCode()).isEqualTo("E_PAY_TIMEOUT");
        assertThat(response.message()).isEqualTo("Payment gateway response timeout after 3000ms");
        assertThat(response.occurredAt())
                .isEqualTo(OffsetDateTime.parse("2026-05-01T12:00:00+08:00"));
        assertThat(DemoPaymentLogResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "orderNo",
                        "traceId",
                        "level",
                        "errorCode",
                        "message",
                        "occurredAt"
                );
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void shouldAcceptTheInclusiveLimitBoundaries(int limit) {
        when(mockPaymentLogMapper.selectByFilters("order_1024", null, limit))
                .thenReturn(List.of());

        List<DemoPaymentLogResponse> responses = demoPaymentLogService.query(
                "order_1024",
                "  ",
                limit
        );

        assertThat(responses).isEmpty();
        verify(mockPaymentLogMapper).selectByFilters("order_1024", null, limit);
        verifyNoMoreInteractions(mockPaymentLogMapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 21, 100})
    void shouldRejectAnOutOfRangeLimitBeforeQuerying(int limit) {
        assertThatThrownBy(() -> demoPaymentLogService.query("order_1024", null, limit))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_PARAM_INVALID));

        verify(mockPaymentLogMapper, never()).selectByFilters(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @ParameterizedTest
    @MethodSource("missingFilters")
    void shouldRejectMissingOrBlankFiltersBeforeQuerying(String orderNo, String errorCode) {
        assertThatThrownBy(() -> demoPaymentLogService.query(orderNo, errorCode, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_PARAM_INVALID));

        verifyNoInteractions(mockPaymentLogMapper);
    }

    @Test
    void shouldReturnAnEmptyListWhenNoPaymentLogMatches() {
        when(mockPaymentLogMapper.selectByFilters(null, "E_PAY_UNKNOWN", 5))
                .thenReturn(List.of());

        List<DemoPaymentLogResponse> responses = demoPaymentLogService.query(
                null,
                " E_PAY_UNKNOWN ",
                5
        );

        assertThat(responses).isEmpty();
        verify(mockPaymentLogMapper).selectByFilters(null, "E_PAY_UNKNOWN", 5);
        verifyNoMoreInteractions(mockPaymentLogMapper);
    }

    private static Stream<Arguments> missingFilters() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of("", null),
                Arguments.of(null, ""),
                Arguments.of("  ", "\t")
        );
    }

    private static MockPaymentLog paymentLog() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-05-01T12:00:00+08:00");
        MockPaymentLog log = new MockPaymentLog();
        log.setId(260000000000000002L);
        log.setOrderNo("order_1024");
        log.setTraceId("pay-trace-1024");
        log.setLogLevel("ERROR");
        log.setErrorCode("E_PAY_TIMEOUT");
        log.setMessage("Payment gateway response timeout after 3000ms");
        log.setOccurredAt(occurredAt);
        log.setCreatedAt(occurredAt);
        return log;
    }
}
