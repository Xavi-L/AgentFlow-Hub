package com.agentflow.knowledge.storage;

import com.agentflow.knowledge.model.DocumentFileType;
import java.io.IOException;
import java.io.InputStream;

/**
 * 中文：原始文档文件的存储边界。业务层依赖这个接口而不依赖本地磁盘，未来可单独替换为对象存储。
 *
 * <p>English: Storage boundary for original document files. The business layer depends
 * on this interface rather than a local disk, so object storage can replace it later
 * without changing the upload API.
 */
public interface DocumentStorage {

    StoredDocument store(
            long userId,
            long knowledgeBaseId,
            DocumentFileType fileType,
            InputStream content
    ) throws IOException;

    void delete(StoredDocument storedDocument) throws IOException;
}
