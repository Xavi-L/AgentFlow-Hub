# AgentFlow Hub 后端模块结构与 API 设计

本文档用于沉淀 AgentFlow Hub 的后端工程结构、模块边界、分层规范、核心接口抽象、REST API 和 SSE 事件设计。

设计目标：

> 后端要体现 Java 工程能力，而不是把所有逻辑写在几个 Controller 里。模块边界要清晰，但不要过度 DDD 化，确保个人项目能落地。

---

## 1. 后端总体原则

架构形态：

> Spring Boot 模块化单体。

基本原则：

- 按业务模块拆包，而不是按技术类型全局堆 `controller/service/mapper`。
- 每个模块内部可以有自己的 `controller`、`service`、`repository`、`dto`。
- 外部依赖统一放到 `infra` 或模块内的 `infra` 子包中。
- Controller 只处理 HTTP 请求、参数校验和响应封装。
- Service 承载业务流程和事务边界。
- Repository/Mapper 只负责数据库访问。
- Agent 执行引擎、RAG 服务、工具运行时必须有明确接口，方便测试和面试讲解。
- 所有 API 统一以 `/api/v1` 作为版本前缀。

---

## 2. 推荐包结构

```text
com.agentflow
  AgentFlowApplication.java

  common
    api
      ApiResponse.java
      PageRequest.java
      PageResult.java
    error
      ErrorCode.java
      BusinessException.java
      GlobalExceptionHandler.java
    id
      IdGenerator.java
    json
      JsonUtils.java
    web
      TraceIdFilter.java
      CurrentUser.java
      CurrentUserArgumentResolver.java

  config
    SecurityConfig.java
    RedisConfig.java
    RabbitMqConfig.java
    MybatisPlusConfig.java
    SpringAiConfig.java
    SseConfig.java

  user
    controller
    service
    repository
    model
    dto
    security

  knowledge
    controller
    service
    repository
    model
    dto
    parser
    chunk

  rag
    controller
    service
    model
    dto

  agent
    controller
    service
    engine
    state
    repository
    model
    dto

  tool
    controller
    service
    runtime
    builtin
    repository
    model
    dto

  task
    controller
    service
    event
    sse
    repository
    model
    dto

  trace
    controller
    service
    repository
    model
    dto

  harness
    service
    policy
    episode
    model
    dto

  evaluation
    controller
    service
    repository
    model
    dto

  demo
    controller
    service
    repository
    model
    data

  infra
    llm
    embedding
    vector
    storage
    mq
    redis
```

说明：

- `common`：跨模块基础能力，不能依赖业务模块。
- `config`：Spring 配置类。
- `user`：登录、JWT、当前用户、基础角色。
- `knowledge`：知识库、文档、chunk、解析和切分。
- `rag`：检索、rerank、上下文组装、RAG 调试。
- `agent`：Agent 配置和执行引擎。
- `tool`：工具注册中心和工具运行时。
- `task`：异步任务、状态、SSE 事件。
- `trace`：Agent trace、LLM 调用、RAG hit、工具调用日志。
- `harness`：Episode Package、PolicyGuard、评测复盘支撑。
- `evaluation`：轻量评测。
- `demo`：模拟订单、支付日志、工单数据源。
- `infra`：LLM、向量库、MinIO、RabbitMQ、Redis 等外部适配。

---

## 3. 模块职责

### 3.1 user

职责：

- 用户注册。
- 用户登录。
- JWT 签发和校验。
- 当前用户上下文。
- 基础角色控制。

不负责：

- Agent 权限细节。
- 知识库业务逻辑。

### 3.2 knowledge

职责：

- 知识库 CRUD。
- 文档上传。
- 文档元数据管理。
- 文档解析任务创建。
- chunk 切分和保存。
- 文档处理状态管理。

不负责：

- 具体向量检索排序策略。
- Agent 推理。

### 3.3 rag

职责：

- query embedding。
- 向量召回。
- metadata filter。
- rerank，可选。
- RAG prompt context 构造。
- 检索日志记录。
- RAG 调试接口。

不负责：

- 文档上传。
- Agent 状态机。

### 3.4 agent

职责：

- Agent CRUD。
- Agent 与知识库绑定。
- Agent 与工具绑定。
- Prompt 版本。
- Agent 执行引擎。
- Agent 状态机。
- Agent 任务主流程编排。

