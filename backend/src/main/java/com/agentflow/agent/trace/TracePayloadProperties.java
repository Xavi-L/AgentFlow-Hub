package com.agentflow.agent.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configurable serialized UTF-8 byte ceilings for durable Trace payloads. */
@ConfigurationProperties(prefix = "agentflow.trace.payload")
public class TracePayloadProperties {
    private int smallMaxBytes = 16 * 1024;
    private int toolMaxBytes = 64 * 1024;
    private int largeMaxBytes = 256 * 1024;
    private int ragHitContentMaxBytes = 16 * 1024;

    public int getSmallMaxBytes() {
        return smallMaxBytes;
    }

    public void setSmallMaxBytes(int smallMaxBytes) {
        this.smallMaxBytes = smallMaxBytes;
    }

    public int getToolMaxBytes() {
        return toolMaxBytes;
    }

    public void setToolMaxBytes(int toolMaxBytes) {
        this.toolMaxBytes = toolMaxBytes;
    }

    public int getLargeMaxBytes() {
        return largeMaxBytes;
    }

    public void setLargeMaxBytes(int largeMaxBytes) {
        this.largeMaxBytes = largeMaxBytes;
    }

    public int getRagHitContentMaxBytes() {
        return ragHitContentMaxBytes;
    }

    public void setRagHitContentMaxBytes(int ragHitContentMaxBytes) {
        this.ragHitContentMaxBytes = ragHitContentMaxBytes;
    }

    void validate() {
        if (smallMaxBytes < 1 || toolMaxBytes < 1 || largeMaxBytes < 1 || ragHitContentMaxBytes < 1) {
            throw new IllegalArgumentException("Trace payload byte limits must be positive");
        }
    }
}
