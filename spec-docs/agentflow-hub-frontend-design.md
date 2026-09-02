# AgentFlow Hub Frontend 设计

> 文档状态：**NORMATIVE**  
> 权威范围：V0.1 页面边界、后端状态映射、SSE 恢复、事件 reducer 和展示规则  
> 最近审查基线：`main@f276549`（V36）  
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
- logout 为清除本地 token。

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
/tasks/:taskId
```

页面结构：

```text
Header: Agent 名称、Task 状态、取消按钮
Main: 用户输入 + 最终答案/临时流式答案
Right/Bottom: 执行时间线
Evidence: 引用列表
Action: 查看 Trace
```

V0.1 不做多轮 conversation。每次提交创建独立 task；历史 task 可以通过最近任务列表进入。

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

每次用户点击发送时生成不可复用的 opaque idempotency key：

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
lastEventSequence
eventsUrl
idempotencyKey
```

随后从 `lastEventSequence` 建立 SSE。即使 task 已经开始，数据库 replay 会补发后续事件。

### 5.3 网络结果未知

POST 超时或连接断开时，不立即生成新 key 重试。使用原 key 重发创建请求，后端应返回同一 task 或明确冲突。

---

## 6. Task Store

建议状态：

```ts
interface TaskRuntimeState {
  task: AgentTaskDetail | null
  uiState: TaskUiState
  lastProcessedSequence: number
  timeline: TaskTimelineItem[]
  draftAnswer: string
  finalAnswer: string | null
  citations: Citation[]
  reconnectAttempts: number
  streamError: string | null
}
```

ID 一律使用 `string`，不得转换为 JavaScript number。

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

1. 严格解析 `event.id` 和 data.sequenceNo；
2. 二者必须相同；
3. `sequenceNo <= lastProcessedSequence`：忽略；
4. `sequenceNo == lastProcessedSequence + 1`：应用；
5. 出现 gap：停止增量应用，调用 snapshot/trace 恢复；
6. 应用成功后再更新 lastProcessedSequence。

不能只依赖浏览器库“应该不会重复”。

### 7.3 断线重连

- 指数退避，但设置最大间隔；
- 始终携带 lastProcessedSequence；
- 401 不自动无限重连，清除 token；
- 404 表示 task 不可见，停止；
- terminal task 不再重连；
- 重连失败时仍允许用户刷新 task detail。

### 7.4 页面刷新

刷新流程：

1. GET task detail；
2. GET trace 或 events snapshot，重建 timeline；
3. 以 task.finalAnswer/citations 为权威；
4. 若 task 非终态，以快照的 lastEventSequence 建立 SSE；
5. GET 与 SSE 之间产生的事件由 replay 补发。

### 7.5 临时答案和最终答案

`ANSWER_CHUNK` 只追加到 `draftAnswer`。收到 terminal event 后：

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
| `RAG_FINISHED` | 显示命中数量、耗时和 citation 摘要 |
| `DECISION_FINISHED` | 显示 `CALL_TOOL` 或 `FINISH`，不显示隐藏推理 |
| `TOOL_STARTED` | 新增工具调用进行中项 |
| `TOOL_FINISHED` | 按 toolCallId 更新对应项 |
| `FINAL_GENERATION_STARTED` | 显示生成中 |
| `ANSWER_CHUNK` | 追加临时答案 |
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

最终答案中的 `[C1]` 映射到 task.citations：

```text
citationId
fileName
titlePath
score
contentPreview
chunkId
documentId
```

交互：

- 点击 marker 打开 evidence drawer；
- 只显示后端验证过的 citation；
- citation 不存在时前端不自行猜测；
- contentPreview 有长度限制；
- 可跳转到对应文档/chunk；
- 源文档已删除时仍显示 Trace snapshot，并标注“历史快照”。

---

## 12. Trace 页面

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

禁用 Agent 时明确提示：新 task 无法创建；已经 RUNNING 的 task 是否继续以其 snapshot 为准，由后端契约决定，前端不自行终止。

---

## 15. 错误体验

错误区域至少显示：

```text
errorCode
safe message
Task ID
查看 Trace
重试为新 Task
```

不得只显示“请求失败”。

重试 task 时生成新的 Idempotency-Key；网络结果未知的创建请求重发时使用原 key，两者必须区分。

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
- 更复杂 timeline。

这些页面不得先于对应后端事实和状态机出现。

---

## 17. 前端验收

必须验证：

1. BIGINT ID 不发生 JS 精度丢失；
2. POST 网络未知时使用原 Idempotency-Key；
3. Server status、phase 和 client uiState 分离；
4. TIMED_OUT 单独展示；
5. parseStatus 与 retrievalReadiness 分开展示；
6. SSE 重复事件被忽略；
7. sequence gap 会触发恢复而不是继续错误追加；
8. 页面刷新后 timeline/final answer 可恢复；
9. terminal 后以 task.finalAnswer 覆盖 draft；
10. cancel 不提前伪造终态；
11. citation 只来自后端白名单；
12. Trace 不泄漏内部配置或 chain-of-thought；
13. V0.1 主流程只需要少量页面即可完整演示。
