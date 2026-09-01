package com.agentflow.config;

import com.agentflow.infra.llm.LlmGateway;
import com.agentflow.infra.llm.SpringAiOpenAiCompatibleLlmGateway;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.ApiKey;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** Manual Spring AI wiring: one synchronous compatible client, no starter auto-configuration. */
@Configuration
@EnableConfigurationProperties(OpenAiChatProperties.class)
public class SpringAiConfig {
    static final int UPSTREAM_MAX_ATTEMPTS = 1;

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel openAiCompatibleChatModel(OpenAiChatProperties properties) {
        Duration timeout = requireValidTimeout(properties.getTimeout());
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(normalizeBaseUrl(properties.getBaseUrl()))
                .apiKey(apiKey(properties.getApiKey()))
                .completionsPath("/chat/completions")
                .embeddingsPath("/embeddings")
                .restClientBuilder(restClientBuilder(timeout))
                .webClientBuilder(WebClient.builder())
                .responseErrorHandler(sanitizedResponseErrorHandler())
                .build();

        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(defaultOptions)
                .toolCallingManager(ToolCallingManager.builder().build())
                .retryTemplate(RetryTemplate.builder()
                        .maxAttempts(UPSTREAM_MAX_ATTEMPTS)
                        .noBackoff()
                        .build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(LlmGateway.class)
    public LlmGateway llmGateway(ChatModel chatModel) {
        return new SpringAiOpenAiCompatibleLlmGateway(chatModel);
    }

    private static RestClient.Builder restClientBuilder(Duration timeout) {
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        return RestClient.builder().requestFactory(requestFactory);
    }

    private static ResponseErrorHandler sanitizedResponseErrorHandler() {
        return new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return response.getStatusCode().isError();
            }

            @Override
            @SuppressWarnings("removal")
            public void handleError(ClientHttpResponse response) throws IOException {
                if (response.getStatusCode().is4xxClientError()) {
                    throw new NonTransientAiException("LLM provider returned a client error");
                }
                throw new TransientAiException("LLM provider returned a server error");
            }
        };
    }

    private static ApiKey apiKey(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            return new NoopApiKey();
        }
        return new SimpleApiKey(configuredKey.trim());
    }

    private static String normalizeBaseUrl(String configuredBaseUrl) {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            throw new IllegalArgumentException("agentflow.llm.base-url must not be blank");
        }
        String normalized = configuredBaseUrl.trim().replaceAll("/+$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("agentflow.llm.base-url must not be blank");
        }
        try {
            URI uri = new URI(normalized);
            boolean supportedScheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            if (!supportedScheme
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "agentflow.llm.base-url must be an absolute HTTP(S) URL without credentials, query, or fragment"
                );
            }
        } catch (URISyntaxException invalidUrl) {
            throw new IllegalArgumentException(
                    "agentflow.llm.base-url must be an absolute HTTP(S) URL without credentials, query, or fragment"
            );
        }
        return normalized;
    }

    private static Duration requireValidTimeout(Duration timeout) {
        if (timeout == null
                || timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(
                    "agentflow.llm.timeout must be between 1ms and Integer.MAX_VALUE milliseconds"
            );
        }
        return timeout;
    }
}
