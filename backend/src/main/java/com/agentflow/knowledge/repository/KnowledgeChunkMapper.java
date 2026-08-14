package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 中文：解析后文本块的数据访问边界。V4 使用 BaseMapper 的插入和分页读取能力；向量检索不属于
 * 此 Mapper 的职责。
 *
 * <p>English: Data-access boundary for parsed text chunks. V4 uses BaseMapper inserts
 * and paged reads; vector retrieval is deliberately outside this mapper's role.
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {
}
