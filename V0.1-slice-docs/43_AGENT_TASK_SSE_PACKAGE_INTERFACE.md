# V42 基于持久事件日志的可恢复 SSE 接口包说明

> 状态：已实现并完成验收（2026-09-06）；全量 691/691 通过，具体证据见第 7 节。
> 路线位置：M4F-B，接续已完成的 V41/M4F-A。Task REST / Trace 与可恢复 SSE 均已验收，M4F 完成。
> 验收边界：真实 HTTP SSE 客户端、JWT、Runner、Engine、ToolRuntime 与 PostgreSQL；模型和向量使用可控替身。

## 1. 目标与唯一新增接口

```http
GET /api/v1/tasks/{taskId}/events?afterSequence=0
Accept: text/event-stream
Authorization: Bearer <access token>
```

读取 V38–V40 已持久化的 `agent_task_event`，支持晚订阅、断线后的历史回放和持续读取。
订阅不创建任务、不启动执行、不修改任务状态，不增加第二套事件或任务状态机。
V41 的五个公开 REST 接口保持原有职责；本片不增加其他公开接口或订阅地址字段。

建立流前按 `taskId + JWT userId` 查询 task，不存在和跨用户统一返回 `404 COMMON_NOT_FOUND`。
owner 只能来自 JWT principal；未认证/无效 JWT 沿用已有 401 契约。历史可见性只依赖持久 task 的 owner，
不依赖当前 Agent、KB、文档、chunk 或工具 live 状态。关联资源删除或修改后，自己的历史事件仍可回放。
本片不增加 Agent 硬删除或事件清理能力。

## 2. 客户端游标与拒绝规则

`afterSequence` query 和 `Last-Event-ID` header 都表示客户端已经完整处理的最后一个持久事件 sequence。
只发送严格大于该游标的事件，不把 GET task 返回的 `lastEventSequence` 当成客户端已处理位置。

| 输入 | 结果 |
| --- | --- |
| 两者均缺省 | 从 0 开始，包含 `TASK_CREATED` |
| 只提供其中一个 | 使用该值 |
| 两者均提供且数值相同 | 使用该值；例如 `01` 和 `1` 数值相同 |
| 两者均提供但数值不同 | `400 COMMON_PARAM_INVALID` |
| 任一参数重复，即使值相同 | `400 COMMON_PARAM_INVALID` |
| 空串、空白、正负号、小数、科学计数法、非 ASCII 数字 | `400 COMMON_PARAM_INVALID` |
| 负数、超出 signed long 范围 | `400 COMMON_PARAM_INVALID` |
| 大于本次读快照的服务端 `lastEventSequence` | `400 COMMON_PARAM_INVALID` |

输入长度限 1–19 位，有效数值范围为 `0..9223372036854775807`，只允许 ASCII 十进制数字，
不 trim、不容忍隐含转换；前导零计入长度上限。
重复 header 或被代理合并为逗号列表的 header 都不能被静默选取一个值。ID 仍须为正数。
超前判断与任务状态、事件查询基于同一数据库快照；稍后可能产生该 sequence，不能使当前的超前输入合法。

客户端应在业务处理和本地去重/持久游标更新完成后，再推进 `lastProcessedSequence`。
网络断线可能发生在服务端写出后、客户端处理前，因此允许重发；客户端按 taskId + sequence 去重后再应用。
协议不承诺网络层 exactly-once，也不把 TCP 写入成功解释为客户端已消费。

## 3. 分批数据库回放与持续读取

扩展 V41 `TaskEventQueryService`，每批返回安全事件集合、任务状态与服务端 `lastEventSequence`。
一次独立只读 `REPEATABLE_READ` 事务依次读取 owner task 和严格大于游标的事件，按 sequence 升序、限制批量。
查询不 join 当前源资源、不调用 Runner、Engine、模型或工具。没有新增表或 migration。

事务返回后才编码/发送或等待下一次轮询；发送、心跳、网络背压和轮询等待期间不持有数据库事务。
每条连接最多持有一批待发送数据，发送完本批才读取下一批；积压跨多个批次逐步回放。
单次批次的 task、状态、上界游标和事件属于同一读快照；不同批次可以看到后续已提交的新事实。

