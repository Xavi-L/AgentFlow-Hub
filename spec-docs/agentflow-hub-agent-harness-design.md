# AgentFlow Hub Runtime Governance（Harness）设计

> 文档状态：**FUTURE-NORMATIVE**  
> 权威范围：Episode、Tool Policy、Evaluation 和受控 MCP 的后续边界  
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

V1 初期优先：

```text
GET /api/v1/tasks/{taskId}/episode
GET /api/v1/tasks/{taskId}/episode/export
```

由 Trace query service 动态聚合。

只有满足以下任一条件时才增加 `agent_episode`：

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

V1 优先实现 explainability 和 simulation replay，不虚构跨模型的完全确定性。

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

### 6.2 最小指标

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
- V1.0：API/CLI 轻量评测，可人工判断；
- V1.5：配置对比和自动指标；
- V2.0：更完整 regression pipeline，可选。

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

在 V0.1 完成后：

1. 先稳定 Trace 聚合查询；
2. 增加动态 Episode view/export；
3. 增加 Evaluation CLI/API；
4. 用 Evaluation 证明 Prompt/RAG 修改有效；
5. 出现真实风险工具后增加 Tool Policy；
6. 出现真实写操作后增加 Approval；
7. 最后评估 HTTP/MCP adapter。

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
