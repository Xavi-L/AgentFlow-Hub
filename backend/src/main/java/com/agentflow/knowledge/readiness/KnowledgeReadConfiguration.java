package com.agentflow.knowledge.readiness;

/** Resolves persisted configuration against the same fixed contract used by Agent snapshots. */
public final class KnowledgeReadConfiguration {
    public static final String EMBEDDING_PROFILE_CODE = "dashscope-te-v4-1024-cosine";
    public static final String CHUNK_STRATEGY_VERSION = "structured-token-v1";

    private KnowledgeReadConfiguration() {
    }

    /** Unknown and legacy provider/model combinations have no supported profile. */
    public static String embeddingProfileCode(String provider, String model) {
        return "dashscope".equals(provider) && "text-embedding-v4".equals(model)
                ? EMBEDDING_PROFILE_CODE
                : null;
    }

    /** Resolves the configured strategy; actual document chunk versions are checked separately. */
    public static String chunkStrategyVersion(Integer size, Integer overlap) {
        return Integer.valueOf(800).equals(size) && Integer.valueOf(120).equals(overlap)
                ? CHUNK_STRATEGY_VERSION
                : null;
    }
}
