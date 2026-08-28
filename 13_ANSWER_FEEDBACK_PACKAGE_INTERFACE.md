# AgentFlow Hub Knowledge：V12 不可变回答二元用户反馈

V12 只为一条已冻结的 V10 `knowledge_chat_answer` 追加一次二元用户判断：当前 owner 可以对当前
知识库中的当前 `answerId` 提交 `HELPFUL` 或 `NOT_HELPFUL`。反馈是一个独立、不可修改的事件；它不改写
回答文本、V8/V9 预算、citationIds 或 sources 快照，也不会重新解释该回答。

同一 answer 对相同 verdict 的重复 `POST` 是幂等重试：返回已有事件，不再写入第二行。若已有事件的
verdict 相反，服务返回 `409`，而不是把历史反馈改成新的判断。V12 不把这条主观反馈提升为模型质量、训练
标签或检索正确性的结论。

## 1. HTTP 契约

```http
POST /api/v1/knowledge-bases/{knowledgeBaseId}/chat-answers/{answerId}/feedback
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "verdict": "HELPFUL"
}
```

请求体严格只有 `verdict` 一个文本字段，且值严格为大写枚举 `HELPFUL` 或 `NOT_HELPFUL`。缺失或
`null` verdict 由 Bean Validation 返回 `400 COMMON_PARAM_INVALID`；未知字段、非文本 verdict 或未知枚举值
在反序列化时返回 `400 COMMON_REQUEST_BODY_INVALID`。客户端不能在 body 中指定 `feedbackId`、`answerId`、
`knowledgeBaseId`、`userId`、时间戳或评论内容。

首次提交与相同 verdict 的幂等重试均返回 `200 OK`，统一使用
`ApiResponse<KnowledgeChatAnswerFeedbackResponse>` 外壳：

```json
{
  "code": "OK",
  "message": "Knowledge chat answer feedback recorded",
  "data": {
    "feedbackId": "701",
    "answerId": "501",
    "verdict": "HELPFUL",
    "createdAt": "2026-08-27T10:30:00+08:00"
  }
}
```

重试返回的四个 `data` 字段都来自原有事件，尤其 `feedbackId` 和 `createdAt` 不会改变；响应中没有
“改写”“撤销”“第二条反馈”或模型评分类字段。

## 2. Scope、幂等与失败边界

`AuthenticatedUser` 只来自 JWT principal，`knowledgeBaseId` 和 `answerId` 只来自路径。feedback 的每一次
读取和写入都通过其父 V10 answer 绑定下面的同一范围；没有独立的全局 answer 预检查或无 scope 的 feedback 查询：

```text
id = path answerId
AND knowledge_base_id = path knowledgeBaseId
AND user_id = currentUser.id
```

记录不存在、属于其他 owner，或位于另一个知识库时，都返回
`404 KNOWLEDGE_CHAT_ANSWER_NOT_FOUND`。反馈读取使用 `knowledge_chat_answer_feedback` 与
`knowledge_chat_answer` 的 scoped JOIN，写入使用同一谓词的 `INSERT ... SELECT`，因此不能借由
`answerId` 探测其他 owner 是否已经给出反馈。

服务先执行 scoped feedback JOIN；若没有已存在事件，则以当前请求的 verdict 执行原子 scoped insert：

1. 已有同 verdict 时直接返回该行，不执行 update 或第二次 insert；
2. 已有相反 verdict 时返回 `409 KNOWLEDGE_CHAT_ANSWER_FEEDBACK_CONFLICT`，默认文案为
   `Knowledge chat answer feedback has already been recorded with a different verdict`；
3. 没有已有事件时，`INSERT ... SELECT FROM knowledge_chat_answer ... ON CONFLICT (answer_id) DO NOTHING`
   只有在同一 owner/知识库范围内找到目标 answer 时才写入一行；
4. insert 影响零行时，服务以同一 scoped JOIN 重新读取：查到同 verdict 返回 `200`，查到相反 verdict 返回
   上述 `409`，仍查不到则返回上述 `404`。这同时覆盖目标回答不在 scope 和并发首次提交，不会产生第二条反馈或
   把数据库重复键错误泄露为通用冲突。

因此，V12 的“不可修改”不是客户端约定：API 没有 PATCH/PUT/DELETE 路由，且相反判断明确不是一次更新操作。

## 3. 包接口与执行边界

