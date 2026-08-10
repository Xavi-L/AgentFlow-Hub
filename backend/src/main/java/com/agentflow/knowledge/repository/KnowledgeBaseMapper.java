package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeBase;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 中文：知识库元数据的数据访问边界。当前的创建和分页查询都由 BaseMapper 提供，无须 XML。
 * English: Data-access boundary for knowledge-base metadata. BaseMapper supplies the
 * creation and paginated query operations needed by this slice, so no XML is required.
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
}
