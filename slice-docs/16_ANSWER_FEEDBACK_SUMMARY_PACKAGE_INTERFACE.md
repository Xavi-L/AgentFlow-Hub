# AgentFlow Hub Knowledge：V15 不可变回答反馈原始计数概览

V15 为当前 owner、当前知识库提供已经提交的 V12 不可变回答反馈原始事件计数。它只报告三个
计数：已提交 feedback 事件总数、`HELPFUL` 事件数和 `NOT_HELPFUL` 事件数。该概览是一次
owner/知识库范围内的只读数据库聚合；它不是模型质量、准确率、覆盖率、趋势、训练标签或评测结论。

V15 不创建、重试、修改或撤销 feedback，不改写 V10 回答快照，也不改变 V11 回答台账、V12
提交/409 契约、V13 单条状态查询或 V14 分页反馈台账。每条已提交 V12 feedback 仍是独立的
不可变事件；本接口只在当前请求范围内对既有事件计数。

## 1. HTTP 契约

~~~http
GET /api/v1/knowledge-bases/{knowledgeBaseId}/chat-answer-feedbacks/summary
Authorization: Bearer <access-token>
~~~

项目统一的 `ApiResponse` 保留外层 `code`、`message`、`traceId` 和 `timestamp`。V15 的 `data`
严格只有下列三个数值字段：

~~~json
{
  "submittedCount": 2,
  "helpfulCount": 1,
  "notHelpfulCount": 1
}
~~~

`AuthenticatedUser` 只能来自 JWT principal，`knowledgeBaseId` 只能来自路径。接口没有 request
body，也不接受 `answerId`、`feedbackId`、`userId`、`verdict`、`page`、`pageSize`、时间范围、排序或
导出参数。成功响应文案为 `Knowledge chat answer feedback summary retrieved`。

三个字段均是非负的原始事件数，并保持：

~~~text
submittedCount = helpfulCount + notHelpfulCount
~~~

`submittedCount` 统计当前范围内所有已提交 V12 feedback 行；另外两个字段按保存的二元 verdict 分别
统计。它们不表示未反馈回答数、比例、覆盖率、平均分、质量、准确率、训练信号或用户意图之外的结论。

## 2. Scope、空结果与可见性边界

V15 从 feedback event 开始，通过不可变 parent answer 在同一 SQL 中固定当前 owner 和当前知识库：

~~~sql
SELECT COUNT(*) AS submitted_count,
       COUNT(*) FILTER (WHERE f.verdict = 'HELPFUL') AS helpful_count,
       COUNT(*) FILTER (WHERE f.verdict = 'NOT_HELPFUL') AS not_helpful_count
FROM knowledge_chat_answer_feedback f
INNER JOIN knowledge_chat_answer a ON a.id = f.answer_id
WHERE a.knowledge_base_id = #{knowledgeBaseId}
  AND a.user_id = #{userId}
~~~

`knowledge_chat_answer` 是 `user_id` 与 `knowledge_base_id` 的权威来源；feedback 表不复制这些
可能漂移的归属字段。由于是 `INNER JOIN`，只有已经存在且其 parent answer 位于当前范围内的 V12
事件可以参与计数。V9 的 `UNIQUE(answer_id)` 和 `CHECK (verdict IN ('HELPFUL', 'NOT_HELPFUL'))`
保证一条回答至多贡献一条、且只能贡献两个 verdict 计数中的一项，因此三个原始计数保持上述等式。

聚合在零匹配行时仍返回一行三个零值。于是当前范围为空、路径知识库属于其他 owner，或 feedback 的
parent answer 属于另一个知识库时，全部返回 HTTP 200 与 `0/0/0`。V15 不预查 knowledge base、answer
或 feedback 是否存在，也不返回 404、V12 的 409 或差异化文案，因此不能借由此 endpoint 区分范围外资源的
存在性。

V13 是单条 parent answer 的状态读取，必须用 `LEFT JOIN` 来区分“scope 内但未反馈”与“answer 不在
scope”；V15 的目标是已提交事件的范围聚合，故使用 `INNER JOIN`，不会统计未反馈回答。

## 3. 包接口与只读边界

~~~text
KnowledgeChatAnswerController
  GET /chat-answer-feedbacks/summary
    -> KnowledgeChatAnswerFeedbackService.getSummaryOwnedByKnowledgeBase(...)
         -> KnowledgeChatAnswerFeedbackMapper.selectSummaryOwnedByKnowledgeBase(...)
              feedback INNER JOIN scoped parent answer
         -> KnowledgeChatAnswerFeedbackSummaryResponse
