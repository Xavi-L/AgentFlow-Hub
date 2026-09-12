# AgentFlow Hub Tool：V27 数据库定义驱动的 `order_query` 运行时闭环

> 当前状态：V27 的 Flyway、Java、自动化测试、切片契约与独立 `.http` 验收脚本均已实现。
> JDK 21 与 Mockito javaagent 下，33 个 V27 聚焦测试及 348 个完整 backend 测试全部通过。
> Spring Boot/Flyway 已在隔离 PostgreSQL 18.4 空库顺序应用 V1–V13；登录后的真实 HTTP/数据库
> 验收完成 47 项断言。基础场景产生 `SUCCESS=1 / REJECTED=4 / FAILED=1`，受控未知-handler
> 故障注入再产生一条安全的 `FAILED`。本地、mock、SQL-shape、实库与 HTTP 证据在下文分别说明，
> 不从这些结果外推生产并发、真实订单系统或 M3 全部完成。

V27 正式建立 M3 的第一个数据库定义驱动内置工具闭环：`ToolRuntime + order_query +
tool_call_log`。它从 PostgreSQL 加载工具定义，依据数据库 `input_schema` 校验参数，通过代码 allowlist
执行 `OrderQueryToolHandler`，直接复用 V26 `DemoOrderService`，并把调用状态、输入、结构化结果、耗时和
安全错误写回 PostgreSQL。

文件编号为 28，功能切片版本为 V27。V26 已由独立提交
`be5b611 feat(demo): add V26 order and payment read data` 建立基线；V27 只新增
`V13__create_tool_definition_and_tool_call_log.sql`，不修改已经存在的 V12 或更早 migration。

## 1. 目标、范围与全局资源语义

本切片只实现一个内置工具：

```text
order_query({"orderNo":"order_1024"})
```

完成条件是证明以下链路真实成立：

```text
PostgreSQL tool_definition
  -> ToolDefinitionService
  -> ToolArgumentValidator(input_schema)
  -> ToolCallLogService(RUNNING / REJECTED)
  -> BuiltinToolExecutor allowlist
  -> OrderQueryToolHandler
  -> DemoOrderService
  -> mock_order
  -> ToolExecutionResult
  -> ToolCallLogService(SUCCESS / FAILED)
```

`tool_definition` 是全局共享资源，不按当前登录用户过滤。Spring Security 仍要求先认证；认证只决定调用者能否
进入工具发现/测试入口，不把用户 ID 拼入工具定义或 V26 demo 订单 SQL。V27 没有 Agent、Agent owner、工具绑定、
PolicyGuard 或管理员工具管理语义。

独立测试调用的 `task_id`、`step_id` 固定写入 SQL `NULL`。V27 不伪造任务或 step ID，也不提前创建
`agent_task`、`agent_step` 表。等这些父表在后续切片落地后，才允许通过更高版本 migration 增加外键。

## 2. V13 数据库契约

### 2.1 `tool_definition`

V13 新建 `tool_definition`：

| 字段 | 约束与 V27 含义 |
| --- | --- |
| `id BIGINT` | 主键；内置 fixture 固定 ID。 |
| `tool_code VARCHAR(128)` | 非空、非空白、全局唯一。 |
| `name VARCHAR(128)` | 非空、非空白；调用日志保存该名称快照。 |
| `description TEXT` | 非空、非空白。 |
| `type VARCHAR(32)` | 词汇表为 `BUILTIN / HTTP / MCP`；V27 只执行 `BUILTIN`。 |
| `input_schema JSONB` | 非空且必须为 JSON object；由 V27 validator 执行基础校验。 |
| `output_schema JSONB` | 非空且必须为 JSON object；V27 保存结构说明，不做输出 schema validator。 |
| `config JSONB` | 非空且必须为 JSON object；V27 只读取 allowlist handler。 |
| `timeout_ms INTEGER` | 必须大于 0；V27 只保存配置，不执行异步超时。 |
| `retry_count INTEGER` | 必须非负；fixture 为 0，Runtime 也不重试。 |
| `requires_confirmation BOOLEAN` | 非空；fixture 为 false，V27 不实现人工确认。 |
| `permission_level VARCHAR(32)` | `LOW / MEDIUM / HIGH`；订单查询采用语义定义中的 `MEDIUM`。 |
| `status VARCHAR(32)` | `ACTIVE / DISABLED`。 |
| `created_at / updated_at / deleted_at` | 审计与软删除字段；不进入 HTTP DTO。 |

