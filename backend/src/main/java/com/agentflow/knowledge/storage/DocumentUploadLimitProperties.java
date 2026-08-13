package com.agentflow.knowledge.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * 中文：复用 Spring Servlet multipart 的同一份单文件大小配置。这样 Web 容器在进入 Controller
 * 前执行的限制，和 Service 面向测试/替代 MultipartFile 的防御性校验不会出现两个独立阈值。
 *
 * <p>English: Reuses the same single-file limit as Spring Servlet multipart handling.
 * This keeps the container's pre-controller limit and the Service's defensive check for
 * tests or alternate MultipartFile implementations from drifting into separate values.
 */
@ConfigurationProperties(prefix = "spring.servlet.multipart")
public class DocumentUploadLimitProperties {
    private DataSize maxFileSize = DataSize.ofMegabytes(20);

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }
}
