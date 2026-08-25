package com.agentflow.knowledge.chat;

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
    public ChatGateway openAiCompatibleChatGateway(OpenAiChatProperties properties) {
        return new OpenAiCompatibleChatGateway(
                properties,
                ChatRestClientFactory.create(properties.getBaseUrl(), properties.getTimeout())
        );
    }
}
