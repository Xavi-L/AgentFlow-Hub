# AgentFlow Hub Tool：V28 数据库定义驱动的 `payment_log_query` 闭环

> 当前状态：V28 的 V14 migration、受限参数校验、Java Handler、代码 allowlist、自动化测试、
> `.http` 验收输入和本切片契约均已实现。JDK 21 与 Mockito javaagent 下，36 个聚焦测试及
> 362 个完整 backend 测试全部通过。Spring Boot/Flyway 已在隔离 PostgreSQL 18.4 空库顺序
> 应用 V1–V14；登录后的真实 HTTP 与数据库验收通过 64 项断言，最终形成
> `SUCCESS=5 / REJECTED=8 / FAILED=1 / TOTAL=14`。这些是当前 checkout 的本地、mock、
> SQL-shape、实库与 HTTP 证据，不外推生产并发、外部支付系统或完整 M3 能力。

V28 正式建立 M3 的第二个数据库定义驱动内置工具闭环：
`payment_log_query + PaymentLogQueryToolHandler`。它复用 V27 已有的 `ToolRuntime`、工具定义发现、
调用日志与 HTTP 入口，并直接调用 V26 `DemoPaymentLogService` 从 PostgreSQL 查询支付日志。

V27 已由独立提交 `44f1e80 feat(tool): add order query runtime` 建立基线。V28 只新增
`V14__add_payment_log_query_tool.sql`，不修改已应用的 V12、V13，也不创建新表或新增 demo fixture。

## 1. 目标、范围与完成定义

本切片支持以下三类有效参数：

```json
{"orderNo":"order_1024"}
```

```json
{"errorCode":"E_PAY_TIMEOUT"}
```

```json
{"orderNo":"order_1024","errorCode":"E_PAY_TIMEOUT","limit":1}
```

完成链路为：

```text
PostgreSQL tool_definition(V14 payment_log_query)
  -> ToolDefinitionService
  -> ToolArgumentValidator(input_schema + 顶层 required-only anyOf)
  -> ToolCallLogService(REJECTED 或 RUNNING)
  -> BuiltinToolExecutor 精确 allowlist
  -> PaymentLogQueryToolHandler
  -> DemoPaymentLogService.query(orderNo, errorCode, limit)
  -> MockPaymentLogMapper
  -> PostgreSQL mock_payment_log
  -> PaymentLogQueryToolData
  -> ToolExecutionResult
  -> ToolCallLogService(SUCCESS / FAILED)
```

V28 的关键完成点不是单独增加一个查询方法，而是证明两个不同的 PostgreSQL 工具定义都能经过同一个
`ToolRuntime`、同一个日志生命周期和同一个 HTTP 测试入口执行；V27 运行时没有写死为只服务
`order_query` 的一次性链路。

工具定义仍是全局共享资源。现有 Spring Security 要求先登录，但 V28 没有 Agent owner、Agent 工具绑定、
PolicyGuard 或管理员管理语义。独立测试调用的 `task_id`、`step_id` 继续为 SQL `NULL`。

## 2. V14 数据库契约

### 2.1 migration 边界

V14 只包含一次：

```sql
INSERT INTO tool_definition (...)
```

V14 不包含：

- `CREATE TABLE`、`ALTER TABLE` 或 `DROP TABLE`；
- 对 V12、V13 的修改；
- `mock_order`、`mock_payment_log` 新 fixture；
- `report_generate`、`ticket_query`、`knowledge_search`；
- Agent、task、step、Trace 或工具绑定表。

Flyway migration 保持不可变：已经存在的 V12、V13 不为 V28 回写。

### 2.2 固定工具定义

V14 插入一条固定定义：

| 字段 | 固定值 |
| --- | --- |
| `id` | `280000000000000001` |
| `tool_code` | `payment_log_query` |
| `name` | `Payment Log Query` |
| `type` | `BUILTIN` |
| `status` | `ACTIVE` |
| `permission_level` | `MEDIUM` |
| `timeout_ms` | `5000` |
| `retry_count` | `0` |
| `requires_confirmation` | `false` |
| `config` | `{"handler":"paymentLogQueryTool","readonly":true}` |

