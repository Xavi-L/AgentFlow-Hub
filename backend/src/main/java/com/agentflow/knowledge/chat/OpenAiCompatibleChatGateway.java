package com.agentflow.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * One non-streaming OpenAI-compatible {@code /chat/completions} adapter. It owns one fixed
 * internal instruction, while V9 service retains citation validation and source provenance.
 */
public final class OpenAiCompatibleChatGateway implements ChatGateway {
    static final String SYSTEM_INSTRUCTION = """
            Answer only from the supplied knowledge-base context.
            Every answer must include at least one citation marker exactly in the form [S#].
            Use only citation markers already present in the supplied context, and never invent a source.
            """;

    private final OpenAiChatProperties properties;
    private final RestClient restClient;

    OpenAiCompatibleChatGateway(OpenAiChatProperties properties, RestClient restClient) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    }

    @Override
    public String generate(ChatRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String model = requireNonBlank(properties.getChatModel(), "OPENAI_CHAT_MODEL");
        String apiKey = properties.getApiKey();

        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .headers(headers -> {
                        if (apiKey != null && !apiKey.isBlank()) {
                            headers.setBearerAuth(apiKey.trim());
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(model, request))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException requestFailure) {
            throw new ChatGatewayException("OpenAI-compatible chat request failed", requestFailure);
        }

        return extractAnswer(response);
    }

    private static Map<String, Object> requestBody(String model, ChatRequest request) {
        Map<String, Object> systemMessage = new LinkedHashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", SYSTEM_INSTRUCTION);

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", "Question:\n" + request.query() + "\n\nContext:\n" + request.context());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(systemMessage, userMessage));
        body.put("max_tokens", request.maxAnswerTokens());
        body.put("stream", false);
        return body;
    }

    private static String extractAnswer(JsonNode response) {
        JsonNode content = response == null
                ? null
                : response.path("choices").path(0).path("message").path("content");
        if (content == null || !content.isTextual() || content.textValue().isBlank()) {
            throw new ChatGatewayException(
                    "OpenAI-compatible response must contain a non-blank choices[0].message.content"
            );
        }
        return content.textValue();
    }

    private static String requireNonBlank(String value, String settingName) {
        if (value == null || value.isBlank()) {
            throw new ChatGatewayException(settingName + " is not configured");
        }
        return value;
    }
}
