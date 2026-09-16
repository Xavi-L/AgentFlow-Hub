# AgentFlow Hub Architecture Atlas

## Scope and baseline

已依次完成 **01 · System Context**、**02 · Backend Runtime Architecture**（均为 `architecture`）及 **03 · End-to-End Task Workflow**（`workflow` v2）。图 03 当前交付的 artifact、browser evidence 与截图 visual review 均通过，旧失败记录保留为历史。图 04 已完成 Task Creation and Dispatch Sequence，图 05 已完成 Agent Execution Loop（workflow v2）；图 06 RAG Data Flow 已完成修复包应用，artifact、browser evidence 与截图 visual review 均通过，旧失败证据保留；图 07 Tool Invocation Sequence 已完成 artifact、browser 和视觉验收；图 08 Task Lifecycle 已完成三层验收；图 09 派发与并发控制已完成三层验收；图 10 已完成：10A原产物保持不变，10B协调布线修复经原版artifact、browser与实际视觉复核通过，整体complete；图 11 已施工但原版compiler验证失败，保持incomplete；图 12–15 与最终独立 Architecture Audit 尚未施工。

- 图 01 交付日期：2026-09-14；图 02 于 2026-09-14 开始检查、2026-09-15 完成交付（Asia/Shanghai）。
- 图 01/02 施工基线分支：`main`；HEAD：`ba04adc5308222635e6eb1a86a0981da8ede408c`（`docs: add Archify architecture atlas construction guide`）。未 fetch、未改变 Git 基线；此处指施工时本地 main。
- 工作树：**dirty**。施工前已有 V0.3 配置/评估/episode 相关改动、`application-dev.yml`、规格文档和未跟踪的 `.agents/` 等内容。本图按实际文件检查；没有把这些改动视为 HEAD 已提交行为。
- Archify：仓库本地 `.agents/skills/archify/SKILL.md`，metadata version `2.17`；`showcase` 质量，中文 Viewer，静态默认视图。
- 未运行外部业务 runtime：本轮未启动 Spring Boot、PostgreSQL、Redis、Qdrant、Chat、DashScope，也未执行付费请求或真实服务验收。图展示代码/配置支持的逻辑关系，不证明部署中的连通性、生产拓扑或 provider 行为。
- 图 06 于 2026-09-16 基于 `main@0320892af12fdafb1b5ae89e9135b987dbd1630a` 的 dirty working tree 完成修复与交付；使用当前本地 Archify 2.17（package 2.17.0-dev.1）。仅修改图 06 布局与验收记录，图 01–05 原产物及既有业务代码改动保留；未运行真实外部 provider。各图基线、历史失败与分层验收分别见对应章节。

- 图 07 于 2026-09-16 基于同一 `main@0320892` dirty working tree 完成；7 个参与者、22 条消息，artifact / browser / visual review 分别通过，旧失败证据保留。图 07 当轮仅修改该图与本 Atlas，当时尚未开始图 08。

## Diagram index

| ID | Diagram | Type | Question | Status |
| --- | --- | --- | --- | --- |
| 01 | System Context | architecture | 用户、浏览器前端、后端与哪些存储和外部服务交互？ | 完成：validate / deliver / browser / visual review 通过 |
| 02 | Backend Runtime Architecture | architecture | Spring Boot 后端内部的主要运行时模块如何分工和连接？ | 完成：validate / deliver / browser / visual review 通过 |
| 03 | End-to-End Task Workflow | workflow v2 | 提交 task 后，创建到最终答案如何推进，关键 gate 在哪里？ | 完成：showcase validate / deliver 9/9，0 errors / 0 warnings；四尺寸 browser / 明暗截图 visual review 通过 |
| 04 | Task Creation and Dispatch Sequence | sequence | 创建请求按何顺序处理，何时跨越 COMMIT 与 async dispatch？ | 完成：showcase validate / deliver 9/9，0 errors / 0 warnings；四尺寸 browser / 双主题截图 visual review 通过 |
| 05 | Agent Execution Loop | workflow v2 | task 进入 runtime 后，如何完成预检索、decision/tool 回环与独立 final generation？ | 完成：showcase validate / deliver 9/9，0 errors / 0 warnings；四尺寸 browser / 双主题截图 visual review 通过 |
| 06 | RAG Data Flow | dataflow | 文档从上传到被一次Agent task检索并进入模型上下文，数据经历什么路径？ | complete：9/9 showcase，composition 0 errors / 0 warnings；四视口 browser 与双主题截图 visual review passed |
| 07 | Tool Invocation Sequence | sequence | 模型决定调用工具后，定义、校验、执行、结果与后续模型调用如何串起来？ | complete：9/9 showcase，0 errors / 0 warnings；四视口 browser 与双主题截图 visual review passed |
| 08 | Task Lifecycle | lifecycle | Task 的持久状态是什么，哪些触发与 guard 允许转换？ | complete：9/9 showcase，0 errors / 0 warnings；四视口 browser 与双主题截图 visual review passed |
| 09 | Dispatch, Thread Pool and Concurrency Control | workflow v2 | 已提交任务如何经线程池、队列、claim和实际工作许可形成背压？ | complete：9/9 showcase，0 errors / 0 warnings；四视口browser和双主题visual review passed |
| 10 | Failure, Cancellation and Settlement Lifecycle | lifecycle + workflow v2 | 执行中断与终态保存失败分别如何收敛？ | complete：10A原件不变；10B协调布线修复后9/9、0 errors / 0 warnings，四视口browser与双主题visual review passed |
| 11 | Restart Recovery and Controlled Cutover | workflow v2 | 受控重启如何识别遗留任务、收尾并开放准入？ | incomplete：两轮定向布局修复后原版compiler内部异常，未deliver/browser/visual review |

## Evidence convention

- **CODE_CONFIRMED**：当前生产实现、migration、配置或所锁定依赖实现直接支持；仅说明静态事实，不等于运行成功。
- **TEST_CONFIRMED**：测试/受控验收中的明确断言及其适用边界。仅阅读测试时写明“断言已检查，本轮未执行”；不得写成新的 PASSED。
- **DOC_DECLARED**：README、spec、切片的声明，尚未由本次代码追踪或运行证据升级。
- **UNKNOWN**：当前证据不能确定，保留问题及验证方法。

## 图 01 施工前工作记录

本节在 Typed JSON 生成前形成，以下事实限定在 System Context。

### CODE_CONFIRMED nodes

| 稳定 ID | 确认节点 | 事实与条件 |
| --- | --- | --- |
| context-user | 用户 / Browser | 用户在浏览器中操作 UI；不是后端可信身份来源。 |
| context-vue | Vue Frontend | Vue 挂载在浏览器中；开发期 Vite 5173 提供前端并代理 `/api`，不是另一个业务后端。 |
| context-backend | Spring Boot Backend | JVM 中的 API、Agent/RAG、内置工具及健康检查；此图不展开内部模块/线程池。 |
| context-postgres | PostgreSQL | JDBC/MyBatis/Flyway；身份、Agent、知识文档元数据、task/event/config 等数据，含共享演示订单/支付表。 |
| context-files | 文档文件存储 | `LocalDocumentStorage` 对后端可见本地目录做文件 I/O；不是对象存储服务。 |
| context-qdrant | Qdrant | `remote` 向量模式的 REST adapter；Compose 仅提供它及命名卷，不包含整个系统部署。 |
| context-chat | Chat / LLM provider | OpenAI-compatible `/chat/completions`；地址和模型由部署配置选择，可是本地独立服务或远程服务。 |
| context-embedding | DashScope embedding | 与 Chat 分离的 `/embeddings` adapter；仅 `remote` 向量模式使用。 |
| context-redis | Redis | starter + dev 配置 + Actuator 条件健康贡献器；生产 Java 中检索不到 Redis 业务调用，不构成 task queue。 |

### CODE_CONFIRMED edges

1. 用户操作 Vue 页面：浏览器内 UI 交互。
2. Vue → Backend：Axios REST + Bearer JWT；Backend → Vue：REST 响应和 GET 建立的 SSE 事件流。开发时跨 Vite proxy 到 JVM；生产代理/TLS 位置未确认。
3. Backend → PostgreSQL：JDBC/MyBatis 同步 SQL 与 Flyway，跨进程/网络/持久化边界。
4. Backend → 文档目录：同步 `Files` 写、读、删除，跨持久化边界，没有远程文件服务网络边。
5. Backend → Qdrant：同步 REST upsert/query/delete；仅 remote 模式，跨进程/网络边界。
6. Backend → Chat：Spring AI 同步模型请求；跨进程/网络边界。
7. Backend → embedding：RestClient 同步 POST；仅 remote 模式，跨进程/网络边界。
8. Backend ⇢ Redis：已配置的条件 Actuator health 访问关系；不属于任务执行主路径，实际 Bean 激活与连通性未运行确认。

### Confirmed states, boundaries and failure paths

- 本图不建模 task 状态机、调用顺序、事务起止或执行线程状态；这些内容留给对应主题。
- Browser/Vue 在客户端边界内，Backend 在服务端 JVM 边界内；外部网络依赖、后端可见文件目录分别标识。逻辑分组不证明这些进程位于不同宿主机。
- 身份：`SecurityConfig` + `JwtAuthenticationFilter` 验证 Bearer、查 ACTIVE 用户；owner 从认证主体进入服务/SQL，不信任请求提供的 userId。
- owner-scoped 覆盖 Agent/知识库/task 等用户资源；内置 `mock_order` / `mock_payment_log` 是认证用户共享的演示数据，不能概括为所有表都按 owner 隔离。
- 失败事实：缺少有效身份拒绝访问；越 owner 查 task/知识库为 not found；远程 adapter 的失败会返回/抛出失败，不代表 provider 已停止。文件根目录无法建立会导致初始化失败。
- `/api/v1/health` 只返回轻量应用存活信息；Actuator 暴露 health/info、probes，另有 `taskExecution` health。不能以轻量 UP 推断所有依赖可用或可接收任务。

### TEST_CONFIRMED facts

以下为**已检查的测试断言，本轮未执行业务测试**：

- `RemoteVectorizationGatewayConfigurationTest`：remote 注入 DashScope/Qdrant，维度不一致拒绝创建上下文；不发真实请求。
- `DashScopeEmbeddingGatewayTest`、`QdrantVectorStoreGatewayTest`：MockRestServiceServer 检查协议、维度、scope filter；不是外部服务验收。
- `LocalDocumentStorageTest`：临时目录读写删除和路径边界断言。
- `AgentTaskSseControllerTest.shouldPassTheResolvedCursorAndAuthenticatedOwnerToTheStreamService`：使用认证 owner，即使请求携带另一 userId。
- `frontend/src/stores/runtime.test.ts`：受控 GET/Trace/stream 断言刷新、回放、重连；不能证明真实网络部署。

### DOC_DECLARED only facts

- README 中“V47 保留真实模型 + DashScope + Qdrant 验收”的历史声明；本轮仅检查 `scripts/v47-real-provider-acceptance.sh` 的真实验收入口及状态写出机制，没有重新验证历史产物或执行真实服务验收，故不升级为当前 runtime PASSED。
- 文档中的后续部署与图集主题，不作为图 01 的实现节点。

### UNKNOWN facts

- 生产 ingress/TLS、静态前端托管、宿主机/容器划分、外部服务实际部署地址、当前 profile/env 覆盖、连接与鉴权是否有效：需在目标环境做配置与连通性验收。
- Redis health 是否在目标环境启用、健康情况如何：需检查运行时 Actuator；配置和依赖源码只能证明条件注册。
- provider 超时后是否继续计算、是否支持远程取消：需 provider 侧证据，本轮不推断。
- 文件目录在目标部署中的持久卷/备份保证：需部署配置证明；代码仅支持本地文件系统实现。

## Cross-diagram invariants

图 01 与图 02 的共同语义已核对。以下仅是已交付两图之间的检查，**不是**最终独立 Architecture Audit；未施工图的状态和链路仍待核查。

| 不变量 | 图 01 / 图 02 的一致处理 |
| --- | --- |
| persisted task / runtime / provider / browser observation 分开 | 图 01 为进程及依赖边界；图 02 为 JVM 内职责。Recorder/事件为持久事实，Runner 为执行，provider 网关为外部调用，SSE 为回读观察。 |
| Task status 一致性 | 两图不展开状态机；图 02 Engine 返回 outcome、Runner/settlement 收尾，不把 Engine 结果等同于已提交终态。完整状态转移待 lifecycle 图。 |
| Config Version / Snapshot 关系 | 图 01 只概括 PostgreSQL 存储；图 02 区分草稿、不可变版本、执行快照，不建立身份等价关系。详细对象关系待图 13。 |
| async boundary 一致 | 图 02 的 task 派发在创建事务 commit 后，使用 JVM 本地 executor；图 01 Redis 不成为队列。网关自身同步，任务 deadline 边界可外派工作并等待。 |
| provider cancellation 语义 | 均不主张本地 timeout/cancel 可终止远端请求；调用方停止等待与 provider 停止处理分开。 |
| SSE observation 语义 | 图 01 为客户端发起的 HTTP 事件流；图 02 从持久事件回读，直接由 SSE controller 进入，不创建、重启或驱动 task。 |
| restart recovery 语义 | 两图均未画恢复执行边；完整恢复语义留待图 11，不以本图证明。 |
| 数据/网络边界 | 图 02 的 Provider Gateways 是 JVM 内接口和 adapter；网络服务是图 01 的 Chat、remote embedding/Qdrant。各领域 Mapper 使用同一 PostgreSQL；共享演示表仍是 owner scope 的例外。 |

## 文档与代码差异

在图 01 范围内，README 对 Redis、Compose、Chat/embedding 和本地文档存储的描述与已检查实现没有发现冲突。注意 `application-dev.yml` 缺省选择 `remote`，但 `VectorizationGatewayConfiguration` 在属性缺省或 `local` 时提供 deterministic embedding + in-memory vector store；因此图中 remote 依赖是**带配置条件**的关系，不是所有配置下都必需。未审查与本图无关的规格差异。

## 图 01 Evidence index

- **Diagram:** 01 · System Context
- **Question:** AgentFlow Hub 在整个运行环境中与哪些用户、前端、后端、存储和外部服务交互？
- **Type:** `architecture`
- **Primary path:** 用户 → 浏览器内 Vue → Spring Boot → PostgreSQL。箭头表示交互发起/依赖方向，不表示 task 的执行时序。REST 响应及 SSE 事件由后端返回浏览器；SSE 使用同一条客户端发起的 HTTP 连接，未另画重复回边。
- **Key nodes:** 9 个，见施工前节点表及下列来源索引。
- **Key evidence:** 下列节点来源及逐边机制表；所有图中来源保留本地路径，`local-only` 避免把 dirty 文件冒充远端已提交内容。
- **Unknowns:** 见施工前 UNKNOWN facts 与 Known unknowns；运行连通性、环境覆盖及生产拓扑未确认。
- **JSON:** [01-system-context.architecture.json](01-system-context.architecture.json)
- **HTML:** [01-system-context.html](01-system-context.html)
- **Validation:** [validate 回执](01-system-context.validation.json)，9/9 showcase，0 errors、0 warnings；[deliver 回执](01-system-context.delivery.json) 成功，21 个节点来源引用经工具核对。
- **Visual check:** [自动浏览器回执](01-system-context.visual-check.json) `pass`；[明暗主题截图索引](01-system-context.visual-check.html)；[独立视觉复核记录](01-system-context.visual-review.json) `passed`。

### 节点来源

来源行号对应本次工作树。工具核对路径/行号与 repository revision，不代替人工对代码语义的判断。

| Node ID | 生产代码 / 配置 / migration |
| --- | --- |

| `context-user` | [任务交互页面](../../frontend/src/pages/TasksPage.vue)（L1） |
| `context-vue` | [Vue 挂载](../../frontend/src/main.ts)（L8）；[REST 与 JWT](../../frontend/src/lib/api.ts)（L15）；[SSE 观察](../../frontend/src/lib/stream.ts)（L14） |
| `context-backend` | [认证身份](../../backend/src/main/java/com/agentflow/user/security/JwtAuthenticationFilter.java)（L66）；[owner 授权](../../backend/src/main/java/com/agentflow/agent/task/service/TaskEventQueryService.java)（L48）；[Actuator 配置](../../backend/src/main/resources/application.yml)（L30） |
| `context-postgres` | [Task 与事件持久化](../../backend/src/main/resources/db/migration/V18__create_agent_task_and_event.sql)（L5）；[内置工具共享数据](../../backend/src/main/resources/db/migration/V12__create_demo_order_payment_data.sql)（L5）；[owner SQL](../../backend/src/main/java/com/agentflow/agent/task/repository/AgentTaskMapper.java)（L175） |
| `context-chat` | [Chat HTTP 配置](../../backend/src/main/java/com/agentflow/config/SpringAiConfig.java)（L77）；[同步模型调用](../../backend/src/main/java/com/agentflow/infra/llm/SpringAiOpenAiCompatibleLlmGateway.java)（L57） |
| `context-embedding` | [remote 条件](../../backend/src/main/java/com/agentflow/knowledge/vector/RemoteVectorizationGatewayConfiguration.java)（L18）；[POST embeddings](../../backend/src/main/java/com/agentflow/knowledge/vector/DashScopeEmbeddingGateway.java)（L35） |
| `context-redis` | [Redis starter](../../backend/pom.xml)（L95）；[Redis 配置](../../backend/src/main/resources/application-dev.yml)（L23） |
| `context-files` | [本地实现注册](../../backend/src/main/java/com/agentflow/knowledge/storage/DocumentStorageConfig.java)（L20）；[文件写入和读取](../../backend/src/main/java/com/agentflow/knowledge/storage/LocalDocumentStorage.java)（L42）；[文档目录配置](../../backend/src/main/resources/application.yml)（L102） |
| `context-qdrant` | [REST 向量存储](../../backend/src/main/java/com/agentflow/knowledge/vector/QdrantVectorStoreGateway.java)（L46）；[仅 Qdrant 容器](../../compose.yml)（L7） |

### 逐边机制与持久化副作用

所有 relationship ID 对应 JSON 的 `connections[].id`。这里列的是系统级边界，内部事务/线程细节不在图 01 展开；JDBC 参与本地数据库事务，provider HTTP 和文件 I/O 不由 PostgreSQL 事务原子提交。

