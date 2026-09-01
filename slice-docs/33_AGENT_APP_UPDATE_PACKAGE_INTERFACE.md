# V32 当前 Owner Agent 公开配置部分更新接口包说明

> 状态：本地实现与验收完成。V31 已先独立提交为 `5bc68f6`；V32 在该基线上新增严格 PATCH、
> owner/live 行锁合并和无变化短路。JDK 21 聚焦/完整测试、隔离 PostgreSQL 18.4 空库迁移、
> 真实 JWT HTTP 与受控并发验收均已执行。

## 1. 切片目标

V32 是 M4 的第三个 Agent 根资源切片，新增：

```http
PATCH /api/v1/agents/{agentId}
Authorization: Bearer <access-token>
Content-Type: application/json
```

完成后只能声明：

> 当前认证用户可以部分修改自己尚未删除的 Agent 公开配置，并安全读回更新结果。

这里的 Agent 仍是 `agent_app` 中的配置元数据。V32 不调用模型、工具、知识库检索或 AgentEngine，
也不创建 task、step、Trace 或 SSE 事件。

## 2. 基线、顺序与 schema

V32 开始前，Git `HEAD` 与 `origin/main` 都在 V30 `a13eeb7`，V31 已在工作区实现并验收但尚未提交。
本轮先用明确文件 allowlist 复跑 V31 聚焦测试 22/22，再将 V31 独立提交为：

```text
5bc68f6 feat(agent): add V31 agent app detail
```

V32 因此只相对 V31 产生独立源码与文档 diff；既有 `.DS_Store`、本地认证脚本和
`backend/target/**` 生成物均未纳入 V31 提交，也不属于 V32。

V16 已包含全部可编辑列和数据库约束。V32：

- 不修改已经应用的 `V16__create_agent_app.sql`；
- 不新增 Flyway migration；
- 不新增 Entity 字段、Response DTO、错误码或索引；
- PostgreSQL schema version 继续为 16，仓库不存在空的 `V17__*.sql`。

## 3. HTTP 成功契约

部分更新示例：

```json
{
  "name": "支付问题诊断助手 V2",
  "temperature": 0.3,
  "maxSteps": 8,
  "maxToolCalls": 5
}
```

当前 owner 更新一条未软删除 Agent 后返回 `200 OK`：

```json
{
  "code": "OK",
  "message": "Agent updated",
  "data": {
    "id": "301",
    "name": "支付问题诊断助手 V2",
    "description": "分析订单支付失败",
    "systemPrompt": "你是企业内部研发运营助手……",
    "modelProvider": "openai-compatible",
    "modelName": "kimi-k2",
    "temperature": 0.3,
    "topP": 0.8,
    "maxSteps": 8,
    "maxToolCalls": 5,
    "maxTokens": 8000,
    "timeoutSeconds": 120,
    "status": "ACTIVE",
    "createdAt": "2026-09-01T10:00:00+08:00",
    "updatedAt": "2026-09-01T10:10:00+08:00"
  }
}
```

响应继续复用 V30/V31 的 `AgentAppResponse`。`BIGINT` ID 仍输出为 JSON 字符串，响应不包含：

```text
userId
config
deletedAt
currentPromptVersionId
knowledgeBaseIds
toolIds
```

可空 `description` 清空后，共享 Jackson `non_null` 配置可以省略该 JSON 属性；这与数据库列为
`NULL` 的语义一致。V30 分页列表继续使用 `AgentAppSummaryResponse`，不会因为 V32 而返回 prompt、
temperature/topP 或执行预算。

## 4. 字段 allowlist 与部分更新语义

`UpdateAgentAppRequestDeserializer` 只允许以下 11 个字段：

```text
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
```

以下 server-owned、审计、未来版本或绑定字段不是 PATCH 输入：

```text
id
userId
status
config
createdAt
updatedAt
deletedAt
currentPromptVersionId
knowledgeBaseIds
toolIds
```

请求 DTO 保存内部 `presentFields`，只用于区分字段缺失和显式 `null`。局部严格反序列化器不允许
客户端提交 `presentFields` 本身。

字段语义为：

