# AgentFlow Hub 实施路线图

> 文档状态：**NORMATIVE**  
> 权威范围：V0.1 历史施工与验收、V0.2/V0.3/V1.x 的施工顺序、依赖和验收门槛\
> 最近审查基线：`v0.1@1d062df` 及其后的 V49 工作区改动（2026-09-10）；第 1 节保留历史施工起点。
> 版本规划与目录对齐：2026-09-12，基于 `main@58b6145`；不改变既有发布验收结论。

---

## 1. 当前判断

2026-09-10：已创建并推送 annotated tag `v0.1`，固定于验收材料提交 `1d062df`，见[发布说明](../release-docs/V0.1_RELEASE_NOTES.md)。
随后完成 [V49 知识库绑定数量边界统一](../V0.1-slice-docs/50_KNOWLEDGE_BINDING_LIMIT_PACKAGE_INTERFACE.md)：写入、snapshot 和执行上限统一为 20，历史 21–50 项仍可完整读取并明确删减修复。
后端重点 64/64、PostgreSQL 22/22、前端 81/81 与构建、浏览器 20/20 通过；一个新 GLM-5.2 + DashScope + Qdrant 任务通过存储 23/23、独立 final、有效引用及刷新/Trace 收敛，零自动重试。
这是 tag 之后的数量契约修复，不代表多知识库检索质量、容量或统计可靠性；未开始任务恢复/重试能力。

2026-09-09 总验收：按第 10 节逐项核对，**V0.1 Release Gate 13/13 通过**。
本轮包括新的 GLM-5.2 + DashScope + Qdrant 真实任务、V48 22 例失败/恢复矩阵、
前端 76 项与 19 例浏览器回归、后端 185 项重点测试及 47 项 PostgreSQL 检查。
PostgreSQL 首轮取消用量断言失败原样保留，仅修正测试时序后复验通过；生产代码未改变。
范围、已知非阻断问题和结构化证据见[总验收报告](../release-docs/V0.1_RELEASE_GATE.md)。验收材料随后保存于 `1d062df`，tag 发布情况见上方 2026-09-10 更新。
以下日期段落为历史交付记录。

2026-09-07 更新：V37–V42 已完成绑定快照、Task/Runner、任务 Trace、快照驱动执行、Task REST 与可恢复 SSE。
V43/M4G-A、V45/M4G-B2、V46/M4G-C 已完成最小任务、知识库管理和 Agent 配置前端及各自受控浏览器验收，
具体证据见第 9 节引用的契约。V47/M4G-D1 已实现真实 provider/Qdrant 成功主路径的独立验收入口，
截至 2026-09-07，V47 已取得一次智谱 GLM-5.2 + DashScope + Qdrant 正常应用成功主路径：
第九次入口创建 task `2096972424179240962`，浏览器 PASSED、存储23/23通过，两工具、独立final、有效引用和刷新/GET/Trace一致。
此前七个真实任务FAILED与第八次PostgreSQL启动失败分别保留；原生probe、有限单步回放、本地/受控测试仍与真实E2E分开。
最新提示适配重点Java46/46、默认受控19/19（44.6秒）通过。本结论仅覆盖固定模型/演示材料的一次成功路径，不代表统计可靠性或V0.1发布验收。
详情以V47契约为准。以下清单记录V36施工起点，不作为当前缺失能力清单。

截至 V36，项目已经完成大量基础能力，但还没有完成 V0.1 Agent 闭环。

已完成：

```text
M0 工程骨架
M1 用户/JWT/common
M2 Knowledge 上传、解析、分块、向量化和检索
M2+ 文档删除/重处理补偿
M3 tool_definition、ToolRuntime、三个 builtin handlers
M4 前半 AgentApp CRUD、LlmGateway、同步 AgentEngine core loop
```

尚未完成：

```text
Agent bindings
AgentTask root
TaskRunner
前置 RAG 与 AgentEngine 集成
Task-scoped Trace
Task API
持久事件
可恢复 SSE
最小执行 UI
真实端到端验收
```

因此从 V36 开始，施工策略改为：

> 冻结外围功能，只完成一条 `task -> RAG -> decision -> tools -> final answer -> trace -> SSE -> UI` 纵向链路。

---

## 2. 施工原则

### 2.1 不回滚已有正确实现

继续保留：

- V1–V16 migration；
- owner-scoped 复合外键；
- 文档删除/重处理 generation fence；
- 当前确定性 DocumentChunker；
- EmbeddingGateway/VectorStoreGateway；
- seeded ToolDefinition 和 ToolRuntime；
- AgentApp CRUD；
- V35 `LlmGateway.chat`；
- V36 同步 AgentEngine 内核。

### 2.2 不继续横向铺功能

V0.1 完成前暂停：

- 新工具；
- PDF；
- MinIO；
- RabbitMQ；
- Redis task state；
- semantic chunking；
- rerank/Hybrid Search；
- PolicyGuard；
- Episode；
- Evaluation；
- Prompt versions；
- conversation；
- Completed 文档重处理的更多产品能力；
- 管理后台扩展；
- HTTP/MCP。

### 2.3 每个切片必须向闭环推进

一个切片只有在满足以下至少一项时才进入当前主线：

- 建立 task 根对象；
- 把 RAG/LLM/tool 关联到 task；
- 提高任务可靠性；
- 提供 task API/SSE；
- 提供 V0.1 页面；
- 增加真实端到端证据。

仅增加新的 CRUD、报表、工具或设计预留不算主线进展。

---

## 3. M4A：规范收敛

### 目标

在继续 migration 和 task 代码前冻结：

