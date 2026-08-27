package com.agentflow.knowledge.repository;

import com.agentflow.knowledge.model.KnowledgeChatAnswer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * 中文：V10/V11 回答审计记录的数据访问边界。写入仅使用 BaseMapper.insert；单条读取必须同时带上
 * answerId、knowledgeBaseId 和已认证 owner，不能先按 ID 取行再在 Java 中判断归属。V11 分页读取使用
 * BaseMapper.selectPage 和 Service 构造的 owner、knowledgeBaseId、固定排序 wrapper。
 *
 * <p>English: Data access for V10/V11 answer audit rows. Writes use only BaseMapper.insert;
 * detail reads bind answerId, knowledgeBaseId, and authenticated owner in one SQL predicate
 * rather than fetching by ID and checking ownership in Java. V11 paged reads use
 * BaseMapper.selectPage with the Service-owned scope and fixed-sort wrapper.</p>
 */
@Mapper
public interface KnowledgeChatAnswerMapper extends BaseMapper<KnowledgeChatAnswer> {

    @Results(id = "knowledgeChatAnswerResult", value = {
            @Result(id = true, property = "id", column = "id"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "knowledgeBaseId", column = "knowledge_base_id"),
            @Result(property = "query", column = "query"),
            @Result(property = "answer", column = "answer"),
            @Result(property = "topK", column = "top_k"),
            @Result(property = "maxContextTokens", column = "max_context_tokens"),
            @Result(property = "usedContextTokens", column = "used_context_tokens"),
            @Result(property = "skippedChunkCount", column = "skipped_chunk_count"),
            @Result(property = "maxAnswerTokens", column = "max_answer_tokens"),
            @Result(property = "sourcesSnapshotJson", column = "sources_snapshot_json"),
            @Result(property = "citationIdsJson", column = "citation_ids_json"),
            @Result(property = "createdAt", column = "created_at")
    })
    @Select("""
            SELECT id,
                   user_id,
                   knowledge_base_id,
                   query,
                   answer,
                   top_k,
                   max_context_tokens,
                   used_context_tokens,
                   skipped_chunk_count,
                   max_answer_tokens,
                   sources_snapshot_json,
                   citation_ids_json,
                   created_at
            FROM knowledge_chat_answer
            WHERE id = #{answerId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND user_id = #{userId}
            """)
    KnowledgeChatAnswer selectOwnedById(
            @Param("answerId") Long answerId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );
}
