# V0.2 施工契约：受控重启收尾与中断加固

> 文档日期：2026-09-12。审查基线：`main@dce66e3365f45f2492d1b10d7c03118ac1564514`。
> 状态：**V0.2-A 已实现，A01–A17 受控验收通过；V47 真实服务回归 BLOCKED；V0.2-B 待实现**。本轮没有整个 V0.2 发布声明。
> 版本范围来源：[Project Spec §7.1](../spec-docs/agentflow-hub-project-spec.md)；规范优先级见[文档索引](../spec-docs/README.md)。
> 执行顺序：**V0.2-A 单独施工、验收，再做 V0.2-B**。不因本文同时描述两片而一次实现全部功能。

## 1. 目标、术语与版本边界

本轮目标是：执行进程中断后，任务不永久假装正在执行；已知事实保留，未知结果明确，不自动制造第二次业务调用。

| 能力 | 本文定义 | 归属 |
| --- | --- | --- |
| 观察恢复 | 刷新、SSE 重连、重新 GET 同一任务 | 沿用 V0.1/V48，不重新执行 |
| 中断收尾 | 已确认旧执行进程退出后，将遗留任务收敛到可解释终态 | V0.2-A |
| 中断加固 | 外层取消/超时后的迟到结果、资源与终态持久化缺口 | V0.2-B |
| 执行恢复 | 从检查点续跑，或再次调用模型/工具 | 不在本轮范围 |
| 新任务重试 | 用户明确创建新的执行尝试 | 后续独立契约，不复活旧 task |

本文只覆盖 V0.2 的任务稳定性主线。Trace retention/进一步脱敏、文档与向量 reconciliation、完整 Compose 和容量压测仍按路线图另立切片；A/B 通过不等于整个 V0.2 Release Gate 通过。

### 1.1 已核对的实现起点

- [V48 契约](../V0.1-slice-docs/49_FAILURE_RECOVERY_E2E_PACKAGE_INTERFACE.md)只验收浏览器观察恢复。
- [BoundedTaskDispatcher](../backend/src/main/java/com/agentflow/agent/task/dispatch/BoundedTaskDispatcher.java)提交到进程内线程池；JVM 退出后，已入库的 `QUEUED` 也可能失去调度。
- [TaskRunner](../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java)负责领取、执行和终态仲裁；deadline 从 `startedAt` 与快照总时限计算。
- [生命周期事务](../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java)将条件状态更新与事件配对；普通 `fail()` 只处理 `RUNNING`，`markDispatchRejected()` 表达当场调度拒绝。
- [ExecutionRecorderTransactionService](../backend/src/main/java/com/agentflow/agent/trace/ExecutionRecorderTransactionService.java)中 step、LLM/RAG 记录与事件写入分别使用短事务；不能把多个 `REQUIRES_NEW` 方法包在外层事务后就声称整体原子。
- [TaskSnapshotAgentExecutor](../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java)的 LLM 日志在返回/异常处理后记录；observations、重复调用计数和部分预算仍在内存。崩溃可能留下 step 而没有对应 LLM 日志。
- [TaskExternalCallDeadline](../backend/src/main/java/com/agentflow/agent/engine/TaskExternalCallDeadline.java)限制调用方等待；底层 I/O 中断是协作式的。
- [高级设置契约](../V0.1-slice-docs/51_AGENT_ADVANCED_SETTINGS_PACKAGE_INTERFACE.md)已交付 V21 migration、snapshot v2、单次模型超时与已有失败元数据处理；本轮不重做这些功能。

### 1.2 本轮实际 HEAD 核对（2026-09-12）

当前 `HEAD=fb6a325a9d1906632ca81eee1d2eb10f86359214`，相对本文基线 `dce66e3365f45f2492d1b10d7c03118ac1564514` 只有三份文档增改：本文件、V0.3 首份契约、spec-docs/README.md；无生产代码/migration/测试漂移。保留本轮前已有 `backend/http/user-auth.http` 和 `backend/src/main/resources/application-dev.yml` 本地修改。

本轮先同步 Engine/Data/API/Frontend/Roadmap 的 V0.2-A 规范投影，再实现：复用 TaskEventAppender 的事务内 sequence 分配与既有生命周期/owner/幂等/快照读取；新增专用恢复 Mapper 和独立单任务事务，避免嵌套调用原 REQUIRES_NEW 记录服务。受影响入口为 task 创建与取消、内部 create/dispatch/run/claim、Task/Trace DTO、事件 recovery 摘要和 Task/Trace 前端；相关回归覆盖生命周期/执行/Trace/API/SSE、V48 与 V47 预检。下一可用 migration 为 V22，V1–V21 保持不变。

## 2. 给 Codex 的施工规则

先核对实际 HEAD 与本文基线的差异，列出复用点、受影响接口和测试；不得把文档里的“目标行为”当成已实现代码。先同步受影响的 Engine/Data Model/API/Frontend 规范投影，再提交 migration、实现和测试；不得只在 DTO 中偷偷创造新语义。

