package com.agentflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.infra.llm.LlmChatRequest;
import com.agentflow.infra.llm.LlmGateway;
import com.agentflow.infra.llm.LlmMessage;
import com.agentflow.infra.llm.LlmMessageRole;
import com.agentflow.infra.llm.SpringAiOpenAiCompatibleLlmGateway;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SpringAiConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SpringAiConfig.class)
            .withPropertyValues(
                    "agentflow.llm.base-url=http://127.0.0.1:1234/v1/",
                    "agentflow.llm.api-key=",
                    "agentflow.llm.timeout=PT1S"
            );

    @Test
    void shouldManuallyRegisterOneChatModelAndOneGenericGatewayWithToolExecutionDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context).hasSingleBean(LlmGateway.class);
            assertThat(context.getBean(LlmGateway.class))
                    .isInstanceOf(SpringAiOpenAiCompatibleLlmGateway.class);
            assertThat(context.getBean(ChatModel.class).getDefaultOptions())
                    .isInstanceOfSatisfying(OpenAiChatOptions.class, options ->
                            assertThat(options.getInternalToolExecutionEnabled()).isFalse()
                    );
            assertThat(SpringAiConfig.UPSTREAM_MAX_ATTEMPTS).isEqualTo(1);
        });
    }

    @Test
    void shouldAllowFocusedTestsToReplaceBothInfrastructureBeans() {
        ChatModel testChatModel = mock(ChatModel.class);
        LlmGateway testGateway = request -> null;

        contextRunner
                .withBean(ChatModel.class, () -> testChatModel)
                .withBean(LlmGateway.class, () -> testGateway)
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context).hasSingleBean(LlmGateway.class);
                    assertThat(context.getBean(ChatModel.class)).isSameAs(testChatModel);
                    assertThat(context.getBean(LlmGateway.class)).isSameAs(testGateway);
                });
    }

    @Test
    void explicitTimeoutMustKeepUsingAProvidedChatModelOverride() {
        ChatModel testChatModel = mock(ChatModel.class);
        when(testChatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("controlled answer")))));
        contextRunner.withBean(ChatModel.class, () -> testChatModel).run(context -> {
            LlmChatRequest request = new LlmChatRequest("openai-compatible", "controlled-model",
                    List.of(new LlmMessage(LlmMessageRole.USER, "question")), BigDecimal.ZERO, BigDecimal.ONE,
                    512, null, null, null, 120);

            assertThat(context.getBean(LlmGateway.class).chat(request).content()).isEqualTo("controlled answer");
            verify(testChatModel).call(any(Prompt.class));
        });
    }

    @Test
    void shouldFailFastOnInvalidConnectionSettings() {
        contextRunner
                .withPropertyValues("agentflow.llm.timeout=PT0S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "agentflow.llm.timeout must be between 1ms and Integer.MAX_VALUE milliseconds"
                            );
                });

        contextRunner
                .withPropertyValues("agentflow.llm.base-url= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("agentflow.llm.base-url must not be blank");
                });

        contextRunner
                .withPropertyValues("agentflow.llm.base-url=not-a-url")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "agentflow.llm.base-url must be an absolute HTTP(S) URL without credentials, query, or fragment"
                    );
                });
    }
}