`MEDIUM` 表示查询业务数据或日志可能涉及敏感信息。`timeout_ms=5000` 目前只是数据库配置；V28 没有
future、定时取消或 TIMEOUT 状态产生。`retry_count=0` 与当前同步执行、无重试行为一致。

### 2.3 输入 schema

V14 固定保存：

```json
{
  "type": "object",
  "properties": {
    "orderNo": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64
    },
    "errorCode": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 20,
      "default": 10
    }
  },
  "anyOf": [
    {"required": ["orderNo"]},
    {"required": ["errorCode"]}
  ],
  "additionalProperties": false
}
```

`default=10` 是定义元数据，不由 validator 改写输入 JSON。省略 `limit` 时 Handler 传入 `null`，由 V26
`DemoPaymentLogService` 应用默认值 10。

### 2.4 输出 schema 与安全字段

V14 的 `output_schema` 描述根对象 `logs` 数组；每个元素只声明：

```text
orderNo, traceId, level, errorCode, message, occurredAt
```

其中 `errorCode` 允许 string 或 null。V28 与 V27 一样不执行 output schema validator；真实输出安全边界由
`DemoPaymentLogResponse` 和 `PaymentLogQueryToolData` 的静态类型保证。

数据库 ID、`createdAt`、用户号、内部排序字段和其他审计字段不进入工具 data。

## 3. `ToolArgumentValidator` 的 V28 增量

### 3.1 保留的基础子集

V28 保留 V27 已实现的：

- `object`、`string`、`integer`；
- `required`；
- `minLength / maxLength`；
- `minimum / maximum`；
- `additionalProperties=false`；
- 上述能力的嵌套 object 递归；
- positive `minLength` 下对 whitespace-only string 的拒绝。

### 3.2 唯一新增的组合能力

V28 只支持根 schema 的 `anyOf`，且必须同时满足：

1. 根 schema 的 `type` 是 `object`；
2. `anyOf` 是非空数组；
3. 每个分支是 JSON object；
4. 每个分支只能有一个关键字：`required`；
5. `required` 是非空、非空白 string 数组；
6. 分支引用的字段必须在根 `properties` 中声明；
7. 至少一个分支的全部 required 字段在参数对象中存在且非 null。

嵌套 `anyOf`，或在分支中混入 `type`、`properties` 等其他关键字，会被视为不受支持的工具定义并
fail closed。V28 没有实现通用 `oneOf/anyOf` 求值、分支内 schema、`allOf`、`format`、`pattern`、条件
schema 或完整 JSON Schema draft。

参数值仍要经过各自 property schema。因而有 `orderNo` 但值为空白、类型错误或超过 64 个 Unicode code
point 时，不会因为 anyOf 的“字段存在”而放行。

### 3.3 Runtime 拒绝位置

`DefaultToolRuntime` 的顺序仍为：

```text
解析 ACTIVE 定义
  -> 校验全部参数
  -> 参数不合法：直接写 REJECTED
  -> 参数合法：才写 RUNNING 并执行 Handler
```

因此 `{}`、`{"limit":10}`、空白、类型错误、超长、越界和多余字段都映射为：

```text
HTTP 400 / TOOL_ARGUMENT_INVALID / tool_call_log.REJECTED
```

它们不会先形成虚假 `RUNNING`，也不会被 V26 Service 的防御性检查误分类为 `FAILED`。

## 4. Handler、allowlist 与 V26 复用

### 4.1 精确 allowlist

`BuiltinToolExecutor` 现在只接受两个代码所有的二元组：

```text
order_query       + orderQueryTool
payment_log_query + paymentLogQueryTool
```

只有 tool code 和数据库 `config.handler` 同时精确匹配，才调用对应构造器注入的 Handler。数据库不能按
任意 Bean 名、类名或反射扩展执行能力。错误 code/handler 组合、未知 handler 或非 `BUILTIN` 定义都进入
现有受控未知执行异常路径。

### 4.2 直接调用 Service

`PaymentLogQueryToolHandler.execute(arguments)` 只提取：

```text
orderNo, errorCode, limit
```

随后直接调用：

```java
demoPaymentLogService.query(orderNo, errorCode, limit)
```