唯一约束为：

```text
uk_tool_definition_tool_code(tool_code)
```

### 2.2 唯一 `order_query` fixture

V13 只插入一条工具定义：

| 字段 | 固定值 |
| --- | --- |
| `id` | `270000000000000001` |
| `tool_code` | `order_query` |
| `name` | `Order Query` |
| `type` | `BUILTIN` |
| `status` | `ACTIVE` |
| `permission_level` | `MEDIUM` |
| `timeout_ms` | `3000` |
| `retry_count` | `0` |
| `requires_confirmation` | `false` |
| `config` | `{"handler":"orderQueryTool","readonly":true}` |

`input_schema` 只有 `orderNo` 一个 property：

```json
{
  "type": "object",
  "properties": {
    "orderNo": {
      "type": "string",
      "description": "Demo order number, for example order_1024",
      "minLength": 1,
      "maxLength": 64
    }
  },
  "required": ["orderNo"],
  "additionalProperties": false
}
```

`output_schema.properties` 恰好只包含 V26 的六个安全订单字段：

```text
orderNo, amount, currency, status, paymentStatus, errorCode
```

其中 `errorCode` 允许 string 或 null，以覆盖后续可能出现的非失败订单；V27 不把 output schema 执行结果校验
扩进本切片。

### 2.3 `tool_call_log`

V13 新建 `tool_call_log`：

| 字段 | 约束与含义 |
| --- | --- |
| `id BIGINT` | 服务端生成的主键。 |
| `task_id / step_id BIGINT` | 均可空，V27 不建立父表外键。 |
| `tool_id BIGINT` | 非空，外键指向当前已存在的 `tool_definition(id)`。 |
| `tool_code / tool_name` | 非空、非空白的调用时快照。 |
| `arguments JSONB` | 非空；参数拒绝也保留已解析的原始参数。 |
| `result JSONB` | `RUNNING/PENDING` 为空；终态必须为结构化 JSON object。 |
| `status VARCHAR(32)` | `PENDING / RUNNING / SUCCESS / FAILED / TIMEOUT / REJECTED`。 |
| `retry_count INTEGER` | 非负；V27 始终写 0。 |
| `latency_ms INTEGER` | 可空或非负；所有终态必须非空。 |
| `error_code / error_message` | 成功为空；失败、超时和拒绝必须非空且非空白。 |
| `started_at / finished_at` | 由 lifecycle CHECK 约束运行态与终态形状。 |
| `created_at` | 非空调用创建时间。 |

`ck_tool_call_log_terminal_fields` 固定下列状态形状：

| 状态 | `started_at` | `finished_at/latency/result` | 错误字段 |
| --- | --- | --- | --- |
| `PENDING` | 空 | 全空 | 空 |
| `RUNNING` | 非空 | 全空 | 空 |
| `SUCCESS` | 非空 | 全非空，result 为 object | 空 |
| `FAILED/TIMEOUT/REJECTED` | 非空 | 全非空，result 为 object | code/message 非空白 |

V27 的 Java 运行时只产生 `RUNNING / SUCCESS / REJECTED / FAILED`。`PENDING / TIMEOUT` 只是数据库状态词汇，
不代表已经实现排队、真正超时或超时恢复。

V13 建立三个指定索引：

```text
idx_tool_call_task(task_id)
idx_tool_call_step(step_id)
idx_tool_call_tool(tool_id, created_at DESC)
```

## 3. Java 包接口与执行责任

