package com.agentflow.knowledge.storage;

import java.util.Objects;

/**
 * 中文：一次成功写入后返回的内部定位信息。它只在 Service 与存储实现之间流转，不能直接成为
 * HTTP 响应的一部分。
 *
 * <p>English: Internal location information returned after a successful write. It only
 * travels between the service and storage implementation and must not become an HTTP
 * response field.
 */
public record StoredDocument(String storageBucket, String storageObjectKey) {
    public StoredDocument {
        Objects.requireNonNull(storageBucket, "storageBucket must not be null");
        Objects.requireNonNull(storageObjectKey, "storageObjectKey must not be null");
    }
}
