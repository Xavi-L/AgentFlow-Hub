package com.agentflow.knowledge.parser;

import com.agentflow.knowledge.model.DocumentFileType;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 中文：把受控 fileType 路由到唯一支持它的解析器，避免业务编排层依赖 TXT/Markdown 的具体类。
 * English: Routes a controlled fileType to its supporting parser so orchestration does
 * not depend on TXT or Markdown implementation classes.
 */
@Component
public class DocumentParserResolver {
    private final List<DocumentParser> documentParsers;

    public DocumentParserResolver(List<DocumentParser> documentParsers) {
        this.documentParsers = List.copyOf(Objects.requireNonNull(
                documentParsers,
                "documentParsers must not be null"
        ));
    }

    public ParsedDocument parse(DocumentFileType fileType, InputStream content) throws IOException {
        Objects.requireNonNull(fileType, "fileType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        return documentParsers.stream()
                .filter(parser -> parser.supports(fileType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No document parser is configured for file type " + fileType
                ))
                .parse(content);
    }
}
