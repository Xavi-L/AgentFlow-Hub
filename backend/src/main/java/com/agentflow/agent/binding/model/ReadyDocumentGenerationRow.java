package com.agentflow.agent.binding.model;

/** One READY document generation selected for a future task retrieval snapshot. */
public class ReadyDocumentGenerationRow {
    private Long knowledgeBaseId;
    private Long documentId;
    private Long vectorGeneration;
    private String chunkStrategyVersion;

    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getVectorGeneration() { return vectorGeneration; }
    public void setVectorGeneration(Long vectorGeneration) { this.vectorGeneration = vectorGeneration; }
    public String getChunkStrategyVersion() { return chunkStrategyVersion; }
    public void setChunkStrategyVersion(String chunkStrategyVersion) { this.chunkStrategyVersion = chunkStrategyVersion; }
}
