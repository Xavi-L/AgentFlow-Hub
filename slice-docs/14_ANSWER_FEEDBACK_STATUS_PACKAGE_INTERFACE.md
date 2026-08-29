# AgentFlow Hub Knowledge：V13 单条不可变回答反馈状态查询

V13 只补齐 V12 反馈事件的只读可见性。当前 owner 可以查询当前知识库中一条已经冻结的
V10 回答是否已经提交 V12 反馈；若已提交，返回的必须是同一条既有不可变事件。它不创建、
重试、修改或重新解释反馈，也不修改 V10 回答、V11 台账或 V12 提交契约。

V13 不是反馈列表或统计入口，也不是聊天会话、模型评测或训练机制。一次 GET 只表达执行该
owner-scoped 查询时刻的数据库状态；若另一请求在之后提交 V12 反馈，下一次独立 GET 才会
看到该事件。

## 1. HTTP 契约

~~~http
GET /api/v1/knowledge-bases/{knowledgeBaseId}/chat-answers/{answerId}/feedback
Authorization: Bearer <access-token>
~~~

接口沿用统一 ApiResponse 外壳。AuthenticatedUser 只能来自 JWT principal；knowledgeBaseId 和
answerId 只能来自路径。GET 不接受 body、verdict、分页、筛选、userId 或客户端指定的事件字段。

当当前 owner 在当前知识库中能看到该回答、但尚未提交 V12 反馈时，返回 HTTP 200：

~~~json
{
  "code": "OK",
  "message": "Knowledge chat answer feedback status retrieved",
  "data": {
    "submitted": false
  }
}
~~~

data 中不输出 feedback: null。省略该字段明确表示“回答在当前 scope 内存在，但没有 feedback
事件”，不会伪造一个 feedbackId 或时间戳。

当该回答已有 V12 事件时，返回 HTTP 200：

~~~json
{
  "code": "OK",
  "message": "Knowledge chat answer feedback status retrieved",
  "data": {
    "submitted": true,
    "feedback": {
      "feedbackId": "701",
      "answerId": "501",
      "verdict": "HELPFUL",
      "createdAt": "2026-08-28T10:30:00+08:00"
    }
  }
}
~~~

nested feedback 复用 V12 的四字段不可变事件响应：feedbackId、answerId、verdict 和 createdAt
都来自已保存行。GET 不返回回答文本、sources、citationIds、预算、评论、统计或模型评分类字段。

回答不存在、属于其他 owner 或位于其他知识库时，三种情况一律返回 HTTP 404 和
KNOWLEDGE_CHAT_ANSWER_NOT_FOUND。V13 不会把这些情况伪装成 submitted=false，也不会返回
V12 的相反 verdict 409；409 只属于 V12 POST 的修改冲突边界。

## 2. Scope、可见性与失败边界

V13 的存在性判断始终以父回答为准，且在同一条 SQL 的 parent predicate 中固定：

~~~text
a.id = path answerId
AND a.knowledge_base_id = path knowledgeBaseId
AND a.user_id = currentUser.id
~~~

查询从 knowledge_chat_answer 作为父表开始，再 LEFT JOIN V12 的
knowledge_chat_answer_feedback：

1. parent answer 没有命中：Mapper 返回空，Service 返回
   KNOWLEDGE_CHAT_ANSWER_NOT_FOUND；
2. parent answer 命中且 feedbackId 为 null：返回 submitted=false；
3. parent answer 命中且 feedbackId 非空：返回 submitted=true 和原始 V12 event。

因此 V13 不会先按全局 answerId 或 feedbackId 查行，再在 Java 中判断 owner；也不会使用 V12 的
inner JOIN 查询来推断“无反馈”。后者无法区分“未反馈”和“父回答不在当前 scope”。

V13 不加锁、不写入，也不会通过读后补写把未反馈状态变成已反馈状态。并发 V12 POST 与 GET
之间的可见性由各自 SQL 执行时刻决定；本切片不定义会话一致性、轮询、事件订阅或重试协议。

## 3. 包接口与只读边界

~~~text
KnowledgeChatAnswerController
  GET /chat-answers/{answerId}/feedback
    -> KnowledgeChatAnswerFeedbackService.getFeedbackStatus(...)
         -> KnowledgeChatAnswerFeedbackMapper.selectStatusOwnedByAnswerId(...)
              FROM parent answer LEFT JOIN V12 feedback
         -> KnowledgeChatAnswerFeedbackStatusResponse
