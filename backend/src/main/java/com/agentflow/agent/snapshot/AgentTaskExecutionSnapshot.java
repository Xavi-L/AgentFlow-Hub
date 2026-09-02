package com.agentflow.agent.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Immutable V0.1 execution dependency snapshot resolved before a future task dispatch. */
public record AgentTaskExecutionSnapshot(
        String snapshotVersion,
        AgentSnapshot agent,
        RuntimeSnapshot runtime,
        ChatModelSnapshot chatModel,
        RetrievalSnapshot retrieval,
        List<ToolSnapshot> tools
) {
    public AgentTaskExecutionSnapshot {
        requireText(snapshotVersion, "snapshotVersion");
        Objects.requireNonNull(agent, "agent must not be null");
        Objects.requireNonNull(runtime, "runtime must not be null");
        Objects.requireNonNull(chatModel, "chatModel must not be null");
        Objects.requireNonNull(retrieval, "retrieval must not be null");
        tools = List.copyOf(tools);
    }

    public record AgentSnapshot(
            String agentId,
            String systemPrompt,
            String status,
            int maxDecisionTurns,
            int maxToolCalls,
            int maxTotalTokens,
            int timeoutSeconds
    ) {
    }

    public record RuntimeSnapshot(
            String decisionProtocolVersion,
            String promptRulesVersion,
            String applicationRevision
    ) {
    }

    public record ChatModelSnapshot(
            String profileCode,
            String provider,
            String model,
            BigDecimal temperature,
            BigDecimal topP,
            int contextWindow,
            boolean supportsUsage
    ) {
    }

    public record RetrievalSnapshot(
            List<KnowledgeBaseSnapshot> knowledgeBases,
            int topK,
            BigDecimal similarityThreshold,
            boolean useRerank
    ) {
        public RetrievalSnapshot {
            knowledgeBases = List.copyOf(knowledgeBases);
        }
    }

    public record KnowledgeBaseSnapshot(
            String knowledgeBaseId,
            String embeddingProfileCode,
            String chunkStrategyVersion,
            List<DocumentGenerationSnapshot> documents
    ) {
        public KnowledgeBaseSnapshot {
            documents = List.copyOf(documents);
        }
    }

    public record DocumentGenerationSnapshot(String documentId, long vectorGeneration) {
    }

    public record ToolSnapshot(
            String toolId,
            String toolCode,
            String name,
            String description,
            JsonNode inputSchema,
            String inputSchemaHash,
            String implementationVersion,
            int timeoutMs
    ) {
        public ToolSnapshot {
            if (inputSchema == null || !inputSchema.isObject()) {
                throw new IllegalArgumentException("inputSchema must be an object");
            }
            inputSchema = inputSchema.deepCopy();
        }

        @Override
        public JsonNode inputSchema() {
            return inputSchema.deepCopy();
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
