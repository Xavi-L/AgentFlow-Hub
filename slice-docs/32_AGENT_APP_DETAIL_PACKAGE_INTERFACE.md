# V31 当前 Owner Agent 配置详情接口包说明

> 状态：本地实现与验收完成。V31 在 V30 的 `agent_app` 根资源上增加当前 owner 的单 Agent
> 配置详情；JDK 21 聚焦/完整测试、隔离 PostgreSQL 18.4 空库迁移、登录后真实 HTTP 与数据库
> 验收均已执行。

## 1. 切片目标

V31 是 M4 的第二个 Agent 根资源切片，新增：

```http
GET /api/v1/agents/{agentId}
Authorization: Bearer <access-token>
```

完成后只能声明：

> 当前认证用户可以读取自己尚未删除的单个 Agent 配置详情。

这里的 Agent 仍是配置元数据，不是已经能够调用模型、获得工具、检索知识库或执行任务的运行时。

## 2. 依据与范围冻结

V31 的实现顺序基于当前仓库事实：

- V30 已实现创建与当前 owner 分页列表，并明确排除详情、修改、删除与启停；
- Agent API 在创建、列表之后列出的下一个根资源接口是 `GET /api/v1/agents/{agentId}`；
- 路线图要求先完成 agent app CRUD，再进入 LLM Gateway 与 AgentEngine；
- V30 的 `AgentAppResponse` 已包含详情所需的完整公开配置，无需新增 DTO；
- V16 的 `agent_app` 已保存详情所需字段，Prompt 历史、知识库绑定和工具绑定仍允许在后续切片实现。

V31 不修改已经应用的 V16，也不创建空的 V17 migration。当前 migration 文件仍只有 V1–V16。

## 3. HTTP 契约

### 3.1 成功响应

当前 owner 查询一条未软删除 Agent，返回 `200 OK`：

```json
{
  "code": "OK",
  "message": "Agent retrieved",
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
    "status": "ACTIVE",
    "createdAt": "2026-09-01T10:00:00+08:00",
    "updatedAt": "2026-09-01T10:00:00+08:00"
  }
}
```

`id` 延续项目约定，将 PostgreSQL `BIGINT` 输出为 JSON 字符串。详情继续复用
`AgentAppResponse`，公开完整 prompt、模型参数与执行预算，但不返回：

```text
userId
config
deletedAt
currentPromptVersionId
knowledgeBaseIds
toolIds
```

如果可空的 `description` 为 `null`，共享 Jackson 配置可以省略该字段；这不改变其公开字段语义。

### 3.2 列表契约保持不变

`GET /api/v1/agents` 继续使用 `AgentAppSummaryResponse`。分页行不读取或返回 `systemPrompt`、
temperature/topP 或执行预算。详情的完整 shape 不扩张 V30 列表 shape。

## 4. Owner 与可见性边界

`AgentAppMapper.selectVisibleOwnedById` 使用一条专用 SQL：

```sql
SELECT id,
       name,
       description,
       system_prompt,
       model_provider,
       model_name,
       temperature,
       top_p,
       max_steps,
       max_tool_calls,
       max_tokens,
       timeout_seconds,
       status,
       created_at,
       updated_at
FROM agent_app
WHERE id = #{agentId}
  AND user_id = #{userId}
  AND deleted_at IS NULL
```

路径 ID、JWT principal owner 和 live 条件在同一查询中收口。实现不先用 `selectById` 读取全局记录，
也不在 Java 中对已经读取的其他 owner 数据做二次过滤。

以下情况都让 scoped query 返回无行，并统一转换为：

```text
HTTP 404
COMMON_NOT_FOUND
Agent not found
```

- Agent 不存在；
- Agent 属于其他用户；
- Agent 已软删除。

查询不增加 `status = 'ACTIVE'`。`DISABLED` 是可管理状态而不是删除状态，所以当前 owner 仍可读取详情。
本切片不提供把 Agent 变为 `DISABLED` 的 API；该状态夹具只用于验证已有数据的读取语义。

## 5. 路径参数与认证错误

V31 冻结 `agentId` 行为：

