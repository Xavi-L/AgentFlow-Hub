package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.vector.VectorSearchRequest.DocumentGeneration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * 中文：解析后文本块的数据访问边界。V4 使用 BaseMapper 的插入和分页读取能力；V5 额外提供
 * 一个只挑选来源文档已经 COMPLETED 的向量化候选查询。V7/V8 仅通过本 Mapper 回读、验证 Qdrant
 * 命中的 canonical chunk；V24 只在删除已获准后探测 PROCESSING 并物理删除该文档的 chunks。
 * 它不在 PostgreSQL 做相似度计算。
 *
 * <p>English: Data-access boundary for parsed text chunks. V4 uses BaseMapper inserts
 * and paged reads. V5 adds a completed-source candidate query; V7/V8 use this mapper
 * only to re-read and validate canonical chunks after vector-store retrieval, while V24
 * performs only an admitted document's processing probe/scoped physical deletion. It never
 * runs similarity search in PostgreSQL.
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * Pages only the canonical generation of a currently COMPLETED document. The caller holds
     * shared parent/document locks across MyBatis-Plus's count and data statements.
     */
    @Select("""
            SELECT kc.*
            FROM knowledge_chunk kc
            INNER JOIN knowledge_document kd
                ON kd.id = kc.document_id
                AND kd.knowledge_base_id = kc.knowledge_base_id
                AND kd.user_id = kc.user_id
            INNER JOIN knowledge_base kb
                ON kb.id = kd.knowledge_base_id
                AND kb.user_id = kd.user_id
            WHERE kc.document_id = #{documentId}
              AND kc.knowledge_base_id = #{knowledgeBaseId}
              AND kc.user_id = #{userId}
              AND kc.vector_generation = kd.vector_generation
              AND kd.parse_status = 'COMPLETED'
              AND kd.deleted_at IS NULL
              AND kb.deleted_at IS NULL
            ORDER BY kc.chunk_index ASC, kc.id ASC
            """)
    Page<KnowledgeChunk> selectVisibleCompletedDocumentPage(
            Page<KnowledgeChunk> page,
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

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
            INNER JOIN knowledge_base kb
                ON kb.id = kd.knowledge_base_id
                AND kb.user_id = kd.user_id
            WHERE kc.knowledge_base_id = #{knowledgeBaseId}
              AND kc.user_id = #{userId}
              AND kd.parse_status = 'COMPLETED'
              AND kc.vector_generation = kd.vector_generation
              AND kd.deleted_at IS NULL
              AND kb.deleted_at IS NULL
            ORDER BY kc.document_id ASC, kc.chunk_index ASC, kc.id ASC
            """)
    List<KnowledgeChunk> selectVectorizationCandidates(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    /**
     * Used only while V24 holds the matching parent document lock. A true result means a worker
     * has already committed a vectorization claim and may still be between external upsert and
     * its terminal database update, so deleting the document would be unsafe.
     */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM knowledge_chunk
                WHERE document_id = #{documentId}
                  AND knowledge_base_id = #{knowledgeBaseId}
                  AND user_id = #{userId}
                  AND vectorization_status = 'PROCESSING'
            )
            """)
    boolean hasProcessingChunkByDocumentScope(
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );

    /** Physically removes only chunks whose parent document has already passed V24 admission. */
    @Delete("""
            DELETE FROM knowledge_chunk
            WHERE document_id = #{documentId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
            """)
    int deleteByDocumentScope(
            @Param("documentId") Long documentId,
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
              AND kc.vector_generation = kd.vector_generation
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

    /** Task corpus fence. No Agent binding or current replacement generation is resolved here. */
    @Results({@Result(property = "documentFileName", column = "document_file_name")})
    @Select("""
            <script>
            SELECT kc.*, kd.file_name AS document_file_name
            FROM knowledge_chunk kc
            JOIN knowledge_document kd
              ON kd.id = kc.document_id
             AND kd.knowledge_base_id = kc.knowledge_base_id
             AND kd.user_id = kc.user_id
            JOIN knowledge_base kb
              ON kb.id = kd.knowledge_base_id
             AND kb.user_id = kd.user_id
            WHERE kc.user_id = #{userId}
              AND kc.knowledge_base_id = #{knowledgeBaseId}
              AND kb.status = 'ACTIVE'
              AND kb.deleted_at IS NULL
              AND kd.parse_status = 'COMPLETED'
              AND kd.deleted_at IS NULL
              AND kc.vectorization_status = 'COMPLETED'
              AND kc.vector_generation = kd.vector_generation
              AND kc.chunk_strategy_version = #{chunkStrategyVersion}
              AND kc.content_hash IS NOT NULL
              AND kc.vector_id IS NOT NULL
              AND
              <foreach collection="documents" item="document" open="(" separator=" OR " close=")">
                (kc.document_id = #{document.documentId}
                 AND kc.vector_generation = #{document.vectorGeneration})
              </foreach>
              AND kc.id IN
              <foreach collection="chunkIds" item="chunkId" open="(" separator="," close=")">
                #{chunkId}
              </foreach>
            </script>
            """)
    List<KnowledgeChunk> selectSnapshotRetrievableChunks(
            @Param("userId") long userId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documents") List<DocumentGeneration> documents,
            @Param("chunkStrategyVersion") String chunkStrategyVersion,
            @Param("chunkIds") List<Long> chunkIds
    );
}