必须保持：现有任务 ID、owner 隔离、任务终态不可复活、幂等请求指向原任务、快照不可改写、事件序号由原追加器生成，以及正常执行中的短事务边界。不得修改已应用的 V1–V21；后续迁移取施工时下一个未使用编号，不预占另一分支的编号。

每片 PR 分开报告：实现了什么、运行了哪些命令、哪些环境被跳过、哪些外部边界受控、失败记录在哪里。不接受只修改说明文字或直接改数据库任务终态作为端到端验收。

## 3. V0.2-A：受控单实例重启后的遗留任务收尾

### 3.1 运行前提与部署互斥

第一片仅认证**单宿主机、同一数据库执行域、同一受控启动协议**。所有会创建、领取或执行 AgentTask 的应用进程必须参与该协议；外部供应商不是本地执行进程的一部分。

冻结最小互斥方案：受控启动入口取得本地持久路径上的排他进程锁，由执行 JVM 持有到进程真正结束。可使用 Java `FileChannel.tryLock()`；锁文件必须位于稳定的本地文件系统，不在每次重启新建的临时目录。不同数据库执行域使用不同锁；同一执行域的全部进程必须使用同一锁路径。应用内任务准入不得绕开该检查。

必须同时满足以下约束：

1. 锁获取失败、锁路径配置缺失或已知不支持排他锁时，拒绝进入恢复和任务写入阶段；不能“告警后继续”。
2. 不得仅因连接池断线、数据库锁释放、心跳过期、PID 文件存在与否或 `updated_at` 陈旧，就判断旧执行者已死亡。
3. 不提前释放进程锁来容许新实例启动。关机时先关准入、停止调度；旧 JVM 真正退出前，不能把执行域交给新 JVM。
4. 首次从不参与锁协议的旧版本升级，部署脚本必须先停止并等待旧 JVM 退出。新版本拿到文件锁并不能证明旧版 JVM 不存在；首次升级须保留冷切换记录。
5. 多宿主机、不同锁挂载、同一数据库另有不参与协议的执行者，不属于本片支持范围，禁止在这些环境启用自动收尾。文件锁不是跨主机 fencing。

新增部署开关 `agentflow.task.recovery.mode=DISABLED|CONTROLLED_SINGLE_HOST`，默认 `DISABLED`，不得根据数据库里“看起来很旧”的记录自动开启。`CONTROLLED_SINGLE_HOST` 要求显式配置稳定锁路径并完成上述部署前提。`DISABLED` 不执行任何恢复写入；若启动时发现非终态遗留任务，任务准入保持关闭并给出操作诊断，而不是跳过遗留任务继续宣称恢复完成。无遗留任务时，普通启动仍须遵守原单实例部署约束。

### 3.2 启动门禁：不只挡 dispatch

顺序冻结为：

```text
关闭任务准入与调度
→ 确认受控执行域与进程互斥
→ 完成必要迁移并读取遗留集合
→ 分批、逐任务原子收尾
→ 复查无未处理候选且无收尾失败
→ 开放任务准入与调度
```

门禁必须在任务应用服务层生效，同时覆盖 `POST /api/v1/agents/{agentId}/tasks`、取消写入及内部调度/Runner 领取入口。不能只依赖负载均衡、前端禁用按钮或 Spring readiness；直接访问已监听的应用端口也必须受到约束。

准入关闭时，任务写接口返回 `503 / TASK_EXECUTION_NOT_READY`，不创建 task、不消费幂等键、不留下 after-commit dispatch。普通鉴权只读 GET/Trace/SSE 可用时仍沿用原 owner 检查；liveness 不代表任务可接收。启动后的首次任务不得进入遗留集合。

若任何候选收尾失败，保持准入关闭并输出可操作的启动诊断；不得把失败任务静默跳过后标记 READY。恢复事务已经提交的任务无需回滚，下一次启动继续处理剩余非终态任务。

### 3.3 候选集合与终态政策

只有在 3.1–3.2 前提成立时，启动时数据库中的 `QUEUED/RUNNING` 才可作为旧执行域遗留任务处理。用有界批次、稳定主键游标读取候选；不使用年龄阈值，不设置运行期定时扫描。

| 旧任务情况 | 新状态 / terminationReason | task.errorCode | 事件与解释 |
| --- | --- | --- | --- |
| `RUNNING` 且无取消请求 | `FAILED / SYSTEM_ERROR` | `TASK_RESTART_INTERRUPTED` | `TASK_FAILED`；执行中断，最终结果未确认 |
| `RUNNING` 且已有取消请求 | `CANCELLED / USER_CANCELLED` | 保持现有取消语义，null | `TASK_CANCELLED`；在 recovery 元数据记录中断原因 |
| `QUEUED` 且无取消请求 | `FAILED / SYSTEM_ERROR` | `TASK_RESTART_DISPATCH_LOST` | `TASK_FAILED`；调度随进程退出丢失，不重新投递 |
| 异常遗留 `QUEUED` 且已有取消请求 | `CANCELLED / USER_CANCELLED` | null | 防御性收敛；标记该组合非正常创建路径 |
| 任意已有终态 | 完全不变 | 不变 | 不追加第二条终态事件 |