- TaskStatus/TaskPhase；
- TaskRunner/AgentEngine 所有权；
- execution snapshot；
- decision protocol；
- budget；
- ToolRuntime hard validation；
- RAG profile/vector identity/readiness；
- event/SSE replay；
- V0.1 范围。

### 验收

- `spec-docs/README.md` 存在；
- canonical contract 唯一；
- Project/Data/Engine/RAG/Tool/API/Frontend/Roadmap 无互斥定义；
- learning guide 明确非规范性；
- 不修改业务代码和 V1–V16 migration。

本 PR 完成 M4A。

---

## 4. M4B：执行依赖与绑定

### 目标

让一个 Agent 在数据库中拥有明确、可校验的知识库和工具能力边界。

### Schema

按依赖新增 migration：

1. `agent_app (id,user_id)` unique，并收紧新 Agent 的 `maxToolCalls < maxDecisionTurns` application validation；
2. `knowledge_chunk.chunk_strategy_version`；
3. `agent_knowledge_binding`；
4. `agent_tool_binding`。

### Backend

- owner-scoped binding service；
- knowledge binding GET/PUT；
- tool binding GET/PUT；
- 只允许两个 V0.1 BUILTIN 工具；
- Agent snapshot resolver；
- READY document generation resolver；
- schema hash 和 implementation version resolver；
- 固定 Chat/Embedding profile 解析。

### Seed/验收数据

支付 Agent 固定绑定：

```text
1 个支付知识库
order_query
payment_log_query
```

`report_generate` 不绑定。

### 验收门槛

- 不能绑定跨 owner 或 DISABLED KB；
- 不能绑定 disabled/deleted/unsupported tool；
- 未绑定工具不进入 snapshot；
- KB 无 READY 文档时 snapshot resolver 失败；
- 已创建的 snapshot 不受普通 binding 修改影响；
- 固定 profile code 与 RAG canonical contract 一致。

---

## 5. M4C：AgentTask 根对象、持久事件与 TaskRunner

### 目标

建立一次执行的权威根对象、从 task 创建开始存在的持久事件序列，以及可靠单实例调度。

### Schema

创建：

```text
agent_task
agent_task_event
```

字段和 CHECK 以 Data Model 为准。事件表必须与 task 同阶段建立，因为 task 创建、dispatch 失败、取消和终态从第一天就需要稳定 sequence；不能等到 SSE 页面开发时才补事件事实。

### Backend

- `AgentTaskApplicationService.createTask`；
- `Idempotency-Key`；
- versioned canonical request fingerprint；
- 同事务 execution snapshot；
- 同事务 `TASK_CREATED` event；
- `agent_task.last_event_sequence`；
- 单一 `TaskEventAppender`；
- after-commit dispatch；
- bounded `TaskDispatcher`；
- `TaskRunner`；
- `QUEUED -> RUNNING` 条件领取；
- `TASK_STARTED` 和 phase events；
- phase/event 同事务；
- cancel_requested_at；
- terminal conditional update；
- terminal state/event 同事务；
- dispatch rejection 失败落库。

### 暂时允许

本阶段 AgentEngine 可以先用 mock/脚本化 outcome，目的是先验证 lifecycle、event ordering 和调度，不急于同时接 RAG/完整 Trace。

### 验收门槛

- 相同 idempotency key + 相同 payload 返回同一 task；
- 相同 key + 不同 payload 409；
- 两个 runner 只有一个领取成功；
- thread pool rejection 不留下永久 QUEUED；
- cancel/complete 竞态只有一个终态；
- status/phase/terminationReason CHECK 全部通过；
- 每个 task 从 `TASK_CREATED` 开始拥有严格递增 sequence；
- 禁止 `max(sequence)+1`；
- terminal task 和 terminal event 同事务可见。

---

## 6. M4D：Task-scoped Trace 基础

### 目标

在 AgentEngine 正式接入 task 前，先建立它所依赖的 step 和专项日志结构，使 ToolRuntime/LLM/RAG 从第一次 task 执行开始就能写入合法关联，而不是先产生无 step 的临时日志再补迁移。

### Schema

创建：

```text
agent_step
llm_call_log
rag_retrieval_log
rag_retrieval_hit
```

并为现有 `tool_call_log` 增加：

- `(step_id, task_id) -> agent_step(id, task_id)` 复合 FK；
- standalone `(NULL,NULL)` / AgentTask `(NOT NULL,NOT NULL)` CHECK；
- task 查询索引。

### Backend

- `ExecutionRecorder` 接口与持久实现；
- step start/success/failure lifecycle；
- LLM DECISION/FINAL_GENERATION log writer；
- RAG retrieval/hit snapshot writer；
- ToolRuntime task command 和 task/step log writer；
- Prompt/response/tool 数据脱敏与大小限制；
- 禁止保存自由 chain-of-thought；
- Trace 聚合 query service 骨架。

### 暂时允许

使用脚本化 Engine/recording fixture 验证 recorder 和数据库不变量；本阶段不要求真实 provider 或完整支付诊断。

### 验收门槛

- 所有专项日志的 step 属于同一 task；
- standalone tool test 仍可使用 `(NULL,NULL)`；
- stepIndex 唯一；
- source document/tool 后续删除不破坏 snapshot；
- 敏感字段不进入数据库/API；
- 失败中断后已经提交的 Trace 保留。

---

## 7. M4E：前置 RAG 与 V36 Engine 正式接入

### 目标

把当前独立 Knowledge Retrieval 和 V36 同步循环接入 TaskRunner、ExecutionRecorder、ToolRuntime task context 和持久事件，形成完整后端执行链。

### Retrieval

