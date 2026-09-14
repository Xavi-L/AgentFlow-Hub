# AgentFlow Hub Archify 架构图集施工指南

> 目的：指导 Codex 使用 Archify 对当前 `AgentFlow-Hub` 仓库进行分层、可验证、可持续更新的架构分析。
>
> 本文不是“画一张总架构图”的说明，而是一套 **Architecture Atlas（架构图集）施工契约**。每张图只回答一个明确问题，并以真实代码、数据库结构、配置和验收证据为依据。图之间互相补充，而不是把所有信息堆进一张大图。
>
> 适用基线：以施工时当前 `main` 为准。开始任何一张图之前，都必须重新检查 HEAD 和实际代码，不得直接复用本文对历史实现的描述作为事实。

---

## 1. 总体目标

最终在仓库中形成一套可导航的架构知识库，建议放置于：

```text
docs/architecture/
├── ARCHITECTURE_ATLAS.md
├── ARCHITECTURE_AUDIT.md
├── 01-system-context.architecture.json
├── 01-system-context.html
├── 02-backend-runtime.architecture.json
├── 02-backend-runtime.html
├── 03-task-e2e.workflow.json
├── 03-task-e2e.html
├── 04-task-create.sequence.json
├── 04-task-create.html
├── 05-agent-loop.workflow.json
├── 05-agent-loop.html
├── 06-rag.dataflow.json
├── 06-rag.html
├── 07-tool-call.sequence.json
├── 07-tool-call.html
├── 08-task-lifecycle.lifecycle.json
├── 08-task-lifecycle.html
├── 09-dispatch-concurrency.workflow.json
├── 09-dispatch-concurrency.html
├── 10-failure-cancel.lifecycle.json
├── 10-failure-cancel.html
├── 11-restart-recovery.workflow.json
├── 11-restart-recovery.html
├── 12-sse-observation.sequence.json
├── 12-sse-observation.html
├── 13-config-version.dataflow.json
├── 13-config-version.html
├── 14-evaluation.workflow.json
├── 14-evaluation.html
├── 15-persistence.architecture.json
└── 15-persistence.html
```

目录名、文件名可根据 Archify 实际输出要求微调，但编号、主题和图之间的职责边界应保留。

---

## 2. 施工总原则

### 2.1 先证据，后绘图

任何图都不得仅凭 README、类名、包名或文档标题直接生成。

证据优先级：

1. **生产代码（CODE_CONFIRMED）**
2. **数据库 migration / 配置 / schema（CODE_CONFIRMED）**
3. **自动化测试、受控验收脚本（TEST_CONFIRMED）**
4. **版本切片、项目 spec、README（DOC_DECLARED）**
5. **无法确认的推断（UNKNOWN）**

若文档和当前代码冲突：

- 图中优先表达当前代码事实；
- 在 `ARCHITECTURE_ATLAS.md` 中记录冲突；
- 不得把 `DOC_DECLARED` 自动升级为 `CODE_CONFIRMED`。

### 2.2 一张图只回答一个问题

禁止 mega diagram。

如果一张图开始同时解释：

- 模块组成；
- 调用顺序；
- 状态变化；
- 失败恢复；
- 数据流；

则必须拆图。

Architecture 图原则上控制在约 8–12 个主要节点。需要更多细节时建立子图。

### 2.3 必须区分四类“状态”

整个图集中反复出现的一个关键边界是：

```text
persisted task state
!= runtime execution state
!= external provider call state
!= browser observation state
```

不得把：

- Future 已 cancel；
- Java 调用已 timeout；
- SSE 断开；
- 浏览器刷新；

等价为“真实外部调用已经终止”或“任务重新执行”。

### 2.4 必须标出关键边界

只要与当前图主题相关，应显式表达：

- process boundary；
- thread / async boundary；
- transaction boundary；
- persistence boundary；
- network boundary；
- ownership / authorization boundary；
- trust boundary；
- external dependency boundary。

