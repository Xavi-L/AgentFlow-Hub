package com.agentflow.common.web;

import com.agentflow.common.api.ApiResponse;
import java.time.OffsetDateTime;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
    private final Environment environment;

    public HealthController(Environment environment) {
        this.environment = environment;
    }

    /**
     * 中文：提供不依赖数据库的轻量存活检查，并用统一响应外壳返回应用元数据。
     * English: Provides a lightweight, database-independent liveness check and returns
     * application metadata through the shared response envelope.
     */
    @GetMapping
    public ApiResponse<HealthResponse> health() {
        String[] activeProfiles = environment.getActiveProfiles();
        String profile = activeProfiles.length == 0 ? "default" : String.join(",", activeProfiles);

        HealthResponse response = new HealthResponse(
                "UP",
                environment.getProperty("spring.application.name", "agentflow-hub"),
                profile,
                OffsetDateTime.now()
        );

        return ApiResponse.success(response);
    }

    public record HealthResponse(
            String status,
            String application,
            String profile,
            OffsetDateTime timestamp
    ) {
    }
}