- `RetrievalService` 接收 corpus snapshot；
- 按 documentId + vectorGeneration 过滤；
- PostgreSQL 二次验证；
- bounded evidence；
- citation ID；
- RAG 空结果合法；
- snapshot evidence 进入 Decision/Final Prompt；
- knowledge 内容标记为 untrusted data；
- disabled/deleted document 不回退到新 generation。

### Engine 调整

- V36 `TOOL_CALL/FINAL_ANSWER` 收敛为 canonical `CALL_TOOL/FINISH`；
- `FINISH` 只触发 Final Generation；
- `max_steps` 语义重命名/映射为 maxDecisionTurns；
- provider usage unknown 使用保守 estimator，不按 0，也不无条件使成功任务失败；
- 为 Final Generation 预留 token；
- maxToolCalls/decision limit 触发受限最终生成；
- ToolRuntime 接收已经存在的 task/step/snapshot context；
- 当前 Agent binding 不在运行中重新查询；
- 全局 tool disable/soft delete 作为紧急撤销；
- 重复调用检测；
- 通过已有 ExecutionRecorder 写 step/LLM/RAG/tool logs；
- 通过已有 TaskEventAppender 写 RAG/decision/tool/final-generation 语义事件；
- V0.1 Final Generation 保持同步非流式，可只发一个完整 ANSWER_CHUNK。

### 验收门槛

固定脚本模型必须产生：

```text
1 PRE_RETRIEVAL step
>= 1 LLM_DECISION step
2 TOOL_CALL steps
1 LLM_FINAL_GENERATION step
对应专项日志和事件
```

并证明：

- 无 RAG hit 仍可工具执行；
- 未绑定工具在 snapshot/Engine 边界被拒绝；
- 普通 Agent/binding 修改不改变运行中 snapshot；
- 平台级资源撤销安全生效；
- budget 精确收敛；
- 执行事件、step 和 task phase 顺序一致；
- source 资源后续修改后历史 Trace 仍可解释。

---

## 8. M4F：Task API 与可恢复 SSE

实施拆分：V41/M4F-A 交付 Task REST API 与公开 Trace（契约
`V0.1-slice-docs/42_AGENT_TASK_API_PACKAGE_INTERFACE.md`）；V42/M4F-B 交付可恢复 SSE（契约
`V0.1-slice-docs/43_AGENT_TASK_SSE_PACKAGE_INTERFACE.md`），复用并扩展 V41 安全事件投影与游标读取。
2026-09-06 两片均已完成验收，M4F 完成：真实 HTTP SSE/JWT、Runner、Engine、ToolRuntime 与 PostgreSQL，
全量 691/691 通过；模型和向量仍使用可控替身，不构成真实 provider/Qdrant E2E。

### API

```text
POST /api/v1/agents/{agentId}/tasks
GET  /api/v1/tasks
GET  /api/v1/tasks/{taskId}
POST /api/v1/tasks/{taskId}/cancel
GET  /api/v1/tasks/{taskId}/events
GET  /api/v1/tasks/{taskId}/trace
```

### Event 投影收口

- 为 M4E 已记录的执行事实固定 payload schema；
- semantic events；
- `ANSWER_CHUNK` 合并；
- 同步 Final Generation 允许一个完整答案 chunk；
- event payload 脱敏；
- 不增加第二套 event 状态机。

### SSE

V0.1 使用数据库事件日志读取：

- `afterSequence`；
- `Last-Event-ID`；
- persisted replay；
- terminal 后关闭；
- 不依赖内存 emitter 保证可靠性。

### 验收门槛

- POST 返回前产生的事件不丢；
- 新客户端从 `afterSequence=0` 可读取 TASK_CREATED；
- 服务端 lastEventSequence 与客户端 lastProcessedSequence 不混用；
- SSE 重连不重不漏；
- sequence gap 可检测；
- TASK_COMPLETED 时 finalAnswer 已可 GET；
- 浏览器断线不影响 task 执行；
- event 不替代 Trace 专项日志。

---

## 9. M4G：最小前端与真实 E2E

M4G 分片推进，以下完整页面与真实 E2E 仍是整个 M4G 的目标。

### M4G-A / V43：最小任务运行前端与恢复闭环

冻结契约：`V0.1-slice-docs/44_FRONTEND_TASK_RUNTIME_PACKAGE_INTERFACE.md`。
2026-09-06 已实现并通过本片验收：前端边界测试 23/23、构建通过，独立 PostgreSQL/真实浏览器 3/3。
采用 Vue 3 / TypeScript / Vite，只实现：

- 路由、API 客户端、JWT 登录、退出和认证失效状态清理；
- 选择已有 Agent、自己的任务列表、提交输入，结果未知时复用原 Idempotency-Key；
- Task 状态、阶段、时间线、答案、引用和取消，区分服务端与连接状态；
- Bearer SSE、无损 ID/游标、严格 sequence 去重、成功处理后推进游标、有界重连、gap 停止与离页释放；
- 刷新时用已有公开 Trace events 重建，继续订阅，终态以 GET task 的答案和引用收敛；
- 只读聚合 Trace（steps、RAG、LLM、工具与事件）。

核心验收使用真实浏览器、JWT、后端 Runner/Engine/ToolRuntime 和 PostgreSQL，
模型与向量继续可控：登录 → 预配置 Agent 创建 → 持久事件 → 断网重连与刷新 → 终态 →
答案、引用与 Trace 一致。验收状态与复现证据以 V43 契约为准。

本片不实现知识库上传管理、Agent 配置编辑、真实 provider/Qdrant E2E、新增后端公开接口、
provider streaming、多轮对话或任务执行重试。V43 完成只计 M4G-A，不等于整个 M4G 或 V0.1 Release Gate。

### M4G-B1 / V44 与 M4G-B2 / V45：知识库就绪读模型与最小管理前端

