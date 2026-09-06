# AgentFlow Hub Frontend 设计

> 文档状态：**NORMATIVE**  
> 权威范围：V0.1 页面边界、后端状态映射、SSE 恢复、事件 reducer 和展示规则  
> 最近审查基线：`main@8d63ced`（V42）；V43/M4G-A 范围冻结于 2026-09-06。
> 前端不得重新定义 TaskStatus、TaskPhase 或 RetrievalReadiness。

---

## 1. 目标

V0.1 前端只负责证明核心 Agent 闭环可用、可观察、可恢复：

```text
配置最小知识库
-> 配置支付 Agent
-> 提交 task
-> 展示执行过程
-> 展示最终答案和引用
-> 查看 Trace
```

前端不是当前项目的主要差异化能力。使用简单、稳定的表格、表单、抽屉和时间线，不建设复杂工作台、流程编辑器或仪表盘。

### 1.1 V43 / M4G-A 当前实现边界

本设计保留完整 V0.1 目标；V43 只实现最小任务运行前端与恢复闭环，契约见
`slice-docs/44_FRONTEND_TASK_RUNTIME_PACKAGE_INTERFACE.md`：

- 前端基础、登录、JWT、退出与认证失效状态清理；
- 选择已有 Agent、自己的任务列表、提交输入及结果未知时复用原 Idempotency-Key；
- Task 状态、阶段、持久事件时间线、答案、引用与取消；
- Bearer SSE、无损 sequence、去重、处理后推进游标、有界重连、gap 停止应用与离页清理；
- 刷新时用公开 Trace 的 events 重建，终态用 GET task 的答案与引用收敛；
- 已有聚合接口的只读 Trace。

第 4.2、4.3、13、14 节的知识库管理和 Agent 配置页面留给后续切片。
V43 使用真实浏览器、后端执行组件和 PostgreSQL，模型与向量允许可控替身；
真实 provider/Qdrant E2E 仍是后续 M4G 与 V0.1 Release Gate 的独立要求。
V43 不增加后端公开接口、不实现任务执行重试、多轮对话或 provider streaming。

---

## 2. 技术栈

```text
Vue 3
TypeScript
Vite
Vue Router
Pinia
Element Plus
Axios
@microsoft/fetch-event-source
```

使用 `fetchEventSource` 而不是原生 `EventSource`，因为 Task SSE 需要 Bearer Authorization header。

---

## 3. 前端状态分层

前端必须区分三类状态：

### 3.1 Server TaskStatus

直接使用后端值：

```text
QUEUED
RUNNING
COMPLETED
FAILED
CANCELLED
TIMED_OUT
```

不得将 `TIMED_OUT` 映射为 `FAILED` 后丢失原因，也不得自行使用 `TIMEOUT` 作为另一种服务端状态。

### 3.2 Server TaskPhase

只有 RUNNING 时存在：

```text
PREPARING
RETRIEVING
DECIDING
EXECUTING_TOOL
GENERATING
```

Phase 用于当前进度提示，不作为历史时间线唯一来源。

### 3.3 Client UI State

客户端自己的网络/交互状态独立保存：

```text
IDLE
SUBMITTING
LOADING_SNAPSHOT
CONNECTING
CONNECTED
RECONNECTING
CLOSED
```

`SUBMITTING` 不是 TaskStatus；`CONNECTED` 也不表示 task 正在运行。不要把客户端状态和服务端状态放进同一个 enum。

---

## 4. V0.1 页面范围

### 4.1 登录页

```text
/login
```

- username/password；
- 登录成功保存 access token；
- token 过期时清除本地状态并跳转；
- 不实现 refresh token UI；
- logout 清除 token、用户、Agent/任务/Trace 缓存、待确认创建请求及运行页状态，并终止请求、SSE 与重连计时器；
- REST/SSE 401 与本地过期采用相同清理路径；旧会话晚到的响应不得写回新会话。

### 4.2 知识库页

```text
/knowledge-bases
/knowledge-bases/:kbId
```

V0.1 可以将知识库列表、创建、文档上传和文档状态放在同一详情页，避免额外页面。

展示：

- name/description；
- embeddingProfileCode，只读；
- chunkStrategyVersion，只读；
- status；
- 文档列表；
- Upload；
- Retrieval Test，可折叠。

文档必须同时展示：

```text
parseStatus
vectorization counts
retrievalReadiness
vectorGeneration
```