| 输入 | 处理位置 | 结果 |
| --- | --- | --- |
| `1..Long.MAX_VALUE` | 进入 owner/live scoped query | 有匹配为 200，无匹配为统一 404 |
| 非数字 | Spring `Long` 绑定 + 全局异常处理 | `400 / COMMON_PARAM_INVALID` |
| 超出 `Long` 范围 | Spring `Long` 绑定 + 全局异常处理 | `400 / COMMON_PARAM_INVALID` |
| `0` 或负数 | Service 在 Mapper 前校验 | `400 / COMMON_PARAM_INVALID` |
| 未认证 | 既有 Spring Security 过滤器链 | `401 / AUTH_UNAUTHENTICATED` |

Service 对 `0`、负数和内部直接调用的 `null` 使用 `agentId must be a positive integer`；非数字与溢出沿用
统一参数绑定文案。V31 只冻结所有这些情况的 HTTP 状态和错误码，不要求它们共享同一 400 文案。

## 6. 实现边界

调用链固定为：

```text
AgentAppController.get(currentUser, agentId)
  -> AgentAppService.getOwnedById(currentUser, agentId)
     -> AgentAppMapper.selectVisibleOwnedById(agentId, currentUser.id)
        -> AgentAppResponse.from(agentApp)
```

`AgentAppService.getOwnedById` 使用 `@Transactional(readOnly = true)`。Agent 业务详情 SQL 只读取
`agent_app`；一次完整的认证 HTTP 请求仍会先由既有 `JwtAuthenticationFilter` 查询 `app_user`，确认 token
主体当前仍为 ACTIVE。这是认证链的既有行为，不能把“详情 Mapper 只读 agent_app”扩张为“整个 HTTP 请求
只产生一条数据库查询”。

V31 修改：

```text
AgentAppController
AgentAppService
AgentAppMapper
AgentAppControllerTest
AgentAppServiceTest
AgentAppMapperTest
backend/http/agents.http
```

V31 新增：

```text
slice-docs/32_AGENT_APP_DETAIL_PACKAGE_INTERFACE.md
```

V31 没有新增 Entity 字段、Response DTO、错误码、索引或 Flyway migration，也没有注入 ToolRuntime、
LLM Gateway、RAG Service、AgentEngine、task/step 或日志服务。

## 7. 验收证据

### 7.1 自动化测试

新增 10 个 V31 测试，覆盖：

- Controller 的成功消息、完整安全响应、404 翻译、非数字/溢出和非正 ID；
- Service 的完整公开配置、BIGINT 字符串、`DISABLED` 可读、scoped miss 和 Mapper 前校验；
- Mapper 的 ID/owner/live 三谓词、完整公开投影、无 status/绑定 JOIN/写操作。

2026-09-01 在 JDK 21 与显式 Mockito `-javaagent` 下实际执行：

```text
Agent Controller/Service/Mapper focused: 22/22 passed
Agent package including V16 contract:     26/26 passed
Agent + V27–V29 tool focused regression:  89/89 passed
complete backend Maven suite:             404/404 passed
Failures: 0, Errors: 0, Skipped: 0
```

这些结果证明 Java 行为、SQL 文本/shape 与既有工具回归，不单独冒充真实 PostgreSQL、外部 provider 或生产验收。

### 7.2 PostgreSQL 与真实 HTTP

`backend/http/agents.http` 已加入“创建显式配置后按同一 ID 读回详情”、合法 miss、非法 ID 和匿名详情请求；
它是可重放输入，文件本身不等于执行证据。

本轮另在 `/private/tmp` 启动一次性 PostgreSQL 18.4 cluster 和本地 Spring Boot，使用 `local` vector adapter，
注册/登录两名一次性用户并执行真实 Bearer JWT 请求。64 项断言全部通过：