V44 契约：`V0.1-slice-docs/45_KNOWLEDGE_READINESS_PACKAGE_INTERFACE.md`，补充只读 profile/strategy、
当前 generation 四项计数和五种 Readiness。V45 契约：`V0.1-slice-docs/46_KNOWLEDGE_FRONTEND_PACKAGE_INTERFACE.md`，
消费 V44 读模型，实现知识库分页列表/详情/创建、TXT/MD 单文件上传、显式解析/向量化、文档分页状态。
上传只产生 PENDING，GET 轮询不自动入库；超时结果待确认，创建和上传不自动重发。
验收区分真实浏览器/JWT/PostgreSQL/文件解析与受控 embedding/vector，并保留 V43 回归，实际证据见对应契约。
Agent 配置、检索调试、编辑/删除/重处理、全库筛选统计、自动调度及真实 provider/Qdrant E2E 未纳入 V45。

### M4G-C / V46：最小 Agent 配置前端

契约：`V0.1-slice-docs/47_AGENT_FRONTEND_PACKAGE_INTERFACE.md`。增加 Agent 分页列表/详情/创建、配置编辑、
启停与既有知识库/工具绑定 GET/PUT；从详情进入已有任务运行页。配置与两类绑定分别保存，
保留草稿与跨页/失效绑定 ID，隔离晚到响应；未知写入先读回核对，不自动重发。
provider 固定 `openai-compatible`，工具仅 `order_query`、`payment_log_query`，使用公开预算字段。
真实浏览器/JWT/PostgreSQL 下验证页面创建 Agent 至答案/Trace，模型/向量受控，保留 V43/V45 回归。
运行证据见契约；不含 Agent 删除、Prompt 版本、新后端接口/迁移或执行引擎改造。

### M4G-D1 / V47：真实 provider + Qdrant 主路径 E2E

契约：`V0.1-slice-docs/48_REAL_PROVIDER_E2E_PACKAGE_INTERFACE.md`。正常启动主应用并使用现有
OpenAI-compatible Chat、DashScope embedding 和实际 Qdrant，不加载 V43/V45/V46 受控 Gateway。
独立入口 `scripts/v47-real-provider-acceptance.sh` 提供不生成答案的环境预检、临时 PostgreSQL、
独立 Qdrant collection、有限单任务预算和本次运行证据。

浏览器创建知识库 → 上传 TXT/MD（主路径使用固定 MD）→ 显式解析/向量化至 READY → 创建 Agent →
绑定知识库及 `order_query`、`payment_log_query` → 提交 `order_1024` 支付诊断任务。
成功必须证明实际 RAG 命中本次文档的冻结/当前 generation、两个工具均经 ToolRuntime 成功执行、
模型 FINISH 后独立最终生成成功、有效引用和刷新后的 GET/Trace 一致，不能只看绑定或 COMPLETED。
真实 builtin handler 仍查询既有 V12 演示业务表，不宣称接入真实外部支付系统。

