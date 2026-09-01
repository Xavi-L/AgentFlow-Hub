package com.agentflow.knowledge.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.config.OpenAiChatProperties;
import com.agentflow.infra.llm.LlmChatRequest;
import com.agentflow.infra.llm.LlmChatResult;
import com.agentflow.infra.llm.LlmFailureType;
import com.agentflow.infra.llm.LlmGateway;
import com.agentflow.infra.llm.LlmGatewayException;
import com.agentflow.infra.llm.LlmMessageRole;
import com.agentflow.infra.llm.LlmTokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpenAiCompatibleChatGatewayTest {

    @Test
    void shouldAdaptTheFixedKnowledgePromptAndExactContextToTheGenericGateway() {
        OpenAiChatProperties properties = properties();
        LlmGateway llmGateway = mock(LlmGateway.class);
        when(llmGateway.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "先核对支付渠道错误码。[S1]",
                "resolved-model",
                "stop",
                LlmTokenUsage.known(100, 20, 120),
                "chatcmpl-1",
                8
        ));
        OpenAiCompatibleChatGateway gateway = new OpenAiCompatibleChatGateway(properties, llmGateway);
        String context = """
                [S1]
                Source: refund-rules.md
                Title: 支付 / 退款
                DocumentId: 301
                ChunkId: 401
                Content:
                先检查支付渠道错误码。""";

        String answer = gateway.generate(new ChatRequest("退款失败如何排查？", context, 256));

        assertThat(answer).isEqualTo("先核对支付渠道错误码。[S1]");
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmGateway).chat(requestCaptor.capture());
        LlmChatRequest request = requestCaptor.getValue();
        assertThat(request.modelProvider()).isEqualTo("openai-compatible");
        assertThat(request.modelName()).isEqualTo("local-qwen-test");
        assertThat(request.temperature()).isEqualByComparingTo("0.2");
        assertThat(request.topP()).isEqualByComparingTo("0.8");
        assertThat(request.maxOutputTokens()).isEqualTo(256);
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).role()).isEqualTo(LlmMessageRole.SYSTEM);
        assertThat(request.messages().get(0).content())
                .isEqualTo(OpenAiCompatibleChatGateway.SYSTEM_INSTRUCTION);
        assertThat(request.messages().get(1).role()).isEqualTo(LlmMessageRole.USER);
        assertThat(request.messages().get(1).content())
                .isEqualTo("Question:\n退款失败如何排查？\n\nContext:\n" + context);
    }

    @Test
    void shouldTranslateEveryGenericFailureToTheExistingV9GatewayFailure() {
        LlmGateway llmGateway = mock(LlmGateway.class);
        when(llmGateway.chat(any(LlmChatRequest.class))).thenThrow(new LlmGatewayException(
                LlmFailureType.PROVIDER_REJECTED,
                "sanitized generic failure"
        ));
        OpenAiCompatibleChatGateway gateway = new OpenAiCompatibleChatGateway(properties(), llmGateway);

        assertThatThrownBy(() -> gateway.generate(new ChatRequest(
                "退款失败如何排查？",
                "[S1]\nContent:\n先检查错误码。",
                256
        )))
                .isInstanceOf(ChatGatewayException.class)
                .hasMessage("OpenAI-compatible chat request failed")
                .hasNoCause();

        verify(llmGateway).chat(any(LlmChatRequest.class));
    }

    private static OpenAiChatProperties properties() {
        OpenAiChatProperties properties = new OpenAiChatProperties();
        properties.setBaseUrl("http://openai-compatible.test/v1");
        properties.setApiKey("test-openai-key");
        properties.setChatModel("local-qwen-test");
        return properties;
    }
}
