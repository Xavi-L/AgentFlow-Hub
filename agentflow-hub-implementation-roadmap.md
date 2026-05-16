# AgentFlow Hub 开发实施顺序与里程碑边界

本文档用于沉淀 AgentFlow Hub 的工程实施顺序、里程碑边界、模块依赖、V0.1 最小闭环、V1.0 简历可用版本、验收标准和开发取舍。

注意：

> 本文档不是学习时间线，而是工程实现顺序。目标是让项目尽快形成可运行闭环，再逐步补齐工程化能力。

---

## 1. 总体实施原则

### 1.1 先闭环，后增强

优先跑通：

> 上传文档 -> 创建知识库 -> 创建 Agent -> 提交任务 -> RAG 检索 -> 工具调用 -> 流式输出 -> Trace 回放。

不要一开始就把所有管理页面、所有字段、所有异步队列、所有评测能力都做完。

### 1.2 纵向切片优先

优先做一条完整纵向链路，而不是横向铺所有 CRUD。

推荐第一条纵向链路：

```text
支付问题诊断 Agent
  -> 支付业务知识库
  -> order_query 工具
  -> payment_log_query 工具
  -> Agent 执行
  -> SSE 输出
  -> Trace 查看
```

### 1.3 后端主导，前端跟随

这个项目的核心竞争力是后端和 AI 工程化。

前端策略：

- 先做能调用 API 的简洁页面。
- 优先展示核心链路。
- 不在早期投入复杂视觉和交互。

### 1.4 从同步到异步

V0.1 可以同步或线程池执行，V1.0 再引入 RabbitMQ。

演进路径：

```text
同步执行 -> 线程池异步 -> RabbitMQ 异步任务
```

### 1.5 从内置工具到工具注册中心

V0.1 可以先写死内置工具。

V1.0 再补：

- `tool_definition`
- `agent_tool_binding`
- JSON Schema 校验
- 工具启停
- 工具测试

### 1.6 Trace 从第一天保留

即使 V0.1 功能很简，也要从一开始保存：

- task。
- step。
- RAG hit。
- tool call。
- LLM call。

原因：

- Trace 是项目最大亮点之一。
- 后面补页面和评测都依赖这些数据。
- 如果一开始不记录，后面会返工。

### 1.7 外部依赖通过接口隔离

从第一版就定义 Gateway：

- `LlmGateway`
- `VectorStoreGateway`
- `FileStorageGateway`

即使内部实现先简单，也不要让业务代码到处直接调用第三方 SDK。

---

## 2. 里程碑总览

| 里程碑 | 名称 | 定位 | 目标 |
| --- | --- | --- | --- |
| M0 | 工程骨架 | 项目可启动 | 后端、前端、基础依赖和代码规范搭好 |
| M1 | 基础后端 | 用户和通用能力 | 登录、统一响应、异常、数据库迁移 |
| M2 | 知识库最小链路 | RAG 入库可用 | 上传文档、chunk、embedding、Qdrant、检索测试 |
| M3 | 模拟业务与工具 | 工具调用可用 | 订单、日志 mock 数据和内置工具 |
| M4 | Agent 最小执行闭环 | V0.1 核心 | 前置 RAG、工具调用、最终回答、基础 Trace |
| M5 | SSE 对话演示 | 可演示 | 对话页实时展示 Agent 执行过程 |
| M6 | V0.1 收口 | 最小闭环稳定 | 可跑完整支付失败诊断 demo |
| M7 | V1.0 工程化补齐 | 简历可用 | 权限、异步、工具注册、Trace、评测、前端完整化 |
| M8 | V1.5 加分增强 | 加分项 | rerank、Hybrid Search、限流、压测、成本统计 |

---

## 3. M0：工程骨架

### 3.1 目标

让项目具备可持续开发的基础结构。

### 3.2 后端任务

- 创建 Spring Boot 3 项目。
- 使用 Java 21。
- 使用 Maven。
- 建立基础包结构：
  - `common`
  - `config`
  - `user`
  - `knowledge`
  - `rag`
  - `agent`
  - `tool`
  - `task`
  - `trace`
  - `evaluation`
  - `demo`
  - `infra`
