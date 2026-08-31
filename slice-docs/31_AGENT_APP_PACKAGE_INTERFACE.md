# V30 Agent App 根资源创建与当前 Owner 分页接口包说明

> 状态：本地实现与验收完成。V30 只建立 `agent_app` 根资源，并提供当前认证用户的创建与分页列表；
> JDK 21 聚焦/完整测试、隔离 PostgreSQL 18.4 空库迁移、登录后真实 HTTP 与数据库验收均已执行。

## 1. 切片目标

V30 是 M4 的第一个 Agent 根资源切片，提供：

```http
POST /api/v1/agents
GET  /api/v1/agents?page=1&pageSize=20
```

完成后只能声明：

> 当前认证用户能够在 PostgreSQL 中创建并分页查看自己拥有的 Agent 配置元数据。

`Agent` 在本切片中仍是配置记录，不是已经能调用模型、检索知识库、获得工具或执行任务的运行时。

## 2. 依据与范围冻结

当前实现顺序的直接依据是：

- M3 已完成 `order_query`、`payment_log_query`、`report_generate` 三个内置工具和调用日志边界；
- 路线图推荐顺序在 tool runtime/内置工具之后进入 agent app CRUD；
- M4 的首张表和首项功能分别是 `agent_app` 与创建/配置 Agent；
- V0.1 API 列出 `POST /agents`、`GET /agents`，但示例没有单独冻结默认值与上限；
- V0.1 数据表边界允许 Prompt 版本、知识库绑定和工具绑定延后。

V30 将 API 示例中的 `0.2/0.8/6/4/8000/120` 提升为本项目 V0.1 创建默认值。这是 V30 的新契约决定，
不是把示例误述成此前已经存在的规范语义。

## 3. 数据库契约

迁移文件：

```text
backend/src/main/resources/db/migration/V16__create_agent_app.sql
```

### 3.1 表边界

V16 只创建 `agent_app`：

| 字段 | 约束或初始值 |
| --- | --- |
| `id` | `BIGINT` 主键，由 MyBatis-Plus 在插入前生成 |
| `user_id` | 非空，外键到 `app_user.id` |
| `name` | `VARCHAR(128)`，非空且不能全空白 |
| `description` | 可空，最多 4000 字符 |
| `system_prompt` | 非空、不能全空白、最多 20000 字符 |
| `model_provider` | `VARCHAR(64)`，非空且不能全空白 |
| `model_name` | `VARCHAR(128)`，非空且不能全空白 |
| `temperature` | `NUMERIC(4,3)`，`0–2`，默认 `0.2` |
| `top_p` | `NUMERIC(4,3)`，`(0,1]`，默认 `0.8` |
| `max_steps` | `1–20`，默认 `6` |
| `max_tool_calls` | `0–20` 且不超过 `max_steps`，默认 `4` |
| `max_tokens` | `256–100000`，默认 `8000` |
| `timeout_seconds` | `1–600`，默认 `120` |
| `status` | `ACTIVE / DISABLED`，默认 `ACTIVE` |
| `config` | 非空 JSON object，默认 `{}` |
| `created_at/updated_at` | 非空，默认数据库当前时间；应用创建也写同一响应时间 |
| `deleted_at` | 可空软删除标记 |

`temperature/topP` 最多接受 3 位小数，这是 `NUMERIC(4,3)` 的表示精度约束；V30 选择在插入前拒绝更高精度，
而不是让 PostgreSQL 静默舍入后导致创建响应与实际存储值不一致。

### 3.2 暂不加入 `current_prompt_version_id`

`agent_prompt_version` 尚未创建。V16 不保存无法通过外键验证的 Prompt 版本 ID；未来创建 Prompt 版本表时，
用新的 Flyway migration 同时增加列和外键。已经应用的 V16 不回写。

### 3.3 索引与重名

当前 owner 列表索引固定为：

```sql
CREATE INDEX idx_agent_app_user_created
    ON agent_app (user_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
```

V30 不建立 `(user_id, name)` 唯一约束，同一用户可以创建重名 Agent。

## 4. 创建接口契约

### 4.1 请求 allowlist

```json
{
  "name": "支付问题诊断助手",
  "description": "用于分析支付失败",
  "systemPrompt": "你是企业内部研发运营助手。",
  "modelProvider": "openai-compatible",
  "modelName": "kimi-k2",
  "temperature": 0.2,
  "topP": 0.8,
  "maxSteps": 6,
  "maxToolCalls": 4,
  "maxTokens": 8000,
  "timeoutSeconds": 120
}
```

