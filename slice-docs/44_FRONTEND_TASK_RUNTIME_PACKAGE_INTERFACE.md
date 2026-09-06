# V43 最小任务运行前端与恢复闭环接口包说明

> 状态：已实现并完成本片验收（2026-09-06）；前端 23/23、真实浏览器 3/3，具体证据见第 9 节。
> 路线位置：M4G-A，接续 V42/M4F-B 的持久 SSE；本片完成不代表整个 M4G 或 V0.1 完成。
> 开工基线：`main@8d63ced`（V42，2026-09-06）。
> 验收边界：真实浏览器、JWT、Runner、Engine、ToolRuntime 与 PostgreSQL；模型和向量继续可控。

## 1. 目标与范围

使用 Vue 3 / TypeScript / Vite 建立从登录到持久任务结果的最小前端：

```text
登录 → 选择已有 Agent → 提交输入 → 查看持久事件 → 断网重连 / 页面刷新
→ 收到终态 → GET 答案与引用 → 查看只读 Trace
```

本片包含路由、API 客户端、JWT 会话与清理、自己的任务列表、任务提交、运行页、可恢复 SSE、
终态权威切换和聚合 Trace 展示。Agent、知识库、模型 profile、工具绑定由现有接口或验收 fixture 预配置，
前端不承担它们的编辑与上传管理。

## 2. 前端模块与页面

项目目录为 `frontend/`。使用 Vue Router、Pinia、Element Plus、Axios、`@microsoft/fetch-event-source`，
用 `lossless-json` 在 API/SSE 原文边界保留大整数。Task reducer 和连接生命周期独立于页面组件，
服务端状态与客户端连接状态各自管理；不要求把每个独立运行页实例都注册成全局 store。

| 页面 | 职责 |
| --- | --- |
| `/login` | username/password 登录 |
| `/tasks` | 选择已有 Agent、输入、提交与自己的分页任务列表 |
| `/agents/:agentId/run` | 同一任务入口，预选路由中的已有 Agent |
| `/tasks/:taskId` | Task 状态/阶段、时间线、答案、引用、取消及恢复 |
| `/tasks/:taskId/trace` | 公开 Trace 的 overview、steps、RAG、LLM、工具、events，只读 |

受保护路由检查会话；所有 ID 在路由、模型、列表 key 和 HTTP path 中保持字符串。
返回任务列表、切换任务、进入 Trace、退出或页面销毁时释放运行页连接及重连计时器。

## 3. 只消费现有公开接口

| 方法与路径 | 当前响应及用途 |
| --- | --- |
| `POST /api/v1/auth/login` | `{username,password}` → `accessToken,tokenType,expiresIn,user` |
| `GET /api/v1/agents?page=&pageSize=` | 当前用户 Agent 列表，用于选择已有 Agent |
| `GET /api/v1/tasks?page=&pageSize=` | 当前用户任务分页 |
| `POST /api/v1/agents/{agentId}/tasks` | `{userInput}` 与 `Idempotency-Key`；201 新建、200 复用、409 冲突 |
| `GET /api/v1/tasks/{taskId}` | 持久 Task 状态、答案、引用与服务端事件上界 |
| `POST /api/v1/tasks/{taskId}/cancel` | 已有取消契约，响应可能仍为 RUNNING |
| `GET /api/v1/tasks/{taskId}/events?afterSequence=` | Bearer SSE，持久回放与继续订阅 |
| `GET /api/v1/tasks/{taskId}/trace` | 一个公开聚合，含 task、executionSnapshot、steps、events |

普通 REST 外层为 `code,message,data,traceId,timestamp`，分页 data 为 `items,page,pageSize,total,hasNext`。
SSE 成功数据不套 ApiResponse；建流前错误仍为 JSON ApiResponse。
所有权来自 JWT，前端不提交 owner；不存在或跨用户资源沿用 `404 COMMON_NOT_FOUND`。

