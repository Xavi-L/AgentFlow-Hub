# V35 同步通用 LLM Gateway 接口包说明

> 状态：本地实现与自动化验收完成。V34 已提交并推送为 `4296b39`；V35 在该基线上新增
> M4 首个 Agent 执行基础设施切片。JDK 21、显式 Mockito javaagent 聚焦测试与完整 Maven
> suite 均已执行。本轮使用本地 HTTP stub 验证真实序列化、响应解析与失败边界；未配置或调用
> 真实外部 LLM provider。

## 1. 切片目标与基线判断

V35 建立同步、非流式、OpenAI-compatible 的通用 `LlmGateway.chat`，返回文本与调用元数据，供
后续 `AgentEngine` 使用；同时让 V9 Knowledge Chat 作为领域适配器复用该边界，不再维护第二套
provider HTTP 客户端。

路线顺序来自两处既有规格：

- `spec-docs/agentflow-hub-implementation-roadmap.md` 的实现顺序明确为 `agent app CRUD → LLM
  gateway → AgentEngine`；
- `spec-docs/agentflow-hub-backend-api-design.md` 将 `LlmGateway` 定义为屏蔽具体模型供应商的内部接口。

V35 不是“项目首次调用模型”。V9 已实现 `ChatGateway` 与 OpenAI-compatible Knowledge Chat；但它只
接受 query/context/answer cap，只返回 answer，并拥有知识问答 instruction。该边界不承载 Agent 级模型、
采样参数、消息历史、usage、finish reason 或调用元数据，因此不能直接作为 `AgentEngine` 的通用接口。

V35 完成后只能声明：

> 后端已经建立可复用的同步 LLM 调用边界，并将现有 Knowledge Chat 接入该边界；后续
> AgentEngine 可以在不依赖具体供应商 API 的情况下发起模型调用。

仍不能宣称 Agent 已可执行、`DISABLED` 已阻止执行、工具调用闭环已完成或 Trace 已落库。

## 2. 通用包接口

包路径固定为：

```text
com.agentflow.infra.llm
```

主接口：

```java
public interface LlmGateway {
    LlmChatResult chat(LlmChatRequest request);
}
```

输入：

```java
record LlmChatRequest(
    String modelProvider,
    String modelName,
    List<LlmMessage> messages,
    BigDecimal temperature,
    BigDecimal topP,
    int maxOutputTokens
) {}
```

消息只支持：

```text
SYSTEM
USER
ASSISTANT
```

消息顺序与正文逐项保留；Gateway 不 trim、拼接、重排或将消息转换成领域 Prompt。V35 不接受
`TOOL` 角色、tool schema、tool result 或多模态内容。

`maxOutputTokens` 是一次上游调用的输出上限，只映射到 `max_tokens`。它与 `agent_app.max_tokens`
语义不同：后者是整个 Agent 任务的 token 预算。V35 不读取 `agent_app`，也不把任务预算直接当成单次
output cap；后续由 `AgentEngine/BudgetGuard` 根据剩余预算计算每次请求值。

结果：

```java
record LlmChatResult(
    String content,
    String resolvedModel,
    String finishReason,
    LlmTokenUsage usage,
    String providerRequestId,
    long latencyMs
) {}
```

字段语义：

| 字段 | 语义 |
| --- | --- |
| `content` | provider 返回的单个非空文本 choice，保留正文，不 trim |
| `resolvedModel` | provider response 中实际返回的 model；缺失时为 `null`，不回填请求模型 |
| `finishReason` | Spring AI choice finish reason 规范化为小写；缺失时为 `null` |
| `usage` | provider token usage；缺失时为明确 unknown |
| `providerRequestId` | provider response/chat completion ID；缺失时为 `null` |
| `latencyMs` | 当前进程内从 `ChatModel.call` 前到完成解析后的单调时钟耗时，非 provider 服务端耗时 |

`LlmTokenUsage` 使用 nullable `Integer` 表达未知：

```text
known:   inputTokens/outputTokens/totalTokens 均非 null，可合法为 0
unknown: inputTokens/outputTokens/totalTokens 均为 null
```

不得把 Spring AI `EmptyUsage` 暴露的三个 0 当作 provider 返回的真实 0。部分字段存在、部分字段缺失也
不伪造成完整 usage；V35 将其视为 unknown。

## 3. 输入校验与配置边界

以下条件在任何 `ChatModel.call` 或网络调用之前以 `CONFIGURATION` 拒绝：

