# AgentFlow Hub Knowledge：V10 单次回答不可变审计记录与查询

V10 承接 V9 已完成的单次、可追溯回答，但只增加一个持久化边界：当且仅当 V9 已从 V8
`KnowledgeContextResponse` 生成回答并完成 `[S#]` citation 校验后，V10 才把回答、V8 预算、有效
`citationIds` 与 V8 `sources` 冻结为一条不可变记录。后续按 `answerId` 读取时只读取该记录，绝不
重新检索、重组 context 或调用模型。

`/chat-test` 仍是 V9 的不落库验证接口；V10 新增的 `/chat` 是有审计记录的正式单次回答入口。这一
区分使既有 V9 mock/本地模型验证保持可用，同时不把 V10 扩展为会话系统。

## 1. HTTP 契约

### 1.1 生成并保存一条回答

```http
POST /api/v1/knowledge-bases/{knowledgeBaseId}/chat
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "query": "退款失败时应该如何排查？",
  "topK": 5,
  "maxContextTokens": 3000,
  "maxAnswerTokens": 512
}
```

请求与 V9 的 `chat-test` 使用同一个局部严格四字段白名单：只允许 `query`、可选 `topK`、必填
`maxContextTokens`、必填 `maxAnswerTokens`。`model`、`prompt`、`chunkId`、`citationId` 及所有其他
字段在进入 Service 前返回 `400 COMMON_REQUEST_BODY_INVALID`。客户端不能控制模型、Prompt、chunk
或 citation。

成功时返回 `200 OK`；`data` 包含已保存的 `answerId` 以及这条审计快照：

```json
{
  "answerId": "501",
  "answer": "先核对支付渠道返回的错误码，再按错误码处理。[S1]",
  "query": "退款失败时应该如何排查？",
  "topK": 5,
  "maxContextTokens": 3000,
  "usedContextTokens": 83,
  "skippedChunkCount": 1,
  "maxAnswerTokens": 512,
  "sources": [
    {
      "citationId": "S1",
      "chunkId": "401",
      "documentId": "301",
      "fileName": "refund-rules.md",
      "titlePath": "支付 / 退款",
      "score": 0.91
    }
  ],
  "citationIds": ["S1"],
  "createdAt": "2026-08-25T10:30:00+08:00"
}
```

`query`、`topK`、`maxContextTokens`、`usedContextTokens`、`skippedChunkCount` 与 `sources` 都是同一份
V8 response 的值；`maxAnswerTokens` 是 V9 已验证的单次上游输出预算；`citationIds` 是 V9 服务端
从 answer 中按首次出现顺序去重后的有效 ID。V10 不新增任何 citation 解析或来源推断。

### 1.2 查询一条冻结记录

```http
GET /api/v1/knowledge-bases/{knowledgeBaseId}/chat-answers/{answerId}
Authorization: Bearer <access-token>
```

成功响应与 POST 的 `data` 结构一致，且所有 `answer`、预算、`sources`、`citationIds` 与 `createdAt`
均来自持久化记录。这个 GET 不接收 query、预算或模型参数，也不读取 V7/V8、Qdrant 或 ChatGateway。

查询 SQL 在同一谓词中限制 `answerId`、`knowledgeBaseId` 和 JWT owner。记录不存在、属于其他 owner、
或位于另一个知识库时都统一返回 `404 KNOWLEDGE_CHAT_ANSWER_NOT_FOUND`，不泄露其他用户是否持有该
`answerId`。

## 2. 包接口与依赖链

```text
KnowledgeChatAnswerController
  POST /chat
    -> KnowledgeChatAnswerService.chat(...)
         -> KnowledgeChatService.chatTest(...)                 (V9)
              -> KnowledgeContextService.retrieveContextTest(...) (V8)
              -> ChatGateway.generate(...)
              -> CitationReferenceExtractor
         -> KnowledgeChatAnswerMapper.insert(...)              (V10 append only)

  GET /chat-answers/{answerId}
    -> KnowledgeChatAnswerService.getById(...)
         -> KnowledgeChatAnswerMapper.selectOwnedById(...)
```