`QUEUED` 正常取消已在当前事务中直接终结；防御性分支不得被用来引入新的“取消等待排队”状态。

重启时超过原 deadline，不足以证明旧进程当时因超时结束，**不得因此改成 `TIMED_OUT`**。第一片将遗留 `QUEUED` 失败是范围选择，不是宣称未来不能可靠投递。

收尾设置 `phase=null`、`completedAt=recoveredAt`；这里的时间是本地收尾时间，不是推测的崩溃时间。保留原 `startedAt/cancelRequestedAt`，不为未执行任务伪造开始时间。不得发布 `finalAnswer`、引用或 `ANSWER_CHUNK`；即使 final 的成功调用日志已存在，也不能据此重新执行完成发布流程。

### 3.4 证据完整性与恢复元数据

新增 nullable `agent_task.recovery_metadata JSONB`，公开 Task 详情和 Trace 使用可选 `recovery` 对象投影。历史未恢复任务保持缺失，不能回填为“完整”。数据库字段、DTO 和前端同一实现 PR 同步。

`recovery` 的最小字段冻结如下；所有公开 ID 使用字符串：

| 字段 | 契约 |
| --- | --- |
| `schemaVersion` | `task-recovery-v1` |
| `mode` | `CONTROLLED_SINGLE_HOST` |
| `recoveryRunId` | 本次启动恢复批次的不可变标识；重复启动不改已收尾任务的值 |
| `recoveredAt` | 本次收尾时间，带时区 |
| `previousStatus` | 收尾前实际状态 |
| `reasonCode` | `TASK_RESTART_INTERRUPTED` 或 `TASK_RESTART_DISPATCH_LOST`；取消任务也保留该事实 |
| `executionOutcome` | `NOT_STARTED` 或 `UNKNOWN`；不是供应商状态查询结果 |
| `recordCompleteness` | `COMPLETE` 或 `UNCONFIRMED`；后者表示无法证明记录完整，不一定已证明有缺失 |
| `counterCompleteness` | `COMPLETE` 或 `UNCONFIRMED`；指预算消费计数，不是日志条数 |
| `recordedUsage` | 已持久化 LLM 日志去重汇总的 input/output/total tokens，及这些记录自己的 usage quality |
| `recordedLlmCalls/recordedToolCalls` | 已持久调用日志条数，不等于模型轮数/工具预算消费次数 |
| `previousTaskUsage` | 恢复前 task 汇总的数值与质量，保留审计依据 |

正常 `QUEUED` 在没有 step/调用/执行事件证据时可标为 `NOT_STARTED + COMPLETE`，零表示本地没有开始执行；不得写成供应商返回的 `EXACT` 用量。若 `QUEUED` 带有执行证据，不猜测、不删证据：作为数据不变量异常拒绝该任务收尾并保持门禁关闭，输出安全诊断。

`RUNNING` 保守标为 `executionOutcome=UNKNOWN`、两种 completeness 均为 `UNCONFIRMED`。不能从缺少调用日志推出“从未调用”。任务被收尾失败不代表供应商未执行、未计费或外部业务未发生。

用量汇总规则：

- 只累加已经持久化且归属于本任务的唯一 LLM 调用记录，不把 step summary 再累计一次，不补造不存在的调用行。
- 保留每条日志原有 `EXACT/ESTIMATED/UNKNOWN` 事实；混合记录沿用当前汇总规则，不把估算改为精确值。
- 中断 `RUNNING` 的 task token 数值可由该持久日志集合重建，但 task 级 `tokenUsageQuality` 必须为 `UNKNOWN`，完整性由 `recovery` 说明。原 task 数值先存入 `previousTaskUsage`。
- 若已有非零 task 汇总与持久证据相互矛盾，不按组件取最大值、不双重相加、不覆盖后假装一致；该任务收尾失败并报告数据不变量问题。正常基线中未终结任务的初始零汇总不是这种冲突。
- 无日志时的零只能显示为“当前无已记录用量，完整性未确认”；有日志时显示“已记录 N tokens，可能不完整”，不能显示“总费用为零”。
- `decisionTurnsUsed/toolCallsUsed` 无法仅靠日志条数完整还原，保持已有持久值并标为未确认；参数拒绝、复用 observation 和日志前崩溃尤其不能用一行一调用机会推导。

不要求本片把全部 LLM 改成调用前置账本，也不新增供应商查询/对账。将来引入调用账本仍须独立解决“请求已发出、供应商是否执行未知”的窗口。

### 3.5 未结束的 step 与调用记录

所有已终结 step 和调用日志保持原结果、正文/摘要、用量和关联关系；成功工具日志不能因为整个 task 失败而改成失败。

