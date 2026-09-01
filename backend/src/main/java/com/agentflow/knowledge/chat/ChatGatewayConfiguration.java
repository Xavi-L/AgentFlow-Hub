package com.agentflow.knowledge.chat;

import com.agentflow.config.OpenAiChatProperties;
import com.agentflow.infra.llm.LlmGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the only production V9 gateway while still allowing focused test adapters to replace it. */
@Configuration
@EnableConfigurationProperties(OpenAiChatProperties.class)
public class ChatGatewayConfiguration {

    @Bean
    @ConditionalOnMissingBean(ChatGateway.class)
    public ChatGateway openAiCompatibleChatGateway(
            OpenAiChatProperties properties,
            LlmGateway llmGateway
    ) {
        return new OpenAiCompatibleChatGateway(properties, llmGateway);
    }
}
