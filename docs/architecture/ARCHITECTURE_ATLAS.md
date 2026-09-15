# AgentFlow Hub Architecture Atlas

## Scope and baseline

已依次完成 **01 · System Context** 与 **02 · Backend Runtime Architecture**，均为 `architecture`。本轮施工 **03 · End-to-End Task Workflow**（workflow v2），目前因首屏溢出及修正候选的路由约束未通过而**未完成**。图 04–15 与最终独立 Architecture Audit 尚未施工。

- 图 01 交付日期：2026-09-14；图 02 于 2026-09-14 开始检查、2026-09-15 完成交付（Asia/Shanghai）。
- 本地分支：`main`；HEAD：`ba04adc5308222635e6eb1a86a0981da8ede408c`（`docs: add Archify architecture atlas construction guide`）。未 fetch、未改变 Git 基线；此处指施工时本地 main。
- 工作树：**dirty**。施工前已有 V0.3 配置/评估/episode 相关改动、`application-dev.yml`、规格文档和未跟踪的 `.agents/` 等内容。本图按实际文件检查；没有把这些改动视为 HEAD 已提交行为。
- Archify：仓库本地 `.agents/skills/archify/SKILL.md`，metadata version `2.17`；`showcase` 质量，中文 Viewer，静态默认视图。
- 未运行外部业务 runtime：本轮未启动 Spring Boot、PostgreSQL、Redis、Qdrant、Chat、DashScope，也未执行付费请求或真实服务验收。图展示代码/配置支持的逻辑关系，不证明部署中的连通性、生产拓扑或 provider 行为。
- 本轮仅新增图 03 的 source、HTML、修正候选与证据侧文件并更新本 Atlas；图 01/02 产物及既有业务代码改动保留。图 03 的通过项与失败项分别记录。

## Diagram index

| ID | Diagram | Type | Question | Status |
| --- | --- | --- | --- | --- |
| 01 | System Context | architecture | 用户、浏览器前端、后端与哪些存储和外部服务交互？ | 完成：validate / deliver / browser / visual review 通过 |
| 02 | Backend Runtime Architecture | architecture | Spring Boot 后端内部的主要运行时模块如何分工和连接？ | 完成：validate / deliver / browser / visual review 通过 |
| 03 | End-to-End Task Workflow | workflow v2 | 提交 task 后，创建到最终答案如何推进，关键 gate 在哪里？ | **未完成**：首版 validate / deliver 通过；browser / visual review 失败；修正候选路由未通过 |

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

- **Diagram:** 03 · End-to-End Task Workflow（**未完成**）
- **Question:** 用户提交一个 Agent task 后，从创建到最终答案，完整 happy path 如何推进，关键 gate 在哪里？
- **Type:** `workflow` / schema v2 / showcase。
- **Primary path:** submit → auth/owner/admission/idempotency → config/snapshot → task + event 提交 → after-commit → executor claim → 前置 RAG → decision/tool loop → 独立 final → settlement → Browser 终态核对。
- **Key nodes:** 首版 12 个节点，6 条职责泳道；工具为支路，11 个主路径节点。修正候选将同一创建事务内的 snapshot/persistence 合并为 11 个节点、3 条泳道，尚未通过路由校验。
- **Key evidence:** 下列逐边生产代码索引及工作记录。workflow 工具不支持 architecture 的 repository-evidence 核验；源码语义由本次读取确认，不声称工具自动验证。
- **Unknowns:** 本轮未执行后端/数据库/provider/浏览器业务 E2E；成功率、真实环境 Bean/配置、远程取消与完整恢复策略未知。本文 HTML 的 Chrome 检查只测制图产物。
- **JSON:** [首版 source](03-task-e2e.workflow.json)，与首版 HTML 回执字节一致；[未交付的修正候选](03-task-e2e.candidate.workflow.json)。
- **HTML:** [首版 HTML](03-task-e2e.html)，仅供查看；存在桌面纵向溢出，**不是已完成交付**。
- **Validation:** [首版 validate](03-task-e2e.validation.json) 9/9 / 0 errors / 0 warnings；[首版 deliver](03-task-e2e.delivery.json) 成功；[修正候选 validate](03-task-e2e.candidate.validation.json) 非零失败。
- **Visual check:** [浏览器回执](03-task-e2e.visual-check.json) `fail`；[截图索引](03-task-e2e.visual-check.html)；[图像复核](03-task-e2e.visual-review.json) `failed` / `correction_rounds: 1`；[未完成状态记录](03-task-e2e.incomplete.json)。

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

修正候选的 `wf-gate-persist` 合并首版 `wf-gate-snapshot` 与 `wf-snapshot-persist` 的职责；其余边沿用相同事实，候选不是已验证的新实现。泳道为职责聚合，实际线程/事务边界以节点、关系和本表为准。

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

## 图 03 未完成记录

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

### 完成标准状态

- [x] 单一问题、workflow v2、证据先行；真实主链路、gate、边界与未知项已记录。
- [x] 首版 JSON / HTML 保存，首版 validate / deliver 通过，逐边索引补齐。
- [ ] 桌面首屏 containment 与视觉复核通过（当前失败）。
- [ ] 紧凑修正候选 validate / deliver 通过（路由未通过，未交付）。
- [ ] 图 03 完成。
- [x] **未开始图 04–15，未生成最终独立 ARCHITECTURE_AUDIT.md。**
