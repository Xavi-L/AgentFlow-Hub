# AgentFlow Hub Knowledge：V8 RAG 上下文装配与引用溯源

V8 承接 V7 的 owner-scoped canonical retrieval：它把已经验证过的相似度有序 chunk 转换为一个未来
`ChatGateway` 可直接使用的、受预算约束的 `context`，以及与 context 中 `[S#]` 标记一一对应的
`sources`。**V8 不调用 LLM、不构造 Prompt、不生成回答。**

这条边界让后续 V9 只需要负责 `ChatGateway(context) -> answer`：召回、预算控制和引用来源已经在
模型调用之前稳定下来，不会混进生成逻辑。

## 1. HTTP 契约

```http
POST /api/v1/knowledge-bases/{knowledgeBaseId}/retrieve-context-test
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "query": "退款失败时应该如何排查？",
  "topK": 5,
  "maxContextTokens": 3000
}
```

- `query` 和 `topK` 与 V7 `retrieve-test` 相同：query 去首尾空白后必须非空、最多 1000 个 Java
  字符；`topK` 可省略并沿用 V7 默认 `5`，范围 `1..10`。
- `maxContextTokens` 是必填整数，范围 `1..8000`。它是 context 本身的预算，不是未来模型回答、工具
  或系统 Prompt 的预算。
- `{knowledgeBaseId}` 和当前 JWT principal 原样交给 V7；请求体不能指定 `userId`、Qdrant filter、
  文档或 chunk ID。
- V7 的缺失/非 owner/软删除 KB `404` 和非 `ACTIVE` KB `409` 行为不变；V8 不复制一份范围检查或
  Qdrant 查询逻辑。

成功响应的 `data` 示例：

```json
{
  "query": "退款失败时应该如何排查？",
  "topK": 5,
  "maxContextTokens": 3000,
  "usedContextTokens": 83,
  "skippedChunkCount": 1,
  "context": "[S1]\nSource: refund-rules.md\nTitle: 支付 / 退款\nDocumentId: 301\nChunkId: 401\nContent:\n退款失败时，先核对支付渠道返回的错误码。",
  "sources": [
    {
      "citationId": "S1",
      "chunkId": "401",
      "documentId": "301",
      "fileName": "refund-rules.md",
      "titlePath": "支付 / 退款",
      "score": 0.91
    }
  ]
}
```

`sources` 只列出实际进入 `context` 的 chunk，并严格按 context 中的 `[S1]`、`[S2]` 顺序排列。
`titlePath` 对无 Markdown 标题的 TXT chunk 为 `""`，而不是虚构标题或省略字段。`score` 仍是 V7 的
原始 Qdrant Cosine 相似度：它只描述本次 dense retrieval 的排序，不是答案置信度。

## 2. 装配链路与职责边界

```text
KnowledgeContextController
  -> KnowledgeContextService
       -> KnowledgeRetrievalService.retrieveTest(...)
            -> EmbeddingGateway.embed(query, kb provider/model)
            -> VectorStoreGateway.search(query vector, fixed owner + KB scope)
            -> PostgreSQL canonical chunk/document re-read + vectorId verification
       -> deterministic budget assembly + [S#] source markers
```

V8 没有直接依赖 `EmbeddingGateway`、`VectorStoreGateway`、Qdrant HTTP client、LLM client 或 Prompt
类。它调用 V7 后只处理下列已验证字段：正文、chunk/document ID、来源文件名、标题路径、持久化的
`tokenCount` 和相似度分数。

为使同一 canonical retrieval 可以提供文件名和预算单位，V7 的 `RetrievedChunkResponse` 追加了
`fileName` 与 `tokenCount`；二者都由 PostgreSQL 回读，**不是** Qdrant payload。这个响应扩展不改变
V7 的 embedding、Qdrant 查询、filter、排序或 stale-vector 校验逻辑。

## 3. 确定性预算与引用规则

### 3.1 每个 context block 的固定格式

```text
[S1]
Source: refund-rules.md
Title: 支付 / 退款
DocumentId: 301
ChunkId: 401
Content:
退款失败时，先核对支付渠道返回的错误码。
```

`[S1]` 是后端按已包含 block 的顺序生成的稳定 citation ID，不由模型编造。每个包含 block 都同时产生
一个同 ID 的 `sources` 条目，因此 V9 可以把 context 交给模型，同时把来源表保留给最终答案的引用核对。

### 3.2 token 计数

V8 继续使用 V4 的 **lightweight estimated tokens**，并不伪装成 DashScope 或未来 Chat 模型的 tokenizer：

1. chunk 正文使用 V4 处理时已写入 `knowledge_chunk.token_count` 的值；这避免将来替换估算器时悄悄
   改写历史 chunk 正文的预算含义。
2. V8 对固定的 `[S#]`、来源、标题、document/chunk ID 和 `Content:` 包装使用同一个 deterministic
   `TokenEstimator` 计算开销。
3. 两者相加得到一个完整 context block 的 `usedContextTokens` 贡献；block 间只有空白分隔，在当前
   估算器下不增加 token。

因此 `usedContextTokens <= maxContextTokens` 覆盖的是 API 返回的完整 context，不只是正文片段。

### 3.3 顺序、跳过与不截断

V8 逐项遍历 V7 返回的相似度顺序，不重排、不 rerank、不做 knapsack 优化：