```text
KnowledgeChatAnswerController
  POST /chat-answers/{answerId}/feedback
    -> KnowledgeChatAnswerFeedbackService.submitFeedback(...)
         -> KnowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(...) (feedback JOIN scoped answer)
         -> INSERT ... SELECT FROM scoped answer ... ON CONFLICT (answer_id) DO NOTHING
         -> KnowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(...) (zero-row re-read)
         -> KnowledgeChatAnswerFeedbackResponse
```

1. `KnowledgeChatAnswerController` 的 V12 endpoint 仅传递已认证 owner、两个路径 ID 和经过严格反序列化/校验的
   `KnowledgeChatAnswerFeedbackRequest`；不接受服务端归属或事件 ID。
2. `submitFeedback(...)` 不先做独立 `exists` 查询。它通过 `selectOwnedByAnswerId(...)` 的 answer JOIN 和
   `insertIfAbsent(...)` 的 `INSERT ... SELECT`，让每一次读写都带上 answer、knowledge base 和 JWT owner；
   scoped re-read 仍为空时统一为 `KNOWLEDGE_CHAT_ANSWER_NOT_FOUND`。
3. `KnowledgeChatAnswerFeedbackMapper` 只读取或 append `knowledge_chat_answer_feedback`，且读取一律 JOIN
   scoped answer、写入一律 SELECT scoped answer；它不提供更新、删除、列表或统计查询。
4. 该 Service 不依赖 `KnowledgeChatService`、`KnowledgeContextService`、V7 retrieval、Embedding/Qdrant、
   `ChatGateway` 或模型配置。一次反馈请求只做 owner-scoped 回答读取和 feedback 事件读取/写入；不会重新检索、
   生成回答或影响下一次请求的 Prompt。

## 4. 数据与迁移契约

V12 是功能切片编号；其对应的下一条 Flyway schema 版本是：

```text
V9__create_knowledge_chat_answer_feedback.sql
```

它新建独立的 `knowledge_chat_answer_feedback`，而不是修改 V7 的
`knowledge_chat_answer`：

| 字段 | 约束与含义 |
| --- | --- |
| `id` | feedback 事件 ID，作为 API 的 `feedbackId`。 |
| `answer_id` | `NOT NULL UNIQUE`，外键引用 `knowledge_chat_answer(id)`；一条回答最多一行反馈。 |
| `verdict` | `NOT NULL`，数据库 CHECK 只允许 `HELPFUL` 或 `NOT_HELPFUL`。 |
| `created_at` | feedback 首次提交的时间；同 verdict 重试返回它，不更新它。 |

feedback 表不复制 `user_id`、`knowledge_base_id`、回答文本、预算、sources 或 citationIds；owner/知识库范围
始终由其父 `knowledge_chat_answer` 的 scoped JOIN/INSERT SELECT 判定。它没有 `updated_at`、软删除或评论列。Flyway 同时安装
`BEFORE UPDATE OR DELETE` trigger，拒绝普通应用连接的直接修改/删除。V10 回答表及其预算、sources 快照和
不可变 trigger 均不修改。

## 5. 实现与验收

自动化单元/mock 验收覆盖：

1. Controller 只接受嵌套 POST 路由和严格的一字段 body；多余字段、无效枚举、非文本值、缺失/`null` verdict
   分别保持 `400` 请求契约；
2. Mapper 的 feedback 读取一律 JOIN `answerId + knowledgeBaseId + current owner`，写入一律从同范围 answer
   `INSERT ... SELECT`；不存在、跨 owner 与跨知识库在 scoped re-read 为空时均为
   `404 KNOWLEDGE_CHAT_ANSWER_NOT_FOUND`，没有全局 feedback 查找；
3. 首次 `HELPFUL` 或 `NOT_HELPFUL` 写入一条独立 feedback，并按 `feedbackId`、`answerId`、verdict、
   `createdAt` 返回；
4. 相同 verdict 重试返回同一事件，不执行第二次写入；相反 verdict 返回
   `409 KNOWLEDGE_CHAT_ANSWER_FEEDBACK_CONFLICT`，没有 update；
5. `ON CONFLICT DO NOTHING` 的零影响行分支会 scoped re-read：既有事件按其 verdict 返回同一 `200` 或受控
   `409`，仍无事件则返回 `404`；
6. 反馈 Service 验证 `KnowledgeChatService` 为零交互，证明本地编排不会进入 V8/V9、检索或模型路径。

