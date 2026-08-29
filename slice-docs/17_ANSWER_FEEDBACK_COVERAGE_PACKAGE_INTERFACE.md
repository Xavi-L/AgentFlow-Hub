# AgentFlow Hub Knowledge：V16 不可变回答反馈覆盖原始计数概览

V16 为当前 owner、当前知识库提供不可变回答反馈的覆盖原始计数：已经持久化的 V10 回答总数、
已提交 V12 feedback 的回答数，以及尚未提交 feedback 的回答数。它只返回这三个原始整数，
不计算比例或覆盖率，也不对模型质量、准确率、用户满意度、训练价值或趋势作出结论。

V16 从 `knowledge_chat_answer` 父回答开始，并以当前 JWT owner 和路径知识库固定范围，再 `LEFT JOIN`
`knowledge_chat_answer_feedback`。因此，scope 内已有 V10 回答但从未提交 V12 feedback 的记录仍会进入
聚合。该 GET 只读既有不可变记录，不创建、重试、修改或撤销 feedback，也不改写 V10 回答快照。

## 1. HTTP 契约

~~~http
GET /api/v1/knowledge-bases/{knowledgeBaseId}/chat-answer-feedbacks/coverage
Authorization: Bearer <access-token>
~~~

项目统一的 `ApiResponse` 保留外层 `code`、`message`、`traceId` 和 `timestamp`。V16 的 `data`
严格只有下列三个数值字段：

~~~json
{
  "answerCount": 2,
  "submittedCount": 1,
  "unsubmittedCount": 1
}
~~~

`AuthenticatedUser` 只能来自 JWT principal，`knowledgeBaseId` 只能来自路径。接口没有 request body，
也不接受 `answerId`、`feedbackId`、`userId`、`verdict`、`page`、`pageSize`、时间范围、排序、导出或
跨知识库参数。成功响应文案为 `Knowledge chat answer feedback coverage retrieved`。

三个字段均为非负原始计数，并保持：

~~~text
answerCount = submittedCount + unsubmittedCount
~~~

`answerCount` 只统计当前范围内已经持久化的 V10 parent answer；`submittedCount` 统计其中已有 V12
feedback 的回答；`unsubmittedCount` 统计其中没有 V12 feedback 的回答。它们不等同于比例、覆盖率、
评分、质量、准确率、训练信号或用户意图之外的结论。

## 2. Scope、空结果与可见性边界

V16 由 parent answer 驱动，在同一 SQL 中固定当前 owner 与当前知识库，并用 `LEFT JOIN` 保留没有
feedback 的回答：

~~~sql
SELECT COUNT(a.id) AS answer_count,
       COUNT(f.id) AS submitted_count,
       COUNT(a.id) FILTER (WHERE f.id IS NULL) AS unsubmitted_count
FROM knowledge_chat_answer a
LEFT JOIN knowledge_chat_answer_feedback f ON f.answer_id = a.id
WHERE a.knowledge_base_id = #{knowledgeBaseId}
  AND a.user_id = #{userId}
~~~

`knowledge_chat_answer` 是 owner 与 knowledge base 的权威来源；feedback 表不复制这些可能漂移的
归属字段。V9 的 `UNIQUE(answer_id)` 保证一条 parent answer 至多匹配一条 feedback，因而每条
scope 内回答恰好贡献到 submitted 或 unsubmitted 之一。响应 DTO 再校验三个非负计数及上述等式。

无 `GROUP BY` 的 PostgreSQL 聚合即使没有匹配 parent answer 也返回一个零值投影。因此，当前范围为空、
路径知识库属于其他 owner，或路径知识库与 parent answer 不匹配时，均返回 HTTP 200 和 `0/0/0`。
V16 不预查 knowledge base、answer 或 feedback 是否存在，也不返回 404 或差异化文案；不能借由本端点
区分范围外资源是否存在。

V15 从已提交 feedback event 开始做 `INNER JOIN`，所以只统计已提交事件；V16 的问题不同，必须从
parent answer 开始并 `LEFT JOIN`，否则尚未反馈的回答会消失。V16 不改变 V10、V11、V12、V13、V14 或 V15
各自既有的读写与可见性契约。

## 3. 包接口与只读边界

