package com.agentflow.knowledge.model;

/**
 * 中文：原始文档从接收至完成解析的有限状态集合。数据库仍存储字符串，以便保持与既有 V3
 * migration 的兼容；业务代码使用此枚举避免散落的拼写常量。
 *
 * <p>English: The finite lifecycle for an accepted source document. The database still
 * stores strings for compatibility with the existing V3 migration, while business code
 * uses this enum instead of scattering status literals.
 */
public enum DocumentParseStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    REPROCESSING
}
