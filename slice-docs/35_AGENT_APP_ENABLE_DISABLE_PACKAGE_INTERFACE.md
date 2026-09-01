# V34 当前 Owner Agent 启用/禁用接口包说明

> 状态：本地实现与验收完成。V33 已提交并推送为 `8496f2c`；V34 在该基线上新增单个
> Agent 的 owner-scoped 启用与禁用。JDK 21/Mockito javaagent 聚焦、Agent package 与完整
> Maven suite，隔离 PostgreSQL 18.4 空库迁移、真实 JWT HTTP、逐列持久化与受控并发验收
> 均已执行。

## 1. 切片目标

V34 是 M4 的第五个 Agent 根资源切片，新增两个共享同一状态机与并发边界的动作：

```http
POST /api/v1/agents/{agentId}/enable
POST /api/v1/agents/{agentId}/disable
Authorization: Bearer <access-token>
```

请求不包含 body。完成后只能声明：

> 当前认证用户可以启用或禁用自己尚未删除的 Agent；状态变化会反映在现有列表、详情和配置
> PATCH 路径中。

也可以称为“Agent 根资源后端基础管理动作已闭合”，但不能宣称禁用状态已经阻止 Agent 执行。
当前尚不存在 AgentEngine 或 task 创建入口；只有后续执行入口明确要求 `status = ACTIVE` 并完成验收后，
才能证明禁用门槛已进入执行链。

## 2. 基线、判断依据与 schema

V34 开始前，Git `HEAD` 与本地 `origin/main` 都是：

```text
8496f2c feat(agent): add V33 agent soft delete
```

V33 基线完整 Maven suite 为 431/431。本轮实现前没有重新运行该历史基线，而是先核对当前 checkout、
迁移、源码、测试、`spec-docs` 和连续切片契约。

V34 先于 LLM Gateway 的依据是：

- Agent API 已独立列出 enable 与 disable；
- 项目规格把“启用/禁用 Agent”列为必须功能；
- V32 与 V33 连续冻结了“V33 软删除 → V34 启用/禁用 → LLM Gateway”；
- 先固定 `ACTIVE / DISABLED` 的管理语义，可避免执行入口出现后再返工状态并发与幂等边界。

路线图在 `agent app CRUD` 后直接进入 LLM Gateway 也具有更快产生执行链价值的合理性，但不改变上述已经
冻结的动作契约。enable 与 disable 放在同一个切片，因为它们共享状态列、owner/live 行锁、SQL、测试夹具
和并发语义；拆开会产生没有业务价值的中间版本。

V16 已有：

```sql
status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
CHECK (status IN ('ACTIVE', 'DISABLED'))
```

因此 V34：

- 不修改已应用的 `V16__create_agent_app.sql`；
- 不新增 Flyway migration，也不创建空的 `V17__*.sql`；
- schema version 继续为 16；
- 不补数据模型目标态中的 `idx_agent_user_status`：V34 按主键与 owner 更新，现有列表也不按 status 过滤；
- 不新增 DTO、Entity 字段、错误码、索引、枚举或内部 `config` 字段。

## 3. HTTP 契约

### 3.1 成功响应

disable 示例：

```http
POST /api/v1/agents/301/disable
Authorization: Bearer <access-token>
```

```json
{
  "code": "OK",
  "message": "Agent disabled",
  "data": {
    "id": "301",
    "name": "支付问题诊断助手",
    "description": "分析订单支付失败",
    "systemPrompt": "你是企业内部研发运营助手……",
    "modelProvider": "openai-compatible",
    "modelName": "kimi-k2",
    "temperature": 0.2,
    "topP": 0.8,
    "maxSteps": 6,
    "maxToolCalls": 4,
    "maxTokens": 8000,
    "timeoutSeconds": 120,
    "status": "DISABLED",
    "createdAt": "2026-09-01T10:00:00+08:00",
    "updatedAt": "2026-09-01T10:20:00+08:00"
  },
  "traceId": "...",
  "timestamp": "..."
}
```

enable 使用相同完整公开 DTO，差异为：

```text
message = Agent enabled
data.status = ACTIVE
```

V34 复用 `AgentAppResponse`，不新增动作 DTO。响应仍不包含 `userId`、内部 `config`、`deletedAt`、
Prompt 版本或知识库/工具绑定字段。DELETE 返回 `Void` 是因为资源随后不可见；启停后的 live resource
仍然可见并可管理，返回完整 DTO 能让调用方直接确认目标状态与 `updatedAt`。

### 3.2 错误与幂等规则