1. `KnowledgeChatAnswerController` 从 `AuthenticationPrincipal` 取得 owner；JSON 与路径均不能指定或覆盖
   `userId`。
2. `KnowledgeChatAnswerService.chat` 的唯一生成依赖是既有 `KnowledgeChatService.chatTest`。它不 import
   或调用 V7、Embedding、`VectorStoreGateway`、Qdrant 或 V8 的内部装配逻辑。
3. V9 成功返回后，V10 将其字段序列化为 `KnowledgeChatAnswer`；在 `insert` 之前没有审计行。
4. `KnowledgeChatAnswerMapper` 的读取语句把 `id`、`knowledge_base_id` 与 `user_id` 放在一条 SQL
   `WHERE` 中，避免先按全局 ID 取出记录再在 Java 内检查归属。
5. `getById` 仅反序列化 row 内的 `sourcesSnapshotJson` 和 `citationIdsJson`。它没有
   `KnowledgeChatService`、V8/V7 或 Gateway 调用，因此返回的是历史快照而不是当前检索结果。

## 3. 数据契约与不可变性

Flyway `V7__create_knowledge_chat_answer.sql` 新建 `knowledge_chat_answer`：

| 字段组 | 持久化值 | 作用 |
| --- | --- | --- |
| 归属 | `id`、`user_id`、`knowledge_base_id` | `id` 是 `answerId`；复合外键保证知识库和 owner 匹配。 |
| 回答 | `query`、`answer`、`created_at` | 保存当时的规范化 query、通过 V9 校验的 answer 和创建时间。 |
| V8/V9 统计 | `top_k`、`max_context_tokens`、`used_context_tokens`、`skipped_chunk_count`、`max_answer_tokens` | 保留 V8 context 预算与单次回答预算，不重新估算。 |
| 来源快照 | `sources_snapshot_json`、`citation_ids_json` | 分别是非空 JSON array 形式的 V8 sources 和已验证 citationIds，数据库约束拒绝空、畸形或非数组快照。 |

该表没有 `updated_at` 或软删除字段；应用层没有 update/delete Service 或 HTTP 路由。Flyway 同时安装
`BEFORE UPDATE OR DELETE` trigger，普通应用连接若尝试修改或删除该表行会被拒绝。因此后续文档重处理、
向量重建、检索排序变化或模型配置变化都不会改写历史回答的来源表。保留策略、法务删除或审计导出若有
需要，必须作为后续切片显式设计，不能绕过这条不可变契约。

V10 不保存 V8 完整 context、原始 provider 请求/响应或检索日志：这些都未列入本切片要求，且保存它们会
把本切片扩张为 Prompt/运行时观测系统。

## 4. 成功与失败边界

1. V8 没有可用 source/context 时，V9 返回 `409 KNOWLEDGE_CONTEXT_EMPTY`，V10 不执行 insert。
2. Gateway 不可用时，V9 返回 `503 KNOWLEDGE_CHAT_GATEWAY_UNAVAILABLE`，V10 不执行 insert。
3. 缺失、未知或畸形 citation 时，V9 返回 `502 KNOWLEDGE_CHAT_CITATION_INVALID`，V10 不执行 insert。
4. 只有 V9 已返回合法 `KnowledgeChatResponse` 时，V10 才写一行；插入影响行数不是 1 或持久化抛出
   运行时异常时，事务不会提交半条审计记录，客户端只收到受控的服务端失败，而不是伪造 `answerId`。
5. 已冻结的 answer 查询不重新判断当前的知识库是否仍 ACTIVE，也不重新验证 citation；它验证的是
   “当时通过 V9 的结果”，不是“按当前数据重新生成的结果”。读取仍要求原 owner 和原 knowledgeBaseId。

## 5. 实现与验收

自动化单元/mock 验收覆盖：

1. V9 返回的 answer、query、预算、sources 和有效 citationIds 被逐项冻结，POST 返回 MyBatis-Plus
   分配的 `answerId`；
