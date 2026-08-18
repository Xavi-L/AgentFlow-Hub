package com.agentflow.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DashScopeEmbeddingGatewayTest {

    @Test
    void shouldCallTheConfiguredDashScopeModelAndValidateItsDimension() {
        DashScopeEmbeddingProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeEmbeddingGateway gateway = new DashScopeEmbeddingGateway(properties, builder.build());

        server.expect(requestTo("https://dashscope.test/compatible-mode/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-dashscope-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "model":"text-embedding-v4",
                          "input":"退款失败时先检查支付渠道错误码",
                          "dimensions":3,
                          "encoding_format":"float"
                        }
                        """))
                .andRespond(withSuccess("""
                        {"data":[{"embedding":[0.125,-0.5,0.75],"index":0,"object":"embedding"}]}
                        """, MediaType.APPLICATION_JSON));

        EmbeddingVector vector = gateway.embed(new EmbeddingRequest(
                "退款失败时先检查支付渠道错误码",
                "dashscope",
                "text-embedding-v4"
        ));

        assertThat(vector.values()).containsExactly(0.125f, -0.5f, 0.75f);
        server.verify();
    }

    @Test
    void shouldRejectAKnowledgeBaseModelThatDoesNotMatchTheConfiguredCollectionContract() {
        DashScopeEmbeddingProperties properties = properties();
        DashScopeEmbeddingGateway gateway = new DashScopeEmbeddingGateway(
                properties,
                RestClient.builder().baseUrl(properties.getBaseUrl()).build()
        );

        assertThatThrownBy(() -> gateway.embed(new EmbeddingRequest(
                "退款规则",
                "dashscope",
                "text-embedding-v3"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured embeddingModel=text-embedding-v4");
    }

    @Test
    void shouldRejectAProviderResponseWithAnUnexpectedDimension() {
        DashScopeEmbeddingProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeEmbeddingGateway gateway = new DashScopeEmbeddingGateway(properties, builder.build());
        server.expect(requestTo("https://dashscope.test/compatible-mode/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"data":[{"embedding":[0.125,-0.5],"index":0,"object":"embedding"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.embed(new EmbeddingRequest(
                "退款规则",
                "dashscope",
                "text-embedding-v4"
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned 2 dimensions; expected 3");
        server.verify();
    }

    private static DashScopeEmbeddingProperties properties() {
        DashScopeEmbeddingProperties properties = new DashScopeEmbeddingProperties();
        properties.setBaseUrl("https://dashscope.test/compatible-mode/v1");
        properties.setApiKey("test-dashscope-key");
        properties.setModel("text-embedding-v4");
        properties.setDimensions(3);
        return properties;
    }
}
