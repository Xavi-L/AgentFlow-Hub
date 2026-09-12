package com.agentflow.agent.task.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agentflow.task.recovery")
public class TaskRecoveryProperties {
    public enum Mode { DISABLED, CONTROLLED_SINGLE_HOST }
    private Mode mode = Mode.DISABLED;
    private String lockPath;
    private int batchSize = 100;
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getLockPath() { return lockPath; }
    public void setLockPath(String lockPath) { this.lockPath = lockPath; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public void validate() {
        if (mode == null || lockPath == null || lockPath.isBlank())
            throw new IllegalStateException("TASK_EXECUTION_NOT_READY: stable task recovery lock-path is required in every mode");
        if (batchSize < 1 || batchSize > 1000)
            throw new IllegalStateException("Task recovery batch-size must be between 1 and 1000");
    }
}