2. V9 citation 失败时 Mapper 没有任何交互，证明失败不落库；
3. GET 从被持久化的 JSON 快照恢复历史 `sources`，并验证没有调用 V9；
4. Mapper 的查询语句同时包含 `answerId`、知识库和 owner scope；未命中统一映射为 404；
5. Controller 覆盖 POST `/chat`、GET `/chat-answers/{answerId}`、严格四字段拒绝和 404 HTTP 合约。

在 `backend/http/knowledge-document.http` 中，按 Login -> Create KB -> Upload -> Process -> Vectorize ->
V7 -> V8 -> **V10 POST `/chat`** -> **V10 GET** 的顺序执行。POST 仍是一次真实模型调用，只有本地
OpenAI-compatible 服务和 `OPENAI_*` 配置已经就绪时才应运行；GET 使用刚保存的
`agentflowChatAnswerId`，不需要也不会再调用模型。上述自动化 mock 测试验证编排与持久化契约，不代表
真实模型质量、外部服务 SLA 或线上审计合规认证。

## 6. 明确不做

- 多轮历史、会话表、流式/SSE；
- 重试、Agent/tool calling、Prompt 管理、模型选择或 provider 配置；
- 检索日志、rerank、V7/V8/Qdrant/Embedding 改动，或重新检索、重新调用模型；
- 用户反馈、评测、异步队列、保留/删除策略或审计导出；
- 保存完整 context、provider 原始请求/响应，或把模型自由文本升级为新的来源事实。

## 面试问题与回答

### 问题 1：V10 为什么复用 V9，而不是自己重新调用 V8、模型和 citation 校验？

**回答：** 已实现的 `KnowledgeChatAnswerService.chat` 只把请求交给 `KnowledgeChatService.chatTest`，并且只在
后者成功返回 `KnowledgeChatResponse` 后插入审计行。V9 已负责 V8 context 的原样传递、模型调用和 `[S#]`
校验；V10 若再次访问 V8/V7/Qdrant 或自行解析 citation，就会产生两套检索/来源权威边界。V10 的职责仅是冻结
V9 已证实的结果，不纳入第二次生成、重试或重新检索。

### 问题 2：怎样证明查询到的是历史来源快照，而不是当前检索结果？

**回答：** `knowledge_chat_answer` 存储 `sources_snapshot_json` 与 `citation_ids_json`，GET 只经
`KnowledgeChatAnswerMapper.selectOwnedById` 读取该行并反序列化这两个 JSON array。对应单元测试放入一个
`historical-refund-rules.md` 快照并验证 GET 原样返回它，同时验证 V9 没有交互；因此文档重处理、向量重建或
当前检索排序均不会改变这个 response。本切片不保存完整 context 或检索日志，那些信息不能被错误地宣称为已审计。

### 问题 3：owner scope 如何防止 `answerId` 被猜测后读取他人的回答？

**回答：** Controller 的 owner 仅来自 JWT principal，Mapper 的 SQL 同时条件化 `id`、`knowledge_base_id` 和
`user_id`。不命中时一律抛出 `404 KNOWLEDGE_CHAT_ANSWER_NOT_FOUND`，不会先返回全局记录再暴露“属于另一个用户”的
差异。该行为已经有 Service 和 Controller 的 mock 测试；这证明本地代码路径的 scope 契约，不代表已经完成外部
渗透测试或多租户合规认证。

### 问题 4：不可变性是如何实现的，哪些修改能力仍未纳入？

**回答：** V10 模型没有 `updatedAt`/软删除字段，Service 和 HTTP 没有 update/delete 入口，Flyway V7 还设置
`BEFORE UPDATE OR DELETE` trigger 拒绝普通应用连接的直接 DML。这样成功回答的文本、预算与来源快照不能被后续业务
流程悄悄改写；但保留策略、法务删除、管理员审计导出和迁移期间的受控维护均未纳入本切片，不能据此宣称已经实现完整
审计治理或合规生命周期。