不负责：

- 具体工具业务实现。
- 具体向量数据库 SDK 调用。

### 3.5 tool

职责：

- 工具定义 CRUD。
- 工具 JSON Schema。
- 工具权限、超时、重试配置。
- 工具参数校验。
- 工具执行。
- 内置工具实现。

不负责：

- 决定 Agent 是否要调用工具。
- LLM 推理。

### 3.6 task

职责：

- Agent 任务创建。
- 任务状态查询。
- 任务取消。
- SSE 连接管理。
- 任务事件保存和推送。
- 异步任务投递。

不负责：

- Agent 推理细节。
- RAG 检索细节。

### 3.7 trace

职责：

- LLM 调用日志。
- RAG 召回日志。
- 工具调用日志。
- Agent step。
- Trace 详情聚合查询。

不负责：

- 修改业务状态。
- 执行 Agent。

### 3.8 harness

职责：

- Agent Episode Package 聚合。
- episode 导出。
- PolicyGuard 策略检查。
- policy check 记录。
- 为 Evaluation Harness 提供运行证据。

不负责：

- 替代 AgentEngine 执行任务。
- 直接执行工具。
- 修改知识库或业务数据。

### 3.9 evaluation

职责：

- 评测集 CRUD。
- 评测问题 CRUD。
- 评测运行。
- 评测结果保存。

不负责：

- 复杂自动打分平台。

### 3.9 demo

职责：

- 模拟订单数据。
- 模拟支付日志。
- 模拟工单。
- 给内置工具提供业务数据源。

---

## 4. 分层规范

每个业务模块推荐结构：

```text
module
  controller   HTTP API
  service      业务流程
  repository   数据访问封装
  model        Entity / Enum / Domain Model
  dto          Request / Response DTO
```

复杂模块可增加：

```text
engine       执行引擎
runtime      运行时
parser       解析器
event        事件
```

### 4.1 Controller 规范

Controller 只做：

- 接收请求。
- 参数校验。
- 获取当前用户。
- 调用 service。
- 返回 DTO。

Controller 不做：

- 数据库查询。
- 拼 prompt。
- 调用 LLM。
- 执行工具。
- 复杂 if/else 业务流程。

### 4.2 Service 规范

Service 负责：

- 事务边界。
- 权限校验。
- 状态流转。
- 调用 repository。
- 调用 infra client。
- 调用其他模块 service。

### 4.3 Repository / Mapper 规范

Repository/Mapper 负责：

- 单表 CRUD。
- 简单条件查询。
- 分页查询。
- 必要的 join 查询。

复杂聚合查询可以由 Service 编排多个 Repository 完成。

### 4.4 DTO 规范

命名：

```text
CreateAgentRequest
UpdateAgentRequest
AgentDetailResponse
AgentListItemResponse
KnowledgeBaseResponse
PageResult<T>
```

不要直接把 Entity 返回给前端。

原因：

- 避免泄露内部字段。
- 便于控制 ID、JSONB、时间格式。
- 便于未来调整数据库结构。

---

## 5. 通用 API 规范

### 5.1 路径前缀

所有 API：

```text
/api/v1
```

### 5.2 认证方式

使用 Bearer Token：

```http
Authorization: Bearer <access_token>
```

V1.0 只做 access token，不强制做 refresh token。

### 5.3 统一响应结构

成功响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {},
  "traceId": "af-20260501-xxx",
  "timestamp": "2026-05-01T12:00:00+08:00"
}
```

失败响应：

```json
{
  "code": "AGENT_TASK_NOT_FOUND",
  "message": "Agent task not found",
  "data": null,
  "traceId": "af-20260501-xxx",
  "timestamp": "2026-05-01T12:00:00+08:00"
}
```

### 5.4 分页响应结构

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 128,
  "hasNext": true
}
```

### 5.5 ID 返回规范

数据库 ID 使用 `BIGINT`。

API JSON 中 ID 建议返回字符串：

```json
{
  "id": "1844674400000000001"
}
```

原因：

- 避免 JavaScript number 精度丢失。
- 前端展示和路由传参更安全。

### 5.6 时间格式

统一使用 ISO-8601：

```text
2026-05-01T12:00:00+08:00
```

### 5.7 HTTP 状态码

