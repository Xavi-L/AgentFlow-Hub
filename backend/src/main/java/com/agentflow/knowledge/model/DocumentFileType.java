package com.agentflow.knowledge.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 中文：当前文档接入切片允许的、服务端认可的文件类型。客户端声明的 MIME type 不作为放行
 * 依据；服务端根据受控扩展名生成稳定 MIME type，后续解析切片再负责读取正文。
 *
 * <p>English: File types accepted by this ingestion slice. A client-declared MIME type
 * is not an admission decision; the server derives a stable MIME type from the allowed
 * extension. A later parsing slice will be responsible for reading document contents.
 */
public enum DocumentFileType {
    TXT("txt", "text/plain"),
    MD("md", "text/markdown");

    private final String extension;
    private final String mimeType;

    DocumentFileType(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public String getExtension() {
        return extension;
    }

    public String getMimeType() {
        return mimeType;
    }

    /**
     * 中文：扩展名比较大小写不敏感，例如 {@code RULES.TXT} 与 {@code rules.txt} 都属于 TXT。
     * English: Extension comparison is case-insensitive, so RULES.TXT and rules.txt are
     * both TXT documents.
     */
    public static Optional<DocumentFileType> fromExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(fileType -> fileType.extension.equals(normalized))
                .findFirst();
    }
}
