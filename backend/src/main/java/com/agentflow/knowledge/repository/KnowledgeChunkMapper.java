package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * 中文：解析后文本块的数据访问边界。V4 使用 BaseMapper 的插入和分页读取能力；V5 额外提供
 * 一个只挑选来源文档已经 COMPLETED 的向量化候选查询。V7/V8 仅通过本 Mapper 回读、验证 Qdrant
 * 命中的 canonical chunk；它不在 PostgreSQL 做相似度计算。
 *
 * <p>English: Data-access boundary for parsed text chunks. V4 uses BaseMapper inserts
 * and paged reads. V5 adds a completed-source candidate query; V7/V8 use this mapper
 * only to re-read and validate canonical chunks after vector-store retrieval, never to
 * run similarity search in PostgreSQL.
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

    /**
     * Re-validates Qdrant-provided chunk IDs against the current owner, knowledge base,
     * document lifecycle, and successful-vectorization state. SQL deliberately does not
     * prescribe result ordering: the service restores Qdrant's similarity order only for
     * rows whose current vectorId still matches the hit.
     */
    @Results({
            @Result(property = "documentFileName", column = "document_file_name")
    })
    @Select("""
            <script>
            SELECT kc.*, kd.file_name AS document_file_name
            FROM knowledge_chunk kc
            INNER JOIN knowledge_document kd
                ON kd.id = kc.document_id
                AND kd.knowledge_base_id = kc.knowledge_base_id
                AND kd.user_id = kc.user_id
            WHERE kc.knowledge_base_id = #{knowledgeBaseId}
              AND kc.user_id = #{userId}
              AND kc.vectorization_status = 'COMPLETED'
              AND kd.parse_status = 'COMPLETED'
              AND kd.deleted_at IS NULL
              AND kc.id IN
              <foreach collection="chunkIds" item="chunkId" open="(" separator="," close=")">
                #{chunkId}
              </foreach>
            </script>
            """)
    List<KnowledgeChunk> selectRetrievableChunks(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId,
            @Param("chunkIds") List<Long> chunkIds
    );
}