Handler 不回调本应用 Demo HTTP，不访问 Mapper，不硬编码日志内容。V26 Service 负责：

- 对可选 string 做 strip；
- 缺省 `limit=10`；
- 防御性检查至少一个过滤条件和 limit 范围；
- 委托既有 Mapper；
- 映射为六字段 `DemoPaymentLogResponse`。

Mapper 在 PostgreSQL 中参数化执行：

```sql
WHERE order_no = ?
  AND error_code = ?
ORDER BY occurred_at DESC, id DESC
LIMIT ?
```

只提供一个条件时只生成对应 predicate；两个条件同时存在时使用 AND。合法无匹配记录返回空 list，不抛
not found。

### 4.3 标准输出

单条固定日志的 `ToolExecutionResult.data` 为：

```json
{
  "logs": [
    {
      "orderNo": "order_1024",
      "traceId": "pay-trace-1024",
      "level": "ERROR",
      "errorCode": "E_PAY_TIMEOUT",
      "message": "Payment gateway response timeout after 3000ms",
      "occurredAt": "2026-05-01T12:00:00+08:00"
    }
  ]
}
```

合法无匹配结果固定为：

```json
{"logs":[]}
```

两者都是 `success=true`。summary 只报告匹配条数，不代替结构化 data。

## 5. HTTP 与日志契约

V28 复用既有入口，不新增路由：

```http
GET /api/v1/tools
POST /api/v1/tools/{toolId}/test
```

`GET /tools` 返回 V13 `order_query` 和 V14 `payment_log_query` 两个 ACTIVE、未删除工具。两个 BIGINT ID
都以十进制 string 输出；安全 DTO 仍不返回 `config`、handler 或审计字段。

场景矩阵：

| 场景 | HTTP / code | 日志 |
| --- | --- | --- |
| 按 `orderNo` 查询 | 200 / `OK` | `SUCCESS` |
| 按 `errorCode` 查询 | 200 / `OK` | `SUCCESS` |
| 两个条件同时提供 | 200 / `OK`，SQL 使用 AND | `SUCCESS` |
| 合法但无匹配 | 200 / `OK`，`{"logs":[]}` | `SUCCESS` |
| 两个过滤条件都缺失 | 400 / `TOOL_ARGUMENT_INVALID` | `REJECTED` |
| 仅提供 `limit` | 400 / `TOOL_ARGUMENT_INVALID` | `REJECTED` |
| 空白、类型错误、超长、limit 越界、多余字段 | 400 / `TOOL_ARGUMENT_INVALID` | `REJECTED` |
| 工具不存在、DISABLED 或软删除 | 404 / `TOOL_NOT_FOUND` | 不写 |
| 未知执行异常 | 500 / `TOOL_EXECUTION_FAILED` | `FAILED` |
| malformed body | 400 / `COMMON_REQUEST_BODY_INVALID` | 不写 |
| 未登录 | 401 / 既有认证 code | 不写 |

V28 不新增日志状态。当前 Java 仍只实际产生 `RUNNING / SUCCESS / REJECTED / FAILED`；实测请求结束后没有
遗留 RUNNING。`PENDING / TIMEOUT` 只是 V13 数据库词汇。

## 6. 文件与接口变更

新增：

- `backend/src/main/resources/db/migration/V14__add_payment_log_query_tool.sql`；
- `backend/src/main/java/com/agentflow/tool/PaymentLogQueryToolHandler.java`；
- `backend/src/main/java/com/agentflow/tool/PaymentLogQueryToolData.java`；
- `backend/src/test/java/com/agentflow/tool/PaymentLogQueryToolHandlerTest.java`；
- `backend/src/test/java/com/agentflow/tool/V14PaymentLogQueryToolMigrationContractTest.java`；
- `slice-docs/29_PAYMENT_LOG_QUERY_TOOL_PACKAGE_INTERFACE.md`。

修改：

- `ToolArgumentValidator`：增加严格根级 required-only `anyOf`；
- `BuiltinToolExecutor`：增加第二个精确 allowlist 二元组；
- 既有 Runtime、Controller、definition、validator 和 executor 测试；
- `backend/http/tools.http`：增加两工具发现和 V28 手工验收请求。