### 2.5 每张图必须保留证据索引

每个图在 `ARCHITECTURE_ATLAS.md` 中至少记录：

```text
Diagram:
Question:
Type:
Primary path:
Key nodes:
Key evidence:
Unknowns:
JSON:
HTML:
Validation:
Visual check:
```

重要 edge 应能反查到：

```text
source component
relationship
mechanism
sync/async
source file
class
method
persistence side effect（如有）
evidence level
```

---

## 3. Archify 通用施工流程

每一张图均按以下步骤执行。

### Step A：限定问题

先写一句：

> 这张图只回答什么问题？

若不能用一句话回答，则范围仍过大。

### Step B：Repository reconnaissance

只检查与当前图有关的仓库区域，不要一次性把整个仓库塞入上下文。

需要优先检查：

- 实际调用入口；
- interface / implementation；
- configuration；
- repository / persistence；
- enum / state transition；
- tests；
- acceptance scripts；
- 对应 slice/spec 文档。

### Step C：先整理事实，不急着画

施工前先列：

```text
Confirmed nodes
Confirmed edges
Confirmed states
Confirmed boundaries
Confirmed failure paths
Unknown / unproven facts
```

若关键关系仍为 UNKNOWN，继续查代码，而不是用合理推测补齐。

### Step D：选择 Archify 类型

- `architecture`：稳定组件、服务、存储、系统边界；
- `workflow`：存在分支、gate、审批、失败、恢复的过程；
- `sequence`：调用顺序、请求/响应、同步/异步时序；
- `dataflow`：数据、配置、快照、文档、向量、证据如何移动；
- `lifecycle`：状态、事件、重试、等待、终态。

### Step E：生成 Typed JSON

先形成最小可读主路径。

不要为了“详细”把所有内部类都画成节点。细节优先放：

- label；
- card；
- supporting note；
- 独立子图。

### Step F：验证

对候选 JSON 执行 Archify 当前版本要求的 `validate`。

只有完整通过后才 `deliver`。

不得把：

- schema pass；
- renderer 能运行；
- HTML 能打开；

描述为“架构事实已验证”。

### Step G：视觉检查

环境允许时执行 `visual-check`。

另外人工检查：

- 主路径是否一眼可见；
- label 是否遮挡；
- failure path 是否喧宾夺主；
- 图是否需要进一步拆分；
- 是否为了视觉整洁删除了重要语义。

### Step H：更新 Atlas

只有在：

- JSON 已保存；
- validate 通过；
- deliver 成功；
- Atlas 证据索引已补齐；

后，才算该图完成。

---

# 4. 图 01：System Context

## 4.1 目标

回答：

> AgentFlow Hub 在整个运行环境中与哪些用户、前端、后端、存储和外部服务交互？

## 4.2 类型

`architecture`

## 4.3 预期核心节点

根据施工时实际代码确认后，从以下候选中选择约 8–12 个：

- User / Browser
- Vue Frontend
- Spring Boot Backend
- PostgreSQL
- Qdrant
- document storage
- Chat / LLM provider
- embedding provider
- Redis（必须注明实际用途；当前代码若未用于 task queue，不得画成 queue）
- built-in tool data source / application DB

## 4.4 必查证据

- `README.md`
- `frontend/`
- `backend/src/main/resources/application*.yml`
- `compose.yml`
- provider / gateway 配置
- vector store 配置
- document storage 实现
- health / actuator 配置

## 4.5 必须表达

- Browser 到 frontend/backend 的边界；
- Backend 到 PostgreSQL/Qdrant/model/embedding 的 network boundary；
- 哪些依赖是 runtime required，哪些只是已配置基础设施；
- 用户身份与 owner-scoped 数据的信任边界。

## 4.6 禁止

- 不得把 Redis 自动画成任务队列；
- 不得把所有外部 AI provider 合并成“AI”而丢失 chat / embedding 区别；
- 不得在此图加入 task 内部线程池细节。

