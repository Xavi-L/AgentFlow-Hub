package com.agentflow.knowledge.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 中文：本地开发文档存储根目录。默认目录在用户主目录下而不是工作区，因此上传的真实资料不会
 * 被 Git 或 Maven 构建目录意外收集；部署环境可用环境变量覆盖。
 *
 * <p>English: Root directory for local-development document storage. The default lives
 * below the user's home directory rather than the workspace, preventing real uploads
 * from being accidentally collected by Git or Maven; deployments can override it.
 */
@ConfigurationProperties(prefix = "agentflow.document.storage")
public class DocumentStorageProperties {
    private Path root = Path.of(System.getProperty("user.home"), ".agentflow-hub", "documents");

    public Path getRoot() {
        return root;
    }

    public void setRoot(Path root) {
        this.root = root;
    }
}
