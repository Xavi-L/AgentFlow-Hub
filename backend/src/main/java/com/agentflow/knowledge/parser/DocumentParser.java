package com.agentflow.knowledge.parser;

import com.agentflow.knowledge.model.DocumentFileType;
import java.io.IOException;
import java.io.InputStream;

/**
 * 中文：按受控文件类型读取原始文档的边界。V4 只提供 TXT 与 Markdown 实现；以后新增 PDF
 * 不需要改动处理编排层。
 *
 * <p>English: Boundary for reading a source document by its controlled file type. V4
 * implements TXT and Markdown only; a future PDF parser can be added without changing
 * the processing orchestration.
 */
public interface DocumentParser {

    boolean supports(DocumentFileType fileType);

    ParsedDocument parse(InputStream content) throws IOException;
}
