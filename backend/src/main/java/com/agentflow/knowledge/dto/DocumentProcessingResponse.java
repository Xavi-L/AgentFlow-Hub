package com.agentflow.knowledge.dto;

/**
 * 中文：一次同步 PENDING 文档处理请求的汇总。单个文件失败会记录为 FAILED 并计入本结果，不会让
 * 同一批的其他文档失去处理机会。
 *
 * <p>English: Summary of one synchronous PENDING-document processing request. One
 * failed source is marked FAILED and counted here without preventing the rest of the
 * batch from being processed.
 */
public record DocumentProcessingResponse(
        int discovered,
        int claimed,
        int completed,
        int failed,
        int skipped
) {
}