未修改：

- V12、V13 migration；
- V26 demo fixture、Service、Mapper 与 DTO；
- 现有 HTTP route；
- V13 `tool_call_log` 表和生命周期约束。

## 7. 实现与验收证据

### 7.1 聚焦自动化测试

JDK 21 与 Mockito `-javaagent` 下，以下聚焦组合为 36/36 通过：

- `ToolArgumentValidatorTest`；
- `BuiltinToolExecutorTest`；
- `PaymentLogQueryToolHandlerTest`；
- `DefaultToolRuntimeTest`；
- `ToolControllerTest`；
- `ToolDefinitionServiceTest`；
- `V14PaymentLogQueryToolMigrationContractTest`。

覆盖内容包括：

- orderNo-only、errorCode-only、两者同时提供和缺省 limit；
- 无分支匹配、limit-only、空白、类型、长度、范围与额外字段拒绝；
- 嵌套或非 required-only anyOf 定义 fail closed；
- 两个 code + handler 精确路由，不交叉执行；
- Handler 对 V26 Service 的直接参数委托；
- 六字段日志 DTO、ISO offset 时间和空数组；
- Runtime 在 RUNNING 前记录 REJECTED；
- 两工具列表、string ID 与安全 HTTP DTO；
- V14 只插入一个定义且不修改表或 demo fixture。

这些测试包含 Mockito 与 SQL 文本/shape 证据，不单独视为真实 PostgreSQL 或完整 Spring Security 验收。

### 7.2 完整 Maven suite

同一 JDK 21 与 Mockito javaagent 下，完整 backend suite 为：

```text
Tests run: 362, Failures: 0, Errors: 0, Skipped: 0
```

### 7.3 PostgreSQL 18.4 空库迁移

一次性隔离 cluster 从 `<< Empty Schema >>` 启动，Spring Boot/Flyway：

1. validate 14 个 migration；
2. 顺序执行 V1、V2……V14；
3. 成功应用 14 个 migration；
4. 最终 schema version 为 14；
5. `tool_definition` 恰有 V13、V14 两条定义；
6. V14 fixture、anyOf 分支、默认 limit 与 V12 支付日志来源均由 SQL 断言核验。

Flyway 对 PostgreSQL 18.4 输出“当前版本测试到 PostgreSQL 17”的升级建议警告；这不影响本次实际迁移成功，
但本地成功不等于该版本组合已有 Flyway 官方生产兼容保证。

### 7.4 登录后的真实 HTTP 与数据库日志

运行中的应用连接上述隔离库，在端口 18081 注册一次性用户、登录并执行 curl + jq + psql 验收。最终 64 项
断言全部通过：

- 未登录 discovery 为 401；
- 登录后列表恰有两个安全工具，ID 均为 string；
- `order_query` 仍经共享 Runtime 成功执行一次；
- payment 按 orderNo、按 errorCode、双条件匹配均返回固定 PostgreSQL 日志；
- 双条件中故意使用不匹配 errorCode 返回空数组，证明真实链路使用 AND；
- 缺过滤条件、limit-only、空白、类型、超长、limit 两端越界和额外字段形成 8 条 REJECTED；
- 不存在、malformed、未登录、临时 DISABLED 和临时软删除均不增加日志；
- 临时把 payment handler 改为未知值后得到安全 500 和一条 FAILED，随后恢复原定义；
- 14 条调用的 task/step 全为 NULL、retry count 全为 0，且没有遗留 RUNNING/PENDING/TIMEOUT；
- 4 条 payment SUCCESS 日志都保存结构化 `data.logs` 数组；
- FAILED 的持久化 result 不含注入的未知 handler 文本。

最终实库计数为：

```text
SUCCESS=5, REJECTED=8, FAILED=1, TOTAL=14
order_query=1, payment_log_query=13
```

`backend/http/tools.http` 是可重复的 IDEA HTTP Client 输入；本次真实 HTTP 证据来自独立 curl/jq/psql
断言，不把“脚本已准备”写成“IDEA 文件已逐项点击”。隔离数据库、应用与临时账号仅证明本地闭环。

## 8. 明确不做

