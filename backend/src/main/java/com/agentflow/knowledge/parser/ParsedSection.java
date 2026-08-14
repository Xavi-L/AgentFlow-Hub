package com.agentflow.knowledge.parser;

import java.util.Objects;

/**
 * 中文：解析文本中的一个标题上下文起点。offset 使用 Java String 的 UTF-16 偏移，只用于安全的
 * substring 定位；对外统计的 charCount 则在分块时使用 Unicode code point。
 *
 * <p>English: The start of a heading context in parsed text. The offset is a Java
 * String UTF-16 offset used only for safe substring positioning; externally visible
 * charCount is calculated from Unicode code points during chunking.
 */
public record ParsedSection(int startOffset, String titlePath) {
    public ParsedSection {
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must not be negative");
        }
        titlePath = Objects.requireNonNull(titlePath, "titlePath must not be null").strip();
        if (titlePath.isEmpty()) {
            throw new IllegalArgumentException("titlePath must not be blank");
        }
    }
}
