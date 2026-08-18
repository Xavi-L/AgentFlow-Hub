# AgentFlow Hub Knowledge：V5 向量化入库切片

本文件描述当前实现的 V5：**只对来源文档已经解析完成的 `knowledge_chunk` 执行显式、同步的
向量化入库。** 每个 chunk 会生成 embedding，通过 `VectorStoreGateway` 做幂等 upsert，并将自己的
向量化状态更新为成功或失败。

它建立在不可修改的 Flyway V1–V4 之上。V5 的完成标准是“chunk 的待向量化状态可被显式触发、成功
后可查看稳定 `vectorId`、失败可追踪、重复调用安全”；它**不**做语义查询、rerank、Agent、异步队列
或自动重试。

> V5 建立了本地可验收的状态机与 Gateway 边界。真实 DashScope + Qdrant 的 remote mode 已在 V6
> 接入，配置与真实环境验收以 `07_REAL_VECTOR_INGESTION_PACKAGE_INTERFACE.md` 为准。本文件保留 V5
> 的稳定 identity、状态机和本地 adapter 契约，便于理解两层职责如何分离。

## 1. V5 最小闭环

```text
V4 完成解析的 document
  -> knowledge_chunk.vectorization_status = PENDING
  -> POST /knowledge-bases/{kbId}/chunks/vectorize-pending
  -> EmbeddingGateway.embed(...)
  -> VectorStoreGateway.upsert(stable vectorId, vector, payload)
  -> knowledge_chunk.vectorization_status = COMPLETED
  -> GET /knowledge-bases/{kbId}/documents/{docId}/chunks
     可查看 vectorizationStatus / contentHash / vectorId
```

只有满足以下条件的 chunk 会成为候选项：

- 当前 JWT user 拥有该 knowledge base；
- knowledge base 未软删除；
- 来源 `knowledge_document.parse_status = COMPLETED`；
- 来源 document 未软删除；
- chunk 自身仍为 `PENDING`。

`COMPLETED`、`FAILED`、`PROCESSING` chunk 会被计为 `skipped`，不会再次调用 embedding 或 vector
store。V5 不自动重试失败项，也不回收陈旧的 `PROCESSING`，这些需要后续单独定义 retry/lease 语义。

## 2. Chunk 向量化状态机

```text
PENDING
  -- 条件更新（仅仍为 PENDING 时） --> PROCESSING
  -- embedding + vector-store upsert + 成功状态写回 --> COMPLETED
  -- embedding / upsert / 状态写回失败 --> FAILED
```

两个并发请求看到同一个 `PENDING` chunk 时，只有一个能通过带状态条件的数据库更新认领它；另一个
请求把它计入 `skipped`，不会重复调用外部 Gateway。外部调用发生在数据库事务之外，认领、成功和失败
各自使用短的 `REQUIRES_NEW` 事务，避免网络延迟长期占用连接。

PostgreSQL 与 Qdrant 不共享事务。若向量已 upsert 而完成状态写回失败，未来补偿仍会使用同一个
`vectorId` 重新 upsert，而不会制造第二个 point。

## 3. Flyway V5 数据契约

位置：

```text
backend/src/main/resources/db/migration/V5__add_chunk_vectorization_state.sql
```

| 字段 | 含义 |
| --- | --- |
| `vectorization_status` | `PENDING`、`PROCESSING`、`COMPLETED` 或 `FAILED` |
| `vectorization_error` | 仅失败时保存的受控错误类别；不泄漏 provider/网络异常细节 |
| `content_hash` | chunk 精确 UTF-8 正文的 64 位小写 SHA-256 十六进制值 |
| `vector_id` | 仅成功时保存的、Qdrant 可接受的稳定 UUID |

迁移会用 PostgreSQL `pgcrypto` 为已有 V4 chunks 回填同一 SHA-256 规则，随后把 `content_hash` 设为
非空。数据库约束保证成功行必须同时有 `vector_id` 且无错误，失败行必须有错误且无 `vector_id`，待处理
或处理中行不能伪装成已入库。

新 V4 chunk 持久化时也立即写入 `content_hash` 和 `PENDING`，因此 V5 不需要再读取原始文件或重新解析
正文。

## 4. 稳定 identity 与幂等 upsert

`ChunkVectorIdentityFactory` 用精确正文计算：

```text
contentHash = SHA-256(chunk.content as UTF-8)

vectorId = UUIDv8(SHA-256(
  "agentflow-knowledge-vector-v1\n" + userId + "\n" + knowledgeBaseId + "\n" +
  documentId + "\n" + chunkIndex + "\n" + contentHash
))
```

