# V41 Task REST API 与公开 Trace 查询接口包说明

> 状态：已实现并完成验收（2026-09-05）。JDK 21、PostgreSQL 18.4 全量 634/634 通过，无失败、错误或跳过。
> 路线位置：M4F-A，接续 V40/M4E。V41 交付 REST，V42 交付可恢复 SSE；两片完成才算完整 M4F。
> 验收边界：真实 HTTP/JWT、Dispatcher、Runner、Engine、ToolRuntime 与 PostgreSQL；模型和向量使用可控替身。

## 1. 目标与范围

调用方可以通过 HTTP 创建或复用任务、查询自己的任务和答案、请求取消、查看公开执行记录。
创建复用 V38 的幂等事务与 after-commit dispatch，执行复用 V40 快照链路，取消复用既有生命周期。
公开查询读取持久事实，不触发 RAG、模型或工具执行。

| 方法与路径 | 返回 |
| --- | --- |
| `POST /api/v1/agents/{agentId}/tasks` | 新建 201 / 同请求复用 200；`AgentTaskResponse` |
| `GET /api/v1/tasks` | 200；`PageResult<AgentTaskSummaryResponse>` |
| `GET /api/v1/tasks/{taskId}` | 200；`AgentTaskResponse` |
| `POST /api/v1/tasks/{taskId}/cancel` | 200；当前 `AgentTaskResponse` |
| `GET /api/v1/tasks/{taskId}/trace` | 200；`PublicTaskTraceResponse` |

统一使用既有 `ApiResponse` 包装与 JWT principal。请求体、header、query 都不能提供 owner。
业务资源不可见（不存在或属于其他用户）统一 `404 COMMON_NOT_FOUND`。
未认证请求遵循已有 JWT 401 错误契约；非法 ID/参数返回 400。

## 2. 创建与并发幂等

```http
POST /api/v1/agents/{agentId}/tasks
Authorization: Bearer <access token>
Idempotency-Key: payment-diagnosis-001
Content-Type: application/json

{"userInput":"查询订单 ORD20260001 的支付失败原因"}
```

- `Idempotency-Key` 必填，1–128 字符且非空白；映射 `client_request_id`，不裁剪或自动补值。
- body 只允许一个 `userInput` 字段，必须为非空白字符串，保留原始文本和空格参与指纹。
  未知字段、重复字段或非字符串类型为 `400 COMMON_REQUEST_BODY_INVALID`；缺失/null/空白为
  `400 COMMON_PARAM_INVALID`。PostgreSQL text 不支持的 NUL 字符在应用边界以 400 拒绝，避免进入失败写事务。
  客户端不能覆盖 snapshot、模型、预算、owner、task ID 或状态。
- key 在用户内唯一，跨 Agent 复用同 key 也会比较请求。指纹沿用 V38 的
  `agent-task-request-v1 + agentId + 原始 userInput` canonical JSON。
- 已有 key、相同指纹返回原任务 200，不重新解析当前 Agent/绑定，也不重新 dispatch。
  同 key 不同请求为 `409 TASK_IDEMPOTENCY_CONFLICT`。
- 新 key 通过 V37 owner/live/ACTIVE Agent 和 READY 知识 gate 后，原子保存任务、快照和
  `TASK_CREATED`，提交后调度。返回 201；此时任务可能已开始执行，HTTP 状态不随任务状态变化。
- `AgentTaskApplicationService.createTaskWithResult` 返回 `CreateAgentTaskResult(task, created)`。
  只有本次 INSERT 成功为 `created=true`。并发唯一约束失败必须先退出失败事务，再独立读取胜出任务，
  匹配则 `created=false`；Controller 只读取此标志决定 201/200。
- 兼容内部 `createTask` 入口仍返回 task，底层复用上述明确结果实现。