期望下一个 sequence 为当前读取位置加 1，逐条校验连续性，不能用 `MAX(sequence_no)+1` 推算缺失事实。
在服务端快照游标表明仍有未读事件时，空批次、首项或批内不连续均视为缺口。
分批边界处也必须校验下一条，避免只有单批连续而跨批跳号。不会补造事件、跳过缺口或修改数据库。

## 4. 固定 SSE 与安全 payload

成功响应为 `200 text/event-stream`，UTF-8 编码；每帧以空行分隔。
持久事件的 SSE `id` 为十进制 sequence，`event` 为已有 `TaskEventType`，`data` 只包含以下五个字段，
不套 `ApiResponse`，不暴露事件数据库行 ID：

```text
id: 9
event: TOOL_FINISHED
data: {"taskId":"30001","sequenceNo":9,"eventType":"TOOL_FINISHED","timestamp":"2026-09-06T10:00:00Z","payload":{"stepId":"40001","toolCode":"order_query","reused":false,"status":"SUCCESS"}}

```

`taskId`、payload 内 `stepId` 等业务 ID 为字符串；sequence、计数、token、chunkIndex 为数字。
`timestamp` 使用持久事件的创建时间，而非重放时刻。JSON 正文中的换行按 JSON 转义，不产生额外 SSE 字段。

复用 `SafeTaskEventProjector`，以已知事件类型和字段 allowlist 固定对外 payload；Trace 和 SSE 共用投影，
不直接序列化 Entity 或任意历史 JSON。事件不是 Trace 专项日志的替代品。

| event | 允许的 payload 字段 |
| --- | --- |
| `TASK_CREATED` | `status` |
| `TASK_STARTED` | `status`, `phase` |
| `PHASE_CHANGED` | `phase` |
| `RAG_FINISHED` | `stepId`, `validHitCount`, `candidateCount`, `staleHitCount` |
| `DECISION_FINISHED` | `stepId`, `decisionType`, `totalTokens`, `usageQuality` |
| `TOOL_STARTED` | `stepId`, `toolCode`, `reused` |
| `TOOL_FINISHED` | `stepId`, `toolCode`, `reused`, `status`；失败时 `errorCode` |
| `FINAL_GENERATION_STARTED` | `maxOutputTokens` |
| `ANSWER_CHUNK` | `chunkIndex`, `text` |
| `TASK_COMPLETED` | `status`, `terminationReason` |
| `TASK_FAILED` | `status`, `terminationReason`, `errorCode` |
| `TASK_CANCELLED` | `status`, `terminationReason` |
| `TASK_TIMED_OUT` | `status`, `terminationReason` |

缺失字段不根据其他记录补造；allowlist 以外字段不公开。已存在字段须符合固定类型；不支持的持久事件类型、
非对象 payload 或字段类型错误视为读取失败，不将任意内容写入 SSE `event` 字段。
诊断元数据继续执行既有递归安全投影，
不泄漏凭据、运行 endpoint/headers、原始推理或任意配置。`ANSWER_CHUNK.text` 是业务答案正文，保留原文、
空格、Unicode 和看起来像 JSON 的文本，不能当诊断 JSON 再解析后改写。

`ANSWER_CHUNK` 是 V40 已持久化的答案展示分片，可能是完整答案的一块或多块，并非 provider token streaming。
V40 在同一完成事务提交 finalAnswer、所有分片和 `TASK_COMPLETED`；客户端收到完成事件后可 GET 已存答案，
按 sequence 拼接分片应与 task / Trace 的 finalAnswer 完全一致。

空闲心跳采用 SSE comment，形如 `: heartbeat` 加空行，无 `id`，不写入事件表、不推进任何持久游标。
服务端可发送连接建立 comment；comment 同样不能视为 task 事件或客户端消费确认。

## 5. 终态、缺口与传输错误

任务沿用既有 `COMPLETED / FAILED / CANCELLED / TIMED_OUT` 终态集合。
只有读快照中 task 已终态，且该快照的全部事件已经写出后，才正常关闭连接。
终态历史仍从请求游标回放；终态且游标已等于服务端上界时正常返回空事件流并关闭。
非终态且暂时追平时继续轮询，不误判完成。

