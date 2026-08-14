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

    /**
     * 中文：根据受控的存储定位信息打开原始文档。调用方只能传数据库中保存的 bucket/object key，
     * 不会接触或拼接物理绝对路径。
     *
     * <p>English: Opens a source document from its controlled storage locator. Callers
     * pass only the persisted bucket/object key and never construct physical absolute
     * paths themselves.
     */
    InputStream open(StoredDocument storedDocument) throws IOException;

    void delete(StoredDocument storedDocument) throws IOException;
}
