# V33 当前 Owner Agent 软删除接口包说明

> 状态：本地实现与验收完成。V32 已提交并推送为 `fbd1630`；V33 在该基线上新增单个
> Agent 的 owner-scoped 软删除。JDK 21 聚焦/完整测试、隔离 PostgreSQL 18.4 空库迁移、
> 真实 JWT HTTP、逐列持久化检查与受控并发验收均已执行。

## 1. 切片目标

V33 是 M4 的第四个 Agent 根资源切片，新增：

```http
DELETE /api/v1/agents/{agentId}
Authorization: Bearer <access-token>
```

请求不包含 body。完成后只能声明：

> 当前认证用户可以软删除自己尚未删除的 Agent；删除后该 Agent 在现有列表、详情和配置 PATCH
> 路径中不可见。

也可以称为“`agent_app` 根资源基础 CRUD 已闭合”，但不能宣称 Agent 管理模块、M4、Agent 执行闭环
或 V0.1 已经完成。

## 2. 基线、判断依据与 schema

V33 开始前，Git `HEAD` 与本地 `origin/main` 都是：

```text
fbd1630 feat(agent): add V32 agent config patch
```

当前顺序保持为：

```text
V33 Agent 软删除
V34 Agent 启用/禁用
之后进入 LLM Gateway
```

选择软删除而不是直接进入 LLM Gateway，是因为 Agent API、项目规格和数据模型已经约定删除能力与
软删除策略，V32 也明确冻结了上述后续顺序。直接进入执行链可能更快产生产品演示价值，但会绕过已经
采用的根资源 CRUD 切片顺序。

V16 已有 `deleted_at`、`updated_at` 和 live-row 部分索引。V33：

- 不修改已应用的 `V16__create_agent_app.sql`；
- 不新增 Flyway migration，也不创建空的 `V17__*.sql`；
- PostgreSQL schema version 继续为 16；
- 不新增请求/响应 DTO、Entity 字段、错误码、索引、触发器或锁查询。

## 3. HTTP 契约

请求：

```http
DELETE /api/v1/agents/301
Authorization: Bearer <access-token>
```

成功返回项目既有的 `200 OK + ApiResponse<Void>`，不切换为 `204 No Content`：

```json
{
  "code": "OK",
  "message": "Agent deleted",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
```

Agent 不存在、属于其他 owner、已经软删除或重复删除时统一返回：

```text
HTTP 404
COMMON_NOT_FOUND
Agent not found
```

路径 ID 规则继续沿用 V31/V32：

| 输入 | 结果 | Mapper 是否访问 |
| --- | --- | --- |
| 非数字 | `400 / COMMON_PARAM_INVALID` | 否，由 Spring 参数绑定拒绝 |
| 超出 `Long` | `400 / COMMON_PARAM_INVALID` | 否，由 Spring 参数绑定拒绝 |
| `0` 或负数 | `400 / COMMON_PARAM_INVALID` | 否，由 Service 拒绝 |
| 正整数但不在 owner/live scope | `404 / COMMON_NOT_FOUND` | 是，scoped UPDATE 影响 0 行 |

## 4. 调用链、事务与 SQL

调用链固定为：

```text
AgentAppController.softDelete(currentUser, agentId)
  -> AgentAppService.softDeleteOwned(currentUser, agentId)
     -> AgentAppMapper.softDeleteOwned(agentId, currentUser.id, deletedAt)
```

`softDeleteOwned` 使用 `@Transactional`，但不先读取资源。Mapper 只执行一条原子 UPDATE：

```sql
UPDATE agent_app
SET deleted_at = #{deletedAt},
    updated_at = #{deletedAt}
WHERE id = #{agentId}
  AND user_id = #{userId}
  AND deleted_at IS NULL
```

规则为：

- `agentId` 只来自路径，`userId` 只来自已认证 JWT principal；
- `deleted_at` 与 `updated_at` 复用同一个服务端 `OffsetDateTime` 参数，值完全相同；
- SQL 同时包含 ID、owner 与 live-row 谓词，不读取全局 ID 后再在 Java 判断 owner；
- 不使用 MyBatis-Plus `deleteById`，不执行 `DELETE FROM`；
- 不增加 `status` 条件，因此 `ACTIVE` 与 `DISABLED` Agent 都可删除；
- 不修改 `status`、公开配置、owner、内部 `config` 或 `created_at`；
- 影响 0 行映射为统一 `404 / COMMON_NOT_FOUND / Agent not found`；
- 影响 1 行为成功；其他影响行数作为内部一致性错误处理。

## 5. 删除后的既有可见性

V30 列表、V31 详情和 V32 配置 PATCH 已经在 SQL 中使用 `deleted_at IS NULL`。V33 成功后直接复用这些
边界：

- `GET /api/v1/agents/{agentId}` 返回统一 404；
- `PATCH /api/v1/agents/{agentId}` 的 owner/live `SELECT ... FOR UPDATE` 返回空并统一 404；
- `GET /api/v1/agents` 不再包含该 Agent；
- 再次 DELETE 的 live-row UPDATE 影响 0 行并统一 404；
- 不存在任何 PATCH 将 `deleted_at` 写回 `NULL` 的路径，因此不能通过公开接口复活记录。

