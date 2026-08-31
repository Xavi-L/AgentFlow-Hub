package com.agentflow.demo.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.demo.dto.DemoPaymentLogResponse;
import com.agentflow.demo.repository.MockPaymentLogMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：全局共享 demo 支付日志的只读查询边界。两个可选过滤条件同时出现时按 AND 组合；
 * 稳定排序和 LIMIT 在 Mapper SQL 中完成，因此 V27 可直接复用相同语义。
 *
 * <p>English: Read-only query boundary for globally shared demo payment logs. When both optional
 * filters are present they are combined with AND; stable ordering and LIMIT remain in mapper SQL
 * so V27 can reuse the same semantics directly.
 */
@Service
public class DemoPaymentLogService {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 20;
    private static final int MAX_ORDER_NO_LENGTH = 64;
    private static final int MAX_ERROR_CODE_LENGTH = 64;

    private final MockPaymentLogMapper mockPaymentLogMapper;

    public DemoPaymentLogService(MockPaymentLogMapper mockPaymentLogMapper) {
        this.mockPaymentLogMapper = mockPaymentLogMapper;
    }

    @Transactional(readOnly = true)
    public List<DemoPaymentLogResponse> query(String orderNo, String errorCode, Integer limit) {
        String normalizedOrderNo = normalizeOptional(orderNo, MAX_ORDER_NO_LENGTH);
        String normalizedErrorCode = normalizeOptional(errorCode, MAX_ERROR_CODE_LENGTH);
        if (normalizedOrderNo == null && normalizedErrorCode == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }

        int normalizedLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (normalizedLimit < MIN_LIMIT || normalizedLimit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }

        return mockPaymentLogMapper.selectByFilters(
                        normalizedOrderNo,
                        normalizedErrorCode,
                        normalizedLimit
                ).stream()
                .map(DemoPaymentLogResponse::from)
                .toList();
    }

    private static String normalizeOptional(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        return normalized;
    }
}