~~~

1. `KnowledgeChatAnswerController` 只传递 JWT 当前 owner 和路径 `knowledgeBaseId`，不绑定 body 或
   `PageRequest`，并在统一响应外壳的 `data` 内返回三个字段。
2. `getSummaryOwnedByKnowledgeBase(...)` 是 `readOnly` transaction，只依赖 feedback Mapper 的一条
   聚合 SELECT；它不预检查知识库或回答，并将聚合的单行投影转换为窄响应。
3. `KnowledgeChatAnswerFeedbackMapper` 的 V15 statement 不接受 `answerId` 或 verdict 筛选条件，也不
   提供 INSERT、UPDATE、DELETE、分页、排序、缓存或内存重排路径。
4. 该 Service 不依赖 `KnowledgeChatAnswerService`、`KnowledgeChatService`、`KnowledgeContextService`、
   V7 retrieval、V8 context、V9、Embedding、Qdrant、`ChatGateway` 或模型配置。因此 V15 GET 不读取
   回答正文、sources/citation 快照或预算，不重新检索、生成回答或影响 Prompt。

## 4. 数据与查询契约

V15 不新增 Flyway migration 或索引，只读取既有的两张不可变表：

- V7 的 `knowledge_chat_answer` 保存 immutable parent answer 和其 owner/knowledge-base 归属；
- V9 的 `knowledge_chat_answer_feedback` 保存 `id`、`answer_id`、`verdict` 与 `created_at`，已有
  `UNIQUE(answer_id)`、二元 verdict CHECK、parent foreign key，以及拒绝 UPDATE/DELETE 的 trigger。

Mapper 将三个 PostgreSQL `COUNT` 聚合显式映射为内部的 `Long` 投影，再转换为 API 的三个 `long`
字段。没有 `GROUP BY`，故一个 owner/knowledge-base scope 每次最多返回一个概览；没有时间、verdict 或
answer 条件，故该概览始终覆盖当前范围内的全部已提交事件。V15 不创建 status 表、物化视图、缓存、
后台汇总任务或新的索引。

## 5. 实现与验收

自动化本地单元/mock 验收覆盖：

1. Mapper 注册的 V15 statement 以 `knowledge_chat_answer_feedback` INNER JOIN parent answer，包含
   parent `knowledgeBaseId` 与当前 owner 谓词、三个 `COUNT` 投影；不包含 `LEFT JOIN`、分页、answer
   快照字段、时间/verdict 筛选或写入 SQL；
2. Service 分别映射 `0/0/0`、首次 `HELPFUL` 后 `1/1/0`、另一条独立回答首次 `NOT_HELPFUL` 后
   `2/1/1`，并保持 `submittedCount = helpfulCount + notHelpfulCount`；
3. 空范围、跨 owner 和跨知识库的聚合零值都返回正常 `0/0/0`，不预查存在性，也不把它们转换为异常；
4. Service 不调用 V12 `insertIfAbsent`、V13 status 或 V14 page Mapper 方法，证明该本地编排为零写入；
5. Controller 覆盖嵌套 GET 路由、成功文案、`data` 的精确三字段数值 JSON，以及 V10 answer Service
   零交互。

已在 JDK 21 加 Mockito javaagent 下完成本地验证：V15 相关 Mapper/Service/Controller 测试共
31/31 通过，`mvn test` 全套共 176/176 通过。它们是本地单元/mock 回归证据，不包含真实模型调用、
生产数据库验收或外部服务 SLA 检查。

`backend/http/knowledge-document.http` 的手工验收顺序是：

1. Login -> Create KB -> Upload -> Process -> Vectorize -> V7 -> V8；
2. V10 POST `/chat`，保存第一条 `agentflowChatAnswerId`。这个前置步骤会经 V9 发起真实模型调用，只有
   本地 OpenAI-compatible 配置已就绪时才运行；
3. V15 GET，断言 `0/0/0`；
4. 对第一条 answer 执行 V12 `HELPFUL` POST，再执行 V15 GET，断言 `1/1/0`；
5. 再执行一次 V10 POST `/chat`，保存不同的第二条 `agentflowSecondChatAnswerId`；
6. 只对第二条 answer 首次执行 V12 `NOT_HELPFUL` POST，再执行 V15 GET，断言 `2/1/1`；
7. 保留现有对第一条 answer 的同 verdict 200 与反向 verdict 409 回归。V15 链路不会用反向 verdict
   伪造第二类计数。

