# AgentFlow Hub 设计文档索引与规范优先级

> 文档状态：**NORMATIVE**  
> 最近审查基线：`main@f276549`（V36 AgentEngine core loop）  
> 版本规划与目录对齐：2026-09-12，基于 `main@58b6145`；不重写历史实现或验收记录。\
> V0.2/V0.3 施工契约补充：2026-09-12，核对基线 `main@dce66e3365f45f2492d1b10d7c03118ac1564514`；仅定义待实现约束。\
> 适用范围：`spec-docs/`、后续设计变更、V0.1–V0.3 施工与 V1.x 演进

本目录保存 AgentFlow Hub 的产品边界、领域模型和工程设计。它不是若干互相独立的专题笔记集合，而是一套有明确权威来源和覆盖关系的系统规范。

任何实现、migration、API、前端状态或 slice 说明与本目录冲突时，必须先判断冲突属于合理版本差异、实现切片尚未完成，还是规范漂移；不得同时保留两种互斥解释。

---

## 1. 文档分类

### 1.1 规范性文档（Normative）

规范性文档定义系统必须遵守的契约：

| 文档 | 权威范围 |
| --- | --- |
| `agentflow-hub-project-spec.md` | 项目定位、V0.1 完成标准及 V0.2/V0.3/V1.x 版本边界 |
| `agentflow-hub-agent-engine-design.md` | 任务生命周期、执行阶段、AgentEngine、预算、取消、失败和事件语义 |
| `agentflow-hub-rag-design.md` | 文档入库、分块、embedding profile、向量身份、检索、citation 和重建语义 |
| `agentflow-hub-tool-system-design.md` | ToolRuntime、工具定义、绑定、参数校验、超时、重试和工具结果语义 |
| `agentflow-hub-data-model.md` | 目标 PostgreSQL/Qdrant 数据形状、外键、约束、索引和快照字段 |
| `agentflow-hub-backend-api-design.md` | HTTP、SSE、DTO、错误码和内部接口投影 |
| `agentflow-hub-frontend-design.md` | 前端页面边界、后端状态映射、SSE 恢复和展示规则 |
| `agentflow-hub-implementation-roadmap.md` | V0.1 施工与验收记录、V0.2/V0.3/V1.x 演进顺序和验收门槛 |

### 1.2 规划性文档（Future-Normative）

`agentflow-hub-agent-harness-design.md` 描述 V0.3 的动态 Episode 与轻量 Evaluation，以及 V1.5/V2.0 的 Tool Policy、自动配置对比和受控 MCP 等后续演进。版本归属以 Project Spec 为准；规划条目不表示已经实现，未来章节不得反向扩大 V0.1 范围。

### 1.3 信息性文档（Informative）与切片施工契约

`agent-backend-ai-learning-guide.md` 是学习索引，不是产品需求、架构决策或里程碑验收依据。它不能为项目新增模块、表、依赖或完成标准。

`V0.1-slice-docs/` 保存 V0.1 阶段及后续维护切片的实现契约与验收证据。目录归属不等于 tag 内容，是否随版本发布仍以提交、tag 和 release-docs 为准。

后续切片目录统一命名为 `V0.2-slice-docs/`、`V0.3-slice-docs/`，现已分别建立第一份施工契约。规划契约、实际实现和已执行验收必须分别标明；目录或文档存在本身不构成完成证据。这些文档不单独重定义长期产品架构。若 slice 中出现比旧规范更精确且已经由代码和 migration 固化的契约，应通过新的设计变更将该契约回写到本目录，而不是让两套定义长期并存。

| 待实现施工契约 | 切片与验收范围 |
| --- | --- |
| [V0.2：受控重启收尾与中断加固](../V0.2-slice-docs/01_TASK_RECOVERY_AND_INTERRUPTION_PACKAGE_INTERFACE.md) | A：进程互斥、启动准入、遗留 QUEUED/RUNNING 原子收尾与事实完整性（A01–A17）；B：不合作调用、迟到结果、资源边界与有限终态持久化重试（B01–B10） |
| [V0.3：配置版本、评测关联与回归基线](../V0.3-slice-docs/01_CONFIG_VERSION_AND_EVALUATION_PACKAGE_INTERFACE.md) | A：不可变配置、实际快照关联、幂等兼容、CLI 逐例记账（A01–A13）；B：固定材料与标注、分层指标、比较条件、动态 Episode 与真实基线（B01–B15） |

两份文件是 Project Spec 已定义版本范围内的施工输入，明确列出已核对的当前行为、待实现扩展、接口/数据约束、验收矩阵和不做事项。实施者先按第 5 节将本次需要的扩展同步到对应 canonical/Data/API/Frontend 投影，再编写 migration 和代码；不能因为旧版本没有这些字段而省略新约束，也不能把切片的未来状态写成现行已交付能力。

施工按 `V0.2-A → V0.2-B → V0.3-A → V0.3-B` 分片推进；文档同时存在不授权一口气扩大施工范围。V0.3-A 的基础依赖是 V0.2-A，涉及迟到结果和运行期持久化故障的场景还须核对 V0.2-B。V0.2 的 retention、reconciliation、完整 Compose/容量验证仍另立契约，不因本文 A/B 完成而宣称全版本发布。

实现时尤其注意：恢复模式只控制是否执行启动收尾，**不是绕过进程互斥的开关**；无论是否自动恢复，参与同一受控执行域的新版执行进程都必须遵守同一个进程锁协议。评测的 `resume` 只恢复协调记录、核对原请求和观察原 task，不授权重新执行已失败的 task。缺少历史配置、调用事实或评测材料时保留未知，不补造证据。

---

## 2. 冲突时的优先级

发生冲突时，按以下顺序判断：

```text
已接受且仍生效的 canonical contract / ADR
> 本目录对应专题的规范性文档
> 数据模型与 API/Frontend 投影
> Implementation Roadmap
> 对应版本切片目录中的实现说明
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
| V0.1/V0.2/V0.3/V1.x 范围 | Project Spec |
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