1. 空 schema 成功校验并顺序应用 V1–V16，最终 schema version 和成功 migration 数均为 16；
2. 当前 owner 通过 POST 创建 Agent，再由 V31 以同一字符串 ID 读回完整公开配置；
3. 响应精确包含 15 个公开字段，不含 owner、config、删除、Prompt 版本或绑定字段；
4. 不存在、跨 owner 和软删除记录得到相同的 `404 / COMMON_NOT_FOUND / Agent not found`；
5. 数据库夹具中的 `DISABLED` 未删除 Agent 对当前 owner 返回 200；
6. 非数字、`Long` 溢出、0 和负数均返回 `400 / COMMON_PARAM_INVALID`；
7. 匿名详情返回 `401 / AUTH_UNAUTHENTICATED`；
8. V30 分页列表仍能找到创建记录，并继续省略 `systemPrompt`；
9. 详情请求前后目标行 `updated_at` 完全相同，`tool_call_log` 始终为 0；
10. `agent_prompt_version`、知识库/工具绑定、Agent task/step、LLM/RAG log 表以及
    `current_prompt_version_id` 列均不存在。

PostgreSQL 18.4 高于当前 Flyway 声明的最新测试版本 17，启动时有 upgrade recommended 警告；本次实际
成功只是本地兼容性证据，不扩张为生产版本组合保证。验收没有调用 Qdrant、embedding、LLM provider 或
任何内置工具，也不构成 Agent 执行、生产并发或性能证明。应用、cluster、一次性用户/数据与临时脚本均已停止并清理。

## 8. 明确不做

- PATCH 或任何字段修改；
- 删除、恢复；
- 启用、禁用；
- Prompt 历史版本；
- 知识库和工具绑定；
- 绑定数量或伪造的空数组；
- LLM Gateway、AgentEngine；
- Agent 任务、执行、Trace、SSE；
- 验证已保存模型配置是否被 provider 使用；
- 前端详情页；
- 宣称 M4、Agent CRUD 或 V0.1 已完成。

后续切片可继续按 `配置 PATCH -> 软删除 -> 启用/禁用 -> LLM Gateway` 拆分。PATCH 必须独立冻结字段
allowlist、缺省与 `null`、跨字段预算、无变化更新和 owner-scoped UPDATE，不纳入 V31。

## 面试问题与回答

### 问题 1：为什么详情不能先按 ID 查询，再在 Java 中判断 owner？

回答：这样会先把其他用户记录读入服务端，并使实现更容易产生可枚举的差异化分支。V31 在
`selectVisibleOwnedById` 的同一 SQL 中同时限定 ID、`user_id` 和 `deleted_at IS NULL`，因此不存在、跨 owner
与软删除都只表现为“无匹配行”，Service 统一返回 `Agent not found`。

### 问题 2：为什么详情复用 `AgentAppResponse`，列表仍保留 Summary DTO？

回答：V30 创建响应已经冻结了详情需要的完整公开配置，并排除了 owner/config/删除字段，因此复用能避免两个
完整 DTO 漂移。分页列表的调用语义不同，继续以专用 SQL 和 `AgentAppSummaryResponse` 排除大 prompt 与执行预算，
不会因为新增详情而膨胀每个分页项。

### 问题 3：为什么 `0` 和负数返回 400，而不是与不存在记录一起返回 404？

回答：它们不属于 V16 `BIGINT` 主键的合法资源标识空间。Service 在访问 Mapper 前拒绝非正 ID；格式合法的
正整数才进入 owner/live 查询，并在无匹配时返回 404。非数字和溢出则更早在 Spring 路径绑定阶段返回 400。

### 问题 4：为什么 `DISABLED` Agent 仍可读取？

回答：`status` 表达是否启用，`deleted_at` 才表达是否被删除。owner 需要能查看受管理但已禁用的配置；因此
详情 SQL 只有 live 条件，没有 `status = 'ACTIVE'`。V31 只读取该状态，不提供启停能力。

### 问题 5：详情返回了模型参数和工具预算，是否说明 Agent 已能执行？

回答：不能。V31 只读取 `agent_app` 配置，未注入 LLM、RAG、ToolRuntime、AgentEngine，也不创建 task、step 或
调用日志。真实 HTTP 只证明配置可以被当前 owner 安全读回；provider 使用和 Agent 执行闭环属于后续切片。