登录 user 为 `id,username,displayName,role`，`expiresIn` 单位为秒。
本片直接使用登录响应的 user，不额外调用已有 `/users/me`；认证有效性由本地到期与实际 REST/SSE 认证结果处理。
Agent 列表项为 `id,name,description,modelProvider,modelName,status,createdAt,updatedAt`，主键是 `id`。
Task 列表项使用 `taskId,agentId,status,phase,terminationReason,userInput` 和时间字段。
当前创建响应是完整 `AgentTaskResponse`，**没有 `eventsUrl`**，订阅地址由 taskId 构造。
不得为前端方便新增后端公开接口或猜测尚不存在的响应字段。

## 4. JWT 会话与请求隔离

access token 与当前用户会话保存于当前标签页的 `sessionStorage`，不保存密码，不实现 refresh token。
REST 与 SSE 使用 `Authorization: Bearer <token>`，不将 token 放入 URL。

退出、本地会话过期、REST/SSE 401 使用统一清理路径：

1. 清除 token、用户及待确认的创建请求；
2. 终止会话内未完成请求、运行页 SSE 和重连计时器；
3. 清空 Agent/Task/Trace、时间线、答案、引用及游标状态，跳转登录；
4. 旧会话晚到的成功/失败响应不得覆盖下一会话状态。

HTTP 错误、连接错误和任务失败分别显示。SSE 断线不得把 RUNNING 改为 FAILED；
401 清理会话，404 停止订阅，429/5xx/网络错误按连接恢复规则处理，不无限重试。

## 5. 创建与取消交互

用户明确开始一个独立新任务时生成 `crypto.randomUUID()` 作为 Idempotency-Key。
保存当前用户会话的 `{agentId,userInput,idempotencyKey}`，输入保留原文，不在重发时 trim 或改写。
提交期间防重复操作；201/200 返回 task 后进入运行页。

超时、断网或无法确认的传输结果不等于创建失败。保留原请求，重发时复用同一个 key、Agent 和输入。
刷新页面后仍能恢复该待确认请求；不能因刷新、再次点击或网络重连自动生成新 key。
结果未知时不将正在编辑的新输入混入旧 key；明确业务错误与幂等冲突展示后端 code/message。
退出或认证失效清理待确认请求，避免跨用户复用。

仅 QUEUED/RUNNING 提供取消。取消请求 pending 期间禁用重复点击。
响应若仍为 RUNNING，则显示取消已请求，并等待后端安全边界收敛；不能本地先改为 CANCELLED。
终态重复取消遵循后端幂等响应。本片不提供执行重试；SSE 重连只恢复观察连接。

## 6. 运行态、事件与无损游标

服务端 TaskStatus 保持 `QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/TIMED_OUT`；
TaskPhase 保持 `PREPARING/RETRIEVING/DECIDING/EXECUTING_TOOL/GENERATING`，仅 RUNNING 有 phase。
客户端提交、加载快照、连接、已连接、重连、已关闭、缺口/错误等状态单独保存。
`MAX_DECISION_TURNS` / `MAX_TOOL_CALLS` 的 COMPLETED 显示预算提示，不能改写成失败或正常完整回答。

维护两个独立十进制字符串：

- `serverLastEventSequence`：本次读取观察到的服务端持久上界；
- `lastProcessedSequence`：当前页面已经成功应用的事件位置，唯一重连游标。

初始游标为 `"0"`。不能把创建或 GET 响应的上界直接当已处理游标。
创建成功和页面刷新共用进入流程：先 GET Trace，从 0 严格重建其中完整 events，再从实际已处理
游标订阅 SSE。早期事件由 Trace 持久回放，后续事件由 SSE replay 补发；不要求第一条 SSE 请求必为 afterSequence=0。
后端业务 ID 为字符串，`sequenceNo`、`lastEventSequence`、控制帧游标仍为 JSON numeric long。
必须在 REST/SSE 原始字符串解析时保留精度，再以字符串存储、BigInt 比较和加一；
禁止普通 JSON.parse → Number → String，也禁止 parseInt/Number 或浮点排序业务 ID/sequence。
游标只接受 ASCII 十进制数字、长度 1–19、范围 `0..9223372036854775807`，持久事件 sequence 必须正数。

