package com.agentflow.agent.task.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentflow.task.dispatcher")
public class AgentTaskDispatcherProperties {
    private int corePoolSize = 2;
    private int maxPoolSize = 4;
    private int queueCapacity = 100;

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    void validate() {
        if (corePoolSize < 1 || maxPoolSize < corePoolSize || queueCapacity < 0) {
            throw new IllegalArgumentException("Agent task dispatcher pool configuration is invalid");
        }
    }
}