- request 为 null；
- `modelProvider` 不是精确值 `openai-compatible`；
- `modelName` 为 null 或全空白；
- messages 为 null、空列表、包含 null、role 为 null，或正文为 null/全空白；
- `temperature` 为 null 或不在 `0..2`；
- `topP` 为 null 或不在 `(0,1]`；
- `maxOutputTokens < 1`。

Gateway 对合法值不做静默降级：不会丢弃 model、temperature、topP 或 max output cap，也不会根据模型名
猜测能力。reasoning model 的 `max_completion_tokens`、不支持 temperature/topP 的模型、供应商能力注册表
与参数自动兼容不在 V35；provider 不接受参数时进入 `PROVIDER_REJECTED`。

连接配置继续使用：

```text
OPENAI_BASE_URL   -> agentflow.llm.base-url
OPENAI_API_KEY    -> agentflow.llm.api-key
OPENAI_CHAT_MODEL -> agentflow.llm.chat-model（仅 V9 固定模型）
OPENAI_TIMEOUT    -> agentflow.llm.timeout
```

base URL 必须是没有内嵌 credentials、query 或 fragment 的绝对 HTTP(S) URL；timeout 必须为
`1ms..Integer.MAX_VALUE ms`。空 API key 使用 Spring AI `NoopApiKey`，因此不发送伪造的 Authorization
header；非空 key 去首尾空白后发送 Bearer。

## 4. Spring AI 与上游 wire contract

V35 在 `backend/pom.xml` 引入：

```text
spring-ai-bom:1.1.8
spring-ai-openai
```

没有引入 OpenAI starter。`SpringAiConfig` 手工创建 `OpenAiApi`、`OpenAiChatModel` 与 `LlmGateway`，
使 AgentFlow 继续拥有连接属性、timeout、重试和工具执行策略。Spring AI 类型只存在于 infra/config
实现内部，不出现在 `LlmGateway` 公共包接口的请求/结果中。

每次调用使用 `ChatModel.call(Prompt)` 与 request-level `OpenAiChatOptions`：

```text
model = request.modelName
temperature = request.temperature
topP = request.topP
maxTokens = request.maxOutputTokens
n = 1
internalToolExecutionEnabled = false
```

默认 options 与每请求 options 都固定 `internalToolExecutionEnabled=false`。V35 不注册 tool callback/schema；
即使后续加入 tool schema，也必须由自研 `AgentEngine + ToolRuntime` 驱动循环，不能让框架在预算、权限和
Trace 之外自动执行工具。

Spring AI 默认 retry 未被采用。V35 注入 `maxAttempts=1`、无 backoff 的 `RetryTemplate`，所以一个
`LlmGateway.chat` 最多发起一次上游请求，不对连接失败、timeout、4xx 或 5xx 自动重试。

同步 wire contract 为：

```http
POST {OPENAI_BASE_URL}/chat/completions
Authorization: Bearer <OPENAI_API_KEY>   # 仅 key 非空时存在
Content-Type: application/json
```

```json
{
  "model": "<request.modelName>",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "..."}
  ],
  "temperature": 0.2,
  "top_p": 0.8,
  "max_tokens": 1024,
  "n": 1,
  "stream": false
}
```

V35 只调用非流式路径；`streamChat`、SSE 与 conversation 留给后续切片。

## 5. 稳定失败类型与脱敏规则

内部失败类型固定为：

| 类型 | 条件 |
| --- | --- |
| `CONFIGURATION` | 请求/采样/output cap/provider/model/messages 或连接属性非法 |
| `TIMEOUT` | cause chain 含 socket、HTTP 或通用 timeout |
| `TRANSPORT` | 连接拒绝、DNS、无路由或其他 socket 传输失败 |
| `PROVIDER_REJECTED` | provider 返回 4xx 或 5xx |
| `MALFORMED_RESPONSE` | JSON 无法解析、choice 数不是 1、output/content 缺失或空白、响应结构异常 |

`LlmGatewayException` 只保留稳定类型与安全消息，不保留原 provider 异常作为 cause。因此异常对象及其
cause chain 不含 API key、完整 provider body 或内部 endpoint URL。`SpringAiConfig` 的 response error
handler 也不读取或拼接 provider body。

V35 没有公共 HTTP 入口，因此不新增 `LLM_PROVIDER_ERROR` 或修改 `GlobalExceptionHandler`。后续 task/
AgentEngine 负责把内部类型映射为任务失败码。

## 6. V9 Knowledge Chat 收敛

V9 公共 HTTP、DTO、citation 与 owner/RAG 契约保持不变。实现链调整为：