只使用 `contentHash` 会使两个不同文档中恰好相同的文本争用一个 Qdrant point，因此 V5 将 owner、知识
库、文档和稳定 chunk 序号一并纳入 ID。相同 chunk 的重复执行得到相同 ID；正文改变时 content hash 和
point ID 同时改变。`VectorStoreGateway.upsert` 以这个 ID 覆盖同一点，业务层据已完成状态跳过重复工作。

Qdrant payload 只带回查和过滤需要的 metadata：`chunkId`、`documentId`、`knowledgeBaseId`、`userId`、
`chunkIndex`、`contentHash`、`titlePath`（若有）、`embeddingProvider` 和 `embeddingModel`。长正文仍以
PostgreSQL 为权威来源，不复制成 Qdrant 的权威数据。

## 5. Gateway 边界

```java
public interface EmbeddingGateway {
    EmbeddingVector embed(EmbeddingRequest request);
}

public interface VectorStoreGateway {
    void upsert(VectorStoreRecord record);
}
```

`ChunkVectorizationService` 只依赖上述接口和 provider-neutral records；它没有任何模型客户端或 Qdrant
SDK import。

V5 基线的 local mode bean：

| Bean | 作用 | 边界 |
| --- | --- | --- |
| `DeterministicDevelopmentEmbeddingGateway` | 从正文 hash 生成固定 16 维浮点向量 | 仅用于调用链和 HTTP 验收，不具有语义能力 |
| `InMemoryVectorStoreGateway` | 根据 `vectorId` 做线程安全覆盖写入 | 仅模拟 Qdrant upsert 的幂等性，不提供搜索或持久化 |

当 `agentflow.knowledge.vectorization.mode=local` 时会选择这些 adapter；它适合状态机单元测试与离线
HTTP 验收，不能证明真实语义质量。V6 的 `remote` mode 提供 DashScope 和 Qdrant bean，但仍不改变本类
业务编排、稳定 identity 或幂等写入语义。

## 6. HTTP 接口

```http
POST /api/v1/knowledge-bases/{knowledgeBaseId}/chunks/vectorize-pending
Authorization: Bearer <accessToken>
```

成功示例：

```json
{
  "code": "OK",
  "message": "Pending chunks vectorized",
  "data": {
    "discovered": 3,
    "claimed": 2,
    "completed": 1,
    "failed": 1,
    "skipped": 1
  }
}
```

`discovered` 是该 owner/KB 下、来源 document 已完成的全部 chunks；`claimed` 只统计本请求成功从
`PENDING` 认领的项；`skipped` 是已完成、已失败或同时被其他请求认领的项。正常情况下
`claimed = completed + failed`。

知识库不存在、非 owner 或软删除时，接口沿用 `404 COMMON_NOT_FOUND`；非正数路径 ID 返回
`400 COMMON_PARAM_INVALID`。Controller 只从 JWT security context 取 owner，绝不从请求体接收 user ID。

通过现有 owner-scoped chunk 查看接口可观察新字段：

```http
GET /api/v1/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/chunks?page=1&pageSize=20
Authorization: Bearer <accessToken>
```

成功 item 的关键字段为：

```json
{
  "vectorizationStatus": "COMPLETED",
  "contentHash": "<64-char lowercase sha256>",
  "vectorId": "<stable UUID>"
}
```

如果失败，`vectorizationStatus` 为 `FAILED`，并返回受控的 `vectorizationError`；成功行不带错误字段。

## 7. IDEA 手工验收

打开：

```text
backend/http/knowledge-document.http
```

按顺序运行：Login → Create knowledge base → Upload fixture → Process pending → Vectorize pending → Repeat
vectorize → Inspect chunks。

脚本会检查：

1. 处理合法 fixture 后至少有一个 persisted chunk；
2. 首次 `vectorize-pending` 至少完成一个 chunk；
3. 第二次调用不再完成同一 chunk，已完成项进入 `skipped`；
4. chunk item 的 `vectorizationStatus` 变为 `COMPLETED`；
5. item 有合法 UUID `vectorId` 和 64 位 `contentHash`。

这组验收证明 V5 的 owner scope、状态机、稳定 identity 与幂等调用链；真实 Qdrant endpoint、embedding
API、语义检索效果、rerank、Agent 和异步队列都不在本切片的验收范围内。
