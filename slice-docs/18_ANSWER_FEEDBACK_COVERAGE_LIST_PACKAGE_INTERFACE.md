# AgentFlow Hub Knowledge：V17 不可变回答反馈提交状态分页索引

V17 为当前 owner、当前知识库提供不可变回答反馈的提交状态分页索引。它从已经持久化的 V10
`knowledge_chat_answer` 出发，逐条标记该回答是否已经存在 V12 `knowledge_chat_answer_feedback` 事件。
它只返回回答 ID、提交状态和回答创建时间，不返回 feedback 事件详情、回答正文或 V11 的回答摘要字段。

V17 是只读查询：不创建、重试、修改或撤销 feedback，不改写 V10 回答快照，不重新调用 V7/V8/V9、检索、
Embedding、Qdrant、`ChatGateway` 或模型。空范围、跨 owner 和跨知识库都通过同一条 scope 查询返回正常的
HTTP 200 空页，不预查资源是否存在。

## 1. HTTP 契约

~~~http
GET /api/v1/knowledge-bases/{knowledgeBaseId}/chat-answer-feedbacks/coverage-ledger?page=1&pageSize=20
Authorization: Bearer <access-token>
~~~

`AuthenticatedUser` 只能来自 JWT principal，`knowledgeBaseId` 只能来自路径。分页参数复用 common 的
`PageRequest`：缺省为 `page=1&pageSize=20`，页码小于 1 归一为 1，`pageSize` 收敛到 `[1, 100]`。
没有 request body，也不接受 `answerId`、`feedbackId`、`userId`、`verdict`、时间范围、搜索词、客户端排序、
导出或跨知识库参数。

项目统一的 `ApiResponse` 保留外层 `code`、`message`、`traceId` 和 `timestamp`。成功文案为
`Knowledge chat answer feedback coverage ledger retrieved`。`data` 复用 `PageResult` 的分页元数据；每个
`items` 元素严格只有以下三个字段：

~~~json
{
  "page": 1,
  "pageSize": 20,
  "total": 2,
  "hasNext": false,
  "items": [
    {
      "answerId": "502",
      "submitted": true,
      "answerCreatedAt": "2026-08-29T11:00:00+08:00"
    },
    {
      "answerId": "501",
      "submitted": false,
      "answerCreatedAt": "2026-08-29T10:00:00+08:00"
    }
  ]
}
~~~

`answerId` 以字符串返回；`submitted` 是布尔值；`answerCreatedAt` 是父回答的原始创建时间。
`items` 不包含 `verdict`、`feedbackId`、反馈创建时间、`answer`、`query`、`citationIds` 或 `sources`。
其中 `total` 只是当前 scope 内回答行的分页总数，不是反馈比例、质量评分或趋势结论。

## 2. Scope、排序与空结果边界

V17 以不可变 parent answer 为驱动表，并在同一条查询中固定当前 owner 与路径知识库；feedback 只用于计算
提交状态：

~~~sql
SELECT a.id AS answer_id,
       f.id IS NOT NULL AS submitted,
       a.created_at AS answer_created_at
FROM knowledge_chat_answer a
LEFT JOIN knowledge_chat_answer_feedback f ON f.answer_id = a.id
WHERE a.knowledge_base_id = #{knowledgeBaseId}
  AND a.user_id = #{userId}
ORDER BY a.created_at DESC, a.id DESC
~~~

`knowledge_chat_answer` 是 owner 与 knowledge-base 归属的权威来源；feedback 表不复制这些归属字段。
V9 的 `UNIQUE(answer_id)` 保证一个 parent answer 至多匹配一个 feedback 行，因此 `f.id IS NOT NULL` 能够
稳定映射为 `submitted=true`，没有 feedback 的回答由 `LEFT JOIN` 保留并映射为 `false`。

排序只使用 `answer.created_at DESC, answer.id DESC`，即使多个回答创建时间相同，也有稳定的回答 ID 次序。
该排序与 V11 回答审计台账相同，直接复用已有的
`idx_knowledge_chat_answer_list_owner_kb_created_id`，不新增 V17 migration 或 index。

没有匹配 parent answer 时，MyBatis-Plus 分页结果为 `total=0`、`items=[]`、`hasNext=false`，仍返回 HTTP 200。
当前知识库为空、路径知识库属于其他 owner、当前 owner 在该知识库下没有回答，或路径与回答范围不匹配，
都不预查 knowledge base、answer 或 feedback，也不返回 404 或差异化错误文案。

## 3. 包接口与只读边界

