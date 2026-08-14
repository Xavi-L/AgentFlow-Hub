package com.agentflow.knowledge.parser;

import java.io.IOException;

/**
 * 中文：可预期的源文本解析失败，例如非法 UTF-8。它的 message 是经过控制的短摘要，可安全写入
 * 文档的内部 parse_error；底层原因仍只留在服务端日志。
 *
 * <p>English: An expected source-text parsing failure such as invalid UTF-8. Its
 * controlled short message may be stored in the document's internal parse_error, while
 * the underlying cause stays in server logs.
 */
public class DocumentParseException extends IOException {
    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