| 输入或资源状态 | HTTP / code / message | Mapper 行为 |
| --- | --- | --- |
| 非数字 ID | `400 / COMMON_PARAM_INVALID` | Spring 参数绑定阶段拒绝 |
| 超出 `Long` | `400 / COMMON_PARAM_INVALID` | Spring 参数绑定阶段拒绝 |
| `0` 或负数 | `400 / COMMON_PARAM_INVALID` | Service 在 Mapper 前拒绝 |
| 不存在、跨 owner、已软删除 | `404 / COMMON_NOT_FOUND / Agent not found` | owner/live 锁查询无记录 |
| 已经处于目标状态 | `200 / OK` | 锁定后直接返回，不执行 UPDATE |

重复 enable 或 disable 不使用 404/409。目标资源仍然存在且可见；幂等 200 能支持安全网络重试，并且不会
制造虚假的 `updatedAt` 变化。

## 4. 调用链、事务与 SQL

调用链固定为：

```text
AgentAppController.enable(currentUser, agentId)
  -> AgentAppService.enableOwned(currentUser, agentId)
     -> changeOwnedStatus(currentUser, agentId, "ACTIVE")

AgentAppController.disable(currentUser, agentId)
  -> AgentAppService.disableOwned(currentUser, agentId)
     -> changeOwnedStatus(currentUser, agentId, "DISABLED")
```

两个 public Service 方法都使用 `@Transactional`，并复用 V32 的 owner/live 锁查询：

```sql
SELECT <完整公开列>
FROM agent_app
WHERE id = #{agentId}
  AND user_id = #{userId}
  AND deleted_at IS NULL
FOR UPDATE
```

规则为：

1. `agentId` 只来自路径，`userId` 只来自认证 principal；
2. scoped 查询无记录，统一返回 `404 / COMMON_NOT_FOUND / Agent not found`；
3. 当前 `status` 等于目标状态时，直接用锁定行构造 `AgentAppResponse`，不写数据库；
4. 真实转换只设置目标 status 和服务端 `updatedAt`；
5. Mapper 更新继续保留 ID、owner 与 live-row 谓词，不读取全局 ID 后在 Java 判断 owner；
6. 使用锁定行的当前 status 作为 `expectedStatus` 防护：

```sql
UPDATE agent_app
SET status = #{targetStatus},
    updated_at = #{updatedAt}
WHERE id = #{agentId}
  AND user_id = #{userId}
  AND deleted_at IS NULL
  AND status = #{expectedStatus}
```

同一事务已持有行锁，因此真实转换必须恰好影响 1 行。影响 0 行或异常多行意味着锁后状态/可见性发生了
不符合当前模型的一致性问题，统一抛内部 `IllegalStateException`，不能再次伪装成资源 404。

V34 与 V33 的写法不同。V33 删除只需原子判断 owner/live，可用单条 scoped UPDATE；V34 必须区分不可见
资源与已处于目标状态，还要让幂等动作保持原 `updatedAt`，因此需要先锁定并读取状态。

## 5. 状态、可见性与字段边界

`DISABLED` 是 live resource 的运行状态，不是删除：

- V30 列表继续返回 DISABLED Agent；
- V31 详情继续返回完整公开配置与 DISABLED status；
- V32 PATCH 继续允许修改 DISABLED Agent 的公开配置，并保持 status；
- V33 DELETE 继续允许删除 ACTIVE 或 DISABLED Agent，并保持删除前 status；
- V34 不向列表、详情或 PATCH SQL 增加 `status = ACTIVE`；
- 已软删除行无法命中 V34 owner/live 锁查询，enable 不能把 `deleted_at` 恢复为 NULL。

真实转换的 UPDATE 只包含：

```text
status
updated_at
```

它不写 name、description、system prompt、模型配置、采样配置、执行预算、owner、内部 `config`、
`created_at` 或 `deleted_at`。响应的完整公开配置来自同一事务中已经锁定的当前行，而不是一次无 scope 的
二次查询。

## 6. 并发语义

V32 PATCH 与 V34 启停都先执行相同的 owner/live `SELECT ... FOR UPDATE`；V33 scoped DELETE 的 UPDATE
也会取得目标行写锁。PostgreSQL `READ COMMITTED` 下预期结果为：

| 竞争动作 | 串行化结果 |
| --- | --- |
| 两个 disable | 第一个真实转换；第二个取得锁后看到 DISABLED，幂等返回；只刷新一次时间 |
| 两个 enable | 第一个真实转换；第二个取得锁后看到 ACTIVE，幂等返回；只刷新一次时间 |
| enable 与 disable | 按获得行锁的顺序串行执行，后执行的动作决定最终 status |
| PATCH 与启停 | 共享行锁；PATCH 只写配置，启停只写 status，双方不回写对方字段 |
| DELETE 先完成 | 等待中的启停重新判断 live predicate 后无记录，返回统一 404 |
| 启停先完成 | 启停先返回 200，随后 DELETE 成功，最终资源已软删除 |

