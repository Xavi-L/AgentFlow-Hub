# AgentFlow Hub 项目规格

> 文档状态：**NORMATIVE**  
> 权威范围：项目定位、版本边界、完成标准  
> 最近审查基线：`main@f276549`（V36）  
> 版本规划对齐：2026-09-12，基于 `main@58b6145`；第 4 节仍保留 V36 历史起点。\
> 任务状态、执行阶段和预算语义以 `agentflow-hub-agent-engine-design.md` 为准。

---

## 1. 项目定位

AgentFlow Hub 是一个面向企业知识库与受控业务工具的 Java Agent 应用平台。项目的核心价值不是提供一个通用聊天壳，也不是提前建设完整低代码平台，而是证明一条 Agent 运行链路能够同时满足：

- **可执行**：模型可以基于知识和业务数据完成多步任务；
- **可控**：模型只提出动作，后端决定是否执行；
- **可追踪**：RAG、LLM、工具、预算和最终结果可以回查；
- **可恢复**：任务、事件和外部调用失败具有稳定语义；
- **可演进**：V0.1 的核心边界可以自然扩展，而不是在 V1.0 推翻重写。

首个业务场景固定为：

> 支付失败诊断 Agent：结合支付规则知识库、订单数据和支付日志，分析指定订单失败原因并给出处理建议。

项目采用 Spring Boot + PostgreSQL + Qdrant + Vue 3 的模块化单体架构。当前阶段不拆微服务。

---

## 2. 设计原则

### 2.1 先完成纵向闭环

所有早期工作都应服务于以下链路：

```text
用户提交问题
-> 创建 AgentTask
-> 冻结执行快照
-> 前置 RAG
-> 模型决策
-> ToolRuntime
-> 最终生成
-> Trace
-> SSE/页面展示
```

在该链路完成前，不继续横向扩展工具、文档治理、评测平台或基础设施。

### 2.2 核心边界从第一天正确，外围能力保持简单

V0.1 必须从一开始正确处理：

- owner scope；
- task 生命周期；
- Agent/知识库/工具绑定；
- 执行快照；
- 外部依赖 Gateway；
- 工具白名单和参数校验；
- token、工具次数和 deadline；
- Trace 的事实所有权；
- SSE sequence 和恢复；
- PostgreSQL/Qdrant 的一致性边界。

V0.1 可以简化：

- 只支持一个模型 profile；
- 只支持一个 embedding profile；
- 只支持两个只读工具；
- 只支持单轮用户输入；
- 使用有界线程池而不是消息队列；
- 使用本地文件存储；
- 使用固定 RAG 参数和固定 Prompt contract。

### 2.3 模型不拥有系统状态

模型只能返回内部动作：

```text
CALL_TOOL
FINISH
```

模型不能直接：

- 修改 task 状态；
- 选择未绑定工具；
- 绕过 ToolRuntime；
- 修改预算；
- 声明某个外部调用已经成功；
- 创建任意 HTTP/MCP 连接；
- 将知识库文本解释为系统指令。

### 2.4 PostgreSQL 保存业务事实，外部系统保存派生数据

- PostgreSQL 是用户、知识库、文档、chunk 正文、Agent、task 和 Trace 的权威数据源；
- Qdrant 是向量索引，不是正文权威；
- SSE 是事件投影，不是 task 状态权威；
- Episode 是 Trace 聚合视图，不是新的事实源；
- Redis 若后续引入，也不成为 task 权威状态。

### 2.5 外部依赖通过窄接口隔离

V0.1 保留以下能力边界：

- chat completion；
- chat streaming；
- embedding；
- vector store；
- file storage；
- task dispatch；
- task event sink。

现有 `LlmGateway.chat` 可以继续作为 chat completion 的实现接口，但不得扩张为同时处理 embedding、rerank 和业务 Prompt 的巨型接口。

---

## 3. 总体系统边界

### 3.1 核心模块

```text
common
user
knowledge
agent
  app
  execution
tool
trace
frontend
demo
infra
```

说明：