首批读取和连续性校验在流建立之前完成。首批缺口返回普通 JSON 错误
`409 TASK_EVENT_SEQUENCE_GAP`（沿用 `ApiResponse`）；不会先提交 200 再把首批缺口伪装成正常 EOF。
建流前其他读取/投影/编码异常返回 `500 SYS_INTERNAL_ERROR`，本接口异常响应显式使用 `application/json`，
即使请求 `Accept: text/event-stream` 也能保持错误协议。
流建立后的缺口或读取/编码错误不能更改 HTTP 状态；在传输仍可写时，发送一次无 `id` 的控制帧后关闭：

```text
event: STREAM_ERROR
data: {"code":"TASK_EVENT_SEQUENCE_GAP","lastSentSequence":8}

```

控制帧固定字段为 `code`、`lastSentSequence`；缺口诊断可增加 `expectedSequence`、`actualSequence`、
`lastEventSequence`，均为数字，不返回 SQL、异常堆栈或底层运行地址。持久读取失败使用
`TASK_SSE_READ_FAILED`，单帧超限使用 `TASK_SSE_EVENT_TOO_LARGE`。
`STREAM_ERROR` 不属于持久 `TaskEventType`，不入库、不占用 sequence，也不是 task 失败状态。
`lastSentSequence` 只表示本连接服务端最后完整写出的事件位置，初值是请求游标；它不是客户端 ack。
重连仍使用客户端自己的已处理位置，不能直接用该诊断字段跳过未处理事件。
缺口发生后不发送缺口之后的持久事件；慢客户端或网络已坏时无法保证错误帧可达，直接关闭。
客户端断线、超时、服务端关闭或发送失败均不调用 task cancel，也不改变 Runner 执行。

## 6. 有界连接与资源释放

使用独立 SSE 轮询资源和 Servlet 异步非阻塞写；不占用 TaskRunner 执行线程，也不为每条连接长期占用
一个等待线程。Servlet 可写回调只推进已准备好的有界数据；数据库读取通过专用轮询资源执行。

配置前缀为 `agentflow.task.sse`，默认资源界限如下：

| 配置项 | 界限 | 默认值 |
| --- | --- | --- |
| `max-connections` | 全局并发 SSE 连接 | 128 |
| `max-connections-per-user` | 每个 JWT 用户并发 SSE 连接 | 8 |
| `batch-size` | 每批数据库事件数 | 64，SQL 读取上限 256 |
| `max-pending-bytes` | 每连接待发送编码数据 | 262144（256 KiB） |
| `poll-interval-ms` | 空闲数据库轮询间隔 | 250 |
| `heartbeat-interval-ms` | 空闲心跳间隔 | 15000 |
| `connection-timeout-ms` | 单连接总寿命 | 300000 |
| `send-timeout-ms` | 待发送数据无进展超时 | 10000 |
| `poll-workers` | 专用数据库轮询工作线程 | 4 |
| 固定值 | 独立连接超时检查线程 | 1 |

达到全局或每用户连接上限时在建流前返回 `503 TASK_SSE_CAPACITY_EXCEEDED`，不能通过无限排队绕过上限。
待发送字节上限包括 SSE/JSON 编码开销；一批编码超出上限时只保留能容纳的完整事件前缀，剩余事件在后续
批次重读，不能推进未发送的游标。单个无法容纳的帧以 `TASK_SSE_EVENT_TOO_LARGE` 明确失败：建流前为
500 JSON，建流后尽力控制帧再关闭；不能截断答案或丢弃事件继续发送。
总寿命从连接建立起计算，持续产生事件也不会无限延长。待发送数据长时间无法继续写出则关闭；
发送异常立即收敛，不无限重试。总寿命到期、无发送进展和断开均直接 EOF，不保证错误帧可达。
客户端可根据自己的已处理游标重新建流。