任何顺序都没有把 `deleted_at` 写回 NULL 的 SQL，因此不能恢复已软删除记录。这些结果必须用真实
PostgreSQL 的受控并发验收确认；Service mock 与 Mapper SQL-shape 测试只证明 Java 分支与静态语句边界。

## 7. 实现文件与边界

V34 新增：

```text
slice-docs/35_AGENT_APP_ENABLE_DISABLE_PACKAGE_INTERFACE.md
```

V34 修改：

```text
backend/src/main/java/com/agentflow/agent/controller/AgentAppController.java
backend/src/main/java/com/agentflow/agent/service/AgentAppService.java
backend/src/main/java/com/agentflow/agent/repository/AgentAppMapper.java
backend/src/test/java/com/agentflow/agent/controller/AgentAppControllerTest.java
backend/src/test/java/com/agentflow/agent/service/AgentAppServiceTest.java
backend/src/test/java/com/agentflow/agent/repository/AgentAppMapperTest.java
backend/http/agents.http
```

V34 不新增或修改：

```text
请求/响应 DTO、AgentApp Entity 字段、ErrorCode、Flyway migration、索引、状态枚举
```

## 8. 验收标准与当前证据

验收至少覆盖：

1. owner 可完成 `ACTIVE → DISABLED` 与 `DISABLED → ACTIVE`；
2. 成功响应包含正确 message、完整安全公开 DTO 与目标 status；
3. 真实转换只更新 `status` 与 `updated_at`；
4. 重复动作返回 200 且 `updatedAt` 不变；
5. 配置、owner、内部 `config`、`created_at`、`deleted_at` 均不改变；
6. 不存在、跨 owner、已删除统一 404；
7. 非数字、溢出、零、负数 ID 使用既定 400；
8. DISABLED Agent 仍可在列表、详情与 PATCH 中管理；
9. 删除后的 Agent 不能通过 enable 恢复；
10. 启停、PATCH/启停、DELETE/启停并发满足第 6 节串行化结果；
11. 不产生工具、LLM、RAG、task、step 或 Trace 日志；
12. schema 仍为 16，V30–V33、V27–V29 与完整 Maven suite 全部通过；
13. 真实 HTTP 完成“创建 → 禁用 → 重复禁用 → 详情/PATCH/列表 → 启用 → 重复启用 → 删除 →
    启用/禁用均 404”。

### 8.1 自动化测试

2026-09-01 使用 Microsoft OpenJDK 21.0.11 与显式 Mockito `-javaagent` 实际执行：

```text
Agent Controller/Service/Mapper focused: 60/60 passed
Agent package including V16 contract:     64/64 passed
complete backend Maven suite:             442/442 passed
Failures: 0, Errors: 0, Skipped: 0
```

新增测试已覆盖两个路由与消息、安全 DTO、非法路径、统一 404、双向转换、同状态无写入、异常影响行数、
删除后不可恢复、owner/live/expected-status SQL shape，以及既有 DISABLED 可管理行为。它们不单独证明真实
数据库锁等待、逐列持久化结果或线上并发。

完整 suite 包含 V30–V33 Agent、V27–V29 ToolRuntime 及此前全部切片回归。测试运行会刷新
`backend/target/**`；这些生成物不属于 V34 源码改动，也不得纳入后续提交。

### 8.2 PostgreSQL、真实 HTTP 与并发

本轮在 `/private/tmp` 启动一次性 PostgreSQL 18.4 空集群与本地 Spring Boot，使用 local vector adapter、
两名一次性用户和真实 Bearer JWT 请求执行 204 项断言，全部通过：

1. Flyway 成功校验并应用 V1–V16，成功 migration 数与最终 schema version 均为 16；
2. `ck_agent_app_status` 仍只有 `ACTIVE / DISABLED`，没有 V17 migration 或空泛的 status 索引；
3. 当前 owner 完成“创建 → 禁用 → 重复禁用 → 详情/列表/PATCH → 启用 → 重复启用 → 删除 →
   启用/禁用均 404”；
4. 两个动作请求均无 body，成功外壳分别为 `Agent enabled / Agent disabled`，并返回字段集合精确匹配
   `AgentAppResponse` 的完整安全 DTO；
5. status 真实转换前后逐列比较确认只改变 `status` 与 `updated_at`；owner、完整公开配置、内部
   `config`、`created_at` 与 `deleted_at` 不变，HTTP `updatedAt` 与 PostgreSQL `updated_at` 为同一时刻；
6. 重复启用/禁用均返回 200，数据库整行与 `updatedAt` 保持不变，临时 transition audit 计数不增加；
7. 不存在、跨 owner、已删除使用相同 `404 / COMMON_NOT_FOUND / Agent not found`；非数字、溢出、零、
   负数路径 ID 使用既定 400，缺失认证使用 401；