只有 `retrievalReadiness=READY` 显示“可用于 Agent”。`parseStatus=COMPLETED` 不能直接显示为“向量化完成”。

Completed 文档 reprocess 在 V0.1 默认隐藏到高级/维护操作，不作为主流程按钮。

### 4.3 Agent 配置页

```text
/agents
/agents/:agentId
```

字段：

- name；
- description；
- systemPrompt；
- chatModelProfileCode，只读或固定选择；
- temperature/topP；
- maxDecisionTurns；
- maxToolCalls；
- maxTotalTokens；
- timeoutSeconds；
- status；
- knowledge binding；
- tool binding。

V0.1 工具选择只显示：

```text
order_query
payment_log_query
```

不显示 `report_generate`、HTTP、MCP、permission level 或 confirmation 配置。

保存前校验：

```text
maxToolCalls < maxDecisionTurns
```

但后端仍是最终校验者。

### 4.4 Agent 执行页

```text
/agents/:agentId/run
/tasks
/tasks/:taskId
```

页面结构：

```text
Header: Agent 名称、Task 状态、取消按钮
Main: 用户输入 + 最终答案/临时答案
Right/Bottom: 执行时间线
Evidence: 引用列表
Action: 查看 Trace
```

V0.1 不做多轮 conversation。每次提交创建独立 task；历史 task 可以通过最近任务列表进入。

SSE 实时展示任务阶段和工具过程。由于 V0.1 Final Generation 仍使用同步非流式 LlmGateway，答案增量可以只有一个完整 `ANSWER_CHUNK`；前端不得用逐字动画伪装 provider token streaming。

### 4.5 Trace 页

```text
/tasks/:taskId/trace
```

V0.1 使用 tabs 或折叠面板：

- Overview；
- Steps；
- RAG；
- LLM Calls；
- Tool Calls；
- Events。

不实现复杂 DAG、火焰图、Episode 页面或 Evaluation 页面。

---

## 5. Task 创建交互

### 5.1 请求

每次用户明确发起一个新 task 时生成新的 opaque idempotency key：

```ts
const idempotencyKey = crypto.randomUUID()
```

调用：

```http
POST /api/v1/agents/{agentId}/tasks
Idempotency-Key: <uuid>
```

在请求结果明确前，重复点击使用**同一个** key，而不是生成新 key。只有用户主动发起新的 task 才生成新 key。

### 5.2 成功

保存：

```text
taskId
status
phase
serverLastEventSequence
idempotencyKey
```

当前创建响应为 `AgentTaskResponse`，没有 `eventsUrl`。前端根据 taskId 构造
`/api/v1/tasks/{taskId}/events`；新建返回 201，幂等复用返回 200，冲突返回 409。

`serverLastEventSequence` 表示服务端已经提交到哪里，不代表客户端已经处理到哪里。新 task 的本地
`lastProcessedSequence` 初始化为 `0`。V43 进入运行页时先 GET 公开 Trace，从 0 严格校验并应用
其中完整 events，再从实际已处理游标连接 SSE；Trace 读取之后提交的事件由 SSE replay 补发。
若采用直接订阅方式则必须从 `afterSequence=0` 开始，两种方式都不能丢失 TASK_CREATED 等早期事件。

不得直接把创建响应的 `lastEventSequence` 复制到 `lastProcessedSequence`，否则客户端会跳过尚未读取的事件。

### 5.3 网络结果未知

POST 超时或连接断开时，不立即生成新 key 重试。保留原 key、agentId 和原始 userInput（包括空白），
重发完全相同的请求，后端返回同一 task 或明确冲突。刷新后也保留当前会话的待确认请求，
退出、认证失效或切换用户时清除；不能把未知结果自动解释为创建失败。

---

## 6. Task Store

建议状态：

```ts
interface TaskRuntimeState {
  task: AgentTaskDetail | null
  uiState: TaskUiState
  serverLastEventSequence: string
  lastProcessedSequence: string
  timeline: TaskTimelineItem[]
  draftAnswer: string
  finalAnswer: string | null
  citations: Citation[]
  reconnectAttempts: number
  streamError: string | null
}
```

ID 与游标一律保存为十进制 `string`，不得经 JavaScript number 中转。后端业务 ID 已为字符串，
但 `lastEventSequence`、`sequenceNo` 与 STREAM_ERROR 的游标仍是 JSON numeric long；
REST 和 SSE 均须从响应原文无损解析，再使用 BigInt 比较/加一。普通 `JSON.parse` 后转字符串无法恢复已丢失精度。