## 4.7 完成标准

非项目成员能从本图在 1 分钟内说清：

- 系统入口；
- 核心后端；
- 主要持久化；
- AI / vector 外部依赖；
- 最大的系统边界。

---

# 5. 图 02：Backend Runtime Architecture

## 5.1 目标

回答：

> Spring Boot 后端内部的主要运行时模块如何分工和连接？

## 5.2 类型

`architecture`

## 5.3 重点模块候选

按代码确认后聚合为约 8–12 个职责节点：

- API / Controller
- Auth / owner scope
- Agent configuration
- Task application service
- Snapshot/config resolution
- Dispatch subsystem
- Engine/runtime
- Retrieval/RAG
- Tool runtime
- Provider gateway
- Persistence / repositories
- Trace/event/SSE

## 5.4 必查证据

- `backend/src/main/java/com/agentflow/**`
- 后端包结构说明
- Controller → Service 依赖
- Dispatcher configuration
- Engine interfaces/implementations
- snapshot package
- trace / SSE package

## 5.5 重点

此图画“模块职责”，不画一次任务的完整时间顺序。

边上可注明：

- sync call；
- async dispatch；
- DB persistence；
- external gateway。

## 5.6 禁止

- 不要把每个 Java package 都变成节点；
- 不要用 Controller/Service/Repository 三层模板代替真实模块分析；
- 不要在本图解释所有 failure branch。

---

# 6. 图 03：End-to-End Task Workflow

## 6.1 目标

回答：

> 用户提交一个 Agent task 后，从创建到最终答案，完整 happy path 如何推进，关键 gate 在哪里？

## 6.2 类型

`workflow`

## 6.3 建议泳道

- Browser / Frontend
- API/Application Service
- Persistence
- Dispatcher
- Agent Runtime
- Retrieval / Tools
- External Providers
- Observation / Trace

## 6.4 必查链路

必须从真实代码追踪：

```text
submit
→ authentication / owner check
→ idempotency check
→ config/snapshot resolution
→ task persistence
→ commit
→ after-commit dispatch
→ claim
→ execution
→ decision
→ retrieval/tool path
→ final generation
→ settlement
→ trace/event persistence
→ frontend terminal observation
```

## 6.5 关键要求

明确区分：

- transaction 内；
- commit 后；
- async executor 内；
- external network call；
- final settlement。

## 6.6 禁止

- 不得把 “task row created” 画成“execution already started”；
- 不得把 browser SSE 当 execution driver。

---

# 7. 图 04：Task Creation and Dispatch Sequence

## 7.1 目标

回答：

> 一次 task create request 在后端内部严格按什么顺序处理，并在什么时点跨越 commit 和 async dispatch？

## 7.2 类型

`sequence`

## 7.3 参与者候选

施工时按真实类名替换：

- Frontend/API client
- Task Controller
- AgentTaskApplicationService
- Idempotency lookup
- Config Version Resolver
- Snapshot Resolver
- Transaction / Repository
- AfterCommitTaskDispatchCoordinator
- AgentTaskDispatcher
- Task Executor

## 7.4 必须查清

- 已有 clientRequestId 时何时回读；
- configVersionId 显式与省略路径；
- snapshot 什么时候解析；
- task/event 什么时候写 DB；
- after-commit callback 什么时候注册/触发；
- dispatch rejection 怎么处理；
- claim 在哪里发生。

## 7.5 关键边界

图中必须显式标：

```text
DB transaction boundary
COMMIT
async boundary
```

---

# 8. 图 05：Agent Execution Loop

## 8.1 目标

回答：

> task 已进入 runtime 后，Agent 如何进行 decision、retrieval/tool 调用以及 final generation？

## 8.2 类型

`workflow`

## 8.3 必查内容