V27 在 `com.agentflow.tool` 建立以下核心类型：

| 类型 | 责任 |
| --- | --- |
| `ToolRuntime` / `DefaultToolRuntime` | 统一入口、固定执行顺序、错误分类与结果标准化。 |
| `ToolDefinitionService` | 只加载 `ACTIVE AND deleted_at IS NULL` 的全局定义。 |
| `ToolArgumentValidator` | 执行 V27 基础 JSON Schema 子集。 |
| `BuiltinToolHandler` | 代码内置 handler 接口。 |
| `BuiltinToolExecutor` | 显式 allowlist router；不反射、不按 Bean 名动态查找。 |
| `OrderQueryToolHandler` | 取 `orderNo` 后直接调用 V26 `DemoOrderService`。 |
| `ToolCallLogService` | 用独立事务创建/终结调用日志。 |
| `ToolExecutionCommand` | 承载 tool ID、可空 task/step ID 与 arguments。 |
| `ToolExecutionResult` | 标准化 success、summary、data、安全错误与 latency。 |

### 3.1 定义加载与全局可见性

`ToolDefinitionMapper` 的单条和列表 SQL 都把可见性放在同一查询：

```sql
WHERE status = 'ACTIVE'
  AND deleted_at IS NULL
```

按 ID 执行时再增加 `id = #{toolId}`。禁用、软删除和不存在统一映射为
`TOOL_NOT_FOUND / HTTP 404`，且不做第二次存在性探测、不写 `tool_call_log`。列表固定按
`created_at ASC, id ASC` 排序，不接收 owner 参数。

### 3.2 V27 schema validator 子集

`ToolArgumentValidator` 只承诺：

- `object`、`string`、`integer`；
- `required`；
- `minLength / maxLength`；
- `minimum / maximum`；
- `additionalProperties=false`；
- 上述能力的嵌套 object 递归。

required string 在 `minLength > 0` 时额外把 whitespace-only 视为空，使 `"   "` 在 handler 前成为
`TOOL_ARGUMENT_INVALID / REJECTED`，而不是误记为执行失败。长度按 Unicode code point 计算。

`oneOf`、`anyOf`、`allOf`、`format`、`pattern`、条件 schema、自定义关键字和完整 JSON Schema draft
兼容均未纳入。遇到 V27 不支持的声明 type 时 validator fail closed 为服务端定义错误，不静默放行。

### 3.3 allowlist 与 V26 Service 复用

数据库 `config.handler` 不能驱动反射或任意 Bean/类执行。V27 的 router 只接受一个明确二元组：

```text
toolCode = order_query
handler  = orderQueryTool
```

只有二者同时匹配才调用注入的 `OrderQueryToolHandler`。未知 handler、错误 tool code 或非 `BUILTIN` 类型都
进入受控未知执行异常路径。

`OrderQueryToolHandler` 不硬编码 `199.00 CNY / CREATED / PAY_FAILED / E_PAY_TIMEOUT`，也不通过 HTTP
回调本应用；它调用 `DemoOrderService.getByOrderNo`，由 V26 mapper 从 PostgreSQL `mock_order` 读取并映射
`DemoOrderResponse`。因此工具 data 沿用 V26 六字段安全 DTO，不包含数据库 ID、`userNo` 或审计时间。

### 3.4 日志事务边界

`ToolCallLogService` 的每个写方法使用 `REQUIRES_NEW`：

1. 参数合法时先提交 `RUNNING`；
2. handler 成功后只允许 `WHERE id = ? AND status = 'RUNNING'` 更新成 `SUCCESS`；
3. 已知业务错误和未知执行异常同样从 `RUNNING` 更新成 `FAILED`；
4. 参数拒绝直接插入完整 `REJECTED` 终态，不制造一次虚假的 RUNNING 执行；
5. 日志插入或终态更新影响行数不是 1 时 fail closed。