- 接入 MyBatis-Plus。
- 接入 PostgreSQL。
- 接入 Flyway 作为数据库迁移工具。
- 创建基础健康检查接口。
- 配置 dev profile。

### 3.3 前端任务

- 创建 Vue 3 + TypeScript + Vite 项目。
- 接入 Element Plus。
- 接入 Vue Router。
- 接入 Pinia。
- 封装 Axios。
- 创建基础 Layout。
- 创建 Login 页面占位。
- 创建 Agent / Knowledge / Tasks 页面占位。

### 3.4 基础设施任务

创建 `docker-compose.yml`，至少包含：

- PostgreSQL。
- Redis。
- Qdrant。

V0.1 可以暂缓：

- RabbitMQ。
- MinIO。

V1.0 再补齐。

### 3.5 验收标准

- 后端可以启动。
- 前端可以启动。
- 前端能请求后端健康检查接口。
- PostgreSQL 可以连接。
- Flyway 能执行第一版 migration。
- README 能说明本地启动方式。

---

## 4. M1：基础后端能力

### 4.1 目标

完成用户、认证和通用后端基础能力。

### 4.2 后端任务

- 统一响应结构 `ApiResponse<T>`。
- 统一分页结构 `PageResult<T>`。
- 全局异常处理。
- 错误码体系。
- traceId filter。
- 用户表 `app_user`。
- 用户注册。
- 用户登录。
- JWT 签发和校验。
- 当前用户查询。
- 基础鉴权拦截。

### 4.3 前端任务

- 登录页。
- auth store。
- Axios 自动携带 token。
- 401 自动跳转登录。
- 主框架显示当前用户。
- 退出登录。

### 4.4 可简化项

V0.1 可以只预置一个 demo 用户。

但建议 M1 就把 JWT 做掉，因为后续所有资源归属都依赖当前用户。

### 4.5 验收标准

- 用户可以登录。
- 登录后能进入主框架。
- 后端接口能识别当前用户。
- 未登录访问业务接口返回 401。

---

## 5. M2：知识库最小链路

### 5.1 目标

完成最小 RAG 入库和检索能力。

### 5.2 后端任务

数据库表：

- `knowledge_base`
- `knowledge_document`
- `knowledge_chunk`

接口：

- 创建知识库。
- 查询知识库列表。
- 上传文档。
- 查询文档列表。
- 查询 chunk 列表。
- 检索测试。

功能：

- 支持 `.txt`。
- 支持 `.md`。
- 文档内容读取。
- 文本清洗。
- chunk 切分。
- token 估算。
- 调用 embedding。
- 写入 Qdrant。
- 查询 Qdrant。
- 根据 chunkId 回查 PostgreSQL。

### 5.3 前端任务

- 知识库列表页。
- 创建知识库弹窗。
- 文档上传页。
- 文档状态展示。
- Chunk 查看页。
- 检索测试页。

### 5.4 可简化项

V0.1 可以：

- 文档同步处理。
- 文件先存在本地临时目录。
- 暂不接 MinIO。
- 暂不支持 PDF。
- 暂不做 rerank。
- 暂不做 Hybrid Search。

### 5.5 验收标准

用户可以：

1. 创建知识库。
2. 上传一份 Markdown 支付规则文档。
3. 系统生成 chunks。
4. chunks 写入 PostgreSQL。
5. embeddings 写入 Qdrant。
6. 在检索测试页输入“支付超时错误码怎么处理”。
7. 页面返回相关 chunk、score、来源文档。

---

## 6. M3：模拟业务与内置工具

### 6.1 目标

让 Agent 能调用真实后端工具，而不是纯 prompt 编造。

### 6.2 后端任务

数据库表：

- `mock_order`
- `mock_payment_log`
- `mock_ticket`，可 V1.0 再做
- `tool_definition`
- `tool_call_log`

功能：

