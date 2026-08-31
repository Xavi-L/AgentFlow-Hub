package com.agentflow.demo.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.demo.dto.DemoOrderResponse;
import com.agentflow.demo.dto.DemoPaymentLogResponse;
import com.agentflow.demo.service.DemoOrderService;
import com.agentflow.demo.service.DemoPaymentLogService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 中文：V26 Demo Business API 的只读入口。Spring Security 的默认规则要求登录，但数据本身是
 * 全局共享 fixture，因此此 Controller 不把 JWT owner 传入查询条件，也不提供增删改或 seed 路由。
 *
 * <p>English: Read-only V26 Demo Business API. The default Spring Security rule requires an
 * authenticated request, while the fixture itself is global; no JWT owner is added to a query,
 * and no mutation or seed route is exposed here.
 */
@RestController
@RequestMapping("${agentflow.api.prefix}/demo")
public class DemoBusinessController {
    private final DemoOrderService demoOrderService;
    private final DemoPaymentLogService demoPaymentLogService;

    public DemoBusinessController(
            DemoOrderService demoOrderService,
            DemoPaymentLogService demoPaymentLogService
    ) {
        this.demoOrderService = demoOrderService;
        this.demoPaymentLogService = demoPaymentLogService;
    }

    @GetMapping("/orders/{orderNo}")
    public ApiResponse<DemoOrderResponse> getOrder(@PathVariable String orderNo) {
        return ApiResponse.success("Demo order retrieved", demoOrderService.getByOrderNo(orderNo));
    }

    @GetMapping("/payment-logs")
    public ApiResponse<List<DemoPaymentLogResponse>> getPaymentLogs(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(
                "Demo payment logs retrieved",
                demoPaymentLogService.query(orderNo, errorCode, limit)
        );
    }
}