对未结束的本地 step 和已有 `RUNNING` 工具日志，按现有状态集合收尾为 `FAILED`，使用 `TASK_RESTART_INTERRUPTED` 和安全说明“本地执行进程中断，外部结果未确认”。这里的 FAILED 是本地记录未完成，不是断言外部业务失败。保留原开始时间，结束时间写恢复时间。

LLM/RAG 若没有对应持久调用行，只收尾已有 step、在 recovery 标明证据未确认；不伪造一次失败调用、供应商 request ID、延迟或 token 消耗。已经保存的 final response 只留在合法的脱敏 Trace 中，不提升为权威最终答案。

已有终态 task 即使存在历史 Trace 缺口也不由 A 修改；如需修复，另立终态 Trace 修复契约。

### 3.6 单任务原子事务

恢复协调器不持有跨全部任务的大事务；单任务入口可使用独立短事务，但其内部所有 Mapper/追加器必须参与**同一个物理事务**：

```text
锁定 task 行，复核状态/取消意图/候选资格
→ 校验本地证据不变量并记录恢复前汇总
→ 收尾未完成 step 和已有调用记录
→ 条件更新 task 终态、usage 与 recovery_metadata
→ 使用既有 TaskEventAppender 分配 sequence 并追加唯一终态事件
→ COMMIT
```

禁止组合现有多个 `REQUIRES_NEW` 服务来假装原子；复用 Mapper 或参与当前事务的内部方法。不要为此全局修改正常执行的事务传播方式，也不要依赖同类自调用触发 Spring 事务代理。

行锁内发现已终态，整个单任务操作无写入返回；条件更新影响 0 行时不能提交任何 step/调用/事件修改，必须无副作用返回或回滚。事件插入失败时，状态、计数、记录收尾与 sequence 分配一起回滚。

终态事件只追加 `TASK_FAILED` 或 `TASK_CANCELLED`，payload 带最小 `recovery` 摘要（版本、批次、原状态、原因、完整性），不能塞入完整日志。保留原事件序列，不能重编号。一个原非终态任务只产生一次有效终态提交；协调器重复调用、启动再次中断和提交应答丢失都必须安全重入。

恢复路径不调用 Dispatcher、Engine、LLM、embedding、Qdrant 或工具 handler，不做外部 I/O。

### 3.7 GET、Trace、SSE 与前端

GET 和 Trace 的终态与 recovery 来自同一持久记录；SSE 回放同一终态事件，不自行计算另一种恢复状态。原 sequence/游标契约、owner 检查和大整数无损处理不变。

前端至少区分“执行中断 / 调度中断 / 取消且执行曾中断”，显示未知用量说明。`completedAt - startedAt` 对恢复任务不是可信执行耗时，不能混入普通任务性能比较。刷新和重连不能自动创建新任务、补发外部调用或推出重试按钮的隐式执行。

## 4. V0.2-A 验收矩阵

使用 disposable PostgreSQL、真实应用进程与真实任务入口。测试控制点通过 test-source 夹具暴露，不新增生产故障接口。每个进程内控制点须有“已到达/允许继续”信号，不能只靠 sleep 猜时序。

| ID | 场景 | 必须成立 |
| --- | --- | --- |
| A01 | 任务已入库排队，Runner 尚未领取，强制结束 JVM 并重启 | 遗留 `QUEUED` 失败；无 `TASK_STARTED`、无外部调用、无重新投递 |
| A02 | 已 claim、首个外部调用之前终止 | `RUNNING` 中断失败；不因没有日志声称供应商用量精确为零 |
| A03 | 模型服务已收到请求，JVM 尚未记录 LLM 结果时终止 | 允许没有 LLM 行；step 收尾；结果/完整性未知；重启不发送第二次请求 |
| A04 | 工具 handler 已进入但日志未终结时终止 | 工具日志本地失败、外部结果未知；不重复 handler |
| A05 | 工具成功日志已提交，step 或 task 尚未终结时终止 | 成功工具结果完整保留，task 按中断收尾 |
| A06 | final 成功日志已提交、最终答案事务尚未提交时终止 | 不发布 finalAnswer/ANSWER_CHUNK；成功调用及 usage 保留 |
| A07 | 取消请求先落库，再中断；另测取消与恢复竞争的事务级夹具 | 最终取消且保留中断事实；一条终态事件 |
| A08 | 重启时超过旧 deadline，覆盖 RUNNING 与 QUEUED | 不自动改成 TIMED_OUT |
| A09 | 已有 COMPLETED/FAILED/CANCELLED/TIMED_OUT，再启动两次 | 任务与 Trace 不改写，终态事件与 sequence 不增加 |
| A10 | 在单任务收尾各写入点注入数据库失败，尤其事件追加失败 | 整个单任务回滚；准入保持关闭；下一次启动可处理 |
| A11 | 一批中途再次 kill JVM；另测 COMMIT 成功但客户端应答丢失 | 已提交不重复；未提交可再做；不能产生重复终态/重复计账 |
| A12 | 旧实例仍持锁；新实例启动，含旧调用长期无 updated_at 更新 | 新实例拒绝恢复；旧任务不能被误失败 |
| A13 | 门禁关闭时直接访问 HTTP，并触发内部任务入口 | 503/明确拒绝；无 task、幂等键和 dispatch 副作用 |
| A14 | 一例证据异常、一次迁移/恢复读取失败 | 不带病开放准入；明确列出失败任务/阶段，不静默跳过 |
| A15 | 同一任务 GET、Trace、SSE、刷新与重复原幂等请求 | 同一终态/原因/恢复元数据；不执行第二次；无权 owner 不可读取 |
| A16 | 历史 v1/v2 快照、完整与缺失调用日志、EXACT/ESTIMATED 混合用量 | 不使用今天默认重写历史；不重复累计、不把不完整汇总标 EXACT |
| A17 | 关闭恢复模式但库中有遗留任务；以及首次旧版冷切换 | 不自动写恢复状态；任务入口不放行；有明确受控启用/冷切换证据 |