- engine 入口；
- decision prompt / model call；
- structured decision parsing；
- tool selection；
- retrieval selection；
- tool/retrieval result 如何进入后续模型上下文；
- budget / step limit；
- final generation；
- trace event。

## 8.4 重点

happy path 应明显占主视觉。

side branch 只保留真实存在的：

- malformed decision；
- no tool / no retrieval；
- provider failure；
- budget exhausted；
- cancel/timeout observation。

## 8.5 禁止

不要根据“Agent”概念凭空加入：

- planner；
- reflection；
- memory；
- self-correction；
- autonomous sub-agent。

除非代码确实存在。

---

# 9. 图 06：RAG Data Flow

## 9.1 目标

回答：

> 文档从上传到被一次 Agent task 检索并进入模型上下文，数据经历什么路径？

## 9.2 类型

`dataflow`

## 9.3 两条主阶段

### Ingestion

```text
Upload
→ storage
→ parse
→ chunk/document generation
→ embedding
→ vector store
→ readiness
```

### Retrieval

```text
Task snapshot
→ query construction
→ embedding/search
→ Qdrant
→ retrieved chunks
→ context assembly
→ model
```

## 9.4 必须区分

- 原始文件；
- parsed content；
- generation/version；
- embedding；
- vector reference；
- retrieval result；
- model context；
- citation/trace。

## 9.5 重点

READY 的真实判定条件必须依据代码，不得用“upload success”替代 retrieval readiness。

---

# 10. 图 07：Tool Invocation Sequence

## 10.1 目标

回答：

> 模型决定调用一个工具后，工具定义、参数校验、执行、结果和后续模型调用如何串起来？

## 10.2 类型

`sequence`

## 10.3 建议参与者

- Agent Runtime
- Model Provider
- Decision Parser
- Tool Registry/Binding
- Tool Executor
- concrete tool (`order_query`, `payment_log_query` 等)
- persistence/trace
- Final Generation

## 10.4 必查

- tool schema 从哪里来；
- tool binding 如何进入 snapshot；
- execution 前做什么校验；
- 结果是结构化还是文本；
- error 如何表示；
- 结果是否持久化到 trace；
- subsequent decision 和 final answer 的真实关系。

## 10.5 禁止

不得把 tool call 画成模型直接访问数据库，除非代码事实确实如此。

---

# 11. 图 08：Task Lifecycle

## 11.1 目标

回答：

> Task 的持久化状态机究竟是什么，哪些事件允许哪些状态转换？

## 11.2 类型

`lifecycle`

## 11.3 证据来源

必须直接查：

- task status enum；
- transition service；
- claim/update SQL；
- cancel service；
- settlement；
- recovery；
- tests。

## 11.4 必须表达

每条 transition 尽可能注明：

```text
trigger
guard
writer
persistence effect
failure behavior
```

## 11.5 特别要求

不要只画 happy path。

至少核对：

- queued；
- running；
- success；
- failed；
- cancelled；
- recovery settlement 形成的终态；
- 若存在其他状态则按真实 enum 加入。

## 11.6 禁止

- 不得从产品语义猜状态；
- 不得把 runtime Future 状态混入 task DB status。

---

# 12. 图 09：Dispatch, Thread Pool and Concurrency Control

## 12.1 目标

回答：

> 一个已提交 task 如何进入本地执行线程，线程池、队列、claim 和 external-call concurrency limit 如何共同形成背压？

## 12.2 类型

`workflow`

## 12.3 必查证据

- `AgentTaskDispatcherConfiguration`
- dispatcher properties
- executor submit path
- queue capacity
- rejection policy
- task claim
- external-call permit / semaphore 等实现
- V0.2-B tests / acceptance

## 12.4 重点

必须区分至少三层容量：

```text
Task executor threads
Task executor queue
Actual outstanding external calls
```

如果配置值在施工时发生变化，以当前配置为准。

## 12.5 必须表达