`DISABLED` 只表示禁用，不等于已删除。V33 不读取或改变 status；未删除的 `ACTIVE` 和 `DISABLED` 行
拥有相同删除资格。

## 6. 并发语义

V33 不复制 V32 的 `SELECT ... FOR UPDATE`。删除没有读取、合并和回写旧值的过程，单条 scoped UPDATE
已经让数据库原子决定当前事务是否命中 live row。

预期并发结果：

- 两个 DELETE 同时竞争同一 Agent：一个更新 1 行并成功，另一个在前者提交后更新 0 行并返回 404；
- PATCH 先获得 V32 行锁：DELETE 等待 PATCH 提交，再写入删除时间，最终记录已删除且 PATCH 结果先保留；
- DELETE 先更新：等待中的 PATCH 在 DELETE 提交后无法命中 `deleted_at IS NULL`，返回 404；
- 无论提交顺序如何，PATCH SQL 都不写 `deleted_at`，DELETE SQL 都不写配置，记录不会被复活，也不会产生
  由 DELETE 回写旧配置造成的覆盖。

这些结论需要真实 PostgreSQL 受控并发验收；Mapper SQL shape 测试只能证明语句边界，不能单独冒充数据库
锁等待与提交顺序证据。

## 7. 实现文件与边界

V33 新增：

```text
slice-docs/34_AGENT_APP_SOFT_DELETE_PACKAGE_INTERFACE.md
```

V33 修改：

```text
backend/src/main/java/com/agentflow/agent/controller/AgentAppController.java
backend/src/main/java/com/agentflow/agent/service/AgentAppService.java
backend/src/main/java/com/agentflow/agent/repository/AgentAppMapper.java
backend/src/test/java/com/agentflow/agent/controller/AgentAppControllerTest.java
backend/src/test/java/com/agentflow/agent/service/AgentAppServiceTest.java
backend/src/test/java/com/agentflow/agent/repository/AgentAppMapperTest.java
backend/http/agents.http
```

V33 不新增或修改：

```text
请求 DTO、响应 DTO、AgentApp Entity 字段、ErrorCode、Flyway migration、索引、行锁查询
```

## 8. 验收标准与验收证据

验收至少覆盖：

1. 当前 owner 可软删除自己的未删除 Agent，且成功外壳为 `200 / OK / Agent deleted / data: null`；
2. 数据库只更新 `deleted_at` 与 `updated_at`，两个时间完全相同；
3. owner、status、公开配置、内部 `config` 与 `created_at` 保持不变；
4. `ACTIVE` 和 `DISABLED` Agent 均允许删除；
5. 不存在、跨 owner、已删除和重复删除使用相同 404；
6. 非数字、溢出、零和负数路径 ID 使用约定的 400；
7. 删除后详情与 PATCH 返回 404，列表隐藏该 Agent；
8. 并发 DELETE 只有一个成功；
9. 并发 PATCH/DELETE 不覆盖配置、不复活记录；
10. 不物理删除，不读取全局 ID 判断 owner；
11. 只写 `agent_app`，不创建工具、LLM、RAG、task、step 或 Trace 数据；
12. schema version 仍为 16，不创建空 V17；
13. V30–V32 Agent、V27–V29 ToolRuntime 与完整 Maven suite 全部回归；
14. 真实 HTTP 执行“创建 → PATCH → DELETE → 详情 404 → PATCH 404 → 列表隐藏 → 重复删除 404”。

### 8.1 自动化测试

2026-09-01 使用 Microsoft OpenJDK 21.0.11 和显式 Mockito `-javaagent` 实际执行：

```text
Agent Controller/Service/Mapper focused: 49/49 passed
Agent package including V16 contract:     53/53 passed
complete backend Maven suite:             431/431 passed
Failures: 0, Errors: 0, Skipped: 0
```

新增测试覆盖 DELETE 路由、成功外壳、路径 ID、统一 404、单次 Mapper 写入、异常影响行数、既有可见性
复用和 scoped SQL shape。完整 suite 包含 V30–V32 Agent 回归、V27–V29 ToolRuntime 及此前全部切片。
这里的 mock 与 SQL shape 测试只证明 Java 分支和静态语句边界，不单独冒充真实数据库或线上并发证据。

### 8.2 PostgreSQL、真实 HTTP 与并发

本轮在 `/private/tmp` 启动一次性 PostgreSQL 18.4 空集群与本地 Spring Boot，使用 local vector adapter、
两名一次性用户和真实 Bearer JWT 请求执行 107 项断言，全部通过：

1. Flyway 成功校验并应用 V1–V16，成功 migration 数与最终 schema version 均为 16；
2. 当前 owner 完成“创建 → PATCH → DELETE → 详情 404 → PATCH 404 → 列表隐藏 → 重复删除 404”；
3. DELETE 请求无 body，成功外壳为 `200 / OK / Agent deleted / data: null`；
4. 软删除后物理行仍存在，`deleted_at` 非空且与 `updated_at` 完全相同；逐列比较确认其他 owner、status、
   公开配置、内部 `config` 与 `created_at` 都不变；