~~~text
KnowledgeChatAnswerController
  GET /chat-answer-feedbacks/coverage
    -> KnowledgeChatAnswerFeedbackService.getCoverageOwnedByKnowledgeBase(...)
         -> KnowledgeChatAnswerFeedbackMapper.selectCoverageOwnedByKnowledgeBase(...)
              scoped knowledge_chat_answer LEFT JOIN feedback aggregate
         -> KnowledgeChatAnswerFeedbackCoverageResponse
~~~

1. `KnowledgeChatAnswerController` 只传递 JWT 当前 owner 和路径 `knowledgeBaseId`，不绑定 body 或
   `PageRequest`，并在统一响应外壳的 `data` 内返回精确三个字段。
2. `getCoverageOwnedByKnowledgeBase(...)` 是 `readOnly` transaction，只依赖 Mapper 的一条 aggregate
   SELECT；它不预检查知识库或回答，也不把零值范围改写为异常。
3. Mapper 的 V16 statement 以 `knowledge_chat_answer a` 为驱动表，`LEFT JOIN` feedback；它不接受
   `answerId`、verdict、时间或分页参数，也不提供 INSERT、UPDATE、DELETE、排序、缓存或内存重排路径。
4. 该 Service 不依赖 `KnowledgeChatAnswerService`、`KnowledgeChatService`、`KnowledgeContextService`、
   V7 retrieval、V8 context、V9、Embedding、Qdrant、`ChatGateway` 或模型配置。因此 V16 GET 不读取
   回答正文、sources/citation 快照或预算，不重新检索、生成回答或影响 Prompt。

## 4. 数据与查询契约

V16 不新增 Flyway migration 或索引，只读取既有的两张不可变表：

- V7 的 `knowledge_chat_answer` 保存 immutable parent answer 及其 owner/knowledge-base 归属；
- V9 的 `knowledge_chat_answer_feedback` 保存 `id`、`answer_id`、`verdict` 与 `created_at`，已有
  `UNIQUE(answer_id)`、二元 verdict CHECK、parent foreign key，以及拒绝 UPDATE/DELETE 的 trigger。

Mapper 将三个 PostgreSQL `COUNT` 聚合显式映射为内部 `Long` 投影，再转换为 API 的三个 `long` 字段。
没有 `GROUP BY`，所以一个 owner/knowledge-base scope 每次最多返回一个概览；没有时间、verdict 或
answer 条件，所以该概览始终覆盖当前范围内的全部 immutable parent answer。V16 不创建 status 表、
物化视图、缓存、后台汇总任务或新的索引。

## 5. 实现与验收

自动化本地单元/mock 验收覆盖：

1. Mapper 注册的 V16 statement 以 `knowledge_chat_answer` 为驱动表，并 `LEFT JOIN` feedback，包含
   parent `knowledgeBaseId` 与当前 owner 谓词、三个 `COUNT` 投影；不包含 `INNER JOIN`、verdict 筛选、
   分页、answer 快照字段或写入 SQL；
2. Service 映射完整顺序 `0/0/0 -> 1/0/1 -> 1/1/0 -> 2/1/1 -> 2/2/0`，并在每一步检查
   `answerCount = submittedCount + unsubmittedCount`；
3. 空范围、跨 owner 与跨知识库均映射为正常 `0/0/0`，不预查存在性，也不把它们转换为异常；
4. Service 不调用 V12 `insertIfAbsent`、V13 status、V14 page 或 V15 summary Mapper 方法，证明该本地
   编排为零写入；Controller 覆盖嵌套 GET 路由、成功文案、`data` 的精确三字段数值 JSON，以及 V10
   answer Service 的零交互。

已在 JDK 21 与 Mockito javaagent 下完成本地验证：V16 相关 Mapper/Service/Controller 测试共
36/36 通过，`mvn test` 全套共 181/181 通过。

`backend/http/knowledge-document.http` 的手工验收顺序是：

1. 在 fresh current-owner/current-KB scope、任何 V10 回答创建前执行 V16 GET，断言 `0/0/0`；
2. 执行首次 V10 POST `/chat` 并保存第一条 `agentflowChatAnswerId`，随后 V16 GET 断言 `1/0/1`；
3. 对第一条 answer 首次执行 V12 `HELPFUL` POST，随后 V16 GET 断言 `1/1/0`；
4. 再执行一次 V10 POST `/chat` 并保存不同的第二条 `agentflowSecondChatAnswerId`，随后 V16 GET
   断言 `2/1/1`；