并发 HTTP 验收发现并修复了既有 snapshot Agent 锁与 Runner 外键检查的死锁：竞争创建持有 Agent
`FOR UPDATE` 后，唯一键检查等待正在更新胜出 task 的 Runner；Runner 的 Agent 外键 `KEY SHARE`
检查反向等待该 Agent。V41 新增 resolver 专用 `selectVisibleOwnedByIdForSnapshot`，使用
`FOR NO KEY UPDATE`，保留 owner/live 与 REPEATABLE_READ，同时允许 FK KEY SHARE。
配置/状态/binding 写路径仍持有原来的 FOR UPDATE，与 snapshot 锁互斥；不引入重试。
此修复针对已复现的创建/Runner 锁环，不宣称消除所有可能的数据库死锁。

## 3. 列表、详情与历史查询

分页参数为 `page`（默认 1）和 `pageSize`（默认 20，最大 100）。沿用 `PageRequest`：page 小于 1
归一为 1，pageSize 限制在 1–100；非整数为 400。offset 使用 long 运算，避免极大页码整数溢出。
暂不提供状态、Agent、时间、关键字等筛选条件。

SQL 仅查询 `agent_task WHERE user_id = ?`，顺序固定 `created_at DESC, id DESC`；复用 V18
`idx_agent_task_user_created`，无需新 migration。列表 items 和 total 在同一 REPEATABLE_READ 事务读取。
稳定排序保证相同时间戳下 ID 顺序确定；offset 分页不承诺不同 HTTP 请求之间抵抗新增任务造成的位移。

列表项字段：

```text
taskId, agentId, status, phase, terminationReason, userInput,
createdAt, updatedAt, completedAt
```

详情、创建和取消共用字段：

```text
taskId, agentId, status, phase, terminationReason, userInput,
maxDecisionTurns, maxToolCalls, maxTotalTokens, reservedFinalTokens,
decisionTurnsUsed, toolCallsUsed, inputTokens, outputTokens, totalTokens, tokenUsageQuality,
finalAnswer, citations, errorCode, errorMessage,
cancelRequestedAt, startedAt, completedAt, lastEventSequence, createdAt, updatedAt
```

次数和 token 用量是已经持久化的任务汇总；运行中可能仍为初始值，不承诺实时刷新。
`tokenUsageQuality` 保留 `EXACT/ESTIMATED/MIXED/UNKNOWN`；不把未知质量当成已验证的 provider 用量。
`finalAnswer` 是已持久化答案；未有答案时为 null，citations 沿用持久化数组。
`lastEventSequence` 是该读取快照中服务端已持久化的事件游标，不是客户端已消费游标。

详情与 Trace 先用 `taskId + JWT userId` 查询任务；取消使用相同 owner 条件更新。
历史查询不 join 当前 Agent、KB、文档、chunk 或工具表。Agent/工具软删除、文档删除清理或 binding
变化不会使自己的历史任务消失。历史引用来自持久快照；不承诺源文档仍可通过源资源接口访问。
当前 Agent 有历史任务时仍受原 FK 约束，V41 不增加硬删除 Agent 的能力。

## 4. 取消语义

| 取消时任务状态 | 行为 |
| --- | --- |
| `QUEUED` | owner-scoped 条件更新为 `CANCELLED`，同事务追加 `TASK_CANCELLED`，返回 200 |
| `RUNNING` | 持久化 `cancelRequestedAt`；允许响应仍为 RUNNING；Runner 在安全边界协作终止 |
| 任一终态 | 返回 200 与当前状态，不追加重复终态事件、不修改已有答案 |

沿用 V38 的条件更新竞态处理，不引入第二套状态机，不强制中断外部 provider 调用。

## 5. 公开 Trace 与安全事件投影

```text
PublicTaskTraceResponse
  task: AgentTaskResponse（含最终答案、引用与 lastEventSequence）
  executionSnapshot: 已脱敏的创建时执行快照
  steps[]
    llmCalls[]
    ragRetrievals[] -> hits[]
    toolCalls[]
  events[]: id, taskId, sequenceNo, eventType, payload, createdAt
```

`TaskTraceQueryService.findOwnedPublicTrace` 在一个独立只读 `REPEATABLE_READ` 数据库事务内，先读取
owner task，再读取 steps、专项日志、命中与事件。复用内部私有聚合逻辑，不调用另一个
`REQUIRES_NEW` 读服务切换快照。并发完成时本次响应只看到一个数据库版本。
steps 按 `step_index,id`；各日志按 `created_at,id`；hits 按 retrieval/rank；events 按 sequence 升序。
运行中的 Trace 允许未结束 step、尚无专项日志，以及不同短事务间已经提交的中间事实。