5. `ACTIVE` 和数据库夹具中的 `DISABLED` Agent 均删除成功，DISABLED 的 status 保持不变；
6. 不存在、跨 owner、已删除与重复删除得到相同 `404 / COMMON_NOT_FOUND / Agent not found`；
7. 非数字、超出 `Long`、零与负数路径 ID 得到约定的 400，缺失认证得到 401；
8. 受控双 DELETE 在临时 `BEFORE UPDATE pg_sleep(0.8)` 触发器下稳定重叠，结果恰为一个 200 和一个 404；
9. PATCH-first 场景中 PATCH 200、随后 DELETE 200，PATCH 后的 name/model 配置保留且最终已删除；
10. DELETE-first 场景中 DELETE 200、等待中的 PATCH 404，name/model 未被覆盖且记录没有复活；
11. `tool_call_log` 始终为 0，知识库、文档、chunk、回答与反馈表均未产生行；
12. 临时并发触发器、应用、数据库、用户、数据、验收脚本和存储目录均在验收后停止并清理。

并发触发器只存在于一次性验收数据库，用来稳定制造锁竞争窗口，不属于 V33 实现。PostgreSQL 18.4 高于
当前 Flyway 声明的最新测试版本 17，启动时出现 upgrade recommended 警告；本次结果是本地兼容性、HTTP
契约与锁语义证据，不扩张为生产版本组合、性能或所有隔离级别保证。

`backend/http/agents.http` 已增加可重放的 V33 主流程、重复删除、路径错误与鉴权检查；该文件本身只是
验收输入。上述 107 项断言来自本轮另行执行的一次性验收脚本，不把 `.http` 文件本身当作执行证据。

验收没有调用 Qdrant、embedding、LLM provider、内置工具或 AgentEngine，也不证明删除已处理尚未建模的
未来绑定或执行数据。

## 9. 明确不做

- 物理删除、恢复、批量删除或管理员跨 owner 删除；
- 删除原因、保留期限或审计日志；
- 修改、启用或禁用 `status`；
- Prompt 历史、知识库或工具绑定清理；
- 级联删除未来 conversation、task、step、Trace 或调用日志；
- LLM Gateway、AgentEngine、SSE；
- 前端删除按钮或确认弹窗；
- migration、索引、触发器、队列或后台清理任务；
- 将本地 mock、SQL shape 或隔离数据库验收表述为线上能力或生产并发证明。

未来绑定、运行任务和 Trace 的生命周期、外键策略与审计要求尚未冻结。V33 只删除当前已经存在的根资源
可见性，不凭空设计未来表的级联或清理语义。

## 面试问题与回答

### 问题 1：为什么直接做 scoped UPDATE，而不是先查询再删除？

回答：删除只需要判断“这个 ID 是否属于当前 JWT owner 且尚未删除”，这三个条件可以在一条 UPDATE 的
WHERE 中原子表达。先查询会增加一次往返并引入查询命中后、更新前状态变化的窗口，还可能诱使代码先读取
全局 ID 再暴露 owner 差异。V33 用影响行数统一表达成功或不可见；0 行就是相同 404，异常多行是内部一致性
错误。

### 问题 2：为什么使用软删除而不是物理删除？

回答：`agent_app` 的既有数据模型明确提供 `deleted_at`，V30–V32 也已统一按 live-row predicate 控制可见性。
软删除能保留根资源身份和未来审计/关联处理空间，同时立即从公开路径隐藏记录。V33 没有冻结恢复、保留期或
未来关联清理，所以不能把这些潜在能力写成已完成。

### 问题 3：为什么 `DISABLED` Agent 仍允许删除？

回答：`DISABLED` 是可见 live row 的运行状态，删除是独立生命周期动作。若 DELETE 增加
`status = 'ACTIVE'`，禁用后的 Agent 反而无法删除，并把两个本应独立的动作错误耦合。V33 SQL 不读取也不
修改 status，因此 ACTIVE 和 DISABLED 使用同一 owner/live 删除规则。

### 问题 4：PATCH 与 DELETE 并发时如何避免记录复活？

回答：V32 PATCH 在事务内用 owner/live `SELECT ... FOR UPDATE` 锁行，并且 UPDATE 不写 `deleted_at`；V33
DELETE 是 owner/live 单条 UPDATE，也不写配置。PATCH 先锁时 DELETE 等待并最终删除；DELETE 先完成时 PATCH
无法再读到 live row，只能返回 404。两条路径都没有把旧 `deleted_at` 回写为 NULL 的能力，因此不会复活。
最终并发结论以真实 PostgreSQL 受控验收为准。

### 问题 5：为什么 V33 不清理未来绑定、任务和 Trace 数据？

回答：当前 schema 只有 `agent_app` 根资源，Agent 的 Prompt 历史、知识库/工具绑定、执行 task/step 与 Trace
生命周期尚未进入本切片，也没有冻结外键、保留期或审计契约。现在猜测级联清理会扩大范围并可能破坏未来
审计要求。V33 只证明根资源软删除与现有公开可见性；后续表出现时再用独立切片定义其删除策略。