- `knowledge`：知识库、文档、解析、分块、向量化和在线检索；
- `agent.app`：Agent 配置和绑定；
- `agent.execution`：AgentTask、TaskRunner 和 AgentEngine；
- `tool`：工具定义、绑定、参数校验和执行；
- `trace`：step、LLM、RAG、tool 和 event 查询；
- `demo`：模拟订单和支付日志数据；
- `infra`：外部 provider、Qdrant、存储和线程池适配。

`task` 不在 V0.1 建设成通用异步任务平台；AgentTask 暂属于 `agent.execution`。出现第二种具有相同生命周期语义的业务任务后，再评估是否抽取通用 task 模块。

`harness` 不作为独立顶层业务模块。Episode、Tool Policy、Evaluation 和 MCP 分别归属 Trace、Tool、Evaluation 和 Tool Adapter。

### 3.2 不属于当前项目的内容

- 通用低代码 Agent 操作系统；
- 用户在线执行任意代码；
- 插件市场；
- 任意 MCP Server 自动接入；
- 多 Agent 协商；
- 长期自治后台 Agent；
- 模型训练平台；
- 企业组织与计费；
- Kubernetes 生产平台；
- 完整 OpenTelemetry 后端。

---

## 4. 当前仓库状态

截至 V36，仓库已经实现：

- 用户注册、登录、JWT 和 owner scope；
- 知识库、文档、确定性分块、向量化与检索；
- PostgreSQL/Qdrant 删除和重处理补偿；
- 工具定义、Schema 校验、工具调用日志；
- `order_query`、`payment_log_query`、`report_generate`；
- AgentApp CRUD、启停和执行参数；
- 通用同步 `LlmGateway.chat`；
- 同步、进程内、非持久化 AgentEngine 决策循环。

V36 仍不代表 V0.1 完成，因为尚缺：

- Agent 与知识库/工具绑定；
- `agent_task` 根对象；
- 前置 RAG 与 AgentEngine 集成；
- task/step/LLM/RAG/event 基础 Trace；
- 公开 Task API；
- 可恢复 SSE；
- 最小执行页面；
- 真实 provider + Qdrant 的完整端到端验收。

当前已实现能力保留，不回滚；从 V36 开始暂停外围扩展，优先完成上述闭环。

---

## 5. V0.1 产品边界

### 5.1 V0.1 用户故事

用户能够：

1. 登录；
2. 创建或选择一个支付知识库；
3. 上传一份 `.txt` 或 `.md` 支付规则文档；
4. 等待文档达到可检索状态；
5. 创建或选择支付诊断 Agent；
6. 将 Agent 绑定到一个知识库和两个只读工具；
7. 提交：`帮我分析 order_1024 支付失败的原因，并给出处理建议。`；
8. 看到检索、工具调用和最终生成的执行进度；
9. 查看最终答案和引用；
10. 查看该任务的基础 Trace。

### 5.2 V0.1 必须实现

#### 用户和资源隔离

- JWT 鉴权；
- 当前用户只能访问自己的知识库、Agent 和 task；
- 绑定关系必须证明资源属于同一 owner；
- 跨 owner 与软删除资源统一不可见。

#### 知识库

- `.txt`、`.md` 上传；
- 本地文件存储 Gateway；
- 确定性 `structured-token-v1` 分块；
- 固定 embedding profile；
- Qdrant 向量化；
- 文档解析状态与 chunk 向量化状态分离；
- 可检索就绪状态；
- topK 向量检索；
- PostgreSQL 正文回查；
- citation 白名单。

#### Agent 配置

- Agent 名称、描述、system prompt；
- 固定 chat model profile；
- 最大 decision turns；
- 最大工具调用次数；
- 总 token 预算；
- 整体 deadline；
- ACTIVE/DISABLED；
- 一个或多个知识库绑定的数据模型，但首个演示只绑定一个；
- 工具绑定的数据模型，但首个演示只绑定两个只读工具。

#### Agent 执行

- 创建持久化 AgentTask；
- 请求幂等；
- 执行快照；
- 有界线程池调度；
- 条件更新领取 task；
- 前置 RAG；
- 严格 JSON decision；
- 零到多次 ToolRuntime 调用；
- 工具结果 observation；
- 独立 Final Generation；
- budget/deadline/cancel 检查；
- 稳定失败分类。

#### 工具

V0.1 模型只可见：