- core/max pool 行为；
- bounded queue；
- rejection；
- claim 与 execute；
- permit acquire/release；
- timeout/cancel 后 provider call 若仍未退出时 permit 是否继续占用。

## 12.6 禁止

不得把“Future cancelled”画成“provider request stopped”。

---

# 13. 图 10：Failure, Cancellation and Settlement Lifecycle

## 13.1 目标

回答：

> task 执行期间发生 timeout、cancel、provider failure 或终态持久化失败时，系统分别如何收敛？

## 13.2 类型

优先 `lifecycle`；若单图过密，可拆为：

- execution interruption lifecycle；
- settlement persistence workflow。

## 13.3 必查

- cancellation token / interruption mechanism；
- timeout handling；
- Future cancel；
- provider call wrapper；
- settlement service；
- transient DB error classification；
- retry count/backoff；
- unknown COMMIT read-back；
- admission shutdown / health degradation。

## 13.4 必须保持的语义

明确区分：

```text
logical task cancellation
local Java execution cancellation
real external call termination
terminal persistence success
```

## 13.5 禁止

不得写：

> timeout 后远端调用一定停止。

除非当前 provider adapter 能提供并证明远端取消。

---

# 14. 图 11：Restart Recovery and Controlled Cutover

## 14.1 目标

回答：

> JVM 启动、进程锁、任务准入、遗留 QUEUED/RUNNING 检测和恢复收尾是如何工作的？

## 14.2 类型

`workflow`

必要时配套一个小型 `lifecycle` 子图。

## 14.3 必查

- startup recovery coordinator；
- lock path / file lock；
- recovery mode；
- execution gate；
- stale candidate query；
- atomic settlement；
- health exposure；
- cold-cutover script；
- restart acceptance。

## 14.4 主路径建议

```text
JVM start
→ acquire process lock
→ recovery mode check
→ inspect stale tasks
→ controlled settlement / diagnostic-only behavior
→ open execution gate
→ normal task admission
```

## 14.5 必须突出

V0.2 recovery 的语义是：

> 对中断后的遗留任务进行受控识别和终态收敛。

不得画成：

```text
restart
→ automatically resume previous model/tool request
```

除非未来代码真的实现。

## 14.6 额外边界

- single-host assumption；
- lock 不是跨主机 fencing；
- old JVM cutover 前提；
- process lifetime vs Spring context lifetime。

---

# 15. 图 12：SSE, Trace and Browser Observation

## 15.1 目标

回答：

> task execution 与浏览器如何观察 task，是怎样解耦的？断线、重连和刷新到底恢复了什么？

## 15.2 类型

`sequence`

## 15.3 建议参与者

- Browser
- Vue Task View
- REST API
- SSE Endpoint
- Task/Event Persistence
- Runtime
- Trace Query

## 15.4 必须覆盖

- task 已在后台运行时 browser disconnected；
- SSE event 产生/读取机制；
- reconnect；
- refresh；
- task detail query；
- trace query；
- terminal state observation。

## 15.5 核心语义

本图必须让读者清楚：

```text
SSE disconnect != task cancellation
SSE reconnect != task restart
browser refresh != task re-execution
```

如果实现中存在特殊例外，按代码说明。

---

# 16. 图 13：Config Version and Execution Snapshot Data Flow

## 16.1 目标

回答：

> Mutable Agent Draft、Immutable Config Version 和实际 Task Execution Snapshot 如何形成，并为什么不能混为一谈？

## 16.2 类型

`dataflow`

## 16.3 必须区分三个核心对象

### Mutable Agent Draft

用户持续编辑的当前配置。

### Immutable Config Version

冻结一次配置选择，包含 `configHash`。

### Actual Task Execution Snapshot

task 创建时解析后的实际执行事实，进一步形成 `effectiveConfigHash` 或等价实际身份。

## 16.4 必查

