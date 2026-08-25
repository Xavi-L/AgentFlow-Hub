package com.agentflow.knowledge.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleChatGatewayTest {

    @Test
    void shouldSendAConfiguredNonStreamingChatCompletionWithTheExactContext() {
        OpenAiChatProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleChatGateway gateway = new OpenAiCompatibleChatGateway(properties, builder.build());
        String context = """
                [S1]
                Source: refund-rules.md
                Title: 支付 / 退款
                DocumentId: 301
                ChunkId: 401
                Content:
                先检查支付渠道错误码。""";

        server.expect(requestTo("http://openai-compatible.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-openai-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("local-qwen-test"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.max_tokens").value(256))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value(
                        OpenAiCompatibleChatGateway.SYSTEM_INSTRUCTION
                ))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value(
                        "Question:\n退款失败如何排查？\n\nContext:\n" + context
                ))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"先核对支付渠道错误码。[S1]"}}]}
                        """, MediaType.APPLICATION_JSON));

        String answer = gateway.generate(new ChatRequest("退款失败如何排查？", context, 256));

        assertThat(answer).isEqualTo("先核对支付渠道错误码。[S1]");
        server.verify();
    }

    @Test
    void shouldWrapAProviderFailureAsAGatewayFailure() {
        OpenAiChatProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleChatGateway gateway = new OpenAiCompatibleChatGateway(properties, builder.build());
        server.expect(requestTo("http://openai-compatible.test/v1/chat/completions"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> gateway.generate(new ChatRequest(
                "退款失败如何排查？",
                "[S1]\nContent:\n先检查错误码。",
                256
        )))
                .isInstanceOf(ChatGatewayException.class)
                .hasMessage("OpenAI-compatible chat request failed");
        server.verify();
    }

    @Test
    void shouldRejectAProviderResponseWithoutAUsableAnswer() {
        OpenAiChatProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleChatGateway gateway = new OpenAiCompatibleChatGateway(properties, builder.build());
        server.expect(requestTo("http://openai-compatible.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{}}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.generate(new ChatRequest(
                "退款失败如何排查？",
                "[S1]\nContent:\n先检查错误码。",
                256
        )))
                .isInstanceOf(ChatGatewayException.class)
                .hasMessageContaining("choices[0].message.content");
        server.verify();
    }

    @Test
    void shouldPermitAnUnauthenticatedLocalCompatibleServerWhenNoKeyIsConfigured() {
        OpenAiChatProperties properties = properties();
        properties.setApiKey(" ");
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleChatGateway gateway = new OpenAiCompatibleChatGateway(properties, builder.build());
        server.expect(requestTo("http://openai-compatible.test/v1/chat/completions"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"本地回答。[S1]"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(gateway.generate(new ChatRequest(
                "退款失败如何排查？",
                "[S1]\nContent:\n先检查错误码。",
                256
        ))).isEqualTo("本地回答。[S1]");
        server.verify();
    }

    private static OpenAiChatProperties properties() {
        OpenAiChatProperties properties = new OpenAiChatProperties();
        properties.setBaseUrl("http://openai-compatible.test/v1");
        properties.setApiKey("test-openai-key");
        properties.setChatModel("local-qwen-test");
        return properties;
    }
}
