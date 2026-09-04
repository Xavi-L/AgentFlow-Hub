package com.agentflow.agent.trace.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Mutable persistence row for {@code rag_retrieval_log}. */
public class RagRetrievalLogRecord {
    private Long id;
    private Long taskId;
    private Long stepId;
    private String query;
    private String embeddingProfileCode;
    private String corpusSnapshotJson;
    private Integer topK;
    private BigDecimal similarityThreshold;
    private Integer candidateCount;
    private Integer validHitCount;
    private Integer staleHitCount;
    private Long latencyMs;
    private String status;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getStepId() { return stepId; }
    public void setStepId(Long stepId) { this.stepId = stepId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getEmbeddingProfileCode() { return embeddingProfileCode; }
    public void setEmbeddingProfileCode(String embeddingProfileCode) { this.embeddingProfileCode = embeddingProfileCode; }
    public String getCorpusSnapshotJson() { return corpusSnapshotJson; }
    public void setCorpusSnapshotJson(String corpusSnapshotJson) { this.corpusSnapshotJson = corpusSnapshotJson; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public BigDecimal getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(BigDecimal similarityThreshold) { this.similarityThreshold = similarityThreshold; }
    public Integer getCandidateCount() { return candidateCount; }
    public void setCandidateCount(Integer candidateCount) { this.candidateCount = candidateCount; }
    public Integer getValidHitCount() { return validHitCount; }
    public void setValidHitCount(Integer validHitCount) { this.validHitCount = validHitCount; }
    public Integer getStaleHitCount() { return staleHitCount; }
    public void setStaleHitCount(Integer staleHitCount) { this.staleHitCount = staleHitCount; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
