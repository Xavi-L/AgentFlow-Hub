package com.agentflow.knowledge.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-owned OpenAI-compatible chat settings. The three connection values reuse the
 * existing OPENAI_BASE_URL, OPENAI_API_KEY, and OPENAI_CHAT_MODEL development configuration.
 */
@ConfigurationProperties(prefix = "agentflow.llm")
public class OpenAiChatProperties {
    private String baseUrl = "http://127.0.0.1:1234/v1";
    private String apiKey = "";
    private String chatModel = "qwen2.5-7b-instruct";
    private Duration timeout = Duration.ofSeconds(30);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
