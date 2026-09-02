# AgentFlow Hub 实施路线图

> 文档状态：**NORMATIVE**  
> 权威范围：从当前 V36 基线到 V0.1/V1.x 的施工顺序、依赖和验收门槛  
> 最近审查基线：`main@f276549`（V36）

---

## 1. 当前判断

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

1. `agent_app (id,user_id)` unique；
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
- schema hash 和 implementation version resolver。

### Seed/验收数据

支付 Agent 固定绑定：

```text
1 个支付知识库
order_query
payment_log_query
```

`report_generate` 不绑定。

### 验收门槛

- 不能绑定跨 owner KB；
- 不能绑定 disabled/deleted/unsupported tool；
- 未绑定工具不进入 snapshot；
- KB 无 READY 文档时 snapshot resolver 失败；
- 已创建的 snapshot 不受绑定后续修改影响。

---

## 5. M4C：AgentTask 根对象与 TaskRunner

### 目标

建立一次执行的权威根对象和可靠单实例调度。

### Schema

创建：

```text
agent_task
```

字段和 CHECK 以 Data Model 为准。

### Backend

- `AgentTaskApplicationService.createTask`；
- `Idempotency-Key`；
- request fingerprint；
- 同事务 execution snapshot；
- after-commit dispatch；
- bounded `TaskDispatcher`；
- `TaskRunner`；
- `QUEUED -> RUNNING` 条件领取；
- phase 更新；
- cancel_requested_at；
- terminal conditional update；
- dispatch rejection 失败落库。

### 暂时允许

本阶段 AgentEngine 可以先用 mock/脚本化 outcome，目的是先验证 lifecycle，不急于同时接 RAG/Trace。

### 验收门槛

- 相同 idempotency key + 相同 payload 返回同一 task；
- 相同 key + 不同 payload 409；
- 两个 runner 只有一个领取成功；
- thread pool rejection 不留下永久 QUEUED；
- cancel/complete 竞态只有一个终态；
- status/phase/terminationReason CHECK 全部通过。

---

## 6. M4D：将前置 RAG 接入 V36 Engine

### 目标

把当前独立 Knowledge Retrieval 接入 task snapshot 和 AgentEngine。

### Backend

- `RetrievalService` 接收 corpus snapshot；
- 按 documentId + vectorGeneration 过滤；
- PostgreSQL 二次验证；
- bounded evidence；
- citation ID；
- RAG 空结果合法；
- snapshot evidence 进入 Decision/Final Prompt；
- knowledge 内容标记为 untrusted data。

### Engine 调整

- V36 `TOOL_CALL/FINAL_ANSWER` 收敛为 canonical `CALL_TOOL/FINISH`；
- `FINISH` 只触发 Final Generation；
- `max_steps` 语义重命名/映射为 maxDecisionTurns；
- provider usage unknown 使用保守 estimator，不按 0，也不无条件使成功任务失败；
- 为 Final Generation 预留 token；
- maxToolCalls/decision limit 触发受限最终生成；
- ToolRuntime 接收 task/step/snapshot context；
- 重复调用检测。

### 验收门槛

- 脚本化模型能完成 RAG -> 两工具 -> Final；
- 无 RAG hit 仍可工具执行；
- 未绑定工具被 parser/Engine 拒绝；
- budget 精确收敛；
- Agent/工具/KB 中途修改不改变 snapshot 语义。

---

## 7. M4E：Task-scoped Trace

### 目标

让同一 task 的执行路径完整可查询。

### Schema

创建：

```text
agent_step
llm_call_log
rag_retrieval_log
rag_retrieval_hit
```

并为现有 `tool_call_log` 增加 task/step 一致性约束和索引。

### Backend

- `ExecutionRecorder`；
- step lifecycle；
- DECISION/FINAL_GENERATION LLM calls；
- RAG hit snapshot；
- tool log 关联 task/step；
- Prompt/response/tool 数据脱敏与大小限制；
- 禁止保存自由 chain-of-thought；
- Trace 聚合 query service。

### 验收门槛

固定任务必须拥有：

```text
1 PRE_RETRIEVAL step
>= 1 LLM_DECISION step
2 TOOL_CALL steps
1 LLM_FINAL_GENERATION step
对应专项日志
```

源文档/Agent/工具后续修改后，历史 Trace 仍可解释。

---

## 8. M4F：Task API、持久事件与 SSE

### Schema

创建：

```text
agent_task_event
```

### API

```text
POST /api/v1/agents/{agentId}/tasks
GET  /api/v1/tasks
GET  /api/v1/tasks/{taskId}
POST /api/v1/tasks/{taskId}/cancel
GET  /api/v1/tasks/{taskId}/events
GET  /api/v1/tasks/{taskId}/trace
```

### Event

- 单一 `TaskEventAppender`；
- task 内 sequence；
- semantic events；
- terminal state/event 同事务；
- `ANSWER_CHUNK` 合并；
- event payload 脱敏。

### SSE

V0.1 使用数据库事件日志读取：

- `afterSequence`；
- `Last-Event-ID`；
- persisted replay；
- terminal 后关闭；
- 不依赖内存 emitter 保证可靠性。

### 验收门槛

- POST 返回前产生的事件不丢；
- SSE 重连不重不漏；
- sequence gap 可检测；
- TASK_COMPLETED 时 finalAnswer 已可 GET；
- 浏览器断线不影响 task 执行；
- event 不替代 Trace 专项日志。

---

## 9. M4G：最小前端与真实 E2E

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
- SSE sequence reducer；
- reconnect；
- refresh snapshot；
- draft answer 与 finalAnswer 权威切换；
- citation drawer；
- cancellation；
- TIMED_OUT；
- Trace tabs。

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

只有同时满足以下条件才打 V0.1 tag：

1. Project Spec 的用户故事完整可执行；
2. Task/phase/termination contract 只有一套；
3. Agent 绑定真实存在；
4. RAG 使用 READY generation snapshot；
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

### V0.2：稳定性与维护

- 陈旧 RUNNING task 恢复策略；
- 更完整 timeout/cancel；
- Trace retention 和脱敏；
- 文档/向量 reconciliation；
- Docker Compose 一键启动；
- 压测和线程池参数验证。

### V0.3：质量回归

- Prompt/config version；
- Evaluation CLI/API；
- 固定 eval dataset；
- tool/citation/RAG 指标；
- 动态 Episode export。

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
- 多实例部署。

引入 RabbitMQ 的前提：单实例 DB task + thread pool 已经无法满足可靠投递、吞吐或多 worker 需求。RabbitMQ 不是“项目看起来更完整”的必选装饰。

### V1.5

- semantic-v1，在评测证明优于 baseline 后；
- rerank/Hybrid Search；
- Tool Policy；
- HTTP adapter；
- Episode persistence cache；
- Redis fan-out/限流。

### V2.0

- 受控 MCP；
- Approval；
- 多 Agent；
- 工作流；
-企业组织；
-完整 observability stack。

---

## 12. 下一批建议提交顺序

建议按可审查的小提交推进：

```text
docs: align V0.1 architecture contracts
feat(agent): add knowledge and tool bindings
feat(agent): add task schema and idempotent creation
feat(agent): add bounded dispatcher and task runner
feat(rag): add task corpus snapshot retrieval
refactor(agent): align decision and budget contracts
feat(trace): add task steps and llm/rag linkage
feat(tool): attach tool calls to task steps
feat(task): add durable events and replayable sse
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
- SSE 事件无法从数据库恢复；
- 新 embedding model 准备写入旧 collection；
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