`serverLastEventSequence` 只用于判断服务端是否还有未处理事件；SSE cursor 始终使用 `lastProcessedSequence`。

`timeline` 是 event 投影。完整 Trace 由 Trace API 加载，不把所有 Prompt/result 长期存入 Pinia。

---

## 7. SSE 连接与恢复

### 7.1 建立连接

```ts
await fetchEventSource(
  `/api/v1/tasks/${taskId}/events?afterSequence=${lastProcessedSequence}`,
  {
    headers: { Authorization: `Bearer ${token}` },
    signal,
    onmessage(event) {
      applyTaskEvent(event)
    }
  }
)
```

### 7.2 Sequence 去重

收到事件时：

1. 从原始 JSON 无损解析 `event.id` 和 data.sequenceNo，只接受 ASCII 十进制整数及 signed long 范围；
2. 二者必须相同，data.taskId 必须属于当前任务，SSE event 名必须等于 data.eventType；
3. `sequenceNo <= lastProcessedSequence`：忽略；
4. `sequenceNo == lastProcessedSequence + 1`：应用；
5. 出现 gap 或非法事件：停止增量应用并释放连接，展示错误；用户点击“刷新并恢复”后通过已有 GET trace 重建，完整性校验仍失败则继续停止，不跳过缺口；
6. 应用成功后再更新 lastProcessedSequence；
7. 同时更新 serverLastEventSequence 的最大值。

不能只依赖浏览器库“应该不会重复”。

Trace event 的时间字段为 `createdAt`，SSE data 为 `timestamp`，恢复时需显式适配。
SSE comment 不推进游标；无 id 的 `STREAM_ERROR` 是连接控制帧，不是 TaskStatus 或持久事件。
不得用其 `lastSentSequence` 更新客户端已处理游标。服务端首批 gap 为 409 JSON；建流后的 gap 为控制帧或 EOF。

### 7.3 断线重连

- 指数退避，设置最大间隔及累计尝试上限；达到上限显示断线状态并保留手动恢复入口；
- 始终携带 lastProcessedSequence；
- 401 不自动无限重连，清除 token；
- 404 表示 task 不可见，停止；
- terminal task 在处理完 terminal event 并完成 GET 收敛后不再重连；
- EOF 本身不代表任务完成，未确认终态时按已处理游标有界重连；
- 重连失败时仍允许用户刷新 task detail；离开运行页、切换任务或会话时终止连接及计时器。

### 7.4 页面刷新

刷新流程：

1. GET `/api/v1/tasks/{taskId}/trace`，取同一快照的 task/events（当前没有独立完整 events snapshot REST 接口）；
2. 按 sequence 重建 timeline，并将 `lastProcessedSequence` 设置为**实际成功应用的最大 sequence**；
3. 若 trace.task 非终态，从 lastProcessedSequence 建立 SSE；
4. GET 与 SSE 之间产生的事件由 replay 补发；
5. 若已终态，GET task 并再次读取 Trace，核对状态、游标、答案、引用一致后，以 GET task 的 finalAnswer/citations 收敛。

Trace 自带 task、events，属于同一读快照。重建时从 0 验证连续 events，只有成功应用到该快照
`trace.task.lastEventSequence` 才能宣称完整恢复。不同 GET 返回可能跨越终态，不能用较旧响应覆盖已知终态。
Trace 已终态时仍须重建历史，并 GET task 确认最终答案/引用；终态 GET 暂时失败时显示“待同步”，允许重试 GET。

不得直接将 task.lastEventSequence 当成已处理 cursor，除非对应事件列表已经全部成功应用。

### 7.5 临时答案和最终答案

`ANSWER_CHUNK` 追加到 `draftAnswer`。V0.1 通常只收到一个完整答案 chunk，但 reducer 保持支持多个合并 chunk。收到 terminal event 后：

1. GET task detail；
2. 用 `task.finalAnswer` 覆盖 draftAnswer；
3. 使用 task.citations；
4. 清除不完整尾部；
5. 关闭 SSE。

SSE draft 永远不是最终数据源。

---

## 8. Event Reducer

### 8.1 事件映射