8. DISABLED Agent 在详情和列表中保持可见，并能通过 V32 PATCH 修改配置且保持 DISABLED；删除后 enable
   不能恢复记录；
9. 两个受控并发 disable 都返回 200，但只产生一次真实转换并共享一个更新时间；
10. 并发 enable/disable 按行锁顺序串行化，后完成的动作与数据库最终 status 一致；
11. 并发 PATCH/disable 后，最终行同时保留配置改动与 DISABLED status，owner、内部 config、created/deleted
    边界均未被覆盖；
12. status-first/DELETE 场景中 status 200、DELETE 200、最终行已删除且保留转换后状态；DELETE-first/status
    场景中 DELETE 200、等待中的 status 404、记录保持删除前状态；
13. `tool_call_log`、知识库/文档/chunk/回答/反馈与文档任务表始终为 0，未来 agent task/step/Trace 表不存在；
14. 验收专用 sleep/audit 触发器、函数和表在成功报告前删除；应用、数据库、临时用户、数据、脚本、存储和
    集群目录随后全部停止并清理。

临时触发器只存在于一次性验收数据库，用于稳定制造锁竞争窗口，不属于 V34 实现。PostgreSQL 18.4 高于
当前 Flyway 声明的最新测试版本 17，启动时出现 upgrade recommended 警告；上述结果是当前 checkout 的
本地兼容性、HTTP 契约与锁语义证据，不扩张为生产版本组合、性能、线上能力或所有隔离级别保证。

`backend/http/agents.http` 已加入可重放 V34 主流程、幂等、删除后 404、路径错误与鉴权检查；该文件本身
只是验收输入。204 项断言来自本轮另行执行的一次性脚本，未把 `.http` 文件本身当作执行证据。验收没有调用
Qdrant、embedding、LLM provider、内置工具或 AgentEngine。

## 9. 明确不做

- 通过 PATCH 或请求 body 任意提交 status；
- `DRAFT`、`ARCHIVED` 等新状态；
- 启用前调用真实模型验证配置；
- Agent 与知识库、工具绑定检查；
- 禁用历史、原因、审计日志或定时启停；
- LLM Gateway、AgentEngine、task、step、SSE、Trace；
- 物理删除、恢复、批量或管理员跨 owner 操作；
- 前端启停按钮；
- migration、索引、触发器、队列或后台任务；
- 将 mock、SQL shape 或隔离本地数据库验收表述为线上能力、生产并发或所有隔离级别保证。

## 面试问题与回答

### 问题 1：为什么 enable 与 disable 使用独立 POST 动作，而不让 PATCH 修改 status？

回答：既有 Agent API 已把启用与禁用定义为独立动作，V32 PATCH 也冻结了严格公开配置 allowlist并拒绝
`status`。独立动作只有一个服务端确定的目标状态，不需要接受客户端任意状态字符串，便于固定鉴权、幂等、
响应消息和后续执行门槛。新状态或通用状态机未纳入本切片。

### 问题 2：为什么重复启用或禁用返回 200，还必须保持 updatedAt？

回答：资源仍然属于当前 owner、尚未删除且已经满足调用者目标，因此不是 404 或冲突。200 让网络重试安全；
锁定后检测同状态并跳过 UPDATE，能避免没有真实状态变化却制造更新时间和下游变更信号。

### 问题 3：为什么 V34 要先 SELECT FOR UPDATE，而 V33 删除只做单条 UPDATE？

回答：V33 只需判断 owner/live 并写删除时间，影响行数可以完整表达结果。V34 还必须区分不可见资源和合法的
同状态幂等请求，并返回当前完整 DTO；先锁定读取能同时提供该区分、稳定响应快照和并发串行化。真实转换随后
只写 status 与 updated_at。

### 问题 4：为什么真实转换影响 0 行不是 404？

回答：404 已由同一事务内的 owner/live 锁查询决定。查询命中且当前状态需要转换后，本事务持有目标行锁，
expected-status UPDATE 应恰好命中该行；此时 0 行意味着模型之外的触发器、约束、事务边界或代码一致性问题。
把它降级为 404 会掩盖内部错误并错误暗示资源不可见。

### 问题 5：DISABLED 是否已经能阻止 Agent 执行？

回答：不能这样宣称。V34 只证明 `agent_app` 状态管理以及列表、详情、PATCH、DELETE 的交互；当前没有
AgentEngine/task 执行入口，也没有运行时查询 `status = ACTIVE` 的验收。后续执行切片必须在任务创建或引擎
入口实施并验证该门槛，才能证明禁用会阻止执行。