| 输入 | 结果 |
| --- | --- |
| 字段缺失 | 使用锁定行中的当前值，不使用 V30 创建默认值 |
| `description: null` | 清空为数据库 `NULL` |
| `description` 为空白 | trim 后清空为数据库 `NULL` |
| 其他字段显式 `null` | `400 / COMMON_PARAM_INVALID` |
| `{}` | `400 / COMMON_PARAM_INVALID` |
| 未知字段 | `400 / COMMON_REQUEST_BODY_INVALID` |
| 非 object、错误类型或 JSON 语法错误 | `400 / COMMON_REQUEST_BODY_INVALID` |

V30 的值约束保持不变：

- `name` trim 后非空且不超过 128 字符；
- `description` trim 后不超过 4000 字符；
- `systemPrompt` 非空白且不超过 20000 字符，沿用 V30 的原文保留语义；
- `modelProvider` 只接受精确值 `openai-compatible`；
- `modelName` trim 后非空且不超过 128 字符；
- `temperature` 为 0–2，`topP` 为大于 0 且不大于 1，精度规则沿用 V30；
- `maxSteps` 为 1–20，`maxToolCalls` 为 0–20；
- `maxTokens` 为 256–100000；
- `timeoutSeconds` 为 1–600。

`maxToolCalls <= maxSteps` 不只检查本次同时提交的两个字段。Service 先将请求值与锁定行的存储值
合并，再校验有效配置；因此只提交 `maxSteps` 或只提交 `maxToolCalls` 时，也会使用数据库中的另一项。

V32 直接更新当前 `system_prompt` 列，不创建 Prompt 历史版本。

## 5. Owner、软删除与状态边界

锁定查询与实际写入都在 SQL 中同时包含：

```sql
WHERE id = #{agentId}
  AND user_id = #{userId}
  AND deleted_at IS NULL
```

路径 ID 只来自 URL，owner 只来自已经验证的 JWT principal。实现不先读取全局 ID 再在 Java 中判断
owner，也不接受请求体中的 `userId`。

以下 scoped miss 统一返回：

```text
HTTP 404
COMMON_NOT_FOUND
Agent not found
```

- Agent 不存在；
- Agent 属于其他 owner；
- Agent 已软删除。

查询和更新都不增加 `status = 'ACTIVE'`。`DISABLED` 表示禁用而不是删除，所以当前 owner 仍可修改其
公开配置；V32 只保持该状态，不提供修改 `status` 的能力。

非数字和超出 `Long` 范围的路径 ID由 Spring 参数绑定映射为 `400 / COMMON_PARAM_INVALID`；0、负数
以及 Service 内部的 null ID 在访问 Mapper 前被拒绝。

## 6. 并发与事务边界

V32 调用链为：

```text
AgentAppController.update(currentUser, agentId, request)
  -> AgentAppService.updateOwnedConfig(currentUser, agentId, request)
     -> AgentAppMapper.selectVisibleOwnedByIdForUpdate(agentId, currentUser.id)
     -> 归一化、合并、交叉校验、同值判断
     -> AgentAppMapper.updateConfigOwned(agentId, currentUser.id, effectiveAgent)
     -> AgentAppResponse.from(effectiveAgent)
```

`updateOwnedConfig` 使用 `@Transactional`。锁定查询在 owner/live scope 后增加：

```sql
FOR UPDATE
```

同一 Agent 的第二个并发 PATCH 会在读取当前配置前等待第一个事务提交，然后基于第一个事务的新值
重新合并。这样两个请求分别修改不同字段时，后到请求不会把先到请求的字段回写成旧值。V31 普通详情
仍使用非锁定、只读查询。

实际 UPDATE 只写 11 个公开配置列和服务端 `updated_at`：

```text
name, description, system_prompt, model_provider, model_name,
temperature, top_p, max_steps, max_tool_calls, max_tokens,
timeout_seconds, updated_at
```

SQL 不写 `id`、`user_id`、`status`、`config`、`created_at` 或 `deleted_at`。即使锁定查询已经命中，
UPDATE 仍重复 ID/owner/live 谓词；零行写入映射为相同的 `Agent not found`，异常多行写入则作为内部
一致性错误处理。

## 7. 无变化 PATCH

