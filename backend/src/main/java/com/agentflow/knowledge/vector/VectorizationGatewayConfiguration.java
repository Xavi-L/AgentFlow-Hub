package com.agentflow.knowledge.vector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the application runnable before provider/Qdrant settings are supplied. A later
 * real configuration selects another mode and contributes concrete gateway beans; the
 * V5 service itself remains unchanged.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "agentflow.knowledge.vectorization",
        name = "mode",
        havingValue = "local",
        matchIfMissing = true
)
public class VectorizationGatewayConfiguration {

    @Bean
    @ConditionalOnMissingBean(EmbeddingGateway.class)
    public EmbeddingGateway developmentEmbeddingGateway() {
        return new DeterministicDevelopmentEmbeddingGateway();
    }

    @Bean
    @ConditionalOnMissingBean(VectorStoreGateway.class)
    public VectorStoreGateway developmentVectorStoreGateway() {
        return new InMemoryVectorStoreGateway();
    }
}
