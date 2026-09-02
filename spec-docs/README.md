# AgentFlow Hub 设计文档索引与规范优先级

> 文档状态：**NORMATIVE**  
> 最近审查基线：`main@f276549`（V36 AgentEngine core loop）  
> 适用范围：`spec-docs/`、后续设计变更、V0.1 施工与 V1.x 演进

本目录保存 AgentFlow Hub 的产品边界、领域模型和工程设计。它不是若干互相独立的专题笔记集合，而是一套有明确权威来源和覆盖关系的系统规范。

任何实现、migration、API、前端状态或 slice 说明与本目录冲突时，必须先判断冲突属于合理版本差异、实现切片尚未完成，还是规范漂移；不得同时保留两种互斥解释。

---

## 1. 文档分类

### 1.1 规范性文档（Normative）

规范性文档定义系统必须遵守的契约：

| 文档 | 权威范围 |
| --- | --- |
| `agentflow-hub-project-spec.md` | 项目定位、版本边界、V0.1/V1.0 完成标准 |
| `agentflow-hub-agent-engine-design.md` | 任务生命周期、执行阶段、AgentEngine、预算、取消、失败和事件语义 |
| `agentflow-hub-rag-design.md` | 文档入库、分块、embedding profile、向量身份、检索、citation 和重建语义 |
| `agentflow-hub-tool-system-design.md` | ToolRuntime、工具定义、绑定、参数校验、超时、重试和工具结果语义 |
| `agentflow-hub-data-model.md` | 目标 PostgreSQL/Qdrant 数据形状、外键、约束、索引和快照字段 |
| `agentflow-hub-backend-api-design.md` | HTTP、SSE、DTO、错误码和内部接口投影 |
| `agentflow-hub-frontend-design.md` | 前端页面边界、后端状态映射、SSE 恢复和展示规则 |
| `agentflow-hub-implementation-roadmap.md` | 从当前仓库状态向 V0.1/V1.0 演进的施工顺序和验收门槛 |

### 1.2 规划性文档（Future-Normative）

`agentflow-hub-agent-harness-design.md` 描述 Episode、Tool Policy、Evaluation 和受控 MCP 的后续演进。只有其中明确标为当前版本的条目才构成当前施工要求；未来章节不得反向扩大 V0.1 范围。

### 1.3 信息性文档（Informative）

`agent-backend-ai-learning-guide.md` 是学习索引，不是产品需求、架构决策或里程碑验收依据。它不能为项目新增模块、表、依赖或完成标准。

`slice-docs/` 是已完成切片的实现契约与验收证据。它们用于说明某个提交实际完成了什么，但不单独定义长期产品架构。若 slice 中出现比旧规范更精确且已经由代码和 migration 固化的契约，应通过新的设计变更将该契约回写到本目录，而不是让两套定义长期并存。

---

## 2. 冲突时的优先级

发生冲突时，按以下顺序判断：

```text
已接受且仍生效的 canonical contract / ADR
> 本目录对应专题的规范性文档
> 数据模型与 API/Frontend 投影
> Implementation Roadmap
> 当前 slice-docs 实现说明
> 学习材料和历史说明
```

补充规则：

1. **数据库事实优先保证可迁移性。** 已应用的 Flyway migration 不回改；规范调整通过更高版本 migration 实现。
2. **代码不是自动的长期规范。** 临时实现 shortcut 不会因为已提交就自动升级为永久设计。
3. **已经固化且正确的精确契约必须回写。** 例如当前稳定 UUID 向量身份、owner-scoped 复合外键、V36 配置快照边界。
4. **版本差异必须显式标注。** 不得用“以后再说”解释同一版本内的互斥状态、字段或职责。
5. **下游文档不得重新定义上游概念。** Frontend 只能映射 TaskStatus，Backend API 只能暴露 TaskStatus，不能各自创建另一套任务状态机。

---

## 3. Canonical Contract Register

以下概念只能在指定文档中定义一次，其他文档必须引用：

| 概念 | 唯一定义位置 |
| --- | --- |
| V0.1/V1.0 范围 | Project Spec |
| `TaskStatus`、`TaskPhase`、`terminationReason` | Agent Engine Design |
| `AgentDecision` 协议 | Agent Engine Design |
| 预算计数规则 | Agent Engine Design |
| ToolRuntime 硬校验所有权 | Tool System Design |
| 文档解析/向量化/检索就绪状态 | RAG Design |
| embedding profile 和 Qdrant collection | RAG Design |
| vector ID 精确字节契约 | RAG Design |
| 表字段、外键和索引 | Data Model |
| HTTP/SSE wire contract | Backend API Design |
| UI 状态映射 | Frontend Design |
| 施工顺序 | Implementation Roadmap |

如果需要变更上述概念，应先改其唯一定义位置，再依次更新投影文档。

---

## 4. 当前 V0.1 已冻结的架构决策

截至本次对齐，V0.1 固定采用：

- 模块化单体，不拆微服务；
- 当前 V36 同步、进程内 AgentEngine 作为执行内核；
- `AgentTaskApplicationService -> TaskDispatcher -> TaskRunner -> AgentEngine` 的生命周期所有权；
- 一个固定 Chat Model Profile；
- 一个固定 Embedding Profile 和一个与其绑定的 Qdrant collection；
- `structured-token-v1` 确定性分块；
- 前置 RAG；
- 严格 JSON `CALL_TOOL / FINISH` 决策协议；
- `order_query` 和 `payment_log_query` 两个只读工具；
- 最小 Agent-知识库、Agent-工具绑定；
- PostgreSQL task/step/LLM/RAG/tool/event 基础 Trace；
- 数据库事件日志驱动的可恢复 SSE；
- 本地文件存储、有界线程池和单实例部署。

V0.1 明确不包含：RabbitMQ、Redis 任务状态、MinIO、PDF、语义分块、rerank、Hybrid Search、PolicyGuard、人工确认、HTTP/MCP 工具、Episode 持久化、Evaluation UI、conversation、多 Agent 和复杂 RBAC。

---

## 5. 设计变更流程

涉及跨文档概念的修改应按以下顺序完成：

1. 写清问题、约束和决策；
2. 修改 canonical contract；
3. 修改 Data Model；
4. 修改 Backend API；
5. 修改 Frontend 投影；
6. 修改 Roadmap；
7. 新增或调整 migration/代码；
8. 在 slice-doc 中记录实际实现和验收边界。

禁止先在某个 DTO、migration 或页面中创造新状态，再事后让其他文档被动追随。

---

## 6. Migration 与历史设计规则

- V1–V16 migration 保持不可变；
- 新表、约束、外键和字段使用 V17+；
- 旧规范中已经被新规范取代的内容应删除或明确标记为历史，不保留两个“当前推荐”；
- 生成目录中的文档副本不具有规范效力；
- `backend/target/`、`out/`、`.DS_Store` 等生成物不得作为设计或验收证据提交。

---

## 7. 审查门槛

任何宣称“V0.1 完成”的提交，必须证明同一条真实链路：

```text
Task 创建
-> 执行快照冻结
-> 真实 Qdrant 前置检索
-> 真实模型决策
-> ToolRuntime 执行两个只读工具
-> 真实模型最终生成
-> task/step/LLM/RAG/tool/event 全部可回查
-> SSE 可从 sequence 恢复
-> 最终答案和引用可验证
```

单元测试、mock provider 或某个子模块单独成功，均不能替代这一端到端完成标准。