| Event | UI 行为 |
| --- | --- |
| `TASK_CREATED` | 建立 timeline 起点 |
| `TASK_STARTED` | 显示开始执行 |
| `PHASE_CHANGED` | 更新当前 phase，不重复创建大量相同行 |
| `RAG_FINISHED` | 显示 validHitCount、candidateCount、staleHitCount；耗时和引用详情从 Trace 读取 |
| `DECISION_FINISHED` | 显示 `CALL_TOOL` 或 `FINISH`，不显示隐藏推理 |
| `TOOL_STARTED` | 新增工具调用进行中项 |
| `TOOL_FINISHED` | 按 stepId 更新对应项，事件没有 toolCallId |
| `FINAL_GENERATION_STARTED` | 显示生成中 |
| `ANSWER_CHUNK` | 追加临时答案，可为完整答案单块 |
| `TASK_COMPLETED` | 拉取 task，展示成功/受预算限制原因 |
| `TASK_FAILED` | 拉取 task，展示 errorCode/safe message |
| `TASK_CANCELLED` | 展示已取消 |
| `TASK_TIMED_OUT` | 展示整体超时 |

### 8.2 Timeline 不显示 chain-of-thought

展示：

- decision type；
- tool code/name；
- 简短 reason；
- RAG hit count；
- safe summary；
- latency；
- status。

不展示：

- 自由形式思维链；
- 完整 provider 原始响应；
- 内部 Prompt rules；
- handler/config；
- Authorization/API key；
- 未脱敏日志正文。

完整可公开 Trace 仍需要折叠和访问控制。

---

## 9. 状态展示

### 9.1 TaskStatus 标签

| 状态 | 文案 |
| --- | --- |
| `QUEUED` | 等待执行 |
| `RUNNING` | 执行中 |
| `COMPLETED` | 已完成 |
| `FAILED` | 执行失败 |
| `CANCELLED` | 已取消 |
| `TIMED_OUT` | 已超时 |

颜色和样式由主题决定，业务逻辑不依赖颜色。

### 9.2 Phase 文案

| Phase | 文案 |
| --- | --- |
| `PREPARING` | 正在准备执行配置 |
| `RETRIEVING` | 正在检索知识库 |
| `DECIDING` | 正在判断下一步动作 |
| `EXECUTING_TOOL` | 正在查询业务数据 |
| `GENERATING` | 正在生成最终答案 |

### 9.3 terminationReason

`COMPLETED` 也可能带预算受限原因：

- `ANSWERED`：正常完成；
- `MAX_DECISION_TURNS`：基于已有证据生成部分答案；
- `MAX_TOOL_CALLS`：达到工具次数上限后生成部分答案。

UI 需要在答案顶部显示非阻断提示，不能把这两种完成伪装成完全正常，也不能简单显示为失败。

---

## 10. 取消交互

- 仅 QUEUED/RUNNING 显示取消按钮；
- 点击后按钮进入 pending；
- RUNNING cancel 响应可能仍是 RUNNING；
- 显示“取消请求已提交，当前外部调用结束后生效”；
- 不提前把本地状态改为 CANCELLED；
- 最终状态来自 task/event；
- terminal task 的重复取消视为幂等，不弹错误。

---

## 11. 引用展示

最终答案中的 `[S1]` 等 marker 按实际 citationId 映射到 task.citations。当前 V40 输出字段为：

```text
citationId
chunkId
documentId
vectorGeneration
```

当前 task.citations 不含 fileName/titlePath/score/contentPreview。需要展示证据正文或 score 时，
通过公开 Trace 的 `steps[].ragRetrievals[].hits[]` 按 citationId 关联 `contentSnapshot`、`score`、
`metadataSnapshot`，不得假设 metadata 一定含文件名或标题。

交互：

- 点击 marker 打开 evidence drawer；
- 只显示后端验证过的 citation；
- citation 不存在时前端不自行猜测；
- 证据正文默认折叠并控制显示长度；
- V43 只展示引用与 Trace 历史快照，不提供尚未实现的文档/chunk 页面跳转；
- 历史证据显示为“历史快照”，缺失的 metadata 显示缺省值，不推断源文档是否已删除。

---

## 12. Trace 页面

只消费 `GET /api/v1/tasks/{taskId}/trace` 的公开聚合：`task`、`executionSnapshot`、`steps`、`events`。
LLM/RAG/tool 记录嵌套在各 step 的 `llmCalls`、`ragRetrievals`、`toolCalls` 中，页面可投影成独立面板，
不假设存在独立 Trace 子接口或顶层 `rag`、`llmCalls`。可展示的字段以当前公开 DTO 为准。

