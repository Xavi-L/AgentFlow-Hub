package com.agentflow.knowledge.vector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 中文：V6 的显式 remote mode。它把真实 DashScope 和 Qdrant HTTP adapter 注入既有 Gateway
 * 边界；V5 的业务编排、状态机和稳定 identity 不依赖具体 HTTP/SDK 实现。
 *
 * <p>English: Explicit V6 remote mode. It injects real DashScope and Qdrant HTTP
 * adapters behind existing Gateway boundaries, while V5 orchestration, state transitions,
 * and stable identity stay independent of HTTP/SDK details.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "agentflow.knowledge.vectorization",
        name = "mode",
        havingValue = "remote"
)
@EnableConfigurationProperties({DashScopeEmbeddingProperties.class, QdrantProperties.class})
public class RemoteVectorizationGatewayConfiguration {

    @Bean
    @ConditionalOnMissingBean(EmbeddingGateway.class)
    public EmbeddingGateway dashScopeEmbeddingGateway(DashScopeEmbeddingProperties properties) {
        return new DashScopeEmbeddingGateway(
                properties,
                VectorizationRestClientFactory.create(properties.getBaseUrl(), properties.getTimeout())
        );
    }

    @Bean
    @ConditionalOnMissingBean(VectorStoreGateway.class)
    public VectorStoreGateway qdrantVectorStoreGateway(
            QdrantProperties properties,
            DashScopeEmbeddingProperties embeddingProperties
    ) {
        if (properties.getVectorSize() != embeddingProperties.getDimensions()) {
            throw new IllegalStateException(
                    "Qdrant vectorSize must equal configured DashScope embedding dimensions"
            );
        }
        return new QdrantVectorStoreGateway(
                properties,
                VectorizationRestClientFactory.create(properties.getBaseUrl(), properties.getTimeout())
        );
    }
}