2026-09-07 入口与断言已实现。较早预检曾因配置未就绪 BLOCKED；随后一次真实运行通过预检并完成
浏览器上传/解析/真实向量化至 READY，Qdrant/RAG 命中当前 generation，但首个 Chat 决策 **FAILED**：
`qwen/qwen3-1.7b` 返回两个相邻顶层 CALL_TOOL JSON，违反单 JSON/EOF 契约，产生 `AGENT_INVALID_DECISION`。
该次工具调用和独立最终生成为 0。后续 9B 默认超时运行成功执行 order_query，但第二轮达到 30 秒读取超时。
再以 Chat 120 秒/任务 300 秒显式运行后，三轮均返回合法但相同的 order_query 意图，
实际执行一次、复用一次，第三次触发 AGENT_DUPLICATE_TOOL_LOOP。已有 observation 和支付日志工具在请求快照中均存在；
第四次跟随用户配置切换为 `google/gemma-4-e4b`，保持 prompt/预算及 Chat 120 秒/任务 300 秒：
order_query 成功，第二轮因 512-token 输出额度耗尽、content 为空被 gateway 拒绝，产生 AGENT_LLM_FAILED；不是读取超时。
Trace 总计 5997 MIXED 与服务端两次报告 2835 tokens 分开记录，不覆写持久记账或宣称核定费用。
前四次尚无 payment_log_query、final、答案/引用及成功刷新证据，历史失败记录保留。
针对第四次的 512-token 输出限制，本轮已授权增加服务端
`agentflow.task.execution.decision-max-output-tokens`（环境 `AGENTFLOW_TASK_DECISION_MAX_OUTPUT_TOKENS`），
默认 512、当次范围 1–4096（后续智谱适配扩至 1–16384），第五/六次适配运行显式 2048。实际 cap 仍取配置、扣除 final 所需额度后的任务预算及 context 余量的最小值；
保留 24000 总预算、final reserve、Chat 120 秒/任务 300 秒及单 JSON 协议，沿用 Trace 实际 maxOutputTokens，不加 DTO/迁移。
适配已实现并通过后端 focused 测试 25/25（执行器 19、属性 6），预检检查 1–4096 并记录
`budget.decisionMaxOutputTokens`。第五次实际 cap 全部为 2048，两个工具均经 ToolRuntime 各成功一次，EXACT 4466 tokens；
第三轮 FINISH 被 Markdown json 围栏包裹，违反已有 prompt/单 JSON 契约，产生 AGENT_INVALID_DECISION。
无被接受的 FINISH、独立最终生成、答案/引用或成功刷新，第五次仍为 FAILED。该次未见 length/空正文，
但第二轮 completion 475 低于旧 cap512，不能由单次生成证明提高上限已普遍解决截断。
前五次运行各自有限预算、无自动重试或协议放宽；下述第六次由用户后续明确发起。
随后授权可选 DECISION JSON Schema 适配：`AGENTFLOW_TASK_DECISION_JSON_SCHEMA_ENABLED=true`
（服务端默认 false），按冻结工具生成原 CALL_TOOL/FINISH 对象分支并发送 strict json_schema；final 仍正文。
schema 不可变、最多 64 KiB，纳入预算估算并由现有 Trace 请求投影留证；不改 parser/prompt、不剥围栏、不自动降级或修复重试。
独立原生兼容性 probe 已 PASS：Gemma 返回裸 FINISH、4.7 秒、103 tokens、1 请求/0 重试，无 embedding/Qdrant/业务任务。
该 probe 不计为只读预检或应用 E2E；Schema 重点测试 71/71、Python 语法及 4 项离线采证格式检查已通过，
采证核对 DECISION schema 模式及 final 纯正文。第六次开启 Schema 后三次 DECISION 均合法、实际 strict schema 与冻结/持久记录一致，
但均请求 order_query：实际执行一次、缓存复用一次、第三次 AGENT_DUPLICATE_TOOL_LOOP，EXACT 3444 tokens，浏览器 19.4 秒失败。
本次未执行 payment_log_query/FINISH/final，未到成功刷新；无 final 时格式检查成立不能充当真实 final 证据。
第五次两个工具成功仍保留为该次历史证据，不能挪用至第六次；该阶段六次 FAILED，未自动开启第七次。
Schema 适配后的默认 schema 关闭/cap 512 受控回归再次 19/19 通过（47.3 秒、exit 0），与开启 schema 的真实验收分开记录。
适配后默认 512 下 V43/V45/V46 受控回归再次 19/19 通过（48.7 秒），与此前 46.5 秒记录分开保留。
本轮前端 76/76、构建和 V43/V45/V46 受控浏览器 19/19 通过；它们不能代替 V47 真实依赖验收。
后续用户授权使用 IDEA 中的 `ZHIPU_API_KEY` 并按模型调高输出上限；凭据仅映射至进程 `OPENAI_API_KEY`，不进入证据。
智谱 `glm-5.2`、`https://open.bigmodel.cn/api/paas/v4` 的独立原生 probe 已 PASS：GET/models 200 并列出模型，
生成采用 json_object、thinking disabled、max_tokens 8192，HTTP 200/stop、2.188 秒、124 input + 23 output = 147 tokens，
request ID `202609072116078ee2823883904f3b`，无围栏或 reasoning_content。证据目录
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/v47-zhipu-glm52-probe-6jhr1dnp`。
该次只证明原生 JSON object 请求的兼容性，不证明 JSON Schema 约束、应用传输、工具/final/引用或浏览器 E2E。
智谱适配明确开启 `AGENTFLOW_TASK_DECISION_JSON_OBJECT_ENABLED`、`AGENTFLOW_TASK_PROVIDER_THINKING_DISABLED`
并关闭 `AGENTFLOW_TASK_DECISION_JSON_SCHEMA_ENABLED`；三个开关默认均 false，两种 JSON 模式互斥。
DECISION 使用 json_object，final 仍正文；不放宽 parser、工具参数校验和循环保护，不自动降级、修复或重试。
decision/final 配置范围 1–16384，本次分别显式 8192；`AGENTFLOW_TASK_FINAL_MAX_OUTPUT_TOKENS` 默认不配置并沿用旧 reserve 上限。
既有 final reserve 2048、总 24000、steps 5/tools 3、Chat 120 秒/任务 300 秒不变；final 超出 reserve 的额度仅来自扣除已记账 usage
与本次输入保守估算后的任务/context 余额，不将请求前输入估算当作 provider 实际 usage。
新增采证逐字段核对 PostgreSQL 与公开 Trace 的请求选项；缺少 final 调用时 final 格式检查明确 false，旧运行证据不改写。
智谱适配 Java 9 类重点测试最终 98/98：86 项非 HTTP 首轮通过，HTTP 类首轮被 sandbox loopback 限制阻断后，
仅重跑该类 12/12 通过；日志 `/private/tmp/v47-zhipu-core-focused-tests.log`、`/private/tmp/v47-zhipu-http-focused-tests.log`。
Python 语法、10 项请求选项边界检查、3 项使用第六次证据副本的离线集成断言通过，覆盖请求选项一致性、缺 final 与篡改 cap 拒绝。
默认配置下 V43/V45/V46 受控回归 19/19 通过、45.2 秒，日志 `/private/tmp/v47-zhipu-controlled-browser.log`。
第七次独立正常应用运行预检 READY，浏览器 9.9 秒 FAILED/verify-persisted-evidence；run FAILED/exit 1/stage storage-evidence，
storage FAIL/errors=[]。task `2096953425345351681`；证据目录
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-zhipu-glm52-4bawq2af`。
GLM-5.2 的三次 DECISION 均 SUCCESS/stop、合法 JSON，应用与 PostgreSQL/公开 Trace 记录均为 cap 8192、json_object、thinking disabled、无 schema。
三次耗时 1767/1447/1959 ms，usage 合计 EXACT 3314 = 3182 input + 132 output；却均选择 order_query(order_1024)：
实际 ToolRuntime 成功一次（6 ms）、缓存复用一次、第三次 AGENT_DUPLICATE_TOOL_LOOP。后续请求已有订单结果和支付日志工具，未发现漏传。
真实 DashScope/Qdrant/RAG 命中本次 generation 0、1 valid/0 stale、score 0.66849273；17 个持久事件连续且末条 TASK_FAILED，GET 与 Trace 一致。
未执行 payment_log_query/FINISH/final，无最终答案/引用或成功刷新；已核对失败截图，临时/受控端口关闭，证据与数据库/collection 保留。
应用请求选项一致性与 HTTP stub 序列化测试分开记录，未宣称取得厂商侧 wire 日志；safe provider 摘要不含凭据或推理正文。
本次未超时、截断或格式失败，不能归因为本地小模型或据此断定关闭 thinking 导致循环，重复决策根因仍未证实。
第七次结束时尚未修改 prompt 或再跑；随后按渐进验证方案，先以独立 `scripts/v47-decision-replay.py` 检验执行历史提示。
入口默认取第七次 task `2096953425345351681` 第 2 次 DECISION 的原请求，只给 B 的第二 SYSTEM content
追加 `scripts/fixtures/v47-decision-history-instructions.txt`；两组均保持 SYSTEM/SYSTEM/USER 三条消息，其他输入/options不变。
固定 A/B、B/A、A/B 共六次 GLM-5.2 请求，temperature 0.1/top_p 0.8/json_object/thinking disabled/cap8192：
A 合法 JSON 3/3、目标 payment_log_query 0/3（均重复 order_query）；B 合法 JSON 3/3、目标 3/3。
合计 6883 input + 278 output = 7161 tokens，自动重试0；证据
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/v47-history-paired-dx53hc5r/replay`。
随后使用显式历史复合状态，只跑候选B两次：订单消息/结果来自第七次，支付日志 observation 来自第五次
task `2096902272884645889` 第3次DECISION；剩余预算仅在诊断payload设为3次decision/1次tool。
B2/2返回合法FINISH/stop，2666 input + 474 output = 3140 tokens；来源与结果保留在
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/v47-history-finish-968qb6_y`。
该历史复合状态不是同一生产任务快照；两项诊断合计8次Chat/10301reported tokens，没有执行新工具、检索、final或浏览器，
不计入E2E次数，也不能将第五次支付日志成功挪用至第七或第八次。
独立入口最多6次请求、总预算60000、单次cap8192/120秒；输入UTF8估算+8192不满足预算或context则停止，不减cap。
未知usage、HTTP/transport错误停止后续请求且不重试；只保存严格决策安全字段与模型/usage/耗时/来源，不保存reason、推理或凭据。
回放结果支持在该状态下提升精确候选，但不证明通用可靠性或内部唯一因果机制。生产TaskPromptBuilder已追加同一通用历史说明：
已完成结果应参与下一动作判断、相同参数重查复用缓存并不刷新数据、证据足够时FINISH；仍不服从工具数据内的指令。
不硬编码工具顺序或订单，不修改严格parser、工具参数校验、重复意图保护与独立最终生成协议。
第八次入口预检READY后在stage postgres启动失败，Unix-domain socket路径超过103-byte上限；
证据 `/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-zhipu-history-1dij76aj`。
该次没有应用/浏览器、task或付费Chat/embedding调用，不是模型失败。必要launcher适配关闭未使用的Unixsocket，
建库/应用/采证均沿用loopback TCP；未改迁移、业务API或协议，在新目录再次启动，不重放不明结果任务。
第九次入口成功，证据 `/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-zhipu-history-9yxypy7d`：
唯一新task `2096972424179240962`，浏览器PASSED/complete约22.8秒，run PASSED/exit0/stagecomplete，storage PASS/errors=[]、23/23全通过。
KB `2096972415597694978`、document `2096972415962599426`、chunk `2096972416348475394`、
collection `v47_3eca76909da441e693ca3cb92005d350`，1293-byte原MD经浏览器显式解析/DashScope向量化至READY；
实际Qdrant/RAG命中本次generation0、1valid/0stale、score0.66849273，point/chunk/document一致。
GLM-5.2依次选择order_query、payment_log_query、FINISH并独立FINAL_GENERATION；两项ToolRuntime各一次SUCCESS、5ms、retryCount0，
参数均order_1024，不借用历史结果或cache复用充数。四次实际cap8192、thinkingdisabled，只有decision带json_object，PG/publicTrace选项一致。
任务COMPLETED/ANSWERED，3decisions/2tools，started到completed约19.056秒；usage EXACT4759input+965output=5724tokens。
20条事件连续且末条TASK_COMPLETED，答案与final正文一致，有效S1映射本次document/chunk/generation0；
刷新checkpoint完成，前后task和Trace相同，渲染/事件与GET/Trace收敛。答案逐项核对工具/文档，区分演示事实、处理建议与未知渠道状态。
最新重点Java46/46通过，日志`/private/tmp/v47-history-focused-tests.log`；默认V43/V45/V46受控19/19通过、44.6秒，
日志`/private/tmp/v47-history-controlled-browser.log`。本阶段8次回放加新任务4次Chat共16025reported tokens，不含embedding用量。
当前是前七个任务失败、第八次入口启动失败、第九次入口（第八个实际任务）取得一次固定模型成功主路径；仅一个计划的新任务实际被创建，automaticRetries0。
测试、原生probe、单步/历史复合回放和完整E2E仍分开记录，不外推为跨模型稳定性、生产支付能力或完整V0.1验收。
缺少凭据、不可达或失败如实记录，禁止回退 mock；真实运行后还应人工对照答案与工具/文档证据核查建议。
保留 V43/V45/V46 受控回归，仅修复实际暴露的必要适配问题；不新增业务接口、迁移、页面或执行协议。
V47 未纳入的失败 E2E 由下述 V48 承接；仓库生成物清理和 V0.1 发布验收继续留待后续。