A03 的模型服务应运行在被测 JVM 之外，保存按 case/task 关联的接收次数与响应闸门；在 kill 之后仍可读证据。工具边界的 durable 计数也应位于独立夹具或专用测试记录中，不与将被回滚的业务事务混淆。外部服务器收到请求证明的是受控 HTTP 边界，不是付费供应商真实执行或计费。

主验收必须真实终止进程并等待退出，不能以抛异常、关闭浏览器或仅重启 Spring context 代替。恢复前后核对请求/handler 次数、日志内容、任务记录、事件、序号和副作用；恢复造成的新增外部调用数必须为 0。

## 5. V0.2-B：取消、迟到结果与终态持久化缺口

### 5.1 取消/超时后实际调用未退出

沿用整体 deadline、单次模型时限和用户取消的区分，不重新实现一套预算机制。外层不再等待不等于 provider 停止，也不等于 Future 对应的实际工作线程已经退出。

必须对 LLM、embedding/vector 与工具各调用路径核对真实工作边界：

- 调用前检查取消/剩余时限，正常返回先保留可用 usage 再仲裁；取消/超时后不触发后续决策、工具、答案发布或任务复活。
- 底层若晚返回，不能绕过任务/记录的条件状态更新继续覆盖结果；特别核对 handler 内部仍可能执行的日志更新。
- 第一片 B 不建设供应商迟到结果对账。已被终结的调用记录不改回 SUCCESS；可记录安全诊断，但不得泄露原始响应、覆盖先前终态或把未知用量认定为零。
- 限制的是**实际尚未退出的本地外部调用工作**，不是仅限制等待者。新增独立有界并发许可，首版默认上限 4；测试可调为 1。准入等待受取消/deadline 约束，不建立无限等待队列。
- 许可由工作体真正退出的 `finally` 释放，不能在调用方超时或 `Future.isDone()` 后提前释放。任务若尚未进入工作体即取消，仍须有一次且仅一次的许可归还路径。
- 不允许嵌套包装重复占用同一资源的许可而自锁；根据调用图确定每个真实工作体的唯一拥有者。线程池、有界许可和 JDBC/HTTP 超时分别验证，不以虚拟线程便宜为理由忽略资源边界。

永久不响应中断的依赖可能一直占有许可。B 的保证是限制损害、停止新工作并可诊断，不是安全杀死任意 Java 代码；人工受控重启仍可能必要。

### 5.2 业务执行结束，但终态写入失败

把“再次保存同一个已观测执行结果”和“重新执行任务”分开。只对可识别的暂时性数据库故障作有限收尾重试；不得重跑 `executionDelegate.execute()`、恢复 observations 或再次请求模型/工具。

冻结首版策略：单任务首次尝试加最多 2 次重试；退避分别为 100ms、500ms，可在测试中使用受控时钟。数据库连接/锁/语句必须有有限超时；约束违反、非法数据等非暂时错误不盲重试。待收尾结果保持在有界的在进程上下文中，不创建新的通用可靠队列。

每次重试先读取已持久 task：若已经终态，接受原结果而不再发事件。仅当仍非终态时重新执行条件终态事务。COMMIT 应答未知必须回读核对，不能立即假定回滚后改写为另一终态。

保存最初已观测的 outcome、用量及完成观察时间；不能因为重试等待跨过 deadline 就把原先确定的结果重新解释为“当时已超时”。取消仍按现行持久取消意图与终态条件更新仲裁，不能覆盖已经提交的取消。该精化必须同步 Engine 契约并测试与现有优先级的兼容。

重试耗尽时：输出 task ID、尝试次数、失败阶段和安全错误码 `TASK_SETTLEMENT_PERSIST_FAILED`，关闭新增任务准入并使任务执行健康状态降级；不伪造数据库里已经有终态，也不无限堆积待收尾结果。数据库持续不可用或 JVM 随后退出时，不能承诺及时收尾；下一次满足 A 条件的受控重启处理剩余非终态。

