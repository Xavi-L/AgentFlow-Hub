package com.agentflow.agent.task.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** Durable M4C root record for one immutable Agent execution request. */
@TableName("agent_task")
public class AgentTask {
    private Long id;
    private Long userId;
    private Long agentId;
    private String clientRequestId;
    private String requestFingerprint;
    private String status;
    private String phase;
    private String terminationReason;
    private String userInput;
    private String executionSnapshot;
    private Integer maxDecisionTurns;
    private Integer maxToolCalls;
    private Integer maxTotalTokens;
    private Integer reservedFinalTokens;
    private Integer decisionTurnsUsed;
    private Integer toolCallsUsed;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private String tokenUsageQuality;
    private String finalAnswer;
    private String citations;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime cancelRequestedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private Long lastEventSequence;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public void setTerminationReason(String terminationReason) {
        this.terminationReason = terminationReason;
    }

    public String getUserInput() {
        return userInput;
    }

    public void setUserInput(String userInput) {
        this.userInput = userInput;
    }

    public String getExecutionSnapshot() {
        return executionSnapshot;
    }

    public void setExecutionSnapshot(String executionSnapshot) {
        this.executionSnapshot = executionSnapshot;
    }

    public Integer getMaxDecisionTurns() {
        return maxDecisionTurns;
    }

    public void setMaxDecisionTurns(Integer maxDecisionTurns) {
        this.maxDecisionTurns = maxDecisionTurns;
    }

    public Integer getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(Integer maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public Integer getMaxTotalTokens() {
        return maxTotalTokens;
    }

    public void setMaxTotalTokens(Integer maxTotalTokens) {
        this.maxTotalTokens = maxTotalTokens;
    }

    public Integer getReservedFinalTokens() {
        return reservedFinalTokens;
    }

    public void setReservedFinalTokens(Integer reservedFinalTokens) {
        this.reservedFinalTokens = reservedFinalTokens;
    }

    public Integer getDecisionTurnsUsed() {
        return decisionTurnsUsed;
    }

    public void setDecisionTurnsUsed(Integer decisionTurnsUsed) {
        this.decisionTurnsUsed = decisionTurnsUsed;
    }

    public Integer getToolCallsUsed() {
        return toolCallsUsed;
    }

    public void setToolCallsUsed(Integer toolCallsUsed) {
        this.toolCallsUsed = toolCallsUsed;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getTokenUsageQuality() {
        return tokenUsageQuality;
    }

    public void setTokenUsageQuality(String tokenUsageQuality) {
        this.tokenUsageQuality = tokenUsageQuality;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public String getCitations() {
        return citations;
    }

    public void setCitations(String citations) {
        this.citations = citations;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public OffsetDateTime getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public void setCancelRequestedAt(OffsetDateTime cancelRequestedAt) {
        this.cancelRequestedAt = cancelRequestedAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public Long getLastEventSequence() {
        return lastEventSequence;
    }

    public void setLastEventSequence(Long lastEventSequence) {
        this.lastEventSequence = lastEventSequence;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
