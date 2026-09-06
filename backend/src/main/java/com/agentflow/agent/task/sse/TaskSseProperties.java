package com.agentflow.agent.task.sse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Single-instance transport limits, independent of the task Runner executor. */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties("agentflow.task.sse")
public class TaskSseProperties {
    @Min(1) @Max(4096) private int maxConnections = 128;
    @Min(1) @Max(4096) private int maxConnectionsPerUser = 8;
    @Min(1) @Max(256) private int batchSize = 64;
    @Min(1024) @Max(16777216) private int maxPendingBytes = 262144;
    @Min(10) private long pollIntervalMs = 250;
    @Min(10) private long heartbeatIntervalMs = 15000;
    @Min(100) private long connectionTimeoutMs = 300000;
    @Min(100) private long sendTimeoutMs = 10000;
    @Min(1) @Max(32) private int pollWorkers = 4;
}
