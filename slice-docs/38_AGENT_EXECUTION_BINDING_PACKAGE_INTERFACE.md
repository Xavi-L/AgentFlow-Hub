# V37 Agent 执行依赖绑定与快照接口包说明

> 状态：已按远程 `spec-docs` 的 M4B 规范实现并完成自动化及 PostgreSQL 验收。V37 建立
> owner-scoped 知识库/工具绑定和 task 创建前的执行快照解析；不创建 `agent_task`、Trace、
> TaskRunner、异步执行或 SSE。

## 1. 切片目标与路线位置

V37 对应远程路线图 M4B“执行依赖与绑定”。它把 Agent 可以使用的知识库、READY document
generation 和两个 V0.1 内置工具解析为不可变的 `agent-task-snapshot-v1`，供下一切片创建 task 时持久化。

路线边界为：

```text
M4A 规范冻结
  -> M4B/V37 绑定与 snapshot resolver（本切片）
  -> M4C agent_task + agent_task_event + TaskRunner
  -> M4D Trace
  -> M4E 将 V36 AgentEngine 接入持久任务
```

因此本切片完成后只能声明：

> 后端已经具备 owner-scoped Agent 执行依赖绑定，以及在短数据库事务内解析稳定执行快照的能力。

不能声明已有正式任务、持久 Trace、异步执行、RAG 调用、SSE 或完整 Agent 闭环。

## 2. Schema 契约

迁移固定为：

```text
backend/src/main/resources/db/migration/V17__create_agent_execution_bindings.sql
```

V17 不修改 V1–V16，新增：

- `agent_app (id,user_id)` unique，供绑定与后续 task 使用复合 owner 外键；
- `knowledge_chunk.chunk_strategy_version VARCHAR(64) NOT NULL`；
- 既有 chunk 回填 `structured-token-v1`；
- `agent_knowledge_binding`；
- `agent_tool_binding`。

知识库绑定同时用 `(agent_id,user_id)` 和 `(knowledge_base_id,user_id)` 复合外键证明双方 owner
一致，并以 `(agent_id,knowledge_base_id)` 唯一。工具绑定用 Agent 复合 owner 外键、tool 外键和
`(agent_id,tool_id)` 唯一约束。两表的 `priority` 均不得为负。

新建和更新 Agent 的 application validation 收紧为：

```text
maxToolCalls < maxDecisionTurns
```

数据库列仍沿用 `max_steps`，Java snapshot 对外语义映射为 `maxDecisionTurns`。V16 已经应用后不可
改写，因此本切片不修改旧 migration；旧的等值配置会在 snapshot resolver 和 V36 配置快照入口被拒绝。

新写入的 chunk 由 `DocumentProcessingTransactionService` 显式保存
`chunk_strategy_version=structured-token-v1`，不依赖伪造的客户端值。

## 3. Binding HTTP API