这样 `DemoOrderService` 抛出 `COMMON_NOT_FOUND` 后，即使 HTTP 链继续抛异常，已经独立提交的 FAILED 日志不会被
调用方事务一起回滚。真正的崩溃恢复、RUNNING 残留扫描和补偿未纳入 V27。

## 4. HTTP 契约

两个入口都需要登录：

```http
GET /api/v1/tools

POST /api/v1/tools/{toolId}/test
Content-Type: application/json

{
  "arguments": {
    "orderNo": "order_1024"
  }
}
```

### 4.1 工具列表安全 DTO

`GET /tools` 只返回 ACTIVE、未删除定义。每项允许：

```text
id, toolCode, name, description, type,
inputSchema, outputSchema,
timeoutMs, retryCount, requiresConfirmation,
permissionLevel, status
```

`id` 以十进制 string 返回，因为固定 BIGINT 已超过 JavaScript safe integer；数据库和 Java Runtime 内部仍使用
BIGINT/Long，Controller path 仍绑定为 Long。这样浏览器发现 ID 后不会因 number 舍入而调用错误路径。

明确不返回 `config`、`config.handler`、`createdAt`、`updatedAt` 或 `deletedAt`。

### 4.2 测试结果

成功响应为 HTTP 200 / `OK`，`data` 是标准化 `ToolExecutionResult`：

```json
{
  "success": true,
  "toolCode": "order_query",
  "summary": "Order order_1024 is CREATED with payment status PAY_FAILED, amount 199.00 CNY and error code E_PAY_TIMEOUT.",
  "data": {
    "orderNo": "order_1024",
    "amount": 199.00,
    "currency": "CNY",
    "status": "CREATED",
    "paymentStatus": "PAY_FAILED",
    "errorCode": "E_PAY_TIMEOUT"
  },
  "latencyMs": 0
}
```

`latencyMs` 是本次同步调用实测值，示例中的 0 只表示一次不足 1ms 的本地调用，不是固定值。

### 4.3 错误与日志映射

| 场景 | HTTP / code | 日志 |
| --- | --- | --- |
| `order_1024` 成功 | 200 / `OK` | `SUCCESS` |
| 缺失、类型错误、空白、超长或多余参数 | 400 / `TOOL_ARGUMENT_INVALID` | `REJECTED` |
| 订单不存在 | 404 / `COMMON_NOT_FOUND` | `FAILED` |
| 工具不存在、禁用或已软删除 | 404 / `TOOL_NOT_FOUND` | 不写 |
| allowlist/handler 等未知执行异常 | 500 / `TOOL_EXECUTION_FAILED` | `FAILED` |
| 请求体 JSON 无法解析 | 400 / `COMMON_REQUEST_BODY_INVALID` | 不写 |
| 未登录 | 401 / 既有认证 code | 不写 |

只有已解析并成功解析出可见工具定义的调用才属于工具调用。Controller 不调用 `DemoOrderService`，所有 test 请求
必须经过 `ToolRuntime`。

## 5. 日志快照与安全错误

每条 V27 日志保存：

- `tool_id` 与调用时 `tool_code / tool_name` 快照；
- 原始已解析 `arguments` JSON；
- 完整结构化 `ToolExecutionResult` JSON；
- 终态、固定 `retry_count=0`、耗时和起止时间；
- 失败时的稳定业务 code 与可安全展示 message。

订单不存在保留 `COMMON_NOT_FOUND / Resource not found`，让 HTTP 契约与日志分类一致。未知异常的服务端日志可以
保留堆栈用于诊断，但 HTTP、`tool_call_log.error_message` 和结构化 result 只保存
`TOOL_EXECUTION_FAILED / Tool execution failed`，不持久化内部异常、类名、handler 名或数据库细节。

## 6. 实现与验收证据

### 6.1 自动化测试

JDK 21 与 Mockito `-javaagent` 下已运行：

```text
V27 focused suite: 33/33 passed
complete backend suite: 348/348 passed
```

33 个 V27 测试覆盖：

