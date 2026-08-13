package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeDocument;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 中文：原始文档元数据的数据访问边界。当前切片只需要 BaseMapper 提供的插入和分页查询。
 * English: Data-access boundary for source-document metadata. This slice only needs the
 * insert and paginated query operations supplied by BaseMapper.
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