- config version API；
- DB migration；
- canonical JSON/hash；
- explicit configVersionId；
- omitted/null configVersionId；
- task creation path；
- snapshot resolver；
- task projection / trace projection；
- historical nullable behavior；
- owner scope。

## 16.5 必须表达

```text
same configVersion
!= necessarily same effective runtime

same configHash
!= deterministic model output
```

因为：

- deployment defaults；
- document generation；
- tool implementation/schema；
- provider/runtime rule；

可能影响实际执行。

## 16.6 幂等性交互

本图或 supporting card 中必须明确：

- existing request 的回读顺序；
- explicit vs omitted configVersionId 的 request identity；
- config content dedupe != task idempotency。

---

# 17. 图 14：Evaluation Workflow

## 17.1 目标

回答：

> V0.3 evaluation 如何把固定配置、实际 task、运行清单、材料和证据关联起来？

## 17.2 类型

优先 `workflow`。

若 B 阶段施工后数据对象较多，可附加 `dataflow` 子图。

## 17.3 施工前必须确认当前实现范围

V0.3 可能处于部分完成状态。

因此必须先明确：

```text
implemented
accepted locally
real-service validated
planned only
```

只画已实现路径为实线主流程；规划内容不得伪装成现状。

## 17.4 必查

- V0.3 slice doc；
- config version implementation；
- evaluation CLI；
- run manifest；
- case submission；
- persisted evidence files；
- scoring/report；
- comparison guard；
- episode export（若已实现）。

## 17.5 三个身份必须分开

```text
Config identity
Task execution identity
Evaluation run/evidence identity
```

## 17.6 禁止

- 不得把结构完整的 report 等价为模型回答正确；
- 不得把未施工的 Evaluation Web UI / DB 平台画成已存在；
- 不得把“质量提升”作为 V0.3 功能默认结论。

---

# 18. 图 15：Persistence Architecture

## 18.1 目标

回答：

> 哪些核心领域对象由 PostgreSQL / vector store / file storage 持久化，它们之间的身份关系是什么？

## 18.2 类型

`architecture`

这是逻辑持久化架构图，不是完整 ERD。

## 18.3 建议逻辑对象

根据 migration 实际确认：

- User / Owner
- Agent
- Agent Config Version
- Knowledge Base / Document Generation
- Agent Task
- Execution Snapshot
- Task Event / Trace
- Tool binding / knowledge binding
- Evaluation artifact（若 DB 中实际存在）
- Qdrant vector data
- file/document storage

## 18.4 必查

- `backend/src/main/resources/db/migration/`
- entity/model；
- repository；
- foreign key；
- unique constraint；
- owner-scoped constraint；
- historical nullable fields。

## 18.5 重点

强调逻辑 identity 和 immutable/mutable 边界，而不是把每个数据库列都画出来。

对于 hash / snapshot / version，要显示谁引用谁，而不是只写“存在这些表”。

---

# 19. Atlas 汇总文档要求

`docs/architecture/ARCHITECTURE_ATLAS.md` 不应只是链接列表。

至少包含以下章节。

## 19.1 Scope and baseline

记录：

- 生成日期；
- git HEAD；
- Archify version；
- 是否基于 dirty working tree；
- 哪些外部 runtime 没有实际运行验证。

## 19.2 Diagram index

建议表格：

| ID | Diagram | Type | Question | Status |
|---|---|---|---|---|

## 19.3 Evidence convention

定义：

- CODE_CONFIRMED
- TEST_CONFIRMED
- DOC_DECLARED
- UNKNOWN

## 19.4 Cross-diagram invariants

至少检查：

- Task status 在各图中一致；
- Config Version / Snapshot 关系一致；
- async boundary 一致；
- provider cancellation 语义一致；
- SSE observation 语义一致；
- restart recovery 语义一致。

## 19.5 Known unknowns

无法从静态仓库证明的内容必须保留，而不是删掉。

例如：