`backend/http/knowledge-document.http` 的手工验收顺序是：Login -> Create KB -> Upload -> Process -> Vectorize
-> V7 -> V8 -> V10 POST `/chat` -> V12 `HELPFUL` POST -> 同 verdict 重试 -> 反向 `NOT_HELPFUL` POST。若用
已有的、当前 owner/当前知识库的 `agentflowChatAnswerId`，后三步本身不需要模型服务；从空环境开始创建该 answer
时，前置 V10 POST 仍会通过 V9 发起一次真实模型调用。HTTP 客户端断言可证明本地 API 的响应和幂等边界，自动化
mock 测试可证明本地 Service 编排；二者都不构成真实模型质量、外部服务 SLA、训练效果或线上多租户合规认证。

## 6. 明确不做

- 自由文本评论、标签扩展或任何除二元 `verdict` 外的反馈内容；
- 修改、撤销、重投不同 verdict，或 feedback 的 PATCH/PUT/DELETE 路由；
- 反馈列表、筛选、分页、聚合、统计面板或审计导出；
- 把反馈用作模型评测、训练数据、奖励信号或质量结论；
- 用反馈影响 Prompt、上下文装配、检索、rerank、模型选择或下一次回答；
- 会话、message/role、多轮上下文、流式/SSE、异步队列或后台处理；
- 保留/删除策略、法务删除、归档生命周期或管理员修改通道。

## 面试问题与回答

### 问题 1：为什么相同 verdict 的重复 POST 返回 200，而不是再次创建一条反馈？

**回答：** V12 的业务单位是“每条不可变回答的一次二元判断”，表上的 `UNIQUE (answer_id)` 将这个上限交给
数据库强制执行。Service 对已有同 verdict 返回已有 `feedbackId` 和原始 `createdAt`，因此网络重试不会制造
第二条事件。并发时 `INSERT ... ON CONFLICT DO NOTHING` 后重新读取也是同一规则；单元/mock 测试验证本地
分支，不能把它表述为已完成线上数据库容量或可用性验证。

### 问题 2：如何同时做到 owner scope 与“不泄露是否已经反馈过”？

**回答：** 每次 feedback 读取都用 `knowledge_chat_answer_feedback JOIN knowledge_chat_answer` 绑定
`answerId`、路径 `knowledgeBaseId` 和 JWT owner；首次写入也是从同范围 answer 的 `INSERT ... SELECT` 完成，
没有独立的全局 answer 预检查或无 scope feedback lookup。零影响行后仍以同范围重读，空结果统一为
`404 KNOWLEDGE_CHAT_ANSWER_NOT_FOUND`。因此跨 owner 或跨知识库的请求不会得到“已有反馈”“相反 verdict”之类的
差异。这是已实现的本地 Mapper/Service 查询边界，不是外部渗透测试或多租户合规认证。

### 问题 3：为什么反向 verdict 必须返回 409，而不是允许用户更正？

**回答：** 本切片把 feedback 定义为一次不可变事件，而不是可编辑评分。已有 `HELPFUL` 后提交
`NOT_HELPFUL`（或反向）会得到 `KNOWLEDGE_CHAT_ANSWER_FEEDBACK_CONFLICT`，数据库 trigger 同时拒绝直接
UPDATE/DELETE。修改、撤销和重做反馈是明确排除项；若未来需要，必须新建有审计语义的独立切片，不能悄悄改变
这一历史记录。

### 问题 4：反馈会不会重新检索、调用模型，或让模型“学会”用户偏好？

**回答：** 不会。V12 只进行 owner-scoped V10 answer 查询和 feedback 行的读取/append，Service 不依赖
`KnowledgeChatService`、V8 context、V7 retrieval、Qdrant 或 `ChatGateway`；测试验证生成服务为零交互。
这证明本地代码不会在反馈请求中触发模型，但不等于反馈已成为模型评测、训练或线上学习机制——这些能力均未纳入
本切片。

### 问题 5：为什么 feedback 表不复制回答的来源、预算和 owner/知识库字段？

**回答：** 这些事实的权威记录仍是 V10 的不可变 `knowledge_chat_answer`。V12 以 `answer_id` 外键关联它，并在
读写时通过父回答的 owner/知识库谓词验证访问范围；这样不会把 feedback 扩张为第二份回答快照或产生两份可能
漂移的 budget/source 数据。该设计也意味着 V12 没有反馈列表或跨知识库统计能力，二者都被明确排除。
