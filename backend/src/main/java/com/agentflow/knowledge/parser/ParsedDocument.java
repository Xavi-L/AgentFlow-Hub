package com.agentflow.knowledge.parser;

import java.util.List;
import java.util.Objects;

/**
 * 中文：进入分块器的规范化文本和可选标题上下文。正文仍保留 Markdown 原文；标题路径只作为
 * 可观察 metadata，不会删除或重写文档内容。
 *
 * <p>English: Normalized text plus optional heading context passed to the chunker. The
 * body retains Markdown source text; title paths are observable metadata and never
 * remove or rewrite document content.
 */
public record ParsedDocument(String text, List<ParsedSection> sections) {
    public ParsedDocument {
        text = Objects.requireNonNull(text, "text must not be null");
        sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));

        int previousOffset = -1;
        for (ParsedSection section : sections) {
            if (section.startOffset() > text.length()) {
                throw new IllegalArgumentException("Section offset exceeds parsed text length");
            }
            if (section.startOffset() < previousOffset) {
                throw new IllegalArgumentException("Sections must be ordered by startOffset");
            }
            previousOffset = section.startOffset();
        }
    }

    /** Returns the most recent Markdown heading path at a given text offset, if any. */
    public String titlePathAt(int textOffset) {
        if (textOffset < 0 || textOffset > text.length()) {
            throw new IllegalArgumentException("textOffset is outside parsed text");
        }
        String currentTitlePath = null;
        for (ParsedSection section : sections) {
            if (section.startOffset() > textOffset) {
                break;
            }
            currentTitlePath = section.titlePath();
        }
        return currentTitlePath;
    }
}
