package com.agentflow.knowledge.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChatGatewayConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ChatGatewayConfiguration.class)
            .withPropertyValues(
                    "agentflow.llm.base-url=http://127.0.0.1:1234/v1",
                    "agentflow.llm.api-key=test-key",
                    "agentflow.llm.chat-model=local-qwen-test"
            );

    @Test
    void shouldRegisterTheOpenAiCompatibleGatewayFromServerOwnedConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChatGateway.class);
            assertThat(context).getBean(ChatGateway.class).isInstanceOf(OpenAiCompatibleChatGateway.class);
        });
    }

    @Test
    void shouldAllowAFocusedTestGatewayToReplaceTheProductionAdapter() {
        ChatGateway testGateway = request -> "test answer [S1]";

        contextRunner
                .withBean(ChatGateway.class, () -> testGateway)
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatGateway.class);
                    assertThat(context).getBean(ChatGateway.class).isSameAs(testGateway);
                });
    }
}
