package com.agentflow.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class SpringAiOpenAiCompatibleLlmGatewayTest {

    @Test
    void shouldUseRequestLevelOptionsPreserveMessageOrderAndReturnMetadata() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "  exact provider answer  ",
                "resolved-model",
                "provider-request-7",
                "STOP",
                new DefaultUsage(11, 7, 18, new Object())
        ));
        long[] times = {1_000_000L, 6_000_000L};
        AtomicInteger timeIndex = new AtomicInteger();
        LongSupplier nanoTime = () -> times[timeIndex.getAndIncrement()];
        SpringAiOpenAiCompatibleLlmGateway gateway =
                new SpringAiOpenAiCompatibleLlmGateway(chatModel, nanoTime);

        LlmChatResult result = gateway.chat(validRequest());

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        assertThat(prompt.getInstructions()).hasSize(3);
        assertThat(prompt.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(prompt.getInstructions().get(0).getText()).isEqualTo("system\nline 2");
        assertThat(prompt.getInstructions().get(1)).isInstanceOf(UserMessage.class);
        assertThat(prompt.getInstructions().get(1).getText()).isEqualTo("  user body  ");
        assertThat(prompt.getInstructions().get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(prompt.getInstructions().get(2).getText()).isEqualTo("assistant history");

        assertThat(prompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getModel()).isEqualTo("request-model");
        assertThat(options.getTemperature()).isEqualTo(0.2);
        assertThat(options.getTopP()).isEqualTo(0.8);
        assertThat(options.getMaxTokens()).isEqualTo(321);
        assertThat(options.getN()).isEqualTo(1);
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
        assertThat(options.getTools()).isNull();
        assertThat(options.getToolCallbacks()).isEmpty();

        assertThat(result.content()).isEqualTo("  exact provider answer  ");
        assertThat(result.resolvedModel()).isEqualTo("resolved-model");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.usage()).isEqualTo(LlmTokenUsage.known(11, 7, 18));
        assertThat(result.providerRequestId()).isEqualTo("provider-request-7");
        assertThat(result.latencyMs()).isEqualTo(5);
    }

    @Test
    void shouldKeepMissingUsageAndMissingOptionalMetadataUnknown() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("answer"))),
                ChatResponseMetadata.builder().build()
        );
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        SpringAiOpenAiCompatibleLlmGateway gateway = new SpringAiOpenAiCompatibleLlmGateway(chatModel);

        LlmChatResult result = gateway.chat(validRequest());

        assertThat(result.usage().known()).isFalse();
        assertThat(result.usage().inputTokens()).isNull();
        assertThat(result.usage().outputTokens()).isNull();
        assertThat(result.usage().totalTokens()).isNull();
        assertThat(result.resolvedModel()).isNull();
        assertThat(result.finishReason()).isNull();
        assertThat(result.providerRequestId()).isNull();
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldRejectEveryInvalidRequestBeforeCallingSpringAi() {
        ChatModel chatModel = mock(ChatModel.class);
        SpringAiOpenAiCompatibleLlmGateway gateway = new SpringAiOpenAiCompatibleLlmGateway(chatModel);
        List<LlmChatRequest> invalidRequests = new ArrayList<>();
        invalidRequests.add(null);
        invalidRequests.add(request(null, "model", messages(), "0.2", "0.8", 100));
        invalidRequests.add(request("other-provider", "model", messages(), "0.2", "0.8", 100));
        invalidRequests.add(request("openai-compatible", null, messages(), "0.2", "0.8", 100));
        invalidRequests.add(request("openai-compatible", " ", messages(), "0.2", "0.8", 100));
        invalidRequests.add(request("openai-compatible", "model", null, "0.2", "0.8", 100));
        invalidRequests.add(request("openai-compatible", "model", List.of(), "0.2", "0.8", 100));
        invalidRequests.add(request(
                "openai-compatible",
                "model",
                Collections.singletonList(null),
                "0.2",
                "0.8",
                100
        ));
        invalidRequests.add(request(
                "openai-compatible",
                "model",
                List.of(new LlmMessage(null, "content")),
                "0.2",
                "0.8",
                100
        ));
        invalidRequests.add(request(
                "openai-compatible",
                "model",
                List.of(new LlmMessage(LlmMessageRole.USER, " ")),
                "0.2",
                "0.8",
                100
        ));
        invalidRequests.add(request("openai-compatible", "model", messages(), null, "0.8", 100));
        invalidRequests.add(request("openai-compatible", "model", messages(), "-0.001", "0.8", 100));
        invalidRequests.add(request("openai-compatible", "model", messages(), "2.001", "0.8", 100));
        invalidRequests.add(request("openai-compatible", "model", messages(), "0.2", null, 100));
        invalidRequests.add(request("openai-compatible", "model", messages(), "0.2", "0", 100));
        invalidRequests.add(request("openai-compatible", "model", messages(), "0.2", "1.001", 100));
        invalidRequests.add(request("openai-compatible", "model", messages(), "0.2", "0.8", 0));

        for (LlmChatRequest invalidRequest : invalidRequests) {
            assertThatThrownBy(() -> gateway.chat(invalidRequest))
                    .isInstanceOfSatisfying(LlmGatewayException.class, failure -> {
                        assertThat(failure.failureType()).isEqualTo(LlmFailureType.CONFIGURATION);
                        assertThat(failure).hasNoCause();
                    });
        }
        verifyNoInteractions(chatModel);
    }

    @Test
    void shouldClassifyAndSanitizeProviderTransportTimeoutAndParseFailures() {
        ChatModel chatModel = mock(ChatModel.class);
        SpringAiOpenAiCompatibleLlmGateway gateway = new SpringAiOpenAiCompatibleLlmGateway(chatModel);
        String secret = "secret-key provider-body http://internal-provider.test/v1";

        when(chatModel.call(any(Prompt.class))).thenThrow(new NonTransientAiException(secret));
        assertFailure(gateway, LlmFailureType.PROVIDER_REJECTED, secret);
        verify(chatModel).call(any(Prompt.class));

        reset(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new TransientAiException(secret));
        assertFailure(gateway, LlmFailureType.PROVIDER_REJECTED, secret);
        verify(chatModel).call(any(Prompt.class));

        reset(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new ResourceAccessException(
                secret,
                new SocketTimeoutException(secret)
        ));
        assertFailure(gateway, LlmFailureType.TIMEOUT, secret);
        verify(chatModel).call(any(Prompt.class));

        reset(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new ResourceAccessException(
                secret,
                new ConnectException(secret)
        ));
        assertFailure(gateway, LlmFailureType.TRANSPORT, secret);
        verify(chatModel).call(any(Prompt.class));

        reset(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RestClientException(secret));
        assertFailure(gateway, LlmFailureType.MALFORMED_RESPONSE, secret);
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void shouldRejectNullMultipleOrEmptyChoicesAsMalformedResponses() {
        ChatModel chatModel = mock(ChatModel.class);
        SpringAiOpenAiCompatibleLlmGateway gateway = new SpringAiOpenAiCompatibleLlmGateway(chatModel);

        when(chatModel.call(any(Prompt.class))).thenReturn(null);
        assertFailure(gateway, LlmFailureType.MALFORMED_RESPONSE, null);

        reset(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        assertFailure(gateway, LlmFailureType.MALFORMED_RESPONSE, null);

        reset(chatModel);
        Generation generation = new Generation(new AssistantMessage("answer"));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(generation, generation)));
        assertFailure(gateway, LlmFailureType.MALFORMED_RESPONSE, null);

        reset(chatModel);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(" ")))));
        assertFailure(gateway, LlmFailureType.MALFORMED_RESPONSE, null);
    }

    @Test
    void shouldDefensivelyCopyMessagesAndKeepUnknownDistinctFromZeroUsage() {
        List<LlmMessage> mutableMessages = new ArrayList<>(messages());
        LlmChatRequest request = request(
                "openai-compatible",
                "model",
                mutableMessages,
                "0.2",
                "0.8",
                100
        );
        mutableMessages.clear();

        assertThat(request.messages()).hasSize(3);
        assertThatThrownBy(() -> request.messages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(LlmTokenUsage.unknown()).isNotEqualTo(LlmTokenUsage.known(0, 0, 0));
        assertThat(LlmTokenUsage.unknown().known()).isFalse();
        assertThat(LlmTokenUsage.known(0, 0, 0).known()).isTrue();
        assertThatThrownBy(() -> new LlmTokenUsage(1, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertFailure(
            SpringAiOpenAiCompatibleLlmGateway gateway,
            LlmFailureType expectedType,
            String sensitiveText
    ) {
        assertThatThrownBy(() -> gateway.chat(validRequest()))
                .isInstanceOfSatisfying(LlmGatewayException.class, failure -> {
                    assertThat(failure.failureType()).isEqualTo(expectedType);
                    assertThat(failure).hasNoCause();
                    if (sensitiveText != null) {
                        assertThat(failure.getMessage()).doesNotContain(sensitiveText);
                    }
                });
    }

    private static ChatResponse response(
            String content,
            String model,
            String id,
            String finishReason,
            DefaultUsage usage
    ) {
        Generation generation = new Generation(
                new AssistantMessage(content),
                ChatGenerationMetadata.builder().finishReason(finishReason).build()
        );
        return new ChatResponse(
                List.of(generation),
                ChatResponseMetadata.builder()
                        .model(model)
                        .id(id)
                        .usage(usage)
                        .build()
        );
    }

    private static LlmChatRequest validRequest() {
        return request("openai-compatible", "request-model", messages(), "0.2", "0.8", 321);
    }

    private static List<LlmMessage> messages() {
        return List.of(
                new LlmMessage(LlmMessageRole.SYSTEM, "system\nline 2"),
                new LlmMessage(LlmMessageRole.USER, "  user body  "),
                new LlmMessage(LlmMessageRole.ASSISTANT, "assistant history")
        );
    }

    private static LlmChatRequest request(
            String provider,
            String model,
            List<LlmMessage> messages,
            String temperature,
            String topP,
            int maxOutputTokens
    ) {
        return new LlmChatRequest(
                provider,
                model,
                messages,
                temperature == null ? null : new BigDecimal(temperature),
                topP == null ? null : new BigDecimal(topP),
                maxOutputTokens
        );
    }
}