返回显式 DTO 和 defensive-copy JSON/集合，不直接序列化数据库 Entity。task、Agent、step、tool、
日志、引用中的业务 ID 转字符串，嵌套 payload 的业务 ID 也转换；sequence、计数、rank、generation
仍为数字。公开 task 不含 userId、clientRequestId、requestFingerprint、version 和原始 snapshot。

执行 snapshot 使用已知字段 allowlist，保留执行解释所需的 Agent 配置、协议版本、模型 profile、
retrieval 文档/generation 和冻结工具 schema/版本，丢弃任意运行配置。
JSON 递归处理凭据、headers、endpoint/baseUrl、CoT/reasoning 等敏感键；LLM request snapshot
只暴露允许字段。公开查询再次投影历史日志，不能只依赖写入时已经脱敏。
答案正文和 userInput 属于调用方业务内容；答案分片保留与已存 finalAnswer 完全相同的文本，不因为
正文恰好像 JSON 而重新编码或改变空格。诊断配置/元数据与答案正文采用各自的明确字段边界。

诊断 `errorMessage` 专门遮蔽 HTTP/JDBC 运行地址；业务来源 URL 不作全局删除。

内部 `TaskEventQueryService.findOwnedEventsAfter(userId,taskId,afterSequence)` 提供 owner 校验、
严格大于游标的升序事件读取，并复用 Trace 的安全事件投影。V41 不开放 `/events`，不返回订阅地址。
未定义路由（包括 `/events`）统一 `404 COMMON_NOT_FOUND`，修正既有全局兜底误报 500 的行为。
这是 V42 的内部接缝，不代表已实现恢复订阅、Last-Event-ID、gap 检测或推送。

## 6. 实现与验收

主要实现包：`agent.task.controller/dto/service`、`agent.trace` 和既有 Task/Event mapper。
手工请求模板：`backend/http/agent-tasks.http`（模板本身不是执行通过证据）。

核心验收：HTTP 创建 → 真实 Dispatcher / Runner / Engine / ToolRuntime / PostgreSQL → GET 终态答案 →
Trace 中 steps/日志、连续事件、ANSWER_CHUNK 和 TASK_COMPLETED 与 task 状态/答案一致。
覆盖并发新建/复用、用户隔离、同时间戳分页和默认/最大页、排队/运行中/终态取消、资源删除后历史读、
公开脱敏和 ID 字符串化，并复用 V38–V40 重点回归。

独立临时库（测试会清理 fixture 表，不能指向开发数据）：

```bash
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  mvn -f backend/pom.xml \
  -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar \
  -Dagentflow.postgres.integration=true \
  -Dagentflow.postgres.url=jdbc:postgresql://127.0.0.1:55441/agentflow_v41_test \
  -Dagentflow.postgres.user=v41 -Dagentflow.postgres.password= \
  test
```

### 实际验收结果

2026-09-05，使用 JDK 21、显式 Mockito javaagent、PostgreSQL 18.4，独立空库成功应用 V1–V20
全部 20 个 migration。上述全量命令执行 **634/634 通过**，Failures=0、Errors=0、Skipped=0，
Maven 总耗时 14.299 秒；`git diff --check` 通过。

2026-09-06 提交前按相同命令复验，仍为 634/634，通过且无失败、错误或跳过，Maven 总耗时 12.184 秒。

- V41 HTTP/PostgreSQL 6 项：真实 HTTP/JWT 与完整执行链路；6 个同 key 并发请求恰好一个 201 和
  五个 200，仅一条 task/TASK_CREATED、仅一次执行；404 隔离；历史脱敏；稳定分页；取消；
  snapshot Agent 锁兼容。分页 fixture 同时验证同时间戳排序、默认 20、上限 100 和极大页码。
- V41 MVC 11 项、公开投影 4 项，并运行创建服务和既有公共异常处理重点测试。
- V38 PostgreSQL 8 项、V39 Trace PostgreSQL 7 项（其中新增并发完成期间的读快照测试）、
  V40 执行 PostgreSQL 15 项，以及其他既有回归均通过。