| 状态码 | 用途 |
| --- | --- |
| 200 | 成功 |
| 201 | 创建成功，可选 |
| 400 | 参数错误 |
| 401 | 未登录或 token 无效 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 409 | 状态冲突或重复提交 |
| 429 | 限流 |
| 500 | 服务端错误 |

---

## 6. 错误码设计

错误码使用大写蛇形：

```text
AUTH_INVALID_TOKEN
KNOWLEDGE_BASE_NOT_FOUND
DOCUMENT_PARSE_FAILED
AGENT_NOT_FOUND
AGENT_TASK_NOT_FOUND
TOOL_ARGUMENT_INVALID
LLM_PROVIDER_ERROR
VECTOR_STORE_ERROR
```

错误码分组：

| 前缀 | 模块 |
| --- | --- |
| `AUTH_` | 认证 |
| `USER_` | 用户 |
| `KB_` | 知识库 |
| `DOC_` | 文档 |
| `RAG_` | 检索 |
| `AGENT_` | Agent |
| `TASK_` | 任务 |
| `TOOL_` | 工具 |
| `LLM_` | 模型调用 |
| `EVAL_` | 评测 |
| `SYS_` | 系统 |

---

## 7. 核心内部接口

### 7.1 LlmGateway

用途：

> 屏蔽具体模型供应商，统一 chat、stream、embedding、rerank。

建议接口：

```java
public interface LlmGateway {
    ChatResult chat(ChatRequest request);

    void streamChat(ChatRequest request, TokenStreamHandler handler);

    EmbeddingResult embed(EmbeddingRequest request);

    RerankResult rerank(RerankRequest request);
}
```

说明：

- V0.1 可先实现 `chat`、`streamChat`、`embed`。
- `rerank` 可以 V1.5 再正式启用。

### 7.2 VectorStoreGateway

用途：

> 屏蔽 Qdrant SDK。

```java
public interface VectorStoreGateway {
    void upsertChunks(List<VectorChunk> chunks);

    List<VectorSearchHit> search(VectorSearchRequest request);

    void deleteByDocumentId(Long documentId);

    void deleteByChunkIds(List<Long> chunkIds);
}
```

### 7.3 FileStorageGateway

用途：

> 屏蔽 MinIO 或本地文件存储。

```java
public interface FileStorageGateway {
    StoredFile put(FileUploadCommand command);

    InputStream get(String bucket, String objectKey);

    void delete(String bucket, String objectKey);
}
```

### 7.4 RagService

用途：

> 提供统一 RAG 检索入口。

```java
public interface RagService {
    RagResult retrieve(RagQuery query);

    RagContext buildContext(RagResult result);
}
```

### 7.5 AgentEngine

用途：

> Agent 执行主入口。

```java
public interface AgentEngine {
    void execute(AgentExecutionCommand command);

    void cancel(Long taskId, Long userId);
}
```

### 7.6 ToolRuntime

用途：

> 工具执行统一入口。

```java
public interface ToolRuntime {
    ToolExecutionResult execute(ToolExecutionCommand command);

    List<ToolSpec> getToolSpecsForAgent(Long agentId);
}
```

### 7.7 AgentRuntimeHarness

用途：

> 聚合 Agent Episode Package，并为评测和 Trace 回放提供统一运行证据。

```java
public interface AgentRuntimeHarness {
    AgentEpisode buildEpisode(Long taskId, Long userId);

    AgentEpisode exportEpisode(Long taskId, Long userId);
}
```

### 7.8 PolicyGuard

用途：

> 在 ToolRuntime 执行工具前进行策略检查。

```java
public interface PolicyGuard {
    PolicyDecision check(PolicyCheckCommand command);
}
```

### 7.9 TaskEventPublisher

用途：

> 统一保存并推送任务事件。

```java
public interface TaskEventPublisher {
    void publish(Long taskId, TaskEvent event);
}
```

---

## 8. REST API 总览

### 8.1 Auth / User API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | 用户注册 |
| POST | `/api/v1/auth/login` | 用户登录 |
| POST | `/api/v1/auth/logout` | 用户退出 |
| GET | `/api/v1/users/me` | 查询当前用户 |

登录请求：

```json
{
  "username": "xavier",
  "password": "password"
}
```

登录响应：

