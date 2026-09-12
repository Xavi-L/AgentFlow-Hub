# V38 AgentTask 生命周期、持久事件与单实例调度接口包说明

> 状态：已按 M4C 范围实现，并完成聚焦自动化、真实 PostgreSQL 并发验收和完整 Maven 回归。
> V36 AgentEngine、RAG、Trace、公开 Task API 与 SSE 仍未接入。

## 1. 切片目标与路线位置

V38 在 V37 的 `agent-task-snapshot-v1` 之上建立一次执行的权威 `agent_task` 根记录、从创建开始
存在的严格递增事件序列，以及可靠的单实例内存调度和生命周期控制：

```text
V37 snapshot resolver
  -> V38/M4C task + event + idempotency + dispatcher + runner
  -> M4D task-scoped Trace
  -> M4E RAG/AgentEngine/ToolRuntime 正式接入
  -> M4F Task HTTP/SSE
```

V38 只能证明生命周期、事务边界、调度和事件顺序。它不证明真实 Agent、RAG、工具、LLM、
Qdrant、Trace 或 SSE 已接通。

## 2. Schema 契约

迁移固定为：

```text
backend/src/main/resources/db/migration/V18__create_agent_task_and_event.sql
```

V18 新增规范完整字段的 `agent_task` 与 `agent_task_event`。`agent_task` 使用
`(agent_id,user_id) -> agent_app(id,user_id)` 复合 owner 外键和
`UNIQUE(user_id,client_request_id)`；状态只允许 `QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/TIMED_OUT`，
并用 CHECK 约束 phase、termination reason、answer/error、取消/起止时间、预算/使用量、token 加总、
JSON object/array、event cursor 和 optimistic version 的合法组合。

`TIMED_OUT` 的 termination reason 固定为 `DEADLINE_EXCEEDED`；禁止使用 `TIMEOUT` 状态拼写。
`CANCELLED` 以 `cancel_requested_at` 证明取消请求。`COMPLETED` 必须有非空 final answer；只有
`FAILED` 保存非空安全 error code/message。timeout 和 cancel 由 termination reason 表达，不伪造 provider 错误。

每个 task 冻结：

```text
reservedFinalTokens = min(2048, max(1, maxTotalTokens / 4))
```

这是 V38 的实现参数，不反向声明为上游长期规范；已创建 task 的 reserve 永不随以后配置变化。

## 3. 创建与幂等

内部命令为：

```java
record CreateAgentTaskCommand(
    long userId,
    long agentId,
    String clientRequestId,
    String userInput
) {}
```

`userInput` 必须非空白，但校验后仍按原始值保存和参与指纹，不 trim。V38 的字段名固定为
`clientRequestId`；从 HTTP `Idempotency-Key` 取值属于 M4F。

指纹输入是对象 key 递归字典序排列、由 Jackson 固定转义的 canonical JSON：

```json
{"agentId":"1001","input":"原始 userInput","version":"agent-task-request-v1"}
```

对 UTF-8 bytes 计算 lowercase SHA-256。相同 owner/key/fingerprint 返回原 task，不解析新 snapshot、
不新增事件且不二次 dispatch；相同 key 不同 fingerprint 返回 `TASK_IDEMPOTENCY_CONFLICT`。并发唯一键
冲突会先退出失败的创建事务，再通过独立新事务读取胜者，绝不在 PostgreSQL aborted transaction 中查询。

新建事务使用 V37 resolver 冻结 snapshot 和预算，插入 `QUEUED` task，再 append
`TASK_CREATED(sequence=1)`。只注册 after-commit dispatch；回滚不 dispatch。

## 4. 持久事件

`TaskEventAppender` 是唯一 sequence 分配入口。在调用方短事务内执行：

```sql
UPDATE agent_task
SET last_event_sequence = last_event_sequence + 1
WHERE id = ?
RETURNING last_event_sequence;
```

随后用返回值插入 `agent_task_event`。禁止 `MAX(sequence_no)+1`。状态、phase 或终态更新和对应事件
在同一事务提交；单独追加事件不修改 optimistic `version`。

V18 event type CHECK 一次容纳规范完整枚举。V38 实际产生 `TASK_CREATED`、`TASK_STARTED`、
`PHASE_CHANGED`、`TASK_COMPLETED`、`TASK_FAILED`、`TASK_CANCELLED` 和 `TASK_TIMED_OUT`；执行型细粒度
事件留到 M4E。

## 5. Dispatcher 与 Runner

`TaskDispatcher` 的默认单实例线程池参数固定为：

```text
core=2, max=4, queueCapacity=100, rejection=Abort
```

参数可由部署配置覆盖，但以上是 V38 默认值。线程池拒绝或 after-commit submit 失败时，用独立短事务
条件更新 `QUEUED -> FAILED`，写 `SYSTEM_ERROR/TASK_DISPATCH_REJECTED` 和同 sequence 的 `TASK_FAILED`；
不会把已领取、已取消或已终止 task 改写。

Runner 以一条包含 `status='QUEUED' AND cancel_requested_at IS NULL` 的条件 UPDATE 领取。只有更新一行
的 runner 执行；领取与 `TASK_STARTED`、phase 与 `PHASE_CHANGED`、终态与终态 event 分别在同一短事务。
V38 的 delegate 在所有数据库事务外运行；脚本 seam 在 `PREPARING` 校验后只进入 `GENERATING`，不把
尚未接入的 RAG、decision 或 tool phase 伪造成已经执行。

