package com.agentflow.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.demo.dto.DemoOrderResponse;
import com.agentflow.demo.model.MockOrder;
import com.agentflow.demo.repository.MockOrderMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DemoOrderServiceTest {

    @Mock
    private MockOrderMapper mockOrderMapper;

    @InjectMocks
    private DemoOrderService demoOrderService;

    @Test
    void shouldTrimOrderNumberAndMapOnlyTheSafeOrderFields() {
        MockOrder order = order();
        when(mockOrderMapper.selectByOrderNo("order_1024")).thenReturn(order);

        DemoOrderResponse response = demoOrderService.getByOrderNo("  order_1024  ");

        verify(mockOrderMapper).selectByOrderNo("order_1024");
        verifyNoMoreInteractions(mockOrderMapper);
        assertThat(response.orderNo()).isEqualTo("order_1024");
        assertThat(response.amount()).isEqualByComparingTo("199.00");
        assertThat(response.currency()).isEqualTo("CNY");
        assertThat(response.status()).isEqualTo("CREATED");
        assertThat(response.paymentStatus()).isEqualTo("PAY_FAILED");
        assertThat(response.errorCode()).isEqualTo("E_PAY_TIMEOUT");
        assertThat(DemoOrderResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "orderNo",
                        "amount",
                        "currency",
                        "status",
                        "paymentStatus",
                        "errorCode"
                );
    }

    @Test
    void shouldReturnTheUniformNotFoundErrorWhenTheOrderDoesNotExist() {
        when(mockOrderMapper.selectByOrderNo("missing_order")).thenReturn(null);

        assertThatThrownBy(() -> demoOrderService.getByOrderNo(" missing_order "))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_NOT_FOUND));

        verify(mockOrderMapper).selectByOrderNo("missing_order");
        verifyNoMoreInteractions(mockOrderMapper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void shouldRejectAMissingOrBlankOrderNumberBeforeQuerying(String orderNo) {
        assertThatThrownBy(() -> demoOrderService.getByOrderNo(orderNo))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_PARAM_INVALID));

        verify(mockOrderMapper, never()).selectByOrderNo(org.mockito.ArgumentMatchers.any());
    }

    private static MockOrder order() {
        OffsetDateTime auditTime = OffsetDateTime.parse("2026-05-01T12:00:00+08:00");
        MockOrder order = new MockOrder();
        order.setId(260000000000000001L);
        order.setOrderNo("order_1024");
        order.setUserNo("demo_user_1024");
        order.setAmount(new BigDecimal("199.00"));
        order.setCurrency("CNY");
        order.setStatus("CREATED");
        order.setPaymentStatus("PAY_FAILED");
        order.setErrorCode("E_PAY_TIMEOUT");
        order.setCreatedAt(auditTime);
        order.setUpdatedAt(auditTime);
        return order;
    }
}