- seed demo 订单数据。
- seed demo 支付日志。
- 初始化内置工具定义。
- 实现 `ToolRuntime`。
- 实现 JSON Schema 基础校验。
- 实现 `order_query`。
- 实现 `payment_log_query`。
- 实现 `report_generate`。
- 保存工具调用日志。

### 6.3 前端任务

V0.1：

- 工具列表只读。
- 工具详情抽屉。

V1.0：

- 工具测试面板。
- 工具启停。
- 查看工具调用结果。

### 6.4 可简化项

V0.1 可以：

- 不做完整工具 CRUD。
- 不做 Agent 工具绑定表。
- 默认 Agent 拥有全部内置工具。
- 参数校验只做基础 required/type。

### 6.5 验收标准

后端可以独立测试：

- 输入 `order_1024`，`order_query` 返回支付失败状态和错误码。
- 输入 `order_1024` 或 `E_PAY_TIMEOUT`，`payment_log_query` 返回相关日志。
- 每次调用都写入 `tool_call_log`。

---

## 7. M4：Agent 最小执行闭环

### 7.1 目标

跑通项目最重要的 V0.1 核心链路。

### 7.2 后端任务

数据库表：

- `agent_app`
- `agent_task`
- `agent_step`
- `llm_call_log`
- `rag_retrieval_log`
- `rag_retrieval_hit`

核心类：

- `AgentEngine`
- `AgentExecutionContext`
- `AgentPromptBuilder`
- `AgentDecisionParser`
- `BudgetGuard`
- `LlmGateway`
- `RagService`
- `ToolRuntime`

功能：

- 创建 Agent。
- 配置 system prompt。
- 配置模型。
- 绑定一个知识库，可以先用字段或默认值。
- 创建 Agent 任务。
- 前置 RAG 检索。
- Thinking Prompt。
- 模型返回工具调用。
- 执行工具。
- 工具结果写入 observation。
- 最终回答生成。
- 保存 task、step、LLM log、RAG log、tool log。

### 7.3 前端任务

- Agent 列表页。
- 创建 Agent。
- Agent 对话页基础版。
- 任务结果页或 Trace 基础页。

### 7.4 可简化项

V0.1 可以：

- 不做多轮 conversation。
- 不做 Prompt 版本。
- 不做 Agent 与工具绑定表。
- 不做 RabbitMQ。
- 不做复杂取消。
- Thinking 可以先用 JSON 输出协议，不强制模型原生 tool calling。

### 7.5 验收标准

输入：

```text
帮我分析 order_1024 支付失败的原因，并给出处理建议。
```

系统完成：

1. 检索支付知识库。
2. 调用 `order_query`。
3. 调用 `payment_log_query`。
4. 生成原因分析。
5. 给出处理建议。
6. 保存完整基础 trace。

---

## 8. M5：SSE 对话演示

### 8.1 目标

让项目从“后端能跑”变成“前端能演示”。

### 8.2 后端任务

数据库表：

- `agent_task_event`

功能：

- `TaskEventPublisher`。
- SSE endpoint。
- 任务事件持久化。
- 事件序号 `sequenceNo`。
- `TASK_STARTED`。
- `RAG_STARTED` / `RAG_FINISHED`。
- `LLM_STARTED` / `LLM_FINISHED`。
- `TOOL_STARTED` / `TOOL_FINISHED`。
- `TOKEN_DELTA`。
- `TASK_COMPLETED` / `TASK_FAILED`。

### 8.3 前端任务

- 使用 `@microsoft/fetch-event-source`。
- 提交任务后订阅 SSE。
- 对话页显示流式答案。
- 右侧 timeline 显示执行事件。
- 失败时展示 errorCode/errorMessage。
- 完成后提供查看 Trace 按钮。

### 8.4 可简化项

V0.1 可以：

- SSE 断线后只提示刷新。
- 不做 Last-Event-ID。
- 不做复杂事件补偿。

### 8.5 验收标准

用户在对话页能实时看到：

- 正在检索知识库。
- 命中几个文档片段。
- 正在调用订单查询工具。
- 正在调用日志查询工具。
- 最终答案逐字或分块输出。

