# AgentFlow Hub Backend API 设计

> 文档状态：**NORMATIVE**  
> 权威范围：HTTP/SSE wire contract、DTO、错误码、模块边界和内部接口投影  
> 最近审查基线：`main@f276549`（V36）  
> Task 状态引用 Agent Engine Design；表结构引用 Data Model；RAG/Tool 语义不在本文件重复定义。

---

## 1. 模块结构

V0.1 采用模块化单体：

```text
com.agentflow
  common
    api
    error
    web
  user
  knowledge
    parser
    chunk
    vector
    retrieval
    storage
  agent
    app
    execution
  tool
  trace
  demo
  infra
    llm
    embedding
    vector
    storage
    task
```

说明：

- `agent.execution` 拥有 AgentTask application service、TaskRunner 和 AgentEngine；
- V0.1 不建设通用 `task` 平台模块；
- `trace` 只读取/记录执行事实，不修改 task 生命周期；
- `harness` 不作为顶层包；
- Evaluation 后续独立增加；
- Controller 不拼 Prompt、不执行工具、不直接查询数据库。

依赖方向：

```text
controller -> application/service -> domain boundary -> repository/infra adapter
```

`common` 不依赖业务模块，`infra` 不反向接管业务状态机。

---

## 2. 通用 HTTP 规范

### 2.1 路径

```text
/api/v1
```

### 2.2 认证

```http
Authorization: Bearer <access-token>
```

`userId` 永远来自认证 principal。请求体、query 或 header 中出现的 owner ID 不作为授权依据。

跨 owner、软删除和不存在资源默认统一返回 `404 COMMON_NOT_FOUND`，避免 authenticated resource enumeration。只有明确的管理员 API 才可区分。

### 2.3 成功响应

```json
{
  "code": "OK",
  "message": "success",
  "data": {},
  "traceId": "af-...",
  "timestamp": "2026-09-02T12:00:00+09:00"
}
```

### 2.4 失败响应

```json
{
  "code": "TASK_NOT_FOUND",
  "message": "Task not found",
  "data": null,
  "traceId": "af-...",
  "timestamp": "2026-09-02T12:00:00+09:00"
}
```

响应 message 必须稳定、安全，不包含原始 provider body、SQL、stack、endpoint、文件绝对路径、API key 或未脱敏工具结果。

### 2.5 ID 与时间

- 数据库使用 BIGINT；
- JSON 中所有业务 ID 返回字符串；
- 请求中的 ID 字段也使用字符串并在后端严格解析为正整数；
- 时间使用 ISO-8601 offset datetime；
- 不依赖服务器默认时区解释无 offset 时间。

