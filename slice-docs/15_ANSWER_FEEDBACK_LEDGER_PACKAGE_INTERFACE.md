# AgentFlow Hub Knowledge：V14 不可变回答反馈事件分页台账

V14 为当前 owner、当前知识库提供已经提交的 V12 不可变回答反馈事件分页读取。它是反馈事件的
审计索引，不是会话历史、状态轮询、统计入口或模型质量结论；每一项都只是既有 V12 事件的
只读投影。V14 不创建、重试、修改或撤销 feedback，也不改变 V10 回答、V11 回答台账、V12 提交
契约或 V13 单条状态查询。

## 1. HTTP 契约

~~~http
GET /api/v1/knowledge-bases/{knowledgeBaseId}/chat-answer-feedbacks?page=1&pageSize=20
Authorization: Bearer <access-token>
~~~

`AuthenticatedUser` 只能来自 JWT principal，`knowledgeBaseId` 只能来自路径；`page` 和
`pageSize` 使用通用 `PageRequest`。接口没有 request body，也不接受 `userId`、`answerId`、
`feedbackId`、`verdict`、搜索词或排序字段。缺省值是 `page=1&pageSize=20`，页码小于 1 会归一为 1，
`pageSize` 被限制为 1–100。

成功响应使用统一 `ApiResponse` 外壳，`data` 是分页对象：