运行期准入关闭时，已提交而尚未 dispatch 的新任务必须通过原有可持久化拒绝路径收尾；无法落库的情形同样进入降级诊断，不能在后台悄悄丢弃。

### 5.3 B 验收矩阵

| ID | 场景 | 必须成立 |
| --- | --- | --- |
| B01 | 忽略中断的 LLM/工具，外层取消或超时后再允许返回 | 不发布迟到答案、不执行后续动作、不复活终态 |
| B02 | 连续制造不退出调用，超过许可上限 | 实际工作体数量不突破上限；取消等待可退出；许可不提前释放 |
| B03 | 取消发生在许可获得前、获得后但工作体进入前、正常返回边界 | 无许可泄漏/重复释放；无不必要新调用；可用 usage 保留 |
| B04 | 单次模型时限与整体 deadline 分别先到 | 保留 `AGENT_LLM_TIMEOUT` 与任务超时语义区分 |
| B05 | 终态首次写失败、后续成功 | 只重试持久化；一条终态事件，业务调用次数不变 |
| B06 | COMMIT 已成功但应答丢失；另测事件插入回滚 | 回读幂等；不把成功提交改为失败；未提交事务完整重试 |
| B07 | 重试期间到达 deadline/收到取消/另一终态已提交 | 不因重试耗时伪造历史超时；尊重已持久仲裁 |
| B08 | 数据库持续故障或非暂时数据错误 | 有界退出与准入降级；不谎报终态、不重执行业务 |
| B09 | 重试过程再 kill JVM 并按 A 重启 | A 能收尾遗留任务；不能从内存丢失结果恢复为成功 |
| B10 | 迟到 handler 尝试更新调用日志/step，GET/Trace/SSE 持续读取 | 条件更新守住记录终态；观察结果一致，未知部分保留说明 |

## 6. 交付、回归与停止条件

A 的交付必须包含启动/冷切换说明、受控进程锁与门禁、收尾服务和新增 migration、Task/Trace/UI 最小投影、真实进程重启验收入口与机器可读证据。B 的交付包含逐调用路径审计表、资源许可、中断边界测试、有限终态保存重试与降级证据。

每片至少执行对应 Java 单元与真实 PostgreSQL 测试、迁移契约、前端测试/构建及相关浏览器用例；复跑 V48 相关取消、超时、幂等、SSE 受控矩阵。生产执行路径改变后，按已有要求补跑 V47 真实成功主路径；没有凭据/环境时明确记录未执行，不能用受控成功替代真实验收。

证据至少含：代码 SHA、schema/migration 版本、模式与锁作用域（不含秘密）、进程启动/退出与控制点时间、case/task ID、预期与实际终态、调用计数、前后持久记录摘要、事件序号、失败记录与被跳过项。端口、测试库、控制目录独立隔离，失败材料保留，不提交密钥、日志原始正文或本地临时目录内容。

| 里程碑 | 完成门槛 | 当前状态 |
| --- | --- | --- |
| V0.2-A | A01–A17 必测；原子性、互斥与零重发无例外；相关回归证据齐备 | 已实现；A01–A17 17/17 与相关受控回归通过；V47 因凭据缺失 BLOCKED，全部发布证据未齐 |
| V0.2-B | B01–B10 必测；资源有界、迟到结果隔离、仅持久化重试成立 | 未实现 / 未验收 |

阻断项未解决时停止扩大范围，先记录最小复现；不得删除断言、跳过失败样本、缩小到单元测试后宣称完成。

## 7. 明确不做

不做运行期“凭年龄杀任务”、心跳/租约/fencing、多实例接管、自动续跑、可靠任务重新投递、自动模型/工具重试、写工具补偿与对账、供应商立即停止承诺、RabbitMQ/Redis task state、任务状态机重写或第二套执行账本。需要这些能力时先写独立契约及故障模型。

## 8. 依据与框架边界

源码及现有契约链接见 §1.1。框架语义仅用于说明约束，不替代本项目测试：

