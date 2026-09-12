package com.agentflow.agent.configversion;

import java.time.OffsetDateTime;

/** Database row; application writes are INSERT only. */
public class AgentConfigVersion {
    private Long id;
    private Long userId;
    private Long agentId;
    private String schemaVersion;
    private String hashAlgorithmVersion;
    private String configHash;
    private String configJson;
    private OffsetDateTime createdAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long value) { agentId = value; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String value) { schemaVersion = value; }
    public String getHashAlgorithmVersion() { return hashAlgorithmVersion; }
    public void setHashAlgorithmVersion(String value) { hashAlgorithmVersion = value; }
    public String getConfigHash() { return configHash; }
    public void setConfigHash(String value) { configHash = value; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String value) { configJson = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
}
