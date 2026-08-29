package com.agentflow.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class VectorizationGatewayConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(VectorizationGatewayConfiguration.class);

    @Test
    void shouldRegisterLocalGatewayAdaptersByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EmbeddingGateway.class);
            assertThat(context).hasSingleBean(VectorStoreGateway.class);
            assertThat(context).getBean(EmbeddingGateway.class).isInstanceOf(DeterministicDevelopmentEmbeddingGateway.class);
            assertThat(context).getBean(VectorStoreGateway.class).isInstanceOf(InMemoryVectorStoreGateway.class);
        });
    }
}
