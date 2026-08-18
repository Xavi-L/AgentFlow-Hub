package com.agentflow.knowledge.vector;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 中文：V6 DashScope text-embedding-v4 的运行期连接配置。密钥只允许由环境变量或部署密钥
 * 注入；本类和受 Git 跟踪的 YAML 都不保存真实值。
 *
 * <p>English: Runtime connection settings for V6 DashScope text-embedding-v4. The API
 * key must come from an environment/deployment secret; neither this class nor tracked
 * YAML stores a real key.
 */
@ConfigurationProperties(prefix = "agentflow.knowledge.vectorization.embedding.dashscope")
public class DashScopeEmbeddingProperties {
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String apiKey = "";
    private String model = "text-embedding-v4";
    private int dimensions = 1024;
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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