~~~text
KnowledgeChatAnswerController
  GET /chat-answer-feedbacks/coverage-ledger?page=&pageSize=
    -> KnowledgeChatAnswerFeedbackService.listCoverageLedgerOwnedByKnowledgeBase(...)
         -> KnowledgeChatAnswerFeedbackMapper.selectCoverageLedgerPageOwnedByKnowledgeBase(...)
              knowledge_chat_answer LEFT JOIN knowledge_chat_answer_feedback
              fixed owner/knowledge-base scope and answer.created_at DESC, answer.id DESC
         -> PageResult<KnowledgeChatAnswerFeedbackCoverageItemResponse>
~~~

1. `KnowledgeChatAnswerController` 只传递 JWT 当前 owner、路径 `knowledgeBaseId` 和 `PageRequest`，不绑定
   body；分页参数由 common 规则归一化。
2. `listCoverageLedgerOwnedByKnowledgeBase(...)` 是 `readOnly` transaction，只调用一个分页 Mapper statement，
   把数据库投影映射为三字段响应；它不预查资源、不调用其他 feedback 方法，也不把空范围变成异常。
3. Mapper statement 只映射 `answer_id`、`submitted` 和 `answer_created_at`。它不接收 answerId、verdict、时间
   或搜索条件，不提供 INSERT、UPDATE、DELETE、内存排序或筛选已提交项的路径。
4. Service 不依赖 `KnowledgeChatAnswerService`、`KnowledgeChatService`、`KnowledgeContextService`、V7
   retrieval、V8 context、V9、Embedding、Qdrant、`ChatGateway` 或模型配置。因此 V17 GET 不读取回答正文、
   query、citation/source 快照或预算，不重新生成回答，也不影响后续 Prompt 或检索。

## 4. 数据与查询契约

V17 只读取已有的两张不可变表：

- V7 的 `knowledge_chat_answer` 保存 `id`、`user_id`、`knowledge_base_id` 和 `created_at`，并由既有 V11
  复合索引支持 owner/知识库范围及回答创建时间、ID排序；
- V9 的 `knowledge_chat_answer_feedback` 保存 `id`、`answer_id`、`verdict` 和 `created_at`，已有 parent
  foreign key、`UNIQUE(answer_id)`、二元 verdict CHECK 及拒绝 UPDATE/DELETE 的 trigger。

内部 `KnowledgeChatAnswerFeedbackCoverageItem` 是查询投影，不是新的持久化模型；它不包含 feedbackId、
verdict 或反馈时间。API 的 `KnowledgeChatAnswerFeedbackCoverageItemResponse` 将数据库 Long ID 转成字符串，
并验证 ID、提交状态和回答创建时间存在。API 行响应为不可变 record，Jackson 不会额外序列化内部投影字段。

分页由 MyBatis-Plus `Page<KnowledgeChatAnswerFeedbackCoverageItem>` 与既有 PostgreSQL 分页拦截器完成。Service
保留数据库返回的顺序和 `total`，不在 Java 中重新排序、过滤或聚合；`total` 的单位是当前 scope 内的 V10
回答行，不是 feedback event 数量。

## 5. 实现与验收

自动化本地单元/mock 验收覆盖：

1. Mapper 注册 V17 statement，确认驱动表是 `knowledge_chat_answer a`，使用 `LEFT JOIN`，包含 owner/知识库
   谓词和 `answer.created_at DESC, answer.id DESC`，结果映射严格为三个投影字段；同时确认没有 feedback
   详情、回答正文、V11 query/citation 或写入 SQL；
2. Service 映射同时包含 `submitted=true` 与 `submitted=false` 的回答，保留固定顺序、分页参数与总数，并只
   调用 V17 Mapper statement；
3. Service 将空、跨 owner、跨知识库 scope 映射为相同的 200 空页形状，不调用资源预查、V12 写入、V13 状态、
   V14 反馈台账、V15/V16 聚合；
4. Controller 验证嵌套路由、默认/显式分页绑定、成功文案，以及每个 item 的精确三字段 JSON；V10 answer
   Service 不发生交互；
5. `backend/http/knowledge-document.http` 提供从空 scope、未提交回答、已提交回答到多回答稳定排序的手工
   HTTP 顺序。V17 GET 本身不需要模型服务；若按完整脚本创建 V10 前置回答，V10 POST 的模型前置条件仍单独
   适用。

### 手动 HTTP 验收顺序

先执行 `Login` 和 `Create a knowledge base`。V10 请求沿用现有文档处理/模型前置，仅用于准备 V17 的回答数据。

1. `V17 read the empty immutable answer-feedback coverage ledger` → HTTP `200`，`total=0`、`items=[]`。
2. `V10 one-shot grounded chat with immutable answer audit` → `V17 read the first answer's unsubmitted state` → HTTP `200`，
   一项、`submitted=false`、仅有 `answerId`/`submitted`/`answerCreatedAt`。