- [Java 21 FileLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html)：锁生命周期与平台/协作约束；不能推导跨主机互斥。
- [Java 21 Future](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html)：取消是尝试中断，不是强制结束底层计算。
- [Spring 事务传播](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)：`REQUIRES_NEW` 使用独立物理事务。
- [Spring Boot 启动生命周期](https://docs.spring.io/spring-boot/reference/features/spring-application.html)：应用生命周期/readiness 不替代任务服务层准入检查。

## 9. 本轮实际施工与验收记录（2026-09-12）

本轮实现位于 `fb6a325a9d1906632ca81eee1d2eb10f86359214` 之上的未提交工作树；该 HEAD 本身仍是文档基线，不能将它单独当成本轮实现提交。实际命令、逐次失败、最终矩阵和机器摘要保存于 [V0.2-A-20260912.json](evidence/V0.2-A-20260912.json)。

### 9.1 实际修改

- Engine、Data Model、API、Frontend、Roadmap、Project Spec 与规范索引同步本片投影，V0.2-B/V0.3 保留后续范围。
- `TaskExecutionProcessLock` 在 Flyway 写入前取得稳定本地排他锁，DISABLED 同样强制参与；无 context 销毁释放锁。`TaskExecutionAdmission` 从构造起关闭，关机关闭不可被迟到的启动成功重新开放；创建、取消（事务外先检查）、内部 create/dispatch/run/claim 均有门禁。
- `TaskRecoveryStartupCoordinator` 只执行一次启动扫描，按主键有界分页、逐任务独立短事务、最终复查后开放；任一读取或收尾失败保持关闭并报告安全阶段/task ID/不变量原因。
- `TaskRecoveryTransactionService` 与专用 Mapper 在一个物理事务内锁行、验证证据、收尾未结束记录、更新 task/usage/recovery、调用原 TaskEventAppender；无 Executor/模型/工具/embedding/Qdrant 依赖。终态、快照、成功日志与预算消费计数不改写。
- V22 新增 nullable JSONB recovery_metadata；只对 `FAILED + TASK_RESTART_INTERRUPTED` 放开 step/tool 的未知 latency，保留普通终态约束，V1–V21 未修改。新增非终态已有终态发布事实/异常用量/异常 QUEUED 证据拒绝，避免补造或重复事实。
- Task、Trace.task、终态 SSE 安全投影 recovery；前端显示执行/调度/取消中断、未知用量和收尾时间，GET/Trace 收敛核对 recovery，503 明确拒绝不进入未知提交状态；没有新增执行重试按钮或自动续跑。
- `task-cold-cutover.py` 只停止明确指定的旧 Java PID，确定退出后记录并 exec 新 JVM，观察失败或等待超时拒绝切换；新 test-source JVM 夹具、独立受控 HTTP 计数与真实 PG 故障注入支持 A01–A17。验收入口和冷切换说明同步 README/.env.example/既有受控启动脚本。

### 9.2 已执行命令与结果

所有 Java 命令使用 JDK 21，Mockito 测试用现有 `mockito-core/5.17.0` javaagent；测试库均为 disposable PostgreSQL，受控应用直接启动真实 JVM，非 Spring context 重启。

```bash
JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home mvn -q -f backend/pom.xml '-DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar' '-Dtest=TaskExecutionAdmissionTest,AgentTaskApplicationServiceTest,AgentTaskCreationTransactionServiceTest,TaskRunnerTest,AgentTaskMapperContractTest,AgentTaskControllerTest,SafeTaskEventProjectorTest,PublicTaskTraceProjectorTest,V18AgentTaskMigrationContractTest,V19AgentExecutionTraceMigrationContractTest,TaskSnapshotAgentExecutorTest,AfterCommitTaskDispatchCoordinatorTest,AgentTaskDispatcherConfigurationTest' test dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=/tmp/agentflow-v02a-classpath.txt
# 最终生产代码编译与 A01–A17（已实际执行，第二条从仓库根运行）
(cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -DskipTests test-compile)
bash scripts/v02a-restart-acceptance.sh --compiled-classpath /tmp/agentflow-v02a-classpath.txt --pg-port 55463 --run-dir /private/tmp/agentflow-v02a-matrix-run6
JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home mvn -q -f backend/pom.xml '-DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar' -Dtest=TaskExecutionAdmissionTest,AgentTaskControllerTest test
npm --prefix frontend test
npm --prefix frontend run build
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_task_cold_cutover.py -v
python3 scripts/test_v02a_acceptance_evidence.py
TMPDIR=/private/tmp V48_CONTROL_DIR=/private/tmp/agentflow-v02a-v48-regression-host-20260912 bash scripts/v48-failure-recovery-acceptance.sh
bash scripts/v47-real-provider-acceptance.sh --preflight-only
```

| 验证 | 结果 / 证据边界 |
| --- | --- |
| 后端聚焦创建、门禁、Runner、Mapper、DTO、迁移、执行器回归 | 初轮 98/98，无失败或跳过；最后门禁补测另记机器摘要 |
| 恢复核心 + 门禁 + V22 | 14/14 真实 PG、1/1 migration；门禁最终 7/7，连同 11 个 Controller 测试最后补测 18/18 |
| 既有真实 PG 生命周期/执行/Trace/API/SSE | 最终 45/45；五个类各用独立库/锁/JVM，精确命令见机器摘要 existingPostgresAttempts |
| 前端单元、构建、新浏览器规格类型检查 | 99/99、build、strict tsc 均通过 |
| 冷切换脚本边界 | 4/4，通过 mock signal/exec 检验观察错误拒绝、确证退出/僵尸及超时；不是实际冷切换替代品 |
| V48 全矩阵 | 22/22 浏览器、803 case PG checks、3 global checks，全通过；零自动执行/Playwright 重试 |
| A01–A17 与 A15 浏览器 | 最终 run6 **17/17 + 3/3 全通过**；25 个任务、26 个外部接收记录、26 个 Runner 接收（其中 1 个为明确拒绝的内部探针），恢复新增外部调用 0、意外 Runner 重入 0 |
| V47 真实模型/DashScope/Qdrant 成功回归 | **BLOCKED / 未执行**；缺 DASHSCOPE_API_KEY/OPENAI_API_KEY，无 .env.local/V47_ENV_FILE，未发付费请求；受控成功不替代此证据 |

上列 run6 目录为已完成证据目录，复跑需改为新的 `--run-dir`；`--compiled-classpath` 只能复用刚编译的当前工作树，普通入口可不传而自动构建。

实际 A01–A17 逐项状态及时间保存在 `/private/tmp/agentflow-v02a-matrix-run6/matrix.json`；每项 `cases/Axx-summary.json` 包含 task 映射和持久记录摘要，专用取消竞争、写入故障、COMMIT 应答丢失、冷切换材料在同目录。run6 是实际 SIGKILL + waitpid + restart；没有以抛异常或重启 Spring context 替代。

### 9.3 失败、跳过与停止边界

失败明确记录：初次并发编辑期间的测试编译接口不一致（原始内容仅保留在工具会话 35044，没有单独日志文件）；核心 PG fixture 漏必填 display_name；旧 Trace 迁移总数断言仍为 20；sandbox loopback/共享内存/浏览器权限限制。前两项修复后通过；迁移断言按实际 V22 改为 22 后独立新库通过；受控本机验收在已授权 host 环境的新目录重跑。

进程矩阵各失败运行另存：run1 端口被另一个 disposable PG 占用，未创建任务；run2 READY 知识绑定夹具缺失，真实任务入口正确返回 409；run3 A01–A10 通过后，A11 的新写入探测误复用原任务幂等键；run4 A01 通过后，新增证据摘要误迭代 case 名称而非 task ID。均修正夹具或证据代码、保留旧材料，再使用全新库及新任务执行矩阵；没有对遗留 task 重新投递或自动重试业务执行。run5 已 17/17+3/3 通过，随后增加取消事务外门禁并补测 18/18，再以最终生产代码执行 run6，全部再次通过。

付费供应商真实回归因环境缺失明确未执行。A 中 COMMIT 应答丢失使用真实 PostgreSQL COMMIT 成功后的 test-only 代理异常，是受控应答丢失窗口，不宣称真实网络物理断包。旧版冷切换使用当时 Git HEAD `fb6a325` 的实际 pre-lock 后端单独编译运行，仍是隔离数据库内的受控升级，不是现网部署证明。交付入口随后将 `--legacy-ref` 默认值固定到该完整 SHA，解析并记录实际 commit，避免 A 合并后把新 HEAD 当旧版；`baseline-equivalence.json` 证明与 run6 实际归档源码相同，两个纯脚本回归通过。

本轮不实施 V0.2-B、V0.3，不做运行期扫描/续跑/重新投递/外部调用重发；不以受控矩阵宣称真实供应商中断结果或计费已确认，也不宣称整个 V0.2 Release Gate 通过。

## 面试问题与回答

**问题 1：这次“恢复”到底恢复什么？**

回答：本片实现旧 JVM 确认退出后，将遗留任务收尾，不恢复内存执行状态。启动锁、门禁和原子收尾的实际验收见第 9 节；这不代表自动续跑、供应商结果对账或重试调用能力。

**问题 2：为什么不能扫描 updated_at 超时的 RUNNING 后直接失败？**

回答：长外部调用可能没有状态更新，失联不能证明旧工作已停止。A 只在受控冷重启、进程互斥、任务准入关闭的前提下处理遗留集合；多实例租约与 fencing 未纳入本片。

**问题 3：为什么恢复 task、step 和事件需要新的事务边界？**

回答：现有多个服务方法分别使用 REQUIRES_NEW，外面再加事务不会使它们一起回滚。本片的独立恢复事务直接复用 Mapper 和 TaskEventAppender；真实 PostgreSQL 故障注入验证事件失败连同状态、记录收尾和 sequence 一起回滚。

**问题 4：为什么没有 LLM 日志也不能说调用没有发生？**

回答：基线是在调用返回或异常处理后写 LLM 日志，崩溃窗口可能发生在请求已发出而日志未写。A 保留已记录用量并标记完整性未确认，不虚构零费用，也不借恢复补发请求。

**问题 5：取消后 Future 已完成，为什么还要限制实际工作体？**

回答：取消的 Future 可以已结束而底层工作尚未退出。B 的目标是许可随真实工作体退出释放，避免超时后不断新增失控调用；不承诺能强制停止任意不合作依赖。

**问题 6：终态写入重试为什么不算任务重试？**

回答：B 只保存同一份已观测 outcome，回读数据库并做条件更新；不会再次进入 Engine 或发模型/工具调用。重试次数有限，持续数据库故障要降级和说明未收尾事实，不能宣传为无限可靠恢复。
