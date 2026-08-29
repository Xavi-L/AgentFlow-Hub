# AgentFlow Hub Knowledge：V11 不可变回答审计台账分页索引

V11 在 V10 已落库的 `knowledge_chat_answer` 不可变回答快照之上，只增加一个 owner-scoped
分页索引入口：当前所有者可以在一个当前知识库内查看回答审计摘要。它不是会话历史，也不生成、更新或
重新解释历史回答。

完整回答文本、V8 context 预算和来源快照继续使用 V10 的单条详情接口：
`GET /api/v1/knowledge-bases/{knowledgeBaseId}/chat-answers/{answerId}`。V11 列表只返回进入详情页前
所需的固定摘要。

## 1. HTTP 契约

```http
GET /api/v1/knowledge-bases/{knowledgeBaseId}/chat-answers?page=1&pageSize=20
Authorization: Bearer <access-token>
```

`page` 和 `pageSize` 使用既有 `PageRequest`：默认分别为 `1` 和 `20`；小于 `1` 的 page 按 `1`
处理；pageSize 被收敛到 `[1, 100]`。响应沿用统一 `ApiResponse<PageResult<...>>` 外壳：

```json
{
  "code": "OK",
  "data": {
    "items": [
      {
        "answerId": "501",
        "query": "退款失败时应该如何排查？",
        "citationIds": ["S1"],
        "createdAt": "2026-08-25T10:30:00+08:00"
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1,
    "hasNext": false
  }
}
```

每个 item 严格只有 `answerId`、`query`、`citationIds` 和 `createdAt`。列表不返回 `answer`、`sources`、
`topK`、`maxContextTokens`、`usedContextTokens`、`skippedChunkCount` 或 `maxAnswerTokens`；这些字段仍是
V10 单条详情快照的责任。

## 2. Scope、排序与空列表

`AuthenticatedUser` 只能来自 JWT principal，`knowledgeBaseId` 只能来自路径。V11 对
`knowledge_chat_answer` 的查询在同一条 SQL wrapper 中同时固定：

```text
user_id = currentUser.id
AND knowledge_base_id = path knowledgeBaseId
ORDER BY created_at DESC, id DESC
```

因此 owner 不能通过 query parameter 覆盖归属；另一个 owner 的知识库或另一知识库中的 row 都不满足
谓词，不能被枚举。没有匹配行时统一返回 `200` 和空 `items`（包括当前 owner 的空知识库以及不属于当前
owner 的路径），不额外查询或暴露知识库/回答是否存在。

`createdAt DESC, id DESC` 是固定顺序。`id DESC` 是同一 `createdAt` 下的确定性 tie-breaker，避免
OFFSET 分页因同秒记录而出现非确定顺序。

## 3. 包接口与读取边界

```text
KnowledgeChatAnswerController
  GET /chat-answers?page=&pageSize=
    -> KnowledgeChatAnswerService.listOwnedByKnowledgeBase(...)
         -> KnowledgeChatAnswerMapper.selectPage(...)          (V10 table only)
         -> citation_ids_json -> List<String>
         -> KnowledgeChatAnswerSummaryResponse
```

`KnowledgeChatAnswerService` 的 V10 `chat(...)` 仍是唯一会调用 V9 的生成路径；V11 的
`listOwnedByKnowledgeBase(...)` 是 `@Transactional(readOnly = true)`，不调用 `KnowledgeChatService`。
它也不依赖 `KnowledgeContextService`、V7 retrieval、Embedding/Qdrant、`ChatGateway` 或模型配置。

读取投影仅选择 `id`、`query`、`citation_ids_json` 和 `created_at`。`citationIds` 从已冻结的
`citation_ids_json` 反序列化；不读 `sources_snapshot_json`，更不会根据当前数据重新生成 citation 或
来源。

## 4. 数据与迁移契约

Flyway 新增且仅新增以下索引迁移：

```text
V8__add_knowledge_chat_answer_list_index.sql
```

它创建：

```sql
CREATE INDEX idx_knowledge_chat_answer_list_owner_kb_created_id
    ON knowledge_chat_answer (user_id, knowledge_base_id, created_at DESC, id DESC);
```