---

## 9. M6：V0.1 收口

### 9.1 目标

形成一个稳定、可演示、可录屏的最小版本。

### 9.2 必须完成

- 登录。
- 创建知识库。
- 上传 `.md` 或 `.txt`。
- 检索测试。
- 创建 Agent。
- 内置工具可用。
- Agent 对话页可提交任务。
- SSE 展示执行过程。
- Trace 基础页可查看：
  - task。
  - steps。
  - RAG hits。
  - tool calls。
  - LLM calls。
  - final answer。

### 9.3 演示脚本

准备固定演示数据：

1. 支付错误码知识文档。
2. `order_1024` 订单。
3. `E_PAY_TIMEOUT` 支付日志。

演示问题：

```text
帮我分析 order_1024 支付失败的原因，并给出处理建议。
```

期望结果：

- 知识库命中 `E_PAY_TIMEOUT` 说明。
- 订单工具返回 `PAY_FAILED`。
- 日志工具返回网关超时。
- 最终回答指出支付网关超时，并给出检查渠道状态、确认是否扣款、引导重试或退款工单建议。

### 9.4 V0.1 不做

- RabbitMQ。
- MinIO。
- PDF。
- 完整评测。
- Prompt 版本。
- 工具 CRUD。
- HTTP/MCP 工具。
- 复杂权限。
- Rerank。
- Hybrid Search。

---

## 10. M7：V1.0 工程化补齐

### 10.1 目标

把 V0.1 从“可演示 demo”升级为“简历可用项目”。

### 10.2 认证与权限

补齐：

- 用户注册。
- 用户资源隔离。
- 管理员角色。
- 工具管理权限。
- 所有查询按 userId 校验归属。

### 10.3 知识库

补齐：

- PDF 解析。
- MinIO 文件存储。
- 文档异步解析。
- 文档重新解析。
- 删除文档同步删除 chunks 和 vectors。
- 文档失败原因展示。
- chunk metadata 更完整。

### 10.4 异步任务

补齐：

- RabbitMQ。
- 文档解析队列。
- Agent 执行队列。
- 消息确认。
- 失败重试。
- 死信队列，可选。
- 任务幂等。

### 10.5 Agent

补齐：

- `agent_knowledge_binding`。
- `agent_tool_binding`。
- Prompt 版本。
- conversation 和 message。
- 任务取消。
- 最大 token 预算。
- 重复工具调用检测。
- 更完整错误码。

### 10.6 工具

补齐：

- 工具列表。
- 工具详情。
- 工具启停。
- 工具测试。
- 完整 JSON Schema 校验。
- `ticket_query`。
- `knowledge_search`。

### 10.7 Trace

补齐：

- 完整 Trace 聚合 API。
- Trace 页面 tabs。
- Prompt 和 response 折叠展示。
- RAG contentSnapshot 展示。
- Tool arguments/result 展示。
- Event 回放。

### 10.8 评测

补齐轻量版：

- eval dataset。
- eval case。
- eval run。
- eval result。
- 人工 passed 标记。
- RAG 命中文档判断。
- Agent 是否调用预期工具。

### 10.9 前端

补齐：

- Agent 编辑 tabs。
- Knowledge chunks 页面。
- Tool 管理页。
- Task 历史页。
- Trace 详情页。
- Evaluation 页面。
- 更完整空状态和错误状态。

### 10.10 V1.0 验收标准

V1.0 必须做到：

1. 新用户可以登录并拥有自己的资源。
2. 用户可以创建知识库并上传 `.txt`、`.md`、`.pdf`。
3. 文档解析、chunk、embedding、入库异步执行。
4. 用户可以创建 Agent，绑定知识库和工具。
5. 用户可以在对话页提交任务并实时看到执行过程。
6. Agent 可以完成支付失败诊断示例。
7. Trace 页面可以完整回放任务链路。
8. 工具调用、RAG 召回、LLM 调用都有日志。
9. 用户可以创建评测集并运行轻量评测。
10. Docker Compose 可以启动核心依赖。