Service 对归一化后的 11 个有效配置字段逐项比较。字符串与整数使用值相等；`temperature` 和 `topP`
使用 `BigDecimal.compareTo`，所以数据库读出的 `0.200` 与请求中的 `0.2` 相等。

如果所有字段都相同：

- 返回 `200 / OK / Agent updated`；
- 不调用 `updateConfigOwned`；
- 不刷新 `updatedAt`；
- 返回锁定行当前的安全 `AgentAppResponse`。

真实 PostgreSQL 验收同时比较了 HTTP `updatedAt` 与数据库 `updated_at`，确认 scale 等价的 PATCH 没有
执行实际 UPDATE。

## 8. 实现文件

V32 新增：

```text
backend/src/main/java/com/agentflow/agent/dto/UpdateAgentAppRequest.java
backend/src/main/java/com/agentflow/agent/dto/UpdateAgentAppRequestDeserializer.java
slice-docs/33_AGENT_APP_UPDATE_PACKAGE_INTERFACE.md
```

V32 修改：

```text
backend/src/main/java/com/agentflow/agent/controller/AgentAppController.java
backend/src/main/java/com/agentflow/agent/service/AgentAppService.java
backend/src/main/java/com/agentflow/agent/repository/AgentAppMapper.java
backend/src/test/java/com/agentflow/agent/controller/AgentAppControllerTest.java
backend/src/test/java/com/agentflow/agent/service/AgentAppServiceTest.java
backend/src/test/java/com/agentflow/agent/repository/AgentAppMapperTest.java
backend/http/agents.http
```

没有修改 Entity、`AgentAppResponse`、错误码或 migration。

## 9. 验收证据

### 9.1 自动化测试

V32 新增 16 个 Controller/Service/Mapper 测试，覆盖：

- PATCH 路由、JWT principal 传递、成功消息与安全响应；
- 11 字段 presence、description 清空、其他 null、空对象；
- unknown/server-owned 字段、非 object、语法错误与错误 JSON 类型；
- V30 文本、provider、decimal、整数与执行预算范围；
- 缺失字段使用存储值，单预算字段与存储 counterpart 合并校验；
- `DISABLED` 状态保持；
- owner/live `FOR UPDATE` 与 scoped UPDATE 的 SQL shape；
- 归一化和 `BigDecimal` scale 等价时不 UPDATE、不刷新时间；
- scoped miss 与零行写入的统一 404。

2026-09-01 在 JDK 21 和显式 Mockito `-javaagent` 下实际执行：

```text
Agent Controller/Service/Mapper focused: 38/38 passed
Agent package including V16 contract:     42/42 passed
complete backend Maven suite:             420/420 passed
Failures: 0, Errors: 0, Skipped: 0
```

完整 suite 包含 V30 创建/列表、V31 详情、V27–V29 ToolRuntime 及此前所有知识库切片回归。这里的 mock、
SQL shape 与 migration contract 测试不单独冒充真实数据库、线上并发或外部 provider 证据。

### 9.2 PostgreSQL 与真实 HTTP

`backend/http/agents.http` 已补充创建后 PATCH、无变化 PATCH、详情读回、合并预算失败、description 清空、
空对象、server-owned 字段与 invisible ID；该文件是可重放输入，不等于执行证据。

本轮另在 `/private/tmp` 启动一次性 PostgreSQL 18.4 cluster 与本地 Spring Boot，使用 local vector adapter、
两名一次性用户和真实 Bearer JWT 请求执行 97 项断言，全部通过：

1. 空 schema 成功校验并应用 V1–V16，成功 migration 数与最终 version 均为 16；
2. owner 创建 Agent，部分 PATCH 后名称、decimal 和单项预算更新，所有遗漏字段保持存储值；
3. V31 详情读回 V32 有效配置，V30 列表仍使用精简 DTO；
4. `description: null` 落为 SQL NULL，其他 10 个字段显式 null 均为 `COMMON_PARAM_INVALID`；
5. 空对象、server-owned/未来字段、错误类型、非 object 和损坏 JSON 按约定分成两类 400；
6. 单独降低 `maxSteps` 时使用存储的 `maxToolCalls` 发现交叉约束冲突；
7. `0.300` 与数据库 `0.300`/响应 `0.3` 的无变化 PATCH 保持 HTTP 和数据库时间戳不变；
8. 不存在、跨 owner 和软删除 Agent 得到相同 404；
9. 数据库夹具中的 `DISABLED` 未删除 Agent 可更新且 status 保持不变；
10. 受控并发用临时 `BEFORE UPDATE pg_sleep(0.5)` 触发器强制两个不同字段 PATCH 重叠；日志显示第二个
    `SELECT ... FOR UPDATE` 在第一个事务提交后读到新值，最终 name 与 modelName 两个修改都保留；