索引列顺序先匹配 V11 的 owner 和知识库等值谓词，再匹配固定时间/ID 排序。该迁移不创建会话或消息表，不改写
V10 行、快照、trigger 或 JSON 数据，也不为 V10 单条详情、V7/V8 检索或模型调用新增索引用途。

## 5. 实现与验收

自动化单元/mock 验收覆盖：

1. 查询 wrapper 同时带 `user_id = current owner` 与 `knowledge_base_id = path`，且投影只包含四个
   V11 摘要字段；
2. 分页请求的页号、页大小、总数和 `hasNext` 被保留；同一 `createdAt` 的结果以 `id DESC` 作为稳定
   次序；
3. 没有匹配行时返回空分页，而不是调用生成路径；
4. 列表读取验证 `KnowledgeChatService` 零交互，因而不会触发 V9 及其下游 V8/模型路径；
5. Controller 验证嵌套列表路由、分页参数、统一分页元数据，以及 JSON 中没有完整 answer 或 sources。

手工 HTTP 验收顺序是：Login -> Create KB -> Upload -> Process -> Vectorize -> V7 -> V8 -> V10 POST
`/chat`（仅在本地 OpenAI-compatible 配置已就绪时，且会进行一次真实模型调用）-> V10 单条 GET -> V11
列表 GET。最后一步只读取 PostgreSQL 的既有审计行；自动化 mock 测试证明本地编排和无 V9 调用边界，不能替代
真实模型质量、远端服务 SLA 或线上审计合规证明。

## 6. 明确不做

- 会话、多轮上下文、message/role 表或会话历史；
- 跨知识库聚合、全文检索、筛选、排序参数或游标协议；
- 重新检索、重新装配 V8 context、调用模型、修改任何回答快照；
- 删除/保留策略、法务删除、反馈、评测、审计导出；
- 流式、异步队列、重试、Agent/tool calling、Prompt 或 provider 配置。

## 面试问题与回答

### 问题 1：为什么 V11 是审计台账，而不是聊天会话历史？

**回答：** 已实现的 V11 只有 `knowledge_chat_answer` 的 owner-scoped 分页读取，并且每项仅含
`answerId`、query、citationIds 和 createdAt。它没有 `message`、`role`、parent answer 或 session 表，也不把
前一项带入下一次回答；所以它只能帮助定位一条已经冻结的单次回答，不能被表述为多轮上下文能力。

### 问题 2：列表如何避免意外泄露完整回答或当前来源？

**回答：** `listOwnedByKnowledgeBase` 的 SQL 投影只选 id、query、citation_ids_json 和 created_at，并映射到
`KnowledgeChatAnswerSummaryResponse`。它不选 answer、sources_snapshot_json 或预算列；客户端若需要完整的
冻结答案与来源，必须带 `answerId` 走 V10 单条详情。V11 也不会按当前知识库内容重建 sources，因此不会把当前
检索误称为历史审计来源。

### 问题 3：owner 或跨知识库的枚举是如何被阻止的？

**回答：** owner 只取 JWT principal，分页查询同时附加 user_id 和 knowledge_base_id，不能由客户端 body 或
query 参数指定 userId。其他 owner 或另一个知识库的行不会命中；没有命中的结果统一是空分页，不区分“知识库
不存在”“不属于当前 owner”或“没有回答”。这证明当前本地 Service/Mapper 的查询范围，不代表外部渗透测试或
多租户合规认证已经完成。

### 问题 4：为什么要把 `id DESC` 放进同时间排序和索引？

**回答：** createdAt 可能在同一时间精度内相同，只用时间排序会让 OFFSET 页的行序不确定。V11 固定
`created_at DESC, id DESC`，并让新索引按 owner、知识库、created_at、id 的同一顺序排列；单元测试覆盖同时间
记录的 ID tie-breaker。该索引仅改善这个列表查询的范围和顺序，不增加重新检索或修改历史记录的能力。

### 问题 5：怎样证明 V11 列表不会调用 V8、V9 或模型？

**回答：** 该方法只调用 `KnowledgeChatAnswerMapper.selectPage` 并反序列化持久化的 citationIds；测试验证
`KnowledgeChatService` 为零交互。V9 是本项目通向 V8 和 ChatGateway 的生成入口，所以这个验证证明本地列表
编排不进入 V9/V8/模型路径；它不是对真实部署网络、数据库权限或模型服务的外部运行时认证。