3. `V12 submit one immutable binary answer-feedback event` → `V17 read the first answer's submitted state` → HTTP `200`，
   同一项 `submitted=true`，不含 `verdict`、`feedbackId` 或反馈时间。
4. `V10 create a second independent immutable answer for V15/V16` →
   `V12 submit NOT_HELPFUL for the second independent immutable answer` →
   `V17 page both immutable answers by answer creation time` → HTTP `200`，`total=2`，第二个回答在前，两项均为
   `submitted=true`，且每项仍只有三个字段。
5. owner A 的 token 请求 owner B 的 KB ID（或反向组合）→ 同一 V17 GET 返回 HTTP `200` 空页；同一 owner 的
   回答只在 KB-A 时请求无回答的 KB-B → 同样返回 HTTP `200` 空页。两种情况均不先请求资源详情。

自动化测试是本地 unit/mock 与 Mapper statement 形状证据，不等同于真实外部服务、生产多租户合规、模型质量
或线上性能证明。手工 HTTP 请求验证正常运行时的响应形状和状态，不把受控数据转换成质量、满意度或训练结论。

## 6. 明确不做

- 只返回已提交项的筛选、verdict 筛选、搜索、时间窗口、客户端排序、导出或跨知识库汇总；
- 反馈 verdict、feedbackId、反馈时间、评论、标签、编辑、撤销、删除、重新提交或 PATCH/PUT/DELETE；
- 回答正文、query、citation、sources、预算详情，或替代 V10/V11/V13/V14 的其他读取职责；
- 比例、覆盖率、趋势、平均分、质量/准确率/满意度结论，或把 `submitted` 解释为模型评价；
- 训练、奖励信号、模型评测，或用 feedback 改变 Prompt、上下文装配、检索、rerank、模型选择、provider 配置
  或后续回答；
- V7/V8/V9、检索、Embedding、Qdrant、`ChatGateway`、模型调用、异步队列、缓存、物化视图或后台汇总任务；
- 新增 Flyway migration、索引、状态表、反馈写入路径、保留/删除策略或管理员修改通道。

## 面试问题与回答

### 问题 1：为什么 V17 必须从 `knowledge_chat_answer` 开始并使用 `LEFT JOIN`？

**回答：** V17 的索引单位是当前 scope 内的每一条 V10 parent answer，而不是已经提交的 feedback event。
从 feedback 表开始或使用 `INNER JOIN` 会丢失尚未提交反馈的回答，无法返回 `submitted=false`。以 parent
answer 为驱动并 `LEFT JOIN f.answer_id = a.id` 后，每个回答都保留；`f.id IS NOT NULL` 只用于推导布尔状态。
这与 V14 只列出已提交 event 的 `INNER JOIN` 是不同的边界。

### 问题 2：为什么空、跨 owner 和跨知识库要返回 200 空页，而不是 404？

**回答：** 这是范围列表，不是单条资源详情。owner 与 knowledge-base 谓词直接放在 parent answer 查询中，
分页 count 为零时自然得到 `items=[]`；Service 不预查资源，也不区分“不存在”和“存在但不属于当前 scope”。
因此这些情况共享 200 空页，避免通过状态码或文案泄露范围外回答或知识库。V10/V12/V13 的单条 404 契约不因
V17 改变。

### 问题 3：V17 为什么只允许 `answer.created_at DESC, answer.id DESC`？

**回答：** 回答创建时间是该索引的业务顺序，回答 ID 是同一时间下的稳定 tie-breaker。它与 V11 的回答审计
台账使用同一排序和同一 owner/KB 复合索引，所以分页不会依赖反馈提交时间，也不需要 Java 内存重排。反馈
`created_at` 只属于 V14/V12 的事件职责，未纳入 V17。

### 问题 4：`submitted` 能否理解为用户认为回答正确或有帮助？

**回答：** 不能。V17 的 `submitted` 只表示当前 scope 的 parent answer 是否存在一条 V12 feedback 行；它是
持久化提交状态，不返回也不解释 `HELPFUL`/`NOT_HELPFUL`。比例、质量、准确率、满意度和训练价值都未纳入本
切片，V15 的原始 verdict 计数也不能被本接口改写成这些结论。

### 问题 5：如何证明 V17 是只读索引而不会再次调用检索或模型？

**回答：** Controller 只调用 feedback Service 的分页方法；该方法是 `@Transactional(readOnly = true)`，只
构造一个 MyBatis-Plus `Page` 并调用 V17 的单条 `SELECT` Mapper，再映射三字段 record。它没有 insert/update/delete，
也没有 `KnowledgeChatAnswerService`、V7/V8/V9、检索或 `ChatGateway` 依赖。单元/mock 测试断言 V12 写入和
其他状态/台账/聚合 Mapper 零交互；这证明的是当前本地编排边界，不是外部服务 SLA 或生产性能证明。