正常 EOF、缺口、读取/编码异常、生命周期超时、慢客户端、网络断开和服务停止都必须取消轮询、清空待发送
数据并释放用户/全局连接配额；清理应幂等，竞争的结束回调不能重复扣减配额或遗留连接。
服务端写入完成只证明交给传输层，不证明远端业务处理完成。连接上限和待发送数据上限约束应用层资源，
不宣称操作系统与反向代理的全部网络缓存都由应用配置控制。

## 7. 实现与验收

主要实现包：`agent.task.controller.AgentTaskSseController`、`agent.task.sse`（编码、连接管理与资源界限）和 V41 的
`agent.task.service.TaskEventQueryService` / `agent.task.dto.SafeTaskEventProjector`。
手工请求模板：`backend/http/agent-task-events.http`。模板展示请求与重连规则，不是已执行证据。
自动验收使用独立 PostgreSQL 测试库，禁止指向开发数据。

核心链路：HTTP 创建 → 延迟订阅仍收到 `TASK_CREATED` → 中途断连 → 使用已处理 sequence 重连 →
收到终态 → GET task / Trace → 按 sequence 去重后的全部分片与持久 finalAnswer 一致。

必要边界包括：默认/query/header/冲突/重复/非法/负数/溢出/超前游标；未认证与跨用户/不存在 404；
关联资源删除后的历史回放；单批及跨批连续性、读快照一致性；初始和运行中缺口；终态从 0 回放与已追平
终态空流；空闲心跳；连接配额、断开/超时/失败清理和慢客户端关闭。允许网络重发，按 sequence 去重后
验证无重复应用、无持久事件丢失。

### 实际验收结果

2026-09-06，JDK 21、显式 Mockito javaagent、PostgreSQL 18.4。新建独立空库
`agentflow_v42_test` 成功应用 V1–V20 共 20 个 migration，本片不新增或修改 migration。
以下全量 Maven 验收 **691/691 通过**，Failures=0、Errors=0、Skipped=0，总耗时 **26.932 秒**：

```bash
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  mvn -f backend/pom.xml \
  -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar \
  -Dagentflow.postgres.integration=true \
  -Dagentflow.postgres.url=jdbc:postgresql://127.0.0.1:55441/agentflow_v42_test \
  -Dagentflow.postgres.user=v41 -Dagentflow.postgres.password= \
  test
```

- V42 真实 HTTP/PostgreSQL 7 项：延迟订阅、按已处理游标断线重连和有意重发后的 sequence 去重；
  长 Unicode/JSON 样式答案的多块拼接与 task / Trace 完全一致；JWT 与游标错误；删除关联资源后的
  安全历史回放；首批/尾部及跨批缺口；终态回放与追平空流；全局/每用户配额、心跳、断开清理与总寿命。
- V42 传输单元测试 21 项：受控 Servlet readiness 下的慢客户端无进展关闭、持续发送进展刷新超时、
  write 失败与幂等清理、终态空流、缺口控制帧、字节上限的完整帧前缀与后续读取、单帧超限拒绝、
  UTF-8/换行/JSON 样式答案原文保留，以及参数化游标边界。此组是确定性传输替身证据，
  不能单独称为真实网络慢客户端验收。
- SSE Controller 5 项：`Accept: text/event-stream` 下建流前业务/通用异常仍为正确 JSON，
  非法 ID、重复游标和 JWT owner 向下传递。批次读取 8 项、安全事件投影 14 项与 mapper 契约 3 项通过。
- PostgreSQL 并发读快照测试：首次 task SELECT 后另一事务提交答案与终态，本批仍返回旧状态、旧上界和
  对应旧事件；下一次新事务才看到后续完整终态。该测试纳入 Trace PostgreSQL 的 8 项。
- V38 PostgreSQL 8 项、V40 Engine/ToolRuntime/PostgreSQL 15 项、V41 HTTP/PostgreSQL 6 项与其他既有
  回归均通过。每条 HTTP fixture 结束时同时确认 SSE 配额归零、Runner 活动数归零且执行队列为空。

最终补上“连接已结束时不得继续初始化”的竞争守卫后，针对传输 21 项与真实 HTTP/PostgreSQL 7 项
复验 **28/28 通过**，Failures=0、Errors=0、Skipped=0，耗时 **19.364 秒**；日志为
`/private/tmp/agentflow-v42-final-transport-tests.log`。