- `order_query`；
- `payment_log_query`。

两者必须：

- seeded registry；
- Agent 显式绑定；
- ACTIVE 检查；
- JSON Schema 参数校验；
- 只读；
- 有固定 timeout；
- 默认不重试；
- 记录 tool call log。

`report_generate` 已存在，但不加入 V0.1 Agent 的可用工具集合。最终 Markdown 展示由 Final Generation 或确定性渲染完成。

#### Trace 与 SSE

至少保存：

- task；
- step；
- LLM calls；
- RAG retrieval 和 hit snapshots；
- tool calls；
- task events；
- final answer。

SSE 必须支持：

- 持久 sequence；
- `Last-Event-ID` 或 `afterSequence`；
- 断线后从数据库补发；
- 语义事件持久化；
- answer chunk 合并，禁止逐 token 写数据库；
- 以 task.final_answer 为最终权威。

#### 前端

- 登录页；
- 简化知识库页；
- 简化 Agent 配置页；
- Agent 执行页；
- 基础 Trace 页或抽屉。

### 5.3 V0.1 可 hard-code

- 一个 chat model profile；
- 一个 embedding profile；
- 一个 Qdrant collection；
- `structured-token-v1`；
- topK 和 similarity threshold；
- Runtime Rules 版本；
- Final Generation Prompt；
- 两个工具；
- demo 数据；
- 线程池参数；
- 文件和上下文上限；
- SSE 轮询间隔。

Hard-code 仍必须位于明确边界后面：Agent 选择 profile，而不是在业务代码中散落 provider 参数；Agent 通过 binding 获得工具，而不是 Engine 任意扫描所有全局工具。

### 5.4 V0.1 明确不做

- RabbitMQ；
- Redis task state；
- MinIO；
- PDF；
- semantic chunking；
- rerank；
- Hybrid Search；
- Prompt 版本管理；
- conversation/message；
- 工具 CRUD；
- HTTP/MCP 工具；
- PolicyGuard；
- 人工确认；
- Evaluation；
- Episode 持久化；
- 工具写操作；
- 复杂取消恢复；
- 多实例部署。

---

## 6. V0.1 完成标准

### 6.1 功能完成

固定问题：

```text
帮我分析 order_1024 支付失败的原因，并给出处理建议。
```

系统必须真实完成：

1. 读取 task execution snapshot；
2. 检索支付规则知识库；
3. 命中并引用 `E_PAY_TIMEOUT` 相关 chunk；
4. 模型选择 `order_query`；
5. 模型选择 `payment_log_query`；
6. 生成失败原因、证据和可执行建议；
7. 保存 task、step、LLM、RAG、tool、event；
8. 页面可展示并在刷新后恢复；
9. Trace 可以解释最终答案来自哪些知识和工具事实。

### 6.2 失败完成

至少验证：

- RAG 空结果仍可进入受控决策；
- 模型返回非法 JSON；
- 模型请求未绑定工具；
- 工具参数不合法；
- 工具未找到业务数据；
- 工具超时；
- LLM 失败；
- token 预算不足；
- task deadline；
- 用户请求取消；
- 重复创建请求返回同一 task；
- SSE 从 sequence 恢复且不重复追加最终答案。

### 6.3 证据标准

以下证据缺一不可：

- 单元测试；
- migration contract test；
- 本地 PostgreSQL + Qdrant 集成测试；
- 真实 chat provider 端到端运行；
- 一条可重复 `.http` 或脚本化验收路径；
- Trace 数据核对；
- 前端刷新/断线恢复演示。

mock `LlmGateway` 只能证明 Engine 编排，不证明真实模型稳定遵守协议。

---

## 7. V0.2、V0.3 与 V1.0 边界