### M4G-D2 / V48：任务失败与浏览器恢复 E2E

2026-09-09 已按 `V0.1-slice-docs/49_FAILURE_RECOVERY_E2E_PACKAGE_INTERFACE.md` 实现并完成受控验收。
独立入口为 `bash scripts/v48-failure-recovery-acceptance.sh`，真实浏览器/JWT/新 PostgreSQL 与
生产创建、快照、Runner、RAG、ToolRuntime、Trace、终态事件链路运行；仅 LLM、embedding/vector 和指定 handler 故障受控。
覆盖非法决策、重复工具循环、参数拒绝/执行失败、检索异常、非法 final 引用、token、整体 deadline、取消，
以及并发同 key、已提交后丢响应、真实 SSE 中断/游标 replay、offline/online、运行中/终态刷新与终态 GET 待同步。
空检索允许继续工具，次数上限可进入受限 final，均保留为正确行为对照。

浏览器 22/22（34.0 秒），PostgreSQL 803 项逐例及3项全局检查通过、collectorErrors=[]；
实际22任务中5个正确成功对照，其余为预期失败/取消/超时。重复任务、工具/LLM调用、用量、唯一终态与答案发布均交叉核对。
取消/超时的晚到 final 在线程退出后再次采证，未发布答案；SSE 从游标5实际补收序号6 TASK_FAILED。
证据位于 `/private/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v48-browser.YJIcUU`，
复现以仓库脚本为准。单例 smoke 与端口预检 BLOCKED 历史分开保留，均没有被冒充为全矩阵通过。
前端76/76及build、后端重点42/42、V43/V45/V46浏览器19/19（50.9秒）、采证器4组离线反例通过。