---

## 11. M8：V1.5 加分增强

### 11.1 目标

强化项目亮点，但不影响 V1.0 主线。

### 11.2 推荐增强

- Rerank。
- Hybrid Search。
- Redis 用户级限流。
- token 成本统计页面。
- Prompt 版本对比。
- 工具调用失败率统计。
- RabbitMQ 死信队列。
- SSE 断线恢复。
- 文档解析自动重试。
- 简单压测报告。
- README 架构图和演示 GIF。

### 11.3 不建议优先做

- Kubernetes。
- 多 Agent。
- MCP 完整接入。
- 拖拽工作流。
- 插件市场。
- 复杂 BI 仪表盘。

---

## 12. 推荐实现顺序清单

按照真实编码顺序，推荐：

1. 建后端项目骨架。
2. 建前端项目骨架。
3. 写 Docker Compose：PostgreSQL、Redis、Qdrant。
4. 接入 Flyway 和 MyBatis-Plus。
5. 写 common：统一响应、异常、traceId。
6. 写 user/auth。
7. 写 knowledge base CRUD。
8. 写 document upload。
9. 写 text/markdown parser。
10. 写 chunker 和 token estimator。
11. 写 embedding gateway。
12. 写 Qdrant gateway。
13. 写 retrieve-test。
14. 写 demo mock order/payment log。
15. 写 tool runtime 和内置工具。
16. 写 agent app CRUD。
17. 写 LLM gateway。
18. 写 AgentEngine 最小循环。
19. 写 trace 基础日志。
20. 写 task 创建和查询。
21. 写 SSE 事件推送。
22. 写 Agent 对话页。
23. 写 Trace 基础页。
24. 收口 V0.1 demo。
25. 引入 MinIO。
26. 引入 RabbitMQ。
27. 补 Agent 绑定、Prompt 版本、conversation。
28. 补工具管理页。
29. 补评测模块。
30. 收口 V1.0。

---

## 13. Mock 与替换策略

### 13.1 可以先 mock 的能力

| 能力 | V0.1 做法 | V1.0 替换 |
| --- | --- | --- |
| 用户 | demo 用户 | JWT + 用户资源隔离 |
| 文件存储 | 本地文件 | MinIO |
| 文档任务 | 同步处理 | RabbitMQ 异步 |
| Agent 任务 | 线程池 | RabbitMQ worker |
| 工具绑定 | 默认内置工具 | `agent_tool_binding` |
| 知识库绑定 | Agent 单字段或默认 | `agent_knowledge_binding` |
| Prompt 版本 | 只存当前 prompt | `agent_prompt_version` |
| PDF | 暂不支持 | PDFBox 解析 |
| 评测 | 暂不支持 | eval 表和页面 |

### 13.2 不建议 mock 的能力

这些最好从一开始就按真实方式做：

- PostgreSQL 表结构。
- Qdrant 向量检索。
- LLM Gateway。
- 基础 Trace 记录。
- ToolRuntime 调用链路。
- SSE 事件模型。

原因：

- 它们是项目核心亮点。
- 后面替换成本高。
- 面试时要展示真实链路。

---

## 14. 每个阶段的可演示物

### M2 后

可演示：

- 上传知识库文档。
- 查看 chunks。
- 运行检索测试。

### M3 后

可演示：

- 调用订单查询工具。
- 调用支付日志查询工具。
- 查看工具调用日志。

### M4 后

可演示：

- 后端执行完整 Agent 任务。
- 查看基础 trace 数据。

### M5 后

可演示：

- 前端对话页实时显示 Agent 执行。

### M6 后

可演示：

- 完整 V0.1 支付失败诊断闭环。

### M7 后

可演示：

- 接近真实平台的 V1.0 完整系统。

---

## 15. 风险控制

### 15.1 最大风险：项目范围膨胀

控制方式：

- V0.1 只做支付失败诊断一个场景。
- 工具只做 3 个必需工具。
- 前端只做演示所需页面。
- 不做 MCP、多 Agent、拖拽工作流。

