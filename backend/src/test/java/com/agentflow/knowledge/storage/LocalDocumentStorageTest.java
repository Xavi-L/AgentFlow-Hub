package com.agentflow.knowledge.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.knowledge.model.DocumentFileType;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 中文：本地存储测试使用 JUnit 临时目录，不触碰开发者真实的上传目录。
 * English: Local-storage tests use a JUnit temporary directory and never touch a
 * developer's real upload directory.
 */
class LocalDocumentStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldStoreAndDeleteContentUnderAnOpaqueServerGeneratedKey() throws Exception {
        LocalDocumentStorage storage = new LocalDocumentStorage(temporaryDirectory);

        StoredDocument stored = storage.store(
                101L,
                201L,
                DocumentFileType.MD,
                new ByteArrayInputStream("# Refund rules\n".getBytes(StandardCharsets.UTF_8))
        );

        Path storedPath = temporaryDirectory.resolve(stored.storageObjectKey()).normalize();
        assertThat(stored.storageBucket()).isEqualTo(LocalDocumentStorage.STORAGE_BUCKET);
        assertThat(stored.storageObjectKey()).startsWith("users/101/knowledge-bases/201/documents/");
        assertThat(stored.storageObjectKey()).endsWith(".md");
        assertThat(stored.storageObjectKey()).doesNotContain("refund-rules");
        assertThat(storedPath).startsWith(temporaryDirectory);
        assertThat(Files.readString(storedPath)).isEqualTo("# Refund rules\n");

        try (InputStream reopened = storage.open(stored)) {
            assertThat(new String(reopened.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("# Refund rules\n");
        }

        storage.delete(stored);

        assertThat(Files.exists(storedPath)).isFalse();
    }

    @Test
    void shouldRefuseAStorageKeyThatEscapesItsConfiguredRoot() {
        LocalDocumentStorage storage = new LocalDocumentStorage(temporaryDirectory);

        assertThatThrownBy(() -> storage.delete(new StoredDocument("local", "../outside.md")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative to the storage root");

        assertThatThrownBy(() -> storage.open(new StoredDocument("another-bucket", "inside.md")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different storage bucket");
    }
}