全量日志：`/private/tmp/agentflow-v42-all-tests.log`。首次空库 migration 记录见
`/private/tmp/agentflow-v42-focused-tests.log` 的 Flyway 成功应用记录；该日志不作为全量通过证据。
临时 PostgreSQL 复用数据目录 `/private/tmp/agentflow-v41-pg`，本片使用独立数据库，实例日志为
`/private/tmp/agentflow-v42-pg.log`。本次验收后已停止实例，重跑前可启动：

```bash
/Library/PostgreSQL/18/bin/pg_ctl -D /private/tmp/agentflow-v41-pg \
  -l /private/tmp/agentflow-v42-pg.log -o '-p 55441 -h 127.0.0.1 -k /private/tmp' start
```

以上验证真实 HTTP SSE、JWT 与持久后端组件；模型、embedding、vector 替身不构成真实
provider/Qdrant E2E。手工 `.http` 模板只供复现请求，不计入执行通过次数。

## 8. 明确不做

前端、provider streaming、真实 provider/Qdrant E2E、任务执行重试、崩溃恢复、多实例通知与调度、
Trace 拆分接口。SSE 连接恢复只重放持久事件，不重试或恢复 TaskRunner 执行。
不增加事件保留期/清理 API、消息中间件或客户端 ack 写接口。

## 面试问题与回答

**问题 1：为什么不能在 POST 成功后才注册一个进程内监听器？**

回答：TaskRunner 可能在 POST 返回前写出事件，客户端也可能很晚才订阅。持久事件日志保留这些已提交事实，
从 0 回放仍能收到 TASK_CREATED。进程内连接只负责当前传输，可靠回放依赖 PostgreSQL；本片不实现任务
执行的崩溃恢复或多实例调度。

**问题 2：lastEventSequence、lastSentSequence 和 lastProcessedSequence 为什么要区分？**

回答：前者是一次数据库快照的持久事件上界，中间是服务端已完整写出的传输位置，后者是客户端实际处理
完成的位置。网络断开时三者可能不同。重连使用 lastProcessedSequence，按 sequence 去重；不能因 GET
显示了更大的上界或服务端已经写出，就跳过客户端未处理的数据。

**问题 3：分批查询为什么需要 REPEATABLE_READ，而且不能让事务覆盖整个连接？**

回答：每批先读 task 状态/上界，再读事件；同一快照防止把旧状态与新事件或新上界与旧事件混在一起。
事务在返回批次时结束，之后才发送或等待。否则慢客户端和空闲连接会长期占用数据库事务及连接，妨碍
正常任务执行。不同批次读到后续提交是持续订阅的预期行为。

**问题 4：发现 sequence gap 后为什么不能直接从下一条继续？**

回答：缺失的可能是状态变化、工具结果或答案正文，跳过会使客户端把不完整历史当成成功处理。
首批缺口在建流前返回 409；已建流后以无 id 的 STREAM_ERROR 尽力通知并关闭，不发送缺口后的事件。
控制帧不成为持久事件，也不把 task 改成 FAILED。

**问题 5：安全投影为什么对 ANSWER_CHUNK.text 采用单独边界？**

回答：事件诊断字段只开放固定 allowlist，并继续脱敏；text 是用户应收到的答案，可能包含空白、Unicode
或 JSON 片段。把它当运行配置再次解析会改变业务内容，破坏分片拼接与 GET finalAnswer 的一致性。
这些分片由同步 Final Generation 的已存答案生成，不代表 provider 逐 token streaming。

**问题 6：如何防止慢 SSE 客户端拖住任务执行？**

回答：连接、每用户连接、批量和待发送字节均有上限；数据库轮询与 Runner 使用独立资源，网络写采用
Servlet 非阻塞机制。总寿命、无发送进展和发送失败都有关闭路径，所有结束路径释放配额并清理轮询。
关闭 SSE 只结束观察连接，不取消任务；验收使用真实 HTTP/PostgreSQL，但模型和向量仍为可控替身。
