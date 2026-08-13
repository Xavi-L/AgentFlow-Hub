package com.agentflow.knowledge.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 中文：将可替换的本地存储实现注册为 DocumentStorage。以后接入对象存储时只需替换这个 Bean，
 * 上传 Service 和 API 契约无需改变。
 *
 * <p>English: Registers the replaceable local implementation as DocumentStorage. A
 * future object-store integration only needs to replace this bean, without changing
 * the upload service or API contract.
 */
@Configuration
@EnableConfigurationProperties({DocumentStorageProperties.class, DocumentUploadLimitProperties.class})
public class DocumentStorageConfig {

    @Bean
    public DocumentStorage documentStorage(DocumentStorageProperties properties) {
        return new LocalDocumentStorage(properties.getRoot());
    }
}
