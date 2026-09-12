# AgentFlow Hub Runtime Governance（Harness）设计

> 文档状态：**FUTURE-NORMATIVE**  
> 权威范围：Episode、Tool Policy、Evaluation 和受控 MCP 的后续边界  
> 版本规划对齐：2026-09-12，基于 `main@58b6145`；版本归属见 Project Spec 第 7–8 节，规划不表示已实现。\
> V0.1 约束：本文件不得扩大 V0.1 范围

---

## 1. 定位

“Runtime Harness”是对 Agent 运行治理能力的统称，不是一个包罗万象的顶层业务模块，也不接管 AgentEngine、ToolRuntime、Trace 或 Evaluation 的职责。

对应关系：

```text
Episode / export        -> trace.episode
Tool Policy             -> tool.policy
Evaluation              -> evaluation
MCP adapter             -> tool.adapter.mcp
```

禁止建立：

```text
AgentEngine -> Harness -> ToolRuntime -> Harness -> AgentEngine
```

任何双向依赖都说明模块所有权错误。

---

## 2. 设计目标

后续治理能力应让一次 Agent 运行能够：

- 被完整解释；
- 导出为稳定证据包；
- 在不同 Prompt/model/RAG 配置之间比较；
- 对高风险工具执行附加策略；
- 在接入外部工具协议时继续受平台边界控制。

这些能力全部读取或扩展已有执行事实，不创建第二个运行时。

---

## 3. V0.1 边界

V0.1 不实现：

- 独立 Harness 模块；
- `agent_episode` 表；
- PolicyGuard/policy_check_log；
- Evaluation 数据模型和页面；
- MCP；
- 人工确认；
- Prompt/model/RAG A/B；
- Episode 缓存或异步生成。

V0.1 只需要保证 task、step、LLM、RAG、tool 和 event Trace 足够完整，使后续治理能力可以从这些事实自然构建。

V0.1 的 Trace API 可以提供一个动态聚合视图，但不将其命名为已经持久化、可完全复现的 Episode Package。

---

## 4. Episode 设计

### 4.1 Episode 是派生读模型

Episode 从以下权威数据聚合：

```text
agent_task
execution_snapshot
agent_step
llm_call_log
rag_retrieval_log
rag_retrieval_hit
tool_call_log
agent_task_event
```

Episode 不反向修改 task 或调用 ToolRuntime。

### 4.2 最小结构

```json
{
  "schemaVersion": "agent-episode-v1",
  "task": {},
  "executionSnapshot": {},
  "steps": [],
  "llmCalls": [],
  "ragRetrievals": [],
  "toolCalls": [],
  "events": [],
  "budget": {},
  "finalAnswer": "",
  "citations": [],
  "metrics": {}
}
```

### 4.3 动态聚合优先

V0.3 规划动态 Episode view/export，候选接口为：

```text
GET /api/v1/tasks/{taskId}/episode
GET /api/v1/tasks/{taskId}/episode/export
```

由 Trace query service 动态聚合；具体 HTTP/DTO 契约在 V0.3 切片开工前冻结，以上路径不是已实现接口。

V0.3 不要求 Episode 持久化。`agent_episode` 缓存/归档留到 V1.5 候选，只有满足以下任一条件时才考虑增加：

- 聚合查询成本已被测量为不可接受；
- 需要不可变归档；
- 需要长期导出格式冻结；
- Evaluation 大量复用同一聚合结果。

即使增加表，它也必须标记：

```text
source_task_id
schema_version
source_trace_revision
created_at
```

并明确是缓存/归档，不是新的任务事实源。

### 4.4 可复现性的边界

保存 Episode 不等于可以重新得到逐 token 相同输出。应区分：

- **Execution explainability**：可以解释用了什么配置、证据和工具；
- **Deterministic replay**：对相同 provider 能得到完全相同结果；
- **Simulation replay**：不重新调用外部系统，只重放历史事实。

V0.3 优先保证 explainability，通过动态导出保留可回查的执行事实；导出本身不等于已经实现
simulation replay，更不承诺跨模型的 deterministic replay。需要专门回放能力时再单独冻结范围。

---

## 5. Tool Policy

### 5.1 位置

未来调用顺序：

```text
ToolRuntime hard validation
-> ToolPolicy check
-> optional approval
-> handler execution
```

Hard validation 包括：

- 工具存在/live/ACTIVE；
- Agent binding；
- snapshot 一致性；
- Schema；
- handler allowlist。

这些不是 Policy，不能被 `WARN` 或配置绕过。

### 5.2 Policy 适用范围

- 写操作；
- 敏感字段；
- 外部域名；
- 数据出境/环境规则；
- 用户、Agent、工具级频率限制；
- 业务时间窗口；
- 需要人工审批的动作。

### 5.3 决策

```text
ALLOW
ALLOW_WITH_WARNING
BLOCK
REQUIRE_APPROVAL
```

只有 `ALLOW/ALLOW_WITH_WARNING` 可进入 handler。

### 5.4 Policy 记录

未来 `policy_check_log` 保存：

```text
taskId
stepId
toolCallId
toolCode
policyVersion
decision
policyCodes
safeReason
inputFingerprint
createdAt
```

默认不复制完整敏感 arguments。需要审计时使用脱敏 snapshot。

### 5.5 人工确认

人工确认是独立持久状态机，需要：