| Edge ID / source → target | Relationship / mechanism / sync-async | Source file / class / method | Persistence side effect | Evidence level |
| --- | --- | --- | --- | --- |
| `context-user-ui` / 用户 → Vue | 用户在浏览器内操作；不是进程间调用 | [main.ts](../../frontend/src/main.ts) L8 `createApp().mount()`；[TasksPage.vue](../../frontend/src/pages/TasksPage.vue) 页面入口 | 此边本身不产生服务端写入；提交经 API 边 | CODE_CONFIRMED |
| `context-frontend-api` / Vue → Backend | HTTP REST 请求/响应，Axios Promise；带 Bearer JWT。另发 GET 建立异步 SSE 下行事件观察；开发期 Vite proxy 跨至 JVM | [api.ts](../../frontend/src/lib/api.ts) L15 `request()`、L87 `createTask()`；[stream.ts](../../frontend/src/lib/stream.ts) L14 `readTaskStream()`；[vite.config.ts](../../frontend/vite.config.ts) L9；[AgentTaskSseController](../../backend/src/main/java/com/agentflow/agent/task/controller/AgentTaskSseController.java) L36 `events()`；[TaskSseService](../../backend/src/main/java/com/agentflow/agent/task/sse/TaskSseService.java) L59 `open()` | 写 API 可产生业务行；SSE 自身只读持久事件，不创建/重启 task | CODE_CONFIRMED |
| `context-backend-sql` / Backend → PostgreSQL | JDBC/MyBatis 同步 SQL，经独立数据库进程/网络；Flyway 管理 schema。内置工具在后端调用 service/mapper | [AgentTaskMapper](../../backend/src/main/java/com/agentflow/agent/task/repository/AgentTaskMapper.java) L60 `insertTask()`、L183 `selectOwnedById()`；[V18 migration](../../backend/src/main/resources/db/migration/V18__create_agent_task_and_event.sql)；[OrderQueryToolHandler](../../backend/src/main/java/com/agentflow/tool/OrderQueryToolHandler.java) L22 `execute()` → `DemoOrderService.getByOrderNo()` → [MockOrderMapper](../../backend/src/main/java/com/agentflow/demo/repository/MockOrderMapper.java) L38 `selectByOrderNo()`；[PaymentLogQueryToolHandler](../../backend/src/main/java/com/agentflow/tool/PaymentLogQueryToolHandler.java) L29 `execute()` → `DemoPaymentLogService.query()` → [MockPaymentLogMapper](../../backend/src/main/java/com/agentflow/demo/repository/MockPaymentLogMapper.java) L48 `selectByFilters()` | 持久化身份/资源/task/event 等；演示工具仅 SELECT `mock_order` / `mock_payment_log`，数据由 [V12](../../backend/src/main/resources/db/migration/V12__create_demo_order_payment_data.sql) 建立 | CODE_CONFIRMED |
| `context-backend-chat` / Backend → Chat provider | `LlmGateway` → Spring AI ChatModel；同步 HTTP(S) `/chat/completions`。任务外层调度不改变该网关为同步调用这一事实 | [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L210 `callLlm()`、L242 `gateway.chat()`；[SpringAiOpenAiCompatibleLlmGateway](../../backend/src/main/java/com/agentflow/infra/llm/SpringAiOpenAiCompatibleLlmGateway.java) L57 `chat()`；[SpringAiConfig](../../backend/src/main/java/com/agentflow/config/SpringAiConfig.java) L77 `buildChatModel()` | 该 adapter 不写 PostgreSQL；task/trace 记录属于调用方。provider 侧存储/处理行为未知 | CODE_CONFIRMED |
| `context-backend-embedding` / Backend → DashScope | remote 条件下同步 HTTP(S) POST `/embeddings`；用于文档与查询文本向量化 | [RemoteVectorizationGatewayConfiguration](../../backend/src/main/java/com/agentflow/knowledge/vector/RemoteVectorizationGatewayConfiguration.java) L28 `dashScopeEmbeddingGateway()`；[DashScopeEmbeddingGateway](../../backend/src/main/java/com/agentflow/knowledge/vector/DashScopeEmbeddingGateway.java) L35 `embed()`；[ChunkVectorizationService](../../backend/src/main/java/com/agentflow/knowledge/service/ChunkVectorizationService.java) L84 `vectorizePending()`；[KnowledgeRetrievalService](../../backend/src/main/java/com/agentflow/knowledge/service/KnowledgeRetrievalService.java) L70 `retrieveTest()` | adapter 返回向量，无直接本地 DB 写入；后续写入走 Qdrant/元数据逻辑 | CODE_CONFIRMED |
| `context-backend-qdrant` / Backend → Qdrant | remote 条件下 RestClient 同步 HTTP REST，upsert/query/delete；后端与向量存储跨进程/网络 | [QdrantVectorStoreGateway](../../backend/src/main/java/com/agentflow/knowledge/vector/QdrantVectorStoreGateway.java) L46 `upsert()`、L77 `search()`、L110 `deleteByDocumentScope()`、L239 `scopeFilter()`；[remote 配置](../../backend/src/main/java/com/agentflow/knowledge/vector/RemoteVectorizationGatewayConfiguration.java) L37 `qdrantVectorStoreGateway()` | Qdrant collection/points 写入、删除；query 只读且过滤 owner/知识库。独立于 PostgreSQL commit | CODE_CONFIRMED |
| `context-backend-files` / Backend → 文档目录 | 同步本地 `Files` I/O；跨持久化边界，不画成网络对象存储 | [KnowledgeDocumentService](../../backend/src/main/java/com/agentflow/knowledge/service/KnowledgeDocumentService.java) L90 `upload()`、L323 `storeFile()`；[DocumentProcessingService](../../backend/src/main/java/com/agentflow/knowledge/service/DocumentProcessingService.java) L212 读取；[LocalDocumentStorage](../../backend/src/main/java/com/agentflow/knowledge/storage/LocalDocumentStorage.java) L42 `store()`、L68 `open()`、L73 `delete()` | 原始文件写入/读出/删除；metadata 在 PostgreSQL；不是跨两种存储的原子提交 | CODE_CONFIRMED |
| `context-backend-redis` / Backend ⇢ Redis | TCP 条件健康访问，虚线表示配置中的非业务主路径；不是异步任务队列。同步/响应式 health 实现取决于实际 Bean，不推断当前激活结果 | [pom.xml](../../backend/pom.xml) L90–98（Actuator / Redis starter）；[application-dev.yml](../../backend/src/main/resources/application-dev.yml) L23–28；锁定依赖 `spring-boot-actuator-autoconfigure:3.5.15` 的 `RedisHealthContributorAutoConfiguration.redisHealthContributor()` / `RedisReactiveHealthContributorAutoConfiguration.redisHealthContributor()`，均有 `ConditionalOnEnabledHealthIndicator("redis")`。本机同版本 sources.jar 已读取 | 未发现业务缓存/队列写入；不推断运行时健康结果 | CODE_CONFIRMED（配置与条件实现）；runtime UNKNOWN |

### 授权、模式与健康边界补充

- [SecurityConfig.securityFilterChain()](../../backend/src/main/java/com/agentflow/user/security/SecurityConfig.java) L33：注册/登录/健康公开，其余认证；[JwtAuthenticationFilter.doFilterInternal()](../../backend/src/main/java/com/agentflow/user/security/JwtAuthenticationFilter.java) L66–86：验证 JWT，查 ACTIVE 用户，建立认证主体。
- [TaskEventQueryService.findOwnedBatch()](../../backend/src/main/java/com/agentflow/agent/task/service/TaskEventQueryService.java) L48、[KnowledgeBaseService.findOwnedNonDeleted()](../../backend/src/main/java/com/agentflow/knowledge/service/KnowledgeBaseService.java) L251：认证 owner 参与查询；[V3](../../backend/src/main/resources/db/migration/V3__create_knowledge_document.sql)、[V18](../../backend/src/main/resources/db/migration/V18__create_agent_task_and_event.sql)、[V23](../../backend/src/main/resources/db/migration/V23__create_agent_config_version.sql) 使用相应 owner 复合约束。共享演示表是明确例外。
- [application.yml](../../backend/src/main/resources/application.yml) L17 默认 dev；[application-dev.yml](../../backend/src/main/resources/application-dev.yml) L49–59 缺省 remote；[VectorizationGatewayConfiguration](../../backend/src/main/java/com/agentflow/knowledge/vector/VectorizationGatewayConfiguration.java) L14–31 在 local/缺省属性条件下提供 deterministic embedding、in-memory vector store。后者不等于真实外部服务或持久向量库。
- [HealthController.health()](../../backend/src/main/java/com/agentflow/common/web/HealthController.java) L25 不探测依赖；[TaskRecoveryStartupCoordinator.health()](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskRecoveryStartupCoordinator.java) L79 根据 admission 返回 UP/DOWN。没有代码证据表明轻量 health 会逐项探测 Chat、DashScope 或 Qdrant。
- [compose.yml](../../compose.yml) L7–19 只含 Qdrant，REST 6333、gRPC 6334 仅映射 loopback，实际 adapter 使用 REST；其余进程没有由此 Compose 编排。图的分组不是生产部署证明。
- Redis 用法的负向检索范围为 `backend/src/main/java`、`backend/src/main/resources` 及 `backend/pom.xml`，关键词 `redis|lettuce|redisson`；命中 starter 与 dev 属性，没有生产 Java 业务命中。结论限于当前仓库，不延伸到依赖内部自动配置或其他运行环境。

## Known unknowns

当前未确认项详见施工前 UNKNOWN facts；均保留在交付范围说明中。没有把 mock 单测、默认配置或本图自动布局检查升级为真实外部服务验收。

## 图 01 验收记录

- `validate`: exit 0，完整 **9/9 showcase**，composition **0 errors / 0 warnings**。
- `deliver`: exit 0；精确 source 与 HTML 的字节身份见下表；`repository-evidence.verified` 只代表工具核验声明引用，不证明运行时语义。
- `visual-check`: 最终 exit 0 / `status: pass`，四种尺寸均无页面横向或纵向溢出，readability 与 Viewer 控件检查均 pass。
- 初次 Chrome 在沙箱中 `SIGABRT`，属于环境启动失败；在获准的沙箱外以原版命令重新完成。没有改动工具实现或将失败标为 skipped；最终回执只对应最新交付 HTML。
- 图像复核：实际打开 1440×900 与 2048×1320 的 light/dark 四张截图；主路径、标签/边界、三张说明卡与大屏纵向分布通过。`visual_review: passed`；视觉修正 `correction_rounds: 1`（将 READ 隐藏 tag 中关键条件移到 sublabel/card）。自动回执中的 `visualReview: pending` 按工具契约原样保留，人工/图像复核单独记录。
- 视觉声明范围是默认 READ + Still；未声称手工测试交互搜索、passport、导出或全套 Viewer 功能。

| Receipt | SHA-256 | Bytes |
| --- | --- | --- |
| specification | `5fb5bc33dbc77c7411793b5b262bd1bd0a9aa5f60ebfc0ced44b04afbfd3c0da` | 9695 |
| artifact | `1217a99affcea90d3d92280c32005da8553fea7fc9a90f64b21c5a88b0b9bd35` | 816620 |

| Viewport | scrollWidth × scrollHeight | Containment / readability / Viewer |
| --- | --- | --- |
| 1440×900 | 1440×900 | pass / pass / pass |
| 1600×1000 | 1600×1000 | pass / pass / pass |
| 1920×1080 | 1920×1080 | pass / pass / pass |
| 2048×1320 | 2048×1320 | pass / pass / pass |

复核命令（仓库根目录执行；若环境阻止 Chrome 启动，在允许的浏览器环境重试）：

```bash
node .agents/skills/archify/bin/archify.mjs validate architecture docs/architecture/01-system-context.architecture.json --repo-root . --quality showcase --json
node .agents/skills/archify/bin/archify.mjs deliver architecture docs/architecture/01-system-context.architecture.json docs/architecture/01-system-context.html --repo-root . --quality showcase --json
node .agents/skills/archify/bin/archify.mjs visual-check docs/architecture/01-system-context.html --json
```

### 本图完成标准

- [x] 明确一个问题，`architecture` 类型，9 个主要节点。
- [x] README / frontend / application 配置 / Compose / provider / vector / file storage / health 均已检查。
- [x] 节点、重要边、模式依赖、授权信任边界和证据等级已记录。
- [x] Redis 非任务队列；Chat 与 embedding 分开；未加入内部 task 线程池。
- [x] JSON、HTML 已保存；validate、deliver 成功；浏览器检查及截图复核完成。
- [x] Atlas 基线、工作树条件、unknowns、验证回执已补齐。
- [x] 图 01 交付时仅完成图 01；图 02 的后续授权施工记录见下文。最终独立 ARCHITECTURE_AUDIT.md 尚未生成。

## 图 02 施工前工作记录

本节在图 02 Typed JSON 生成前记录。问题：**Spring Boot 后端内部的主要运行时模块如何分工和连接？** 类型 `architecture`；只施工图 02，图 03 尚未开始。

### 图 02 baseline

2026-09-14 重新检查本地 `main`，HEAD 仍为 `ba04adc5308222635e6eb1a86a0981da8ede408c`。工作树仍 dirty，包含上一轮图 01 和既有 V0.3/episode、配置、规格等改动。图 02 按当前文件确认；`TaskTraceQueryService` 的工作树改动作为当前源码事实记录，不伪装成远端已提交内容。未 fetch、未运行外部业务服务；Archify 仍使用本地 2.17 / showcase。

### CODE_CONFIRMED nodes / responsibilities

| 稳定 ID | 职责聚合 | 确认实现 |
| --- | --- | --- |
| runtime-api | API / 身份 | Controllers、SecurityConfig、JWT filter；认证主体传递到 owner-scoped 服务。 |
| runtime-config | 配置与 Snapshot | AgentAppService / AgentBindingService、ConfigVersionService/Transactions、AgentTaskSnapshotResolver；草稿管理、不可变版本、实际执行快照保持区别。 |
| runtime-task | Task 应用服务 | RestService / ApplicationService / CreationTransactionService；公共请求适配、幂等、新任务事务及取消/查询委托。 |
| runtime-dispatch | Dispatch / Runner | AfterCommit coordinator、BoundedTaskDispatcher、TaskRunner、SettlementService；本地准入、有界派发、claim、结果收尾。 |
| runtime-engine | Agent Runtime | TaskExecutionDelegate → AgentEngine 的 TaskExecutionRequest 重载 → TaskSnapshotAgentExecutor；决策、RAG/工具编排、独立最终生成。 |
| runtime-rag | Retrieval / RAG | SnapshotRagService；冻结 generation 检索、canonical chunk 回查与证据校验。 |
| runtime-gateways | Provider Gateways | LlmGateway + EmbeddingGateway + VectorStoreGateway；内部接口/适配器聚合，Chat、embedding、vector 是不同网关。 |
| runtime-tools | Tool Runtime | DefaultToolRuntime、BuiltinToolExecutor、Handler、ToolCallLogService；冻结工具校验、受限 handler 工作、审计。 |
| runtime-trace | Trace / Event / SSE | ExecutionRecorder、TaskEventAppender、TaskTraceQueryService、TaskEventQueryService、TaskSseService；记录与持久化回读，SSE 不驱动执行。 |
| runtime-persistence | 事务 / Repositories | 各领域 Spring 事务服务与 Mapper 的聚合表示，连接 PostgreSQL；不是新增的统一 Repository 服务。 |

### CODE_CONFIRMED edges

- API → Task：Controller 调用 RestService，再委托 Application/生命周期服务。
- API → 配置：Agent/Binding/ConfigVersion controller 调用各自配置服务；不经过 task 执行。
- Task → 配置/Snapshot：`createNew()` 调用 `selectForTask()`、`resolveConfiguration()`；短 REPEATABLE_READ 数据库事务内不调用模型、向量或工具。
- Task → Dispatch：创建事务注册 afterCommit；commit 后通过本地 executor 派发 Runner；没有 Redis queue。
- Dispatch/Runner → Engine：在 worker 上构造已持久化快照的 TaskExecutionRequest，同步调用 delegate；拒绝在数据库事务内执行 delegate。
- Engine → RAG / LLM：经 TaskExternalCallDeadline 外派受限工作并等待结果；网关内为同步调用。
- Engine → ToolRuntime：同步 task-scoped facade；工具 handler 在 Runtime 内经同一 deadline/permit 机制执行。
- RAG → Gateway：同步 embedding 与 vector 调用；另外直接以 KnowledgeChunkMapper 回查 PostgreSQL。
- Engine → Trace：task-bound recorder 记录 step、LLM、RAG 与事件；阶段更新由生命周期服务负责，终态由 Runner/settlement 负责。
- Task → Trace：REST Trace 查询委托 TaskTraceQueryService。SSE controller 另直接调用 TaskSseService，不经 Runner。
- Task / Trace → 持久化：绘制代表性 DB 关系；配置、Runner、RAG、Tool 的自身 Mapper 关系也必须在说明卡及下方证据索引保留，避免伪造单一持久化入口。

### Confirmed boundaries / states / failure paths

- 节点均为单个后端 JVM 内部职责，不是微服务；Spring 事务与线程切换不等于进程边界。JDBC 与 remote provider/vector adapter 才跨网络到图 01 的外部依赖。
- API 认证/owner 与执行准入是不同边界；门禁不取代数据授权。创建、解析在短事务内，派发在 commit 后，慢 I/O 不持有 task 数据库长事务。
- TaskExternalCallDeadline 的顶层工作由独立线程执行、调用者等待；嵌套受限同步工作复用已有边界。Future cancel 不证明外部 provider 请求停止。
- 当前 `DefaultAgentEngine` 同时保留 `execute(AgentExecutionCommand)`（旧即时路径）和 `execute(TaskExecutionRequest)`（转交 TaskSnapshotAgentExecutor）。图中任务路径只表示后者，不能依据类注释误接旧路径。
- 仅在工作记录核对失败边界：幂等冲突、配置/owner/依赖校验失败、调度拒绝、执行失败与收尾失败；图中不展开这些分支或 task 状态机。
- Trace/Event/SSE 聚合不同读写职责；Recorder 写执行事实，TaskEventAppender 分配事件序号，SSE 只回读事件，断线不会触发 Runner。

### TEST_CONFIRMED facts

已检查测试源码断言，本轮**未运行这些业务测试**：

- AfterCommitTaskDispatchCoordinatorTest：回滚不派发，只有 afterCommit 才 dispatch，拒绝时走 settlement。
- TaskSnapshotAgentExecutorTest：冻结 RAG/工具与独立 FINAL_GENERATION 的受控编排；SnapshotRagServiceTest 检查冻结 corpus、canonical hash 和不回退新 generation。
- AgentTaskSsePostgresIntegrationTest 与 V03AConfigVersionPostgresIntegrationTest 是 opt-in PostgreSQL 集成入口；存在测试不等于本轮 PostgreSQL/真实服务验收通过。

### DOC_DECLARED / conflicts

- backend-api-design 的包结构示意使用 `agent.app`、`agent.execution`、顶层 `trace` 和 `infra.embedding/vector/storage`；当前代码实际落在 `agent.service`、`agent.task.*`、`agent.engine`、`agent.trace`、`knowledge.vector/storage` 等包。图按职责和当前实现归属绘制，不复制该设计目录为事实。
- AgentEngine/DefaultAgentEngine 的“non-persistent / V36”注释不能覆盖现存 TaskExecutionRequest 重载；当前调用点和重载实现优先。
- 各历史切片与验收结果仅作背景，不升级为当前版本实时验收。本图不把规划中的通用 planner、memory、reflection、多 Agent、分布式调度或 Evaluation Web 平台作为节点。

### UNKNOWN

当前 provider/数据库连通性、部署 Bean/env 覆盖、真实并发容量与 provider 远程取消能力未运行验证；最终外部拓扑见图 01 unknowns。数据库损坏/持续失效下的完整恢复、全部失败路径和性能不由本图证明。图 02 不替代后续 sequence/workflow/lifecycle，也不代表完成全部图集 Audit。

## 图 02 Evidence index

- **Diagram:** 02 · Backend Runtime Architecture
- **Question:** Spring Boot 后端内部的主要运行时模块如何分工和连接？
- **Type:** `architecture`
- **Primary path:** API / 身份 → Task 应用服务 → Dispatch / Runner → Agent Runtime。箭头表示职责之间的调用/派发关系，不表示一次任务的完整时间顺序；辅助边表示配置解析、RAG、工具、网关与持久化读写。
- **Key nodes:** 10 个 JVM 内部职责聚合。不是每个 Java package 一个节点，也不是独立微服务。
- **Key evidence:** 29 个图内来源引用及以下 13 条边的 class/method、同步/异步、事务和持久化机制；额外列出聚合后省略的直接依赖，避免把代表性 DB 边误读为唯一入口。来源为当前 dirty 工作树，采用 `local-only`。
- **Unknowns:** 当前配置/profile/Bean 覆盖、数据库与 provider 实际连通性、真实并发容量、远程取消能力未运行确认。所有业务测试只检查源码，不构成新 PASSED。完整 failure、recovery、状态机、时序留待对应主题。
- **JSON:** [02-backend-runtime.architecture.json](02-backend-runtime.architecture.json)
- **HTML:** [02-backend-runtime.html](02-backend-runtime.html)
- **Validation:** [validate 回执](02-backend-runtime.validation.json)，完整 9/9 showcase、0 errors / 0 warnings；[deliver 回执](02-backend-runtime.delivery.json) `ok: true`，29 个节点引用经工具核对。
- **Visual check:** [自动浏览器回执](02-backend-runtime.visual-check.json) `pass`；[截图索引](02-backend-runtime.visual-check.html)；[独立图像复核](02-backend-runtime.visual-review.json) `passed`、`correction_rounds: 0`。

### 节点来源

行号对应本次工作树；工具检查来源存在及 artifact 合同，语义确认来自代码追踪。

| Node ID | 生产代码来源 |
| --- | --- |
| `runtime-api` | [JWT 认证身份](../../backend/src/main/java/com/agentflow/user/security/JwtAuthenticationFilter.java)（L66）；[Task REST 入口](../../backend/src/main/java/com/agentflow/agent/task/controller/AgentTaskController.java)（L38）；[SSE 独立入口](../../backend/src/main/java/com/agentflow/agent/task/controller/AgentTaskSseController.java)（L36） |
| `runtime-task` | [REST 应用适配](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskRestService.java)（L43）；[幂等编排](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskApplicationService.java)（L45）；[创建事务](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java)（L61） |
| `runtime-dispatch` | [提交后派发](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinator.java)（L30）；[本地 executor](../../backend/src/main/java/com/agentflow/agent/task/dispatch/BoundedTaskDispatcher.java)（L27）；[领取与结果收尾](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java)（L59） |
| `runtime-engine` | [Task 快照重载](../../backend/src/main/java/com/agentflow/agent/engine/DefaultAgentEngine.java)（L72）；[任务 Engine](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java)（L95）；[受限工作与等待](../../backend/src/main/java/com/agentflow/agent/engine/TaskExternalCallDeadline.java)（L67） |
| `runtime-config` | [草稿管理](../../backend/src/main/java/com/agentflow/agent/service/AgentAppService.java)（L144）；[配置版本选择](../../backend/src/main/java/com/agentflow/agent/configversion/AgentConfigVersionTransactions.java)（L80）；[实际快照解析](../../backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskSnapshotResolver.java)（L137） |
| `runtime-rag` | [快照检索](../../backend/src/main/java/com/agentflow/agent/rag/SnapshotRagService.java)（L65）；[canonical 数据回查](../../backend/src/main/java/com/agentflow/knowledge/repository/KnowledgeChunkMapper.java)（L1） |
| `runtime-gateways` | [Chat 适配器](../../backend/src/main/java/com/agentflow/infra/llm/SpringAiOpenAiCompatibleLlmGateway.java)（L57）；[embedding 适配器](../../backend/src/main/java/com/agentflow/knowledge/vector/DashScopeEmbeddingGateway.java)（L35）；[vector 适配器](../../backend/src/main/java/com/agentflow/knowledge/vector/QdrantVectorStoreGateway.java)（L77） |
| `runtime-persistence` | [生命周期短事务](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java)（L51）；[Task SQL](../../backend/src/main/java/com/agentflow/agent/task/repository/AgentTaskMapper.java)（L60）；[Trace 短事务](../../backend/src/main/java/com/agentflow/agent/trace/ExecutionRecorderTransactionService.java)（L65） |
| `runtime-trace` | [写侧 Recorder](../../backend/src/main/java/com/agentflow/agent/trace/PersistentExecutionRecorder.java)（L19）；[owner Trace 聚合](../../backend/src/main/java/com/agentflow/agent/trace/TaskTraceQueryService.java)（L93）；[只读事件观察](../../backend/src/main/java/com/agentflow/agent/task/sse/TaskSseService.java)（L59） |
| `runtime-tools` | [task-scoped 执行](../../backend/src/main/java/com/agentflow/tool/DefaultToolRuntime.java)（L74）；[Handler 白名单](../../backend/src/main/java/com/agentflow/tool/BuiltinToolExecutor.java)（L43）；[工具审计写入](../../backend/src/main/java/com/agentflow/tool/ToolCallLogService.java)（L58） |

### 逐边机制、事务与持久化副作用

边为模块依赖；一条聚合边可包含多个同步调用。事务发生在具体的 Spring 代理方法上，不是整块职责节点拥有一个长事务。所有图中模块均在同一 JVM；只有经网关/JDBC 到图 01 外部依赖时跨进程/网络。

| Edge ID / source → target | Relationship / mechanism / sync-async | Source file / class / method | Persistence / transaction effect | Evidence level |
| --- | --- | --- | --- | --- |
| `runtime-api-task` / API → Task | JVM 内同步 REST 适配；JWT filter 先建立认证身份，Controller 传 owner。 | [AgentTaskController](../../backend/src/main/java/com/agentflow/agent/task/controller/AgentTaskController.java) L38 `create()` → [AgentTaskRestService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskRestService.java) L43 `create()` → [AgentTaskApplicationService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskApplicationService.java) L45 `createTaskWithResult()` | Controller 无数据库事务；写入经创建事务，get/list 为 owner 查询，cancel 委托生命周期服务。 | CODE_CONFIRMED |
| `runtime-task-dispatch` / Task → Dispatch | 创建事务内注册 TransactionSynchronization，afterCommit 后 executor.execute；切换到本地 worker。 | [AgentTaskCreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) L104 `createNew()` → [AfterCommitTaskDispatchCoordinator](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinator.java) L30 `dispatchAfterCommit()` → [BoundedTaskDispatcher](../../backend/src/main/java/com/agentflow/agent/task/dispatch/BoundedTaskDispatcher.java) L27 `dispatch()` | task + snapshot + 初始事件先提交；回滚不派发。派发拒绝经 settlement 持久化拒绝结果，不是 Redis queue。 | CODE_CONFIRMED |
| `runtime-dispatch-engine` / Dispatch / Runner → Runtime | worker 上同步 delegate.execute；明确拒绝在活跃数据库事务内执行 delegate。 | [TaskRunner](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) L59 `run()` → [AgentEngineTaskExecutionDelegate](../../backend/src/main/java/com/agentflow/agent/task/execution/AgentEngineTaskExecutionDelegate.java) L16 `execute()` → [DefaultAgentEngine](../../backend/src/main/java/com/agentflow/agent/engine/DefaultAgentEngine.java) L72 `execute(TaskExecutionRequest)` → [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L95 `execute()` | Runner 先 claim，读取持久化快照并执行；Engine 返回 outcome，Runner 再调用 settlement 短事务收尾。此边不是旧即时执行重载。 | CODE_CONFIRMED |
| `runtime-api-config` / API → 配置与 Snapshot | 各 Controller 同步调用配置服务；配置管理不经过 Task 执行。 | [AgentAppController](../../backend/src/main/java/com/agentflow/agent/controller/AgentAppController.java) L49 `create()` → [AgentAppService](../../backend/src/main/java/com/agentflow/agent/service/AgentAppService.java) `create()`；[AgentBindingController](../../backend/src/main/java/com/agentflow/agent/binding/controller/AgentBindingController.java) L38 `replaceKnowledgeBindings()` / L58 `replaceToolBindings()` → [AgentBindingService](../../backend/src/main/java/com/agentflow/agent/binding/service/AgentBindingService.java) 同名方法；[AgentConfigVersionController](../../backend/src/main/java/com/agentflow/agent/configversion/AgentConfigVersionController.java) L24 `publish()` → [AgentConfigVersionService](../../backend/src/main/java/com/agentflow/agent/configversion/AgentConfigVersionService.java) `publish()` | 草稿与绑定按 owner 管理；发布版本经配置事务持久化。不可变版本与实际 Task 快照是不同对象。 | CODE_CONFIRMED |
| `runtime-task-config` / Task → 配置与 Snapshot | 同步调用 selectForTask / resolveConfiguration，处于 createNew 的 REPEATABLE_READ 短事务。 | [AgentTaskCreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) L61 `createNew()` → [AgentConfigVersionTransactions](../../backend/src/main/java/com/agentflow/agent/configversion/AgentConfigVersionTransactions.java) L80 `selectForTask()` / [AgentTaskSnapshotResolver](../../backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskSnapshotResolver.java) L137 `resolveConfiguration()` | 无显式版本时捕获/复用版本；读取并验证 owner、依赖和冻结语料；task 保存版本标识及实际快照。不调用 LLM/Qdrant/工具网络。 | CODE_CONFIRMED |
| `runtime-task-persistence` / Task → 事务 / Repositories | 同步 SQL，经各自 Spring 事务代理与 MyBatis；图中是代表性写入关系。 | [AgentTaskCreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) L61 `createNew()` → [AgentTaskMapper](../../backend/src/main/java/com/agentflow/agent/task/repository/AgentTaskMapper.java) L60 `insertTask()`；[AgentTaskRestService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskRestService.java) L78 `cancel()` → [AgentTaskLifecycleTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java) L89 `requestCancellation()` | 创建 task、snapshot、初始事件原子提交；取消请求/相应状态和事件由生命周期短事务提交。查询也由各自 Mapper 读取。 | CODE_CONFIRMED |
| `runtime-engine-rag` / Runtime → Retrieval / RAG | 受限调用：外派工作并等待；内部 RAG 方法同步返回。 | [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L168 `retrieve()` → [TaskExternalCallDeadline](../../backend/src/main/java/com/agentflow/agent/engine/TaskExternalCallDeadline.java) L67 `call()` → [SnapshotRagService](../../backend/src/main/java/com/agentflow/agent/rag/SnapshotRagService.java) L65 `retrieve()` | RAG 只读冻结语料，回查 canonical chunks；返回 hits 后由 Engine recorder 写 RAG 事实。慢调用不持有 task 长事务。 | CODE_CONFIRMED |
| `runtime-engine-gateways` / Runtime → Provider Gateways | 受限 LLM 工作；LlmGateway.chat 在工作体内同步调用 ChatModel。 | [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L210 `callLlm()` → [TaskExternalCallDeadline](../../backend/src/main/java/com/agentflow/agent/engine/TaskExternalCallDeadline.java) L67 `call()` → [SpringAiOpenAiCompatibleLlmGateway](../../backend/src/main/java/com/agentflow/infra/llm/SpringAiOpenAiCompatibleLlmGateway.java) L57 `chat()` | 网关无直接 PostgreSQL 写入；调用日志/usage 由 recorder 记录。Chat HTTP 跨网络见图 01；timeout 不证明远端停止。 | CODE_CONFIRMED |
| `runtime-rag-gateways` / Retrieval / RAG → Provider Gateways | 同步 embedding.embed 与 vectors.search；remote 时为两个独立 HTTP adapter。 | [SnapshotRagService](../../backend/src/main/java/com/agentflow/agent/rag/SnapshotRagService.java) L78 `retrieve()` → [DashScopeEmbeddingGateway](../../backend/src/main/java/com/agentflow/knowledge/vector/DashScopeEmbeddingGateway.java) L35 `embed()` / [QdrantVectorStoreGateway](../../backend/src/main/java/com/agentflow/knowledge/vector/QdrantVectorStoreGateway.java) L77 `search()` | 此检索边只生成查询向量并查询 points，不做 ingestion/upsert。local 模式的确定性/in-memory 实现不构成真实 provider 证据。 | CODE_CONFIRMED |
| `runtime-engine-tools` / Runtime → Tool Runtime | 同步 task-scoped facade；Runtime 校验冻结定义后，在 deadline 边界运行 handler。 | [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L327 `invokeTool()` → [DefaultToolRuntime](../../backend/src/main/java/com/agentflow/tool/DefaultToolRuntime.java) L74 `execute()` / L170 executeTask() / L217 executeWithinTaskDeadline() → [BuiltinToolExecutor](../../backend/src/main/java/com/agentflow/tool/BuiltinToolExecutor.java) L43 `execute()` | ToolCallLogService 独立短事务写审计；task 可绑定 order_query/payment_log_query，handler 读取共享演示表。不能由通用 executor 的 report handler 推断 task 允许绑定 report。 | CODE_CONFIRMED |
| `runtime-engine-trace` / Runtime → Trace / Event / SSE | 同步 task-bound recorder facade → Spring 事务代理；记录 step、LLM、RAG 事实。 | [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L102 `execute()` → [PersistentExecutionRecorder](../../backend/src/main/java/com/agentflow/agent/trace/PersistentExecutionRecorder.java) L19 `startStep()` → [ExecutionRecorderTransactionService](../../backend/src/main/java/com/agentflow/agent/trace/ExecutionRecorderTransactionService.java) L65 `startStep()` | REQUIRES_NEW 短事务持久化 trace；事件由 TaskEventAppender 在现有事务内分配序号。该边不表示 Engine 调用 SSE 网络推送。 | CODE_CONFIRMED |
| `runtime-task-trace` / Task → Trace / Event / SSE | REST Trace 查询同步委托；认证 owner 先查 task，再投影公共 Trace。 | [AgentTaskRestService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskRestService.java) L86 `trace()` → [TaskTraceQueryService](../../backend/src/main/java/com/agentflow/agent/trace/TaskTraceQueryService.java) L93 `findOwnedPublicTrace()` / L99 projectPublicTrace() | 只读 REPEATABLE_READ / REQUIRES_NEW 聚合已持久化事实，不执行 Engine 或工具。L99 是当前工作树提取出的投影方法。 | CODE_CONFIRMED |
| `runtime-trace-persistence` / Trace / Event / SSE → 事务 / Repositories | Recorder 写入与 Trace/SSE 回读共用领域数据库；写事务与只读查询分别管理。 | [ExecutionRecorderTransactionService](../../backend/src/main/java/com/agentflow/agent/trace/ExecutionRecorderTransactionService.java) L119 `recordLlm()`；[TaskEventAppender](../../backend/src/main/java/com/agentflow/agent/task/service/TaskEventAppender.java) L33 `append()`；[TaskEventQueryService](../../backend/src/main/java/com/agentflow/agent/task/service/TaskEventQueryService.java) L48 `findOwnedBatch()` | 写 step/LLM/RAG/event；event append 要求活跃事务，分配序号并插入。SSE/Trace 查询为只读，不产生任务执行副作用。 | CODE_CONFIRMED |

### 聚合后保留的直接依赖与边界

以下关系不全部绘线，仍属于已确认的模块事实；默认 READ 说明卡已提示代表性 DB 边及 SSE 直接回读。**事务 / Repositories 不是一个所有模块都必须调用的新增集中服务。**

| 直接依赖 | 实现证据与职责边界 | 等级 |
| --- | --- | --- |
| API / 身份 → SSE | [AgentTaskSseController](../../backend/src/main/java/com/agentflow/agent/task/controller/AgentTaskSseController.java) L36 `events()` 直接调用 [TaskSseService](../../backend/src/main/java/com/agentflow/agent/task/sse/TaskSseService.java) L59 `open()`，先 owner 查询，再开启 Servlet async；poll/watchdog 调度与非阻塞输出回读持久事件。无 Runner 依赖，不经 TaskRestService。 | CODE_CONFIRMED |
| Config → 自身 Mapper | [AgentConfigVersionTransactions](../../backend/src/main/java/com/agentflow/agent/configversion/AgentConfigVersionTransactions.java) L50 `capture()` / L80 selectForTask() 及 Agent/Binding 服务读写本领域表；snapshot resolver 只读并验证创建所需依赖。不是经 TaskRestService 转发所有配置 SQL。 | CODE_CONFIRMED |
| Runner → Lifecycle / Settlement → Mapper | [TaskRunner](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) L59 `run()` claim 后执行，L97 settle；[TaskSettlementService](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskSettlementService.java) L46 `settle()` → [AgentTaskLifecycleTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java) L152 `settleObserved()`，终态与事件在短事务内提交。 | CODE_CONFIRMED |
| RAG → KnowledgeChunkMapper | [SnapshotRagService](../../backend/src/main/java/com/agentflow/agent/rag/SnapshotRagService.java) L116 `retrieve()` 在向量候选后调用 selectSnapshotRetrievableChunks，验证 owner/KB/document/generation/hash；不向新 generation 回退。 | CODE_CONFIRMED |
| Tool Runtime → 审计 / Demo Mapper | [ToolCallLogService](../../backend/src/main/java/com/agentflow/tool/ToolCallLogService.java) L58 的 running/success/failure/rejected 方法使用独立事务；内置查询 handler 经 demo service/mapper 读取共享订单与支付日志表，关系与图 01 一致。 | CODE_CONFIRMED |
| HTTP 管理/测试入口 → 领域服务 | [KnowledgeDocumentController](../../backend/src/main/java/com/agentflow/knowledge/controller/KnowledgeDocumentController.java) L36 → document/processing services；[KnowledgeChunkController](../../backend/src/main/java/com/agentflow/knowledge/controller/KnowledgeChunkController.java) L17 → vectorization；[KnowledgeChatController](../../backend/src/main/java/com/agentflow/knowledge/controller/KnowledgeChatController.java) L20、[KnowledgeRetrievalController](../../backend/src/main/java/com/agentflow/knowledge/controller/KnowledgeRetrievalController.java) L20 → 各自服务；[ToolController](../../backend/src/main/java/com/agentflow/tool/controller/ToolController.java) L25 → definition service / standalone runtime。这些入口存在，本图不展开全部 CRUD、ingestion 或测试链路，也不声称所有请求都走 Task。 | CODE_CONFIRMED |

- 调度配置：[AgentTaskDispatcherConfiguration](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AgentTaskDispatcherConfiguration.java) L17 `agentTaskExecutor()` 使用本地 ThreadPoolTaskExecutor，有限 core/max/queue 与 AbortPolicy。配置入口在 [application.yml](../../backend/src/main/resources/application.yml) L84；本图只保留“有界派发”职责，不展开容量图。配置值不是实测容量。
- 授权：[SecurityConfig](../../backend/src/main/java/com/agentflow/user/security/SecurityConfig.java) L33 `securityFilterChain()`、[JwtAuthenticationFilter](../../backend/src/main/java/com/agentflow/user/security/JwtAuthenticationFilter.java) L66 `doFilterInternal()` 在 Controller 前建立身份；owner 检查仍分布在服务和 SQL，API 节点不代表唯一授权点。执行 admission 也不替代 owner 授权。
- 事务约束：[TaskRunner](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) L76 `run()` 在 delegate 前检查当前无活跃事务；[TaskExternalCallDeadline](../../backend/src/main/java/com/agentflow/agent/engine/TaskExternalCallDeadline.java) L67 `call()` 顶层外派工作并等待，嵌套已拥有的边界同步复用。超时/取消本地 Future 不证明外部 provider 已停止处理。
- 持久化模型： [V18 task/event](../../backend/src/main/resources/db/migration/V18__create_agent_task_and_event.sql)、[V19 execution trace](../../backend/src/main/resources/db/migration/V19__create_agent_execution_trace.sql)、[V23 config version](../../backend/src/main/resources/db/migration/V23__create_agent_config_version.sql) 支持上述实体及 owner/关系约束；本图不展开 ER 全模型。
- 文档差异定位：[backend-api-design](../../spec-docs/agentflow-hub-backend-api-design.md) L11–49 的包结构是设计示意，实际实现归属以节点来源为准；[DefaultAgentEngine](../../backend/src/main/java/com/agentflow/agent/engine/DefaultAgentEngine.java) L72 `execute(TaskExecutionRequest)` 已转交 TaskSnapshotAgentExecutor，与 L79 的旧即时命令路径区别处理。没有据旧注释判定当前 task engine 未接入。

### 测试断言来源与适用范围

以下均为源码阅读，**本轮未执行，不报告新的测试通过率或真实外部服务结果**：

| 来源 | 已检查的证据 | 边界 |
| --- | --- | --- |
| [AfterCommitTaskDispatchCoordinatorTest](../../backend/src/test/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinatorTest.java) L40 | 回滚不派发；模拟 afterCommit 后 dispatch；拒绝委托 settlement。 | Mockito / 手动事务同步回调，不是实测 executor 容量。 |
| [TaskSnapshotAgentExecutorTest](../../backend/src/test/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutorTest.java) L74 | 冻结 RAG、工具编排与独立最终生成；ordered facts；不由 Engine 调用 lifecycle.complete。 | 受控 gateway/runtime 的模块测试。 |
| [SnapshotRagServiceTest](../../backend/src/test/java/com/agentflow/agent/rag/SnapshotRagServiceTest.java) L57 | 错 owner/generation/hash 不纳入；撤销/空冻结集合不回退新语料。 | Mock 语料与向量结果，不是检索质量或真实 Qdrant 验收。 |
| [AgentTaskSsePostgresIntegrationTest](../../backend/src/test/java/com/agentflow/agent/task/AgentTaskSsePostgresIntegrationTest.java) L215 | 断开 SSE 后任务仍运行且无取消标记；重连按 cursor 读取事件。 | opt-in PostgreSQL + HTTP/JWT 集成测试源码；provider/delegate 受控，本轮未启动。 |
| [V03AConfigVersionPostgresIntegrationTest](../../backend/src/test/java/com/agentflow/agent/configversion/V03AConfigVersionPostgresIntegrationTest.java) L37 | opt-in 入口包含冻结配置、owner admission、隐式版本/初始事件回滚场景。 | 入口/测试场景已检查；不据其存在推断当前数据库验收通过。 |

## 图 02 验收记录

- 2026-09-15 完成，local main / HEAD 与施工前一致；使用当前 dirty 文件作来源。没有修改业务代码或图 01 产物，没有 fetch/commit/push。
- `validate`: exit 0，完整 **9/9 showcase**，composition **0 errors / 0 warnings**。`deliver`: exit 0 / `ok: true`，来源引用 29；工具验证不等于架构运行时语义验证。
- `visual-check`: exit 0 / `status: pass`；四种桌面尺寸页面无横/纵溢出，readability 和 Viewer 控件检查均 pass。Chrome 使用已获准的浏览器环境运行，未改动 Archify 工具实现。
- 已实际查看 1440×900、2048×1320 的 light/dark 四张截图：主路径清楚、无节点/标签遮挡、三张说明卡和大屏纵向布局通过；`visual_review: passed`、`correction_rounds: 0`。默认 READ + Still；不声称手工测试搜索、passport 或导出。
- 自动回执的 `visualReview: pending` 按工具合同原样保留；图像复核另记于 visual-review.json，并绑定相同 HTML 身份。

| Receipt | SHA-256 | Bytes |
| --- | --- | --- |
| specification | `95ded8029cb40029f6fa9481b7e7e6e22b5bc85efdfd993f9f79580f5dd991ee` | 12348 |
| artifact | `609657a101113011bcf1d1fa36b759fb06ce2c674ef911d08c2daf021b175daf` | 817817 |

| Viewport | scrollWidth × scrollHeight | Containment / readability / Viewer |
| --- | --- | --- |
| 1440×900 | 1440×900 | pass / pass / pass |
| 1600×1000 | 1600×1000 | pass / pass / pass |
| 1920×1080 | 1920×1080 | pass / pass / pass |
| 2048×1320 | 2048×1320 | pass / pass / pass |

复核命令（仓库根目录执行；Chrome 需可启动的浏览器环境）：

```bash
node .agents/skills/archify/bin/archify.mjs validate architecture docs/architecture/02-backend-runtime.architecture.json --repo-root . --quality showcase --json
node .agents/skills/archify/bin/archify.mjs deliver architecture docs/architecture/02-backend-runtime.architecture.json docs/architecture/02-backend-runtime.html --repo-root . --quality showcase --json
node .agents/skills/archify/bin/archify.mjs visual-check docs/architecture/02-backend-runtime.html --json
```

### 本图完成标准

- [x] 只回答后端模块分工/连接，`architecture` 类型，10 个职责节点。
- [x] 实际 Controller → Service、dispatcher 配置、Engine 接口/重载、snapshot、trace/SSE 与主要持久化实现已检查。
- [x] 生产代码事实、测试断言、文档声明/差异、UNKNOWN 分开记录；29 个图内来源、13 条边及聚合后直接依赖可反查。
- [x] 明确 owner、短事务/commit、async、慢工作与网关/数据库网络边界；保留 SSE 观察、Runner 收尾、配置与快照区别。
- [x] 未把每个 package 画成节点，未套三层模板，未展开完整 task 时序/所有失败分支/状态机或未实现能力。
- [x] JSON/HTML 保存，完整 validate 与 deliver 成功，浏览器/图像复核通过，Atlas 索引补齐。
- [x] 图 01 / 02 共同不变量检查完成；不等同于最终独立 Architecture Audit。
- [x] 图 02 交付时按要求停止；后续图 03 授权施工见下文。最终独立 ARCHITECTURE_AUDIT.md 尚未生成。

## 图 03 施工前工作记录

本节先于图 03 Typed JSON 形成。只回答：**用户提交一个 Agent task 后，从创建到最终答案，完整 happy path 如何推进，关键 gate 在哪里？** 类型 `workflow`，新建 source 使用 Archify workflow v2；不施工图 04。

### 图 03 baseline

2026-09-15（Asia/Shanghai），本地 `main` / HEAD `ba04adc5308222635e6eb1a86a0981da8ede408c`，未 fetch。工作树仍 dirty，既有业务、规格、图 01/02 文件保持原状；按当前实现核查，不把未提交内容视作 HEAD 行为。Archify 2.17 / showcase。workflow 不支持 architecture 专用 `--repo-root` 与节点 sources 核验；证据完整保留于本 Atlas，不伪造 CLI repository verification。未启动 Spring Boot/PostgreSQL/Redis/Qdrant/provider，未执行付费调用或业务验收测试。

### Confirmed nodes / edges — CODE_CONFIRMED

- Browser `TasksPage.submit → submission.submitTask/sendPending → api.createTask`：保存幂等 key，Bearer + Idempotency-Key 发 POST；响应后导航到 task 页面，HTTP 201/200 不等同于 RUNNING。
- JWT filter 验证 ACTIVE 用户；RestService 取认证 owner，ApplicationService 先 admission 和 owner-key 幂等查重。同 key/同 fingerprint 返回已有 task，不重新解析配置或派发；冲突拒绝。新任务才继续创建事务。
- `createNew()` 的 REPEATABLE_READ 事务选择/捕获 Config Version，解析实际 Snapshot，并检查 owner、Agent ACTIVE、知识 READY 与工具依赖；不执行模型、向量或 handler 工作。
- 同一创建事务插入 QUEUED task、执行快照、TASK_CREATED 并注册 afterCommit。只有成功 commit 后触发 dispatcher；row 已插入不表示执行开始。
- `BoundedTaskDispatcher` 向本地 executor 提交 Runner；worker 在短事务条件 claim QUEUED 且未取消的 task，写 RUNNING/PREPARING 与 TASK_STARTED。claim 未取得则不执行。
- Runner 读取持久快照，在无活跃数据库事务时调用 TaskExecutionRequest Engine 重载。前置 Snapshot RAG 先于 decision loop；remote 时 embedding/vector 为网络调用，然后 PostgreSQL canonical 校验。运行期空检索可继续，不等同于绕过创建 READY gate。
- 每轮先 boundary / 次数与 token 预算检查，再 DECISION 模型调用。CALL_TOOL 走冻结工具校验与 task-scoped runtime，observation 回到循环；FINISH 仅为 answer plan。次数上限可转受限最终生成，token 不足/取消/超时等则不能强行发布答案。
- FINAL_GENERATION 是独立 Chat 调用，校验引用并返回 outcome。Engine 返回 COMPLETED outcome 仍不是持久终态；Runner 冻结 observedAt，经 settlement 在短事务内重新仲裁持久取消状态。
- 成功 settlement 原子写 COMPLETED、finalAnswer、citations、全部 ANSWER_CHUNK 与 TASK_COMPLETED；阶段/step/LLM/RAG/tool Trace 在运行中独立短事务记录，并非全部留到末尾。TaskEventAppender 在调用方事务中分配事件序号。
- Browser 从创建响应得到 taskId 后先 GET Trace，再按已应用 cursor 订阅 SSE；可在执行中观察。SSE 查询持久事件，不驱动 Runner。终态事件或 GET 检出终态后，GET task + Trace 一致才标记 settled 并展示权威答案/引用；EOF 不是完成证明。

### Confirmed states / boundaries / failure exits

- 成功路径持久状态为 QUEUED → RUNNING → COMPLETED。PREPARING/RETRIEVING/DECIDING/EXECUTING_TOOL/GENERATING 是 phase，不能混作 status；workflow 不展开全状态机。
- 创建事务、commit 后派发、executor worker、慢调用无长事务、终态事务分别标明；Browser/JVM/外部 HTTP/PostgreSQL 的进程、网络、持久化和 owner 信任边界保留。
- admission/auth/owner/配置失败不进入新任务执行；幂等命中复用、冲突拒绝；派发拒绝走条件失败持久化；claim 空结果退出；runtime gate 和 settlement 取消仲裁可能阻止成功答案。只作必要旁注，不展开图 09–12 的全部失败/恢复分支。
- 本地 Future cancel、provider 远端停止、数据库终态、Browser connection/settled 分开。SSE 重连/刷新不启动新执行。

### TEST_CONFIRMED / DOC_DECLARED / UNKNOWN

- 已阅读 AfterCommitTaskDispatchCoordinatorTest 的回滚/afterCommit/拒绝断言；TaskSnapshotAgentExecutorTest 的冻结 RAG/工具/独立最终生成、预算转受限 final、Engine 不提交终态断言；frontend runtime.test.ts 的 cursor 回放、GET/Trace 一致与不一致断言。均本轮未执行，不记为新 PASSED。
- V40 / 前端运行时切片文档中的历史受控验收声明不升级为当前真实 provider 结果；V47 脚本入口存在，读取入口未执行。后续/历史验收数字不是本图端到端实测证据。
- 指南列出的 decision → retrieval/tool 是待追踪候选链，不是代码顺序证明；当前实现是前置 retrieval → decision/tool loop。本图按实现绘制，既有图 02 的快照与收尾语义保持一致。
- 实际部署 profile/网络连通性、外部 provider timeout 后行为、真实 happy path 成功率、性能/并发与全部恢复策略仍 UNKNOWN；需目标环境验收，不在本图补造。

## 图 03 Evidence index

- **Diagram:** 03 · End-to-End Task Workflow（**完成**，2026-09-15 Asia/Hong_Kong）。
- **Question:** 用户提交一个 Agent task 后，从创建到最终答案，完整 happy path 如何推进，关键 gate 在哪里？
- **Type:** `workflow` / schema v2 / showcase。
- **Primary path:** submit → auth/owner/admission/idempotency → config/snapshot → task + event 提交 → after-commit → executor claim → 前置 RAG → decision/tool loop → 独立 final → settlement → Browser 终态核对。
- **Key nodes:** 11 个节点、3 条 compact 泳道；工具为支路，10 个主路径节点；同一创建事务内的 snapshot/persistence 聚合为一个节点。
- **Key evidence:** 下列逐边生产代码索引及施工记录；源码语义为静态证据，workflow 工具不支持 architecture 专用 repository-evidence 核验。
- **Unknowns:** 未执行后端/数据库/provider 业务 E2E；真实成功率、远程取消、实际部署配置仍 UNKNOWN。浏览器验收只针对本张制图 HTML。
- **JSON / HTML:** [正式 source](03-task-e2e.workflow.json) 与 [冻结 candidate](03-task-e2e.candidate.workflow.json) 字节相同；[当前正式 HTML](03-task-e2e.html)。
- **Artifact validation:** [candidate validate](03-task-e2e.candidate.validation.json)、[正式 validate](03-task-e2e.validation.json)、[deliver](03-task-e2e.delivery.json) 均 exit 0 / showcase 9/9 / 0 errors / 0 warnings。
- **Browser evidence:** [visual-check](03-task-e2e.visual-check.json) `pass`，四个 desktop viewport containment / readability / Viewer 均通过；[四张截图索引](03-task-e2e.visual-check.html)。
- **Visual review:** [实际图像复核](03-task-e2e.visual-review.json) `passed`，已查看 1440×900 与 2048×1320 的 light/dark 四张截图；补充交互访问被本地文件 URL 策略阻止，未声明交互动作验收通过。
- **Layout / fonts:** [最终 layout](03-task-e2e.candidate.layout.json)、[基线与当前测量](03-task-e2e.layout-measurements.json)、[全部 11 个节点与 22 个实际 SVG text 字号](03-task-e2e.text-fit.json)。
- **Current acceptance / history:** [当前验收状态](03-task-e2e.acceptance.json) `complete`；[缩宽前失败基线](history/03-before-width-128/README.md)、[更早路由诊断和首版浏览器失败产物](history/03-before-supported-auto/README.md)。

### 逐边证据（首版稳定 ID）

所有边均为 **CODE_CONFIRMED 静态关系**；不等于本轮实测 happy path 成功。表中补充的 gate/失败出口不扩展为完整 failure workflow。

| Edge ID / source → target | Relationship / mechanism / sync-async | Source file / class / method | Persistence / boundary effect | Evidence level |
| --- | --- | --- | --- | --- |
| `wf-submit-gate` / Browser 提交 → 认证 / 幂等门禁 | 跨 Browser/JVM 的 HTTP POST，Bearer + Idempotency-Key；Promise 等待创建响应。 | [submission.ts](../../frontend/src/lib/submission.ts) L32 `submitTask/sendPending` → [api.ts](../../frontend/src/lib/api.ts) L87 `createTask` → [JwtAuthenticationFilter](../../backend/src/main/java/com/agentflow/user/security/JwtAuthenticationFilter.java) L66 `doFilterInternal` → [AgentTaskController](../../backend/src/main/java/com/agentflow/agent/task/controller/AgentTaskController.java) L38 `create` | 浏览器保留原请求/key；实际路径为 POST /api/v1/agents/{agentId}/tasks。该边不是进入执行的证明。 | CODE_CONFIRMED |
| `wf-gate-snapshot` / 门禁 → 版本与 Snapshot | JVM 同步；只有新请求进入创建事务，先查 owner-key 幂等，再解析配置。 | [AgentTaskRestService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskRestService.java) L43 `create` → [AgentTaskApplicationService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskApplicationService.java) L45 `createTaskWithResult` → [AgentTaskCreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) L61 `createNew` | 同 key/fingerprint 直接返回已有 task，不创建、不派发；冲突拒绝。201/200 由 winning insert 决定，不依据 task 的异步状态。 | CODE_CONFIRMED |
| `wf-snapshot-persist` / 版本 / Snapshot → 创建持久化 | 同一 REPEATABLE_READ 事务中同步选择版本、解析实际快照、插入 task。 | [AgentConfigVersionTransactions](../../backend/src/main/java/com/agentflow/agent/configversion/AgentConfigVersionTransactions.java) L80 `selectForTask` / [AgentTaskSnapshotResolver](../../backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskSnapshotResolver.java) L137 `resolveConfiguration` → [AgentTaskCreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) L61 `createNew` | 验证 owner/ACTIVE/READY/工具；写版本关联、execution_snapshot、QUEUED、预算及 TASK_CREATED。无慢 provider/handler 调用。 | CODE_CONFIRMED |
| `wf-persist-dispatch` / 创建提交 → after-commit 派发 | 创建事务注册同步回调；只有物理 commit 成功后调用 dispatcher。 | [AgentTaskCreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) L103 `createNew` → [AfterCommitTaskDispatchCoordinator](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinator.java) L30 `dispatchAfterCommit` | task、snapshot 与 TASK_CREATED 原子提交；回滚不派发。派发拒绝经 settlement 独立记录条件失败。 | CODE_CONFIRMED |
| `wf-dispatch-claim` / Dispatcher → claim | 本地 executor.execute 异步启动 worker；claim 为短事务条件 UPDATE RETURNING。 | [BoundedTaskDispatcher](../../backend/src/main/java/com/agentflow/agent/task/dispatch/BoundedTaskDispatcher.java) L27 `dispatch` → [TaskRunner](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) L59 `run` → [AgentTaskLifecycleTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java) L51 `claim` / [AgentTaskMapper](../../backend/src/main/java/com/agentflow/agent/task/repository/AgentTaskMapper.java) L84 | 仅 QUEUED 且 cancel_requested_at IS NULL 可领取；写 RUNNING/PREPARING、startedAt 与 TASK_STARTED。未领取到行则退出。 | CODE_CONFIRMED |
| `wf-claim-rag` / 已领取 → 前置 RAG | Runner 解析已持久化快照，检查取消/deadline；无活跃数据库事务时调用 Engine；deadline 外派工作并等待。 | [TaskRunner](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) L69 `run` → [DefaultAgentEngine](../../backend/src/main/java/com/agentflow/agent/engine/DefaultAgentEngine.java) L72 `execute(TaskExecutionRequest)` → [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L95 `execute` / L168 retrieve | 阶段变化、PRE_RETRIEVAL step、RAG 日志/事件独立短事务提交；RAG 慢调用不包在 task 长事务内。 | CODE_CONFIRMED |
| `wf-rag-decision` / 冻结 RAG → 决策 / 预算门禁 | 同步使用检索结果构造决策上下文；前置 RAG 先于循环，空结果仍可继续。 | [SnapshotRagService](../../backend/src/main/java/com/agentflow/agent/rag/SnapshotRagService.java) L65 `retrieve` → [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L104 `execute` / L110 循环 / L129 callLlm | remote embedding/vector 经 HTTP，随后 KnowledgeChunkMapper canonical 校验。创建仍要求至少一个 READY 文档；运行期空结果不绕过该创建 gate。 | CODE_CONFIRMED |
| `wf-decision-tool` / 决策 → 冻结工具调用 | CALL_TOOL 分支；task-scoped runtime 校验后经 deadline 执行 handler。 | [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L327 `invokeTool` → [DefaultToolRuntime](../../backend/src/main/java/com/agentflow/tool/DefaultToolRuntime.java) L170 `executeTask` / L217 executeWithinTaskDeadline | ToolCallLogService 写 running/成功/失败审计；handler 查询共享演示数据。工具结果作为不可信 observation，不成为配置或系统指令。 | CODE_CONFIRMED |
| `wf-tool-decision` / 工具 → 决策循环 | 同步收集 observation 后重新检查 boundary/预算；没有自动重复模型请求的重试保证。 | [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L338 `invokeTool` / L351 tools.execute / L368 observations.add / L110 循环 | TOOL_STARTED/FINISHED 与 step 事实分步记录；相同 intent 第一次执行、第二次经校验复用、第三次失败，不能将回环等同于每次新执行。 | CODE_CONFIRMED |
| `wf-decision-final` / 决策 / 预算门禁 → 独立最终生成 | FINISH 产生 answer plan；决策/工具次数上限可进入受限 final。另行 Chat HTTP，同步返回并校验引用。 | [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L110 `execute` / L130 FINISH / L137 budget observation / L153 FINAL_GENERATION | FINISH 不是最终答案。token reserve、deadline、取消、协议与引用验证仍可阻止成功；不能保证达到次数上限一定完成。 | CODE_CONFIRMED |
| `wf-final-settle` / 最终生成 → Runner settlement | Engine 返回 outcome；Runner 冻结 observedAt/deadline 仲裁，再同步进入 settlement 的独立短事务。 | [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L157 `execute` → [TaskRunner](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) L89 `run` → [TaskSettlementService](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskSettlementService.java) L46 `settle` → [AgentTaskLifecycleTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java) L152 `settleObserved` | outcome 不等于已持久终态。事务重新读取/锁定 task，持久取消可覆盖成功；成功时 finalAnswer、citations、全部 ANSWER_CHUNK 与 TASK_COMPLETED 原子提交。 | CODE_CONFIRMED |
| `wf-settle-observe` / 成功提交 → Browser 终态核对 | 提交后的持久事实通过 GET Trace / SSE 回读；终态由 GET task + Trace 一致性确认，跨 HTTP 返回 Browser。 | [TaskEventQueryService](../../backend/src/main/java/com/agentflow/agent/task/service/TaskEventQueryService.java) L48 `findOwnedBatch` → [TaskSseService](../../backend/src/main/java/com/agentflow/agent/task/sse/TaskSseService.java) L59 `open`；[runtime.ts](../../frontend/src/stores/runtime.ts) L95 `settle` / L147 pump / L195 open | 观察侧只读。创建响应后已可 GET Trace + 订阅 SSE；本边只表达最终事实可见性，不表示到 settlement 后才建立连接。SSE EOF/断线不驱动执行。 | CODE_CONFIRMED |

当前交付的 `wf-gate-persist` 合并首版 `wf-gate-snapshot` 与 `wf-snapshot-persist` 的职责；其余边沿用相同事实。制图验收通过不等同于新增或实测了业务实现。泳道为职责聚合，实际线程/事务边界以节点、关系和本表为准。

### 事务、Trace 与观察补充

- 创建事务通过 [V18](../../backend/src/main/resources/db/migration/V18__create_agent_task_and_event.sql) 的 owner-key 唯一约束与 ApplicationService 冲突后回查处理并发。Config Version 与实际 execution_snapshot 仍为不同对象，见 [V23](../../backend/src/main/resources/db/migration/V23__create_agent_config_version.sql) 与实际 resolver。
- [TaskEventAppender.append](../../backend/src/main/java/com/agentflow/agent/task/service/TaskEventAppender.java) L33 要求当前事务，递增序号后插入事件。[ExecutionRecorderTransactionService.startStep](../../backend/src/main/java/com/agentflow/agent/trace/ExecutionRecorderTransactionService.java) L65 在 REQUIRES_NEW 中锁 RUNNING task 并写 step；后续 L119 recordLlm、L158 recordRagRetrieval 分步提交。Trace 不只在最终收尾时写一次。
- [AgentTaskLifecycleTransactionService.settleObserved](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java) L152 锁行仲裁，L122 completeAt 写答案/引用，L182 appendAnswerChunks 与 TASK_COMPLETED 同事务。ANSWER_CHUNK 是持久答案分片，不是 provider token streaming。
- [TaskExternalCallDeadline.call](../../backend/src/main/java/com/agentflow/agent/engine/TaskExternalCallDeadline.java) L67 的独立工作/等待边界与同步网关区分；本地 Future cancel 不证明 provider 远端停止。工具 handler 当前使用 JVM 内实现，不能泛化为任意外部工具 HTTP。
- [frontend runtime](../../frontend/src/stores/runtime.ts) L95 的 `settle` 核对 GET task/Trace 的 status、事件上界、terminationReason、errorCode、recovery、finalAnswer/citations，再标记页面 settled；它与后端 TaskSettlementService 是不同职责。L195 open 从 Trace 重建再订阅 SSE；HTTP 响应、连接状态与任务状态分开。

### 测试与文档证据边界

- **TEST_CONFIRMED（断言已阅读，本轮未执行）**：[AfterCommitTaskDispatchCoordinatorTest](../../backend/src/test/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinatorTest.java) L40–74 回滚不派发、afterCommit 才派发、拒绝持久化委托；[TaskSnapshotAgentExecutorTest](../../backend/src/test/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutorTest.java) L110–157 受控工具 observation / 独立 final / Engine 不完成终态，L456–479 次数预算转受限 final；[runtime.test.ts](../../frontend/src/stores/runtime.test.ts) L87–105 回放与 GET 答案收敛、L148–175 网络/快照不一致不标记 settled。
- [TaskSettlementPostgresIntegrationTest](../../backend/src/test/java/com/agentflow/agent/task/execution/TaskSettlementPostgresIntegrationTest.java) 存在事务回滚、锁仲裁与取消场景入口；本轮仅定位场景，没有执行或复核整套断言，不写 PostgreSQL PASSED。
- **DOC_DECLARED**：[V40 说明](../../V0.1-slice-docs/41_AGENT_TASK_EXECUTION_PACKAGE_INTERFACE.md) 声明历史受控 PostgreSQL 验收；[前端运行时说明](../../V0.1-slice-docs/44_FRONTEND_TASK_RUNTIME_PACKAGE_INTERFACE.md) 描述 Trace 重建和终态收敛。上述本轮代码已确认的关系才升级为 CODE_CONFIRMED；历史测试数字不升级为当前验收。
- [V47 真实 provider 验收脚本](../../scripts/v47-real-provider-acceptance.sh) 入口已读取，没有执行真实服务验收。工具布局通过与业务端到端运行证明分开。
- 施工指南列出的顺序中 decision 后再列 retrieval/tool；当前生产代码的检索为 **pre-retrieval → decision/tool loop**。据代码使用实际顺序，并保留此差异；没有修改业务/spec 文档。

### 与图 01 / 02 的一致性（语义核对，不是完成验收）

| 不变量 | 图 03 处理 |
| --- | --- |
| task status 与执行状态 | QUEUED 只是创建；claim 后 RUNNING；Engine outcome 尚未持久终态；成功 settlement 才发布 COMPLETED。phase 不替代 status。 |
| 配置版本与快照 | 新建事务选取/捕获不可变版本、解析实际快照；幂等命中复用原 task，不重新捕获。 |
| async / network / transaction | commit 后 JVM executor 派发；claim、Trace、settlement 短事务；慢 RAG/Chat/handler 不在 task 长事务内。图 01 Redis 仍不成为队列。 |
| provider cancellation | 本地等待结束、远端停止和数据库终态分开，不添加远端取消保证。 |
| SSE observation | 图 02 只读事件回读与图 03 的 GET/Trace/SSE 一致；Browser 不调用 Runner，重连不创建/恢复执行。 |
| restart recovery | 图 03 不画重启重跑；完整 recovery 留待相应图，不声称已完成独立 Audit。 |

## 图 03 历史未完成记录（已归档）

- 首版 source / HTML 已按文档文件名保存，validate 9/9 与 deliver 成功；浏览器首屏检查失败。为防止候选与旧 HTML 混淆，主 source 已恢复为成功 deliver 的精确字节，修正候选另存。
- Chrome 检查实际运行并获得四尺寸测量，均为纵向溢出；不是环境 unavailable/skipped。实际查看了 1440×900 light 和 2048×1320 dark 两张截图，底部工具/说明卡未完整进入首屏，视觉复核失败。未声称其他截图已人工复核。
- 第 1 轮视觉修正将六泳道收敛为三泳道、合并同一创建事务中的快照解析与写入。该候选未通过工具回环路由校验，未 deliver，未对旧 HTML 冒做候选 browser check。
- 最近两次针对 `wf-tool-decision` 的修正均未减少未解决问题：自动回环与 `wf-final-settle` 交叉；指定 return-left 后仍无法满足端口方向与可读路由约束。失败回执保留。
- Archify SKILL.md 明确要求：**“If two consecutive rounds do not improve that best count, stop and report the unresolved diagnostics truthfully.”** 已停止继续布局修正；不标记图 03 完成，不开始图 04。
- 未修改业务代码、图 01/02 产物、Archify 实现、规格或既有本地改动；未 commit/push。

| 首版回执 | SHA-256 | Bytes |
| --- | --- | --- |
| specification | `f7644a03c9f232546e0587a16fc0eeb2596c835bf28254f4067a3fce1cfbb366` | 7161 |
| artifact | `fa0817b55ea07f2efa4481d5f16d87b5ab21093ea08ffd93f60fe411242cb5cc` | 818082 |

| Viewport | scrollWidth × scrollHeight | Containment / readability / Viewer |
| --- | --- | --- |
| 1440×900 | 1440×1352 | fail / pass / pass |
| 1600×1000 | 1600×1463 | fail / pass / pass |
| 1920×1080 | 1920×1463 | fail / pass / pass |
| 2048×1320 | 2048×1488 | fail / pass / pass |

### 图 03 当前完成标准状态

- [x] 单一问题、workflow v2、证据先行；真实主链路、gate、边界与未知项已记录。
- [x] 当前正式 JSON / HTML 保存，逐边索引补齐，旧失败证据归档。
- [x] 四尺寸桌面首屏 containment / readability 与明暗截图视觉复核通过。
- [x] 紧凑候选及正式 source validate / deliver 9/9 showcase，0 errors / 0 warnings。
- [x] 图 03 完成；独立业务 runtime E2E 不在本张制图验收范围。
- [x] **未开始图 04–15，未生成最终独立 ARCHITECTURE_AUDIT.md。**

### 图 03 限定修复记录：显式 left ports

用户重新授权以 compact 三泳道 candidate 为唯一候选，仅修复 `wf-tool-decision`。本轮只为该 edge 增加 `fromSide: left` 与 `toSide: left`，已逐字段核对其他 node、edge、lane、card、mainPath 和 semanticChecks 完全不变。

执行 `validate workflow docs/architecture/03-task-e2e.candidate.workflow.json --quality showcase --json`，exit **1**；新回执保存于 [candidate.validation.json](history/03-earlier-route-attempts/left-ports.validation.json)。诊断仍为 `workflow/route-preset-conflict`，但 evidence 已确认为 **left → left**，route points 为 `(902.4,246) → (874.4,246) → (874.4,372) → (902.4,372)`，`supportedFixes` 为空。因此只修复自动 ports 选择尚不足以通过；本轮不推测未由该回执指出的更具体原因。

按用户要求在此停止；未运行 `--layout-json`，未替换正式 JSON，未 deliver，未 visual-check / visual review，未手改 HTML，未开始图 04。正式首版 source/HTML 及既有浏览器失败证据保持原样；图 03 仍未完成。未完成记录中的 candidate 字节身份已更新为本轮候选。

### 图 03 限定修复记录：RAG bottom-channel

以当前 compact 三泳道 candidate 为唯一候选，仅为 `wf-rag-decision` 增加 `route: bottom-channel`。未增加 fromSide/toSide/channelY/via/labelAt，`wf-tool-decision` 的 return-left + left/left 及其他全部图定义保持不变；已与施工前 JSON 逐字段核对。

执行用户指定的 showcase validate，exit **1**，stage `render`；完整回执保存于 [candidate.validation.json](history/03-earlier-route-attempts/rag-bottom-channel.validation.json)。本轮 causal diagnostic：

- code：`workflow/route-preset-conflict`。
- subject：workflow / `wf-tool-decision` / `wf-tool → wf-decision` / `return-left`。
- evidence：attemptedCandidateFamily 为 `return-left`；要求 endpoint stub ≥ 8px、interior turn ≥ 16px、direct clearance ≥ 28px。
- generated route points：`(854,246) → (826,246) → (826,372) → (854,372)`；按坐标计算段长为 `28 / 126 / 28 px`。
- chosen ports：`left → left`。
- supportedFixes：`[]`。

该回执说明显式 ports 和上述段长仍不足以通过完整路由约束，但没有指出具体的 corridor 冲突对象，不能据此确认 ingress corridor 竞争就是根因。本轮不继续推测性修改。

按用户要求停止；未执行 layout-json，未替换正式 JSON，未 deliver，未 visual-check / visual review，未修改已交付 HTML，未开始图 04。图 03 仍未完成。

### 图 03 限定修复记录：feedback labelSegment

2026-09-15（Asia/Hong_Kong），用户重新授权一次逻辑修复及 compiler predicate 诊断。唯一候选仍为 compact 三泳道 candidate；只删除 `wf-rag-decision.route: bottom-channel` 以恢复自动路由，并为 `wf-tool-decision` 增加 `labelSegment: 1`。return-left + left/left 保留；其他 edge 字段、lanes、nodes、尺寸、mainPath、semanticChecks、cards 均与施工前一致。

执行 `node .agents/skills/archify/bin/archify.mjs validate workflow docs/architecture/03-task-e2e.candidate.workflow.json --quality showcase --json`，exit **1** / stage `render`，完整新回执保存于 [candidate.validation.json](history/03-before-supported-auto/03-task-e2e.candidate.validation.json)。code 为 `workflow/route-preset-conflict`；subject 为 workflow / `wf-tool-decision` / `wf-tool → wf-decision` / `return-left`。

materialized points 为 `(902.4,246) → (874.4,246) → (874.4,372) → (902.4,372)`，chosen ports 为 left → left，段长 `28 / 126 / 28 px`；满足 endpoint stub ≥ 8px、interior turn ≥ 16px，preset topology 也通过。`labelSegment: 1` 得到 label point `(874.4,299)`，标签矩形为 `x=831, y=289, width=86.8, height=14`。这是失败候选在 feasibility 检查处的实际几何，不是成功交付的最终 layout。

按本轮明确授权读取 workflow compiler，在 `/tmp` 的 compiler 副本中进行一次诊断性 instrumentation：导入原始依赖，在 `readablePresetVia` materialize points 后、原可行性检查前，独立调用全部 predicate 并读取当前 pathCache 的障碍物。未改变原路由/判断逻辑；Skill 原文件从未改写，诊断后与施工前字节比较一致。完整结果及方法保存在 [candidate.predicates.json](03-task-e2e.candidate.predicates.json)，其诊断、points 与普通 CLI 回执一致，并绑定当前 candidate。探针退出 0 只表示采集完成，`compilerOk` 仍为 false。

| Predicate | 当前 materialized route 结果 |
| --- | --- |
| `orthogonalRoute` | PASS |
| `routeHonorsEndpointSides` | PASS |
| `routeMeetsHardRhythm` | PASS |
| `routeClearsEndpointNodes` | PASS |
| `routeClearsUnrelatedNodes` | PASS |
| `routeLabelClearsNodes` | PASS |
| `routeClearsPlacedLabels` | **FAIL** |
| `routeClearsLegend` | PASS |
| `routeClearsSceneLabelObstacles` | PASS |
| `routeClearsFrameBorders` | PASS |
| `routeFitsCanvasOrigin` | PASS |

唯一失败 predicate 为 **`routeClearsPlacedLabels`**。具体分支是 candidate route 对已经放置的其他 edge label 的 clearance 检查：回环 segment 1 `(874.4,246) → (874.4,372)` 穿过 `wf-rag-decision` 的“RAG 结果”标签矩形 `x=850, y=352, width=48.4, height=14`，实际 clearance 为 **0px**，要求 ≥ **4px**。本轮 feedback label 对所有节点均无 overlap，因此 `labelSegment: 1` 已消除本轮该标签与节点的冲突，但仍不足以让整条 return-left route 可行。

供诊断参照，当前已放置的 `wf-rag-decision` 自动路径为 `(846,372) → (902.4,372)`；compiler 的 col3 / col4 中心分别为 `771 / 977.4`（差 206.4px，节点水平净距 56.4px）。这些是失败候选的局部状态；未获得成功的完整 layout-json，不能据此宣称最终 proper crossing、ambiguous corridor、viewBox 或桌面验收通过。

本次 `supportedFixes` 为 `remove route from edge "wf-tool-decision" so readable-v2 can use its verified automatic candidate`。仅记录 CLI 建议，未应用；没有进行第二个图定义修改。按失败分支停止，未执行 layout-json、正式 JSON 替换、deliver、visual-check 或 visual review，未改已交付 HTML，未开始图 04。图 03 仍未完成。

### 图 03 限定修复记录：采用 supported automatic route

2026-09-15（Asia/Hong_Kong），基于 `c10ee75` 的候选，按上一轮 CLI `supportedFixes` 仅删除 `wf-tool-decision.route: return-left`；from/to、label、role、variant、left/left ports、`labelSegment: 1` 及所有其他图字段保持不变，已与修改前 JSON 深比较、Git 单行 diff 核对。未进行第二项布局调整。

本轮使用当前安装的原 CLI `.agents/skills/archify/bin/archify.mjs` 执行用户指定的 `validate workflow … --quality showcase --json`。Skill metadata 为 `2.17`，package 为 `2.17.0-dev.1`；本轮未修改 Skill、未使用 instrumentation，compiler 与上一轮诊断前保存的原文件字节一致。完整回执为 [当前 validation](history/03-before-width-128/03-task-e2e.candidate.validation.json)，实际命令及退出码保存在 [acceptance](history/03-before-width-128/03-task-e2e.acceptance.json)。

**实际结果：exit 1，stage check。9 项 artifact checks 全部通过，但 showcase composition 为 fail，errors = 1、warnings = 0。** properCrossings = 0、ambiguousCorridors = 0、labelRouteClearanceIssues = 0，最小 label-route clearance = 37px。上一轮路由冲突已不再阻塞；这些通过项不能替代完整 showcase 接受，也不是浏览器证据。

新诊断 `composition/desktop-readability` 的 subject 为 `check: composition`。1440×900 下 availableDiagramWidth = 930，viewBoxWidth = 1326，scale = 0.7013574660633484；context 文本“Bearer · 幂等 key”sourceFontPx = 8，projectedFontPx = **5.610859728506787**，低于 minimumProjectedFontPx = **6**。CLI supportedFixes 建议减小 viewBox 宽度、缩短节点文案、加宽受影响节点或拆图；仅保存建议，未应用，因为本轮只授权删除一个 route 字段。

| 当前候选验收层 | 状态 |
| --- | --- |
| Artifact validation | **FAILED**：9/9 artifact checks；showcase composition 1 error / 0 warnings |
| Browser evidence | **NOT_RUN**：本轮未成功 validate / deliver；四尺寸和主题覆盖均未运行 |
| Visual review | **NOT_RUN**：没有本轮交付截图可供视觉复核 |

旧失败回执、旧候选、探针和首版浏览器产物已按原字节保存在 [历史目录说明](history/03-before-supported-auto/README.md)。原 [predicate 文件](03-task-e2e.candidate.predicates.json) 保持不变，仅描述旧 return-left 候选，不能当作本轮自动路由的证据；本轮未沿用其路径或标签坐标。正式 source / HTML 和首版 browser / visual review 回执未更改，首版失败状态仍有效。

按本轮失败分支停止：未运行 layout-json，未替换正式 JSON、deliver、visual-check 或 visual review，未修改业务代码或验证标准。图 03 仍为 incomplete；未开始图 04。

### 图 03 完成记录：统一节点宽度 128px

2026-09-15（Asia/Hong_Kong），从 `fbf5442` 基线继续，只把 11 个节点的 width 从 150 改为 128，height 保持 68。深比较确认所有节点 id/lane/col/type/label/sublabel、edges、cards、mainPath、semanticChecks 与 showcase 均不变；未设置 meta.viewBox。`wf-tool-decision` 保持无 route 字段、left/left、labelSegment 1；`wf-rag-decision` 保持自动路由。未修改业务代码、Skill renderer/checker、字号或质量门槛。

修改前已原字节归档 candidate、失败 validation、acceptance、incomplete；额外执行 layout-json 诊断，exit 0，但没有把这项成功冒充完整 showcase 通过。缩宽后完整 validate 与 layout-json 均 exit 0；成功后冻结 candidate，原样复制正式 source，再执行正式 validate / deliver，全部 exit 0。实际命令及退出码均记在 [acceptance](03-task-e2e.acceptance.json)。

| 布局量 | width 150 基线 | width 128 交付 |
| --- | --- | --- |
| viewBox / requiredViewBox | 1326×534 / 1326×534 | 1194×534 / 1194×534 |
| columns | 123, 319.8, 555, 771, 977.4, 1227 | 112, 286.8, 500, 694, 878.4, 1106 |
| 最右侧节点边界 | 1302 | 1170 |
| lane 右边界 | 1310 | 1178 |
| 最小节点字号（标题 / 副标题） | 11 / 8px | 11 / 8px，全部 11 节点一致 |
| 930px 保守阅读宽度下最小 projected text | 5.61086px | 6.23116px ≥ 6px |

最右侧内容贡献者是 col5 的 `wf-observe`、`wf-settle`、`wf-final`。实际宽度减少 **132px = 6 个逻辑 rank × 22px**；相邻 rank 节点净距保持 `46.8 / 85.2 / 66 / 56.4 / 99.6px`。节点右侧到 lane 边框 8px，lane 到画布右缘 16px；1194px 比 1240px 上限留出 **46px** 余量。这是内容边界随节点尺寸收缩，非填写小 viewBox 裁切。所有节点矩形如下，完整原版 CLI 测量另存 layout JSON。

| Node | 基线 x / y / w / h | 当前 x / y / w / h |
| --- | --- | --- |
| `wf-submit` | 48 / 86 / 150 / 68 | 48 / 86 / 128 / 68 |
| `wf-observe` | 1152 / 86 / 150 / 68 | 1042 / 86 / 128 / 68 |
| `wf-gate` | 48 / 212 / 150 / 68 | 48 / 212 / 128 / 68 |
| `wf-persist` | 244.8 / 212 / 150 / 68 | 222.8 / 212 / 128 / 68 |
| `wf-tool` | 902.4 / 212 / 150 / 68 | 814.4 / 212 / 128 / 68 |
| `wf-settle` | 1152 / 212 / 150 / 68 | 1042 / 212 / 128 / 68 |
| `wf-dispatch` | 244.8 / 338 / 150 / 68 | 222.8 / 338 / 128 / 68 |
| `wf-claim` | 480 / 338 / 150 / 68 | 436 / 338 / 128 / 68 |
| `wf-rag` | 696 / 338 / 150 / 68 | 630 / 338 / 128 / 68 |
| `wf-decision` | 902.4 / 338 / 150 / 68 | 814.4 / 338 / 128 / 68 |
| `wf-final` | 1152 / 338 / 150 / 68 | 1042 / 338 / 128 / 68 |

当前原版 `fittedNodeFontSize` 使用 `floor(min(preferred, (width−8)/(textUnits×0.6))×10)/10` 并受 minimum 下限约束。逐节点调用原版函数确认 128px 未触发任何标题/副标题降字号，且已从 deliver 后实际 SVG 提取全部 **22 个 node text** 再核对：11 个标题都是 11px，11 个副标题都是 8px。checker 的成功回执中 `minProjectedNodeTextPx: null` 表示没有记录失败项，不据此虚构数值；6.23116px 是基于全部实际源字号和 930/1194 的独立计算，浏览器实际记录见下表。

最终自动 feedback points 为 `(814.4,246) → (798.4,246) → (798.4,365) → (814.4,365)`；`labelSegment: 1` 的实际 label point 是 `(798.4,295.5)`，label mask 为 `(755,285.5,86.8,14)`。当前 RAG 自动路径为 `(694,406) → (694,422) → (878.4,422) → (878.4,406)`，其 label point 是 `(786.2,412)`。没有沿用旧 return-left 标签位置。artifact metrics 确认 properCrossings = 0、ambiguousCorridors = 0、labelRouteClearanceIssues = 0、最小 label-route clearance = 36.6px；节点/标签/语义检查均通过。

#### 当前三个验收层

- **Artifact validation: passed。** candidate 与正式 source validate 均为 showcase 9/9、0 errors、0 warnings；deliver 成功，正式 source 与冻结 candidate 字节一致。
- **Browser evidence: passed。** 原版 visual-check exit 0，绑定本次新 HTML；默认 READ + Still，四尺寸 light 测量、1440×900 和 2048×1320 light/dark 截图覆盖完整。
- **Visual review: passed。** 实际查看上述四张截图，主路径/回环可追踪，RAG 与 feedback 标签分离，节点文字未溢出，三张说明卡与图例完整进入首屏；最大视口没有明显空白下半屏。本轮 correction_rounds = 1，仅指这次授权的宽度修复，此前失败轮次保留为历史。

| Viewport | scrollWidth × scrollHeight | 实际最小节点文本 | Containment / readability / Viewer |
| --- | --- | --- | --- |
| 1440×900 | 1440×900 | 8px | pass / pass / pass |
| 1600×1000 | 1600×1000 | 8px | pass / pass / pass |
| 1920×1080 | 1920×1080 | 8px | pass / pass / pass |
| 2048×1320 | 2048×1320 | 8px | pass / pass / pass |

补充交互复核尝试通过 In-app Browser 打开本地正式 HTML，被浏览器本地文件 URL 策略阻止；未绕过策略或改用间接访问。该补充项没有执行 search/focus/passport/export 动作，不记为这些动作通过。已完成的原版 Chrome 四尺寸自动测量和四张实际截图复核是独立证据，不受此限制影响；视觉判断限于默认阅读画面。

| 本次 deliver 身份 | SHA-256 | Bytes |
| --- | --- | --- |
| specification | `6407631ade299c25dd362054024889372a1ec93e91bc0b87163263025dc96812` | 6793 |
| artifact | `cc7d1242ad84286dc7216536270393b26779a0cdf82d5a7a1770341862a701c7` | 815273 |

旧失败回执与 probe 均保留；过时的主目录 incomplete 标记已移入历史，当前状态由 acceptance 表示。没有手改已交付 HTML；没有开始图 04，也未生成最终独立 Architecture Audit。

## 图 04 施工前工作记录

2026-09-15（Asia/Hong_Kong），当前 main / HEAD `460c6caf27f01383b0478a81b9acb3ce77b01187`，未 fetch。已重新读取下述生产代码与 migration；既有业务、规格、本地配置及 `.agents/` 未跟踪内容保持原状。用户标题中的 System Context 对应指南图 01；本轮按其明确编号及指南 §7 施工 **04 · Task Creation and Dispatch Sequence**，类型 **sequence**，不开始图 05。

唯一问题：一次 task create request 在后端内部严格按什么顺序处理，并在何时跨越 DB transaction、COMMIT 和 async dispatch？

### CODE_CONFIRMED nodes / edges

- API client → AgentTaskController.create → AgentTaskRestService.create → AgentTaskApplicationService.createTaskWithResult；owner 取 JWT principal，Idempotency-Key 映射 clientRequestId，DTO 接受可选 configVersionId。图将这三层同步薄入口聚合为一个生命线，真实类名逐边索引保留，不伪造新业务类。
- Application 在无创建事务的范围先 requireReady、校验、计算请求 fingerprint，再调用 AgentTaskQueryService.findByUserAndClientRequestId（独立 REQUIRES_NEW 只读）。同 owner/key 命中且 fingerprint 相同直接复用；不同则 TASK_IDEMPOTENCY_CONFLICT，不重选配置或派发。
- 仅未命中进入 AgentTaskCreationTransactionService.createNew 的 REPEATABLE_READ 事务。先 AgentConfigVersionTransactions.selectForTask：显式 id 取同 owner/agent 版本；省略/null capture 当前草稿，按内容复用或插入版本。不是选择“最新已发布版本”。
- 然后 AgentTaskSnapshotResolver.resolveConfiguration 基于版本配置选择与当前 owner、ACTIVE、READY、工具定义解析实际 snapshot。两个 resolver 的 REQUIRED 事务参与外层 RR；没有模型、向量、handler 慢调用。
- AgentTaskMapper.insertTask 写 QUEUED、execution_snapshot、config identity、预算；TaskEventAppender.append 在同一事务内分配序号并写 TASK_CREATED；随后 coordinator.dispatchAfterCommit 只注册 synchronization。
- 事务代理完成物理 COMMIT 后同步触发 AfterCommitTaskDispatchCoordinator.afterCommit → TaskDispatcher.dispatch 的当前实现 BoundedTaskDispatcher → agentTaskExecutor.execute(runDispatched)。executor 配置为本地 ThreadPoolTaskExecutor、AbortPolicy、agent-task- 前缀；不是 Redis/消息代理。
- TaskRunner.runDispatched → run 由 worker 执行。AgentTaskLifecycleTransactionService.claim 在 REQUIRES_NEW 中条件 UPDATE QUEUED 且未取消 → RUNNING/PREPARING，配对写 TASK_STARTED；没领到则退出。claim 提交返回后才可进入 Engine；本图到此停止，不展开图 05。
- 请求线程在 afterCommit 回调返回后逐层返回；HTTP 201/200 取决于 winning INSERT / 复用。worker claim 与 HTTP 响应之间无跨线程顺序保证；图用并行分支表达，不能解释为响应是 worker 启动门槛。

### CODE_CONFIRMED states / boundaries / failure paths

- Browser/JVM 的 HTTP/JWT、owner 与 DB JDBC 边界；同一创建 RR 事务、COMMIT、请求线程 afterCommit、executor async boundary、worker claim 独立事务分开。QUEUED 不等于运行中，RUNNING 是成功 claim 后的持久状态。
- V18 owner/clientRequestId 唯一约束；V23 不可变配置表与 task/config 联合 FK 支持上述持久关系。初始幂等读先于 createNew；唯一约束失败须等创建事务退出后独立回读 winner。40001 在无 winner 时最多重新进入创建事务 3 次；没有业务执行重试。
- auth/admission/配置/快照校验失败不进入新任务派发；回滚不触发 afterCommit。dispatch 抛异常时 coordinator 调用 TaskSettlementService.rejectDispatch，后者经 markDispatchRejected 的 REQUIRES_NEW 条件写 FAILED + TASK_FAILED；已有终态只读认可，持久化失败可能降级 admission，不能保证补偿必成功。
- Worker 已接收但 claim 前 admission/claim 抛异常，也由 runDispatched 走拒绝持久化；claim 返回 null 直接退出。只作必要失败旁注，不展开完整并发/恢复图。

### TEST_CONFIRMED / DOC_DECLARED / UNKNOWN

- 已读取 AfterCommitTaskDispatchCoordinatorTest 的回滚不派发、只在 afterCommit 派发、拒绝委托 settlement 断言；AgentTaskApplicationServiceTest 的幂等直接复用/冲突、失败事务后 winner 回读、created 不受 task status 影响、显式版本与省略请求 fingerprint 区别、40001 有限重试断言。均是测试代码静态确认，本轮未执行，不记为新测试 PASSED。
- V0.3 配置切片文档描述显式版本与省略捕获当前草稿；V0.1 task execution 文档含历史 snapshot/runtime 声明。前者既有 dirty 状态保持，文档与历史验收不能替代当前代码。v03a-postgres-acceptance.sh 已定位，未执行真实 PostgreSQL/provider 验收。
- 当前 frontend api.createTask 只发送 userInput；后端 DTO 支持 configVersionId，不能画成当前 UI 已提供版本选择。图的 API Client 包含直接 API 调用者。
- 未证明实际部署 profile、网络/DB 可用性、异步排队时长、worker 与 response 的具体交错或真实创建成功率；未运行后端、数据库、provider 或业务 E2E。

## 图 04 Evidence index

- **Diagram:** 04 · Task Creation and Dispatch Sequence（完成）。
- **Question:** 一次 task create request 在后端严格按什么顺序处理，并在何时跨越 COMMIT 与 async dispatch？
- **Type:** `sequence` / schema v1 / showcase；7 条职责生命线、17 条消息、3 个时段。
- **Primary path:** JWT/owner → admission + 幂等回读 → 新建 RR → selectForTask → resolveConfiguration → task/event → 注册回调 → COMMIT → afterCommit → executor → worker claim。
- **Key nodes:** 下表给出显示别名与实际类映射；这些是同步职责聚合，不是新增类或新服务。
- **Key evidence:** 下列逐消息代码/方法索引；V18 owner/key 唯一约束、任务事件表和 V23 配置身份约束。
- **Unknowns:** 未进行新的业务测试、真实 PostgreSQL/provider/E2E；实际配置、排队时长、HTTP/worker 的具体交错与真实失败率未测。Archify 通过不升级为业务运行证明。
- **JSON / HTML:** [正式 sequence](04-task-create.sequence.json)、[冻结 candidate](04-task-create.candidate.sequence.json) 字节一致；[正式 HTML](04-task-create.html)。
- **Artifact validation:** [candidate](04-task-create.candidate.validation.json)、[正式 validate](04-task-create.validation.json)、[deliver](04-task-create.delivery.json) 均 exit 0 / showcase 9/9 / 0 errors / 0 warnings。
- **Browser evidence:** [visual-check](04-task-create.visual-check.json) `pass`；四个 desktop viewport containment/readability/Viewer 通过，light/dark 端点截图齐全。
- **Visual review:** [实际截图复核](04-task-create.visual-review.json) `passed`；[截图索引](04-task-create.visual-check.html)。只评价真实截图中的默认 READ + Still 状态，不声称交互功能/E2E 已测试。
- **Acceptance:** [命令、退出码及独立三层状态](04-task-create.acceptance.json)。

### 图 04 参与者到当前实现的映射

| 显示生命线 | 当前真实类 / 方法 |
| --- | --- |
| API Client | 当前 frontend `api.createTask` 与直接 HTTP API client；后端完整接口 `POST /api/v1/agents/{agentId}/tasks`。当前 UI 只发送 userInput，不能推断已有版本选择 UI。 |
| 创建入口 | `AgentTaskController.create → AgentTaskRestService.create → AgentTaskApplicationService.createTaskWithResult`；幂等读取委托 `AgentTaskQueryService`。 |
| 创建事务 | `AgentTaskCreationTransactionService.createNew` 及包围它的 Spring 事务代理；绿色激活条表达创建 DB transaction 区间。 |
| 配置与快照 | `AgentConfigVersionTransactions.selectForTask/configuration`，随后 `AgentTaskSnapshotResolver.resolveConfiguration`。分别是版本选择与实际依赖解析。 |
| PostgreSQL | `AgentTaskMapper`、`AgentTaskEventMapper`、`AgentConfigVersionMapper` 等 SQL 的持久化端。 |
| 提交后派发 | `AfterCommitTaskDispatchCoordinator` 注册/回调 → `TaskDispatcher` 接口的 `BoundedTaskDispatcher` 实现 → `agentTaskExecutor`。 |
| TaskRunner | `TaskRunner.runDispatched/run`；通过 `AgentTaskLifecycleTransactionService.claim` 领取。 |

### 图 04 逐消息证据

每行均为 **CODE_CONFIRMED 静态证据**。箭头表示方法委托、返回或标注过的框架动作；不是本轮网络调用日志。`tc-lookup-result` 标签中的复用/继续由 Application 决定，数据库只返回已有行或空值。

| Message ID | Source → target | Mechanism / sync-async | Source / class / method | Persistence / boundary |
| --- | --- | --- | --- | --- |
| tc-request | API Client → 创建入口 | HTTP POST；JWT principal owner；Controller → Rest → Application 同步委托 | [AgentTaskController](../../backend/src/main/java/com/agentflow/agent/task/controller/AgentTaskController.java) L38 `public ResponseEntity` → [AgentTaskRestService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskRestService.java) L43 `public CreateTaskResponse create` | Idempotency-Key → clientRequestId；DTO 的 configVersionId 可选；此时尚未创建事务 |
| tc-lookup | 创建入口 → PostgreSQL | admission/校验/fingerprint 后独立 REQUIRES_NEW readOnly 回读 | [AgentTaskApplicationService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskApplicationService.java) L45 `public CreateAgentTaskResult createTaskWithResult` → [AgentTaskQueryService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskQueryService.java) L20 `public AgentTask findByUserAndClientRequestId` → [AgentTaskMapper](../../backend/src/main/java/com/agentflow/agent/task/repository/AgentTaskMapper.java) L68 `AgentTask selectByUserAndClientRequestId` | 以 user_id + client_request_id 查找；非创建事务中的读 |
| tc-lookup-result | PostgreSQL → 创建入口 | 同步返回 existing/null；Application 才比较 fingerprint | [AgentTaskApplicationService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskApplicationService.java) L80 `private static AgentTask sameRequestOrConflict` / [TaskRequestFingerprint](../../backend/src/main/java/com/agentflow/agent/task/service/TaskRequestFingerprint.java) L35 `public Fingerprint calculate(long agentId, String originalInput, Long` | 命中相同请求直接返回 created=false；不进入后续新建链，冲突抛 TASK_IDEMPOTENCY_CONFLICT |
| tc-create-new | 创建入口 → 创建事务 | createNew 的事务代理建立 REPEATABLE_READ；再次 admission | [AgentTaskCreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) L61 `public AgentTask createNew` | 仅未命中才发生；失败回滚后 App 的独立查询可读并发 winner |
| tc-select-version | 创建事务 → 配置与快照 | 同步 selectForTask，默认 REQUIRED 加入创建事务 | [AgentConfigVersionTransactions](../../backend/src/main/java/com/agentflow/agent/configversion/AgentConfigVersionTransactions.java) L80 `public AgentConfigVersion selectForTask` / [AgentConfigVersionTransactions](../../backend/src/main/java/com/agentflow/agent/configversion/AgentConfigVersionTransactions.java) L50 `public Published capture` | 显式版本验证同 owner/agent；省略/null 捕获当前草稿并按内容复用/插入版本 |
| tc-version | 配置与快照 → 创建事务 | 同步返回选定的 AgentConfigVersion | [AgentConfigVersionTransactions](../../backend/src/main/java/com/agentflow/agent/configversion/AgentConfigVersionTransactions.java) L141 `private AgentConfigVersion requiredVersion` / [AgentConfigVersionTransactions](../../backend/src/main/java/com/agentflow/agent/configversion/AgentConfigVersionTransactions.java) L101 `public AgentConfiguration configuration` | 不可变配置选择仍不是实际执行 snapshot；新 task 会关联版本 |
| tc-resolve | 创建事务 → 配置与快照 | 随后调用 resolveConfiguration，同步参与同一 RR | [AgentTaskCreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) L64 `AgentTaskExecutionSnapshot snapshot =` → [AgentTaskSnapshotResolver](../../backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskSnapshotResolver.java) L137 `public AgentTaskExecutionSnapshot resolveConfiguration` | 读取当前 owner/Agent ACTIVE、知识 READY generation、工具 schema/handler；不调用模型/向量服务 |
| tc-snapshot | 配置与快照 → 创建事务 | 同步返回实际 AgentTaskExecutionSnapshot | [AgentTaskSnapshotResolver](../../backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskSnapshotResolver.java) L137 `public AgentTaskExecutionSnapshot resolveConfiguration` / [AgentTaskSnapshotResolver](../../backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskSnapshotResolver.java) L229 `private RetrievalSnapshot resolveRetrievalRows` | 冻结运行所需实际值；READY 文档总数为 0 时拒绝创建 |
| tc-insert | 创建事务 → PostgreSQL | 顺序 insertTask → eventAppender.append → eventMapper 增序号/insertEvent | [AgentTaskCreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) L98 `if (taskMapper.insertTask(task) != 1)` / [TaskEventAppender](../../backend/src/main/java/com/agentflow/agent/task/service/TaskEventAppender.java) L33 `public long append` / [AgentTaskEventMapper](../../backend/src/main/java/com/agentflow/agent/task/repository/AgentTaskEventMapper.java) L22 `Long incrementAndGetSequence` | 同一事务写 QUEUED、execution_snapshot、版本身份、预算、TASK_CREATED；尚未执行 |
| tc-register | 创建事务 → 提交后派发 | 同步注册 TransactionSynchronization；此时不 dispatch | [AgentTaskCreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) L104 `dispatchCoordinator.dispatchAfterCommit` → [AfterCommitTaskDispatchCoordinator](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinator.java) L30 `public void dispatchAfterCommit` | 要求实际事务与 synchronization 均 active；回滚不会触发 afterCommit |
| tc-commit | 创建事务 → PostgreSQL | Spring 事务代理物理 COMMIT；不是业务类手写 commit 方法 | [AgentTaskCreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) L60 `@Transactional(isolation = Isolation.REPEATABLE_READ)` / [AfterCommitTaskDispatchCoordinator](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinator.java) L37 `public void afterCommit` | task/snapshot/TASK_CREATED 原子可见后，才允许回调派发 |
| tc-after-commit | 创建事务代理 → 提交后派发 | 请求线程的 afterCommit 回调同步调用 TaskDispatcher.dispatch | [AfterCommitTaskDispatchCoordinator](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinator.java) L37 `public void afterCommit` → [TaskDispatcher](../../backend/src/main/java/com/agentflow/agent/task/dispatch/TaskDispatcher.java) L6 `void dispatch` → [BoundedTaskDispatcher](../../backend/src/main/java/com/agentflow/agent/task/dispatch/BoundedTaskDispatcher.java) L27 `public void dispatch` | afterCommit 在 COMMIT 后，不等同于新 worker；拒绝走独立 settlement |
| tc-async | 提交后派发 → TaskRunner | 本地 ThreadPoolTaskExecutor.execute(lambda)，跨入 agent-task-* worker | [BoundedTaskDispatcher](../../backend/src/main/java/com/agentflow/agent/task/dispatch/BoundedTaskDispatcher.java) L30 `executor.execute` / [AgentTaskDispatcherConfiguration](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AgentTaskDispatcherConfiguration.java) L17 `public ThreadPoolTaskExecutor agentTaskExecutor` → [TaskRunner](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) L101 `public void runDispatched` | 有界 executor + AbortPolicy；图不引入 Redis queue 或分布式消息投递 |
| tc-created | 创建事务 → 创建入口 | 事务代理及 afterCommit 返回后，App 形成 CreateAgentTaskResult(created=true) | [AgentTaskApplicationService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskApplicationService.java) L60 `return new CreateAgentTaskResult(creationTransactionService.createNew` | 新建成功由 winning INSERT 决定；并发 loser 用事务外 winner 回读返回 created=false |
| tc-response | 创建入口 → API Client | HTTP 响应；主链路新建 201，复用分支 200 | [AgentTaskController](../../backend/src/main/java/com/agentflow/agent/task/controller/AgentTaskController.java) L45 `return ResponseEntity.status` / [AgentTaskRestService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskRestService.java) L52 `return new CreateTaskResponse` | 与 worker claim 没有跨线程 happens-before 保证；不证明实际执行状态 |
| tc-claim | TaskRunner → PostgreSQL | worker → lifecycle.claim 的 REQUIRES_NEW 短事务 | [TaskRunner](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) L59 `public void run(long taskId)` → [AgentTaskLifecycleTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java) L51 `public AgentTask claim` → [AgentTaskMapper](../../backend/src/main/java/com/agentflow/agent/task/repository/AgentTaskMapper.java) L97 `AgentTask claimQueued` | 仅 QUEUED 且未持久取消可写 RUNNING/PREPARING、startedAt；取得行才配对 TASK_STARTED |
| tc-claimed | PostgreSQL → TaskRunner | claim 事务提交后返回 task；空结果直接退出 | [AgentTaskLifecycleTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java) L51 `public AgentTask claim` / [TaskRunner](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) L61 `AgentTask task = lifecycleTransactions.claim` | 成功领取后才解析持久 snapshot 并在无数据库事务时进入 Engine；本图止于该边界 |

### 失败分支、状态与证据边界

- **幂等先行：** `AgentTaskApplicationService` 对同 owner/key 先查后建；fingerprint 由原始 input、agentId、显式 configVersionId 的请求域决定。显式版本变更、显式与省略形态改变都可能冲突，不以“最终配置相同”替代请求一致性。
- **并发失败回读：** DataIntegrityViolation 退出创建事务代理后才调用 QueryService 的 REQUIRES_NEW；无 winner 传播原错误。有序列化异常时先回读，未命中最多 3 次完整创建尝试，不重试 Engine/provider。图以卡片保留分支，不展开全并发图。
- **拒绝补偿：** `AfterCommitTaskDispatchCoordinator.compensateRejectedDispatch → TaskSettlementService.rejectDispatch → AgentTaskLifecycleTransactionService.markDispatchRejected`，后者 REQUIRES_NEW timeout=5，仅 QUEUED 且未取消行转 FAILED/SYSTEM_ERROR/TASK_DISPATCH_REJECTED，并同事务写 TASK_FAILED。已终态可认可；写失败可能关闭 admission，不能画成必然落库成功。`TaskRunner.runDispatched` 还处理已接收但进入 claim 前抛异常的情况。该边界与图 03 的“outcome 不等于持久终态”一致。
- **响应与执行：** 并行分支中的横向对齐仅表示无跨线程顺序约束，并不表示它们同一时刻发生。请求线程先完成 afterCommit 调用；worker 可能在响应之前或之后 claim。201 只表示此次 winning INSERT，200 只表示复用；任何一项均不是 RUNNING/COMPLETED 的证明。
- **DB transaction 范围：** 中间时段的创建事务拥有配置/快照/task/event 语义，不表示 Browser/worker 加入事务；最后 claim 是另一个物理事务。创建流程中没有模型/向量/工具慢调用。COMMIT 箭头表达事务代理的 JDBC 提交，不虚构 `createNew()` 里的手工 commit 调用。
- **持久结构：** [V18 migration](../../backend/src/main/resources/db/migration/V18__create_agent_task_and_event.sql) 的 `UNIQUE(user_id, client_request_id)`、task/event FK 与 event 序号唯一约束；[V23 migration](../../backend/src/main/resources/db/migration/V23__create_agent_config_version.sql) 的不可变配置 trigger、内容唯一约束与 task/config 联合 FK；[TaskStatus](../../backend/src/main/java/com/agentflow/agent/task/model/TaskStatus.java) 区分 QUEUED/RUNNING/终态。图未把 phase PREPARING 当作 status。
- **TEST_CONFIRMED（只读断言，未执行）：** [ApplicationServiceTest](../../backend/src/test/java/com/agentflow/agent/task/service/AgentTaskApplicationServiceTest.java) 覆盖复用、不再创建、fingerprint 冲突、并发 winner 回读、created 不依赖 task status、显式版本/请求形态与有限 40001 重试；[AfterCommitTest](../../backend/src/test/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinatorTest.java) 覆盖 rollback/afterCommit/拒绝委托。未把直接手动触发 synchronization 的 mock 测试当成真实 DB COMMIT 或线上线程证明。
- **DOC_DECLARED / UNKNOWN：** [V0.3 配置切片](../../V0.3-slice-docs/01_CONFIG_VERSION_AND_EVALUATION_PACKAGE_INTERFACE.md) §3.3 与当前省略/null capture 事实一致；[V0.1 执行切片](../../V0.1-slice-docs/41_AGENT_TASK_EXECUTION_PACKAGE_INTERFACE.md) 的历史 snapshot/验收数字不作为本轮结果；[v03a 验收脚本](../../scripts/v03a-postgres-acceptance.sh) 仅定位未运行。指南候选名 `AgentTaskDispatcher` 以当前实际 `TaskDispatcher` + `BoundedTaskDispatcher` 替代。

### 图 04 修复与验收记录

1. 首版因 7 组消息在相同横向空间内间隔不足 28px 而 render 失败；按诊断统一调整 y，并同步事务框、activation 与完整画布高度。原候选与回执保存在 [消息间距历史](history/04-message-spacing/04-task-create.candidate.validation.json)。
2. 随后 artifact checks 9/9，但 7px participant context 在 1120px viewBox 的保守投影仅 5.8125px。按 supportedFix 缩紧 `column_fit: spread` 的实际排布至 1060px，得到 6.14151px；未减字号、未隐藏 overflow、未裁切。原失败证据保存在 [可读宽度历史](history/04-before-readable-width/04-task-create.candidate.validation.json)。
3. 首次 deliver 通过，但浏览器 1440×900 高出 26px、1600×1000 高出 2px。真实截图显示冗余仓库名后缀使标题换为两行；仅去掉该后缀，保留图名、全部参与者/消息/卡片。重新 validate / deliver / visual-check 全部通过。首版 source/HTML/浏览器回执及截图保存在 [标题修复前历史](history/04-before-title-fit/04-task-create.visual-check.html)。
4. 当前 17 条消息、7 个参与者的 artifact checks 9/9，composition errors = 0、warnings = 0；properCrossings = 0、ambiguousCorridors = 0、labelRouteClearanceIssues = 0。实际 viewBox 1060×664；不把成功 CLI 检查称为业务架构运行验收。
5. 实际查看 1440×900 与 2048×1320 的 light/dark 四张截图；标题单行，主时序、事务/COMMIT/async 边界、并行分支、图例与说明卡完整可见。最大视口主图和卡片合理占据纵向空间，没有明显空白下半屏。默认画面无残留浮层；未另行声称搜索/聚焦/导出动作测试通过。visual `correction_rounds: 1` 只计一次标题视觉修复，前两次为交付前诊断修复。

| Viewport | scrollWidth × scrollHeight | 最小节点文本 | Containment / readability / Viewer |
| --- | --- | --- | --- |
| 1440×900 | 1440×900 | 6.1415px | pass / pass / pass |
| 1600×1000 | 1600×1000 | 6.5047px | pass / pass / pass |
| 1920×1080 | 1920×1080 | 7px | pass / pass / pass |
| 2048×1320 | 2048×1320 | 7px | pass / pass / pass |

| 本次 deliver 身份 | SHA-256 | Bytes |
| --- | --- | --- |
| specification | `45a640ccc53d4bcaff3bcb5342a408c87a0c9a4a12d9d73102964a0a1c19b92c` | 5769 |
| artifact | `8693f8146a44ca3ddd5da911a26a8574f80ade4d211c7df94ec20a795a1fa403` | 816209 |

### 图 04 完成标准

- [x] 只回答创建请求的调用顺序与 COMMIT/async 边界，按指南图 04 使用 sequence；没有重画图 01 System Context。
- [x] 初始幂等回读、显式/省略版本、实际 snapshot、task/event 写入、回调注册/触发、拒绝补偿、worker claim 均追踪到当前生产代码与持久结构。
- [x] 事务内、COMMIT 后、executor 异步及 claim 独立事务显式区分；不混同任务创建、执行与 HTTP 响应。
- [x] source/HTML 已保存，validate/deliver 通过，逐消息证据、未知项与历史失败回执完整。
- [x] 四桌面尺寸 browser evidence 与双主题实际截图 visual review 通过；分别记录三层验收。
- [x] 图 01–03 产物与业务代码保持不变。图 04 完成后停止，未开始图 05，未生成最终独立 Architecture Audit。


## 图 05 施工前工作记录

2026-09-15（Asia/Shanghai），main / HEAD `460c6caf27f01383b0478a81b9acb3ce77b01187`，未 fetch；基于当前 dirty working tree。图 04 已交付但未提交，保留全部产物；既有业务、规格和 Skill 文件不改。使用本地 Archify metadata 2.17 / package 2.17.0-dev.1。此记录先于图 05 Typed JSON 写入。

唯一问题：task 已进入 runtime 后，如何从冻结上下文完成一次预检索、decision/tool 回环与独立 final generation？类型 workflow v2 / showcase；只施工图 05，不开始图 06。

### CODE_CONFIRMED nodes

- `loop-entry`：AgentEngineTaskExecutionDelegate.execute → DefaultAgentEngine.execute(TaskExecutionRequest) → TaskSnapshotAgentExecutor.execute；该重载使用持久任务快照，不是旧 AgentExecutionCommand 路径。validate 检查快照/协议后打开 recorder。
- `loop-rag`：SnapshotRagService.retrieve，一次 PRE_RETRIEVAL；query 是 userInput，语料来自 snapshot.retrieval，按 owner/document generation 回查 canonical chunk。空语料不调用 embedding/vector；空命中可继续。
- `loop-budget`：while 顶部 State.boundary、maxDecisionTurns/maxToolCalls，以及 decision 前 outputCap 的总 token/context window/final reserve 检查。节点聚合每轮门禁，不表示 token 一定在 prompt 构造前估算。
- `loop-decision`：TaskPromptBuilder.decision → callLlm(DECISION) → LlmGateway.chat → AgentDecisionParser.parse；模型调用与严格整段 JSON 解析合并为职责节点。只能 CALL_TOOL 或 FINISH；工具选择限于 frozen availableTools。
- `loop-tool`：invokeTool → task-scoped DefaultToolRuntime；当前只支持 order_query/payment_log_query 的只读 builtin，执行前检查 live ACTIVE/冻结 schema/实现/参数/取消期限。成功结果转 observations；第二次相同意图经重新验证复用缓存，第三次中止。
- `loop-limited`：达到 decision/tool 次数上限时设置 termination reason，追加只读 BUDGET_LIMIT observation，并生成受限 answerPlan；不是新模型调用。
- `loop-final`：TaskPromptBuilder.finalAnswer → outputCap → 独立 FINAL_GENERATION 模型请求 → validateCitations；final cap 必须保有 frozen reserve，引用只可来自预检索白名单。
- `loop-outcome`：TaskExecutionOutcome.completed 返回答案、使用量、计数与引用；Engine 不写持久终态。Runner 后续结算不在本图展开。

### CODE_CONFIRMED edges / boundaries

- entry → RAG → 每轮门禁 → decision/parse 是 TaskSnapshotAgentExecutor.execute 内顺序控制流。RAG 不是模型可选择的新 action，也不在工具回环中重跑。
- decision → tool 仅 CALL_TOOL；tool → budget 携带成功/复用 observation，下一轮重建 prompt。RAG evidence、citationIds 与全部 observations 同时进入 decision 和 final 的 common payload；它们都是 UNTRUSTED_DATA，不是持久跨任务 memory。
- decision → final 仅 FINISH，answerPlan 可表达缺少输入并请求用户以新任务补充。FINISH 不表示问题已解决，也没有 WAIT / 自动恢复 action。
- budget → limited → final 仅次数上限；最终调用仍受 token/取消/期限限制，可能失败。没有工具可选时 parser 拒绝 CALL_TOOL；maxToolCalls=0 会在首轮直接走受限 final。
- final → outcome 需调用、引用与边界校验成功。其余失败由 execute catch/failure 映射 FAILED、CANCELLED 或 TIMED_OUT outcome，使用量已测得的部分保留，不等于 DB 结算成功。
- 工作流箭头表示调用/分支与数据携带，不是不同进程或一条长事务。外部 I/O 通过 TaskExternalCallDeadline 的实际工作虚拟线程执行，worker 有界等待并观察取消/期限；Future.cancel 不能证明 provider 停止。Recorder/phase 各自短事务，慢 I/O 不持有数据库事务。

### CODE_CONFIRMED failure / trace facts

- malformed/未知字段/重复键/未知工具 → AGENT_INVALID_DECISION；该 Engine 分支不做自动 JSON 修复或重试模型调用。重复意图第三次 → AGENT_DUPLICATE_TOOL_LOOP；工具撤销/参数/调用失败中止。
- token/context 空间不足、provider 输出限制/失败、未知引用、取消及 task deadline 均可阻止最终答案。单次模型超时 AGENT_LLM_TIMEOUT 与整任务 TASK_TIMED_OUT 分开；远端是否继续计算 UNKNOWN。
- retrieve/callLlm/invokeTool 调用 startStep/completeStep/failStep，记录 RAG/LLM/tool facts；RAG_FINISHED、DECISION_FINISHED、TOOL_STARTED/FINISHED、FINAL_GENERATION_STARTED 是阶段事件，不是 TASK_COMPLETED。缺失 provider usage 保守 ESTIMATED，混合为 MIXED；失败 DECISION 日志不写原始 malformed response。

### TEST_CONFIRMED facts

已逐项读取 TaskSnapshotAgentExecutorTest 的断言，**本轮未执行，不记为新 PASSED**：executesFrozenRagDecisionToolsAndSeparateFinalGenerationWithOrderedFacts；emptyRagStillAllowsToolsAndToolBudgetForcesARestrictedFinal；decisionBudgetForcesFinalWithoutAnotherDecisionRequest；canonicalDuplicateReusesSecondObservationAndRejectsThirdWithoutCallingRuntimeAgain；malformedDecisionPersistsSafeFailedLogWithUsageAndLatencyWithoutRawResponse；actualOverBudgetUsageIsRetainedBeforeFailure；insufficientBudgetBlocksProviderBeforeFirstDecision；cancellationAfterProviderResponsePreservesUsageAndStopsFurtherIo；deadlineAfterProviderResponsePreservesUsageAndStopsFurtherIo；fabricatedFinalCitationFailsAfterRecordingFinalUsage；missingLookupIdentifiersCanFinishWithASeparateClarificationWithoutCallingTools；plainTextClarificationWithNormalStopIsStillAnInvalidDecisionAndIsNotRetried。这些是受控依赖断言，不是本轮真实 provider/E2E 证据。

### DOC_DECLARED only / UNKNOWN

指南 §8 提及 retrieval selection；当前实现只有 snapshot-scoped PRE_RETRIEVAL，并无模型选择 retrieval action。图遵循已读代码，不从 Agent 概念加入 planner、reflection、memory、self-correction 或 autonomous sub-agent。历史切片/验收数字不作本轮成功证据。未启动后端、DB、向量服务或 LLM，未执行真实 provider 请求；目标部署连通性、取消后的远端行为、回答质量、实际耗时和失败率均 UNKNOWN。图 05 artifact/browser/visual review 在验收前均 pending。

## 图 05 Evidence index

- **Diagram:** 05 · Agent Execution Loop（完成）。
- **Question:** task 已进入 runtime 后，如何从冻结上下文完成一次预检索、decision/tool 回环与独立 final generation？
- **Type:** `workflow` / schema v2 / readable-v2 / showcase；8 个节点、9 条关系、3 个职责泳道。泳道不表示线程池、服务进程或数据库事务。
- **Primary path:** TaskExecutionRequest → 一次 PRE_RETRIEVAL → 每轮门禁 → DECISION + strict parse → FINISH plan → 独立 final + 引用校验 → TaskExecutionOutcome；CALL_TOOL 经 observation 回到门禁。
- **Key nodes / evidence:** 施工前工作记录中的 8 个节点及下表逐边方法索引。`loop-decision` 合并模型调用与解析、`loop-final` 合并生成与引用校验，未虚构新的 Service。`database` 类型在 workflow 图例表示“上下文 / 追踪”，RAG 节点不是另一个数据库服务。
- **Unknowns:** 未运行新的业务单测/集成测试、PostgreSQL、真实模型/向量 provider；性能、回答质量、实际外部调用终止、当前目标部署连通性均未测。图的三层验收不升级为业务 E2E。
- **JSON / HTML:** [正式 workflow](05-agent-loop.workflow.json)、[冻结 candidate](05-agent-loop.candidate.workflow.json) 字节一致；[正式 HTML](05-agent-loop.html)。
- **Artifact validation:** [candidate validate](05-agent-loop.candidate.validation.json)、[正式 validate](05-agent-loop.validation.json)、[deliver](05-agent-loop.delivery.json) 均 exit 0，showcase 9/9，composition errors=0 / warnings=0。
- **Browser evidence:** [原版 visual-check](05-agent-loop.visual-check.json) exit 0 / pass；四尺寸 containment、readability、Viewer chrome 通过。回执内 `visualReview: pending` 按工具契约原样保留。
- **Visual review:** [独立截图复核](05-agent-loop.visual-review.json) passed / correction_rounds=0；实际打开 [四张明暗截图](05-agent-loop.visual-check.html) 后形成，不以机器检查冒充视觉判断。只评默认 READ + Still 的可见布局；没有声称 search/focus/passport 操作或导出验收。
- **Acceptance:** [本轮原始命令、退出码及三层状态](05-agent-loop.acceptance.json)；[布局 receipt](05-agent-loop.layout.json)；[全部节点字体测量](05-agent-loop.layout-measurements.json)。

### 图 05 逐边 CODE_CONFIRMED 证据

边表示 Engine 中顺序控制流、条件分支或回到循环，并非每条边都新增跨线程、跨网络或数据库提交。外部调用的真实边界另列于表后。

| Edge ID | Source → target / condition | Current class / method evidence | Mechanism / data / persistence |
| --- | --- | --- | --- |
| loop-entry-rag | 快照执行入口 → 一次预检索 | [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) L95 `execute`，L168 `retrieve`；[SnapshotRagService](../../backend/src/main/java/com/agentflow/agent/rag/SnapshotRagService.java) L65 `retrieve(request,boundaryCheck)` | 校验快照、打开 recorder 后顺序调用一次；PRE_RETRIEVAL step，userInput 查询冻结 KB/document generation，结果日志和 RAG_FINISHED。标签省略因为两个节点已完整表达顺序动作，不省略额外协议/条件。 |
| loop-rag-budget | 一次预检索 → 每轮门禁 | 同一 `execute`：`rag = retrieve(state)` 位于 `while(true)` 之前 | RAG evidence/hits 保留在本次执行局部状态；空命中仍进入 gate，无重复检索。节点“可空证据”及卡片已表达此条件，边未加重复标签。 |
| loop-budget-decision | 轮次 / token 允许 → DECISION + parse | `execute` 的 while 顶部；L377 `outputCap`；[TaskPromptBuilder](../../backend/src/main/java/com/agentflow/agent/engine/TaskPromptBuilder.java) L71 `decision`；Executor L210 `callLlm`；[AgentDecisionParser](../../backend/src/main/java/com/agentflow/agent/engine/AgentDecisionParser.java) L29 `parse` | 次数门禁后构造 messages，估算 decision input，预留 final prompt + reserved_final_tokens，再调用模型；State.boundary 可随时中止。严格 whole-response JSON，无多余字段/重复键；只选 frozen availableTools。 |
| loop-decision-tool | CALL_TOOL → 快照工具调用 | `AgentDecisionParser.parseToolCall` L53；Executor L327 `invokeTool`；[DefaultToolRuntime](../../backend/src/main/java/com/agentflow/tool/DefaultToolRuntime.java) L170 `executeTask`、L254 `validateTaskTool` | 按 frozen toolCode 找 toolId；taskScoped command 带 task/step/owner/snapshot/deadline/probe。实际 handler 前仍读当前 ACTIVE、核验 schema/实现/参数；不是任意网络工具或自动 sub-agent。 |
| loop-tool-budget | 成功/复用 observation → 下一轮门禁 | Executor `invokeTool` 向 `state.observations` 追加，返回 while；PromptBuilder L98 `observation`、L110 `common` | 按 toolCode + canonical arguments 识别重复；首次执行、第二次 validateTaskSnapshot 后复用，第三次失败。下一轮 decision 和后续 final 都取得完整当前 observations；不重新 PRE_RETRIEVAL。 |
| loop-decision-final | FINISH → 独立 final | Parser L79 `parseFinalAnswer`；Executor `execute` 的 `FinalAnswerDecision` 分支 / break → `prompts.finalAnswer`；PromptBuilder L89 `finalAnswer` | FINISH 提供 answerPlan，不提供已发布答案。独立文本生成请求使用同一 RAG、observations；没有 CALL_TOOL/FINISH 输出 schema，仍受总预算与 frozen reserve 约束。 |
| loop-budget-limited | 次数达到上限 → BUDGET_LIMIT / 受限计划 | Executor `execute`：`MAX_DECISION_TURNS` / `MAX_TOOL_CALLS` → break → `reason != ANSWERED` | 追加 `{type:BUDGET_LIMIT,reason,readOnly:true}`，设置受限 plan。maxToolCalls=0 也走此路；不是 token 耗尽后仍必定生成。此节点无新模型请求或单独持久 task 状态。 |
| loop-limited-final | 受限 plan → final | 同一 `execute`：预算分支结束后 `prompts.finalAnswer`、`outputCap`、`FINAL_GENERATION_STARTED`、`callLlm(FINAL_GENERATION)` | 若剩余 cap 小于 finalTokenReserve 则 abort；否则才发独立最终请求。成功 outcome 的 termination reason 保留具体次数上限。 |
| loop-final-outcome | final 调用 / 引用校验成功 → 返回结果 | Executor L425 `validateCitations`、L437 `citations`，`execute` 返回 `TaskExecutionOutcome.completed`；[TaskRunner](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) L59 `run` | 白名单引用来自 rag.hits，答案、计数、usage 与实际使用引用组成内存 outcome。Runner 才负责后续 deadline arbitration 与 settlement；不把 outcome 当作已持久 COMPLETED。节点已标“返回执行结果”，故边省略重复标签。 |

### 图 05 状态、Trace 与失败边界

- **真实入口：** [AgentEngineTaskExecutionDelegate.execute](../../backend/src/main/java/com/agentflow/agent/task/execution/AgentEngineTaskExecutionDelegate.java) 在 engine mode 注入；[DefaultAgentEngine.execute(TaskExecutionRequest)](../../backend/src/main/java/com/agentflow/agent/engine/DefaultAgentEngine.java) L72 委托 taskExecutor。旧 command 重载会取 live Agent/tools，不是本图路径。Runner 成功 claim 后解析持久 snapshot，进入 delegate 前确认无 DB transaction。
- **检索选择与无数据：** SnapshotRagService 由 snapshot.retrieval 冻结语料驱动；不接受 decision 中的 retrieval action。空 corpus 直接空结果，不发 embedding/vector；候选经 canonical SQL 与 generation/owner 等校验，无有效命中也继续。没有工具可用时 CALL_TOOL 不在 allowedTools 中而解析失败，FINISH 可基于已有证据/澄清计划结束；maxToolCalls=0 则按计数分支直接受限 final。没有为本图展开 ingestion/RAG pipeline，图 06 未施工。
- **上下文：** TaskPromptBuilder.common 把 userTask、knowledgeEvidence、citationIds、observations 放入 payload。decision 另加 availableTools/schema 与剩余次数；final 另加 answerPlan，不再加工具规划协议。支持的 v2 prompt 明确缺失输入可 FINISH 后请求新任务补充；不是 WAIT，也不会自动 resume。untrusted 工具结果/知识不能修改 system rules。
- **跨线程 / I/O：** [TaskExternalCallDeadline.call](../../backend/src/main/java/com/agentflow/agent/engine/TaskExternalCallDeadline.java) 将实际工作放在 virtual-thread FutureTask，worker 有界等待并在返回前后检查 boundary。RAG 的远程 embedding/vector 取决于 adapter；Chat 跨 provider I/O；当前 task tool 仅本地只读 builtin，不能画成网络工具服务。嵌套 boundary 与实际-work permit 防止晚结果推进下一步；取消 Future 或本地等待结束不证明远端计算已停。
- **持久短事务：** [ExecutionRecorderTransactionService](../../backend/src/main/java/com/agentflow/agent/trace/ExecutionRecorderTransactionService.java) 的 startStep/completeStep/failStep、recordLlmCall、recordRagRetrieval、appendEvent 均 REQUIRES_NEW；[AgentTaskLifecycleTransactionService.changePhase](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java) L66 单独短事务更新 phase。RETRIEVING、DECIDING、EXECUTING_TOOL、GENERATING 是 phase，不是 task status。
- **Trace 实体：** [V19](../../backend/src/main/resources/db/migration/V19__create_agent_execution_trace.sql) 定义 agent_step、llm_call_log、rag_retrieval_log/hit、tool_call_log 的 task/step 关系与 call_type/step_type 约束。[TaskEventAppender](../../backend/src/main/java/com/agentflow/agent/task/service/TaskEventAppender.java) 分配事件序号并同事务写事件；不存在图中虚构的消息代理。RAG_FINISHED、DECISION_FINISHED、TOOL_STARTED/FINISHED、FINAL_GENERATION_STARTED 仅阶段事实；Engine 不直接写 TASK_COMPLETED。
- **失败映射：** Executor L541 `failure` 保留计数/usage，TASK_CANCELLED → cancelled、TASK_TIMED_OUT → timedOut、其他 → failed；token 不足使用 TOKEN_BUDGET_EXHAUSTED reason。malformed decision、未知工具、重复循环、工具失败、provider 失败/输出上限/单次超时、无效引用均可中止。该 Engine 分支不自动修复 JSON 或重试模型请求；不对 provider SDK 内部策略作额外保证。
- **使用量：** `callLlm` 在取消/超时仲裁前记录可观察返回 usage；缺失 usage 保守估算，混合值标 MIXED。malformed DECISION 不记录原始响应正文。`V20__preserve_task_token_overruns.sql` 的历史持久化边界不意味着模型可无限超支；本轮图只陈述已读计费/失败行为，不重验数据库。
- **跨图一致性：** 承接图 04 的 claim 后 executionRequest；与图 03 一致，RAG 一次、工具回环、独立 final、outcome 后才 settlement。快照不回读 mutable draft，但工具紧急撤销与 canonical chunk 的实时验证仍存在。SSE/刷新/恢复不进入这个 loop 的执行触发路径。

### 图 05 实际布局、修复与验收

- 首轮 CLI exit 1，仅 `schema/required`：根对象缺少必填 `diagram_type`。原候选、回执与 acceptance 保留在 [历史诊断](history/05-required-type/05-agent-loop.candidate.validation.json)。只补 `diagram_type: workflow` 后完整验证通过；未进行几何修复，所有 9 条关系保持自动路由，无 via/channel/端口/label 偏移。
- `viewBox = requiredViewBox = [1018,534]`；列中心为 `[112,268,424,598.8,773.6,929.6]`。全部节点 128×68，三泳道节点 y 为 86/212/338，间隔126。最右节点 outcome 右界993.6，小于1018；没有以缩小 viewBox 裁切。
- Tool feedback 最终 points：`(534.8,120) → (424,120) → (424,212)`；标签 `(479.4,110)`，86.8×14。CALL_TOOL 从 decision 顶部到 tool 底部；feedback 从 tool 左侧回 budget 顶部，两者不共享 corridor。proper crossings=0、ambiguous corridors=0、label-route issues=0。
- 正式 SVG 逐一读取所有 8 个节点的全部 16 段文字：标题11px、副标题8px，未缩小源字号。以930px保守阅读宽度计算最小投影 `8×930/1018 = 7.30845px`；不是仅抽查一个节点。机器 composition 的 `minProjectedNodeTextPx:null` 是无失败项时的空值，未误写成零或凭此声称最小字号。
- candidate / official validate、deliver 均 exit0 / 9/9 / 0 errors / 0 warnings。正式 source 5576 bytes，SHA-256 `547ee6dcc919b2b29d7c1224cfa094b737bf2e6a40f88bea8ecc617fac1f5eb1`；HTML 807523 bytes，SHA-256 `037962f43c24d28968987d267a358e79fd1078097db09efb4c79fa9021ef1ff8`。未手改 HTML 或 Skill，未新增隐藏 overflow/缩放/裁切规则。
- visual-check exit0；1440×900、1600×1000、1920×1080、2048×1320 的 scrollWidth/scrollHeight 分别恰等于对应 viewport，overflowX/Y=false，readability 与 Viewer chrome 通过；每尺寸实际报告最小节点文字8px。1440及2048的light/dark截图均完整。实际四图视觉复核 passed，correction_rounds=0；主线/工具回环/次数侧支可分辨，节点、卡片、图例、dock 无相互遮挡，最大尺寸首屏完整。
- 未运行业务测试或真实 provider 验收；图 05 完成仅指本图证据、artifact、browser、visual review 门禁。图 01–04 原产物保持，图 06 未开始，本轮未提交推送。


## 图 06 施工前工作记录

2026-09-15（Asia/Shanghai），main / HEAD `460c6caf27f01383b0478a81b9acb3ce77b01187`，未 fetch，基于当前 dirty working tree。图 04/05 已交付但尚未提交，全部保留；既有业务/规格/配置与 Skill 不改。Archify metadata 2.17 / package 2.17.0-dev.1，dataflow schema v1，showcase，中文、静态 classic。此记录先于 Typed JSON 写入。

唯一问题：文档从上传到被一次 Agent task 检索并进入模型上下文，数据经历什么路径？只施工图 06，不开始图 07；不展开工具执行、任务状态机或全套重处理恢复。

### CODE_CONFIRMED nodes / data assets

- `rag-file`：KnowledgeDocumentService.upload 校验 JWT owner/ACTIVE KB 后由 LocalDocumentStorage.store 保存 TXT/MD 原始字节、DB 保存 storage key 与 PENDING 元数据。不是对象存储云服务；上传不会自动解析或向量化。
- `rag-parsed`：显式 process-pending → DocumentProcessingService.parseAndChunk → DocumentParserResolver；ParsedDocument 是内存规范化文本与标题区段，不是新持久化表。
- `rag-chunks`：DocumentChunker 生成 ChunkDraft；DocumentProcessingTransactionService 同事务落所有 knowledge_chunk 与 parse COMPLETED。chunk 带正文、title_path、content_hash、vector_generation、structured-token-v1，vectorization=PENDING。
- `rag-embedding`：另一次显式 vectorize-pending，在短事务认领当前代 chunk 后把正文交 EmbeddingGateway，得到派生浮点向量。remote 是 DashScope text-embedding-v4；图中的 remote 模式关系不证明已运行真实 provider。
- `rag-vector`：QdrantVectorStoreGateway.upsert 保存 vectorId、向量和过滤 payload；payload 有 owner/KB/document/chunkIndex/generation/hash，不含正文。Qdrant 是派生索引，正文真值仍在 PostgreSQL。
- `rag-ready`：upsert 成功后编排器另行回写 vectorId + COMPLETED；DocumentReadinessRow.readiness 从当前代统计与固定配置派生 READY。它不是 Qdrant 主动写 PG，也不是单独可写状态。
- `rag-task`：任务创建时 AgentTaskSnapshotResolver.resolveConfiguration 读取所选 KB 当前可用 document generation，冻结进 execution_snapshot；检索输入还有原始 userInput。snapshot 不等于拷贝全文。
- `rag-query`：SnapshotRagService.retrieve 用 userInput 构造 query embedding；固定1024维，复用冻结语料范围，没有模型生成 query rewrite。
- `rag-search`：Qdrant 的 points/query 使用 query向量 + owner/KB/document generation filter，返回候选 vectorId/chunkId/score/contentHash，不返回正文或完整向量。
- `rag-context`：候选 locator 经 KnowledgeChunkMapper.selectSnapshotRetrievableChunks 回读 canonical PG 正文，再核对 frozen generation、vectorId、hash/实际正文；过滤无效候选、阈值/去重/排序/topK，生成 bounded UNTRUSTED evidence 与 S1…引用。
- `rag-model`：TaskPromptBuilder.common 将 evidence/citationIds、userTask/observations 放入 decision 与独立 final 的模型 messages；不是把原始文件或向量直接当 prompt。
- `rag-trace`：ExecutionRecorderTransactionService 的 RAG retrieval/hit快照与 LLM call记录。引用来源、正文快照、score、generation、调用/usage各有事实；最终引用需经 Executor 白名单校验，outcome由Runner另行结算。

### CODE_CONFIRMED flows / boundaries

Ingestion：file → parsed（显式 process读取原始字节）；parsed → chunks（结构化文本分块并短事务落库）；chunks → embedding（另行 vectorize取正文）；embedding → vector（向量 + scoped payload）；vector → ready（编排器收到确认后独立写PG）；chunks → ready（仅当前 generation统计）；ready → task（创建时重新验证并冻结document generation）。这条数据谱系不表示上传自动串起所有动作。

Retrieval：task → query（userInput与冻结范围）；query → search（query向量与范围过滤）；vector → search（索引候选）；search → context（locator/score/hash）；chunks → context（SQL正文与scope/version核验）；context → model（证据、引用白名单）；context → trace（retrieval/hit快照）；model → trace（LLM调用与使用量）。所有箭头都有数据意义；不是未经证明的队列/网络连接。

慢 embedding/vector I/O 不持有处理/向量化数据库长事务，认领/回写是独立短事务。文件与DB、Qdrant与DB不是分布式原子事务；upsert结果不明或完成状态回写失败保留PROCESSING屏障。上传失败尝试清理文件，但不声称崩溃下绝无孤儿。

### CODE_CONFIRMED READY / generation / failure facts

- owner/KB/document可见；KB ACTIVE，parse COMPLETED；配置为 dashscope/text-embedding-v4、chunkSize800/chunkOverlap120，当前代仅一种且为 structured-token-v1；当前代 chunk 非空、全为向量化COMPLETED，才是READY。PENDING/PROCESSING向量化为INDEXING；completed+failed为DEGRADED；配置/策略不支持、零chunk或全失败为FAILED；禁用KB或未完成parse为NOT_READY（parse FAILED另为FAILED）。必须按实际优先级解释。
- Readiness来自PG事实，不向Qdrant探测点存在/在线状态。创建门禁与当前generation SQL + resolver配置校验配合，未取得任何可用document则RAG_KNOWLEDGE_NOT_READY。
- V11给document/chunk加generation默认0；显式重处理在条件更新中推进document generation，旧任务的冻结generation不会自动改成新版。runtime canonical SQL还要求chunk generation等于document当前generation，因此失效/撤销语料只能被丢弃，不能fall forward。
- ChunkVectorIdentityFactory 的稳定vectorId由owner/KB/document/chunkIndex/contentHash派生，**不包含generation**；generation是payload/SQL检索隔离字段，不杜撰它进入ID哈希。
- 解析/embedding失败分别落FAILED；未知upsert保留PROCESSING；检索 provider失败中止RAG，空有效命中可返回空证据继续Agent。取消/期限限制本地后续工作，不能证明远端停止。

### TEST_CONFIRMED facts

已读取断言，**本轮未执行，不记为新测试PASSED**：KnowledgeReadinessPostgresIntegrationTest.shouldExposeFiveStatesAndCurrentGenerationCountsThroughListAndDetail（11组状态、旧代不污染、新代零chunk不被旧代救活、snapshot只取READY）；SnapshotRagServiceTest.freezesCorpusAndKeepsOnlyCanonicalCurrentScopedHashMatchedContent / revokedOrCleanedGenerationIsEmptyEvidenceAndDoesNotFallback（错误owner/generation/hash被丢弃，不回落）；ChunkVectorizationServiceTest.shouldVectorizePendingChunkWithStableIdentityAndQdrantReadyPayload / shouldKeepProcessingBarrierWhenVectorUpsertOutcomeIsUnknown（payload无正文、成功后回写、结果不明屏障）。这些断言不证明本轮真实DashScope/Qdrant联通。

### DOC_DECLARED only / UNKNOWN

指南§9的Ingestion/Retrieval是数据谱系，当前实现将上传、process-pending、vectorize-pending分成三个入口；不能照箭头误写为后台自动pipeline。历史slice中的队列/后续能力及历史真实验收数字不作为本轮证据。未启动应用、DB、provider或业务E2E；目标配置、索引实际完整性、真实检索质量、延迟、外部取消效果UNKNOWN。独立KnowledgeContextService/KnowledgeChatService路径不是本图的Agent task runtime；不把其上下文预算与SnapshotRagService的字节限制混用。

## 图 06 Evidence index

- **Diagram:** 06 · RAG Data Flow（**complete**，2026-09-16）。
- **Question:** 文档从上传到被一次 Agent task 检索并进入模型上下文，数据经历什么路径？
- **Type:** `dataflow` / schema v1 / showcase；12 个数据/职责节点、15 条具名 flow。横向 stages 是数据职责列，上部 ingestion、下部 retrieval，中间 READY 冻结交接；不是时间轴、自动队列或事务边界。
- **Primary path:** 原始文件 → ParsedDocument → current-generation chunks → embedding → vector index → PG回写/派生READY → task冻结语料 → userInput/query embedding → scope search → canonical正文核验/上下文 → decision/final；检索/调用事实进入Trace。
- **Key nodes / evidence:** 上述施工前清单与逐边索引，生产代码与迁移重新读取；不能以历史切片声明替代当前实现。
- **Unknowns:** 未运行本轮业务单测/集成测试、后端、PostgreSQL、真实DashScope/Qdrant；provider连通性、点完整性、检索质量、超时后的远端计算均未证明。本图 artifact、浏览器与截图复核的通过不升级上述业务运行证据。
- **JSON:** [冻结 candidate](06-rag-dataflow.candidate.dataflow.json)；[正式 JSON](06-rag-dataflow.dataflow.json) 为候选原字节复制。
- **HTML:** [正式交付图 06](06-rag-dataflow.html)，原版 deliver exit 0；[delivery receipt](06-rag-dataflow.delivery.json)。
- **Artifact validation:** **passed**，原版 CLI exit 0，9/9 showcase，composition 0 errors / 0 warnings；[candidate 原始回执](06-rag-dataflow.candidate.validation.json)、[正式 JSON 原始回执](06-rag-dataflow.validation.json)。
- **Browser evidence:** **passed**，原版 visual-check exit 0 / status pass，四桌面视口 containment/readability 通过，端点视口双主题截图齐全；[实际回执](06-rag-dataflow.visual-check.json)。
- **Visual review:** **passed**，已用图像阅读器打开四张实际截图检查 READ / Still；[独立视觉记录](06-rag-dataflow.visual-review.json)、[截图 contact sheet](06-rag-dataflow.visual-check.html)。原版自动回执的 `visualReview: pending` 保持不变。
- **Acceptance:** [本轮命令/实际退出码与分层状态](06-rag-dataflow.acceptance.json)；[原失败 causal diagnostic](06-rag-dataflow.diagnostic-summary.json) 与 [修复前完整状态](history/06-before-package-repair-20260916/06-rag-dataflow.acceptance.json) 为历史，未改写成成功。

### 图 06 逐 flow 证据

各关系均为 **CODE_CONFIRMED 静态源码**；下表中的同步或短事务不是本轮实际请求日志。图里的RAW文件、parsed文本、chunk generation、embedding、vector reference、retrieval result、model context及citation/Trace分别有节点/明确字段，未混为一个“RAG数据库”。

| Flow ID | 数据 / 条件 | 当前实现定位 | 边界与限定 |
| --- | --- | --- | --- |
| rag-file-parsed | 原始TXT/MD字节 → ParsedDocument | [KnowledgeDocumentService.upload](../../backend/src/main/java/com/agentflow/knowledge/service/KnowledgeDocumentService.java)；[LocalDocumentStorage.store/open](../../backend/src/main/java/com/agentflow/knowledge/storage/LocalDocumentStorage.java)；[DocumentProcessingService.processPending/parseAndChunk](../../backend/src/main/java/com/agentflow/knowledge/service/DocumentProcessingService.java) | 上传先存文件/key与PENDING，解析由独立HTTP process-pending触发。不是上传回调自动运行；本地Files I/O，不是云对象存储。 |
| rag-parsed-chunks | normalized文本/title sections → ChunkDraft / knowledge_chunk | [DocumentParserResolver.parse](../../backend/src/main/java/com/agentflow/knowledge/parser/DocumentParserResolver.java)；[DocumentChunker.chunk](../../backend/src/main/java/com/agentflow/knowledge/chunk/DocumentChunker.java)；[DocumentProcessingTransactionService.persistChunksAndMarkCompleted](../../backend/src/main/java/com/agentflow/knowledge/service/DocumentProcessingTransactionService.java) | ParsedDocument只在内存；按estimated tokens/段落边界及overlap分块，全部chunks与parse COMPLETED一个REQUIRES_NEW事务提交。chunk向量状态此时仍PENDING。 |
| rag-chunks-embedding | 当前代chunk正文 → embedding vector | [ChunkVectorizationService.vectorizePending](../../backend/src/main/java/com/agentflow/knowledge/service/ChunkVectorizationService.java)；[ChunkVectorizationTransactionService.claimPendingChunk](../../backend/src/main/java/com/agentflow/knowledge/service/ChunkVectorizationTransactionService.java) | 另行HTTP vectorize-pending，先锁父document并核对当前generation/parse完成/可见，再认领PENDING；外部EmbeddingGateway调用不持有该DB事务。 |
| rag-embedding-vector | vector + scoped payload → Qdrant point | `ChunkVectorizationService.payloadFor`；[ChunkVectorIdentityFactory.create](../../backend/src/main/java/com/agentflow/knowledge/vector/ChunkVectorIdentityFactory.java)；[QdrantVectorStoreGateway.upsert](../../backend/src/main/java/com/agentflow/knowledge/vector/QdrantVectorStoreGateway.java) | remote PUT `/collections/{collection}/points?wait=true`；稳定vectorId来自owner/KB/document/index/contentHash，generation另存payload，不在ID派生材料中。payload无正文。 |
| rag-vector-ready | upsert确认 → PG vectorId / COMPLETED回写 | `ChunkVectorizationService.vectorizePending` 的upsert返回后 → `ChunkVectorizationTransactionService.markCompleted` | 编排器写独立短事务；箭头不是Qdrant直连写PG。外部upsert与PG回写不原子；结果未知或回写失败经markOutcomeUnknown保留PROCESSING屏障。 |
| rag-chunks-ready | 当前代chunk向量状态统计 → readiness | [KnowledgeDocumentReadMapper.selectReadinessByDocumentIds](../../backend/src/main/java/com/agentflow/knowledge/repository/KnowledgeDocumentReadMapper.java)；[DocumentReadinessRow.readiness](../../backend/src/main/java/com/agentflow/knowledge/readiness/DocumentReadinessRow.java)；[KnowledgeReadConfiguration](../../backend/src/main/java/com/agentflow/knowledge/readiness/KnowledgeReadConfiguration.java) | owner-scoped LEFT JOIN，仅kc.vector_generation=kd.vector_generation；配置/策略和完整性共同判定。READY是PG派生读模型，没有新增可写READY列，也不测Qdrant在线。 |
| rag-ready-task | 可用document generation → execution_snapshot | [AgentTaskSnapshotResolver.resolveConfiguration/resolveRetrievalRows](../../backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskSnapshotResolver.java)；[AgentKnowledgeBindingMapper.selectSelectedReadyDocumentGenerations](../../backend/src/main/java/com/agentflow/agent/binding/repository/AgentKnowledgeBindingMapper.java) | task创建时选定KB，SQL筛当前代、非空、全completed、单一策略，resolver再检查fixed profile/strategy。不是信任前端READY字段；全无可用document会拒绝新task。冻结ID+generation，未拷贝全文。 |
| rag-task-query | userInput + frozen corpus → query embedding | [SnapshotRagService.retrieve](../../backend/src/main/java/com/agentflow/agent/rag/SnapshotRagService.java) | once PRE_RETRIEVAL，query即原始userInput；固定dashscope/text-embedding-v4与1024维。空corpus不调用provider；无query rewrite模型。 |
| rag-query-search | query vector + owner/KB/document generation filter → search | `SnapshotRagService.retrieve` → `new VectorSearchRequest`；`QdrantVectorStoreGateway.search/scopeFilter` | remote POST `/collections/{collection}/points/query`；每KB有界候选预算，全局候选上限200；不是跨owner的全库搜索。 |
| rag-vector-search | vector index → candidate locators | `QdrantVectorStoreGateway.search/parseSearchHits` | 请求with_payload为chunkId/contentHash、with_vector=false；结果含vectorId、score。此flow表示索引数据被query读取，不虚构另一个Qdrant集群。 |
| rag-search-context | candidate ID / score / hash → verified hit | `SnapshotRagService.retrieve/isVerified` | 每个候选必须通过PG回读与身份/hash校验；无效候选计stale，阈值过滤、按chunk去重、全局排序后取topK。向量命中不等于可引用正文。 |
| rag-chunks-context | canonical PG正文 + 当前scope/generation → 有效证据 | [KnowledgeChunkMapper.selectSnapshotRetrievableChunks](../../backend/src/main/java/com/agentflow/knowledge/repository/KnowledgeChunkMapper.java)；`SnapshotRagService.isVerified/boundedResult` | SQL同时要求当前document generation和冻结generation；匹配owner、KB ACTIVE/可见、document完成/可见、chunk向量完成/策略、非空vectorId/hash；Java核对vectorId、payload hash、DB hash与实际正文hash。只取canonical正文，不fall forward。 |
| rag-context-model | UNTRUSTED evidence + citationIds → LlmMessage payload | [TaskPromptBuilder.common/decision/finalAnswer](../../backend/src/main/java/com/agentflow/agent/engine/TaskPromptBuilder.java)；[TaskSnapshotAgentExecutor.execute/callLlm](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) | boundedResult分配S1…并限每hit正文2000 UTF-8 bytes、evidence总计12000 bytes。decision/final分别取同一rag结果；原文件/向量不直接进入prompt。工具回环留给图05/07，不在本图展开。 |
| rag-context-trace | retrieval/hit数据 → Trace快照 | `TaskSnapshotAgentExecutor.retrieve/ragRecord`；[ExecutionRecorderTransactionService.recordRagRetrieval](../../backend/src/main/java/com/agentflow/agent/trace/ExecutionRecorderTransactionService.java) | 独立短事务保存query/corpus/topK/threshold/候选有效stale数及hit的citationId、contentSnapshot、document/chunk/generation/score/metadata；成功后RAG_FINISHED。失败同样可有失败日志。 |
| rag-model-trace | 模型调用 / usage → llm_call_log | `TaskSnapshotAgentExecutor.callLlm`；`ExecutionRecorderTransactionService.recordLlmCall` | DECISION与FINAL_GENERATION分别记录；已返回usage与估算质量保留。最终答案citation由Executor.validateCitations对白名单验证后才返回outcome，Runner持久结算另有边界，不能把调用Trace当作成功发布。 |

### READY、版本与存储依据

- **READY完整条件：** document/KB在owner可见域，KB ACTIVE、parse COMPLETED；provider/model为dashscope/text-embedding-v4，chunk参数800/120；当前代chunk非空、只有structured-token-v1一种策略，全部vectorization COMPLETED。配置和策略不兼容优先FAILED；正常配置下pending/processing存在则INDEXING；completed+failed为DEGRADED；零chunk/全failed为FAILED。KB禁用优先NOT_READY，parse FAILED为FAILED，其他未完成parse为NOT_READY。这是DocumentReadinessRow的实际顺序，不用upload success或parse COMPLETED替代。
- **快照准入与运行时不同：** 新建task必须取得至少一个符合门禁的document；已经冻结的task运行时可能因删除/重处理/撤销而无有效命中，此时可返回空证据。不能由runtime可空倒推新task准入不要求READY。
- **版本推进：** [V11](../../backend/src/main/resources/db/migration/V11__create_knowledge_document_reprocess_task.sql) 为document/chunk加入generation默认0；[KnowledgeDocumentMapper](../../backend/src/main/java/com/agentflow/knowledge/repository/KnowledgeDocumentMapper.java) 的重处理条件更新推进generation，后续重新解析/向量化。旧task不会改写snapshot或自动选择新代。本图只保留版本隔离事实，不绘制整个重处理补偿状态机。
- **持久化谱系：** [V3](../../backend/src/main/resources/db/migration/V3__create_knowledge_document.sql) 文件元数据/key与owner/KB FK；[V4](../../backend/src/main/resources/db/migration/V4__create_knowledge_chunk.sql) canonical正文及document/KB/owner复合FK；[V5](../../backend/src/main/resources/db/migration/V5__add_chunk_vectorization_state.sql) content_hash、vector_id、向量化状态约束；[V19](../../backend/src/main/resources/db/migration/V19__create_agent_execution_trace.sql) RAG hit内容/引用/身份快照及LLM调用记录。原始文件、DB真值、派生向量索引和历史Trace不是同一个存储对象。
- **模式边界：** [RemoteVectorizationGatewayConfiguration](../../backend/src/main/java/com/agentflow/knowledge/vector/RemoteVectorizationGatewayConfiguration.java) 仅remote启用DashScope/Qdrant adapter并核对维度。实际环境可能选择开发替代实现；本图标识remote逻辑关系，不证明本轮已触达真实服务，也不推断production部署位置。
- **受控测试边界：** 施工前所列4组测试只读断言未执行。readiness集成测试源码覆盖11组状态及旧代不污染；vector测试明确payload无content、unknown upsert不markFailed；snapshot RAG测试排除错误owner/generation/hash、失效语料不回落。没有把测试命名或历史通过数字升级为新的运行成功。
- **跨图一致性：** 图04创建时冻结ready document generation；图05在loop前一次PRE_RETRIEVAL，evidence给每次decision/独立final；outcome后才Runner结算。检索空结果、取消、超时与远端继续计算的unknown均保留；图07未施工。

### 图 06 历史诊断与停止记录（2026-09-15）

以下保留上一轮失败时的判断与停止原因；其中“当前/本轮”指 2026-09-15，不表示本次修复后的状态。原始失败回执见 [历史 candidate validation](history/06-before-package-repair-20260916/06-rag-dataflow.candidate.validation.json)；2026-09-16 的新验收另列于下一节。

所有修改均来自CLI明确诊断，只改具名flow的路由/端口/标签位置；12个节点和15条数据关系及全部语义标签保留，未缩小字体、降低showcase、修改renderer/checker或业务代码。dataflow schema没有semanticChecks字段，没有删除此类检查。

诊断数按实际回执依次为 `19 → 18 → 16 → 15 → 14 → 12 → 10 → 12 → 9 → 10 → 9`。初始自动路由穿过不相关节点，若干自动端口展开生成7px内部段；局部直线、端口与via修复降低了数量，但最近两次对rag-chunks-context的修改未刷新最佳9项。

| Current code | Subject | 当前几何证据 |
| --- | --- | --- |
| clean-flow/edge-through-node | rag-vector-search（2项） | `(888,161)→(745,161)`穿rag-embedding；`(745,161)→(745,382)`穿rag-context。 |
| composition/ambiguous-corridor | rag-vector-search vs rag-chunks-context | 共享`(745,340)→(745,356)`，长度16px，超过8px检测下限。 |
| composition/micro-segment | rag-search-context | 内部段`(637.5,396)→(637.5,389)`仅7px。 |
| composition/micro-segment | rag-context-model | 内部段`(852.5,389)→(852.5,382)`仅7px。 |
| composition/label-route-clearance | rag-chunks-embedding | “正文”标签rect `[621,140,34,16]`与rag-chunks-context竖段`(626,161)→(626,100)`距离0。 |
| composition/label-route-clearance | rag-search-context | “候选引用”标签rect `[612,372,51,16]`与rag-vector-search末段距离0。 |
| layout/constraint | rag-chunks-ready标签 | “当前代统计”覆盖rag-chunks。 |
| layout/constraint | rag-context-trace标签 | “检索快照”覆盖rag-context。 |

完整evidence/subject/supportedFixes见原始回执与diagnostic-summary，不以本表取代原件。CLI对上述几何支持调整端口/route/via/channel或移动相关stage/row，对标签支持labelAt/labelDx/labelDy/labelSegment；本轮不再尝试新的修复。

停止依据为[本地Archify SKILL.md](../../.agents/skills/archify/SKILL.md) Fast authoring path第5项原文：**“If two consecutive rounds do not improve that best count, stop and report the unresolved diagnostics truthfully.”** 当前最佳9，最近两轮10→9触发该条件，因此图06保持incomplete。所有历史候选/失败回执/acceptance在`history/06-round-01`至`history/06-round-10`，没有改写成成功证据。正式JSON、deliver、visual-check、视觉复核均未执行；图01–05保留，不开始图07。本轮未提交推送。


### 图 06 修复包核对与验收（2026-09-16）

本轮仅处理图 06。已读取 [REPAIR_NOTES](../../archify06_repair/REPAIR_NOTES.md) 和安全脚本，核对当前安装版 dataflow 固定列中心 `100 / 315 / 530 / 745 / 960`、节点文字拟合及标签/端口公式。该类型使用固定 stage 网格，未套用 workflow-v2 自适应布局。修复包原本只是未经原版验收的候选，包内独立复算结果未作为 CLI 证据。

在仓库根目录先运行 `python3 archify06_repair/apply_repair.py .`，exit 0，baseline 匹配；对应基线 Git blob 为 `433d983f30114c84dd1fb9e0e40f7f3cc699b4ff`。随后 `--apply` exit 0。应用前原字节保留 candidate、validation、acceptance、diagnostic-summary、handoff validation 于 `history/06-before-package-repair-20260916/`，脚本另保留 `.before-layout-repair.bak`；未再次应用 patch。

本轮 source 变更严格限于包内六条 flow 的几何与 viewBox 宽度：

| Subject | 已应用修改 |
| --- | --- |
| rag-vector-search | bottom → top，经 `(960,320) → (530,320)`，标签 `(850,310)` |
| rag-chunks-context | top → top，经 `(530,96) → (1060,96) → (1060,340) → (745,340)`，标签 `(810,86)` |
| rag-search-context | `route: straight` |
| rag-context-model | `route: straight` |
| rag-chunks-ready | `labelDy: 34` |
| rag-context-trace | `labelDy: 34` |
| meta.viewBox | `[1140,620] → [1080,620]` |

12 个节点、15 条关系、全部节点/阶段文案、说明卡片及其余字段均保持。没有缩小源字号、裁切、手改 HTML、修改 Skill/业务代码或降低质量门槛。

**Artifact validation — passed：** candidate 与正式 JSON 均用当前未修改原版 CLI 完整 showcase validate，exit 0；9/9 checks，composition 0 errors / 0 warnings。proper crossings、ambiguous corridors、label-route clearance issues、短段与微段计数均为 0；最小线段 16px，最小内部段 51px，最小标签/其他路径净距 5px。4 弯路径及 stretch 为回执中的信息指标，不是警告。candidate 通过后原样复制正式 JSON，再 deliver exit 0；未在冻结后增加美化修改。

对本次已交付 SVG 的[补充布局提取](06-rag-dataflow.layout-measurements.json)覆盖全部 12 个节点、24 段节点文字和 15 条 flow；它不是原版 CLI 回执。所有标题 10px、副标题 7px，无拟合降字号。viewBox 为 `0 0 1080 620`，最右节点到 x=1032，stage frame 到 x=1044，绕行到 x=1060，仍有 20px 画布余量。按静态 930px 绘图区预算，全图最小预计节点字号 `7×930/1080=6.02778px`；原 CLI 的 `minProjectedNodeTextPx: null` 不作为数值证明，以此补充提取及实际浏览器数值分别记录。

**Browser evidence — passed：** 本次 deliver 成功后才运行原版 visual-check，exit 0 / status pass。绑定 HTML `56fe06f6d1d594530b0b22493200af9714f4955d969e4c0e1ea5dc27417ae542`（818893 bytes），对应 specification `c9d1f6b6ea379523e0d36a6f722892d5a299ba7f9c1b8ddb13859bb6692de978`（7479 bytes）。

| 桌面视口（light / READ / Still） | scrollWidth × scrollHeight | diagramWidth | 全图最小预计节点字号 | 结果 |
| --- | --- | --- | --- | --- |
| 1440×900 | 1440×900 | 1063px | 6.88981px | containment / readability / viewer chrome pass |
| 1600×1000 | 1600×1000 | 1104px | 7px | containment / readability / viewer chrome pass |
| 1920×1080 | 1920×1080 | 1244px | 7px | containment / readability / viewer chrome pass |
| 2048×1320 | 2048×1320 | 1618px | 7px | containment / readability / viewer chrome pass |

原版回执保守地将放大后的预计字号上限记为源字号；以上数值直接抄录实际回执。1440×900 与 2048×1320 均另有 light/dark 截图，四张捕获全部 pass；两端 dark 也无溢出。图例与 dock 相交面积 0，dock 与 stage 间距 10.21875px，满足 10px 要求。

**Visual review — passed，correction_rounds: 0：** 已打开上述四张实际截图，顶部“正文回查”与“向量 + payload”分开，中间“索引候选”与正文回入路径分开；“当前代统计”“检索快照”均处于节点间隙，遮罩未盖住节点文案。上下两条主阶段可辨，节点/关系/卡片在两种主题下可读，大屏主图与必要说明卡片占用均衡，右外缘路径和底部卡片均完整。未见需要追加 source 修复的视觉缺陷。

视觉记录仅覆盖默认 READ / Still 截图；未把搜索、focus/passport 交互或导出文件检查写成已测。自动回执保持 `visualReview: pending`，独立视觉判断记录在 visual-review.json。三层验收现均通过；历史 9 项失败与所有旧探针仍是历史失败，不替换其结论。本轮没有新增业务运行或真实 DashScope/Qdrant 证据，也未开始图 07 或最终 Architecture Audit。

交接前再次执行正式 JSON 的原版 showcase validate，exit 0，仍为 9/9、0 errors / 0 warnings；[本轮交接回执](06-rag-dataflow.handoff-20260916.validation.json) 使用新文件名，保留旧 `06-rag-dataflow.handoff.validation.json` 失败原件。


## 图 07 施工前工作记录（2026-09-16）

本节在 Typed JSON 生成前形成。问题：模型决定调用一个工具后，工具定义、参数校验、执行、结果和后续模型调用如何串起来？类型 `sequence`，仅图 07，不展开图 08 生命周期。基线 `main@0320892af12fdafb1b5ae89e9135b987dbd1630a`，dirty working tree；图 06 的未提交交付与其他既有改动保留。使用当前 Archify 2.17 / package 2.17.0-dev.1，showcase。

- **Confirmed nodes（CODE_CONFIRMED）：** TaskSnapshotAgentExecutor / TaskPromptBuilder；LlmGateway 的 Model Provider；AgentDecisionParser；ToolDefinitionService + AgentTaskSnapshotResolver 的定义/快照职责；DefaultToolRuntime；BuiltinToolExecutor + 两个只读 handler；PostgreSQL 与 ToolCallLogService / ExecutionRecorder 的持久化职责。相邻类按职责合并为参与者，不伪造新进程。
- **Confirmed edges：** owner-scoped 创建流程从配置版本 enabled bindings 选 tool_definition，冻结 tools[]；runtime 将冻结 schema 加入 decision payload；provider 返回 JSON 后 parser 校验协议与 allowed toolCode；runtime 建 TOOL_CALL step 后经 taskScoped command 调 ToolRuntime；重查当前定义以紧急撤销/漂移检查，按冻结 schema 校验参数；RUNNING 独立事务后才允许 handler；worker 上显式 allowlist 分派到 order_query / payment_log_query，直接调用 Demo 服务与同步 SQL；summary + JSON data 经 SUCCESS 独立事务后返回 runtime；受限 UNTRUSTED_TOOL_RESULT 进入后续 decision 与独立 final generation。
- **Confirmed states：** tool_call_log 为 RUNNING / SUCCESS / REJECTED / FAILED；日志终态不能等同 task 终态。CALL_TOOL / FINISH 是模型协议，FINISH 为 answerPlan；最终文字仍须独立 FINAL_GENERATION。最终 task 持久结算由 Runner 完成，本图不展开。
- **Confirmed boundaries：** 模型网络调用与后端 JVM 分开；parser 和冻结 inputSchema 构成不可信输入校验。task 工具在 TaskExternalCallDeadline 的有界 virtual worker 执行，调用者等待；日志 REQUIRES_NEW 与 handler 只读事务分开。demo 订单/支付数据为共享演示数据，不是实际支付外部系统或按用户隔离的订单。
- **Confirmed failure paths：** 定义禁用/删除、schema/hash/implementation 漂移、非 allowlist、参数不合法拒绝 handler；入场 REJECTED，执行异常 FAILED，错误封装 errorCode/errorMessage 后抛回 runtime 终止，不作为成功 observation 继续。特殊缺失 snapshot/membership 分支未必有日志。deadline 取 task/frozen/current 最小限制，无自动重试；取消 Future 不证明工作已退出。成功日志写入失败不会在 handler catch 内被改报 handler 失败。
- **Duplicate boundary：** 相同 toolCode + canonical arguments 第一次执行，第二次重新 validateTaskSnapshot 后复用本次运行内缓存，不再执行或写 tool_call_log；第三次 AGENT_DUPLICATE_TOOL_LOOP。不是持久化恢复缓存，也不是自动重试。
- **TEST_CONFIRMED（断言已读，本轮未执行）：** TaskScopedToolRuntimeTest 校验冻结元数据、worker、RUNNING→handler→SUCCESS 顺序与缓存复核无调用/日志；禁用/漂移在 handler 前拒绝。ToolCallLogServiceTest 断言参数/结果脱敏及 RUNNING 条件终态更新；TaskSnapshotAgentExecutorTest 断言 observation 累积、独立 final 与重复意图边界。
- **DOC_DECLARED：** V27 切片说明的独立调用无 task/step、当时未启用超时是历史范围；当前 taskScoped 实现已拥有 snapshot 与 worker deadline，不能套用旧说明。历史测试数字不升级为本轮成功。
- **UNKNOWN：** 未运行后端、PostgreSQL、真实 Model Provider 或实际工具请求；不证明当前 provider 连通性、线上可靠性、真实订单/支付集成，或取消后底层 JDBC/provider 已终止。


## 图 07 Evidence index

- **Diagram:** 07 · Tool Invocation Sequence（**complete**，2026-09-16）。
- **Question:** 模型决定调用一个工具后，工具定义、参数校验、执行、结果和后续模型调用如何串起来？
- **Type:** `sequence` / schema v1 / showcase，7 个参与者、22 条具名消息。横向职责，纵向调用顺序；分段框不代表事务或新线程。
- **Primary path:** 创建前置冻结 → DECISION → 严格解析 CALL_TOOL → taskScoped ToolRuntime → 实时撤销/漂移复核与冻结参数校验 → RUNNING → 有界 worker / 只读 handler → 结构化结果 → SUCCESS → observation → 后续 decision 的 FINISH → 严格解析 → 独立 FINAL_GENERATION → 引用校验 / outcome。
- **Key evidence:** 逐消息索引如下；当前生产代码与 migration 优先，已读取测试断言不计本轮运行成功。
- **JSON:** [候选](07-tool-call.candidate.sequence.json)；[正式源](07-tool-call.sequence.json)，以最新通过验收的原字节候选交付。
- **HTML:** [图 07](07-tool-call.html)。
- **Validation / browser / visual:** 各层当前状态及实际命令退出码见 [acceptance](07-tool-call.acceptance.json)，不能由 artifact 通过推定浏览器通过。
- **Unknowns:** 本轮未执行业务测试、实际 task、PostgreSQL 或真实模型请求。内置工具使用本地 demo 表，不证明外部订单/支付集成；超时/取消后底层工作退出仍需运行证据。

### 参与者映射与范围

| ID | 当前实现 | 范围与边界 |
| --- | --- | --- |
| tc-runtime | [TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java)、[TaskPromptBuilder](../../backend/src/main/java/com/agentflow/agent/engine/TaskPromptBuilder.java) | JVM 中 task 编排；已存在的 PRE_RETRIEVAL 结果作为前置输入，不再展开图 06。 |
| tc-model | [LlmGateway](../../backend/src/main/java/com/agentflow/infra/llm/LlmGateway.java)、[SpringAiOpenAiCompatibleLlmGateway](../../backend/src/main/java/com/agentflow/infra/llm/SpringAiOpenAiCompatibleLlmGateway.java) | 通过 gateway 的模型网络依赖，决策与 final 共用该职责而非两个 provider；模型不访问业务 DB。 |
| tc-parser | [AgentDecisionParser](../../backend/src/main/java/com/agentflow/agent/engine/AgentDecisionParser.java) | 同 JVM 严格 JSON 协议及 toolCode allowlist 解析，参数完整 schema 校验另由 ToolRuntime 执行。 |
| tc-registry | [AgentTaskSnapshotResolver](../../backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskSnapshotResolver.java)、[ToolDefinitionService](../../backend/src/main/java/com/agentflow/tool/ToolDefinitionService.java) | 合并定义/快照职责；冻结发生在创建前置，运行时只重查定义用于撤销/漂移，不重新解析 mutable Agent bindings。 |
| tc-tools | [DefaultToolRuntime](../../backend/src/main/java/com/agentflow/tool/DefaultToolRuntime.java)、[ToolArgumentValidator](../../backend/src/main/java/com/agentflow/tool/ToolArgumentValidator.java) | taskScoped 校验、日志、边界和返回封装；不画 standalone admin test 路径。 |
| tc-handler | [BuiltinToolExecutor](../../backend/src/main/java/com/agentflow/tool/BuiltinToolExecutor.java)、[OrderQueryToolHandler](../../backend/src/main/java/com/agentflow/tool/OrderQueryToolHandler.java)、[PaymentLogQueryToolHandler](../../backend/src/main/java/com/agentflow/tool/PaymentLogQueryToolHandler.java) | 显式代码 allowlist 选一个 handler，图中并非每次调用两个。task 当前只准这两个；通用 executor 中 report_generate 不属于本 task 主路径。 |
| tc-store | [ToolCallLogService](../../backend/src/main/java/com/agentflow/tool/ToolCallLogService.java)、[ExecutionRecorderTransactionService](../../backend/src/main/java/com/agentflow/agent/trace/ExecutionRecorderTransactionService.java)、Demo service/Mapper | PostgreSQL 持久化职责聚合，包括 schema 定义、demo 表、tool log、step/event/LLM Trace；箭头代表对应服务/Mapper 同步 SQL，不是模型直连。 |

### 逐消息证据（全部 CODE_CONFIRMED，静态调用追踪）

下表使用上面的可点击实现路径；每行明确实际方法和持久化影响。返回消息没有额外事务，除非注明。仅示意一次成功工具调用后下一次有效 decision 选择 FINISH；也可能继续其他 CALL_TOOL、触发预算后受限 final，或在失败时直接返回失败 outcome。

| Message ID | 关系 / 实现方法 | 机制与副作用 |
| --- | --- | --- |
| tc-freeze | AgentTaskCreationTransactionService.create → AgentTaskSnapshotResolver.resolveConfiguration / resolveTool → runtime 的 request.executionSnapshot | 创建前置摘要，非 runtime 临时取配置。owner-scoped config version 的 orderedEnabledToolIds → AgentToolBindingMapper.selectSelectedSnapshotTools；冻结 id/code/name/description/inputSchema/hash/builtin-v1/timeout，写 agent_task.execution_snapshot，后续 Runner 传入。 |
| tc-decision | TaskSnapshotAgentExecutor.execute / callLlm → TaskPromptBuilder.decision → gateway.chat | 同步等待有界外部 worker 的网络结果；冻结工具 code/name/description/schema 加入 availableTools，模型不取得 handler/config/DB 访问能力。DECISION step 与调用日志独立记录。 |
| tc-call-json | gateway.chat → callLlm | 返回模型 content；此时不可信，尚未调用 handler。 |
| tc-parse | callLlm 的 validation callback → AgentDecisionParser.parse | 严格重复 key、尾随 token、精确字段集合、CALL_TOOL/FINISH 枚举；toolCode 必须唯一存在于 allowedTools，arguments 为 object、reason 有长度界限。 |
| tc-parsed | parseToolCall → runtime | 从 allowedTools 取得 toolId，返回 ToolCallDecision；不信任模型指定任意 ID。decision 校验后写安全 LLM 响应/usage、step 和 DECISION_FINISHED。 |
| tc-invoke | TaskSnapshotAgentExecutor.invokeTool → ToolRuntime.execute(ToolExecutionCommand.taskScoped) | 先 TOOL_CALL step、TOOL_STARTED 与 boundary，传 task/step/user/agent/snapshot/args/deadline；无包围整个外部工作的 DB 事务。 |
| tc-current | DefaultToolRuntime.executeTask → ToolDefinitionService.findActiveById / validateTaskTool | 同步查当前 ACTIVE/未删除定义；检查冻结身份、handler、readonly、无需确认、正 timeout、builtin-v1、当前与冻结 schema hash。 |
| tc-definition | 当前定义 → validateTaskTool / ToolArgumentValidator.validate | 当前定义用于拒绝撤销和漂移；执行 metadata/schema 仍冻结，timeout 可被当前配置收紧。参数只支持 validator 实际子集，不称完整 JSON Schema 标准实现。 |
| tc-running | DefaultToolRuntime.executeTask → ToolCallLogService.recordRunning | REQUIRES_NEW INSERT RUNNING，taskId/stepId、参数快照与 retry_count=0；先落日志再执行 handler。 |
| tc-worker | executeWithinTaskDeadline → TaskExternalCallDeadline.call → BuiltinToolExecutor.execute | 全进程 permit 有界 virtual worker，线程名 agent-tool-{taskId}；显式 code+handler 分派，调用方等待，不是持久消息队列。 |
| tc-query | OrderQueryToolHandler.execute / PaymentLogQueryToolHandler.execute → DemoOrderService.getByOrderNo / DemoPaymentLogService.query | 同 JVM 同步调用只读事务及 Mapper；共享 mock_order / mock_payment_log，不绕行 HTTP、无外部支付请求。 |
| tc-rows | Demo 服务 → handler | 订单 DTO，或匹配条件的支付日志列表；参数和 limit 有界，订单不存在抛 BusinessException，支付空列表可合法返回。 |
| tc-handler-result | handler.execute → executeWithinTaskDeadline | HandlerResult(summary, JSON data)；订单 valueToTree(order)，支付 valueToTree(PaymentLogQueryToolData(logs))。waiter 再检查边界，不接受超时后结果。 |
| tc-success | executeTask → ToolCallLogService.recordSuccess | REQUIRES_NEW 完成结果快照、status/latency；ToolCallLogMapper.updateRunningToTerminal 要求日志 RUNNING，taskScoped 还锁定并要求 task/step RUNNING，失败不是成功观察。 |
| tc-result | DefaultToolRuntime.executeTask → invokeTool | ToolExecutionResult(success, toolCode, summary, data, errorCode, errorMessage, latencyMs)；runtime 复核 success、code、summary/data 非空，转 bounded UNTRUSTED_TOOL_RESULT。 |
| tc-step-end | invokeTool → recorder.completeStep / appendEvent | step 完成与 TOOL_FINISHED 通过 recorder 的独立事务持久化；不等同 task 完成。tool_call_log 和 step/event 不是一次分布式原子操作。 |
| tc-next-decision | execute loop → prompts.decision / callLlm | 将本次运行内累计 observation（summary 上限1024 UTF-8 bytes，data 超8192 bytes 改为4096-byte excerpt）及同一 RAG 证据送入新 DECISION；不重新执行已有工具结果。 |
| tc-finish-json | gateway.chat → callLlm | 示例后续决策返回 FINISH JSON，表示结束计划；不是 final answer。 |
| tc-parse-finish | callLlm → AgentDecisionParser.parseFinalAnswer | 同样严格字段，仅 type/answerPlan，plan 非空且最多2048字符；失败无 final 成功路径。 |
| tc-plan | parseFinalAnswer → execute | FinalAnswerDecision.answerPlan；随后跳出 loop，经过预算和边界检查，不能把 plan 直接发布。 |
| tc-final | execute → prompts.finalAnswer / callLlm(FINAL_GENERATION) | 独立模型请求，使用 observation/RAG/plan；不发送工具执行请求，不复用 DECISION content 作为答案。FINAL_GENERATION_STARTED、调用日志/step/usage 各自记录。 |
| tc-answer | gateway.chat → validateCitations → TaskExecutionOutcome.completed | 引用须在 rag citation whitelist；完成调用 Trace 后返回 outcome，Runner 后续持久结算另有边界，不把 provider 响应等同已提交 task 终态。 |

### 关键失败、契约与证据边界

- **定义与绑定源：** [创建事务](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) 先选择 owner-scoped config version，[绑定 Mapper](../../backend/src/main/java/com/agentflow/agent/binding/repository/AgentToolBindingMapper.java) 筛 enabled 工具的 ACTIVE / BUILTIN / 未删除定义，snapshot resolver 再校验 readonly/handler/version/schema。V13/V14 定义 input_schema；冻结字段不是模型自报，执行期不回退到 live bindings。
- **拒绝与失败：** executeTask 的参数/边界/定义异常一般经 recordRejected 留 REJECTED，再抛异常；无完整 taskScope 会直接 IllegalArgumentException，snapshot membership 不匹配且 current 不存在时没有可写 REJECTED 的定义，不声称所有拒绝都留日志。已 RUNNING 的 handler/等待/后置 boundary 异常尝试 recordFailed，保存安全 errorCode/errorMessage 后抛回 runtime；失败日志本身写失败可能掩盖原异常，不能保证每次失败都成功持久化。
- **成功审计失败：** recordSuccess 位于 handler catch 外，写失败不会追加矛盾的 FAILED 来声称 handler 失败；runtime 不得到成功 observation。ToolCallLogService 要求 INSERT/UPDATE 恰一行。入参/结果经 TracePayloadSanitizer 脱敏/体积控制，标准 envelope 不是随意文本或原始异常堆栈。
- **时限与取消：** task handler 使用 min(task deadline, frozen timeout, current timeout)。Future.cancel(true) 只结束等待/尝试中断；进入 body 后 permit 由真实工作 finally 释放，晚结果不发布。无自动工具重试；任务取消、线程 interruption、provider 计算状态与日志状态分别记录。
- **重复意图：** canonical arguments + toolCode 哈希只做本次执行内去重。第二次 validateTaskSnapshot 仍检查撤销/hash/参数/boundary，然后返回已有 observation，reused=true，有新 step/event、无第二次 handler 或 tool_call_log；第三次在新 TOOL_CALL step 前 AGENT_DUPLICATE_TOOL_LOOP。不是跨 JVM 恢复或刷新重执行。
- **持久化契约：** [V13](../../backend/src/main/resources/db/migration/V13__create_tool_definition_and_tool_call_log.sql)、[V14](../../backend/src/main/resources/db/migration/V14__add_payment_log_query_tool.sql) 的工具定义与日志；[V19](../../backend/src/main/resources/db/migration/V19__create_agent_execution_trace.sql) 限定 task/step 同为 NULL 或同非NULL并 FK 关联，LLM call_type 为 DECISION / FINAL_GENERATION。当前 [ToolCallLogMapper](../../backend/src/main/java/com/agentflow/tool/repository/ToolCallLogMapper.java) 增加活跃 task/step 条件；不能只按旧 V27 单独调用文档理解。
- **测试边界：** 已读 [TaskScopedToolRuntimeTest](../../backend/src/test/java/com/agentflow/tool/TaskScopedToolRuntimeTest.java)、[ToolCallLogServiceTest](../../backend/src/test/java/com/agentflow/tool/ToolCallLogServiceTest.java)、[TaskSnapshotAgentExecutorTest](../../backend/src/test/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutorTest.java) 的具体断言，包括执行/日志顺序、冻结参数、撤销/漂移拒绝、缓存不实际执行、observation 累积与独立 final。本轮未运行，不提供新的 TEST PASSED 或真实服务结论。
- **跨图一致性：** 图04创建时冻结配置/工具；图05先一次 RAG、工具回环、独立 final；图06证据与 citation 进入共同上下文。本图均保持，只补充工具调用细节；没有模型直接访问数据库、自动重试、取消即工作终止或 FINISH 即持久 task 完成的边。


### 图 07 修复与最终验收

1. 初始候选 7 个参与者、22 条消息、3 张说明卡；原版诊断 17 组消息横向重叠且 y 间隔不足28px。只按诊断增加消息间距、同步分段/画布高度，未删除消息；[历史原件](history/07-message-spacing/07-tool-call.candidate.validation.json)保留。
2. 该候选 9/9 showcase 通过且 deliver 成功，但首次 browser 在四桌面视口均纵向溢出，1440×900 scrollHeight=1337。实际 HTML 中原版 Reader 仅对宽高比≥1.55启用桌面宽度适配；1080×850 不满足。[原浏览器回执和截图](history/07-before-desktop-fit/07-tool-call.visual-check.json)保留，未将 artifact pass 当作浏览器成功。
3. **视觉修复1：** 保留全部参与者 ID、22 条消息和28px行距，使用 spread 将实际横向布局扩展至1320×850。重复副标题的信息合并到现有主体文案：task编排进入说明卡标题、网络边界进入 DECISION 消息、协议/allowlist 与 taskScoped/worker/只读已由消息表达，PostgreSQL并入持久化参与者标题。源标题保持11px，没有降低字号；没有删除事实或修改 Viewer。
4. 再次 validate/deliver 通过；三个较大视口 browser 通过，1440×900 只超出11px。截图中第一张卡片较长，因此**视觉修复2**仅将首句精简为“版本启用绑定 → tool_definition；冻结 schema/hash/版本，执行前复核撤销与漂移。”；含义保留，消息/节点/几何全部不变。[11px失败回执](history/07-before-card-fit/07-tool-call.visual-check.json)及对应产物保留。
5. 最后 candidate validate、deliver、visual-check、交接前正式 validate 全部 exit0。candidate 与正式 JSON 原字节相同；未手改已生成 HTML，未修改 Skill/业务代码、门槛或使用 overflow:hidden/crop。图01–06既有产物保持。

**Artifact validation: passed。** [候选](07-tool-call.candidate.validation.json)、[正式交接 validate](07-tool-call.validation.json)和[deliver 原版回执](07-tool-call.delivery.json)证明9/9 showcase、composition 0 errors / 0 warnings。proper crossings、ambiguous corridors、label-route-clearance、short/micro segments 均0；最小标签/其他线净距8px、最短消息线164.5px、无弯折。全部7个参与者的实际SVG标题均11px，静态930px预算预计最小7.75px；[补充布局测量](07-tool-call.layout-measurements.json)不是原版回执替代物。

**Browser evidence: passed。** 原版 [visual-check](07-tool-call.visual-check.json) status=pass / exit0；四个视口 light / READ / Still 的实际数值如下，1440×900和2048×1320另有light/dark四张完整截图。未把暗色截图扩称为四种尺寸各做双主题。

| Viewport | scrollWidth × scrollHeight | diagramWidth | 最小预计节点字号 | 结果 |
| --- | --- | --- | --- | --- |
| 1440×900 | 1440×900 | 930px | 7.75px | containment/readability/viewer chrome pass |
| 1600×1000 | 1600×1000 | 958px | 7.98333px | containment/readability/viewer chrome pass |
| 1920×1080 | 1920×1080 | 1109px | 9.24167px | containment/readability/viewer chrome pass |
| 2048×1320 | 2048×1320 | 1416px | 11px | containment/readability/viewer chrome pass |

**Visual review: passed，correction_rounds: 2。** 已实际打开[四张原版截图](07-tool-call.visual-check.html)复核：两种主题下定义/决策、工具执行、后续模型调用顺序可辨；RUNNING→handler→SUCCESS→result、observation→decision→FINISH解析→独立final路径完整。消息标签、图例和卡片无可见遮挡，大屏有均衡纵向占用。仅记录默认 READ / Still 视觉范围；搜索/focus/passport交互与导出文件未另测。[独立视觉记录](07-tool-call.visual-review.json)绑定本次HTML，原版自动回执的visualReview仍为pending。

所有命令、退出码、历史目录与 specification/artifact 字节绑定见[本轮 acceptance](07-tool-call.acceptance.json)。只完成图07，不开始图08，不执行最终 Architecture Audit；本轮未提交推送，也未新增真实 provider 或业务运行验收。


## 图 08 施工前工作记录（2026-09-16）

在 Typed JSON 前记录：本图只回答 Task 的持久状态与合法转换，类型 lifecycle。基线 main@0320892af12fdafb1b5ae89e9135b987dbd1630a，dirty working tree；已交付01–07及所有未提交改动保留。Archify 2.17 / package 2.17.0-dev.1，showcase。本轮不开始图09，不展开线程池/队列内部或工具/RAG流水线。

- **Confirmed nodes / states（CODE_CONFIRMED）：** TaskStatus 与 V18 一致：QUEUED、RUNNING、COMPLETED、FAILED、CANCELLED、TIMED_OUT。后四种 terminal；无 RECOVERING、CANCELLING 或 Future 状态节点。
- **Confirmed edges：** createNew 原子创建 QUEUED + TASK_CREATED；claimQueued 在未取消QUEUED上条件更新RUNNING并TASK_STARTED。RUNNING经settleObserved仲裁至四终态；QUEUED派发拒绝至FAILED、owner取消至CANCELLED。恢复只将遗留QUEUED/RUNNING按已落库取消标记分为CANCELLED或FAILED；同一状态对合并一条图边，并列触发条件，逐触发证据在Atlas分开。
- **Confirmed guards / writers：** LifecycleTransactionService 短REQUIRES_NEW事务，状态更新+事件原子提交；settleObserved FOR UPDATE后取消优先，complete/fail/timeout SQL要求cancel_requested_at为空，finishCancellation要求非空。Runner只claim一次，Engine在DB事务外返回outcome；SettlementService最多3次数据库保存/回读，不再次执行Engine。
- **Confirmed non-transitions：** RUNNING的owner取消仅首次写cancel_requested_at，phase变化也不改变status；无新状态。任一terminal是吸收态，重复取消/结算回读现有终态；SSE断开、Future取消、浏览器刷新不是DB转换。
- **Confirmed recovery：** 仅启动CONTROLLED_SINGLE_HOST且本地执行域锁持有、关闭admission，逐任务行锁/校验/终结RUNNING steps和tool logs/更新task+recovery_metadata/追加终态事件为一物理事务。无取消的QUEUED→FAILED/TASK_RESTART_DISPATCH_LOST、RUNNING→FAILED/TASK_RESTART_INTERRUPTED；已有取消→CANCELLED，QUEUED取消异常单独标注。恢复不判为COMPLETED/TIMED_OUT，不重投或重发外部调用；旧JVM已退出是受控切换前置，不从deadline或Future推断。
- **Confirmed failure boundaries：** 条件更新0行不发布事件；正常settlement不确定COMMIT先回读，瞬态失败100/500ms后有界再试，耗尽或永久错误关闭admission并记录TASK_SETTLEMENT_PERSIST_FAILED，不虚构FAILED终态。recovery不变量/DB/事件失败回滚本任务并保持admission关闭；DISABLED有遗留任务也不开放。
- **TEST_CONFIRMED（断言阅读，未运行）：** TaskSettlementServiceTest验证保存同一outcome/time、未知COMMIT回读、取消竞争与最多3次尝试；TaskRecoveryPostgresIntegrationTest断言遗留任务FAILED/取消优先/回滚与重跑幂等、保留历史成功事实和UNKNOWN完整性；AgentTaskPostgresIntegrationTest覆盖claim、取消竞争和终态事件。只读断言不升级为本轮业务验收。
- **DOC_DECLARED / UNKNOWN：** V0.2文档规定停止旧JVM/受控冷切换；本轮不执行该操作，未验证目标环境锁域、DB、真实provider或中断后的远端行为。早期V38“无recovery”仅历史切片范围，当前代码已含V0.2启动结算。

## 图 08 Evidence index

- **Diagram:** 08 · Task Lifecycle，2026-09-16，complete。
- **Question:** Task 的持久化状态机是什么，哪些触发条件允许状态转换？
- **Type:** `lifecycle` / schema v1 / showcase。
- **Primary path:** 创建事务产生 QUEUED → claim 为 RUNNING → 结算为 COMPLETED；失败、取消、deadline 与受控重启结算形成旁支。
- **Key nodes:** 六个节点与 [TaskStatus](../../backend/src/main/java/com/agentflow/agent/task/model/TaskStatus.java) 六项 enum 一一对应；四终态无回边。七条状态对关系合并重复端点，下面按不同触发分别列证据。
- **Key evidence:** 当前 service、条件 UPDATE、迁移约束、Runner/settlement/recovery 及具体测试断言，见下表。
- **JSON:** [候选](08-task-lifecycle.candidate.lifecycle.json)、[正式源](08-task-lifecycle.lifecycle.json)，原字节一致。
- **HTML:** [图 08](08-task-lifecycle.html)。
- **Validation:** [候选 validate](08-task-lifecycle.candidate.validation.json)、[交接 validate](08-task-lifecycle.validation.json)、[deliver](08-task-lifecycle.delivery.json)。
- **Visual check:** [原版 browser receipt](08-task-lifecycle.visual-check.json)、[截图集](08-task-lifecycle.visual-check.html)、[独立视觉复核](08-task-lifecycle.visual-review.json)。
- **Unknowns:** 本轮未启动业务应用/DB、未执行 controlled recovery 或真实 provider；外部计算停止、目标环境执行域锁与旧 JVM 退出均无新运行证据。图集完成不等于业务验收。

### 转换证据与原子边界

下表全部为 **CODE_CONFIRMED（静态追踪）**。简称 L 为 [AgentTaskLifecycleTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java)，SQL 为 [AgentTaskMapper](../../backend/src/main/java/com/agentflow/agent/task/repository/AgentTaskMapper.java)，R 为 [TaskRecoveryTransactionService](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskRecoveryTransactionService.java)，R-SQL 为 [TaskRecoveryMapper](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskRecoveryMapper.java)。这些方法通过 MyBatis/JDBC 同步访问同一 PostgreSQL；箭头表达合法持久状态变更，不表示执行线程或网络通信。

| 图中 ID / 转换 | Trigger / guard | Writer / persistence effect | Failure behavior |
| --- | --- | --- | --- |
| 初始 tl-queued：不存在 → QUEUED | 新建请求，经身份、owner-scoped Agent/config version、快照及准入检查 | [CreationTransactionService.createNew](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java)，REPEATABLE_READ 创建事务：INSERT QUEUED、phase=NULL、零 counters/tokens、UNKNOWN usage、execution_snapshot + TASK_CREATED；afterCommit 注册派发 | 配置/DB/事件失败回滚，没有虚构的 CREATED 状态；幂等回读已有 task 不另生转换 |
| tl-claim：QUEUED → RUNNING | [TaskRunner.run](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) claim；准入 ready；SQL status=QUEUED 且 cancel_requested_at IS NULL | L.claim → SQL.claimQueued，REQUIRES_NEW；写 RUNNING/PREPARING、started_at/updated_at、version+1，与 TASK_STARTED 同事务 | 条件不匹配返回 null，不调用 executor；事件/DB失败回滚 claim |
| tl-complete：RUNNING → COMPLETED | Runner 提交 COMPLETED outcome；L.settleObserved 行锁要求 RUNNING，落库取消优先；SQL 要求 cancel=NULL | L.completeAt → SQL.completeRunning；短事务写 final_answer、citations、usage/counters、termination_reason、completed_at、phase=NULL，并追加 ANSWER_CHUNK + TASK_COMPLETED | 事件序列化/写入失败全部回滚；0行不发事件；锁定结算遇0行抛异常。保存层处理见下文 |
| tl-queued-failed：QUEUED → FAILED，派发拒绝 | 创建 COMMIT 后 dispatch 异常或 Runner 准入异常的拒绝补偿；SQL 仍 QUEUED 且未取消 | [AfterCommitTaskDispatchCoordinator](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinator.java) / Runner → Settlement.rejectDispatch → L.markDispatchRejected → SQL.failQueuedDispatch；FAILED/SYSTEM_ERROR/TASK_DISPATCH_REJECTED + TASK_FAILED 同事务 | 竞争失败0行不发布事件；回读已终态可接受，仍非终态不能当成功；保存失败关闭准入 |
| tl-running-failed：RUNNING → FAILED，执行失败 | FAILED outcome；Runner 捕获执行异常可构造 TASK_INTERNAL_ERROR；锁内落库取消优先，SQL RUNNING 且 cancel=NULL | L.failAt → SQL.failRunning，写安全 error_code/message、usage/counters、completed_at、phase=NULL、answer=NULL/citations=[] + TASK_FAILED | 无原始堆栈作为公共结果；DB/事件失败回滚，不能把内存失败 outcome 当已落库 FAILED |
| tl-queued-cancel：QUEUED → CANCELLED，owner 取消 | [RestService.cancel](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskRestService.java) 使用 authenticated user；准入 ready；SQL user_id 匹配且仍 QUEUED | L.requestCancellation → SQL.cancelQueuedOwned；直接写 CANCELLED/USER_CANCELLED、cancel_requested_at/completed_at、phase=NULL、version+1 + TASK_CANCELLED | 未匹配则尝试 RUNNING 标记，再 owner 回读；越 owner/not found 拒绝。重复 terminal 取消回读，无重复终态事件 |
| tl-running-cancel：RUNNING → CANCELLED，结算 | RUNNING 已有 cancel_requested_at；锁内取消覆盖成功/失败/超时 observed outcome，并保留 observed usage/counters | L.settleObserved → finishCancellationAt → SQL.finishRunningCancellation；CANCELLED/USER_CANCELLED、completed_at、phase=NULL、无答案/错误码 + TASK_CANCELLED | SQL 必须 RUNNING 且 cancel 非空；仅内存 CANCELLED outcome、没有持久取消标记不能强行写；0行拒绝结算 |
| tl-timeout：RUNNING → TIMED_OUT | Runner 以 started_at + 冻结 timeoutSeconds 判定 task deadline；开始前或 outcome 后到期，且无优先落库取消 | L.timeOutAt → SQL.timeOutRunning；TIMED_OUT/DEADLINE_EXCEEDED、completed_at、phase=NULL、usage/counters、无答案/错误码 + TASK_TIMED_OUT | 竞争/DB/事件失败不发布假终态；tool/provider 自身超时不一概等于 task deadline；不存在 QUEUED→TIMED_OUT SQL |
| tl-queued-failed：QUEUED → FAILED，重启失投 | 受控启动、锁域持有；R 行锁确认遗留 QUEUED、无取消，且无执行证据或非零执行 counters/usage | R.recover → R-SQL.settleTask；FAILED/SYSTEM_ERROR/TASK_RESTART_DISPATCH_LOST；metadata.executionOutcome=NOT_STARTED、完整性 COMPLETE；TASK_FAILED | 非终态带终态发布证据、PENDING tool、不一致 usage、0行条件更新或事件失败：整笔恢复事务回滚、准入关闭 |
| tl-running-failed：RUNNING → FAILED，重启中断 | 同一受控恢复 gate；遗留 RUNNING，无落库取消；**即使 deadline 已过期** | R.recover → R-SQL.settleTask；FAILED/SYSTEM_ERROR/TASK_RESTART_INTERRUPTED；metadata.executionOutcome=UNKNOWN、record/counter completeness=UNCONFIRMED；TASK_FAILED | 不依据本地记录推断 provider 完成，不复用成功 LLM 日志“抢救”COMPLETED，不改为 TIMED_OUT，不重投任务 |
| tl-running-cancel：RUNNING → CANCELLED，恢复取消 | R 行锁读取到 durable cancel；同一受控恢复 gate | R.recover；CANCELLED/USER_CANCELLED，error=NULL，metadata.reasonCode 仍 TASK_RESTART_INTERRUPTED；TASK_CANCELLED；记录原运行事实未确认 | 与其他恢复相同物理事务；取消先落库才获优先，已 terminal 则 false/无新事件 |
| tl-queued-cancel：QUEUED → CANCELLED，异常记录恢复 | R 处理遗留 QUEUED + cancel 标记的异常记录；**正常 V18 cancel shape 不允许该组合** | R.recover；CANCELLED/USER_CANCELLED；metadata.queuedCancellationAnomaly=true，reasonCode=TASK_RESTART_DISPATCH_LOST、NOT_STARTED | 仅防御性遗留/异常修复分支；不能解释为正常 API 会把带标记 QUEUED 留待执行。测试临时放宽约束构造它 |

Runner 经 settleObserved 的正常结算以 observedAt 为完成观察时刻；状态更新写 version+1；状态/取消条件参与并发仲裁，不能误称所有正常 SQL 都使用 version CAS。恢复 SQL 额外比较 previousStatus、version 与 cancel 标记，确保恢复事实没有被竞争修改。

### 非转换、数据库形状和恢复范围

- **RUNNING 取消不是即时终态：** L.requestCancellation → SQL.requestRunningCancellationOwned 只首次更新 cancel_requested_at/updated_at/version，status 仍 RUNNING，此时无 TASK_CANCELLED。L.changePhase 只在 RUNNING、无取消、phase 改变时更新并 PHASE_CHANGED；phase 不增添 DB status。Future cancel、SSE 断开、浏览器刷新均不是图边。
- **终态含义：** [V18](../../backend/src/main/resources/db/migration/V18__create_agent_task_and_event.sql) 限定 QUEUED 未 started/completed，RUNNING 有 phase/started，无 completed；终态 phase=NULL、reason/completed 非空。COMPLETED 的 reason 可为 ANSWERED、MAX_DECISION_TURNS、MAX_TOOL_CALLS，表示答案已持久发布，不保证用户目标完全达成。FAILED 可能为 SYSTEM_ERROR 或 TOKEN_BUDGET_EXHAUSTED。CANCELLED/TIMED_OUT 的 reason 分别 USER_CANCELLED/DEADLINE_EXCEEDED。
- **保存重试是数据库工作：** [TaskSettlementService.settle/rejectDispatch](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskSettlementService.java) 保存同一首次 outcome/usage/counters/observedAt。未知 COMMIT 先回读已 terminal；仅可识别瞬态 DB 故障最多3次（初次+100ms/500ms后2次）。耗尽、永久错误或中断时记录 TASK_SETTLEMENT_PERSIST_FAILED 并降级准入，不能据此画 FAILED 转换，也不再执行 Engine、模型、工具。
- **恢复事务：** R 在同一物理 REQUIRES_NEW 事务内锁 task、校验记录、结束遗留 RUNNING steps/tools、条件更新 task/recovery_metadata、追加终态事件。历史成功 step/tool/LLM 事实保留；可聚合已记录数值，但 task 顶层 usage quality 保持 UNKNOWN。异常回滚的是本任务，先前已提交的其他恢复任务不受该回滚影响。[V22](../../backend/src/main/resources/db/migration/V22__add_task_recovery_metadata.sql) 为 metadata 及重启中断时未知延迟提供数据库形状。
- **恢复 gate：** [TaskRecoveryStartupCoordinator.run](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskRecoveryStartupCoordinator.java)、[TaskRecoveryProperties](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskRecoveryProperties.java)、[TaskExecutionProcessLock.acquire](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskExecutionProcessLock.java) 使用稳定本地执行域锁，受控启动逐任务结算。默认 DISABLED 仍持锁，有遗留任务则准入保持关闭；无 recovery 执行边。旧 JVM 已确认退出是受控冷切换的前置要求，不能由新 JVM 中的 Future 或单纯超时来证明。
- **图中分区只用于阅读：** 顶部正常推进，中部期限终态，下部其他终态；不是新增状态、线程、事务或恢复执行阶段。TIMED_OUT 的 terminal 类型不因布局位置改变。失败、取消边合并了相同状态对的多个触发，上表保留全部 guard/writer 差异。

### 测试、文档与跨图边界

以下均 **TEST_CONFIRMED：已读断言，本轮未执行**，不计入新的运行 PASSED：

- [AgentTaskPostgresIntegrationTest](../../backend/src/test/java/com/agentflow/agent/task/AgentTaskPostgresIntegrationTest.java)：claim 单赢家及事务外执行；派发拒绝；queued 立即取消、running 只写标记；complete/timeout/cancel 竞争只产生一条终态事件。
- [TaskSettlementServiceTest](../../backend/src/test/java/com/agentflow/agent/task/execution/TaskSettlementServiceTest.java)：保持同一 outcome/time；未知 COMMIT 回读；100/500ms 与最多3次；持久取消竞争、永久错误不重试、耗尽/中断降级。
- [TaskRecoveryPostgresIntegrationTest](../../backend/src/test/java/com/agentflow/agent/task/recovery/TaskRecoveryPostgresIntegrationTest.java)：失投/中断、过期 RUNNING 仍 FAILED、取消提交优先、幂等恢复、事件失败原子回滚、保留历史成功记录、UNKNOWN/UNCONFIRMED 边界、queued cancellation anomaly。

V0.2 冷切换说明是 **DOC_DECLARED 的操作前置**，相应锁/准入/结算代码已直接确认；本轮没有完成该操作的运行证据。早期切片的“无 recovery”边界仅描述其当时范围，当前源码已有启动结算实现，图08以当前代码为准。与图03–07一致：Engine outcome、工具 SUCCESS、模型 FINISH/答案内容均不直接等价于 task 已提交终态；afterCommit 派发、独立短事务结算与持久事件观察分开。未开始图09–15及最终 Architecture Audit。

### 图 08 修复与分层验收

初始候选的原版几何诊断数为7。按具体 subject 和 supportedFixes 依次处理主路径短折线/共享通道、失败标签净距、queued 取消路径、期限节点与取消标签冲突及成功标签位置；每次编辑后运行原版 showcase validate，诊断数为 **7 → 7 → 6 → 5 → 2 → 1 → 0**。六个状态、七条关系、三张卡片均保留。每轮旧候选、完整原版回执与退出码保留在 [history](history/) 的08前缀目录，未重写为成功证据。

第一次 deliver 和 browser 通过后，实际打开四张截图发现中间分区沿用默认英文标题。**视觉修复1**仅增加受支持的中文分区 label，未改状态、关系、文字字号或几何；再次 validate → deliver → visual-check，全部 exit0。原第一次产物和视觉问题记录保留在 [历史目录](history/08-before-lane-localization/)。

**Artifact validation: passed。** 最终原版 validate / deliver 均9/9 showcase、composition 0 errors / 0 warnings。properCrossings=0、ambiguousCorridors=0、labelRouteClearanceIssues=0、short/micro segment=0；最小标签/其他线净距5px，最短线段18px，最短内部段129px。原版 lifecycle 静态回执的 minProjectedNodeTextPx 为 null，不能把 null 当作字号达标证据；字号证据来自实际浏览器及独立 SVG 全节点测量。

**Browser evidence: passed。** 四个 light / READ / Still 视口的实际测量如下；两个端点尺寸另有 light/dark 四张截图。所有结果来自本次 deliver 后的原版 visual-check，含 viewer chrome/图例净距检查。

| Viewport | scrollWidth × scrollHeight | diagramWidth | 最小预计节点字号 | 结果 |
| --- | --- | --- | --- | --- |
| 1440×900 | 1440×900 | 970px | 6.59223px | containment / readability / viewer chrome pass |
| 1600×1000 | 1600×1000 | 1037px | 7px | containment / readability / viewer chrome pass |
| 1920×1080 | 1920×1080 | 1167px | 7px | containment / readability / viewer chrome pass |
| 2048×1320 | 2048×1320 | 1491px | 7px | containment / readability / viewer chrome pass |

**Visual review: passed，correction_rounds: 1。** 已实际打开本次四张截图：顶部 QUEUED→RUNNING→COMPLETED 主线清楚；失败、取消、deadline 分支可沿标签定位，四终态无回边。标签、节点、图例、卡片无可见遮挡，两主题一致；中间标题已中文化，大屏纵向占用均衡，没有明显空白底带。QUEUED 的 claim 与失败支路共享同源起始段后分叉，颜色与标签可辨；原版 ambiguousCorridors=0，不将同源分叉误述为完全无任何共线。复核范围为默认 READ / Still 截图；搜索、focus/passport 交互及导出文件未另测。原版回执 visualReview 仍为 pending，独立视觉记录不改写自动回执。

[补充布局测量](08-task-lifecycle.layout-measurements.json)逐一保存6个节点的SVG矩形、全部标题10px/副标题7px、7条真实路径和标签坐标；源字号由原版 lifecycle 渲染生成，未缩小。viewBox=1030×630，未裁切、缩放作弊、隐藏 overflow 或手改HTML。[Acceptance](08-task-lifecycle.acceptance.json)记录命令、实际退出码、历史和 specification/artifact 字节绑定。仅图08及本 Atlas 在本轮更新，既有01–07产物与其他工作树改动保留；未提交推送。

## 图 09 施工前工作记录（2026-09-16）

本图只回答已提交任务如何经过本地执行池、队列、claim 与实际外部工作许可形成背压；使用 workflow v2/showcase。施工基线 main@0320892af12fdafb1b5ae89e9135b987dbd1630a，dirty working tree；图01–08与所有原有改动保留，不开始图10。

- **CODE_CONFIRMED nodes / capacities：** afterCommit 提交；BoundedTaskDispatcher 的 ThreadPoolTaskExecutor；Runner claim/事务外 Engine；TaskExternalCallDeadline 的公平 Semaphore、virtual worker、真实退出释放及 waiter 放弃观察。任务线程 core2/max4；独立等待队列100；进程内 task 外部工作许可默认4（1–64、环境覆盖）。本轮 application-dev.yml 未覆盖这些项，实际部署覆盖值 UNKNOWN。
- **CODE_CONFIRMED edges：** task+TASK_CREATED COMMIT 后 executor.execute；accepted job 由 agent-task- worker 调 runDispatched；claimQueued 要求 QUEUED且未取消，更新RUNNING/PREPARING+TASK_STARTED同一短事务；无匹配直接返回。Engine事务外执行，其 LLM/RAG/tool handler 通过共用TaskExternalCallDeadline。许可获取后启动虚拟线程；body真正finally退出才释放；waiter超时/取消放弃观察但不会释放已进入body的许可。
- **CODE_CONFIRMED branches / boundaries：** AbortPolicy或准入拒绝后条件FAILED/TASK_DISPATCH_REJECTED补偿；已入队但准入关闭也经runDispatched拒绝路径。等待许可/结果均至多50ms一次边界检查；未进入body时取消通过CAS归还一次，已进入则finally归还一次；嵌套同步工作继承同一许可及父boundary。任务线程返回、DB终态、provider停止分开。
- **TEST_CONFIRMED（读取断言，未执行）：** DispatcherConfigurationTest验证2/4/100/AbortPolicy和零队列饱和拒绝；AfterCommitCoordinatorTest验证回滚不提交和拒绝补偿；TaskExternalCallDeadlineTest验证取消后真实工作仍占许可、未进入取消/launch失败精确归还、嵌套容量1。V0.2-B脚本B01/B02/B03检查受控迟到/容量耗尽/细粒度伴随测试，未运行。
- **DOC_DECLARED / UNKNOWN：** 受控独立HTTP夹具不能证明真实provider停止/计费/可用性。没有Redis或持久队列、跨JVM全局许可或自动重投证据。当前配置为静态配置，未检查目标运行进程的环境覆盖。

## 图 09 Evidence index

- **Diagram:** 09 · Dispatch, Thread Pool and Concurrency Control（2026-09-16）。
- **Question:** 已提交任务如何通过本地线程池、队列、claim 与实际外部工作许可形成背压？
- **Type:** `workflow` / schema v2 / showcase；10个节点、10条具名关系、3张说明卡。
- **Primary path:** 创建已提交 → 有界任务调度 → Runner claim → 冻结快照执行 → 许可等待 → 实际工作 → finally归还；等待方可独立中止。
- **Key nodes:** dc-pool 汇总同一个 executor 的线程/队列，卡片分别说明两项容量；dc-permit/dc-body/dc-release 表达另一项实际外部工作容量。泳道是阅读分区，不是新增组件或精确线程泳道。
- **Key evidence:** 下列当前源码/配置/依赖实现与具体断言；运行时覆盖和真实provider状态仍UNKNOWN。
- **JSON:** [候选](09-dispatch-concurrency.candidate.workflow.json)、[正式源](09-dispatch-concurrency.workflow.json)。
- **HTML:** [本轮交付](09-dispatch-concurrency.html)，交付成功只证明artifact，不自动代表视觉验收完成。
- **Validation:** [候选回执](09-dispatch-concurrency.candidate.validation.json)、[正式交接回执](09-dispatch-concurrency.validation.json)、[deliver](09-dispatch-concurrency.delivery.json)、[compiler layout](09-dispatch-concurrency.layout.json)。
- **Visual check:** [原版浏览器回执](09-dispatch-concurrency.visual-check.json)、[截图集](09-dispatch-concurrency.visual-check.html)、[独立复核](09-dispatch-concurrency.visual-review.json)。各层实际状态见[acceptance](09-dispatch-concurrency.acceptance.json)。

### 三层容量与配置证据（CODE_CONFIRMED）

| 容量 | 当前仓库配置与机制 | 等待/释放边界 |
| --- | --- | --- |
| Task executor threads | [AgentTaskDispatcherConfiguration.agentTaskExecutor](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AgentTaskDispatcherConfiguration.java) 设置 ThreadPoolTaskExecutor 的 core=2/max=4，线程前缀 agent-task-，AbortPolicy | worker 执行 runDispatched；整个Runner期间占用任务线程，包括等待外部许可/结果与终态保存。线程处理完Runnable后才可执行其他任务 |
| Task executor queue | [DispatcherProperties](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AgentTaskDispatcherProperties.java)、[application.yml](../../backend/src/main/resources/application.yml) queue-capacity=100 | 单JVM内存队列，存 Runnable；入队不等于DB claim，排队时task仍可QUEUED。容量可设0，使用直接交接而非容量无限的队列 |
| Actual outstanding task external work | [TaskExecutionProperties](../../backend/src/main/java/com/agentflow/agent/engine/TaskExecutionProperties.java) maxConcurrentExternalCalls=4，1–64；YAML可由 AGENTFLOW_TASK_MAX_CONCURRENT_EXTERNAL_CALLS 覆盖；[TaskExternalCallDeadline](../../backend/src/main/java/com/agentflow/agent/engine/TaskExternalCallDeadline.java) 创建公平 Semaphore | 许可先于virtual worker启动取得；已进入body者由body finally释放。waiter取消后仍未退出的body继续占用容量；未进入body的取消由Lease CAS归还一次 |

`application-dev.yml` 未覆盖上述三个字段；这里只是仓库静态配置，不宣称目标进程实际值。V0.2-B受控脚本将外部容量覆为1，仅用于验收场景。三个数字不能混同：队列容量不是线程数，任务线程数不是provider并发数；非task路径、独立JVM、provider服务端后台工作不由这个进程内Semaphore统一限流。

已核对项目 Java21 与 Spring Boot3.5.15 的本地依赖：其BOM锁定Spring Framework6.2.19，`ThreadPoolTaskExecutor.createQueue` 的已安装class显示正容量为有界LinkedBlockingQueue，非正为SynchronousQueue；本项目validator拒绝负数。已读取本地JDK21 `ThreadPoolExecutor.execute`：worker数量不足core时优先增worker；否则尝试offer入队；无法入队才尝试增至max；增worker失败（饱和或shutdown）触发拒绝。不是“先开满4线程再排100项”。配置的shutdown等待完成/最多等待10秒不证明外部请求已停止，也不是持久队列恢复保证。

### 逐边关系、调用及持久化影响（CODE_CONFIRMED）

| Edge ID | source → target / 方法 | 机制、guard与持久化副作用 |
| --- | --- | --- |
| dc-submit | [CreationTransactionService.createNew](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java) → [AfterCommitTaskDispatchCoordinator.dispatchAfterCommit](../../backend/src/main/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinator.java) → [BoundedTaskDispatcher.dispatch](../../backend/src/main/java/com/agentflow/agent/task/dispatch/BoundedTaskDispatcher.java) | QUEUED与TASK_CREATED在创建事务提交后，afterCommit同步调用dispatcher；admission.requireReady后 executor.execute(() -> runner.runDispatched(taskId))，跨到本地executor worker。回滚不触发dispatch，提交阶段没有第二次INSERT task |
| dc-schedule | dc-pool → [TaskRunner.runDispatched/run](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) → [LifecycleTransactionService.claim](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java) | 此边包含直接新worker或排队后worker领取，不声称每个job都先排队。再次准入检查；[AgentTaskMapper.claimQueued](../../backend/src/main/java/com/agentflow/agent/task/repository/AgentTaskMapper.java) 条件QUEUED且cancel=NULL，写RUNNING/PREPARING、started_at、version+1；TASK_STARTED同一REQUIRES_NEW事务 |
| dc-enter | claim → Runner → TaskExecutionDelegate.execute | 仅claim返回task才解析冻结快照、计算startedAt+timeoutSeconds、检查取消/deadline。Engine在DB事务外同步执行；无围住整次模型/工具等待的长DB事务。取消或已到期可直接形成outcome，不实际进入Engine |
| dc-reject-edge | dispatcher / runDispatched → [TaskSettlementService.rejectDispatch](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskSettlementService.java) | executor AbortPolicy/准入等异常由afterCommit补偿；已接受job在claim前遇准入关闭/异常由runDispatched补偿。markDispatchRejected → failQueuedDispatch要求仍QUEUED且无取消；FAILED/SYSTEM_ERROR/TASK_DISPATCH_REJECTED + TASK_FAILED短事务原子保存。竞争0行不冒充成功，永久/耗尽保存失败关闭准入，详细收敛不在本图展开 |
| dc-skip-edge | claim → Runner.run返回 | SQL未匹配返回null，既不执行Engine也不追加TASK_STARTED；包括已被其他runner领取、已取消/终态。此分支不代表运行期取消会抹去既有执行事实 |
| dc-call | Engine/ToolRuntime → TaskExternalCallDeadline.call | caller同步等待许可及Future结果。初始boundary先检查；顶层tryAcquire以min(50ms,remaining)分段等待，deadline/取消/interruption可终止等待。未取得许可不启动工作；等待发生在当前任务线程上 |
| dc-launch | permit → virtual worker / action.call | 取得许可后再check；新FutureTask由虚拟线程启动。Lease.enter CAS 0→1，active++，安装ThreadLocal boundary；再check才调用action。LLM、一次串行RAG、工具handler每个顶层body持一个许可，不是每个RAG子请求各持一个 |
| dc-abandon | call内waiter → 结束本次等待 | 概括等待许可或等待结果时的超时/取消/中断；finally置abandoned并cancel(true)，已进入body者不在此释放。未进入者cancelBeforeEntry CAS 0→2归还，重复启动/取消不重复释放。外部工作可仍运行，此边没有指向“provider停止” |
| dc-exit | action返回/抛异常 → Lease.exit | worker finally移除ThreadLocal，Lease CAS 1→2、active--、Semaphore.release。这里指本地实际工作体退出；即使本地I/O已经退出，仍不能无provider侧证据推定远端后台计算或计费停止 |
| dc-result | body结束/许可归还 → 仍在等待的caller | Future.get拿到结果或异常；正常结果observe回调可先记录可用usage，随后再check决定是否接受，不发布迟到答案。已放弃的waiter不重新唤醒推进业务。Engine可能继续下一步；本次等待结束不等于整个task结束，Runner最终才交给settlement |

### 外部工作所有权与失败边界

- [TaskSnapshotAgentExecutor.preRetrieve/callLlm](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) 分别包裹整次RAG与单次LLM；[SnapshotRagService.retrieve](../../backend/src/main/java/com/agentflow/agent/rag/SnapshotRagService.java) 在顺序embedding/vector和回查之间调用boundary。父waiter已abandoned后，不允许迟到embedding继续启动后续vector。RAG同一次同步body共享许可，没有嵌套等待第二个许可的死锁。
- [DefaultToolRuntime.executeWithinTaskDeadline](../../backend/src/main/java/com/agentflow/tool/DefaultToolRuntime.java) 包裹builtin handler，使用min(task deadline,tool deadline)，工作线程临时名agent-tool-{taskId}。这些内置工具当前是本地只读handler，不把“外部工作”泛称为全部都是网络provider。
- `ownedBoundary`存在时同步嵌套call继承许可、父取消与deadline，临时替换并finally恢复boundary；容量1也不会二次acquire。只有跨组件的同一同步工作体适用，不是任意线程自动继承。
- `activeWorkCount`在enter后才增加，取得许可但尚未enter的窗口可出现availablePermits减少而active仍0；不能把available与active简单当作永远相加等于capacity。launch失败/获得许可后boundary拒绝由未进入路径归还；工作进入后即使Future显示cancelled也不归还。
- 代码中的50ms是单次tryAcquire/get的等待上限，受线程调度、boundary自身DB读取等影响，不是严格50ms内完成取消的延迟SLA。wall clock deadline与monotonic剩余预算共同约束等待。若工作永久不退出，许可可持续耗尽；实现限制本地实际并发，不承诺自动回收、远端杀请求或重试业务。
- [TaskExecutionAdmission](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskExecutionAdmission.java) 从STARTING关闭，启动成功才open；关闭/保存失败/shutdown阻止后续任务写与dispatch/claim。不是外部call semaphore，不能用admission READY证明provider可达。任务线程结束后可领取下一job，即使旧body仍占外部许可；这正是两层容量需分开的原因。

### 测试与证据等级

**TEST_CONFIRMED，仅阅读断言，本轮未运行：**

- [AgentTaskDispatcherConfigurationTest](../../backend/src/test/java/com/agentflow/agent/task/dispatch/AgentTaskDispatcherConfigurationTest.java)：默认2/4/100/AbortPolicy；core=max=1、queue=0的受控堵塞后第二次提交TaskRejectedException。
- [AfterCommitTaskDispatchCoordinatorTest](../../backend/src/test/java/com/agentflow/agent/task/dispatch/AfterCommitTaskDispatchCoordinatorTest.java)：回滚不dispatch、只afterCommit提交、拒绝调用补偿。
- [TaskExternalCallDeadlineTest](../../backend/src/test/java/com/agentflow/agent/engine/TaskExternalCallDeadlineTest.java)：不响应中断body在waiter退出后active=1/permits=0；等待方取消不启动下一个action；真正退出后active=0/permits恢复。覆盖acquire前取消、acquire后未enter取消及重复run、launch失败、正常usage观察与嵌套单许可父boundary。
- [V0.2-B acceptance脚本](../../scripts/v02b-interruption-acceptance.py) 的B01核对受控迟到结果与外部调用计数不变；B02将容量置1，旧工作未退出时等待取消/超时均无新调用，退出后新任务成功；B03明确需要Java竞态伴随测试，不能用进程脚本替代。

[受控验收说明](../../scripts/v02b-interruption-acceptance.md)属DOC_DECLARED的运行操作与场景说明；已直接检查上述脚本断言，但本轮没有新的B01–B10 PASS。独立HTTP夹具不证明真实provider取消、计费、可用性。未检查目标进程配置覆盖、生产负载、跨JVM调度或provider后台行为，均为UNKNOWN。图04 afterCommit、图05/07事务外工具/模型等待、图08状态结算与本图一致；不展开图10失败收敛或图11恢复流程。

### 图 09 修复与最终验收

**Artifact validation: passed。** 首版及最终版均由原版CLI完成9/9 showcase检查；最终composition 0 errors / 0 warnings，properCrossings=0、ambiguousCorridors=0、labelRouteClearanceIssues=0、短折线/微线段=0。viewBox与requiredViewBox均1147×534；最短segment16px、最短interior26px、最小标签/其他线净距30px。部分自动路由仍超过建议弯折/伸长值（maxBends=4、maxStretch=15.738），原版未列error/warning；保留真实数值，不称所有路线都最短。静态minProjectedNodeTextPx=null，字号验收使用实际browser及全节点SVG测量：10个节点源标题11px、副标题8px，未缩小字体。

**修复记录：** 首版机器检查和browser通过，但实际截图发现dc-call与dc-launch在x=783.6、y=162..246反向共线，视觉上可能误读为绕过许可；因此首版visual review failed，未宣称完成。[完整历史](history/09-before-call-route/)保留源、布局、HTML、回执及截图。视觉修复1只给dc-call设置straight，原版返回workflow/route-preset-conflict；未deliver失败候选。[失败原件](history/09-straight-conflict/09-dispatch-concurrency.candidate.validation.json)保留。视觉修复2按supportedFixes撤销route preset，并为该关系指定bottom→top端口，交由原版自动求解；其余节点/边/cards/mainPath/semanticChecks不变。最终路径虽有绕行，但进入许可与启动工作不再共用通道。

**Browser evidence: passed。** 最终deliver exit0后重新运行原版visual-check exit0/status=pass；默认READ/Still四个light视口全部通过containment/readability/viewer chrome。两个端点尺寸额外覆盖light/dark四张截图；未将其扩称为每个尺寸都运行双主题。

| Viewport | scrollWidth × scrollHeight | diagramWidth | 最小预计节点字号 | 结果 |
| --- | --- | --- | --- | --- |
| 1440×900 | 1440×900 | 1306px | 8px | pass |
| 1600×1000 | 1600×1000 | 1357px | 8px | pass |
| 1920×1080 | 1920×1080 | 1529px | 8px | pass |
| 2048×1320 | 2048×1320 | 1870px | 8px | pass |

**Visual review: passed，correction_rounds: 2。** 已实际打开本次四张明暗PNG；主线、拒绝/claim跳过、许可取得、实际工作退出与等待中止可辨，标签、节点、图例和卡片无可见遮挡；最大视口纵向占用均衡。dc-call保留较长绕行，箭头清楚进入许可节点，不把它解释为直接进入body。默认READ/Still之外的focus/search/passport交互及导出文件未另测。原版browser回执仍visualReview=pending，独立视觉记录另存，不改写自动结论。

交接前正式源再次原版showcase validate exit0；candidate与正式JSON原字节一致。所有命令退出码及specification/artifact字节绑定见[acceptance](09-dispatch-concurrency.acceptance.json)。未修改Skill、业务代码或验证门槛，未手改deliver HTML、缩小字体、删除语义或用overflow隐藏内容。仅完成图09，未开始图10，未提交推送。

## 图 10 施工前工作记录（2026-09-16）

基线main@0320892af12fdafb1b5ae89e9135b987dbd1630a，dirty working tree；原图01–09及其他改动保留。图10只回答执行中断与持久化失败怎样收敛，按指南13.2拆为10A执行中断lifecycle及10B终态保存workflow v2，二者都验收通过才算图10完成；不施工图11。

- **CODE_CONFIRMED：** RUNNING owner取消仅落cancel_requested_at，边界探针读到后形成TASK_CANCELLED；task deadline形成TASK_TIMED_OUT；单次LLM超时为AGENT_LLM_TIMEOUT，provider拒绝/工具超时通常FAILED outcome，不自动都变TIMED_OUT。Runner冻结首次outcome与observedAt，持久取消在settleObserved行锁内优先。
- **Confirmed states / boundaries：** 10A是调用观察方与本地工作体的两条独立生命周期，不是TaskStatus enum。Future.cancel(true)只尝试中断；本地body可仍占许可，真实finally才释放；远端停止UNKNOWN。10B区分内存outcome、DB事务写入、回读已提交终态及无法确认时关闭准入，不增加伪DB状态。
- **Confirmed failure paths：** 保存每次先回读，写异常或条件0行再回读；未知COMMIT即使末次/永久异常也先查终态。仅可识别瞬态DB故障最多3次，100/500ms退避；明确SQLSTATE优先于wrapper。永久故障、耗尽、回读永久错误、退避中断降级准入/health DOWN，保留未知事实，不重执行Engine/provider。
- **TEST_CONFIRMED（仅检查断言）：** TaskSettlementServiceTest的同一outcome/time重试、unknown COMMIT、竞争终态、classifier、恢复中断标志与退避中断；TaskExternalCallDeadlineTest的waiter取消后body存活与精确释放；V0.2-B脚本B04/B05/B06/B08的受控超时、DB故障、回滚和DOWN断言。本轮未运行。
- **DOC_DECLARED / UNKNOWN：** 受控fixture只能证明本地边界，不能证明真实provider停止/计费；部署DB连通性、运行时取消延迟、远端工作是否结束均未验证。重启恢复只保留后续主题引用，不展开图11。

## 图 10 Evidence index

- **Diagram / Question:** Failure, Cancellation and Settlement Lifecycle；timeout、cancel、provider failure 或终态保存失败分别怎样收敛？2026-09-16。
- **Type / Scope:** 按指南13.2拆分10A `lifecycle`（6状态/5关系）与10B `workflow` v2（8节点/12关系），均showcase。10A是两条独立的本地生命周期，不是新增TaskStatus；10B为数据库保存过程。图10当前整体 **complete**，10B本次修复验收见末节；不开始图11。
- **Primary path:** 观察调用结束 → Engine结束后冻结outcome → 每次先回读 → 短事务保存 → 终态确认；中断不响应body独立存活。异常保存先回读，允许的瞬态数据库故障才有界退避；无法确认时关闭准入。
- **10A:** [候选](10-failure-cancel.candidate.lifecycle.json)、[正式源](10-failure-cancel.lifecycle.json)、[HTML](10-failure-cancel.html)、[候选validate](10-failure-cancel.candidate.validation.json)、[正式交接validate](10-failure-cancel.validation.json)、[deliver](10-failure-cancel.delivery.json)、[原版browser](10-failure-cancel.visual-check.json)、[截图集](10-failure-cancel.visual-check.html)、[独立视觉复核](10-failure-cancel.visual-review.json)、[acceptance](10-failure-cancel.acceptance.json)。
- **10B:** [候选](10-settlement.candidate.workflow.json)、[正式源](10-settlement.workflow.json)、[HTML](10-settlement.html)、[候选validate](10-settlement.candidate.validation.json)、[正式交接validate](10-settlement.validation.json)、[deliver](10-settlement.delivery.json)、[原版layout](10-settlement.layout.json)、[browser](10-settlement.visual-check.json)、[截图集](10-settlement.visual-check.html)、[视觉复核](10-settlement.visual-review.json)、[acceptance](10-settlement.acceptance.json)。旧[交接失败回执](10-settlement.handoff.validation.json)保持历史失败，不作为当前验收。
- **Overall acceptance:** [图10分层状态](10-acceptance.json)。不能用10A通过替代10B验收。

### 四个必须分开的事实（CODE_CONFIRMED）

| 事实 | 当前实现与边界 |
| --- | --- |
| Logical task cancellation | [LifecycleTransactionService.requestCancellation](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java) 在REQUIRES_NEW内按owner操作。QUEUED可直接CANCELLED并追加TASK_CANCELLED；RUNNING仅首次写cancel_requested_at，尚无终态事件。Engine boundary读取消标记后形成TASK_CANCELLED；已终态请求幂等读回 |
| Local Java cancellation | [TaskExternalCallDeadline.call](../../backend/src/main/java/com/agentflow/agent/engine/TaskExternalCallDeadline.java) 的waiter finally设置abandoned并Future.cancel(true)，只是尝试中断；InterruptedException恢复中断标志并转为执行异常，不等于持久逻辑取消 |
| Actual work termination | 已进入body的Lease只能由body finally CAS 1→2释放一次；未进入的取消CAS 0→2释放。Future显示cancelled不证明body退出；本地退出也不证明远端计算/计费停止。wrapper没有可证明provider远端终止的协议 |
| Terminal persistence success | 内存outcome不是DB终态。[TaskSettlementService](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskSettlementService.java) 接受成功短事务或回读的既有终态；无法确认只降级准入与health，不伪造FAILED，不重执行Engine/provider |

[TaskSnapshotAgentExecutor](../../backend/src/main/java/com/agentflow/agent/engine/TaskSnapshotAgentExecutor.java) 的State.boundary先取消、再检查task deadline。TASK_CANCELLED映射CANCELLED；TASK_TIMED_OUT映射TIMED_OUT；单次模型TIMEOUT映射AGENT_LLM_TIMEOUT，provider拒绝映射AGENT_LLM_REJECTED，其余模型失败等通常FAILED。工具TOOL_TIMEOUT也不自动等同task超时。[DefaultToolRuntime](../../backend/src/main/java/com/agentflow/tool/DefaultToolRuntime.java) 使用任务/工具deadline的较早者；[SpringAiOpenAiCompatibleLlmGateway.chat](../../backend/src/main/java/com/agentflow/infra/llm/SpringAiOpenAiCompatibleLlmGateway.java) 同步调用并分类TIMEOUT/provider异常，没有已证明的远端取消保证。

[TaskRunner.run](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java) 在Engine外执行最终观察：若首次observedAt已到task deadline且outcome不是CANCELLED，则改为TIMED_OUT并保留usage/计数；随后冻结这个outcome与observedAt。数据库重试不能重新计算观察时间或重新跑模型。进入settleObserved后持久cancel_requested_at优先，可覆盖此前内存结果。正常单次调用返回仍可继续Engine；10A fi-freeze只在Engine结束后发生，不是每个外部调用都结束task。

### 10A逐边证据（CODE_CONFIRMED）

| Edge | source → target | 调用、guard与影响 |
| --- | --- | --- |
| fi-stop | fi-wait → fi-ended | TaskExternalCallDeadline.call：等待许可/结果期间检查boundary与deadline；返回、异常、超时、取消均可结束本次观察。每次tryAcquire/get等待上限50ms，不是取消完成SLA，也不直接写终态 |
| fi-freeze | fi-ended → fi-outcome | TaskRunner.run：Engine执行已结束才冻结outcome、usage、计数与首次observedAt，交给settlement；独立于实际body是否已响应中断 |
| fi-ignore | fi-work → fi-held | Future.cancel(true)后body可能不响应中断；Lease仍entered且占用许可。表示允许发生的路径，不声称每次取消都不响应 |
| fi-body-exit | fi-held → fi-exited | body后来返回/抛错，worker finally执行Lease.exit，仅一次active--和release；迟到结果不重新推进已放弃的业务 |
| fi-normal-exit | fi-work → fi-exited | body直接返回/抛错同样走finally；正常等待方可获得结果，observe可先记录可用usage，再检查boundary，不能发布被拒绝的迟到答案 |

TaskExternalCallDeadline在嵌套同步调用中继承父boundary/许可，已abandoned父调用不能启动后续工作。10A不画观察方到“强制退出”的箭头；本地线程、DB状态和provider服务端三者不合并。对应TEST_CONFIRMED为[TaskExternalCallDeadlineTest](../../backend/src/test/java/com/agentflow/agent/engine/TaskExternalCallDeadlineTest.java)：不响应中断body在waiter退出后active=1/permits=0，真正退出后归还；取得许可尚未进入时取消、重复run、launch失败均精确归还。本轮只读取断言，未运行测试。

### 10B逐边证据（CODE_CONFIRMED；业务语义在本次布局修复中保持不变）

以下所有控制边均来自[TaskSettlementService.settle / persistence retry](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskSettlementService.java)，事务写入委托[AgentTaskLifecycleTransactionService.settleObserved](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java)，条件更新由[AgentTaskMapper](../../backend/src/main/java/com/agentflow/agent/task/repository/AgentTaskMapper.java)执行。

| Edge | source → target | 条件与事实 |
| --- | --- | --- |
| sp-begin | sp-input → sp-read | 同一冻结outcome/observedAt进入保存；每次attempt开始均回读持久任务 |
| sp-live | sp-read → sp-write | 读到非终态才尝试保存；不存在不是成功。settleObserved为REQUIRES_NEW、timeout=5，FOR UPDATE后再次检查终态/状态 |
| sp-existing | sp-read → sp-done | 已终态直接接受，不覆盖竞争方提交的CANCELLED或其他终态，不重复追加事件 |
| sp-read-fail | sp-read → sp-classify | 每次attempt的首次回读异常进入分类，图中“首次”不局限于整个流程第1次；这些失败也消耗最多3次attempt，即使没有任何写入 |
| sp-commit | sp-write → sp-done | 条件更新和关联事实同一物理事务成功。持久取消优先；正常complete/fail/timeout要求RUNNING且cancel=NULL，cancel要求RUNNING且cancel非NULL |
| sp-uncertain | sp-write → sp-readback | 写异常或条件0行不能直接认定保存失败/成功，立即再查终态；COMMIT响应丢失也走此路径 |
| sp-confirm | sp-readback → sp-done | 回读已终态则成功，即使写异常本身永久或已是末次attempt；不重复写答案或TASK_COMPLETED |
| sp-unconfirmed | sp-readback → sp-classify | 未确认终态/回读异常进入错误判断；回读明确永久异常会立即降级，不能一律当瞬态重试 |
| sp-retry | sp-classify → sp-backoff | 原始失败被识别为瞬态数据库错误且仍有attempt预算，进入100ms/500ms有界退避 |
| sp-fail | sp-classify → sp-degrade | 原始永久错误、attempt耗尽或回读永久错误关闭准入，报告TASK_SETTLEMENT_PERSIST_FAILED，DB事实保持未确认 |
| sp-again | sp-backoff → sp-read | 等待正常结束后重做先回读/保存；同一outcome与observedAt，不调用Engine/LLM/tool，不自动重执行业务 |
| sp-interrupt | sp-backoff → sp-degrade | sleep被中断立即降级，stage=BACKOFF_INTERRUPTED；恢复线程中断标志，不继续退避/重试 |

成功complete在同一事务写task终态、答案/引用/chunks及TASK_COMPLETED；任一步失败整体回滚，不能“终态成功但事件失败”。完成时间使用observedAt，updated_at有GREATEST保护。其余终态与对应事件亦由事务服务收敛。内存图节点sp-done指已确认终态，不意味着一定COMPLETED或一定采用原内存答案。

**可重试分类：** cause chain中明确SQLException SQLSTATE优先，08类、40类、55P03、57P01/57P02/57P03、53300、57014可重试；如23514明确不可重试，即使包在TransientDataAccessException中。缺少明确SQLSTATE时才使用Spring TransientDataAccessException / RecoverableDataAccessException / DataAccessResourceFailureException或SQLTransientException / SQLRecoverableException等回退判断。任意业务RuntimeException不自动可重试。最多3次attempt，首次加最多2次；100/500ms仅出现在可继续的分支。

**Unknown COMMIT细节：** 失败阶段不是READ_BACK时，先进行额外终态回读，再判断原始异常；额外回读永久失败立即降级，瞬态回读失败仍受原始失败类型与次数约束。回读不允许吞掉永久写错误并无限重试。方法进入暂时清除已有线程interrupted标志，使结算能执行，finally恢复；新发生的退避中断单独立即降级。

[TaskExecutionAdmission.failSettlement](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskExecutionAdmission.java) 将失败锁存，后续open不能重新开放；requireReady拒绝新任务准入。这里只引用[TaskRecoveryStartupCoordinator.health](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskRecoveryStartupCoordinator.java) 将非ready映射DOWN并带diagnostic的行为，不展开启动恢复。关闭准入不证明旧外部工作停止，也不替DB补造终态。

### 测试、声明与未知边界

- **TEST_CONFIRMED，仅阅读未执行：** [TaskSettlementServiceTest](../../backend/src/test/java/com/agentflow/agent/task/execution/TaskSettlementServiceTest.java) 验证同一outcome/time三次保存与100/500ms；COMMIT丢响应后一次写回读成功；竞争CANCELLED被接受；23514永久失败不重试；初始回读失败3次可有0次写；末次写异常仍回读；SQLSTATE覆盖wrapper；保留进入前中断标志与退避中断立即降级。rejectDispatch复用有界数据库保存，不重执行Engine。
- **TEST_CONFIRMED，仅阅读未执行：** [V0.2-B受控脚本](../../scripts/v02b-interruption-acceptance.py) B04分别断言单次模型超时FAILED/AGENT_LLM_TIMEOUT与task期限TIMED_OUT；B05相同观察结果重试；B06 unknown COMMIT及事件插入失败整事务回滚；B08 40001最多3次与23514一次、admission=false/health=DOWN、DB仍RUNNING。未把断言存在称作本轮通过。
- **DOC_DECLARED：** [V0.2-B验收操作说明](../../scripts/v02b-interruption-acceptance.md)描述受控执行方法；本轮没有启动该验收或新生成真实provider证据。
- **UNKNOWN：** 真实provider中断/计费停止、部署取消延迟、目标数据库可用性、生产故障下持久化结果均未运行验证。代码只证明本地机制与边界。没有扩展图11恢复，也没有添加业务代码。

### 图 10上一轮验收与停止点（历史记录）

以下为协调布线修复前的事实；原始Atlas和分层acceptance已保存在[修复前快照](history/10b-before-coordinated-layout-20260916T104642Z-pqsugypb/)，本次通过结果见下一节。

| 部分 | Artifact validation | Browser evidence | Visual review | 总状态 |
| --- | --- | --- | --- | --- |
| 10A lifecycle | passed，候选/正式交接validate与deliver均exit0，9/9、0 errors / 0 warnings | passed，原版visual-check exit0 | passed，已实际打开4张截图，correction_rounds=0 | complete（仅10A） |
| 10B workflow | failed，当前及交接复验exit1，render阶段失败，未运行完整9项/composition | not_run | not_run | incomplete |
| 图10整体 | incomplete | incomplete | incomplete | incomplete |

10A properCrossings=0、ambiguousCorridors=0、labelRouteClearanceIssues=0，最小标签/其他线净距5px，最短segment93px、interior172px，short/micro均0。viewBox=1030×630；原版静态minProjectedNodeTextPx=null，不当作字号通过证据。[补充测量](10-failure-cancel.layout-measurements.json)从实际SVG读取全部6个节点：标题10px、副标题7px，未缩小源字号。lifecycle回执未暴露requiredViewBox，记录为未提供，未伪造数值。

| 10A Viewport（light） | scrollWidth × scrollHeight | diagramWidth | 最小预计节点字号 | 结果 |
| --- | --- | --- | --- | --- |
| 1440×900 | 1440×900 | 966px | 6.56504854368932px | pass |
| 1600×1000 | 1600×1000 | 1005px | 6.830097087378641px | pass |
| 1920×1080 | 1920×1080 | 1164px | 7px | pass |
| 2048×1320 | 2048×1320 | 1488px | 7px | pass |

10A两端点额外light/dark共4张PNG均实际打开；两条独立生命周期、正常/延后退出分支、节点、标签、导航、图例、卡片无可见遮挡，最大尺寸纵向均衡。只复核默认READ/Still，未另测focus/search/passport及导出文件；不把截图通过称为这些交互功能测试。原版回执visualReview=pending保持不变，独立复核另存。

10A初版2个布局诊断，依次只修fi-ignore的straight和fi-body-exit的labelDy=24，错误2→1→0；[初版历史](history/10a-before-body-straight/)及[第二版历史](history/10a-before-exit-label/)原件保留。这两次是交付前布局修复，截图视觉修复轮数为0。

10B[初版回执](history/10b-before-retry-straight/10-settlement.candidate.validation.json)有4项：sp-confirm与sp-retry proper crossing；sp-again与sp-unconfirmed共享54px竖向通道；sp-existing与sp-uncertain共享184.4px横向通道；sp-retry与sp-uncertain共享50px竖向通道。定向修复1只设sp-retry为straight，[回执](history/10b-straight-conflict/10-settlement.candidate.validation.json)为workflow/route-preset-conflict，bottom→top，points=[[310.8,280],[310.8,338]]，supportedFixes=[]。修复2撤销straight，仅对同一subject指定left/left以避开已诊断右侧通道；仍无法生成可行路线。

**上一轮原版诊断：** workflow/explicit-pin-conflict，subject=sp-retry（sp-classify→sp-backoff），invariant=`readable route feasibility with authored endpoint sides`；冲突字段`/edges/8/fromSide=left`、`/edges/8/toSide=left`；sourceAnchor=[246.8,265]、targetAnchor=[246.8,403]。尝试9类候选：facing-straight、horizontal-then-vertical、vertical-then-horizontal、lane-gap-corridor、column-gap-corridor、outside-left、outside-right、top-corridor、bottom-corridor。supportedFixes为空，未生成被接受的最终route points；CLI未暴露具体失败predicate，不能仅凭端点断定是某一标签/节点碰撞。

空supportedFixes诊断后按Skill允许条件只读workflow compiler，确认可行性还检查标签、其他节点/标签、scene及frame等，并未获得可复现的单predicate结论，未修改或instrument Skill。两轮定向修复均在composition之前失败；原4项没有被重新完整评估，不能将回执4→1→1当成几何改善。按[Archify SKILL.md](../../.agents/skills/archify/SKILL.md)的“If two consecutive rounds do not improve that best count, stop and report the unresolved diagnostics truthfully.”停止本轮B布局，保留最新candidate及全部历史，不继续轮换preset、不deliver失败候选。

本轮只新增图10产物并更新Atlas，原图01–09与其他工作树改动保留。未修改业务代码、Skill、验证标准或手改生成HTML；未用裁切、overflow:hidden或缩小字体过关。未开始图11，未提交推送。


### 图 10B协调布线修复与最终验收（2026-09-16）

本轮基线为main@7e1e8bb7c2ae55b44726b410fb2546cb64d4a401的dirty working tree。先读用户修复包[REPAIR_NOTES](history/10b-before-coordinated-layout-20260916T104642Z-pqsugypb/REPAIR_NOTES.md)，把它作为待验候选。运行安全脚本dry-run exit0，确认候选基线与modeled workflow compiler Git blob `81ba79a14895072643ea680b6eb304c45b0b8e29`均匹配；再整组apply exit0。[dry-run回执](10-settlement.package-dry-run.json)、[apply回执](10-settlement.package-apply.json)记录实际输出与退出码。没有另外应用patch，没有修改Skill或10A。

旧10B候选及相邻JSON回执由应用脚本先复制到[唯一history目录](history/10b-before-coordinated-layout-20260916T104642Z-pqsugypb/)，旧Atlas及整体acceptance也存入该目录。包内独立报告保持其NOT_RUN声明，并以[预测报告原件](history/10b-before-coordinated-layout-20260916T104642Z-pqsugypb/10-settlement.independent-check.json)保存；它不是本轮原版验收。

**语义保留：** 8个节点、12条关系、3泳道、3张cards、全部文案、mainPath与semanticChecks保持不变。节点lane/col/type/width/height不变，只按整包更改连线几何。正常退避返回sp-again保持同一观察结果，只重试数据库保存，不重执行Engine/provider；sp-interrupt仍表示退避中断后降级。代码证据和四种取消/保存事实的区分沿用上节，本轮没有新的业务或外部服务验收。

**原版Artifact validation：passed。** 候选validate、layout-json、deliver及交接前正式源validate均实际exit0；完整9/9 artifact checks，composition 0 errors / 0 warnings。properCrossings=0、ambiguousCorridors=0、labelRouteClearanceIssues=0、containerBorderRuns=0，标签对其他关系最小净距24px。最短端点段10px，interior24px、micro0。原版记录shortSegmentCount=1（一个10px端点段），shortInteriorSegmentCount=0；端点超过8px硬门槛，未被列为error/warning。maxBends=6、maxStretch=3.292，各有2条超过建议值。保留这些实际指标，不称所有路线最短或完全没有短段。

**原版布局对比：** [比较记录](10-settlement.layout-comparison.json)逐一对比columns、8个节点矩形、12条最终路径和12个标签位置；容差1e-8，无差异。原版label x/y是标签定位点，对照独立报告的point，而非其mask左上角。columns=[112,310.8,495.20000000000005,679.6,799.6,919.6]，viewBox=requiredViewBox=[994,534]。[本次SVG测量](10-settlement.svg-measurements.json)确认lane y=52/178/304、height106、gap20，节点top=86/212/338，均128×68；全部8个节点标题11px、副标题8px，未缩小字体。

- sp-again最终路径：[(310.8,406),(310.8,416),(20,416),(20,20),(286.8,20),(286.8,62),(310.8,62),(310.8,86)]；标签“同一观察结果”定位点(165.4,406)。
- sp-interrupt最终路径：[(246.8,372),(34,372),(34,310),(112,310),(112,280)]；“退避被中断”定位点(73,300)。
- sp-existing从read顶部(310.8,86)经y32到done顶部；与sp-again在x310.8、y62..86共用24px端点段，确实存在，未隐藏。原版非共享端点crossing/corridor为0不等于图中没有任何共线。

候选通过后原字节复制为正式源，再deliver。随后只对本次成功生成的HTML运行visual-check，原版回执exit0/status=pass；未重绘或手改HTML。

| 10B Viewport（light） | scrollWidth × scrollHeight | diagramWidth | 最小预计节点字号 | containment/readability |
| --- | --- | --- | --- | --- |
| 1440×900 | 1440×900 | 1132px | 8px | pass |
| 1600×1000 | 1600×1000 | 1176px | 8px | pass |
| 1920×1080 | 1920×1080 | 1325px | 8px | pass |
| 2048×1320 | 2048×1320 | 1726px | 8px | pass |

**Browser evidence：passed；visual review：passed，correction_rounds=0。** 两端点1440×900与2048×1320另有light/dark四张PNG，全部实际打开复核。正常退避从底部沿紫色虚线外圈回读，退避中断从左端口沿红色虚线到关闭准入，路径、标签和方向可区分。read顶部共同端点段可沿紫色入箭头与灰色出线追踪，不表示绕过read直接保存。节点、关系标签、图例与卡片无可见遮挡；图例位于回环下方，导航不压主图；大屏纵向占用均衡。复核范围为默认READ/Still，未另测focus/search/passport或导出文件。原版visualReview=pending保持不变，独立视觉判断另存。

| 部分 | Artifact | Browser | Visual review | 当前状态 |
| --- | --- | --- | --- | --- |
| 10A | passed（原回执不变） | passed（原回执不变） | passed（原回执不变） | complete |
| 10B | passed，9/9、0 errors / 0 warnings | passed | passed，0轮视觉修复 | complete |
| 图10整体 | passed | passed | passed | complete |

全部真实命令与退出码见[本轮命令记录](10-settlement.repair-commands.json)及[10B acceptance](10-settlement.acceptance.json)，[整体状态](10-acceptance.json)已更新。specification SHA-256=`7da010503c5b9eee0f34cb01601c6178f29af9c06ab1a07b23d1e0512154fd6b`（8109 bytes）；artifact SHA-256=`c8fcaf357f648c7dbeadeb55756ba8f1640ebbee20a85487e65c0a7c6263828d`（811362 bytes），采用原版deliver回执值，与browser绑定一致。10A全部原文件及历史记录保持不变；未开始图11，未提交推送。


## 图 11 施工前工作记录（2026-09-16）

本图只回答受控单机重启的进程锁、准入、遗留任务识别与持久收尾，使用workflow v2/showcase。基线main@7e1e8bb7c2ae55b44726b410fb2546cb64d4a401，dirty working tree；图01–10及其未提交修复保留，不开始图12。

- CODE_CONFIRMED：TaskRecoveryConfiguration先取得绝对稳定路径FileLock再Flyway；DISABLED也需要锁。进程静态持有锁，无destroy/close；ContextClosed只关闭准入，锁到JVM退出由OS释放。
- CODE_CONFIRMED：StartupCoordinator只运行一次，起始准入关闭；DISABLED只查有无QUEUED/RUNNING，存在则保持关闭。CONTROLLED_SINGLE_HOST按id游标批次读候选，每项REQUIRES_NEW收尾，末次清零复查、requireHeld后open。异常中止扫描并保持DOWN，不重新dispatch。
- CODE_CONFIRMED：recover行锁重查、校验不变量、汇总已记录usage、结束RUNNING step/tool、条件终态写与metadata/event为同一物理事务。QUEUED→FAILED/dispatch lost，RUNNING→FAILED/interrupted，持久取消优先CANCELLED；既有终态不动。
- CODE_CONFIRMED：cold-cutover脚本核对指定Java PID、SIGTERM后确认不存在或zombie才记录退出并exec新Java；超时/观测异常拒绝替换。操作者负责全域旧JVM清点，锁不提供跨主机fencing。
- TEST_CONFIRMED（读取断言，未运行）：RecoveryPostgresIntegrationTest、test_task_cold_cutover.py和v02a-restart-acceptance.py覆盖原子回滚、竞争取消/幂等、未知usage、锁互斥、disabled gate、旧JVM退出、无外部重发。
- DOC_DECLARED / UNKNOWN：受控说明不是本轮真实provider或数据库运行证明；部署锁路径一致性、全域旧执行器已退出、远端计算已停止均未验证。

## 图 11 Evidence index

- **Diagram / Question:** Restart Recovery and Controlled Cutover；新JVM怎样在单主机冷切换前提下识别遗留任务、收尾并开放准入？
- **Type:** `workflow` / schema v2 / showcase；9节点、11条具名关系、3卡片。只有本图，不增加lifecycle子图或图12。
- **Primary path:** JVM启动 → 进程锁及迁移 → 模式分流 → 受控分页扫描并逐项原子收尾 → 候选清零与锁有效确认 → 开放准入。DISABLED单独只读分支；旧JVM退出是外部操作前提，不是新JVM扫描时间戳得出的结论。
- **Key nodes:** rr-lock汇总先锁后迁移；rr-controlled汇总内部分页循环和每项独立事务；rr-final是两分支汇合，DISABLED已在前一步查无遗留，这里只再次requireHeld，CONTROLLED才额外hasCandidates清零复查。未把这些汇总节点画成新增服务或DB状态。
- **JSON:** [当前候选](11-restart-recovery.candidate.workflow.json)。**没有正式JSON或HTML。**
- **Validation:** [原版当前失败回执](11-restart-recovery.candidate.validation.json)、[上一轮layout诊断](11-restart-recovery.layout.json)、[只读compiler调用栈](11-restart-recovery.compiler-diagnostic.json)、[分层acceptance](11-restart-recovery.acceptance.json)。调用栈不是artifact验收；失败layout未提供最终坐标。
- **Status:** artifact failed；deliver/browser/visual review not_run；整体incomplete。节点、关系、mainPath和semanticChecks均保留。

### 模式、进程锁与冷切换证据（CODE_CONFIRMED）

[TaskRecoveryProperties](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskRecoveryProperties.java)只接受DISABLED、CONTROLLED_SINGLE_HOST，默认DISABLED；batch默认100、合法1–1000。每种模式都要求非空lockPath；[application.yml](../../backend/src/main/resources/application.yml)从AGENTFLOW_TASK_RECOVERY_MODE、AGENTFLOW_TASK_RECOVERY_LOCK_PATH读取，路径默认空，不是自动选一个安全路径。目标进程环境覆盖未检查。

[TaskExecutionProcessLock.acquire](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskExecutionProcessLock.java)要求绝对路径，创建父目录后realPath规范化；先拒绝本JVM已持有的路径或同文件别名，避免第二个descriptor关闭影响POSIX锁。拒绝fileStore类型含nfs/smb/cifs/fuse，NOFOLLOW_LINKS打开、tryLock取独占锁。空锁或异常包装TASK_EXECUTION_NOT_READY并导致启动失败；这不证明所有网络文件系统都能识别，也不提供跨主机fencing。

锁由静态HELD_UNTIL_PROCESS_EXIT引用，Bean的destroyMethod为空，没有Context关闭释放路径；OS在JVM退出时释放。ContextClosedEvent使[TaskExecutionAdmission](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskExecutionAdmission.java)进入SHUTTING_DOWN，不允许open，但不等于JVM退出，也不等于远端provider停工。同一主机、同一DB执行域必须约定同一稳定本地锁文件；不同文件/不同主机不在本锁互斥范围。旧版不遵守锁协议的JVM无法靠新锁排除。

[task-cold-cutover.py](../../scripts/task-cold-cutover.py)要求显式old-pid（>1且非自身）、绝对lock-path、新命令直接java（不允许shell/Maven包装），ps comm必须是java。以exclusive create保留切换记录，发SIGTERM；ps明确无PID或zombie才证明该指定进程已退出。默认60秒后仍存活或ps观测失败均拒绝替换，不自动SIGKILL、不用取得锁绕过退出前提。记录oldJvmExited/时间，设置受控模式及路径，再os.execvp新Java。操作者必须清点该执行域全部旧执行器；脚本不自动发现其他JVM，record也不是新Coordinator读取校验的凭证。

### 逐边调用与失败边界（CODE_CONFIRMED）

| Edge | source → target | 方法、guard、事务/状态影响 |
| --- | --- | --- |
| rr-start-lock | rr-start → rr-lock | [TaskRecoveryConfiguration.taskExecutionProcessLock / taskRecoveryMigrationStrategy](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskRecoveryConfiguration.java)：准入构造即关闭，锁Bean依赖确保Flyway写schema前已经取锁并requireHeld |
| rr-lock-mode | rr-lock → rr-mode | 迁移成功，Spring启动到[TaskRecoveryStartupCoordinator.run](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskRecoveryStartupCoordinator.java)的HIGHEST_PRECEDENCE ApplicationRunner；AtomicBoolean禁止重入，close(RECOVERY_STARTING)、生成run UUID、requireHeld。不是后台定时恢复 |
| rr-lock-halt | rr-lock → rr-halt | 锁失败拒绝Bean初始化；Flyway失败close(MIGRATION_FAILED)并重新抛出，阻止启动。此终端不承诺Actuator仍可响应，与运行中DOWN分开 |
| rr-disabled-mode | rr-mode → rr-disabled | mode=DISABLED调用hasCandidates，只查全表QUEUED/RUNNING存在性，不写任务、step/tool或事件 |
| rr-controlled-mode | rr-mode → rr-controlled | CONTROLLED_SINGLE_HOST进入cursor=0的分页循环，selectCandidateIds(cursor,batchSize)，按id升序，逐个recover(id,runId)，返回后cursor=id |
| rr-disabled-clear | rr-disabled → rr-final | DISABLED hasCandidates=false进入共同的最后持锁检查；不会执行受控分支的第二次hasCandidates。该汇总节点标题“清零与持锁确认”不能解释为DISABLED也恢复任务 |
| rr-disabled-closed | rr-disabled → rr-closed | 有遗留close(DISABLED_WITH_LEGACY_TASKS)并return；初始读取异常也catch并保持关闭，输出stage/reason诊断，不调用事务收尾 |
| rr-scan-done | rr-controlled → rr-final | 分页直到空批次（最初无候选也可），再FINAL_CANDIDATE_CHECK hasCandidates；不是全批次一个事务，也没有按年龄/心跳认定任务过期 |
| rr-scan-fail | rr-controlled → rr-closed | 任一候选读取、单项事务、usage或不变量异常停止扫描；单项事务回滚，先前已提交项保留；日志包括stage、taskId、runId、reason。无本进程自动重扫/业务重执行 |
| rr-ready-edge | rr-final → rr-ready | 受控模式确认无候选（DISABLED已先查过），再次requireHeld后admission.open；health()映射UP。仅开放正常新任务入口，不重新派发遗留task |
| rr-final-fail | rr-final → rr-closed | 受控最终仍有遗留，或最后requireHeld/open失败被catch；close并health DOWN。开放时仍受shuttingDown/settlementFailed锁存约束 |

[TaskRecoveryMapper](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskRecoveryMapper.java)查询条件只有status IN ('QUEUED','RUNNING')及分页id>cursor，无“超过若干分钟即安全接管”的时间判断，无租约所有者或跨主机锁。准入在扫描前关闭以阻止本进程写入；正确性还依赖操作者确认旧执行器退出。

READY只是task准入与该HealthIndicator状态，不等于所有健康组件UP。requireReady调用点包括[AgentTaskRestService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskRestService.java)、[AgentTaskApplicationService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskApplicationService.java)、[CreationTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskCreationTransactionService.java)、[LifecycleTransactionService](../../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskLifecycleTransactionService.java)、[BoundedTaskDispatcher](../../backend/src/main/java/com/agentflow/agent/task/dispatch/BoundedTaskDispatcher.java)、[TaskRunner](../../backend/src/main/java/com/agentflow/agent/task/execution/TaskRunner.java)。恢复Coordinator/TransactionService无Dispatcher、Engine或provider依赖。

### 单任务原子收尾（rr-controlled内部，CODE_CONFIRMED）

[TaskRecoveryTransactionService.recover](../../backend/src/main/java/com/agentflow/agent/task/recovery/TaskRecoveryTransactionService.java)为REQUIRES_NEW、timeout30。锁定task行后，缺失或已终态返回false不改写；校验QUEUED没有执行证据和计数，非终态无旧recovery metadata/终态发布，拒绝PENDING tool；校验持久usage与已记录LLM事实一致。异常不变量不能通过“强制FAILED”掩盖。

| 原状态/条件 | task终态及原因 | 元数据与保留边界 |
| --- | --- | --- |
| QUEUED，无取消 | FAILED / SYSTEM_ERROR / TASK_RESTART_DISPATCH_LOST | executionOutcome=NOT_STARTED，record/counterCompleteness=COMPLETE；不添加TASK_STARTED，不重投 |
| RUNNING，无取消 | FAILED / SYSTEM_ERROR / TASK_RESTART_INTERRUPTED | executionOutcome=UNKNOWN，record/counterCompleteness=UNCONFIRMED；即使task期限已过也不编造TIMED_OUT |
| 持久cancel_requested_at非空 | CANCELLED / USER_CANCELLED，公开errorCode为空 | metadata仍记之前状态的restart reason；QUEUED取消异常另标queuedCancellationAnomaly，不放宽生产schema |
| 已终态 | 不变 | 幂等跳过，原答案、事件及调用事实不重写 |

聚合LLM日志按记录id去重，校验task归属、token非负/总数及质量，UNKNOWN不得带数值；加法溢出拒绝。只汇总已持久的EXACT/ESTIMATED/MIXED记录，不猜未记录调用的usage。task token_usage_quality统一UNKNOWN，metadata分别保存recordedUsage和previousTaskUsage；计数与execution_snapshot不重算/重写。

同一物理事务结束RUNNING step/tool为FAILED/TASK_RESTART_INTERRUPTED，条件写task状态/version/cancel值、清phase/答案/引用、写completed_at及recovery_metadata，最后通过原[TaskEventAppender](../../backend/src/main/java/com/agentflow/agent/task/service/TaskEventAppender.java)追加唯一TASK_FAILED或TASK_CANCELLED。step/tool更新、task条件0行、事件序号或插入任一点失败都回滚本项；已完成step/tool、LLM/RAG记录保留。metadata recoveredAt是恢复观察时间，不是实际执行耗时。[V22 migration](../../backend/src/main/resources/db/migration/V22__add_task_recovery_metadata.sql)只为FAILED+TASK_RESTART_INTERRUPTED允许未知latency，其余生命周期约束保留。

这与图10B运行期有界保存重试不同：本Coordinator遇到异常关闭准入，不在当前启动过程内重试或自动续跑。COMMIT已发生但响应丢失也可能留下“任务已终态、准入DOWN”；后续新JVM重扫跳过已终态，不重复事件。单task原子，不是全域扫描原子。

### 测试、声明及未知范围

**TEST_CONFIRMED，仅检查断言，本轮未执行：**

- [TaskRecoveryPostgresIntegrationTest](../../backend/src/test/java/com/agentflow/agent/task/recovery/TaskRecoveryPostgresIntegrationTest.java)：分页、QUEUED/RUNNING原因差异、保留快照/计数/已成功调用、UNKNOWN usage与未知latency；step/tool/task/sequence/event/zero-row六处故障全部回滚；校验矛盾事实拒绝写入；行锁等待取消提交后CANCELLED且重复恢复无第二终态事件。
- [test_task_cold_cutover.py](../../scripts/test_task_cold_cutover.py)：SIGTERM与不存在/zombie才exec，观测失败/存活超时拒绝，record不伪造退出。mock进程观测断言不等于本轮实际停止旧JVM。
- [v02a-restart-acceptance.py](../../scripts/v02a-restart-acceptance.py)：A01–A08覆盖QUEUED、RUNNING、迟到/工具/最终LLM日志、取消和过期task；A09既有终态不变；A10各写点回滚；A11批间退出及COMMIT丢响应后重入保持已提交事实；A12两种模式第二JVM锁冲突；A13 DISABLED遗留门禁；A14矛盾事实/查询失败关闭；A15只读GET/Trace/SSE与幂等请求；A16旧快照/混合usage；A17真实旧版JVM冷切换。Context关闭不释放锁在本轮属于生产代码证据，不把它归入未检查到的脚本断言。检查external counts和Runner receipts以区分“没发外部请求”和“没错误重投任务”。这些是受控场景断言，不是本轮17/17新通过。

**DOC_DECLARED：** [受控重启验收说明](../../scripts/v02a-restart-acceptance.md)说明独立HTTP夹具、真实本地JVM/PostgreSQL及固定前锁协议旧版revision；不证明生产冷切换、真实provider终止或计费。**UNKNOWN：** 当前部署锁路径/文件系统、全域旧JVM清点、远端工作状态及生产故障恢复结果。新代码只取得自己的锁，不能验证前锁协议旧JVM已经退出。

### 图11布局诊断及实际停止点

[初版](history/11-before-ready-straight/11-restart-recovery.candidate.validation.json)validate exit1：rr-ready-edge与rr-scan-fail在x982、y196..246共享50px通道。定向修复1仅给rr-ready-edge增加route=straight；[回执](history/11-before-disabled-channel/11-restart-recovery.candidate.validation.json)exit1，原冲突不再报告，但rr-disabled-clear与rr-scan-fail在y312、x527.2..607.2共享80px通道。诊断数1→1，没有新的最小值。

定向修复2仅给rr-disabled-clear增加channelY=322，尝试分开回执标明的水平通道，未改任何语义或其他节点/关系。原版当前validate exit1，code=`internal/unclassified`，stage=render，errorName=TypeError，message=`Cannot read properties of undefined (reading 'length')`，subject仅给出当前输入路径，evidence未给出具体edge/坐标，supportedFixes=[]。没有原版最终路径或viewBox，不作猜测。

在两轮定向失败及unsupported内部诊断后，仅只读检查原版compiler并直接调用export的compileWorkflow保存原始异常栈，未修改/插桩Skill。可复现调用链：validateReadablePinnedGeometry → pathFor → readableAutomaticRoute → readableAutomaticVia → classifyFailedAutomaticCandidatePins → candidateLabelRect → workflowEdgeLabelPoint:3620。源码中readableAutomaticCandidateSet的rawCandidates仅有family/via；points只在映射到candidates时生成。失败分类函数却对rawCandidates解构points，导致后续读取undefined.length。这解释当前内部异常，但不能证明原候选的剩余路由/标签可行性，不能将其当作已通过布局。

按[Archify SKILL.md](../../.agents/skills/archify/SKILL.md)“If two consecutive rounds do not improve that best count, stop and report the unresolved diagnostics truthfully.”停止继续布局。两轮均未得到低于1的有效诊断数；内部异常先于完整artifact/composition，不能称底层冲突消失。最新候选、所有历史及实际退出码已保留；artifact failed、deliver/browser/visual review not_run，整体incomplete。未生成正式JSON/HTML、未降低quality、未删semanticChecks、未修改Skill或业务代码，未开始图12，未提交推送。