```json
{
  "accessToken": "jwt-token",
  "tokenType": "Bearer",
  "expiresIn": 7200,
  "user": {
    "id": "1",
    "username": "xavier",
    "displayName": "Xavier",
    "role": "USER"
  }
}
```

---

## 9. Knowledge API

### 9.1 知识库

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/knowledge-bases` | 分页查询知识库 |
| POST | `/api/v1/knowledge-bases` | 创建知识库 |
| GET | `/api/v1/knowledge-bases/{kbId}` | 查询知识库详情 |
| PATCH | `/api/v1/knowledge-bases/{kbId}` | 修改知识库 |
| DELETE | `/api/v1/knowledge-bases/{kbId}` | 删除知识库 |

创建知识库请求：

```json
{
  "name": "支付业务知识库",
  "description": "支付失败、错误码、退款规则相关文档",
  "embeddingProvider": "openai-compatible",
  "embeddingModel": "text-embedding-v3",
  "chunkSize": 800,
  "chunkOverlap": 120
}
```

### 9.2 文档

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/knowledge-bases/{kbId}/documents` | 查询文档列表 |
| POST | `/api/v1/knowledge-bases/{kbId}/documents` | 上传文档 |
| GET | `/api/v1/documents/{documentId}` | 查询文档详情 |
| POST | `/api/v1/documents/{documentId}/reprocess` | 重新解析文档 |
| DELETE | `/api/v1/documents/{documentId}` | 删除文档 |
| GET | `/api/v1/documents/{documentId}/chunks` | 查询文档 chunks |

上传文档：

```http
POST /api/v1/knowledge-bases/{kbId}/documents
Content-Type: multipart/form-data

file=<binary>
```

文档响应核心字段：

```json
{
  "id": "101",
  "knowledgeBaseId": "10",
  "fileName": "payment-error-guide.md",
  "fileType": "MD",
  "fileSize": 24576,
  "parseStatus": "COMPLETED",
  "chunkCount": 32,
  "parseError": null,
  "createdAt": "2026-05-01T12:00:00+08:00"
}
```

### 9.3 检索测试

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/knowledge-bases/{kbId}/retrieve-test` | 知识库检索测试 |

请求：

```json
{
  "query": "支付失败错误码 E_PAY_TIMEOUT 怎么处理？",
  "topK": 5,
  "similarityThreshold": 0.2,
  "useRerank": false
}
```

响应：

```json
{
  "query": "支付失败错误码 E_PAY_TIMEOUT 怎么处理？",
  "hits": [
    {
      "chunkId": "10001",
      "documentId": "101",
      "fileName": "payment-error-guide.md",
      "score": 0.8421,
      "rerankScore": null,
      "content": "E_PAY_TIMEOUT 表示支付网关响应超时...",
      "titlePath": "支付失败/错误码"
    }
  ],
  "latencyMs": 86
}
```

---

## 10. Agent API

### 10.1 Agent 管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/agents` | 分页查询 Agent |
| POST | `/api/v1/agents` | 创建 Agent |
| GET | `/api/v1/agents/{agentId}` | 查询 Agent 详情 |
| PATCH | `/api/v1/agents/{agentId}` | 修改 Agent |
| DELETE | `/api/v1/agents/{agentId}` | 删除 Agent |
| POST | `/api/v1/agents/{agentId}/enable` | 启用 Agent |
| POST | `/api/v1/agents/{agentId}/disable` | 禁用 Agent |

创建 Agent 请求：

```json
{
  "name": "支付问题诊断助手",
  "description": "用于分析订单支付失败、查询日志并生成处理建议",
  "systemPrompt": "你是企业内部研发运营助手，擅长结合知识库和工具分析支付问题。",
  "modelProvider": "openai-compatible",
  "modelName": "kimi-k2",
  "temperature": 0.2,
  "topP": 0.8,
  "maxSteps": 6,
  "maxToolCalls": 4,
  "maxTokens": 8000,
  "timeoutSeconds": 120
}
```