| 字段 | 规则 | 缺省值 |
| --- | --- | --- |
| `name` | 必填、非空白、最多 128；持久化前去除首尾空白 | 无 |
| `description` | 可选、最多 4000；首尾去空白后为空则存 `null` | `null` |
| `systemPrompt` | 必填、非空白、最多 20000；内容按请求原样保存 | 无 |
| `modelProvider` | 必填；V0.1 只接受精确值 `openai-compatible` | 无 |
| `modelName` | 必填、非空白、最多 128；持久化前去除首尾空白 | 无 |
| `temperature` | `0–2`，最多 3 位小数 | `0.2` |
| `topP` | `(0,1]`，最多 3 位小数 | `0.8` |
| `maxSteps` | `1–20` | `6` |
| `maxToolCalls` | `0–20` 且不超过有效 `maxSteps` | `4` |
| `maxTokens` | `256–100000` | `8000` |
| `timeoutSeconds` | `1–600` | `120` |

当只提供 `maxSteps=1` 而省略 `maxToolCalls` 时，有效默认 `maxToolCalls=4` 会违反交叉约束，因此返回 400；
不会为了迎合较小的 `maxSteps` 而偷偷把工具预算改成 1。

### 4.2 严格字段与错误分类

`CreateAgentAppRequestDeserializer` 采用本请求局部 allowlist。以下字段以及任何未识别字段均拒绝：

```text
id
userId
status
config
currentPromptVersionId
createdAt
updatedAt
deletedAt
knowledgeBaseIds
toolIds
```

- 多余字段、错误 JSON 类型、非 object body：`400 / COMMON_REQUEST_BODY_INVALID`；
- 已识别字段的空白、超长、精度、数值范围、provider 或交叉约束错误：
  `400 / COMMON_PARAM_INVALID`；
- 在任何插入发生前完成上述校验。

创建时 `user_id` 只来自 `AuthenticatedUser.id`，`status` 固定 `ACTIVE`，`config` 不进入 insert model，
由 PostgreSQL 默认值生成空 object。客户端没有任何 owner/status/config 写入路径。

### 4.3 创建响应

成功返回 `201 Created` 和 `AgentAppResponse`：

```text
id (字符串)
name
description
systemPrompt
modelProvider
modelName
temperature
topP
maxSteps
maxToolCalls
maxTokens
timeoutSeconds
status
createdAt
updatedAt
```

不返回 `userId`、`config`、`currentPromptVersionId` 或 `deletedAt`。BIGINT ID 延续项目约定输出为字符串。

## 5. 当前 Owner 分页列表

Mapper 中的 SQL 固定为：

```sql
FROM agent_app
WHERE user_id = #{currentUser.id}
  AND deleted_at IS NULL
ORDER BY created_at DESC, id DESC
```

owner 与 live 谓词在同一 SQL 中，不做全表读取后过滤。`DISABLED` 不是删除，列表不增加 status 条件，
因此仍对其 owner 可见。

列表只投影并返回 `AgentAppSummaryResponse`：

```text
id
name
description
modelProvider
modelName
status
createdAt
updatedAt
```

Mapper 的 SELECT 本身不读取 `system_prompt`、执行预算、owner、config 或删除字段；响应也不伪造尚不存在的
知识库数量、工具数量或绑定状态。

分页沿用共享 `PageRequest/PageResult`：默认 `page=1/pageSize=20`，`pageSize` 最大 100。

## 6. 实现边界

V30 新增：

```text
com.agentflow.agent.model.AgentApp
com.agentflow.agent.repository.AgentAppMapper
com.agentflow.agent.service.AgentAppService
com.agentflow.agent.controller.AgentAppController
com.agentflow.agent.dto.CreateAgentAppRequest
com.agentflow.agent.dto.CreateAgentAppRequestDeserializer
com.agentflow.agent.dto.AgentAppResponse
com.agentflow.agent.dto.AgentAppSummaryResponse
```

创建和列表只依赖 `AgentAppMapper`。本切片不注入或调用 `ToolRuntime`、LLM Gateway、RAG Service、
AgentEngine、task/step 或任何日志服务。

## 7. 验收矩阵

### 7.1 自动化测试

| 测试 | 证明范围 |
| --- | --- |
| `V16AgentAppMigrationContractTest` | V16 表、字段、约束、默认值、索引与未来表排除 |
| `AgentAppMapperTest` | 摘要投影与 owner/live/排序 SQL 固定在 Mapper |
| `AgentAppServiceTest` | principal owner、默认/显式配置、插入前失败、DISABLED 列表映射 |
| `AgentAppControllerTest` | 201、严格 JSON、400 分类、安全 DTO 与摘要列表 shape |

当前执行结果（2026-08-31）：

- V30 自身 16 个测试通过；
- V30 与现有 tool 包聚焦回归合计 79 个测试通过；
- 完整 backend Maven suite：394 个测试，0 failure、0 error、0 skipped；
- 测试命令使用项目要求的 JDK 21 与显式 Mockito `-javaagent`；Homebrew JDK 26 的首次尝试只在
  MockMaker 初始化阶段失败，不是业务断言失败。

### 7.2 PostgreSQL 与 HTTP

`backend/http/agents.http` 是可重放验收输入，本身不等于已执行证明。运行时验收应至少核对：