- validator 的 object/string/integer、required、空白、长度、数值上下界和额外字段；
- handler 对 V26 `DemoOrderService` 的直接委托和六字段 DTO；
- tool code + handler 双重 allowlist 及未知 handler 拒绝；
- Runtime 的 SUCCESS、REJECTED、COMMON_NOT_FOUND FAILED、未知 FAILED 和工具不存在不记日志；
- handler 成功后的日志终结失败不会被误记成 handler FAILED；
- 日志 snapshot、NULL task/step、RUNNING→终态 SQL guard；
- ACTIVE/未删除定义 SQL 和无 owner 列表 SQL；
- Controller 的安全 DTO、ToolRuntime 委托、专用错误码和 malformed body 不进入 Runtime；
- V13 migration 的表、fixture、索引、状态词汇与不创建 Agent 父表边界。

这些测试包含 mock 与 SQL-shape 证据，不单独冒充 PostgreSQL 或完整 Spring Security 验收。

### 6.2 PostgreSQL 18.4 空库

在 `/private/tmp` 新建隔离 PostgreSQL 18.4 cluster 后，运行中的 Spring Boot/Flyway：

1. 从 `<< Empty Schema >>` 开始；
2. 成功 validate 13 个 migration；
3. 顺序执行 V1、V2……V13；
4. `flyway_schema_history` 最终有 13 条 success=true，schema version 为 13；
5. catalog 核验唯一 fixture、三个指定索引、tool 外键、无 task/step 外键与 44 项表约束/非空约束；
6. 回滚式负例确认重复 `tool_code`、负 definition/call retry count、无 result 的 SUCCESS、带终态字段的
   RUNNING 均被数据库拒绝。

运行时 Flyway 对 PostgreSQL 18.4 输出了“当前 Flyway 已测试到 PostgreSQL 17”的版本建议警告；这不影响本次
实际 migration 成功证据，但本地成功也不等于该组合已经获得 Flyway 官方生产兼容保证。

### 6.3 登录后的真实 HTTP 与日志

运行中的应用连接上述隔离库，在端口 18080 完成一次注册/登录并执行 47 项 curl + jq + psql 断言：

- 未登录工具列表/测试均为 401；
- 登录后列表只含一个安全 `order_query` 定义；
- `order_1024` 返回 `199.00 CNY / CREATED / PAY_FAILED / E_PAY_TIMEOUT`；
- 缺失、数字类型、纯空白和额外字段分别返回 400，并形成 4 条 REJECTED；
- `order_missing` 返回 COMMON_NOT_FOUND，并形成 1 条 FAILED；
- 不存在工具、malformed JSON 和未认证测试均不增加日志；
- 隔离库临时改为 DISABLED 和临时设置 `deleted_at` 后均返回 TOOL_NOT_FOUND，且日志数不变，随后立即恢复；
- 隔离库临时把 handler 改为未知值后返回安全 500 并形成 1 条额外 FAILED，日志/result 不含注入的 handler，
  随后立即恢复。

基础契约场景在故障注入前的日志计数为：

```text
SUCCESS=1, REJECTED=4, FAILED=1, TOTAL=6
```

增加未知-handler 故障注入后的最终隔离库计数为：

```text
SUCCESS=1, REJECTED=4, FAILED=2, TOTAL=7
```

实库断言还确认 7 条独立调用的 task/step 全为 NULL、retry count 全为 0、code/name 快照一致、所有已观察行均为
完整终态，以及 SUCCESS result 保存了结构化订单 data。

`backend/http/tools.http` 已准备为可重复 IDEA HTTP Client 输入，但本次没有逐项点击该文件；真实 HTTP 证据来自
上述 curl 断言。隔离 PostgreSQL、应用、临时账号和故障注入不能证明生产并发、生产认证或真实订单系统可用性。

## 7. 明确不做