生产默认 delegate 明确 fail closed 为 `TASK_INTERNAL_ERROR`，不会返回伪造成功。测试可注入脚本化
success/failure/timeout/cancel outcome。V36 AgentEngine、RAG、Trace 和 Tool task context 统一留到 M4E。

## 6. 取消与终态竞态

- `QUEUED`：owner-scoped 条件更新为 `CANCELLED`，同事务写 `TASK_CANCELLED`；
- `RUNNING`：只首次设置 `cancel_requested_at`，由 Runner 在安全边界观察；
- 终态：幂等返回当前状态；
- completion/failure/timeout 均要求 `cancel_requested_at IS NULL`；
- Runner 观察到取消后以条件更新完成 `RUNNING -> CANCELLED`；
- 行锁和条件谓词保证 completion、failure、timeout、cancel 最多一个终态更新成功。

V38 使用调用前后检查和组件 timeout 边界，不声称可强制中断已经阻塞的任意 Java 或外部调用。
排队时间不计入 deadline；deadline 从 `started_at + snapshot.agent.timeoutSeconds` 计算。

## 7. 重点验收

1. 空 PostgreSQL 从 V1–V18 完整迁移，task CHECK、event CHECK 和 owner 外键生效；
2. task、原值 snapshot、冻结预算与 `TASK_CREATED(sequence=1)` 同事务提交；
3. rollback 不 dispatch；相同 key/payload 不二次 dispatch，不同 payload 为 409 语义；
4. 真实 PostgreSQL 并发相同 key 只产生一条 task；并发 append sequence 连续唯一且 cursor 一致；
5. 两个 runner 只有一个领取成功，claim/phase/terminal 与各自 event 同事务可见；
6. pool rejection 不留下永久 `QUEUED`；
7. 真实 PostgreSQL 下 QUEUED cancel、RUNNING cancel 和 complete/cancel/timeout 竞态只有一个终态；
8. 脚本化 delegate 在数据库事务外，且覆盖 success/failure/timeout/cancel；
9. V27–V37 聚焦回归和完整 Maven suite 通过。

只执行以上重点、边界和一次必要回归，不把 mock/static 审计表述为真实 PostgreSQL 或外部 provider 证据。

## 8. 验收证据

2026-09-02 使用 Microsoft OpenJDK 21.0.11 与显式 Mockito javaagent 执行：

```text
V38 非数据库聚焦测试：          14/14 passed
V38 PostgreSQL 聚焦集成测试：     8/8 passed
完整 backend Maven suite：       517/517 executed tests passed
默认跳过的 opt-in PostgreSQL：      8（已在上一行独立运行并通过）
Failures: 0, Errors: 0
```

PostgreSQL 18.4 空库实际应用 V1–V18 共 18 个 migration。真实数据库测试覆盖 owner/status/JSON CHECK、
同 key 并发创建、并发 event append、双 Runner claim、dispatch rejection、QUEUED/RUNNING cancel、
complete/timeout/cancel 终态竞态以及状态与事件落库。Spring 测试注入 V37 形状的 snapshot 和脚本化
delegate；它不是 V36 Engine、真实 RAG/LLM/Qdrant/provider E2E 证据。

## 9. 明确不做

- 公共 `POST/GET /tasks` Controller、公共取消接口；
- SSE、Last-Event-ID、事件查询 API；
- `agent_step`、LLM/RAG Trace 表或 ExecutionRecorder；
- 修改 `tool_call_log` 的 task/step 外键；
- 前置 RAG；
- 将 V36 AgentEngine 正式接入 TaskRunner；
- RabbitMQ、Redis task 状态或多实例调度；
- stale RUNNING/进程崩溃恢复；
- Task 列表、详情、Trace 页面；
- 真实 LLM、Qdrant 或 provider E2E。

## 面试问题与回答

### 问题 1：为什么幂等唯一键异常后不能直接在原事务查询？

回答：PostgreSQL 语句触发唯一键错误后，当前事务已经 aborted，继续查询只会再次失败。V38 让创建
事务代理先完整退出，再用 `REQUIRES_NEW` 读取 `(user_id,client_request_id)` 的胜者，并比较固定版本指纹。

### 问题 2：为什么事件 sequence 不使用 `MAX(sequence_no)+1`？

回答：并发事务可能读到相同最大值。V38 原子递增 task 行上的 `last_event_sequence`；该 UPDATE 的行锁
串行化同一 task 的 append，并在同一事务插入事件，回滚时 cursor 和事件一起回滚。

### 问题 3：为什么必须 after commit 才提交线程池？

回答：提交前启动的 worker 可能看不到 task/event，或执行一个随后回滚的 task。after-commit hook 只在
创建事务成功后触发；拒绝时再用独立短事务写持久 FAILED 补偿。

### 问题 4：取消为什么对 RUNNING 只写请求时间？

回答：V38 不宣称能安全强杀阻塞调用。`cancel_requested_at` 是协作式取消事实；Runner 在安全边界观察，
而 completion/timeout 的 SQL 同时拒绝已有取消请求，因此竞态由数据库条件更新裁决。

### 问题 5：V38 的脚本化 delegate 能证明什么？

回答：它只证明外部执行不包在数据库事务中，并可验证成功、失败、超时、取消对应的状态和事件。
生产默认实现明确失败关闭；真实 V36 Engine、RAG、Trace、Tool task context 属于 M4E，未纳入本切片。
