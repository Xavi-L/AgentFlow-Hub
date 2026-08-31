package com.agentflow.demo.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.demo.dto.DemoOrderResponse;
import com.agentflow.demo.model.MockOrder;
import com.agentflow.demo.repository.MockOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：全局共享 demo 订单的只读业务边界。它不接收 owner，也不执行任何 seed 或修改操作，
 * 后续内置工具可以直接复用该服务而不绕行 HTTP。
 *
 * <p>English: Read-only business boundary for globally shared demo orders. It accepts no owner
 * and performs no seeding or mutation, allowing a later built-in tool to reuse it directly.
 */
@Service
public class DemoOrderService {
    private static final int MAX_ORDER_NO_LENGTH = 64;

    private final MockOrderMapper mockOrderMapper;

    public DemoOrderService(MockOrderMapper mockOrderMapper) {
        this.mockOrderMapper = mockOrderMapper;
    }

    @Transactional(readOnly = true)
    public DemoOrderResponse getByOrderNo(String orderNo) {
        String normalizedOrderNo = normalizeRequiredOrderNo(orderNo);
        MockOrder order = mockOrderMapper.selectByOrderNo(normalizedOrderNo);
        if (order == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return DemoOrderResponse.from(order);
    }

    private static String normalizeRequiredOrderNo(String orderNo) {
        if (orderNo == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        String normalized = orderNo.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_ORDER_NO_LENGTH) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        return normalized;
    }
}
