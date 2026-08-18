package com.agentflow.knowledge.vector;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 中文：V6 单 collection Qdrant REST 配置。collection 和 vectorSize 是不可分的模型版本
 * 契约：不同维度或模型必须使用新的 collection，不能与现有 point 混写。
 *
 * <p>English: Qdrant REST settings for V6's single collection. The collection and
 * vector size form one model-version contract; a new dimension or model needs a new
 * collection rather than mixing points with the current one.
 */
@ConfigurationProperties(prefix = "agentflow.qdrant")
public class QdrantProperties {
    private String baseUrl = "http://127.0.0.1:6333";
    private String apiKey = "";
    private String collection = "agentflow_chunks_te_v4_1024";
    private int vectorSize = 1024;
    private Duration timeout = Duration.ofSeconds(10);

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

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public int getVectorSize() {
        return vectorSize;
    }

    public void setVectorSize(int vectorSize) {
        this.vectorSize = vectorSize;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