SSE data 为 `taskId,sequenceNo,eventType,timestamp,payload`，SSE id 是 sequence；
Trace events 为 `id,taskId,sequenceNo,eventType,createdAt,payload`，其中 id 是事件行 ID。
二者显式适配到同一个 reducer，不能把 Trace 行 ID 当 sequence。

处理每个持久事件时：

1. 校验 SSE id 与 data.sequenceNo 数值一致、event 与 eventType 一致、taskId 属于当前任务；
2. 校验已知事件及需要应用的 payload 类型；
3. `sequence <= lastProcessedSequence` 去重忽略；
4. 只接受 `sequence == lastProcessedSequence + 1`；更大即 gap；
5. 完成事件投影、临时答案和时间线更新后才推进游标；处理失败不得推进。

comment/heartbeat 不入时间线、不推进游标。无 id 的 `STREAM_ERROR` 是连接控制帧，
不改变 TaskStatus，不应用为持久事件，不采纳 `lastSentSequence` 作为客户端 cursor。
首批 `409 TASK_EVENT_SEQUENCE_GAP` 与流中 gap 均停止应用后续事件并释放连接；
展示错误，由用户点击“刷新并恢复”后使用已有 Trace 重建并重新验证，不自动循环恢复。
若 Trace 本身不完整，继续显示缺口并保持停止，不跳过缺失 sequence。

事件展示受 V42 payload allowlist 限制：

| 事件 | 可展示的当前字段与行为 |
| --- | --- |
| `TASK_CREATED` / `TASK_STARTED` | status；started 另有 phase |
| `PHASE_CHANGED` | phase |
| `RAG_FINISHED` | stepId、validHitCount、candidateCount、staleHitCount；无 latency/citations |
| `DECISION_FINISHED` | stepId、decisionType、totalTokens、usageQuality；不展示隐藏推理 |
| `TOOL_STARTED` / `TOOL_FINISHED` | 以 stepId 关联，toolCode、reused；finished 另有 status/errorCode；无 toolCallId |
| `FINAL_GENERATION_STARTED` | maxOutputTokens |
| `ANSWER_CHUNK` | chunkIndex、text；按持久 sequence 原样追加 |
| 终态事件 | status、terminationReason；failed 另有 errorCode；触发 GET 收敛 |

缺失的可选字段不从其他事实补造。耗时、RAG 正文和工具调用细节在 Trace 中展示。
`ANSWER_CHUNK` 可能只有一个完整答案块，也可能多个块；它不是 provider token streaming。

## 7. 断网、刷新与终态收敛

Bearer fetch SSE 使用 AbortController。网络错误/非终态 EOF 从最后成功处理游标指数退避重连，
延迟依次为 1/2/4/8/16 秒，最多自动重连 5 次；达到上限后停止并允许手动恢复。
一次成功建连或 offline/online 事件不重置累计预算；上线事件仅唤醒已有等待，不新建第二条并行连接。
SSE 关闭只改变连接状态；离页、任务切换、认证失效时清除连接及计时器，旧响应不得写回已离开的页面实例。
浏览器 pagehide 停止连接，BFCache pageshow 恢复时重新读取 Trace。

刷新或手动恢复流程：

1. GET 公开 Trace，以其自带 task/events 的同一数据库快照为基准；
2. 从 0 校验并重放 events，确认连续覆盖到 `trace.task.lastEventSequence`；
3. 游标只能来自实际成功应用的事件；非终态继续按该游标 SSE 订阅；
4. 读取与订阅间产生的新事件由持久 replay 补发；
5. 若已终态，重建历史后再 GET task 与 Trace，核对状态、事件上界、finalAnswer 和 citations；
   两者一致才标记 settled，展示 GET task 的持久答案和引用。不同快照的晚响应不能回退已知终态。