```text
KnowledgeChatService
  -> ChatGateway.generate(ChatRequest)
     -> OpenAiCompatibleChatGateway                 (V9 领域适配器)
        -> LlmGateway.chat(LlmChatRequest)           (V35 通用边界)
           -> SpringAiOpenAiCompatibleLlmGateway
              -> ChatModel.call(Prompt)
```

V9 领域适配器继续负责：

- 固定 Knowledge Chat system instruction；
- 把 V8 query 与 context 按原有 `Question/Context` 格式传入，context 不 trim、不重装配；
- 从 `OPENAI_CHAT_MODEL` 读取服务端模型；
- 固定 temperature `0.2`、topP `0.8`，把 V9 `maxAnswerTokens` 映射为本次
  `maxOutputTokens`；
- 只取 `LlmChatResult.content` 作为原有 answer，不把通用调用元数据加入 V9 HTTP DTO；
- 将任意 `LlmGatewayException` 转换为无 cause、无 provider 细节的现有 `ChatGatewayException`。

`KnowledgeChatService` 继续把该异常映射为
`503 KNOWLEDGE_CHAT_GATEWAY_UNAVAILABLE`；citation 提取、至少一个合法 `[S#]`、未知/畸形 citation
的 502 语义均未移动到通用 Gateway。通用 Gateway 不 import 或调用 Knowledge、RAG、owner、citation、
Qdrant 或 document 代码。

## 7. 实现文件与 schema 边界

V35 新增：

```text
slice-docs/36_LLM_GATEWAY_PACKAGE_INTERFACE.md
backend/src/main/java/com/agentflow/config/SpringAiConfig.java
backend/src/main/java/com/agentflow/infra/llm/LlmGateway.java
backend/src/main/java/com/agentflow/infra/llm/LlmChatRequest.java
backend/src/main/java/com/agentflow/infra/llm/LlmChatResult.java
backend/src/main/java/com/agentflow/infra/llm/LlmMessage.java
backend/src/main/java/com/agentflow/infra/llm/LlmMessageRole.java
backend/src/main/java/com/agentflow/infra/llm/LlmTokenUsage.java
backend/src/main/java/com/agentflow/infra/llm/LlmGatewayException.java
backend/src/main/java/com/agentflow/infra/llm/LlmFailureType.java
backend/src/main/java/com/agentflow/infra/llm/SpringAiOpenAiCompatibleLlmGateway.java
backend/src/test/java/com/agentflow/config/SpringAiConfigTest.java
backend/src/test/java/com/agentflow/infra/llm/SpringAiOpenAiCompatibleLlmGatewayTest.java
backend/src/test/java/com/agentflow/infra/llm/SpringAiOpenAiCompatibleLlmGatewayHttpTest.java
```

V35 将共享 `OpenAiChatProperties` 从 Knowledge 包移动到 `com.agentflow.config`，并删除已无调用方的
`ChatRestClientFactory`。修改范围还包括 `backend/pom.xml`、`application-dev.yml`、V9 adapter/config/
tests 及本 V9 契约文档。

V35 不新增或修改 Controller、公开 DTO、`.http` Agent 路由、ErrorCode、Flyway migration、表、索引、
状态枚举或 `agent_app`。仓库 migration 文件仍只有 V1–V16，schema version 继续为 16。

## 8. 自动化验收证据

2026-09-01 使用 Microsoft OpenJDK 21.0.11 与显式 Mockito `-javaagent` 实际执行：

```text
V35 + V9 focused suite: 27/27 passed
complete backend suite:  457/457 passed
Failures: 0, Errors: 0, Skipped: 0
```

聚焦测试覆盖：

1. 两次请求使用不同 model、temperature、topP、max output cap，真实 JSON 不串值；
2. SYSTEM/USER/ASSISTANT 顺序与包含首尾空格/换行的正文保持不变；
3. 非空 API key 发送 Bearer，空 key 不发送 Authorization；
4. `stream=false`、`n=1`、无 tools/max_completion_tokens，并且一次调用只产生一次上游请求；
5. content、resolved model、finish reason、usage、provider response ID 与 latency 正确映射；
6. provider 未返回 usage 时三个 token 字段均为 null，真实零值与 unknown 可区分；
7. 所有非法输入在 `ChatModel` 零交互时失败；
8. 400、500、连接拒绝、read timeout、畸形 JSON、无 choice、多 choice 与空 content 的稳定分类；
9. provider body、key、URL 不进入 `LlmGatewayException` 消息或 cause chain；
10. default/request options 均关闭内部工具执行，Spring retry 最大尝试次数为 1；
11. V9 固定 instruction、原样 context、异常转换、citation 与既有 503 行为回归；
12. 完整 suite 包含 V27–V34、V9 及此前全部自动化测试。