- 真实 provider 是否在 socket timeout 后继续计算；
- 某些 runtime 环境特有行为；
- 生产部署实际拓扑；
- 未执行的 real-service acceptance。

---

# 20. 最终 Architecture Audit

全部图完成后，再执行一次独立的反向审计。

输出：

```text
docs/architecture/ARCHITECTURE_AUDIT.md
```

## 20.1 审计目标

不是检查 Archify 是否渲染成功，而是检查：

> 图中的系统语义是否真的被仓库证据支持。

## 20.2 逐图检查

对每个 node：

- 是否有真实代码/存储/配置/外部系统证据？

对每条 edge：

- 谁调用谁？
- 什么机制？
- sync/async？
- 是否跨 transaction？
- 是否跨 thread？
- 是否跨 process？
- 是否跨 network？

对每个 lifecycle transition：

- writer 在哪里？
- guard 在哪里？
- persistence operation 是什么？
- failure path 是什么？

## 20.3 必须重点反查的误区

逐项确认图集中没有暗示：

- timeout 一定停止 provider request；
- Future cancel 一定终止外部调用；
- SSE reconnect 会重新执行 task；
- browser refresh 会重新执行 task；
- restart recovery 会 automatic resume；
- same config version = same effective runtime；
- same config hash = same model output；
- settlement timestamp = crash timestamp；
- mock/local acceptance = real external service validation。

## 20.4 跨图一致性

搜索以下冲突：

- Architecture：A → B，但 Sequence：A → C → B；
- Lifecycle 允许某状态转换，但 Service 没有该路径；
- Workflow 表示同步，但代码实际 after-commit async；
- Data Flow 表示配置冻结，但 runtime 实际继续读取 mutable draft；
- Persistence 图中 identity 关系和 migration 不一致。

发现错误后：

1. 修正 Typed JSON；
2. 重新 validate；
3. 重新 deliver；
4. 更新 Atlas；
5. 在 Audit 中记录修正原因。

## 20.5 问题级别

使用：

- `CRITICAL`：错误表达核心运行时语义；
- `MAJOR`：缺失重要边界或关键分支；
- `MINOR`：不影响主要理解的精度问题；
- `UNPROVEN`：当前静态证据不足。

---

# 21. Codex 每次开始一张图时使用的统一指令模板

建议不要每次自由发挥。对第 N 张图使用下面模板，并替换 `<...>`。

```text
按照 spec-docs/archify-architecture-atlas-guide.md 施工图 <ID>：<NAME>。

严格遵守该文档中这张图的：
- 目标
- 图类型
- 必查证据
- 边界
- 禁止项
- 完成标准

施工要求：

1. 先检查当前 git HEAD 和 working tree。
2. 重新阅读与本图有关的生产代码，不要只依赖 README 或 slice doc。
3. 在动手生成 Archify JSON 前，先在工作记录中列出：
   - CODE_CONFIRMED nodes
   - CODE_CONFIRMED edges
   - TEST_CONFIRMED facts
   - DOC_DECLARED only facts
   - UNKNOWN facts
4. 对关键调用追踪到 class/method；对状态追踪到 enum/transition/write；对持久化关系追踪到 migration/repository。
5. 若文档与代码冲突，以当前实现为图中事实，并记录冲突。
6. 不修改业务代码。
7. 只生成本图需要的 Archify source、HTML 和 Atlas 更新。
8. 图过密时拆 supporting diagram，不要硬塞节点。
9. 执行 Archify validate 和 deliver；环境允许则执行 visual-check。
10. validate/deliver 非零退出时不得宣称完成。
11. 更新 docs/architecture/ARCHITECTURE_ATLAS.md 中本图的证据、unknowns 和验证状态。
12. 完成后停下，不自动开始下一张图；由下一次指令继续。
```

---

# 22. 推荐施工顺序

不要完全按“最容易画”排序。建议按认知依赖施工：