### 10.2 Agent 绑定

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/api/v1/agents/{agentId}/knowledge-bases` | 设置 Agent 绑定知识库 |
| GET | `/api/v1/agents/{agentId}/knowledge-bases` | 查询 Agent 绑定知识库 |
| PUT | `/api/v1/agents/{agentId}/tools` | 设置 Agent 可用工具 |
| GET | `/api/v1/agents/{agentId}/tools` | 查询 Agent 可用工具 |

设置知识库绑定：

```json
{
  "knowledgeBaseIds": ["10", "11"]
}
```

设置工具绑定：

```json
{
  "tools": [
    {
      "toolId": "201",
      "enabled": true,
      "configOverride": {}
    }
  ]
}
```

### 10.3 Prompt 版本

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/agents/{agentId}/prompt-versions` | 查询 Prompt 版本 |
| POST | `/api/v1/agents/{agentId}/prompt-versions` | 创建 Prompt 版本 |
| POST | `/api/v1/agents/{agentId}/prompt-versions/{versionId}/activate` | 激活指定版本 |

---

## 11. Agent Task API

### 11.1 创建和查询任务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/agents/{agentId}/tasks` | 创建 Agent 执行任务 |
| GET | `/api/v1/tasks` | 查询任务历史 |
| GET | `/api/v1/tasks/{taskId}` | 查询任务详情 |
| POST | `/api/v1/tasks/{taskId}/cancel` | 取消任务 |
| GET | `/api/v1/tasks/{taskId}/events` | SSE 订阅任务事件 |

创建任务请求：

```json
{
  "conversationId": null,
  "input": "帮我分析 order_1024 支付失败的原因，并给出处理建议。",
  "stream": true
}
```

创建任务响应：

```json
{
  "taskId": "30001",
  "conversationId": "50001",
  "status": "QUEUED",
  "sseUrl": "/api/v1/tasks/30001/events"
}
```

任务详情响应：

```json
{
  "id": "30001",
  "agentId": "1001",
  "conversationId": "50001",
  "userInput": "帮我分析 order_1024 支付失败的原因，并给出处理建议。",
  "status": "COMPLETED",
  "finalAnswer": "根据订单状态和日志，支付失败原因可能是支付网关超时...",
  "totalTokens": 5230,
  "totalCost": 0.0123,
  "startedAt": "2026-05-01T12:00:00+08:00",
  "completedAt": "2026-05-01T12:00:12+08:00"
}
```

---

## 12. SSE 事件设计

SSE endpoint：

```http
GET /api/v1/tasks/{taskId}/events
Accept: text/event-stream
```

### 12.1 事件格式

```text
event: TOOL_STARTED
id: 12
data: {"taskId":"30001","sequenceNo":12,"payload":{}}
```

### 12.2 通用事件 data

```json
{
  "taskId": "30001",
  "sequenceNo": 12,
  "eventType": "TOOL_STARTED",
  "timestamp": "2026-05-01T12:00:05+08:00",
  "payload": {}
}
```

### 12.3 事件类型

| event | 说明 |
| --- | --- |
| `TASK_STARTED` | 任务开始 |
| `RAG_STARTED` | RAG 检索开始 |
| `RAG_FINISHED` | RAG 检索完成 |
| `LLM_STARTED` | LLM 调用开始 |
| `LLM_FINISHED` | LLM 调用完成 |
| `TOOL_STARTED` | 工具调用开始 |
| `TOOL_FINISHED` | 工具调用完成 |
| `TOKEN_DELTA` | 最终答案 token 增量 |
| `STEP_FINISHED` | 一个 Agent step 完成 |
| `TASK_COMPLETED` | 任务完成 |
| `TASK_FAILED` | 任务失败 |
| `TASK_CANCELLED` | 任务取消 |

### 12.4 TOKEN_DELTA 示例

```json
{
  "taskId": "30001",
  "sequenceNo": 21,
  "eventType": "TOKEN_DELTA",
  "timestamp": "2026-05-01T12:00:10+08:00",
  "payload": {
    "delta": "支付网关响应超时",
    "append": true
  }
}
```

### 12.5 RAG_FINISHED 示例

```json
{
  "taskId": "30001",
  "sequenceNo": 4,
  "eventType": "RAG_FINISHED",
  "payload": {
    "retrievalId": "40001",
    "topK": 5,
    "hits": [
      {
        "chunkId": "10001",
        "fileName": "payment-error-guide.md",
        "score": 0.8421,
        "titlePath": "支付失败/错误码"
      }
    ]
  }
}
```

### 12.6 TOOL_FINISHED 示例