- `report_generate`；留给下一独立切片；
- `ticket_query`、`knowledge_search`；
- 修改或新增 demo fixture；
- Agent、Agent CRUD、AgentEngine、工具绑定或默认 Agent；
- PolicyGuard、调用预算、人工确认；
- 完整 `oneOf/anyOf`、`allOf`、format、pattern、条件 schema 或输出 schema 校验；
- 真正超时、重试、取消及 RUNNING 恢复；
- 工具 CRUD、启停管理或日志查询接口；
- 前端工具列表、详情抽屉或测试面板；
- SSE、Trace、task/step 表及外键；
- HTTP/MCP 工具、动态 Bean、类名或反射执行；
- LLM、RAG、RabbitMQ、MinIO、Qdrant 或真实外部支付服务验收。

因此，V28 的完成点是第二个 PostgreSQL 定义驱动的内置只读工具已经复用统一 Runtime、日志与 HTTP 入口，
不是 M3 或整个工具平台已经完成。

## 面试问题与回答

### 问题 1：为什么把“orderNo 或 errorCode 至少一个”放在 validator，而不是 Handler 或 Service？

**回答：** Runtime 只有在参数校验通过后才写 RUNNING。若依赖 V26 Service 检查，缺少过滤条件会在执行阶段抛
`COMMON_PARAM_INVALID`，被记录成 RUNNING→FAILED，错误地表示 Handler 已接收了一次有效调用。V28 的根级
required-only anyOf 让这些请求直接形成 `TOOL_ARGUMENT_INVALID / REJECTED`，实库验收的 missing 和 limit-only
都证明没有伪造执行失败。

### 问题 2：V28 的 anyOf 为什么不直接实现成通用 JSON Schema 引擎？

**回答：** 当前只有一个明确需求：两个顶层过滤字段至少一个存在。V28 将能力限制为根 object、非空 anyOf、
每分支仅含 required，并拒绝嵌套和扩展分支。这样实现范围、失败边界和测试矩阵可审查；复杂组合、format、pattern
和完整 draft 兼容未纳入本切片，后续若需要应采用独立 schema 引擎或切片评估。

### 问题 3：如何证明 payment_log_query 不是 Handler 硬编码 fixture？

**回答：** Handler 单元测试验证它把三个参数直接委托 `DemoPaymentLogService.query`；V26 Mapper 测试验证双条件
生成 AND、稳定排序和参数化 limit；本次实库 HTTP 又从 V12 `mock_payment_log` 取到固定行，并用不匹配的双条件
得到空数组。三层证据共同证明当前 checkout 的 PostgreSQL-backed demo 链路，但不代表接入真实支付平台。

### 问题 4：数据库里的 handler 为什么仍不能动态选择任意 Spring Bean？

**回答：** `config.handler` 只是定义元数据的一部分。`BuiltinToolExecutor` 只接受
`order_query + orderQueryTool` 和 `payment_log_query + paymentLogQueryTool` 两个代码所有的二元组；没有 Bean
名称查找或反射。实库临时未知 handler 验收得到安全 500/FAILED，持久化错误也不泄露注入值。

### 问题 5：为什么无匹配支付日志是 SUCCESS，而不存在订单在 V27 是 FAILED？

**回答：** 两个接口的查询语义不同。`order_query` 查询单个必需资源，不存在时 V26 Service 抛
`COMMON_NOT_FOUND`；`payment_log_query` 是条件集合查询，合法条件下零行是确定且可用的业务结果，所以返回
`{"logs":[]}` 并记录 SUCCESS。将空集合记为 FAILED 会迫使上层把正常无证据与系统故障混为一谈。

### 问题 6：`timeout_ms=5000`、`retry_count=0` 和日志状态当前分别证明了什么？

**回答：** 5000ms 是持久化配置，不是已执行的超时机制；V28 同步调用且不取消 future，也不产生 TIMEOUT。
retry count 为 0，Runtime 没有重试循环。当前实际状态只有校验后的 RUNNING，以及 SUCCESS、REJECTED、FAILED；
实库 14 条调用最终都进入终态且没有 RUNNING 残留，但这不能证明进程崩溃恢复，补偿扫描仍是后续规划。