5. 只对第二条 answer 首次执行 V12 `NOT_HELPFUL` POST，随后 V16 GET 断言 `2/2/0`。

其中两个 V10 前置 POST 会经既有 V9 路径发起真实模型调用，只有本地 OpenAI-compatible 配置已就绪时
才运行；V16 GET 本身不调用模型。自动化 Mapper/Service/Controller 测试是本地单元/mock 回归证据，
手工 HTTP 脚本也不构成真实模型质量、外部服务 SLA、生产多租户合规、训练效果或线上评测证明。

## 6. 明确不做

- 比例或覆盖率、平均分、质量结论、时间窗口、趋势、verdict 筛选、客户端排序、分页、导出或跨知识库汇总；
- 评论、标签、编辑、撤销、删除、重新提交相反 verdict，或 feedback 的 PATCH、PUT、DELETE；
- 训练、奖励信号、模型评测、质量/准确率结论，或把原始 feedback 数量解释为用户满意度；
- 用 feedback 改变 Prompt、上下文装配、检索、rerank、模型选择、provider 配置或后续回答；
- V10 详情、V11 回答台账、V12 POST 幂等/409、V13 单条状态、V14 分页台账、V15 事件计数、Flyway migration
  或索引改动；
- 会话、多轮 message/role、流式/SSE、异步队列、订阅、轮询协议、Agent/tool calling、缓存或物化视图；
- 保留/删除策略、法务删除、管理员修改通道或后台回填。

## 面试问题与回答

### 问题 1：为什么 V16 必须从 parent answer 开始并使用 LEFT JOIN？

**回答：** V16 的分母语义是当前范围内已经持久化的 V10 回答，而不是已有 V12 feedback event。若从
feedback 开始做 `INNER JOIN`，没有 feedback 的回答不会有行，`unsubmittedCount` 就无法得到。以
`knowledge_chat_answer a` 为驱动并 `LEFT JOIN f.answer_id = a.id` 后，每条 scope 内回答都会参与聚合；
`f.id` 为 null 的行正是未提交数。这与 V15 已提交事件计数的 `INNER JOIN` 有意保持分层。

### 问题 2：为什么空、跨 owner 与跨知识库都返回 200 的 0/0/0？

**回答：** V16 是 owner/knowledge-base 范围聚合，而不是单条资源详情。无 `GROUP BY` 的 count 聚合在
零匹配行仍返回一个零值投影；不预查 knowledge base、answer 或 feedback，使空范围和不可见范围得到
相同 API 结果，避免通过 404 或不同文案披露范围外资源是否存在。这只适用于 V16 概览，不改变 V10/V12/V13
对单条 answer 的既有 404 契约。

### 问题 3：如何保证 answerCount 等于 submittedCount 加 unsubmittedCount？

**回答：** V9 的 `UNIQUE(answer_id)` 让每条 parent answer 至多对应一条 feedback。V16 对同一组
scope 内回答计 `COUNT(a.id)`，把 feedback id 非空的行计为 submitted，并把 feedback id 为空的行计为
unsubmitted，所以每条回答恰好落入一侧。DTO 同时拒绝负数和不满足等式的映射结果；该保证是持久化记录的
计数一致性，不是质量或满意度证明。

### 问题 4：为什么接口名称含“覆盖”，却不返回覆盖率或质量结论？

**回答：** 本切片将“覆盖”限定为三个可审计的原始计数，故调用方若需要比例只能在本切片之外、基于其
明确业务语义自行处理。这里不增加除法、阈值、时间趋势或 verdict 选择，也不会把 HELPFUL/NOT_HELPFUL
解释成模型好坏；这些都需要独立的产品和评测契约，未纳入 V16。

### 问题 5：如何证明 V16 不会写入、调用模型或影响检索？

**回答：** `getCoverageOwnedByKnowledgeBase` 是只读事务，只依赖一条 parent-answer LEFT JOIN aggregate
SELECT；它没有 V12 insert、V13 status、V14 page、V15 summary、V7/V8/V9、检索或 ChatGateway 依赖。
局部 mock 测试断言上述写入与其他 Mapper 方法零交互，Controller 测试断言 V10 answer Service 零交互。
这证明当前本地代码的编排边界，不等于外部服务运行时或生产环境认证。