~~~

1. KnowledgeChatAnswerController 仅传递 AuthenticationPrincipal、knowledgeBaseId 和 answerId；
   没有 request body，也不接受客户端反馈字段。
2. KnowledgeChatAnswerFeedbackService 的 getFeedbackStatus 是 readOnly transaction，只依赖
   KnowledgeChatAnswerFeedbackMapper。它对空 parent projection 统一抛现有
   KNOWLEDGE_CHAT_ANSWER_NOT_FOUND，对非空 projection 仅组装响应。
3. Mapper 的 V13 查询由 knowledge_chat_answer 驱动，LEFT JOIN
   knowledge_chat_answer_feedback，并把 owner、知识库和 answerId 放在 parent WHERE 范围内。
   它不调用 INSERT、UPDATE、DELETE、全局 exists 查询或 feedback 列表查询。
4. KnowledgeChatAnswerFeedbackStatusResponse 在 submitted=false 时省略 nullable feedback；在
   submitted=true 时复用 KnowledgeChatAnswerFeedbackResponse，保持 V12 的事件字段和类型。

该 Service 不依赖 KnowledgeChatAnswerService 的 V10 detail read，也不依赖 KnowledgeChatService、
KnowledgeContextService、V7 retrieval、Embedding、Qdrant、ChatGateway 或模型配置。因此 V13 不会
读取完整 answer/source 快照，也不会进入 V7、V8、V9 或模型路径。

## 4. 数据与查询契约

V13 不新增 Flyway migration 或索引。它只读取现有两张不可变表：

- V7 的 knowledge_chat_answer 是 answerId、owner 和 knowledgeBaseId 的权威记录；
- V9 的 knowledge_chat_answer_feedback 是最多一条的 V12 feedback event，answer_id 有 UNIQUE
  约束，并由外键关联父回答。

查询形态固定为：

~~~sql
SELECT a.id AS answer_id,
       f.id AS feedback_id,
       f.verdict,
       f.created_at
FROM knowledge_chat_answer a
LEFT JOIN knowledge_chat_answer_feedback f ON f.answer_id = a.id
WHERE a.id = #{answerId}
  AND a.knowledge_base_id = #{knowledgeBaseId}
  AND a.user_id = #{userId}
~~~

a.id 是主键，V9 的 answer_id 是 UNIQUE，因此一次查询最多得到一个 parent answer 和一个 feedback
event。没有为 status 再复制 user_id、knowledge_base_id、回答文本、sources 或预算字段，避免产生
第二份可能漂移的归属或审计快照。

V10 与 V12 的原有不可变 trigger、V11 的分页索引及各自响应都不在本切片中修改。V13 也不创建
status 表、物化视图、缓存或后台同步任务。

## 5. 实现与验收

自动化单元/mock 验收覆盖：

1. Mapper 注册的 V13 statement 以 knowledge_chat_answer 为父表，包含 LEFT JOIN
   knowledge_chat_answer_feedback 以及 answerId、knowledgeBaseId、current user 三个 parent
   scope 条件；它映射 answerId、feedbackId、verdict 和 createdAt；
2. Service 对 scope 内且无 feedback 的 projection 返回 submitted=false，对有 feedback 的 projection
   返回既有 V12 event 的四个字段；两个成功分支都不调用 insertIfAbsent；
3. 缺失、跨 owner、跨知识库的 scoped 空结果都返回
   KNOWLEDGE_CHAT_ANSWER_NOT_FOUND，而非 submitted=false；
4. Controller 覆盖嵌套 GET 路由、未反馈 JSON 中 feedback 不存在、已反馈 JSON 的四个嵌套字段，以及
   404 错误码；Controller 测试中 V10 answer service 没有交互。

backend/http/knowledge-document.http 的手工验收顺序是：

1. Login -> Create KB -> Upload -> Process -> Vectorize -> V7 -> V8；
2. V10 POST /chat，保存 agentflowChatAnswerId。这个前置步骤会经 V9 发起一次真实模型调用，只有本地
   OpenAI-compatible 配置已就绪时才运行；