### 15.2 风险：LLM 输出不稳定

控制方式：

- Thinking 阶段要求 JSON 或 tool calling。
- parse 失败最多重试一次。
- 工具参数必须校验。
- Trace 记录 prompt 和 response。

### 15.3 风险：RAG 召回效果差

控制方式：

- 准备高质量演示文档。
- 先做 retrieve-test 页面。
- 调整 chunkSize、topK、threshold。
- V1.5 再加 rerank 和 Hybrid Search。

### 15.4 风险：异步和 SSE 调试复杂

控制方式：

- V0.1 先用线程池。
- SSE 事件先少而清晰。
- V1.0 再接 RabbitMQ。
- Trace 作为兜底回放。

### 15.5 风险：前端耗时过多

控制方式：

- 使用 Element Plus。
- 优先表格、表单、tabs。
- 不做复杂视觉。
- Trace 页面先用 tabs，后续再优化 timeline。

---

## 16. 代码提交建议

每个里程碑可以拆成清晰提交：

```text
feat: bootstrap backend and frontend
feat: add auth and user module
feat: add knowledge base and document ingestion
feat: integrate qdrant vector retrieval
feat: add builtin tool runtime
feat: add agent execution engine
feat: add task events and sse streaming
feat: add trace detail page
feat: add rabbitmq async workers
feat: add evaluation module
```

这样后续写简历或回顾项目时，提交历史也能体现工程推进过程。

---

## 17. README 阶段性要求

README 不要等最后才写。

### V0.1 README 至少包含

- 项目介绍。
- 技术栈。
- 本地启动方式。
- 环境变量说明。
- 演示账号。
- 演示问题。
- V0.1 功能清单。
- 已知限制。

### V1.0 README 补充

- 架构图。
- 模块说明。
- 数据流图。
- Agent 执行流程图。
- RAG 流程图。
- 工具系统说明。
- API 文档链接。
- 演示截图。
- 后续规划。

---

## 18. V0.1 最终验收脚本

### 18.1 准备数据

1. 登录 demo 用户。
2. 创建知识库：支付业务知识库。
3. 上传文档：支付错误码处理指南。
4. 创建 Agent：支付问题诊断助手。
5. 初始化订单和支付日志 demo 数据。

### 18.2 执行任务

输入：

```text
帮我分析 order_1024 支付失败的原因，并给出处理建议。
```

### 18.3 期望执行过程

时间线应包含：

```text
TASK_STARTED
RAG_STARTED
RAG_FINISHED
LLM_STARTED
TOOL_STARTED order_query
TOOL_FINISHED order_query
LLM_STARTED
TOOL_STARTED payment_log_query
TOOL_FINISHED payment_log_query
LLM_STARTED
TOKEN_DELTA
TASK_COMPLETED
```

### 18.4 期望最终答案

最终答案应包含：

- 明确结论：支付失败主要因为支付网关超时。
- 证据 1：知识库中 `E_PAY_TIMEOUT` 的说明。
- 证据 2：订单状态为 `PAY_FAILED`。
- 证据 3：支付日志出现 gateway timeout。
- 建议 1：检查支付渠道状态。
- 建议 2：确认用户是否扣款。
- 建议 3：未扣款则引导重新支付。
- 建议 4：已扣款则创建退款或人工处理工单。

### 18.5 Trace 验收

Trace 页面应能看到：

- 用户输入。
- RAG 命中 chunk。
- 工具调用入参和出参。
- LLM prompt 和 response。
- 每个 step 耗时。
- 最终答案。

---

## 19. V1.0 最终验收标准

V1.0 可以认为完成，当满足：

- `docker compose up` 能启动主要依赖。
- 后端和前端本地可运行。
- 新用户可以登录。
- 用户资源互相隔离。
- 知识库支持 `.txt`、`.md`、`.pdf`。
- 文档解析异步执行。
- Agent 可以绑定多个知识库和多个工具。
- Agent 对话页支持 SSE。
- Trace 页面完整可用。
- 工具系统支持启停、测试和日志。
- 评测模块可以创建 case 并运行。
- README 能让别人按步骤跑通 demo。