```text
01 System Context
↓
02 Backend Runtime Architecture
↓
03 End-to-End Task Workflow
↓
04 Task Creation and Dispatch Sequence
↓
05 Agent Execution Loop
↓
08 Task Lifecycle
↓
09 Dispatch / Thread Pool / Concurrency
↓
10 Failure / Cancellation / Settlement
↓
11 Restart Recovery
↓
12 SSE / Browser Observation
↓
06 RAG Data Flow
↓
07 Tool Invocation
↓
13 Config Version / Snapshot
↓
14 Evaluation
↓
15 Persistence Architecture
↓
Architecture Audit
```

理由：

- 01/02 先建立稳定边界；
- 03/04/05 建主执行模型；
- 08/09/10/11 再深入并发、中断和恢复；
- 12 单独澄清观察语义；
- 06/07 分解 Agent 的两类核心能力；
- 13/14 在已有 task/snapshot 认知上分析 V0.3；
- 15 最后汇总 persistence identity，能更容易发现前图建模不一致。

---

# 23. 施工中的额外注意事项

## 23.1 不要让图代替代码

Atlas 是理解入口，不是 authoritative runtime specification。

代码、migration 和版本契约仍是事实来源。

## 23.2 不要为了“详细”牺牲可读性

详细度来自：

- 多张图；
- 精确证据；
- 正确边界；
- 可追踪 relation；

不是来自节点数量。

## 23.3 文档中的未来能力必须显式区分

尤其 V0.2 / V0.3：

- 已实现；
- mock/受控验收；
- real-service 验收；
- 后续规划；

必须分别记录。

## 23.4 不要把 validation 当语义证明

Archify `validate` / `deliver` 证明的是 artifact/schema/layout 等合同满足要求。

它不能证明：

> Codex 对仓库关系的理解一定正确。

这正是最终 Architecture Audit 必须存在的原因。

## 23.5 尽量保留稳定 ID

已有图更新时，不要无理由重命名节点 ID。

这样后续做 architecture delta / compare 时更容易看清真实变化，而不是被 ID churn 干扰。

## 23.6 变更后更新策略

以后发生重大架构施工时：

1. 先更新真实代码；
2. 找出受影响 diagram；
3. 重新检查证据；
4. 修改对应 Typed JSON；
5. validate/deliver；
6. 必要时使用 Archify architecture delta；
7. 更新 Atlas baseline 和 Audit。

不要每次无差别重画全部 15 张图。

---

# 24. Definition of Done

整个 Archify Architecture Atlas 只有满足以下条件才算完成：

- [ ] 15 个主题均已施工，或明确说明为什么某主题当前不适用；
- [ ] 每张图都有明确单一问题；
- [ ] 每张图关键关系均有仓库证据；
- [ ] README / spec / slice doc 声明与生产代码已区分；
- [ ] task、snapshot、config、provider、SSE、recovery 的关键语义在不同图之间一致；
- [ ] 每张 JSON 均通过当前 Archify validate；
- [ ] 每张 HTML 均由成功 deliver 产生；
- [ ] 可运行环境中的图已完成 visual-check 或记录环境原因；
- [ ] `ARCHITECTURE_ATLAS.md` 完整；
- [ ] `ARCHITECTURE_AUDIT.md` 完整；
- [ ] 所有 CRITICAL / MAJOR audit 问题已修正或显式保留；
- [ ] 未修改任何仅为“配合画图”而改变业务语义的生产代码。

完成后，这套图集应允许一个第一次接触 AgentFlow Hub 的工程师按以下顺序理解项目：

```text
系统边界
→ 后端模块
→ 一次任务怎么跑
→ 状态如何变化
→ 并发和失败怎么收敛
→ 浏览器如何观察
→ RAG / Tool 如何参与
→ 配置和快照如何冻结
→ Evaluation 如何证明比较条件
→ 数据最终落在哪里
```

这才是本图集的最终目标。