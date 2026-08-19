# AgentFlow Hub Knowledge：V7 最小语义检索验收

V7 将 V6 已写入 Qdrant 的 dense vectors 用于一个显式、owner-scoped 的检索验证接口。它的目标
仅是“给出 query 后，返回当前用户当前知识库中仍有效的相关 chunk”。它不生成回答、不重排、不记录
检索日志，也不引入队列。

## 1. HTTP 契约

```http
POST /api/v1/knowledge-bases/{knowledgeBaseId}/retrieve-test
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "query": "退款失败时应该如何排查？",
  "topK": 5
}
```

- `query`：必填，去首尾空白后必须非空，最多 1000 个 Java 字符。
- `topK`：可选，默认 `5`，范围 `1..10`。
- `{knowledgeBaseId}` 由 URL 提供，`userId` 只能来自 JWT principal，不能来自 JSON。
- 不存在、非 owner 或软删除知识库统一为 `404 COMMON_NOT_FOUND`；非 `ACTIVE` 知识库返回既有的
  `409 KNOWLEDGE_BASE_NOT_ACTIVE`。

成功响应的 `data`：

```json
{
  "query": "退款失败时应该如何排查？",
  "topK": 5,
  "items": [
    {
      "rank": 1,
      "score": 0.91,
      "chunkId": "401",
      "documentId": "301",
      "chunkIndex": 0,
      "titlePath": "退款规则示例",
      "content": "退款失败时，先核对支付渠道返回的错误码。"
    }
  ]
}
```

`score` 是 Qdrant 在当前 Cosine collection 返回的原始相似度，仅用于观察本次 dense retrieval
排序；它不是跨模型可比的相关性概率，也不是正确答案置信度。

## 2. 业务与 Gateway 边界

```text
KnowledgeRetrievalController
  -> KnowledgeRetrievalService
       -> EmbeddingGateway.embed(query, kb.provider, kb.model)
       -> VectorStoreGateway.search(queryVector, userId, knowledgeBaseId, topK)
       -> KnowledgeChunkMapper.selectRetrievableChunks(...)
       -> verify current vectorId -> API response
```

`EmbeddingGateway` 沿用 V6 固定的 `dashscope / text-embedding-v4 / 1024` 契约。V7 使用
OpenAI-compatible `/embeddings` 的普通 dense query，尚不添加厂商特有的 `text_type`、instruction
或 sparse 表达；这些模型策略必须与重向量化契约一起在后续独立切片设计。

`VectorStoreGateway` 新增 provider-neutral 的 `search(VectorSearchRequest)`，只收取已生成的
vector、服务端提供的 `userId`、`knowledgeBaseId` 和小范围 `limit`，不接受 Controller 透传的任意
Qdrant filter。`QdrantVectorStoreGateway` 调用：

```text
POST /collections/{collection}/points/query
```

请求固定包含两个 `must` filter：`userId` 与 `knowledgeBaseId`，并且只请求 point `chunkId` payload。
检索路径只验证既有 collection；不会因为一个 read request 创建 collection。

## 3. 双层范围与内容权威性

Qdrant payload 是检索索引，不是正文来源，也不能单独被信任。每一个 Qdrant 命中都必须同时通过：

1. Qdrant 的 `userId + knowledgeBaseId` payload filter；
2. PostgreSQL 回查相同 owner/knowledge base，且 chunk 为 `COMPLETED`、来源 document 为
   `COMPLETED` 且未软删除；
3. PostgreSQL 当前 `chunk.vectorId` 与 Qdrant hit 的 point ID 精确一致。

仅在三项均成立时，V7 才从 PostgreSQL 返回 `content`、`titlePath` 等字段，并按 Qdrant score
顺序重新编号 `rank`。Qdrant 中的陈旧 point、错误 payload、被删除或重新处理后的 chunk 会静默从
结果排除，不会泄漏或覆盖 PostgreSQL 当前数据。

## 4. 数据与状态边界

V7 **没有 Flyway migration**：它不会把 embedding、检索历史、得分或回答写入 PostgreSQL。可检索的
前置条件是 V5/V6 已将 chunk 标为 `vectorizationStatus=COMPLETED`，并保存当前 `vectorId`。

Qdrant collection 仍由 V6 的 `agentflow_chunks_te_v4_1024` / 1024 dimensions / Cosine 契约约束。
若 collection 不存在，V7 read path 会报受控服务端失败而不创建它；请先完成 V6 向量化验收。

## 5. 验收顺序

1. 按 `backend/http/knowledge-base.http`、`backend/http/knowledge-document.http` 完成登录、新建
   V6-compatible knowledge base、上传、处理和 `vectorize-pending`。
2. 确认 chunk 为 `COMPLETED` 且有 `vectorId`，并确认 Qdrant collection 为 1024 维 Cosine。
3. 调用 `retrieve-test`。fixture 的“退款失败时应该如何排查？”应返回至少一个 chunk，首项有
   `rank=1`、数值 `score`、字符串 `chunkId` 与 PostgreSQL `content`。
4. 调用相同接口但 `topK=11`，应得到 `400 COMMON_PARAM_INVALID`。
5. 用另一个用户 token 或另一个 knowledge base 验证：不能召回本知识库的任何 chunk。

## 6. 明确不做

- rerank、关键词/BM25/hybrid retrieval、query rewrite 或厂商 query instruction；
- Agent tool、上下文拼装、LLM 回答生成、引用生成；
- 异步检索、重试/死信、失败 chunk 重向量化；
- 检索日志、点击反馈、离线评测、缓存、分页或跨知识库查询；
- delete-by-vector、payload index 优化、named/sparse vector 或 collection 迁移。
