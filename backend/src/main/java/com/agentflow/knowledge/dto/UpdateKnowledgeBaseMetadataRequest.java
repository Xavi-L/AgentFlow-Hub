package com.agentflow.knowledge.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 中文：V19 当前 owner 可部分更新的知识库元数据输入。两个 presence 标记只用于区分 JSON
 * 字段缺失（保持原值）与显式 null/空白 description（清空），它们不是客户端可命名的 JSON 字段。
 *
 * <p>English: V19 partial knowledge-base metadata input for the current owner. The two
 * presence flags distinguish an absent JSON field (preserve its value) from an explicit
 * null/blank description (clear it); they are not client-addressable JSON fields.
 */
@JsonDeserialize(using = UpdateKnowledgeBaseMetadataRequestDeserializer.class)
public record UpdateKnowledgeBaseMetadataRequest(
        boolean namePresent,
        String name,
        boolean descriptionPresent,
        String description
) {
    public boolean hasAnyMetadataField() {
        return namePresent || descriptionPresent;
    }
}