上述自动化测试和 HTTP 脚本只能证明本地 Mapper/Service/Controller 的读取边界与 API 响应。V15 GET
本身不调用模型；其中两次 V10 前置 POST 仍需要本地模型。它们不构成真实模型质量、外部服务 SLA、生产
多租户合规、训练效果或线上评测证明。

## 6. 明确不做

- 未反馈回答数、比例、覆盖率、平均分、趋势、时间窗口、verdict 筛选、客户端排序、分页、导出或跨知识库汇总；
- 评论、标签、编辑、撤销、删除、重新提交相反 verdict，或 feedback 的 PATCH、PUT、DELETE；
- 训练、奖励信号、模型评测、质量/准确率结论，或把原始 feedback 数量解释为用户满意度；
- 用 feedback 改变 Prompt、上下文装配、检索、rerank、模型选择、provider 配置或后续回答；
- V10 详情、V11 回答台账、V12 POST 幂等/409、V13 单条状态、V14 分页台账、Flyway migration 或索引改动；
- 会话、多轮 message/role、流式/SSE、异步队列、订阅、轮询协议、Agent/tool calling、缓存或物化视图；
- 保留/删除策略、法务删除、管理员修改通道或后台回填。

## 面试问题与回答

### 问题 1：为什么 V15 用 feedback INNER JOIN parent answer，而不是直接聚合 feedback 表？

**回答：** feedback 表故意没有复制 `user_id` 和 `knowledge_base_id`；它们的权威来源是不可变 parent
answer。V15 在同一 SQL 中用 `f.answer_id = a.id`，并以 JWT owner 和路径 `knowledgeBaseId` 限定 parent，
所以范围外事件不会进入三个 count。这样避免两份归属字段漂移，也不需先全局查询 feedback；这是本地
Mapper 的访问边界，而不是生产多租户合规认证。

### 问题 2：为什么空、跨 owner 和跨知识库都返回 200 的 0/0/0？

**回答：** V15 是范围内已提交事件的聚合，而非单条资源详情。PostgreSQL 的无 `GROUP BY` count 聚合在零行
仍返回一条零值投影；不预查 knowledge base、answer 或 feedback 能使空范围和不可见范围得到同一 API
结果，避免将范围外资源存在性暴露为 404 或不同错误。这个策略只适用于本概览，不改变 V10/V12/V13 的
单条 answer 404 边界。

### 问题 3：如何保证 submittedCount 等于 helpfulCount 加 notHelpfulCount？

**回答：** `submittedCount` 是 scoped INNER JOIN 后的全部 feedback 行数，另外两个 count 以保存的
`HELPFUL` 与 `NOT_HELPFUL` 过滤。V9 schema 的 verdict CHECK 只允许这两个值，且 `UNIQUE(answer_id)`
限制一条回答最多一行 feedback；响应 DTO 还校验三个非负计数和这个等式。这里保证的是已保存不可变事件的
计数一致性，而非评分质量或真实用户满意度。

### 问题 4：为什么 2/1/1 的验收必须使用第二条独立回答？

**回答：** V12 把每条回答的首次 verdict 定义为不可修改事件。同一 answer 已有 `HELPFUL` 后提交
`NOT_HELPFUL` 仍应是 `409 KNOWLEDGE_CHAT_ANSWER_FEEDBACK_CONFLICT`，不能把它当作 V15 的第二类计数。
因此 HTTP 验收先创建不同的 V10 `answerId`，再对它首次提交 `NOT_HELPFUL`，才得到两条独立原始事件的
`2/1/1`；编辑、撤销和反向重投均未纳入 V15。

### 问题 5：V15 如何证明它不会写入、调用模型或影响检索？

**回答：** `getSummaryOwnedByKnowledgeBase` 是只读事务，只依赖一条 feedback-to-parent aggregate SELECT；
没有 V12 insert、V13 status、V14 page、V10 detail、V7/V8/V9、检索或 ChatGateway 依赖。局部 mock 测试
断言写入和其他 Mapper 方法零交互，Controller 测试断言 V10 answer Service 零交互。这证明当前本地代码的
编排边界，不等于外部服务运行时或生产环境认证。