- approval request；
- approver identity；
- expiry；
- approve/reject；
- task resume token；
- 幂等；
- 工具定义和参数快照；
- 状态 `WAITING_APPROVAL`。

在这些契约完整前，只保留 `requires_confirmation` 字段，不宣称已经实现人工确认。

---

## 6. Evaluation

### 6.1 定位

Evaluation 是离线或受控批量运行系统，不属于 AgentEngine 内部循环。

```text
EvaluationRunner
-> 创建普通 AgentTask
-> 等待终态
-> 读取 Trace/Episode view
-> 计算指标
```

它不能调用 Engine 的私有方法绕过 task snapshot、ToolRuntime 或 Trace。

### 6.2 V0.3 基础指标范围

以下为指标候选，切片中必须冻结定义、所需标注、分母与缺失值处理。可自动核对的结构指标直接
读取 Trace；Hit@K/MRR 等检索指标需要固定相关性标注，citation accuracy 与回答正确性需要明确
标注或人工 rubric，不能用 citation whitelist 命中或 `COMPLETED` 代替质量判断。

RAG：

- Hit@K；
- MRR；
- expected document/chunk hit；
- citation whitelist/accuracy；
- stale hit count。

Agent：

- terminal status；
- termination reason；
- expected tool set/order；
- invalid decision；
- duplicate loop；
- answer produced。

成本与性能：

- token；
- LLM/RAG/tool latency；
- total latency；
- tool count；
- exact/estimated usage quality。

人工判断：

- passed；
- judge comment；
- rubric version。

### 6.3 对比前提

Prompt/model/RAG A/B 必须固定：

- eval dataset version；
- demo business dataset version；
- tool implementation version；
- corpus generation；
- embedding profile；
- application revision；
- random/temperature 配置。

不固定这些变量时，对比分数不能归因于某一个修改。

### 6.4 版本计划

- V0.1：无 Evaluation；
- V0.2：先完成稳定性与维护，不要求新增 Evaluation；
- V0.3：Prompt/config version、固定且有版本的 eval dataset、轻量 CLI/API、tool/citation/RAG 基础指标及报告、动态 Episode export；支持规则指标自动计算，保留标注/人工判断；
- V1.0：按需增加 Evaluation UI，复用 V0.3 评测事实；
- V1.5：基于固定评测基线的自动配置对比和更完整指标；Episode 持久化缓存仍按第 4.3 节条件评估；
- V2.0：更完整 regression pipeline，可选。

V0.3 不要求自动配置搜索、完整 A/B 编排或 Evaluation UI；公开接口、评测存储、指标公式和
Release Gate 阈值在对应切片中冻结。本计划只调整版本归属，不声明上述后续能力已经交付。

---

## 7. 受控 MCP Adapter

### 7.1 原则

MCP 只是 ToolRuntime 的一种 adapter，不是新的 Agent runtime。

```text
allowlisted MCP server
-> fetch tool schema
-> review and map to ToolDefinition
-> explicit Agent binding
-> immutable task snapshot
-> ToolRuntime
-> Tool Policy
-> timeout / trace
```

### 7.2 必须限制

- 仅管理员注册 server；
- server URL allowlist；
- 禁止内网 SSRF；
- 凭证由服务端 secret store 管理；
- 模型不能选择 server；
- schema 变化需要新版本或 task mismatch 失败；
- tool result 大小限制和脱敏；
- 明确 side-effect class；
- 支持取消、deadline 和 outcome-unknown；
- 不自动发现全网 server。

### 7.3 版本计划

MCP 不进入 V0.1/V1.0 主线。只有 BUILTIN 工具链、Trace、Tool Policy 和外部调用可靠性已经成熟后，才进入 V2.0 候选。

---

## 8. 依赖方向

允许：

```text
evaluation -> public task application API
trace.episode -> trace repositories
tool.runtime -> tool.policy
tool.adapter.mcp -> MCP client infra
```

禁止：

```text
AgentEngine -> Evaluation
AgentEngine -> Episode persistence
Trace -> ToolRuntime execute
Policy -> AgentEngine state transition
MCP client -> AgentEngine
Episode -> 修改 task
```

---

## 9. 实施顺序

在 V0.1 完成后，先按 V0.2 处理稳定性与维护，再按以下依赖推进 V0.3 及后续治理能力：

1. 先稳定 Trace 聚合查询；
2. 增加动态 Episode view/export；
3. 增加 Evaluation CLI/API；
4. 用 Evaluation 证明 Prompt/RAG 修改有效；
5. 出现真实风险工具后增加 Tool Policy；
6. 出现真实写操作后增加 Approval；
7. 最后评估 HTTP/MCP adapter。

V0.3 的评测数据版本、判定标准及 Prompt/config version 必须在可比较的评测运行前固定；
后续配置对比和策略能力按第 6.4 节及 Project Spec 的版本归属推进，不因上述依赖顺序提前纳入 V0.3。

不得为了“架构看起来企业级”而提前创建空表、空模块或循环依赖。

---

## 10. 验收原则

- Episode 字段都能追溯到一个权威 Trace 来源；
- 删除源配置不破坏历史 Episode view；
- Evaluation 使用普通 task 路径，不绕过生产边界；
- Policy 不能绕过 hard validation；
- Approval 有完整持久状态机后才能启用；
- MCP 工具仍受 binding、snapshot、timeout、policy 和 Trace 控制；
- 任何治理功能都不能成为 V0.1 的隐藏前置条件。