3. V13 未反馈 GET，断言 HTTP 200、submitted=false 且没有 feedback；
4. V12 HELPFUL POST，保存 feedbackId 和 createdAt；
5. V13 已反馈 GET，断言 HTTP 200、submitted=true，并逐项比较同一 feedbackId、answerId、verdict
   和 createdAt；
6. 继续执行现有 V12 同 verdict 重试 HTTP 200 与反向 verdict HTTP 409 验收。

上述 GET/POST HTTP 脚本验证本地 API 响应和事件回读链路；自动化 mock 测试验证本地 Service/Mapper
编排和零写入边界。二者都不构成真实模型质量、远端服务 SLA、生产多租户合规或训练效果证明。

## 6. 明确不做

- feedback 列表、分页、筛选、搜索、统计、聚合、审计导出或跨知识库汇总；
- 自由文本评论、标签扩展、编辑、撤销、重新提交相反 verdict，或 feedback 的 PATCH、PUT、DELETE；
- 训练集构建、奖励信号、模型评测、质量评分或把单条主观反馈泛化为模型结论；
- 用反馈改变 Prompt、上下文装配、检索、rerank、模型选择、provider 配置或后续回答；
- V10 单条详情响应、V11 台账响应、V12 POST 幂等/409 契约、Flyway migration 或索引改动；
- 会话、多轮 message/role、流式/SSE、异步队列、后台订阅、轮询协议、Agent/tool calling；
- 保留/删除策略、法务删除、管理员修改通道或缓存/物化状态。

## 面试问题与回答

### 问题 1：为什么 V13 必须从 parent answer 做 LEFT JOIN，而不能复用 V12 的 feedback inner JOIN？

**回答：** V12 的 inner JOIN 只会返回已有 feedback，因此“尚未反馈”和“answerId 不存在或不在当前
owner/知识库范围”都会表现为空，无法实现两种不同的 API 结果。V13 由
knowledge_chat_answer 先通过 answerId、knowledgeBaseId、JWT owner 命中父行，再 LEFT JOIN V12；
父行存在且 feedbackId 为 null 才能安全返回 submitted=false，父行不存在才统一返回
KNOWLEDGE_CHAT_ANSWER_NOT_FOUND。

### 问题 2：为什么未反馈是 HTTP 200，而跨 owner 或跨知识库是 HTTP 404？

**回答：** submitted=false 是一条已经通过当前 owner/知识库谓词的 parent answer 的业务状态，而不是
“查不到东西”的替代值。跨 owner、跨知识库和不存在的 parent row 都不命中同一 scoped SQL，Service 统一
返回 KNOWLEDGE_CHAT_ANSWER_NOT_FOUND，避免通过 answerId 猜测得到其他人的事件存在性。这是 Mapper/Service
本地查询边界，不是外部渗透测试或生产合规认证结论。

### 问题 3：为什么 submitted=false 时要省略 feedback，而不是返回 feedback:null？

**回答：** 接口把未反馈状态定义为只有 submitted=false 的窄对象；省略 feedback 防止客户端把 null
误认为一个可编辑、待创建或部分加载的事件。状态 DTO 用 NON_NULL 序列化约束可选字段，Controller mock
验收断言 $.data.feedback 不存在；当 submitted=true 时才允许出现完整的 V12 四字段事件。

### 问题 4：如何证明 V13 不会重新检索、调用模型或写入反馈？

**回答：** getFeedbackStatus 只有 KnowledgeChatAnswerFeedbackMapper 依赖，使用 readOnly transaction
执行一条 parent LEFT JOIN；它没有调用 KnowledgeChatService、V10 detail Service、V7/V8/V9、Qdrant 或
ChatGateway。自动化 mock 测试验证成功和 404 分支均不调用 insertIfAbsent，且 Controller 测试验证
V10 answer service 零交互。这证明本地代码编排边界，不等于真实外部服务运行时认证。

### 问题 5：V13 能否保证 GET 与并发 V12 POST 的强一致顺序？

**回答：** 不能，也未纳入本切片。每次 GET 只反映自身 LEFT JOIN 语句执行时的已提交状态；如果 V12 POST
在该时刻之后成功，下一次 GET 才可能显示 submitted=true。V13 不增加锁、重试、轮询、流式事件或会话一致性
协议，以保持它只是一次零写入的状态读取。