V0.1 之后按“稳定性与维护 → 质量回归 → 选定的工程化升级”推进。以下定义目标版本归属，
施工顺序见 [Implementation Roadmap 第 11 节](agentflow-hub-implementation-roadmap.md#11-v01-后的优先级)。
版本范围不等于实现或验收完成；各版本开工前再冻结具体切片、依赖与 Release Gate。

### 7.1 V0.2：稳定性与维护

- 受控单宿主机重启后的遗留 `QUEUED/RUNNING` task 收尾（V0.2-A）；
- 更完整的 timeout/cancel；
- Trace retention 和脱敏；
- 文档/向量 reconciliation；
- Docker Compose 一键启动；
- 压测和线程池参数验证。

在已有 deadline/cancel、Trace 脱敏与文档补偿基础上补齐缺口。陈旧任务的识别、终态收敛、
执行续跑和新 task 重试是不同能力，具体策略须在对应切片中明确；“恢复”本身不授权自动重放
模型或工具调用，也不等于 V0.1 已有的浏览器 Trace/SSE 观察恢复。继续遵守 Agent Engine 的
终态不可复活及任务唯一执行根对象契约。

2026-09-12：V0.2-A 按 Engine 的受控启动契约独立交付，只收尾遗留任务；V0.2-B 已实现实际工作许可、迟到结果隔离和有限终态保存，B01–B10 受控验收通过。真实供应商回归 V47 仍 BLOCKED，其他维护能力继续分片；具体命令与证据见 V0.2 首份切片文档，不能由 A/B 的受控通过推导整个 V0.2 发布。

### 7.2 V0.3：质量回归

- Prompt/config version；
- Evaluation CLI/API；
- 固定且有版本的 eval dataset；
- tool/citation/RAG 基础指标和可回查的评测报告；
- 从已有 Trace 动态聚合的 Episode export。

版本管理基于已有 execution snapshot 继续演进；固定评测数据、判定标准和运行配置后，
通过普通 AgentTask 路径运行并读取 Trace/Episode 计算指标。明确可自动核对的指标与需要
标注或人工判断的回答质量；成功终态、引用合法不直接等同于回答正确，真实模型输出也不保证完全确定性。

本版本交付轻量质量回归基础，不要求 Evaluation UI、Episode 持久化缓存、自动配置搜索或
完整 A/B 编排。数据表、公开字段、指标算法与阈值由后续切片另行冻结，不因规划名称提前建表。
原先笼统列入 V1.0 的 Prompt/config version、动态 Episode 和轻量 Evaluation，明确前移至 V0.3；
基础自动指标也纳入 V0.3，自动配置对比与更完整回归编排仍留后续版本。

### 7.3 V1.0：选定的工程化升级

在 V0.2/V0.3 基础上按实际需要选择，不要求全部同时实现：

- PDF；
- MinIO 等对象存储；
- 可靠队列/RabbitMQ；
- conversation 和多轮展示；
- 工具管理和工具启停；
- richer Trace UI；
- Evaluation UI；
- 用户级限流；
- 多实例部署；
- provider token streaming。

引入可靠队列/RabbitMQ 的前提是现有单实例 DB task + thread pool 已无法满足可靠投递、吞吐
或多 worker 需求；不将其作为 V0.2/V0.3 的隐藏前置条件。

V1.0 仍不要求：

- 多 Agent；
- 任意 HTTP/MCP 工具；
- 插件市场；
- 完整审批流；
- 复杂 RBAC；
- Kubernetes；
- 完整 Observability 平台。

---

## 8. V1.5 与 V2.0

### V1.5

只有在有基线评测后再考虑：

- `semantic-v1`；
- Hybrid Search；
- rerank；
- 基于 V0.3 评测基线的自动 prompt/model/RAG 配置对比；
- Episode 持久化缓存；
- Tool Policy 规则；
- HTTP tool allowlist；
- 多实例 SSE fan-out；
- Redis 限流。

### V2.0

可选探索：

- 受控 MCP Adapter；
- 高风险工具审批；
- 多 Agent；
- 可视化流程；
- 企业组织空间；
- OpenTelemetry/Prometheus/Grafana；
- 长期记忆。

这些能力不得成为 V0.1、V0.2、V0.3 或 V1.0 的隐藏前置条件。

---

## 9. 最终架构结论

AgentFlow Hub 的核心不是“功能数量”，而是以下边界：

```text
可配置 Agent
+ 可检索知识
+ 受控工具
+ 持久任务
+ 可解释 Trace
+ 可恢复事件流
```

V0.1 应证明这六项能够组成一条真实、稳定、可解释的链路。所有不能直接提高该证明强度的系统，都应延后。
