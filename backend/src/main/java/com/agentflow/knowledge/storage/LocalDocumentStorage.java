package com.agentflow.knowledge.storage;

import com.agentflow.knowledge.model.DocumentFileType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

/**
 * 中文：面向本地开发的文件存储实现。每个文件使用服务端 UUID 和受控扩展名生成对象键，绝不把
 * 用户文件名拼进磁盘路径；先写入同目录临时文件，再移动到最终位置，避免留下半写入的目标文件。
 *
 * <p>English: Local-development storage implementation. Every object key uses a
 * server-generated UUID and controlled extension, never a user filename. Content is
 * written to a sibling temporary file before moving to its final location, preventing
 * a partially written target file.
 */
public class LocalDocumentStorage implements DocumentStorage {
    public static final String STORAGE_BUCKET = "local";

    private final Path root;

    public LocalDocumentStorage(Path root) {
        try {
            this.root = Objects.requireNonNull(root, "root must not be null")
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(this.root);
            if (!Files.isDirectory(this.root)) {
                throw new IllegalStateException("Document storage root is not a directory");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Document storage root cannot be initialized", ex);
        }
    }

    @Override
    public StoredDocument store(
            long userId,
            long knowledgeBaseId,
            DocumentFileType fileType,
            InputStream content
    ) throws IOException {
        Objects.requireNonNull(fileType, "fileType must not be null");
        Objects.requireNonNull(content, "content must not be null");

        String objectKey = "users/" + userId
                + "/knowledge-bases/" + knowledgeBaseId
                + "/documents/" + UUID.randomUUID() + "." + fileType.getExtension();
        Path target = resolveObjectKey(objectKey);
        Files.createDirectories(target.getParent());

        Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".part");
        try {
            Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveToFinalLocation(temporary, target);
            return new StoredDocument(STORAGE_BUCKET, objectKey);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void delete(StoredDocument storedDocument) throws IOException {
        Objects.requireNonNull(storedDocument, "storedDocument must not be null");
        if (!STORAGE_BUCKET.equals(storedDocument.storageBucket())) {
            throw new IllegalArgumentException("Stored document belongs to a different storage bucket");
        }
        Files.deleteIfExists(resolveObjectKey(storedDocument.storageObjectKey()));
    }

    private void moveToFinalLocation(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target);
        }
    }

    private Path resolveObjectKey(String objectKey) {
        Path relativePath = Path.of(Objects.requireNonNull(objectKey, "objectKey must not be null"))
                .normalize();
        if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
            throw new IllegalArgumentException("Document storage object key must be relative to the storage root");
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Document storage object key escapes the storage root");
        }
        return resolved;
    }
}