- 确定性锁测试：实际 resolver 持锁期间，独立事务 `FOR KEY SHARE NOWAIT` 成功；
  `FOR UPDATE NOWAIT` 返回 PostgreSQL `55P03`。证明允许 FK 检查同时保留配置/binding 写互斥。
- 并发快照测试：task 首次 SELECT 后另一个事务完成 step、新增 step 并提交答案和终态事件；
  本次 Trace 仍为原 RUNNING/task/step/event 集合，下次新查询看到完整终态。

全量日志在本次本机 `/private/tmp/agentflow-v41-all-tests.log`。临时 PostgreSQL 实例位于
`/private/tmp/agentflow-v41-pg`，验收后停止；重新执行前可用以下命令启动：

```bash
/Library/PostgreSQL/18/bin/pg_ctl -D /private/tmp/agentflow-v41-pg \
  -l /private/tmp/agentflow-v41-pg.log -o '-p 55441 -h 127.0.0.1 -k /private/tmp' start
```

以上证明可控模型/向量条件下真实 HTTP 与持久后端组件的行为；模型、embedding、vector 替身不构成
真实 provider/Qdrant E2E 证据。手工 HTTP 模板已提供，但不把模板本身计入实际验收次数。

## 7. 明确不做

SSE、`/events`/订阅地址、Last-Event-ID、前端、provider streaming、真实 provider/Qdrant E2E、
重试、崩溃恢复、多实例调度、Trace 拆分接口。V41 完成仍只算 M4F-A；V42 完成后再验收完整 M4F。

## 面试问题与回答

**问题 1：为什么不能用 QUEUED 或 RUNNING 判断 HTTP 创建返回 201 还是 200？**

回答：任务状态由异步 Runner 推进，首次 POST 返回前可能已经开始甚至终止，而复用任务也可能仍为
QUEUED。创建服务按 INSERT 是否由本次请求成功给出 created 标志；唯一约束冲突后读到胜出任务为复用。
Controller 只映射该结果，既保留并发语义，也避免再次执行任务。snapshot 锁使用 FOR NO KEY UPDATE，
保持与配置/binding 写入互斥，同时兼容 Runner 的外键 KEY SHARE，解除验收复现的锁等待环。

**问题 2：Agent 或文档删除后，为什么历史任务还能查？**

回答：可见性由 agent_task.user_id 决定，查询不依赖当前资源 live 状态。创建快照、检索命中和 citations
已经持久化。源资源是否还可访问与历史任务可见性是两个不同契约；V41 不新增 Agent 硬删除能力。

**问题 3：Trace 为什么需要 REPEATABLE_READ？**

回答：Trace 是多次 SQL 聚合。READ_COMMITTED 下可能先读 RUNNING task，再读到完成事件，形成矛盾。
一次 REPEATABLE_READ 读事务让 task、steps、日志与事件基于同一数据库版本；不同 HTTP 请求仍可能看到
不同进度，这符合查询语义。事务内只有数据库读，不调用模型、向量或工具。

**问题 4：运行中取消为何可以返回 RUNNING？**

回答：取消先持久化请求，Runner 在外部调用前后等安全边界观察并收敛到 CANCELLED。它不承诺立即中断
外部调用。排队任务可立即条件取消；终态重复取消只返回当前状态，不制造第二个终态事件。

**问题 5：安全 DTO 和写入时脱敏分别解决什么问题？**

回答：写入时脱敏减少敏感持久化数据；公开 DTO 进一步固定字段、转换大整数业务 ID，并对历史 JSON
再次安全投影，避免泄漏原始 Entity、运行配置或 CoT。答案分片的正文保持原样，保证拼接与 finalAnswer
一致；诊断元数据的安全规则不会被误用来改写业务答案。

**问题 6：V41 验收是否证明完整 M4F 或真实 provider E2E？**

回答：没有。V41 验证真实 HTTP 与持久后端组件，模型/向量使用可控替身。可恢复 SSE、重连游标与 gap
检测由 V42 交付，只有两片完成才算完整 M4F。真实模型/Qdrant E2E 未纳入本切片。