- `payment_log_query`；留给下一独立切片；
- `report_generate`、`ticket_query`、`knowledge_search`；
- Agent、Agent CRUD、AgentEngine、Agent 绑定或默认 Agent；
- `agent_task`、`agent_step`、对话、任务、Episode 或 Trace 查询；
- PolicyGuard、`policy_check_log`、调用预算或人工确认；
- SSE `TOOL_STARTED / TOOL_FINISHED`；
- 真正的超时、重试、取消、队列、TIMEOUT 产生或 RUNNING 残留恢复；
- 工具创建、修改、启停、删除或管理员管理 API；
- 工具调用日志查询 API 或前端工具页；
- HTTP/MCP executor、动态 Bean、类名或反射执行；
- 修改 V12、demo 写接口、新 demo fixture 或真实订单系统接入；
- LLM、RAG、RabbitMQ、MinIO、Qdrant 或外部 provider 验收。

因此，V27 的完成点不是“M3 全部完成”，而是首个 PostgreSQL 定义驱动的内置工具已能被统一运行时发现、校验、
allowlist 执行、标准化，并留下真实调用日志。

## 面试问题与回答

### 问题 1：为什么数据库保存 handler，却不能直接按 handler 名反射 Spring Bean？

**回答：** 数据库定义负责可配置元数据和 schema，执行能力仍必须由代码控制。V27 的
`BuiltinToolExecutor` 只接受 `order_query + orderQueryTool` 这一明确二元组并调用构造器注入的 handler；未知
handler、其他 tool code 或非 BUILTIN 类型全部 fail closed。这样数据库内容不能扩权为任意 Bean/类执行。动态
注册、HTTP/MCP executor 未纳入本切片。

### 问题 2：为什么参数错误直接写 REJECTED，而不是先写 RUNNING 再失败？

**回答：** 参数没有通过数据库 input schema 时，handler 从未开始执行，写 RUNNING 会歪曲 trace。V27 先解析出
ACTIVE 工具，再校验 arguments；校验失败直接持久化结构化 REJECTED。只有校验通过才创建 RUNNING。请求体根本
无法解析、未认证或工具不可见时连“已解析的工具调用”都不是，因此不写日志。

### 问题 3：为什么日志写入使用独立事务？它解决了什么，又没有解决什么？

**回答：** `DemoOrderService` 的 COMMON_NOT_FOUND 会继续向 HTTP 层抛出。如果日志和调用方共享事务，异常传播可能
把 FAILED 一起回滚；`REQUIRES_NEW` 让 RUNNING/终态分别持久化，实库 HTTP 已证明 404 后仍保留 FAILED。它不解决
进程在两次写之间崩溃的问题，RUNNING 残留恢复、租约和补偿属于后续切片。

### 问题 4：`timeout_ms=3000` 和 `retry_count=0` 当前分别意味着什么？

**回答：** 两者目前都是持久化运行配置与契约字段。V27 同步执行，不创建异步 future、定时取消或 retry loop；
Runtime 和日志 retry count 固定为 0，也不会产生 TIMEOUT。把字段落库不等于超时/重试能力已经实现，真正行为留给
后续独立切片。

### 问题 5：如何证明 order_query 的结果不是 handler 硬编码？

**回答：** 单元测试证明 `OrderQueryToolHandler` 委托 `DemoOrderService` 并只映射 V26 六字段；实库链路进一步由
Spring Boot/Flyway 空库创建 V12 mock_order fixture，HTTP 调用后 MyBatis 执行订单查询并得到同一数据，同时
tool_call_log 保存结构化 result。这里能证明的是当前 checkout 的 PostgreSQL-backed demo 闭环，不是外部真实订单
系统、生产并发或通用工具平台。

### 问题 6：为什么 `task_id`、`step_id` 可以为空且暂时没有外键？

**回答：** V27 要支持独立工具测试，但 M4 的 task/step 表尚未落地。伪造父 ID 会制造不可验证的 trace，提前创建整套
Agent 表又会扩大切片。因此字段先可空，独立 HTTP 调用写 NULL；等真实父表出现后，再用更高版本 migration 增加
外键和 Agent 执行关联。`tool_id` 的父表已经存在，所以 V13 立即为它建立真实外键。
