package com.agentflow.agent.trace.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Mutable persistence row for {@code rag_retrieval_hit}. */
public class RagRetrievalHitLogRecord {
    private Long id;
    private Long retrievalId;
    private Integer rankNo;
    private String citationId;
    private Long chunkIdSnapshot;
    private Long documentIdSnapshot;
    private Long knowledgeBaseIdSnapshot;
    private Long vectorGeneration;
    private BigDecimal score;
    private String contentSnapshot;
    private String metadataSnapshotJson;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRetrievalId() { return retrievalId; }
    public void setRetrievalId(Long retrievalId) { this.retrievalId = retrievalId; }
    public Integer getRankNo() { return rankNo; }
    public void setRankNo(Integer rankNo) { this.rankNo = rankNo; }
    public String getCitationId() { return citationId; }
    public void setCitationId(String citationId) { this.citationId = citationId; }
    public Long getChunkIdSnapshot() { return chunkIdSnapshot; }
    public void setChunkIdSnapshot(Long chunkIdSnapshot) { this.chunkIdSnapshot = chunkIdSnapshot; }
    public Long getDocumentIdSnapshot() { return documentIdSnapshot; }
    public void setDocumentIdSnapshot(Long documentIdSnapshot) { this.documentIdSnapshot = documentIdSnapshot; }
    public Long getKnowledgeBaseIdSnapshot() { return knowledgeBaseIdSnapshot; }
    public void setKnowledgeBaseIdSnapshot(Long knowledgeBaseIdSnapshot) { this.knowledgeBaseIdSnapshot = knowledgeBaseIdSnapshot; }
    public Long getVectorGeneration() { return vectorGeneration; }
    public void setVectorGeneration(Long vectorGeneration) { this.vectorGeneration = vectorGeneration; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getContentSnapshot() { return contentSnapshot; }
    public void setContentSnapshot(String contentSnapshot) { this.contentSnapshot = contentSnapshot; }
    public String getMetadataSnapshotJson() { return metadataSnapshotJson; }
    public void setMetadataSnapshotJson(String metadataSnapshotJson) { this.metadataSnapshotJson = metadataSnapshotJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
