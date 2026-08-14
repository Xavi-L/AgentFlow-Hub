package com.agentflow.knowledge.parser;

import com.agentflow.knowledge.model.DocumentFileType;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 中文：TXT 的 V4 解析器。它只做严格 UTF-8 解码和轻量换行规范化，不会猜测格式或改变正文语义。
 * English: V4 parser for TXT. It performs strict UTF-8 decoding and light newline
 * normalization only; it does not guess a format or rewrite body semantics.
 */
@Component
public class TextDocumentParser implements DocumentParser {

    @Override
    public boolean supports(DocumentFileType fileType) {
        return fileType == DocumentFileType.TXT;
    }

    @Override
    public ParsedDocument parse(InputStream content) throws IOException {
        return new ParsedDocument(Utf8DocumentText.readAndNormalize(content, false), List.of());
    }
}