---

## 20. 当前不进入实施主线的内容

V1.0 前不做：

- Kubernetes。
- 多 Agent 协作。
- MCP 完整接入。
- HTTP 工具开放平台。
- 拖拽工作流。
- 插件市场。
- 多租户组织空间。
- 复杂 BI 仪表盘。
- 模型微调。

原因：

> 这些能力会显著增加实现复杂度，但不会比当前核心闭环更直接地提升实习求职转化率。

---

## 21. 学习与开发策略

建议采用：

> 边做边学，但在每个里程碑开始前先做最小必要预习。

不建议先把所有技术栈系统学完再开工。

原因：

- 技术栈太多，完整学完会拖慢启动。
- 没有项目问题驱动时，学习很容易停留在教程层面。
- 面试更看重能否把技术用于真实模块，而不是是否看完某门课程。
- AgentFlow Hub 的很多能力需要在实现中理解，例如 SSE、RAG 召回质量、工具调用失败处理、Trace 设计。

也不建议完全不预习就直接写。

原因：

- Spring Security、MyBatis-Plus、Qdrant、RabbitMQ、SSE 等都有基本使用门槛。
- 完全边查边写会导致代码结构混乱。

推荐策略：

```text
里程碑开始前：用 0.5 到 1 天快速过一遍最小必要知识
里程碑实现中：围绕当前模块边查边做
里程碑完成后：回头补体系化理解和面试表达
```

### 21.1 每个里程碑的预习范围

| 里程碑 | 开始前只需要预习 |
| --- | --- |
| M0 | Spring Boot 项目结构、Vite 项目结构、Docker Compose 基础 |
| M1 | JWT、Spring Security 基础、统一异常处理 |
| M2 | 文件上传、Markdown/TXT 解析、chunk 思路、Qdrant 基础、embedding API |
| M3 | JSON Schema 基础、Java 策略模式/接口路由、工具调用日志 |
| M4 | Spring AI ChatClient/Tool Calling 基础、Agent 状态机、Prompt JSON 输出 |
| M5 | SSE、fetch-event-source、前端流式渲染 |
| M7 | RabbitMQ、MinIO、PDFBox、权限归属校验、评测表设计 |

### 21.2 学习深度控制

每个技术先学到能完成当前模块即可。

例如：

- 学 Redis 时，先掌握缓存、限流、运行状态，不需要先学集群和哨兵。
- 学 RabbitMQ 时，先掌握队列、ack、重试、死信，不需要先学所有 exchange 模式。
- 学 Qdrant 时，先掌握 collection、upsert、search、payload filter，不需要先研究全部索引参数。
- 学 Spring AI 时，先掌握 chat、stream、embedding、tool calling，不需要通读源码。
- 学前端时，先掌握页面、表格、表单、SSE 展示，不需要深入组件库封装。

### 21.3 推荐工作节奏

每个模块遵循：

```text
先看官方文档最小示例
-> 在项目里实现一个最小版本
-> 跑通接口或页面
-> 补错误处理和日志
-> 补文档和面试表达
```

### 21.4 判断是否该继续学还是继续做

如果出现这些情况，继续做：

- 已经知道当前模块大概怎么实现。
- 能用简单方式跑通最小功能。
- 卡点可以通过查文档解决。

如果出现这些情况，暂停补基础：

- 不理解当前技术的核心概念。
- 代码只能复制粘贴，无法解释。
- 出错后完全不知道该查哪里。
- 实现会影响后续架构，例如认证、事务、异步任务、向量存储。

### 21.5 面向求职的最终目标

学习不是为了“过完技术栈”，而是为了能讲清楚：

- 我为什么在这个模块使用这个技术。
- 它解决了什么工程问题。
- 我遇到过什么失败或限制。
- 我如何做状态、日志、错误处理和后续扩展。

因此，本项目的最佳策略是：

> 用项目牵引学习，用文档沉淀理解，用演示闭环证明能力。