1. 空 PostgreSQL 顺序应用 V1–V16，`flyway_schema_history` 当前版本为 16；
2. 注册/登录一次性测试用户后，required-only 创建得到精确默认值；
3. 数据库中的 `user_id` 等于 JWT 用户、`config={}`、status=`ACTIVE`；
4. 另一个 owner 行、软删除行不在列表，`DISABLED` 行仍在列表；
5. 创建和列表前后没有新增 tool/LLM/RAG/task/step 日志或表级副作用；
6. V27–V29 三个工具聚焦回归与完整 Maven suite 通过。

当前执行结果（2026-08-31）：

- 一次性 PostgreSQL 18.4 cluster 从 `<< Empty Schema >>` 启动，Flyway 成功校验并顺序应用 16 个
  migration，最终 schema version 为 16；`agent_app.current_prompt_version_id` 与所有明确排除的未来表不存在；
- PostgreSQL 18.4 高于当前 Flyway 声明的最新测试版本 17，启动日志给出 upgrade recommended 警告；
  本次实际成功是本地兼容性证据，不扩张为生产版本组合保证；
- 使用两名一次性用户完成真实注册、登录和 Bearer JWT 请求，V30/数据库/工具运行时脚本共通过 41 项断言；
- required-only 创建实际得到字符串 ID、`0.2/0.8/6/4/8000/120`、`ACTIVE` 与数据库 `config={}`；
  `user_id` 与 JWT owner 一致，空白 description 存为 null；
- 数据库夹具证明跨 owner 与软删除行不可见、`DISABLED` 行仍可见，列表顺序为
  `created_at DESC, id DESC`，且返回项没有 prompt、预算、owner、config 或删除字段；
- 多余 owner 字段与错误 JSON 类型实际得到 `COMMON_REQUEST_BODY_INVALID/400`，交叉预算与不支持 provider
  实际得到 `COMMON_PARAM_INVALID/400`，匿名列表得到 `AUTH_UNAUTHENTICATED/401`；
- Agent 创建/列表及失败请求完成后 `tool_call_log` 仍为 0，task/step/LLM/RAG 表仍不存在；随后单独执行
  V27 `order_query`、V28 `payment_log_query`、V29 `report_generate` 均成功，并恰好写入 3 条 SUCCESS 工具日志；
- 应用为启动无关 RAG bean 使用仓库的 `local` adapter；验收没有调用真实 Qdrant、embedding 或 LLM provider，
  也不构成生产并发/性能证明；
- 验收结束后 Spring Boot、一次性 PostgreSQL cluster、测试用户/数据与临时脚本均已停止或删除。

## 8. 明确不做

- `GET /agents/{agentId}`、PATCH、删除、启停；
- `agent_knowledge_binding` 或知识库绑定 API；
- `agent_tool_binding`、`ToolSpec`、`getToolSpecsForAgent`；
- Prompt 版本及 `current_prompt_version_id`；
- LLM Gateway、AgentEngine、Prompt Builder、Decision Parser；
- task、step、Trace、SSE；
- Agent 实际执行或证明配置已经被 provider 使用；
- 前端 Agent 页面或工具列表；
- 宣称 M4 或 V0.1 已完成。

## 面试问题与回答

### 问题 1：为什么 owner 不放进创建 DTO？

回答：owner 是授权边界，不是客户端配置。`AgentAppController` 只接收 Spring Security 已认证的
`AuthenticatedUser`，`AgentAppService` 把 `currentUser.id()` 写入 `user_id`；严格反序列化器同时拒绝
`userId`。这样客户端即使知道其他用户 ID，也没有伪造 owner 的输入通道。

### 问题 2：为什么列表需要单独的 Summary DTO，而且 SQL 也只选摘要列？

回答：`system_prompt` 最多 20000 字符，把它和执行预算复制到每个分页行既扩大响应，也让列表调用方依赖
不必要的配置。`AgentAppMapper.selectVisibleOwnedPage` 只投影名称、模型、状态和时间，
`AgentAppSummaryResponse` 再固定公开 shape；这是数据访问和 API 两层边界，不只是序列化时隐藏。

### 问题 3：为什么 `DISABLED` 仍出现在列表？

回答：V30 将禁用与删除分开建模。只有 `deleted_at IS NULL` 决定 live 可见性，SQL 没有 `status=ACTIVE`
条件；因此 owner 仍能看到 `DISABLED` 元数据，后续启停/管理接口才能对它操作。本切片本身不提供禁用接口。

### 问题 4：为什么 V16 不先放一个可空的 `current_prompt_version_id`？

回答：Prompt 版本表尚未创建，提前放列只能保存无法验证的悬空 ID，也无法建立合法外键。V30 只保存当前
`system_prompt`；后续 Prompt 版本切片用新 migration 同时创建版本表、增加列和外键，Flyway 历史仍保持不可变。

### 问题 5：创建成功是否说明 Agent 已能调用模型和工具？

回答：不能。V30 的代码依赖只有 Agent Controller/Service/Mapper，既不读取 `ToolRuntime`，也不调用 LLM、
RAG 或创建 task/step/log。201 只证明配置元数据已持久化并按安全 DTO 返回；执行闭环属于后续切片。