### 2.6 分页

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 128,
  "hasNext": true
}
```

V0.1 页码从 1 开始，`pageSize` 最大 100。排序必须有稳定 ID tie-breaker。

### 2.7 HTTP 状态码

| HTTP | 用途 |
| --- | --- |
| 200 | 查询、修改、幂等成功 |
| 201 | 创建新资源 |
| 202 | task 已接受并排队，可选；V0.1 统一使用 201 创建 task |
| 400 | 参数或协议错误 |
| 401 | 未认证/token 无效 |
| 403 | 已认证但明确禁止的全局操作 |
| 404 | owner-scoped 资源不可见 |
| 409 | 幂等冲突、状态冲突、定义版本冲突 |
| 413 | 上传超限 |
| 429 | 限流，V1.x |
| 500 | 未分类内部错误 |
| 503 | 外部依赖暂不可用 |
| 504 | 同步调试接口超时；AgentTask 自身使用终态 `TIMED_OUT` |

---

## 3. 错误码命名

使用大写蛇形，按领域分组：

```text
AUTH_
USER_
KNOWLEDGE_
DOCUMENT_
EMBEDDING_
VECTOR_
RAG_
AGENT_
TASK_
TOOL_
LLM_
TRACE_
SYSTEM_
```

Task 与 Engine 至少暴露：

```text
TASK_NOT_FOUND
TASK_IDEMPOTENCY_CONFLICT
TASK_DISPATCH_REJECTED
TASK_NOT_CANCELLABLE
AGENT_NOT_FOUND
AGENT_DISABLED
AGENT_BINDING_INVALID
AGENT_INVALID_DECISION
AGENT_DUPLICATE_TOOL_LOOP
RAG_KNOWLEDGE_NOT_READY
RAG_RETRIEVAL_FAILED
RAG_INVALID_CITATION
TOOL_NOT_AVAILABLE
TOOL_DEFINITION_CHANGED
TOOL_ARGUMENT_INVALID
TOOL_EXECUTION_FAILED
TOOL_TIMEOUT
LLM_CONFIGURATION_ERROR
LLM_TIMEOUT
LLM_TRANSPORT_ERROR
LLM_PROVIDER_REJECTED
LLM_MALFORMED_RESPONSE
TOKEN_BUDGET_EXHAUSTED
```

内部异常类型到公开错误码的映射由 application boundary 完成。不要让 Controller 解析 exception message。

---

## 4. 内部能力接口

### 4.1 Chat completion

当前代码的：

```java
public interface LlmGateway {
    LlmChatResult chat(LlmChatRequest request);
}
```

继续作为同步 chat completion 能力。它不负责业务 Prompt、task 状态、embedding 或 rerank。

概念上保持接口隔离：

```text
ChatCompletionGateway
ChatStreamingGateway
EmbeddingGateway
RerankGateway
```

V0.1 只要求现有 `LlmGateway.chat` 和 `EmbeddingGateway`。不要把 stream/embed/rerank 方法继续堆进同一个接口。

### 4.2 VectorStoreGateway

```java
public interface VectorStoreGateway {
    void upsert(VectorStoreRecord record);
    List<VectorSearchHit> search(VectorSearchRequest request);
    void deleteByDocumentScope(VectorDocumentScope scope);
}
```

精确接口以当前实现为基线；业务层不 import Qdrant types。

### 4.3 DocumentStorage

现有 `DocumentStorage` 继续作为文件边界。V0.1 使用 local adapter，未来 MinIO adapter 不改变 Knowledge service。

### 4.4 TaskDispatcher

```java
public interface TaskDispatcher {
    void dispatch(long taskId);
}
```

V0.1 是有界线程池实现。Dispatcher 不执行业务，只触发 TaskRunner。

### 4.5 AgentEngine

```java
public interface AgentEngine {
    ExecutionOutcome execute(AgentExecutionRequest request);
}
```

不再使用“Engine 自己创建/取消 task”的接口。

### 4.6 RetrievalService

```java
public interface RetrievalService {
    RetrievalResult retrieve(RetrievalQuery query);
}
```

### 4.7 ToolRuntime

```java
public interface ToolRuntime {
    ToolExecutionResult execute(TaskToolExecutionCommand command);
}
```

Agent 可用 ToolSpec 在 task 创建时由 Agent application service + ToolDefinitionService 解析并写入 snapshot，不由 ToolRuntime 在每个 decision 中重新生成一套列表。

### 4.8 TaskEventAppender

```java
public interface TaskEventAppender {
    TaskEvent append(long taskId, NewTaskEvent event);
}
```

只有该边界分配 `sequenceNo`。模块不得自行 `max(sequence)+1`。

---

## 5. Auth API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | 注册 |
| POST | `/api/v1/auth/login` | 登录 |
| GET | `/api/v1/users/me` | 当前用户 |

V0.1 的 logout 为客户端删除 access token；没有 refresh token/黑名单时不提供虚假的服务端撤销语义。

---

## 6. Knowledge API

### 6.1 Knowledge Base

| 方法 | 路径 | V0.1 |
| --- | --- | --- |
| GET | `/api/v1/knowledge-bases` | 必须 |
| POST | `/api/v1/knowledge-bases` | 必须 |
| GET | `/api/v1/knowledge-bases/{kbId}` | 必须 |
| PATCH | `/api/v1/knowledge-bases/{kbId}` | 已有，可保留 |
| DELETE | `/api/v1/knowledge-bases/{kbId}` | 已有，可保留但不扩展 |

创建请求：

```json
{
  "name": "支付业务知识库",
  "description": "支付失败、错误码和处理规则"
}
```

V0.1 不接受任意 embedding provider/model/chunk strategy。响应返回服务端解析后的固定 profile：

```json
{
  "id": "201",
  "name": "支付业务知识库",
  "description": "支付失败、错误码和处理规则",
  "embeddingProfileCode": "dashscope-te-v4-1024-cosine",
  "chunkStrategyVersion": "structured-token-v1",
  "status": "ACTIVE"
}
```

### 6.2 Document

| 方法 | 路径 | V0.1 |
| --- | --- | --- |
| GET | `/api/v1/knowledge-bases/{kbId}/documents` | 必须 |
| POST | `/api/v1/knowledge-bases/{kbId}/documents` | 必须 |
| GET | `/api/v1/documents/{documentId}` | 必须 |
| GET | `/api/v1/documents/{documentId}/chunks` | 调试/Trace 必须 |
| POST | `/api/v1/documents/{documentId}/reprocess` | 已有维护入口，不属于闭环验收 |
| DELETE | `/api/v1/documents/{documentId}` | 已有维护入口 |

文档响应必须同时表达三个维度：

```json
{
  "id": "301",
  "knowledgeBaseId": "201",
  "fileName": "refund-rules.md",
  "fileType": "MD",
  "fileSize": 193,
  "parseStatus": "COMPLETED",
  "vectorization": {
    "pending": 0,
    "processing": 0,
    "completed": 4,
    "failed": 0
  },
  "retrievalReadiness": "READY",
  "vectorGeneration": 0,
  "createdAt": "...",
  "updatedAt": "..."
}
```

前端不得仅凭 `parseStatus=COMPLETED` 显示“可用于 Agent”。

### 6.3 Retrieval Test

```http
POST /api/v1/knowledge-bases/{kbId}/retrieve-test
```

```json
{
  "query": "E_PAY_TIMEOUT 怎么处理",
  "topK": 5,
  "similarityThreshold": 0.2
}
```

V0.1 可保留调试接口，但 `topK/threshold` 必须在安全范围内。`useRerank` 不进入 V0.1 请求。

响应 hit 带 `citationId`，与 Agent 路径相同：

```json
{
  "query": "E_PAY_TIMEOUT 怎么处理",
  "hits": [
    {
      "citationId": "C1",
      "chunkId": "401",
      "documentId": "301",
      "fileName": "refund-rules.md",
      "titlePath": "支付失败/错误码",
      "score": 0.8421,
      "content": "..."
    }
  ],
  "latencyMs": 86
}
```

---

## 7. Agent App API

### 7.1 CRUD

| 方法 | 路径 |
| --- | --- |
| GET | `/api/v1/agents` |
| POST | `/api/v1/agents` |
| GET | `/api/v1/agents/{agentId}` |
| PATCH | `/api/v1/agents/{agentId}` |
| DELETE | `/api/v1/agents/{agentId}` |
| POST | `/api/v1/agents/{agentId}/enable` |
| POST | `/api/v1/agents/{agentId}/disable` |

目标创建请求：

```json
{
  "name": "支付问题诊断助手",
  "description": "分析订单支付失败并给出建议",
  "systemPrompt": "你是企业内部支付问题诊断助手。",
  "chatModelProfileCode": "openai-compatible-default",
  "temperature": 0.2,
  "topP": 0.8,
  "maxDecisionTurns": 6,
  "maxToolCalls": 4,
  "maxTotalTokens": 8000,
  "timeoutSeconds": 120
}
```

当前数据库/DTO 的 `maxSteps/maxTokens/modelProvider/modelName` 在兼容期映射到上述语义。客户端不应长期直接输入任意 provider/model 组合。

约束：

```text
maxToolCalls < maxDecisionTurns
```

### 7.2 Knowledge Binding

| 方法 | 路径 |
| --- | --- |
| GET | `/api/v1/agents/{agentId}/knowledge-bases` |
| PUT | `/api/v1/agents/{agentId}/knowledge-bases` |

```json
{
  "knowledgeBaseIds": ["201"]
}
```

PUT 为全量替换，必须在一个事务中：

- owner-scoped 校验 Agent；
- 校验所有 KB 属于同 owner 且 live；
- 去重；
- 替换 bindings；
- 不改变已经创建的 task snapshot。

### 7.3 Tool Binding

| 方法 | 路径 |
| --- | --- |
| GET | `/api/v1/agents/{agentId}/tools` |
| PUT | `/api/v1/agents/{agentId}/tools` |

```json
{
  "toolIds": ["270000000000000001", "280000000000000001"]
}
```

V0.1 只允许可绑定的两个 BUILTIN 工具。忽略客户端自报 enabled/configOverride/permissionLevel；这些由服务端定义。

---

## 8. Agent Task API

### 8.1 创建 Task

```http
POST /api/v1/agents/{agentId}/tasks
Authorization: Bearer <token>
Idempotency-Key: <client-generated-opaque-key>
Content-Type: application/json
```

```json
{
  "input": "帮我分析 order_1024 支付失败的原因，并给出处理建议。"
}
```

规则：

- `Idempotency-Key` 必填，1–128 字符；
- key 仅在当前 user scope 内唯一；
- 相同 key + 相同 payload 返回原 task，HTTP 200；
- 相同 key + 不同 payload 返回 409；
- 新 task 返回 HTTP 201；
- task 与 snapshot 已提交后才响应；
- 线程池 dispatch 发生在事务提交后。

创建响应：

```json
{
  "id": "30001",
  "agentId": "1001",
  "status": "QUEUED",
  "phase": null,
  "lastEventSequence": 1,
  "eventsUrl": "/api/v1/tasks/30001/events"
}
```

请求不包含 `stream`。事件订阅是独立读取接口，task 是否流式展示不改变执行语义。

### 8.2 Task 列表和详情

| 方法 | 路径 |
| --- | --- |
| GET | `/api/v1/tasks` |
| GET | `/api/v1/tasks/{taskId}` |

详情：

```json
{
  "id": "30001",
  "agentId": "1001",
  "userInput": "...",
  "status": "RUNNING",
  "phase": "EXECUTING_TOOL",
  "terminationReason": null,
  "decisionTurnsUsed": 2,
  "toolCallsUsed": 1,
  "tokenUsage": {
    "inputTokens": 1500,
    "outputTokens": 120,
    "totalTokens": 1620,
    "quality": "EXACT"
  },
  "finalAnswer": null,
  "citations": [],
  "error": null,
  "lastEventSequence": 8,
  "startedAt": "...",
  "completedAt": null,
  "createdAt": "..."
}
```

终态 `COMPLETED` 必须有 `finalAnswer`。`TIMEOUT` 统一使用状态 `TIMED_OUT`，前后端不得再混用两种拼写。

### 8.3 Cancel

```http
POST /api/v1/tasks/{taskId}/cancel
```

- QUEUED：立即 CANCELLED；
- RUNNING：记录 cancelRequestedAt，响应仍可能为 RUNNING；
- 终态：幂等返回现状；
- 不承诺立即中断已经阻塞的 provider/tool call；
- 最终状态以 GET task 为准。

响应：

```json
{
  "id": "30001",
  "status": "RUNNING",
  "cancelRequested": true
}
```

---

## 9. SSE API

### 9.1 Endpoint

```http
GET /api/v1/tasks/{taskId}/events?afterSequence=8
Accept: text/event-stream
Authorization: Bearer <token>
Last-Event-ID: 8
```

`afterSequence` 和 `Last-Event-ID` 都表示“客户端已经完整处理的最后一个 sequence”。若两者同时存在且不同，返回 400；不要静默选择一个。

### 9.2 可靠读取模型

V0.1 SSE 基于数据库事件日志：

1. owner-scoped 校验 task；
2. 从 `sequence_no > cursor` 读取已提交事件；
3. 按 sequence 发送；
4. 持续轮询/等待新持久事件；
5. task 终态且所有事件发送完成后关闭。

不能只依赖进程内 emitter，否则 POST 返回与 SSE 连接之间会丢事件。单实例下数据库轮询足够简单、可恢复；后续多实例再增加通知或 fan-out。

### 9.3 Wire format

```text
id: 9
event: TOOL_FINISHED
data: {"taskId":"30001","sequenceNo":9,"eventType":"TOOL_FINISHED","timestamp":"...","payload":{}}
```

`data` 不套通用 ApiResponse。

### 9.4 Event types

```text
TASK_CREATED
TASK_STARTED
PHASE_CHANGED
RAG_FINISHED
DECISION_FINISHED
TOOL_STARTED
TOOL_FINISHED
FINAL_GENERATION_STARTED
ANSWER_CHUNK
TASK_COMPLETED
TASK_FAILED
TASK_CANCELLED
TASK_TIMED_OUT
```

`ANSWER_CHUNK` 是合并后的展示增量，不要求逐 token。

### 9.5 刷新和重连

- 客户端保存 last processed sequence；
- 重连时从该 sequence 继续；
- 页面刷新先 GET task/trace 建立快照；
- 若 task 仍非终态，从快照的 `lastEventSequence` 之后订阅；
- GET 与 SSE 之间产生的事件会由数据库 replay 补发；
- 终态事件到达后重新 GET task，以 `finalAnswer` 覆盖临时增量。

---

## 10. Trace API

V0.1 主入口：

```http
GET /api/v1/tasks/{taskId}/trace
```

响应：

```json
{
  "task": {},
  "executionSnapshot": {},
  "steps": [],
  "ragRetrievals": [],
  "llmCalls": [],
  "toolCalls": [],
  "events": [],
  "finalAnswer": "...",
  "citations": []
}
```

原则：

- 只读取同一 owner task；
- execution snapshot 可展示前先脱敏；
- 默认折叠完整 Prompt/response；
- 不展示 chain-of-thought；
- 不把 event 当成专项日志替代品；
- 数据按 stepIndex/时间/ID 稳定排序；
- 某个关联源资源已删除时仍返回 snapshot。

可选细分接口：

```text
GET /api/v1/tasks/{taskId}/steps
GET /api/v1/tasks/{taskId}/llm-calls
GET /api/v1/tasks/{taskId}/rag-retrievals
GET /api/v1/tasks/{taskId}/tool-calls
```

V0.1 前端优先使用聚合 trace，避免多请求拼接不一致快照。

Episode API 不进入 V0.1；V1 初期可由相同 Trace query service 动态聚合。

---

## 11. Tool API

V0.1 只需只读：

```text
GET /api/v1/tools
GET /api/v1/tools/{toolId}
```

普通用户只看到可绑定的安全展示字段：

```text
id
toolCode
name
description
type
inputSchema
status
```

不返回 handler、config、内部 URL/headers 或密钥。

已有 tool test/enable/disable 接口可以保留为开发或管理员能力，但不属于 V0.1 用户主流程，也不能绕过 ToolRuntime。

---

## 12. Controller 与事务规则

Controller 只做：

- HTTP 参数解析；
- Bean Validation；
- 当前 principal；
- 调用 application service；
- DTO 映射。

Application service 负责：

- owner scope；
- 事务；
- 状态条件更新；
- snapshot；
- module orchestration。

外部 LLM/Qdrant/tool I/O 不在长数据库事务内。

Task terminal update 与 terminal event 必须在同一数据库事务中完成。SSE 只能观察已提交事件。

---

## 13. DTO 与敏感数据

- Entity 不直接返回；
- JSONB 通过显式 DTO 投影；
- BIGINT 转字符串；
- error message 使用 allowlist；
- Prompt、tool args/result 和 document content 进入 API 前执行大小限制；
- API key、Authorization、Cookie、内部 endpoint、绝对文件路径永不返回；
- Trace 的完整 request/response 只对 task owner 可见，未来真实业务数据前增加字段级脱敏。

---

## 14. V0.1 API 验收

必须证明：

1. owner-scoped 资源不可枚举；
2. 幂等 key 语义正确；
3. task 创建后 snapshot 已持久化；
4. `status` 与 `phase` 不混用；
5. `TIMED_OUT` 在数据库、API 和前端一致；
6. cancel 不伪装成立即终止；
7. SSE 能补发 POST 返回前后产生的事件；
8. Last-Event-ID 不丢不重；
9. TASK_COMPLETED 时 GET task 已有 finalAnswer；
10. Trace 能聚合同一 task 的所有专项日志；
11. Knowledge 文档响应区分 parseStatus 和 retrievalReadiness；
12. Agent 绑定 API 不能绑定跨 owner KB 或不允许的工具；
13. 工具和模型内部配置不经 API 泄漏。