```json
{
  "taskId": "30001",
  "sequenceNo": 9,
  "eventType": "TOOL_FINISHED",
  "payload": {
    "toolCallId": "60001",
    "toolCode": "order_query",
    "status": "SUCCESS",
    "latencyMs": 34,
    "summary": "订单 order_1024 当前状态为 PAY_FAILED，错误码 E_PAY_TIMEOUT"
  }
}
```

---

## 13. Tool API

### 13.1 工具定义

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/tools` | 查询工具列表 |
| POST | `/api/v1/tools` | 创建工具定义，V1.0 可仅管理员 |
| GET | `/api/v1/tools/{toolId}` | 查询工具详情 |
| PATCH | `/api/v1/tools/{toolId}` | 修改工具 |
| POST | `/api/v1/tools/{toolId}/enable` | 启用工具 |
| POST | `/api/v1/tools/{toolId}/disable` | 禁用工具 |
| POST | `/api/v1/tools/{toolId}/test` | 测试工具调用 |

工具详情响应：

```json
{
  "id": "201",
  "toolCode": "order_query",
  "name": "订单查询工具",
  "description": "根据订单号查询订单状态、支付状态和错误码。",
  "type": "BUILTIN",
  "inputSchema": {
    "type": "object",
    "properties": {
      "orderNo": {
        "type": "string",
        "description": "订单号，例如 order_1024"
      }
    },
    "required": ["orderNo"]
  },
  "timeoutMs": 3000,
  "retryCount": 1,
  "requiresConfirmation": false,
  "permissionLevel": "LOW",
  "status": "ACTIVE"
}
```

---

## 14. Trace API

Trace 是项目演示核心。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/tasks/{taskId}/trace` | 查询一次任务完整 trace |
| GET | `/api/v1/tasks/{taskId}/steps` | 查询任务 step 列表 |
| GET | `/api/v1/tasks/{taskId}/llm-calls` | 查询 LLM 调用记录 |
| GET | `/api/v1/tasks/{taskId}/rag-retrievals` | 查询 RAG 检索记录 |
| GET | `/api/v1/tasks/{taskId}/tool-calls` | 查询工具调用记录 |
| GET | `/api/v1/tasks/{taskId}/policy-checks` | 查询策略检查记录 |
| GET | `/api/v1/tasks/{taskId}/episode` | 查询 Agent Episode Package |
| GET | `/api/v1/tasks/{taskId}/episode/export` | 导出 Agent Episode Package |

推荐前端主要调用：

```text
GET /api/v1/tasks/{taskId}/trace
```

完整 trace 响应结构：

```json
{
  "task": {},
  "agentSnapshot": {},
  "steps": [],
  "ragRetrievals": [],
  "llmCalls": [],
  "toolCalls": [],
  "policyChecks": [],
  "episodeSummary": {},
  "events": [],
  "finalAnswer": "..."
}
```

---

## 15. Evaluation API

V1.0 轻量实现。

### 15.1 评测集

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/eval-datasets` | 查询评测集 |
| POST | `/api/v1/eval-datasets` | 创建评测集 |
| GET | `/api/v1/eval-datasets/{datasetId}` | 查询评测集详情 |
| PATCH | `/api/v1/eval-datasets/{datasetId}` | 修改评测集 |
| DELETE | `/api/v1/eval-datasets/{datasetId}` | 删除评测集 |

### 15.2 评测 Case

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/eval-datasets/{datasetId}/cases` | 查询 case |
| POST | `/api/v1/eval-datasets/{datasetId}/cases` | 创建 case |
| PATCH | `/api/v1/eval-cases/{caseId}` | 修改 case |
| DELETE | `/api/v1/eval-cases/{caseId}` | 删除 case |

### 15.3 评测运行

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/eval-datasets/{datasetId}/runs` | 启动评测 |
| GET | `/api/v1/eval-runs` | 查询评测运行历史 |
| GET | `/api/v1/eval-runs/{runId}` | 查询评测运行详情 |
| GET | `/api/v1/eval-runs/{runId}/results` | 查询评测结果 |
| PATCH | `/api/v1/eval-results/{resultId}/judge` | 人工标记结果 |

### 15.4 Evaluation Harness 增强接口

V1.5 可补充：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/eval-datasets/{datasetId}/runs/compare` | 启动 Prompt / RAG 参数对比评测 |
| GET | `/api/v1/eval-runs/{runId}/episodes` | 查询评测运行关联的 episode 列表 |
| GET | `/api/v1/eval-runs/{runId}/metrics` | 查询评测聚合指标 |

