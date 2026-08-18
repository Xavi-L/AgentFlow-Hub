package com.agentflow.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RemoteVectorizationGatewayConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RemoteVectorizationGatewayConfiguration.class)
            .withPropertyValues(
                    "agentflow.knowledge.vectorization.mode=remote",
                    "agentflow.knowledge.vectorization.embedding.dashscope.api-key=test-key",
                    "agentflow.knowledge.vectorization.embedding.dashscope.dimensions=1024",
                    "agentflow.qdrant.base-url=http://127.0.0.1:6333",
                    "agentflow.qdrant.vector-size=1024"
            );

    @Test
    void shouldRegisterOnlyRealGatewayAdaptersInRemoteMode() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EmbeddingGateway.class);
            assertThat(context).hasSingleBean(VectorStoreGateway.class);
            assertThat(context).getBean(EmbeddingGateway.class).isInstanceOf(DashScopeEmbeddingGateway.class);
            assertThat(context).getBean(VectorStoreGateway.class).isInstanceOf(QdrantVectorStoreGateway.class);
        });
    }

    @Test
    void shouldRejectAConfigurationWhereEmbeddingAndCollectionDimensionsDiffer() {
        contextRunner
                .withPropertyValues("agentflow.qdrant.vector-size=768")
                .run(context -> assertThat(context).hasFailed());
    }
}