本片未修改生产执行路径、新API、迁移或页面；入口仅修复端口预检对已结束连接残留的误判。
V47真实成功和失败历史独立保留，本轮未重跑付费provider；以后若修生产执行路径须预检并以新task补跑真实主路径。
本片恢复仅为浏览器观察，不重启TaskRunner、不自动重试任务，不证明真实外部故障概率、线上可靠性或V0.1发布就绪。

### M4G 后续切片与整体目标

V47 承担真实 E2E 成功主路径，V48 承担已冻结的受控失败与浏览器恢复矩阵；Agent/知识库管理扩展能力另行冻结范围。
当前创建响应没有 `eventsUrl`，工具事件提供 `stepId`；前端只消费已存在的公开 DTO，不依赖目标字段。

### 页面

```text
Login
Knowledge Base detail + upload/status
Agent detail + bindings
Agent Run / Task
Task Trace
```

### 前端能力

- Idempotency-Key；
- server status/phase 与 client uiState 分离；
- serverLastEventSequence 与 lastProcessedSequence 分离；
- 新 task 从 0 完整恢复事件；V43 先重建 Trace，再从已处理游标 SSE replay；
- SSE sequence reducer；
- reconnect；
- refresh snapshot；
- draft answer 与 finalAnswer 权威切换；
- citation drawer；
- cancellation；
- TIMED_OUT；
- Trace tabs；
- 不伪装 provider token streaming。

### 真实 E2E

必须使用：

- PostgreSQL；
- 实际 Qdrant；
- 实际 embedding provider；
- 实际 chat provider；
- 两个实际 builtin tool handler；
- 浏览器或脚本化 SSE 客户端。

固定任务：

```text
帮我分析 order_1024 支付失败的原因，并给出处理建议。
```

### 失败 E2E

至少覆盖：

- invalid decision；
- RAG empty；
- tool argument rejection；
- tool failure；
- token budget；
- deadline；
- cancel；
- repeated POST；
- SSE reconnect。

---

## 10. V0.1 Release Gate

2026-09-09 本轮结果：**13/13 PASS**；逐项证据、已知问题和发布边界见
[总验收报告](../release-docs/V0.1_RELEASE_GATE.md)。以下门槛原文保持不变。

只有同时满足以下条件才打 V0.1 tag：

1. Project Spec 的用户故事完整可执行；
2. Task/phase/termination contract 只有一套；
3. Agent 绑定真实存在；
4. RAG 使用 READY document generation snapshot；
5. 两个工具全部经过 ToolRuntime；
6. Final Generation 独立完成；
7. task/step/LLM/RAG/tool/event 可回查；
8. SSE 可恢复；
9. 前端刷新不丢最终结果；
10. 真实 provider + Qdrant E2E 成功；
11. 失败路径有稳定错误码；
12. README 明确启动和演示步骤；
13. 仓库不包含 target/out/.DS_Store 等生成物。

单独的 unit tests 全绿、mock Engine 闭环或工具独立调用不能代替 Release Gate。

---

## 11. V0.1 后的优先级