11. 响应只含公开字段，目标行的 owner、status、`config={}`、创建/删除标记不被 PATCH 写入；
12. `tool_call_log` 始终为 0，未来 Prompt/绑定/task/step 表不存在。

并发触发器只存在于一次性验收数据库，用于稳定制造重叠窗口，验收结束前已删除。临时应用、数据库、
用户、数据、触发器与验收脚本均已停止并清理。PostgreSQL 18.4 高于当前 Flyway 声明的最新测试版本 17，
启动时仍有 upgrade recommended 警告；本次成功是本地兼容性与锁语义证据，不扩张为生产版本组合、性能
或所有隔离级别的保证。

验收没有调用 Qdrant、embedding、LLM provider 或内置工具，不证明已保存配置被 provider 实际使用，
也不构成 Agent 执行闭环。

## 10. 明确不做

- 修改 `status`；
- 删除、恢复、启用或禁用；
- Prompt 历史版本与回滚；
- 知识库或工具绑定；
- 修改内部 `config`；
- LLM Gateway、AgentEngine；
- Agent task、step、Trace、SSE；
- 验证模型配置已被 provider 实际使用；
- 前端编辑页；
- 宣称 M4、完整 Agent CRUD 或 V0.1 已完成。

后续顺序保持：

```text
V33 Agent 软删除
V34 Agent 启用/禁用
之后进入 LLM Gateway
```

## 面试问题与回答

### 问题 1：PATCH 为什么需要 presence，而不能只看 DTO 字段是不是 null？

回答：字段缺失表示保留数据库当前值，而 `description: null` 表示主动清空。如果只看 Java null，这两种
输入会无法区分，其他字段也可能错误套用 V30 创建默认值。V32 的局部反序列化器记录 11 个 allowlist 字段
是否出现；内部 presence 信息不是客户端可提交字段。

### 问题 2：为什么读取后还要 `FOR UPDATE`，普通事务不够吗？

回答：普通 read-merge-write 中，两个事务可能同时读到同一旧行，随后各自整行更新，后提交者会覆盖先提交者
修改的其他字段。V32 在事务内先按 ID/owner/live scope 锁行；第二个 PATCH 只能在第一个提交后读取，因此会
基于最新配置合并。真实受控并发验收强制两个请求重叠，并确认两个不同字段都保留。

### 问题 3：为什么无变化 PATCH 仍返回 200，却不执行 UPDATE？

回答：部分更新是幂等的，客户端提交当前有效值不应被当成失败；但盲目 UPDATE 会制造虚假的审计时间变化和
额外写放大。Service 先比较归一化后的完整有效配置，相同就返回当前安全 DTO。Decimal 使用 `compareTo`，
避免 `0.2` 与 `0.200` 因 scale 不同产生假变化。

### 问题 4：为什么不存在、跨 owner 和软删除都返回同一个 404？

回答：锁定查询和更新 SQL 都把 ID、JWT owner 与 `deleted_at IS NULL` 放在同一谓词中，所有不可见情况只表现为
无匹配行。这样既保持资源接口一致，也不向调用者泄露其他 owner 或已删除记录是否存在。`DISABLED` 不属于
不可见条件，因为禁用与删除是不同状态。

### 问题 5：返回了完整模型与执行预算，是否说明配置已经驱动 Agent 执行？

回答：不能。V32 只在 `agent_app` 中更新并读回配置，没有 LLM Gateway、AgentEngine、绑定、task/step、Trace
或 SSE。自动化与真实 HTTP 证明的是 owner-scoped 配置 PATCH、数据库锁和响应边界；provider 使用与执行闭环
仍是后续规划。