四个鉴权接口为：

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/api/v1/agents/{agentId}/knowledge-bases` | 返回当前知识库绑定 ID |
| PUT | `/api/v1/agents/{agentId}/knowledge-bases` | 全量替换知识库绑定 |
| GET | `/api/v1/agents/{agentId}/tools` | 返回当前启用工具绑定 ID |
| PUT | `/api/v1/agents/{agentId}/tools` | 全量替换工具绑定 |

请求只接受字符串编码的正 BIGINT：

```json
{"knowledgeBaseIds":["201"]}
```

```json
{"toolIds":["270000000000000001","280000000000000001"]}
```

严格反序列化会拒绝未知字段、缺失字段、非数组、数字型 ID、空白/零/负数和超出 BIGINT 的值。
知识库最多 50 个，工具最多 20 个；重复 ID 按首次出现顺序去重。空数组表示清空全部对应绑定。

PUT 在一个短事务内完成：

1. 以 `id + user_id + deleted_at IS NULL` 锁定 Agent；
2. 对完整、去重后的请求集合做绑定资格校验；
3. 任一 ID 不合法则以 `AGENT_BINDING_INVALID` 失败，旧绑定不变；
4. 删除旧集合并按请求顺序写入新集合。

知识库必须属于当前 owner、live 且 `ACTIVE`。工具只允许 live、`ACTIVE`、`BUILTIN` 的
`order_query` 和 `payment_log_query`；`report_generate` 明确不可绑定。`enabled=true`、priority、
时间戳均由服务端写入，客户端不能自报 `enabled/configOverride/permissionLevel`。

缺失、跨 owner 或已软删除 Agent 统一为 `COMMON_NOT_FOUND`。live `DISABLED` Agent 仍可编辑配置绑定，
但不能解析执行快照。

## 4. READY document generation

`AgentKnowledgeBindingMapper.selectReadyDocumentGenerations` 只选择：

- 绑定关系与 Agent owner 匹配；
- 知识库 live 且 `ACTIVE`；
- 文档 live 且 `parse_status=COMPLETED`；
- chunk 属于文档当前 `vector_generation`；
- 当前 generation 至少有一个 chunk；
- 当前 generation 的全部 chunk 均为 `vectorization_status=COMPLETED`；
- generation 内只有一个 chunk strategy version。

`DEGRADED` 或半向量化 generation 不会进入 snapshot，也不会退回旧 generation。所有绑定知识库均没有
READY 文档时，resolver 以 `RAG_KNOWLEDGE_NOT_READY` 失败。

## 5. Execution snapshot 契约

内部入口：

```java
AgentTaskExecutionSnapshot resolve(Long userId, Long agentId)
```

resolver 在 `REPEATABLE_READ` 短事务中锁定 owner/live Agent，并一次性解析：

- `snapshotVersion=agent-task-snapshot-v1`；
- Agent system prompt、ACTIVE 状态和预算；
- decision/prompt 协议版本及 application revision；
- 固定 Chat profile；
- 固定 Embedding profile、绑定知识库及 READY document generations；
- 当前允许的绑定工具、input schema hash、implementation version 和 timeout。

固定 profile 为：

```text
Chat profile:       openai-compatible-default
Embedding profile:  dashscope-te-v4-1024-cosine
Chunk strategy:     structured-token-v1
Tool version:       builtin-v1
```

Embedding profile 只接受当前 canonical 数据库组合：DashScope `text-embedding-v4`、chunk size 800、
overlap 120。工具 resolver 再次校验 exact `toolCode -> handler` allowlist、readonly、对象型 input schema、
正 timeout，以及受控 `implementationVersion`。

`inputSchemaHash` 对递归按 key 排序后的 canonical JSON 计算 SHA-256；对象 key 顺序不同不会改变 hash，
数组顺序保留。snapshot 及其列表不可变，`JsonNode` 在写入和读取时 defensive copy。普通 Agent、binding
或源对象修改不会反向改变已经返回的 snapshot。

该事务只做数据库读取和内存投影，不进行 LLM、Qdrant 或 Tool I/O。V37 还没有 `agent_task`，因此
resolver 只返回待持久化值；M4C 必须在 task 创建事务中把它写入 `execution_snapshot`。

## 6. 稳定错误边界

| 错误 | 条件 |
| --- | --- |
| `COMMON_NOT_FOUND` | owner/live Agent 不可见 |
| `AGENT_DISABLED` | live Agent 为 DISABLED，不能生成执行快照 |
| `AGENT_BINDING_INVALID` | 请求资源不合格，或 snapshot 依赖/profile/schema 不兼容 |
| `RAG_KNOWLEDGE_NOT_READY` | 所有绑定知识库都没有 READY document generation |

PUT 在删除旧集合之前校验完整新集合；校验或 insert 失败会回滚整个事务，不留下部分替换结果。

## 7. 主要实现文件

```text
backend/src/main/java/com/agentflow/agent/binding/controller/AgentBindingController.java
backend/src/main/java/com/agentflow/agent/binding/dto/*
backend/src/main/java/com/agentflow/agent/binding/model/*
backend/src/main/java/com/agentflow/agent/binding/repository/*
backend/src/main/java/com/agentflow/agent/binding/service/AgentBindingService.java
backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskExecutionSnapshot.java
backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskSnapshotResolver.java
backend/src/main/resources/db/migration/V17__create_agent_execution_bindings.sql
```

## 8. 验收证据

2026-09-02 使用 Microsoft OpenJDK 21.0.11 与显式 Mockito javaagent 实际执行：

```text
V37 聚焦测试：                 76/76 passed
完整 backend Maven suite：    503/503 passed
Failures: 0, Errors: 0, Skipped: 0
```

聚焦测试覆盖 strict binding JSON、owner/live/ACTIVE 校验、全量替换事务、两个工具 allowlist、
`report_generate` 拒绝、READY generation SQL、固定 profiles、canonical schema hash、snapshot 防御性复制、
无 READY 文档失败和严格预算关系。

另使用 PostgreSQL 18.4 空库实际启动 Spring Boot/Flyway：

```text
V1-V17:                 17/17 migrations applied
knowledge bindings:    1 valid row persisted
tool bindings:         order_query + payment_log_query persisted
cross-owner KB bind:   rejected by composite FK
chunk strategy column: NOT NULL
```

这属于本地 PostgreSQL 与自动化证据；没有调用真实 LLM、Embedding、Qdrant 或外部 provider。

## 9. 明确不做

- `agent_task`、`agent_task_event`、TaskRunner 或幂等 task 创建；
- `agent_step`、LLM/RAG/Tool Trace 关联或 Trace 查询；
- 将 V36 AgentEngine 改为读取本切片 snapshot；
- 异步线程池、RabbitMQ、取消、恢复或 lease；
- SSE、事件回放或前端 Trace 页面；
- 执行前 RAG、Qdrant 查询或 citation；
- `report_generate` 绑定、通用 HTTP/MCP 工具或 per-Agent config override；
- 动态 Chat/Embedding profile 表；
- 真实外部 provider 端到端验收。

## 面试问题与回答

### 问题 1：为什么绑定表同时保存 user_id？

回答：`user_id` 不是只为查询方便。`agent_knowledge_binding` 的两组复合外键在数据库层证明 Agent 和
知识库属于同一 owner，避免只靠 service 校验产生跨租户脏关系；后续 task 也能复用同样的 owner-scope
模式。工具是全局定义，所以工具绑定只需要 Agent 侧复合 owner 外键。

### 问题 2：为什么 PUT 是全量替换，并且要先校验全部 ID？

回答：全量替换让客户端状态和数据库集合有单一确定含义。service 先锁 Agent、校验完整去重集合，再
delete/insert；任一 ID 跨 owner、disabled、deleted 或 unsupported 都在 mutation 前失败，事务回滚避免
部分新旧集合混合。

### 问题 3：READY document generation 如何定义？

回答：文档必须 live、parse COMPLETED，当前 `vector_generation` 至少有一个 chunk，且所有 chunk 都已
vectorization COMPLETED、strategy version 唯一。只完成部分 chunk 或只有旧 generation 都不算 READY；
本切片不会静默回退。

### 问题 4：为什么 snapshot 既保存 schema 又保存 schema hash 和 implementation version？

回答：schema 是本次模型决策的输入契约；canonical SHA-256 让后续 ToolRuntime 可以判断定义是否发生
变化；`builtin-v1` 区分代码实现语义。V37 只完成解析和冻结对象，真正持久化及运行时漂移检查分别属于
后续 M4C/M4E。

### 问题 5：为什么 resolver 使用 REPEATABLE_READ，但仍强调短事务？

回答：Agent、bindings、READY generations 和工具定义必须来自同一个一致数据库视图，否则可能形成
混合版本 snapshot。事务中只执行 SQL 和内存 canonicalization，不包住 LLM、Qdrant 或 Tool I/O，因此
一致性不会演变成长事务外部调用。

### 问题 6：V37 与旧的同步 AgentEngine 是什么关系？

回答：V36 是非持久化最小循环，原先按执行开始时的全局 ACTIVE builtin 工具集合运行。远程新路线要求
先完成 binding、task/event 和 Trace，再在 M4E 接入 Engine；所以 V37 不提前改写 Engine 执行路径，只把
旧配置校验同步收紧到 canonical 的 `maxToolCalls < maxDecisionTurns`。