收到 COMPLETED/FAILED/CANCELLED/TIMED_OUT 后 GET task，最终答案和引用以持久
`task.finalAnswer` / `task.citations` 为准，覆盖临时 draft。GET 暂时失败时显示“终态待同步”，
允许重试 GET；不能仅因 terminal event 到达就把 draft 宣称为最终答案。
正常 EOF 若没有确认终态，仍按连接恢复处理；GET 的服务端上界不能补造未收到的事件。

## 8. 引用与只读 Trace

当前终态 citation 形状只有：

```text
citationId
documentId
chunkId
vectorGeneration
```

marker 使用实际 citationId（例如 `[S1]`），只展示后端确认的列表，不猜测不存在的引用。
当前 citation 没有 fileName/titlePath/score/contentPreview。详情按 citationId 关联
`steps[].ragRetrievals[].hits[]` 的 contentSnapshot、score、metadataSnapshot；缺失 metadata 不补造。
V43 不提供文档/chunk 管理页跳转，历史证据标为历史快照。

Trace 只读展示公开 `PublicTaskTraceResponse`：

- task 状态、输入、预算/usage、finalAnswer/citations、错误、时间及 executionSnapshot；
- steps 的 stepIndex、stepType、status、title、summary、latency/error；
- 各 step 下 ragRetrievals 的 query/profile/counts/hits/contentSnapshot；
- llmCalls 的 callType、requested/resolved model、usage/quality、latency/status 与已公开摘要；
- toolCalls 的 code/name、arguments/result、status/latency/error；
- events 的 sequence、eventType、createdAt 与安全 payload。

JSON、工具结果和长正文默认折叠；Vue 文本插值展示公开内容，不执行答案或日志中的 HTML。
不增加 Trace 子接口，不展示内部配置、凭据、原始推理或自造 chain-of-thought。

## 9. 实现与验收

主要实现：

- `frontend/src/lib/api.ts` / `session.ts` / `submission.ts`：无损 REST、会话隔离/清理、未知创建结果；
- `frontend/src/lib/sequence.ts` / `events.ts` / `stream.ts`：大整数、事件 reducer、单次 Bearer SSE；
- `frontend/src/stores/runtime.ts`：Trace 重建、连接生命周期、有界恢复、终态 GET/Trace 收敛；
- `frontend/src/pages/` / `components/`：登录、任务入口/运行、只读 Trace、引用抽屉和时间线；
- `backend/src/test/java/com/agentflow/acceptance/V43BrowserFixture.java`：仅 test source 的受控模型/向量边界；
- `scripts/v43-browser-acceptance.sh` / `scripts/v43-browser-backend.sh` 与 `frontend/e2e/`：独立库启动、真实浏览器和证据产物。

fixture 不替换 Runner/Engine/ToolRuntime 或持久 Trace/SSE，不增加公开测试控制接口；
通过临时控制目录中的文件释放受控模型等待。生产 `backend/src/main` 不为本片新增接口或执行行为。

仅运行与本片相关的构建、恢复/解析边界测试及必要后端回归，避免无关的重复测试。
验收库独立于开发数据；使用真实浏览器、真实 HTTP/JWT、真实任务执行组件与 PostgreSQL。
模型、embedding、vector 可控替身不构成真实 provider/Qdrant E2E。

核心验收：浏览器登录 → 选择预配置 Agent 创建 → 展示持久事件 → 断网重连 → 页面刷新 →
收到终态 → GET finalAnswer/citations 与 Trace 完全一致。必须观察实际页面和持久后端事实，
不能以 reducer 单测或静态页面截图替代该链路。

重点边界：