版本边界以 [Project Spec 第 7 节](agentflow-hub-project-spec.md#7-v02v03-与-v10-边界) 为准。
以下区分版本目标与已冻结切片；V0.2-A/B 和 V0.3-A/B 的首份契约已冻结，实际施工逐片进行，
未由切片覆盖的版本目标仍需独立契约。完成声明继续遵守第 13 节。后续切片目录统一为 `V0.2-slice-docs/`、`V0.3-slice-docs/`，已建立，首份施工契约与独立门槛见本文末尾。

### V0.2：稳定性与维护

- 受控单宿主机重启后的遗留 QUEUED/RUNNING 收尾（V0.2-A）；
- 更完整 timeout/cancel；
- Trace retention 和脱敏；
- 文档/向量 reconciliation；
- Docker Compose 一键启动；
- 压测和线程池参数验证。

以现有 timeout/cancel、Trace 和文档补偿为基线补齐缺口。V0.2-A 的受控启动收尾语义已冻结并独立施工；
不能把浏览器观察恢复当作进程恢复证据，不引入自动续跑、任务/模型/工具重试或多实例调度。

### V0.3：质量回归

- Prompt/config version；
- Evaluation CLI/API；
- 固定且有版本的 eval dataset；
- tool/citation/RAG 基础指标与评测报告；
- 动态 Episode export。

依赖已有 task snapshot 和 Trace，通过普通 AgentTask 路径执行评测；指标区分确定性规则核对与
标注/人工质量判断。动态 Episode、轻量 CLI/API 和基础自动指标按 V0.3 规划，
不再沿用旧 Harness 文档中分别推迟到 V1.0/V1.5 的归属；接口、存储和阈值仍需切片冻结。
Evaluation UI 留 V1.0 候选，自动配置对比与 Episode 持久化缓存留 V1.5 候选。

### V1.0：选定的工程化升级

按实际需要选择，而非全部强制同时实现：

- PDF；
- MinIO；
- 可靠队列/RabbitMQ；
- conversation；
- 工具管理；
- richer Trace UI；
- Evaluation UI；
- 用户级限流；
- 多实例部署；
- provider token streaming。

引入 RabbitMQ 的前提：单实例 DB task + thread pool 已经无法满足可靠投递、吞吐或多 worker 需求。RabbitMQ 不是“项目看起来更完整”的必选装饰。

### V1.5

- semantic-v1，在评测证明优于 baseline 后；
- rerank/Hybrid Search；
- 基于 V0.3 评测基线的自动 Prompt/model/RAG 配置对比；
- Tool Policy；
- HTTP adapter；
- Episode persistence cache；
- Redis fan-out/限流。

### V2.0

- 受控 MCP；
- Approval；
- 多 Agent；
- 工作流；
- 企业组织；
- 完整 observability stack。

---

## 12. 下一批建议提交顺序

建议按可审查的小提交推进：

```text
docs: align V0.1 architecture contracts
feat(agent): add knowledge and tool bindings
feat(agent): add task schema, durable events and idempotent creation
feat(agent): add bounded dispatcher and task runner
feat(trace): add task steps and llm/rag schema
feat(trace): add execution recorder and tool task linkage
feat(rag): add task corpus snapshot retrieval
refactor(agent): integrate runner, rag, decision budgets and final generation
feat(task): expose task api and replayable sse
feat(web): add minimal agent run and trace pages
test(e2e): add real payment diagnosis workflow
chore: cut v0.1 release
```

不要把 schema、Engine、SSE、前端和 E2E 混成一个无法审查的大提交。

---

## 13. 每个切片的完成声明

每份 slice-doc 必须明确：

- 基于哪个 commit；
- 新增了什么；
- 没有新增什么；
- 是否修改 schema；
- 是否调用真实外部服务；
- 自动化测试范围；
- 手工/E2E 是否执行；
- 哪些结论不能从当前证据推出。

同时，slice-doc 不再重复创造长期状态、Schema 或版本边界。若实现迫使 canonical contract 变化，先更新 `spec-docs/`。

---

## 14. Stop-the-line 条件

出现以下情况时暂停继续编码，先修契约：

- 需要新增未在 canonical contract 中定义的 task status；
- 同一事实准备同时写入两张权威表；
- 需要绕过 owner scope 或 binding；
- 需要在外部 I/O 中持有数据库事务；
- ToolRuntime 与 Engine 同时计算同一预算；
- Engine 需要 stepId 但 `agent_step` schema 尚不存在；
- SSE 事件无法从数据库恢复；
- 新 embedding model 准备写入旧 collection；
- 当前 vector ID v1 被用于两个 generation 同点并存；
- migration 需要回改 V1–V16；
- 前端需要猜测后端未定义的状态；
- mock 测试被用来宣称真实 E2E 完成。

---

## 15. 范围控制指标

在 V0.1 前，新增功能默认应回答：

```text
它是否直接提高支付诊断闭环的可执行性、可控性、可追踪性或可恢复性？
```

若答案是否定的，进入 backlog，而不是当前 milestone。


## V0.2-A 独立施工门槛（2026-09-12）

目录已建立，施工契约为 `V0.2-slice-docs/01_TASK_RECOVERY_AND_INTERRUPTION_PACKAGE_INTERFACE.md`。本次 HEAD fb6a325 相对基线 dce66e3 仅有三份文档变化，复用 V38/V39/V40/V42/V43/V48/V50 的实际代码；没有已存在的进程收尾实现。

顺序固定 V0.2-A → V0.2-B → V0.3-A → V0.3-B。本片仅受控单宿主机锁、启动门禁、遗留任务原子收尾与事实/UI 投影。A01–A17 必测，使用 disposable PostgreSQL、真实 JVM kill/restart、test-source 控制点和独立 durable 外部边界计数，恢复新增调用数必须为 0；原子失败、互斥、门禁和冷切换证据不可省略。另执行相关 Java/迁移/前端/浏览器、V48 取消/超时/幂等/SSE 回归和 V47 真实主路径（缺凭据或环境须记未执行）。受控 HTTP 服务并非真实供应商执行或计费证据。

实际结果由切片文档逐项记录，施工中不预标 PASSED；A/B 均不覆盖 retention、reconciliation、完整 Compose 或容量验收，不据此宣布整个 V0.2 发布。

2026-09-12 本片结果：V0.2-A 已实现，实际 JVM/PG 受控 A01–A17 17/17、配套浏览器 3/3、V48 22/22 与 803+3 数据检查通过；核心 PG 14/14、既有 PG 回归 45/45、前端 99/99 与 build 通过。V47 缺凭据未发付费调用，真实成功回归 BLOCKED。完整命令与失败记录见切片第 9 节，不能把受控通过提升为真实供应商或整个 V0.2 发布证据。
