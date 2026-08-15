package com.agentflow.knowledge.vector;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文：在未配置真实 embedding provider 时，给 V5 同步验收使用的确定性开发适配器。它只保证
 * 调用链、幂等写入和状态机可验证，绝不代表语义 embedding，不能用于检索质量评估。
 *
 * <p>English: Deterministic development adapter used while no real embedding provider
 * is configured. It validates the V5 call chain, idempotent writes, and state machine;
 * it is not a semantic embedding and must not be used for retrieval-quality evaluation.
 */
public final class DeterministicDevelopmentEmbeddingGateway implements EmbeddingGateway {
    private static final int DIMENSIONS = 16;

    @Override
    public EmbeddingVector embed(EmbeddingRequest request) {
        String contentHash = ChunkVectorIdentityFactory.contentHash(request.content());
        List<Float> values = new ArrayList<>(DIMENSIONS);
        for (int dimension = 0; dimension < DIMENSIONS; dimension++) {
            int offset = dimension * 4;
            int unsignedValue = Integer.parseInt(contentHash.substring(offset, offset + 4), 16);
            values.add((unsignedValue / 65535.0f) * 2.0f - 1.0f);
        }
        return new EmbeddingVector(values);
    }
}