~~~json
{
  "code": "OK",
  "message": "Knowledge chat answer feedback ledger retrieved",
  "data": {
    "items": [
      {
        "feedbackId": "701",
        "answerId": "501",
        "verdict": "HELPFUL",
        "createdAt": "2026-08-28T10:30:00+08:00"
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1,
    "hasNext": false
  }
}
~~~

每个 `items` 元素严格复用 V12 的 `KnowledgeChatAnswerFeedbackResponse`，只包含
`feedbackId`、`answerId`、`verdict` 和 `createdAt`。ID 以字符串返回，`verdict` 仍只可能是
`HELPFUL` 或 `NOT_HELPFUL`；不返回回答正文、query、sources、citationIds、V8 预算、V13 的
`submitted`/`feedback` 包装、评论、统计或模型评分类字段。

## 2. Scope、排序与空页边界

分页查询从已提交的 V12 event 表开始，并在同一条 SQL 中 JOIN 父
`knowledge_chat_answer` 施加当前 owner 与当前知识库范围：

~~~sql
SELECT f.id,
       f.answer_id,
       f.verdict,
       f.created_at
FROM knowledge_chat_answer_feedback f
JOIN knowledge_chat_answer a ON a.id = f.answer_id
WHERE a.knowledge_base_id = #{knowledgeBaseId}
  AND a.user_id = #{userId}
ORDER BY f.created_at DESC, f.id DESC
~~~

INNER JOIN 只会返回已经存在的 V12 feedback 行，且 `knowledge_chat_answer_feedback.answer_id` 的
唯一约束保证一条回答最多对应一项。固定的 `f.created_at DESC, f.id DESC` 让同一时间精度的事件也有
确定顺序，分页边界不会因为仅按时间排序而漂移。

V14 不预查询知识库、回答或反馈是否全局存在。当前 scope 没有事件、knowledgeBaseId 属于其他
owner、或事件的父回答属于其他知识库时，查询都不命中，统一返回 HTTP 200 和空 `PageResult`
（`items=[]`、`total=0`、`hasNext=false`）。它不返回 404、V12 的 409 或不同错误文案，因而不会通过
此 endpoint 区分知识库或 feedback 事件是否在当前 scope 外存在。

V13 的单条状态读取故意从 parent answer 做 LEFT JOIN，以区分“scope 内回答尚未反馈”和“回答不在
scope”；V14 不复用该查询，因为台账只需要已提交 event，且空结果必须对所有不可见情况一致。

## 3. 包接口与只读边界

~~~text
KnowledgeChatAnswerController
  GET /chat-answer-feedbacks
    -> KnowledgeChatAnswerFeedbackService.listOwnedByKnowledgeBase(...)
         -> KnowledgeChatAnswerFeedbackMapper.selectPageOwnedByKnowledgeBase(...)
              feedback INNER JOIN scoped parent answer
         -> PageResult<KnowledgeChatAnswerFeedbackResponse>
~~~

1. Controller 只传递 JWT 的当前 owner、路径中的 `knowledgeBaseId` 和绑定后的 `PageRequest`；没有
   反馈 request body，也不委托 V10 回答 Service。
2. `KnowledgeChatAnswerFeedbackService.listOwnedByKnowledgeBase(...)` 是 `readOnly` transaction，只依赖
   feedback Mapper。它把 MyBatis-Plus `Page` 的结果映射为 V12 的四字段响应，并保留数据库已经确定的
   排序；不做内存重排或 owner/知识库预检查。
3. Mapper 的自定义分页 SQL 从 `knowledge_chat_answer_feedback f` INNER JOIN
   `knowledge_chat_answer a`，在 parent 的 `a.user_id` 和 `a.knowledge_base_id` 谓词中限域；投影只选
   `f.id`、`f.answer_id`、`f.verdict`、`f.created_at`。
4. Service 不依赖 `KnowledgeChatAnswerService`、`KnowledgeChatService`、`KnowledgeContextService`、
   V7/V8/V9、检索、Embedding、Qdrant、`ChatGateway` 或模型配置。因此一次 V14 GET 不读取 V10 详情
   快照、不重新检索、不生成回答，也不会写入 feedback。

## 4. 数据与分页契约

V14 不新增 Flyway migration 或索引。它仅读取既有 V7 的不可变 parent
`knowledge_chat_answer` 与 V9 的不可变 `knowledge_chat_answer_feedback`：后者已有 `id`、`answer_id`、
`verdict`、`created_at`、`UNIQUE(answer_id)`、verdict CHECK、外键以及拒绝 UPDATE/DELETE 的 trigger。

MyBatis-Plus 已配置 PostgreSQL `PaginationInnerInterceptor`，由自定义 Mapper 的 `Page` 参数生成 count
和 `LIMIT/OFFSET` 分页；V14 不自行拼接偏移量、缓存结果、建立物化视图或复制 owner/知识库字段到
feedback 表。V11 的回答台账索引不被改写或声称为 V14 的新索引。

## 5. 实现与验收

自动化本地单元/mock 验收覆盖：

1. Mapper 注册 V14 语句，验证 `feedback JOIN parent answer`、parent owner/知识库谓词、四列 event
   投影及 `f.created_at DESC, f.id DESC`；不会选 query、citationIds 或 sources snapshot；
2. Service 在同一 `createdAt` 的两项模拟 event 中保持 feedback ID 降序，正确回传分页元数据和 V12
   四字段；它不调用 V12 写入、V12 单条读取或 V13 status 查询；
3. 空事件、跨 owner 与跨知识库的 scoped Mapper 空结果均映射为正常空页，而不是业务异常；
4. Controller 覆盖嵌套 GET、`page/pageSize` 绑定、分页元数据及 item 的精确四字段 JSON，并验证没有
   调用 V10 回答 Service；
5. `backend/http/knowledge-document.http` 在一份新建知识库中先验证 V14 空页，再在 V12 首次提交后按
   `feedbackId` 找回同一事件并检查字段集合。

已在 JDK 21 加 Mockito javaagent 下完成本地验证：上述三个 V14 相关 Mapper/Service/Controller 测试
共 26/26 通过，`mvn test` 全套共 171/171 通过。它们是本地单元/mock 回归证据；本切片没有发起真实
模型调用、外部服务 SLA 检查或生产数据库验收。

HTTP 脚本的执行顺序是 Login -> Create KB -> Upload -> Process -> Vectorize -> V7 -> V8 -> V10 POST
（该前置步骤需要本地 OpenAI-compatible 模型配置）-> V13 未反馈 GET -> V14 空页 GET -> V12 首次 POST
-> V14 已提交事件 GET -> V13 已提交 GET -> V12 同 verdict 重试/反向 verdict 冲突。V14 的两个 GET 本身
不调用模型；自动化 mock 与本地 HTTP 验收也不构成真实模型质量、生产外部服务 SLA 或多租户合规认证证明。

## 6. 明确不做

- verdict 筛选、关键词搜索、时间范围、客户端排序、评论、标签、编辑、撤销、删除或重新提交；
- 统计、聚合、审计导出、跨知识库汇总、训练集构建、评测、奖励信号或模型质量结论；
- 用反馈改变 Prompt、上下文装配、检索、rerank、模型选择、provider 配置或后续回答；
- V10 详情、V11 回答台账、V12 POST 幂等/409 契约、V13 单条状态契约、Flyway migration 或索引改动；
- 会话、多轮 message/role、流式/SSE、异步队列、订阅、轮询协议、Agent/tool calling、缓存或物化视图。

## 面试问题与回答

### 问题 1：为什么 V14 必须通过 parent answer JOIN 限制 owner 和知识库，而不能直接分页 feedback 表？

**回答：** feedback 表刻意只保存 `answer_id`、verdict 与创建时间，不复制 `user_id` 或
`knowledge_base_id`，以避免两份归属字段漂移。V14 在同一 SQL 中将 `f.answer_id` JOIN 到 parent answer，
并以 JWT owner 与路径 knowledgeBaseId 作为 `a.user_id`、`a.knowledge_base_id` 谓词。因此不可见 event
根本不会进入分页 count 或 items；这是本地 Mapper 查询边界，不是生产多租户合规认证结论。

### 问题 2：为什么 V14 使用 INNER JOIN，而 V13 状态查询使用 LEFT JOIN？

**回答：** V13 必须区分“scope 内回答还没有 feedback”与“回答不在 scope”，所以从 parent answer LEFT JOIN。
V14 的语义只列出已提交 V12 event，INNER JOIN 使没有 feedback 的回答天然不出现，也让空知识库、跨 owner
和跨知识库在 API 上统一为 200 空页。两条查询分别服务不同的失败/可见性边界，V14 没有改动 V13 契约。

### 问题 3：同一创建时间的反馈为什么还要按 feedbackId 排序？

**回答：** 只按 `created_at DESC` 时，同一时间精度的两行没有确定相对次序，OFFSET 分页可能让相邻页重复或
漏项。V14 固定 `f.created_at DESC, f.id DESC`，并在 Mapper 单元测试与 Service 同秒模拟事件中覆盖该
tie-breaker。这里保证的是本查询的确定性排序，不是跨事务强一致事件流。

### 问题 4：怎样证明 V14 不会重试 V12 写入、重新检索或调用模型？

**回答：** `listOwnedByKnowledgeBase` 是 `readOnly` transaction，唯一依赖是 feedback Mapper，调用的是
自定义分页 SELECT。局部 mock 测试断言它不调用 `insertIfAbsent`、V12 单条读取或 V13 status 查询，Controller
测试也验证 V10 回答 Service 零交互。由于它没有 V7/V8/V9、检索或 ChatGateway 依赖，证明的是当前本地代码
编排边界，不等于远端服务的运行时认证。

### 问题 5：为什么跨 owner 或跨知识库是 200 空页，而不是 404？

**回答：** V14 是范围内 event 的分页索引，而不是单条资源详情。先预检查知识库或 answer 会把“scope 外存在”
与“scope 内没有 event”暴露为不同结果；V14 只执行带 parent scope 的 JOIN，三种零行情况同样返回空
`PageResult`。这与 V10/V12/V13 单条 answer 路由的 404 隐藏策略不同，是该列表接口明确约定的可见性边界。