- 大于 `Number.MAX_SAFE_INTEGER` 的 ID/sequence 及 signed long 上限，非法/溢出/id-data 不一致；
- 重复事件不重复应用、gap 停止、reducer 失败不推进、控制帧不推进；
- 未知创建结果重发同 key 与相同原文，刷新保留当前会话待确认请求；
- 401/退出清理及旧响应隔离；404 停止；有界重连和离页释放；
- 刷新 Trace 重建、终态 GET 失败后的再次同步、取消不伪造终态；
- 最终引用以 task 白名单关联 Trace，受控最终答案/引用一致。

### 实际验收结果

2026-09-06，在 `frontend/` 执行 `npm test`：Vitest **5 个文件、23/23 通过**。
21:04 的运行耗时 766 ms（测试执行 334 ms），21:05 对最终代码复验仍为 23/23。
同次 `npm run build` 的 `vue-tsc --noEmit` 与 Vite 7.3.6 生产构建通过。
前端验证覆盖如下，不把这些替身单测计为真实后端/浏览器证据：

| 测试文件 | 数量 | 重点证据 |
| --- | --- | --- |
| `lib/events.test.ts` | 5 | 大整数及非法词法、SSE id/task/type 一致、重复/gap、处理失败不推进、完整 Trace 重建 |
| `lib/api.test.ts` | 3 | 原文无损解析、退出中止请求与隔离晚到响应、401 清理 |
| `lib/submission.test.ts` | 3 | 未知结果同 key/原文、重复点击合并、退出隔离、明确拒绝后的清理 |
| `lib/stream.test.ts` | 3 | Bearer/游标、分段 SSE 与 comment、gap 控制帧、401，不由库自行重试 |
| `stores/runtime.test.ts` | 9 | Trace 恢复、去重、gap 停止、有界重连、离线/上线、终态待同步及冲突、旧请求隔离、非法路由、取消快照不回退 |

独立一键验收命令在仓库根目录执行（本次选用空闲端口）：

```bash
V43_BACKEND_PORT=18044 V43_PG_PORT=55444 V43_FRONTEND_PORT=5174 \
  bash scripts/v43-browser-acceptance.sh
```

脚本创建全新 PostgreSQL 18.4 临时集群与独立数据库，成功应用 V1–V20 共 20 个 Flyway migration。
真实 Chrome 的 Playwright 验收 **3/3 通过**，浏览器测试阶段耗时 **6.5 秒**（不含启动/编译总耗时）：

1. 真实登录后选取预配置 Agent `430000000000000003`。真实 POST 已返回 201 后故意丢弃浏览器响应，
   刷新并确认原提交，第二次请求使用相同 key、Agent、输入，以 200 取得同一个 Task
   `2096584865607565314`。该 task/Agent/文档/chunk ID 均超过 JavaScript 安全整数范围。
   运行页读取持久 Trace 早期事件后建立 Bearer SSE；浏览器网络离线模拟确认 navigator.onLine=false，
   状态仍 RUNNING。上线后从已处理 cursor `10` 重连，页面刷新重建相同时间线。
   运行中进入 Trace 页时服务端 SSE 活动连接探针由 1 降至 0，同时 GET task 仍为 RUNNING；
   返回后重新恢复。释放受控模型后，收敛为 COMPLETED，最终 **15 条连续且无重复的事件、5 个 steps、
   1 次 RAG、3 次 LLM、1 次真实 order_query 工具调用**，共观察到 4 次 SSE 连接。
   页面 finalAnswer/citations 与 GET task、公开 Trace 完全一致；引用为 S1，documentId
   `430000000000000006`、chunkId `430000000000000007`、vectorGeneration=1。
2. 浏览器取消真实 RUNNING 任务，最终 GET 确认为 CANCELLED；退出后会话、待确认请求清空，
   再访问受保护任务地址跳转登录，页面不残留时间线。
3. 浏览器恢复无效 JWT，由真实后端拒绝后清除会话与待确认提交并回到登录页。

另经真实浏览器页面检查确认 `[S1]` 引用抽屉、工具 Trace tab 及公开工具结果可读；该视觉核对不另计自动测试数量。