### Overview

- task status/phase/terminationReason；
- userInput；
- Agent snapshot 摘要；
- model profile；
- corpus snapshot；
- tool snapshot；
- budget used/max；
- total latency；
- final answer。

### Steps

按 stepIndex 展示：

```text
PRE_RETRIEVAL
LLM_DECISION
TOOL_CALL
LLM_FINAL_GENERATION
```

### RAG

- query；
- profile；
- candidate/valid/stale count；
- hits/citation；
- content snapshot；
- latency。

### LLM

- call type；
- requested/resolved model；
- usage 和 quality；
- latency；
- status/error；
- Prompt/response 默认折叠；
- 不显示 chain-of-thought。

### Tool

- tool code/name；
- arguments/result snapshot；
- status；
- latency；
- error；
- 默认脱敏和折叠。

### Events

用于检查 SSE sequence，不替代专项日志。

---

## 13. Knowledge UI

文档列表筛选：

- parseStatus；
- retrievalReadiness；
- fileType。

上传后：

- 显示 parse 和 vectorization 两阶段；
- 可以每 2–3 秒轮询非终态文档；
- 达到前端轮询上限后停止自动轮询，但不把 document 标记失败；
- 用户可手动刷新；
- 只有 READY 文档计入“可检索文档数”。

Retrieval Test 页面可以作为知识库详情中的折叠区域，不单独建设完整调试工作台。

---

## 14. Agent UI

V0.1 不显示尚未实现的功能：

- Prompt versions；
- conversation memory；
- PolicyGuard；
- requiresConfirmation；
- HTTP/MCP tool；
- arbitrary model provider；
- semantic chunking；
- rerank；
- Evaluation。

禁用 Agent 时明确提示：新 task 无法创建；已经 RUNNING 的 task 继续使用 snapshot，除非后端返回平台级资源撤销。前端不自行终止。

---

## 15. 错误体验

错误区域至少显示：

```text
errorCode
safe message
Task ID
查看 Trace
新建独立 Task（后续可选交互）
```

不得只显示“请求失败”。

V43 不提供任务执行重试。用户从入口主动提交独立新任务时生成新的 Idempotency-Key；
网络结果未知的创建请求重发时使用原 key，两者必须区分。

错误 UI 不显示 stack、provider body、SQL、内部 URL 或本地路径。

---

## 16. V1.x 后续页面

V0.1 完成后再考虑：

- Task history 完整筛选；
- Prompt version；
- conversation；
- Tool 管理；
- Evaluation；
- Episode export；
- PDF 预览；
- Policy/approval；
- 成本报表；
- provider token streaming 和更细粒度 timeline。

这些页面不得先于对应后端事实和状态机出现。

---

## 17. 前端验收

必须验证：

1. BIGINT ID、REST/SSE numeric long 游标不发生 JS 精度丢失；
2. POST 网络未知时使用原 Idempotency-Key；
3. 新 task 从 0 重建完整 Trace events，再从已处理游标 SSE replay，保留所有早期事件；
4. serverLastEventSequence 与 lastProcessedSequence 不混用；
5. Server status、phase 和 client uiState 分离；
6. TIMED_OUT 单独展示；
7. parseStatus 与 retrievalReadiness 分开展示；
8. SSE 重复事件被忽略；
9. sequence gap 停止应用并提供手动“刷新并恢复”，不能继续错误追加；
10. 页面刷新后 timeline/final answer 可恢复；
11. terminal 后以 task.finalAnswer 覆盖 draft；
12. cancel 不提前伪造终态；
13. citation 只来自后端白名单；
14. Trace 不泄漏内部配置或 chain-of-thought；
15. V0.1 不伪装 provider token streaming；
16. V0.1 主流程只需要少量页面即可完整演示。

V43/M4G-A 验收适用以上任务运行相关项；第 7 项知识库状态页面及完整 V0.1 页面主流程留给后续。
V43 核心证据必须覆盖真实浏览器登录 → 预配置 Agent 创建任务 → 持久事件 → 断网重连与刷新 →
终态 → 答案、引用与 Trace 一致，同时明确模型/向量替身边界；不以此宣称整个 M4G 或 V0.1 完成。