1. 为下一个可能包含的 chunk 预览 citation ID，例如 `S1`；
2. 计算它的完整 block 是否放得下剩余预算；
3. 放得下则原样加入 context 和 sources；放不下则完整跳过，`skippedChunkCount + 1`；
4. 继续以原顺序考察之后的 chunk。较小的低分 chunk 可以填补剩余预算，但永远不会越过任何更高分、
   已经包含的 chunk。

chunk 绝不会被字符截断、token 截断或部分混入 context。若没有任何完整 block 放得下，响应为
`context: ""`、`sources: []`、`usedContextTokens: 0`，并把每个 V7 结果计入 `skippedChunkCount`。

## 4. 数据、状态与安全边界

V8 **没有 Flyway migration**，不写 retrieval log、citation 表、评测记录或任务状态。它也不新增 embedding
模型、Qdrant collection、payload、配置或密钥。其可用前提仍然是 V5/V6 已产生可检索 chunk，且 V7 通过
两层 owner/KB 范围和 PostgreSQL 当前 `vectorId` 验证。

`context` 的正文、文件名和标题路径都来自 PostgreSQL canonical data；Qdrant 仍只负责候选排序。V8
不会接受客户端提供的 `chunkId`、文件名或 citation ID，也不会让低权限用户绕过 V7 已有的 owner scope。

## 5. IDEA HTTP 手工验收

打开：

```text
backend/http/knowledge-document.http
```

在已经完成 Login → Create knowledge base → Upload fixture → Process pending → Vectorize pending → V7
`retrieve-test` 后，按顺序运行新增的三个 V8 请求：

1. `V8 deterministic RAG context assembly`：`200 OK`；`context` 含 `[S1]`，`sources[0]` 同时带有
   `citationId`、chunk/document ID、`fileName`、`titlePath` 和数值 `score`；同时验证
   `usedContextTokens <= maxContextTokens`。
2. `V8 no-truncation boundary`：预算为 `1`；fixture 的任何完整 block 都不应进入 context，得到空
   context / 空 sources 和至少一个 skipped chunk。
3. `Invalid V8 maxContextTokens`：预算为 `0`；得到 `400 COMMON_PARAM_INVALID`，在进入 V7 前被参数
   校验拒绝。

## 6. 明确不做

- Chat/LLM 调用、Prompt、流式回答、答案生成、Agent 或 ChatGateway；
- rerank、Hybrid Search、BM25、query rewrite 或其他检索策略；
- 检索日志、回答评测、离线评测、点击反馈或异步队列；
- 新 embedding/Qdrant 配置、collection/payload 变更、向量写入或 Flyway migration；
- 对 `context` 做摘要、压缩、合并相邻 chunk 或内容截断。

V9 可以以此为输入边界：只引入一个受控 `ChatGateway`，接收 V8 的 `context` 和 `sources`，返回答案并把
答案中的 `[S#]` 与这份稳定来源表核对；它不应回头重做 V7/V8 的召回、预算或 citation 编号。

## 面试问题与回答

### 问题 1：为什么 V8 不直接调用 LLM，而要单独做 context 装配？

**回答：** 已实现的 `KnowledgeContextService` 只依赖 V7 的 canonical retrieval 和 `TokenEstimator`，不直接依赖
embedding、Qdrant、LLM 或 Prompt。V7 先完成 owner scope、PostgreSQL 正文回读和当前 `vectorId` 校验，V8 再把这些
已验证结果装配成可审计的 `context + sources`；这样 V9 只消费 `KnowledgeContextResponse`，不能悄悄重做召回或预算。
V8 本身没有模型调用、Prompt、回答或 citation 持久化，这些不应被表述为本切片的已实现生成能力。

### 问题 2：`maxContextTokens` 是怎样计算的？为什么宁可跳过也不截断 chunk？

**回答：** 每个完整 block 的预算由 V4 持久化的 `chunk.tokenCount` 加上 V8 固定 `[S#]`、文件名、标题和 ID 包装的
确定性估算开销构成，因此 `usedContextTokens` 覆盖返回的完整 context，而不仅是正文。当前使用的是 lightweight estimated
tokens，不冒充任何 provider 或未来聊天模型的 tokenizer。放不下时已实现行为是完整跳过并计入 `skippedChunkCount`，继续按
V7 原相似度顺序考察后续 chunk；无任何完整 block 可放入时返回空 context/source，绝不混入半段正文。

### 问题 3：V8 的 `[S#]` 如何保证能追溯到真实来源，而不是模型编出来的标签？

**回答：** `[S#]` 由后端只为实际进入 context 的 chunk 按包含顺序生成，同时生成同 ID、同顺序的 `sources` 条目。其
`chunkId`、`documentId`、`fileName`、`titlePath` 和 score 来自 V7 已验证的 canonical retrieval；其中正文、文件名和
tokenCount 仍以 PostgreSQL 为权威，Qdrant 不提供正文事实。客户端不能传 chunk、文件名或 citation ID，V8 也不写 citation
表；答案引用核验是后续 V9 的职责。

### 问题 4：V8 目前有哪些验收证据，又有哪些外部依赖尚不能由它单独证明？

**回答：** 现有单元/Controller 测试覆盖了固定格式、相似度顺序、预算上限、跳过而不截断，以及 `maxContextTokens` 非法时
在调用 V7 前返回 `400`；它们属于本地/mock 契约验证。`backend/http/knowledge-document.http` 还提供了成功、预算为 1 和
非法预算三条可执行的手工路径；成功路径仍依赖已经真实可检索的 V5/V6/V7 环境，不能由 V8 单独证明。V8 不调用 LLM，
因此它不能单独证明真实 Chat 服务、答案质量或线上 RAG 效果；这些均不在本切片范围。
