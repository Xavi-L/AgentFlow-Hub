package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 中文：解析后文本块的数据访问边界。V4 使用 BaseMapper 的插入和分页读取能力；V5 额外提供
 * 一个只挑选来源文档已经 COMPLETED 的向量化候选查询。向量检索不属于此 Mapper 的职责。
 *
 * <p>English: Data-access boundary for parsed text chunks. V4 uses BaseMapper inserts
 * and paged reads. V5 adds a candidate query that accepts only chunks whose source
 * document is completed; vector retrieval remains outside this mapper's role.
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * The explicit V5 trigger may inspect completed/failed chunks to report them as
     * skipped, but it receives rows only from completed, non-deleted source documents.
     */
    @Select("""
            SELECT kc.*
            FROM knowledge_chunk kc
            INNER JOIN knowledge_document kd
                ON kd.id = kc.document_id
                AND kd.knowledge_base_id = kc.knowledge_base_id
                AND kd.user_id = kc.user_id
            WHERE kc.knowledge_base_id = #{knowledgeBaseId}
              AND kc.user_id = #{userId}
              AND kd.parse_status = 'COMPLETED'
              AND kd.deleted_at IS NULL
            ORDER BY kc.document_id ASC, kc.chunk_index ASC, kc.id ASC
            """)
    List<KnowledgeChunk> selectVectorizationCandidates(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );
}
