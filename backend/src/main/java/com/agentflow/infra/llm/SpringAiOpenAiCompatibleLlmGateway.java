package com.agentflow.infra.llm;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/** OpenAI-compatible Spring AI implementation of the V35 synchronous chat boundary. */
public final class SpringAiOpenAiCompatibleLlmGateway implements LlmGateway {
    public static final String SUPPORTED_PROVIDER = "openai-compatible";

    private static final BigDecimal MIN_TEMPERATURE = BigDecimal.ZERO;
    private static final BigDecimal MAX_TEMPERATURE = new BigDecimal("2");
    private static final BigDecimal MIN_TOP_P = BigDecimal.ZERO;
    private static final BigDecimal MAX_TOP_P = BigDecimal.ONE;

    private final ChatModel chatModel;
    private final LongSupplier nanoTime;

    public SpringAiOpenAiCompatibleLlmGateway(ChatModel chatModel) {
        this(chatModel, System::nanoTime);
    }

    SpringAiOpenAiCompatibleLlmGateway(ChatModel chatModel, LongSupplier nanoTime) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    }

    @Override
    public LlmChatResult chat(LlmChatRequest request) {
        validate(request);
        Prompt prompt = new Prompt(toSpringAiMessages(request.messages()), requestOptions(request));
        long startedAt = nanoTime.getAsLong();

        try {
            ChatResponse response = chatModel.call(prompt);
            long latencyMs = elapsedMillis(startedAt, nanoTime.getAsLong());
            return toResult(response, latencyMs);
        } catch (LlmGatewayException failure) {
            throw failure;
        } catch (NonTransientAiException | TransientAiException providerFailure) {
            throw failure(LlmFailureType.PROVIDER_REJECTED, "LLM provider rejected the request");
        } catch (ResourceAccessException transportFailure) {
            if (hasTimeoutCause(transportFailure)) {
                throw failure(LlmFailureType.TIMEOUT, "LLM provider request timed out");
            }
            throw failure(LlmFailureType.TRANSPORT, "LLM provider could not be reached");
        } catch (RestClientException malformedResponse) {
            if (hasTimeoutCause(malformedResponse)) {
                throw failure(LlmFailureType.TIMEOUT, "LLM provider request timed out");
            }
            if (hasTransportCause(malformedResponse)) {
                throw failure(LlmFailureType.TRANSPORT, "LLM provider could not be reached");
            }
            throw failure(LlmFailureType.MALFORMED_RESPONSE, "LLM provider returned a malformed response");
        } catch (RuntimeException malformedResponse) {
            if (hasTimeoutCause(malformedResponse)) {
                throw failure(LlmFailureType.TIMEOUT, "LLM provider request timed out");
            }
            if (hasTransportCause(malformedResponse)) {
                throw failure(LlmFailureType.TRANSPORT, "LLM provider could not be reached");
            }
            throw failure(LlmFailureType.MALFORMED_RESPONSE, "LLM provider returned a malformed response");
        }
    }

    private static void validate(LlmChatRequest request) {
        if (request == null) {
            throw configurationFailure("LLM chat request must not be null");
        }
        if (!SUPPORTED_PROVIDER.equals(request.modelProvider())) {
            throw configurationFailure("modelProvider must be openai-compatible");
        }
        if (request.modelName() == null || request.modelName().isBlank()) {
            throw configurationFailure("modelName must not be blank");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw configurationFailure("messages must not be empty");
        }
        for (LlmMessage message : request.messages()) {
            if (message == null) {
                throw configurationFailure("messages must not contain null entries");
            }
            if (message.role() == null) {
                throw configurationFailure("message role must not be null");
            }
            if (message.content() == null || message.content().isBlank()) {
                throw configurationFailure("message content must not be blank");
            }
        }
        if (request.temperature() == null
                || request.temperature().compareTo(MIN_TEMPERATURE) < 0
                || request.temperature().compareTo(MAX_TEMPERATURE) > 0) {
            throw configurationFailure("temperature must be between 0 and 2");
        }
        if (request.topP() == null
                || request.topP().compareTo(MIN_TOP_P) <= 0
                || request.topP().compareTo(MAX_TOP_P) > 0) {
            throw configurationFailure("topP must be greater than 0 and at most 1");
        }
        if (request.maxOutputTokens() < 1) {
            throw configurationFailure("maxOutputTokens must be positive");
        }
    }

    private static OpenAiChatOptions requestOptions(LlmChatRequest request) {
        return OpenAiChatOptions.builder()
                .model(request.modelName())
                .temperature(request.temperature().doubleValue())
                .topP(request.topP().doubleValue())
                .maxTokens(request.maxOutputTokens())
                .N(1)
                .internalToolExecutionEnabled(false)
                .build();
    }

    private static List<Message> toSpringAiMessages(List<LlmMessage> messages) {
        return messages.stream().map(SpringAiOpenAiCompatibleLlmGateway::toSpringAiMessage).toList();
    }

    private static Message toSpringAiMessage(LlmMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
        };
    }

    private static LlmChatResult toResult(ChatResponse response, long latencyMs) {
        if (response == null || response.getResults() == null || response.getResults().size() != 1) {
            throw failure(
                    LlmFailureType.MALFORMED_RESPONSE,
                    "LLM provider response must contain exactly one choice"
            );
        }

        Generation generation = response.getResult();
        String content = generation == null || generation.getOutput() == null
                ? null
                : generation.getOutput().getText();
        if (content == null || content.isBlank()) {
            throw failure(
                    LlmFailureType.MALFORMED_RESPONSE,
                    "LLM provider response must contain non-blank content"
            );
        }

        ChatResponseMetadata metadata = response.getMetadata();
        String resolvedModel = metadata == null ? null : nullIfBlank(metadata.getModel());
        String providerRequestId = metadata == null ? null : nullIfBlank(metadata.getId());
        String finishReason = generation.getMetadata() == null
                ? null
                : nullIfBlank(generation.getMetadata().getFinishReason());
        if (finishReason != null) {
            finishReason = finishReason.toLowerCase(Locale.ROOT);
        }

        return new LlmChatResult(
                content,
                resolvedModel,
                finishReason,
                tokenUsage(metadata),
                providerRequestId,
                latencyMs
        );
    }

    private static LlmTokenUsage tokenUsage(ChatResponseMetadata metadata) {
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage == null || usage instanceof EmptyUsage || usage.getNativeUsage() == null) {
            return LlmTokenUsage.unknown();
        }

        Integer inputTokens = usage.getPromptTokens();
        Integer outputTokens = usage.getCompletionTokens();
        Integer totalTokens = usage.getTotalTokens();
        if (inputTokens == null || outputTokens == null || totalTokens == null) {
            return LlmTokenUsage.unknown();
        }
        return LlmTokenUsage.known(inputTokens, outputTokens, totalTokens);
    }

    private static boolean hasTimeoutCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasTransportCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof UnknownHostException
                    || current instanceof SocketException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static long elapsedMillis(long startedAt, long completedAt) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, completedAt - startedAt));
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static LlmGatewayException configurationFailure(String message) {
        return failure(LlmFailureType.CONFIGURATION, message);
    }

    private static LlmGatewayException failure(LlmFailureType type, String message) {
        return new LlmGatewayException(type, message);
    }
}