本地 HTTP stub 真实接收 Spring AI 发出的 loopback HTTP 请求并返回合成 OpenAI-compatible JSON，因此能
证明当前序列化、header、解析和错误适配，但不能表述为真实 OpenAI、线上 provider、模型质量、外网可用性
或生产 SLA 验收。本轮未执行 opt-in 外部 provider smoke，也未启动 PostgreSQL；schema 结论来自没有新增
migration、现有 V1–V16 migration 集合与完整自动化回归。

## 9. 明确不做

- Agent HTTP 测试、对话或任务创建端点；
- Agent owner/live/`ACTIVE` 查询和禁用执行门槛；
- `AgentEngine`、`PromptBuilder`、`DecisionParser`、`BudgetGuard`；
- 原生 tool schema、`tool_calls` 解析或任何工具执行；
- RAG 编排、知识库/工具绑定；
- `streamChat`、SSE、conversation；
- `agent_task`、`agent_step`、`llm_call_log` 或 Trace；
- provider 路由中心、密钥管理后台、模型能力探测；
- reasoning model 参数自动兼容；
- retry、fallback、熔断、限流或成本计算；
- 真实外部 provider 的默认自动验收。

## 面试问题与回答

### 问题 1：为什么不让 V9 的 `ChatGateway` 直接成为 AgentEngine 的模型接口？

**回答：** V9 已实现的 `ChatGateway` 是知识问答领域端口，只接受 query、V8 context 与 answer cap，并且
固定知识问答 instruction、只返回字符串；它无法表达 Agent 的 request-level model、采样参数、消息历史、
usage、finish reason 或调用元数据。V35 保留 V9 端口以稳定既有 HTTP/citation 契约，再让 V9 adapter 调用
通用 `LlmGateway`，从而复用 provider 客户端而不把 RAG/citation 规则塞进 infra。

### 问题 2：为什么 `maxOutputTokens` 不能直接使用 `agent_app.max_tokens`？

**回答：** 数据模型中的 `agent_app.max_tokens` 是整个任务预算；一个 Agent 任务未来可能包含多次思考、工具
观察和最终回答调用。V35 的 `maxOutputTokens` 只限制单次 `max_tokens`。如果直接复制任务预算到每次调用，
多步循环可能在每一步都消耗完整预算。预算分配与剩余量计算属于后续 `AgentEngine/BudgetGuard`，V35 不读取
`agent_app`。

### 问题 3：怎样保证 Spring AI 不绕开自研 AgentEngine 自动执行工具或重试？

**回答：** 已实现配置在 ChatModel default options 与每请求 `OpenAiChatOptions` 上都设置
`internalToolExecutionEnabled=false`，不注册 tool callback/schema；HTTP stub 也确认请求无 tools。同时不用
Spring AI 默认 retry，而是注入 `maxAttempts=1`、无 backoff 的 `RetryTemplate`，400/500 测试分别确认只收到
一次请求。V35 尚未加入工具调用；后续必须由 `AgentEngine + ToolRuntime` 显式驱动并记录预算、权限与 Trace。

### 问题 4：provider 没有返回 usage 时，为什么不能返回三个 0？

**回答：** 0 可能是 provider 真正报告的合法计数，而 Spring AI 的 `EmptyUsage` 也用 getter 返回 0 表示缺失；
把缺失写成 0 会把“未知”伪装成“已测得且为零”。V35 显式识别 `EmptyUsage`，用三个 nullable 字段全为 null
表示 unknown，并用测试证明真实 `0/0/0` 与 unknown 不相等。

### 问题 5：当前失败分类和脱敏能证明什么，不能证明什么？

**回答：** 自动化已证明非法配置、timeout、transport、4xx/5xx 与 malformed response 会得到稳定内部类型，
且 `LlmGatewayException` 不携带 provider 原异常 cause、完整 body、key 或 URL；V9 仍映射为既有 503。它不
代表已实现公共 LLM 错误码、task 失败持久化、线上告警、fallback/熔断或 provider SLA，这些均未纳入 V35。

### 问题 6：本地 HTTP stub 与真实 provider smoke 的证据强度有什么区别？

**回答：** 本地 stub 证明当前 Spring AI 1.1.8 代码会按约定发送 JSON/header，并能解析合成的成功、usage
缺失与错误响应；它不证明任一真实 endpoint/model 支持这些参数或能稳定生成答案。本轮未运行外部 smoke。
若另行配置真实 OpenAI-compatible 服务，一次 opt-in smoke 也只能证明该具体 endpoint/model 在当次配置下
可用，不能扩张为生产能力或模型质量结论。