---

## 16. Demo Business API

Demo API 主要用于开发调试和展示工具数据来源。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/demo/orders/{orderNo}` | 查询模拟订单 |
| GET | `/api/v1/demo/payment-logs` | 查询模拟支付日志 |
| GET | `/api/v1/demo/tickets` | 查询模拟工单 |
| POST | `/api/v1/demo/seed` | 初始化演示数据，管理员 |

说明：

- Agent 工具内部可以直接调用 service，不一定通过 HTTP 调用这些 API。
- 暴露 Demo API 是为了方便前端展示和调试。

---

## 17. API 权限边界

V1.0 权限规则：

- 未登录只能访问 `/auth/register`、`/auth/login`。
- 普通用户只能访问自己的知识库、Agent、任务、评测集。
- 管理员可以管理全局工具和 demo seed。
- 工具调用权限由 Agent 绑定关系、tool permission level 和 PolicyGuard 共同决定。

资源归属校验：

```text
knowledge_base.user_id == current_user.id
agent_app.user_id == current_user.id
agent_task.user_id == current_user.id
agent_episode.user_id == current_user.id
eval_dataset.user_id == current_user.id
```

---

## 18. V0.1 API 边界

V0.1 只需实现：

- `POST /api/v1/auth/login`
- `GET /api/v1/users/me`
- `POST /api/v1/knowledge-bases`
- `GET /api/v1/knowledge-bases`
- `POST /api/v1/knowledge-bases/{kbId}/documents`
- `GET /api/v1/knowledge-bases/{kbId}/documents`
- `POST /api/v1/knowledge-bases/{kbId}/retrieve-test`
- `POST /api/v1/agents`
- `GET /api/v1/agents`
- `GET /api/v1/agents/{agentId}`
- `POST /api/v1/agents/{agentId}/tasks`
- `GET /api/v1/tasks/{taskId}`
- `GET /api/v1/tasks/{taskId}/events`
- `GET /api/v1/tasks/{taskId}/trace`
- `GET /api/v1/tools`

V0.1 可以暂时不做：

- Prompt 版本 API。
- 完整工具 CRUD。
- 完整评测 API。
- 复杂任务历史筛选。
- Demo API。

---

## 19. V1.0 API 完成标准

V1.0 API 应支持：

1. 用户可以登录并拥有自己的资源空间。
2. 用户可以创建知识库、上传文档、查看解析状态和 chunks。
3. 用户可以测试知识库检索效果。
4. 用户可以创建 Agent，绑定知识库和工具。
5. 用户可以发起 Agent 任务，并通过 SSE 看到执行过程。
6. 用户可以查看任务历史和完整 trace。
7. 用户可以查看或导出任务的 Agent Episode Package。
8. 用户可以查看工具定义，并测试工具调用。
9. 用户可以创建轻量评测集并运行评测。
10. 管理员可以初始化 demo 数据。

---

## 20. 面试表达重点

后端模块和 API 设计可以这样讲：

1. **模块化单体**
   - 项目没有过早拆微服务，但按用户、知识库、RAG、Agent、工具、任务、Trace、评测拆出清晰模块。

2. **Controller 很薄**
   - Controller 只做 HTTP 入口，核心流程在 Service、AgentEngine、RagService、ToolRuntime 中。

3. **Agent 执行链路清楚**
   - 创建任务后返回 taskId。
   - 后端异步执行。
   - 前端通过 SSE 订阅任务事件。
   - Trace API 可查看完整执行链路，Episode API 可导出运行证据包。

4. **外部依赖可替换**
   - LLM、向量库、文件存储都通过 Gateway 抽象，便于替换模型供应商或 Qdrant/pgvector。

5. **API 支撑演示闭环**
   - 文档上传、RAG 检索、Agent 配置、工具调用、Trace 回放、评测都能通过 API 串起来。

---

## 21. 暂不设计的后端能力

V1.0 暂不设计：

- 复杂组织空间。
- API key 开放平台。
- 完整 OAuth2 授权服务器。
- 多租户计费。
- 插件市场。
- 拖拽式工作流编排 API。
- Kubernetes 运维 API。
- 复杂管理员审计后台。