本次临时证据根目录：
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v43-browser.7smKfU`。
其中 `backend.log` 记录独立库、20 个 migration 与后端启动/关闭，`postgres.log` 记录 PostgreSQL 18.4
启动及关闭；浏览器证据在
`browser-artifacts/task-runtime-real-login-un-27841-fresh-converge-to-GET-Trace/` 下的
`real-backend-evidence.json`、`completed-runtime.png`、`readonly-trace.png`。
脚本完成后清理了本次后端、前端和 PostgreSQL 进程。临时产物只作本次证据，复现入口为已纳入仓库的脚本和测试。

以上验证真实 Chrome、JWT/HTTP、Runner、快照执行、RAG 的 PostgreSQL chunk 校验、ToolRuntime、
recorder 与持久 SSE/Trace。模型、embedding 和 vector provider 边界仍是可控适配器，浏览器断网为
Playwright 网络离线模拟；不能据此宣称真实 provider/Qdrant E2E、物理网络故障覆盖或完整 M4G 已完成。

## 10. 明确不做

知识库上传管理、Agent 配置编辑、真实 provider/Qdrant E2E、新增后端公开接口、provider streaming、
多轮对话、任务执行重试、崩溃恢复、多实例调度。其余 V0.1 页面及真实 E2E 保留后续切片。
本片的刷新、重放和重连只恢复前端观察状态，不重启或恢复 TaskRunner 执行。

## 面试问题与回答

**问题 1：为什么创建成功后不能直接从 lastEventSequence 订阅？**

回答：该值是服务端已提交事件的上界，不表示页面已经处理。Runner 可能在 POST 返回前写出事件。
新任务与刷新都先从 0 严格应用完整 Trace events，再从实际已处理位置订阅 SSE，
因此既保留 TASK_CREATED 等早期事实，也补齐读取快照之后产生的事件。

**问题 2：业务 ID 已经是字符串，为什么仍需要无损 JSON 解析？**

回答：当前 DTO 的 sequenceNo、lastEventSequence 和控制帧游标仍是 numeric long。
普通 JSON.parse 在大于 2^53−1 时会丢精度，之后转字符串无法恢复。原文解析保留整数，字符串存储，
BigInt 比较和加一；SSE id、data sequence、taskId 还需交叉校验。

**问题 3：连接断开和 sequence gap 为什么不能采用相同的盲目追加策略？**

回答：普通断线可从最后成功处理位置回放；gap 表明缺失事件可能影响答案或状态，必须停止应用并验证
完整 Trace。当前实现停止后由用户点击“刷新并恢复”；普通连接重试最多 5 次，控制帧不推进游标，
SSE 失败不伪造任务失败。连接恢复不等于执行重试。

**问题 4：POST 超时后重新生成 key 有什么风险？**

回答：服务端可能已经创建并开始执行任务，只是响应未到达。复用原 key、Agent 与原始输入，才能让后端
返回同一个任务或明确冲突。待确认请求在当前会话内跨刷新保留，退出/401 清理，防止跨用户残留。

**问题 5：为什么终态还要 GET，引用详情为什么去 Trace 取？**

回答：SSE draft 是展示投影，持久 finalAnswer/citations 才是最终结果。终态 GET 暂时失败应显示待同步，
不能把 draft 当已确认答案。当前 citation 只有标识与 generation，正文和 score 在 Trace RAG hit 中；
工具事件关联 stepId，也不能猜测 toolCallId 或 eventsUrl 等未公开字段。

**问题 6：V43 的浏览器验收能证明整个 V0.1 E2E 吗？**

回答：不能。第 9 节已通过真实浏览器、HTTP/JWT、Runner/Engine/ToolRuntime 和 PostgreSQL 链路，
模型与向量受控，断网使用浏览器网络模拟。知识库/Agent 配置页面及真实 provider/Qdrant E2E
未纳入本切片，V43 完成只计 M4G-A。
