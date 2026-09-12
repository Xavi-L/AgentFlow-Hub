# AgentFlow Hub Demo：V26 模拟订单与支付日志只读数据底座

> 当前状态：V26 的 Flyway、Java、自动化测试、切片契约与独立 `.http` 验收脚本均已实现。JDK 21
> 下 30 个 V26 聚焦测试和 315 个完整 backend 测试全部通过；V1–V12 已由运行中的 Spring Boot/Flyway
> 在隔离 PostgreSQL 18.4 空库顺序应用，并核验表、fixture、索引、唯一约束和 CHECK。运行中本地应用的
> 14 项 curl 断言已覆盖 JWT 注册/登录、未登录 401、成功查询、404、AND/空结果与参数 400。IDEA `.http`
> 文件本身仍是可复跑脚本，未把“脚本已准备”表述为“脚本已点击执行”，也不从本地结果外推生产能力。

V26 正式建立 M3 的第一段只读数据底座：把固定模拟订单和支付日志持久化到 PostgreSQL，并通过两个需要登录的
Demo Business API 暴露安全 DTO。后续 V27 的 `order_query` 与 `payment_log_query` 可以直接复用本切片的两个
Service，而不是返回硬编码字符串或绕行本项目自己的 HTTP 接口。

文件编号为 27，功能切片版本为 V26。V26 只新增
`V12__create_demo_order_payment_data.sql`；已经存在的
`V11__create_knowledge_document_reprocess_task.sql` 及更早 migration 保持不可变。

## 1. 范围、认证与数据归属

V26 新增两个只读入口：

~~~http
GET /api/v1/demo/orders/{orderNo}
Authorization: Bearer <access-token>

GET /api/v1/demo/payment-logs?orderNo=order_1024
Authorization: Bearer <access-token>

GET /api/v1/demo/payment-logs?errorCode=E_PAY_TIMEOUT&limit=10
Authorization: Bearer <access-token>
~~~

Spring Security 的既有默认规则要求两个入口都经过认证；未登录请求沿用现有 401 契约。认证只决定能否进入
Demo API，不成为数据 owner 条件：`mock_order` 与 `mock_payment_log` 是所有已认证用户共享的固定演示数据，查询
SQL 不接收或拼接当前平台用户 ID。

`mock_order.user_no` 是模拟业务系统中的客户编号，不是 `app_user.id`，也不是平台资源 owner。它只保存在内部
持久化模型中，不进入 HTTP DTO。Controller 不接收 `userId`、owner、数据库 ID、排序 ID、创建时间或更新时间。

## 2. HTTP 契约

### 2.1 查询一条模拟订单

成功时返回 HTTP 200、固定消息 `Demo order retrieved`，且 `data` 恰好包含六个字段：

~~~json
{
  "code": "OK",
  "message": "Demo order retrieved",
  "data": {
    "orderNo": "order_1024",
    "amount": 199.00,
    "currency": "CNY",
    "status": "CREATED",
    "paymentStatus": "PAY_FAILED",
    "errorCode": "E_PAY_TIMEOUT"
  },
  "traceId": "...",
  "timestamp": "..."
}
~~~

这里的 `status = CREATED` 是订单状态，`paymentStatus = PAY_FAILED` 才是支付状态；不能因为路线图中的口语化
“订单返回 PAY_FAILED”而把两个字段合并。

路径 `orderNo` 在 Service 中先去除首尾空白；空白或超过数据库字段长度 64 的值返回
`COMMON_PARAM_INVALID` / HTTP 400。规范化后的订单号使用精确等值查询。没有匹配订单时不做额外存在性探测，统一
返回现有通用 404：

~~~json
{
  "code": "COMMON_NOT_FOUND",
  "message": "Resource not found",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
~~~

### 2.2 查询模拟支付日志

支付日志查询接受三个 query parameter：

| 参数 | 是否必需 | 规范化与含义 |
| --- | --- | --- |
| `orderNo` | 与 `errorCode` 至少一个 | 去除首尾空白；空白按未提供处理；非空时按订单号精确匹配；最长 64。 |
| `errorCode` | 与 `orderNo` 至少一个 | 去除首尾空白；空白按未提供处理；非空时按错误码精确匹配；最长 64。 |
| `limit` | 否 | 默认 10，只允许 1–20。 |

两个过滤条件同时出现时必须使用 `AND`，用于进一步缩小结果，不能解释为 OR，也不能执行两次查询后合并。两个条件
在规范化后都为空、任一非空条件超过长度上限，或 `limit` 越界时，统一返回 HTTP 400：

~~~json
{
  "code": "COMMON_PARAM_INVALID",
  "message": "Request parameter is invalid",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
~~~

成功时返回 HTTP 200、固定消息 `Demo payment logs retrieved`，`data` 是数组而不是分页对象。每项恰好包含：

~~~json
{
  "code": "OK",
  "message": "Demo payment logs retrieved",
  "data": [
    {
      "orderNo": "order_1024",
      "traceId": "pay-trace-1024",
      "level": "ERROR",
      "errorCode": "E_PAY_TIMEOUT",
      "message": "Payment gateway response timeout after 3000ms",
      "occurredAt": "2026-05-01T12:00:00+08:00"
    }
  ],
  "traceId": "...",
  "timestamp": "..."
}
~~~

日志固定按 `occurred_at DESC, id DESC` 排序并在数据库查询中应用 `LIMIT`。`id` 只用于同一业务时间下的稳定
tie-break，不进入响应。没有匹配日志不是资源不存在，返回 HTTP 200、`OK` 和空数组 `data: []`。

## 3. Flyway 与确定性 fixture

V12 新建且只新建下列两张表：

| 表 | 字段 |
| --- | --- |
| `mock_order` | `id BIGINT`、`order_no VARCHAR(64)`、`user_no VARCHAR(64)`、`amount NUMERIC(12,2)`、`currency VARCHAR(16)`、`status VARCHAR(32)`、`payment_status VARCHAR(32)`、可空 `error_code VARCHAR(64)`、`created_at TIMESTAMPTZ`、`updated_at TIMESTAMPTZ` |
| `mock_payment_log` | `id BIGINT`、`order_no VARCHAR(64)`、`trace_id VARCHAR(128)`、`log_level VARCHAR(16)`、可空 `error_code VARCHAR(64)`、`message TEXT`、`occurred_at TIMESTAMPTZ`、`created_at TIMESTAMPTZ` |

数据库契约包括：

1. `uk_mock_order_no UNIQUE (order_no)`；
2. `idx_payment_log_order ON mock_payment_log (order_no, occurred_at DESC)`；
3. `idx_payment_log_error ON mock_payment_log (error_code)`；
4. 金额非负、订单号/模拟用户号/trace/message 非空白；
5. `log_level` 只接受 `INFO / WARN / ERROR`；
6. 主键、必要业务字段和时间字段非空；订单或日志的 `error_code` 允许为空，以保留未来表达非失败记录的能力。

本切片不为 `mock_payment_log.order_no` 发明外键或级联删除策略。两张表没有平台 owner、软删除字段、数据库 sequence、
触发器或更新时间自动维护；V26 也没有任何写接口。

V12 插入一组最小、可重复 fixture。固定 ID 和时间确保不同本地数据库能得到相同数据；ID、`user_no` 和审计时间仍
属于内部字段：

| 数据 | 固定值 |
| --- | --- |
| order | `id=260000000000000001`、`order_no=order_1024`、`user_no=demo_user_1024`、`amount=199.00`、`currency=CNY`、`status=CREATED`、`payment_status=PAY_FAILED`、`error_code=E_PAY_TIMEOUT` |
| payment log | `id=260000000000000002`、`order_no=order_1024`、`trace_id=pay-trace-1024`、`log_level=ERROR`、`error_code=E_PAY_TIMEOUT`、`message=Payment gateway response timeout after 3000ms` |
| business time | `occurred_at=2026-05-01T12:00:00+08:00`；fixture 创建/更新时间使用同一个固定时间 |

V12 一旦被 Flyway 应用即不可修改；后续增加 fixture、字段、约束或索引必须使用更高版本 migration。

## 4. 包接口与查询责任

V26 的调用边界固定为：

~~~text
DemoBusinessController
  GET /api/v1/demo/orders/{orderNo}
    -> DemoOrderService.getByOrderNo(orderNo)
         -> normalize and validate orderNo
         -> MockOrderMapper.selectByOrderNo(orderNo)
         -> DemoOrderResponse.from(order)

  GET /api/v1/demo/payment-logs?orderNo&errorCode&limit
    -> DemoPaymentLogService.query(orderNo, errorCode, limit)
         -> trim optional filters; blank becomes absent
         -> require at least one filter; default/bound limit
         -> MockPaymentLogMapper.selectByFilters(orderNo, errorCode, limit)
         -> List<DemoPaymentLogResponse>
~~~

1. `DemoBusinessController` 只做 HTTP 参数转交和 `ApiResponse` 包装。认证由既有 Security filter chain 完成；
   Controller 不把 JWT principal 传给共享 fixture 查询。
2. `DemoOrderService` 与 `DemoPaymentLogService` 都是只读事务边界。它们负责输入规范化、稳定业务错误映射和实体到
   安全 DTO 的转换，后续 V27 可以直接复用。
3. `MockOrderMapper` 只按规范化后的 `order_no` 精确读取一行。
4. `MockPaymentLogMapper` 只接收 Service 已验证的参数；动态 SQL 对两个非空过滤条件使用 AND，并在同一条 SQL 中
   执行 `ORDER BY occurred_at DESC, id DESC LIMIT #{limit}`。
5. Entity 可以包含数据库 ID、`userNo`、`createdAt`、`updatedAt` 等内部字段，但 Controller 不直接序列化 Entity。

Mapper 仍继承项目现有 MyBatis-Plus 基础设施，但 V26 对外只暴露上述专用 SELECT；没有 Controller 或 Service 调用
通用 insert/update/delete API。

## 5. 实现与验收证据

本次已完成并核验：

1. `V11__create_knowledge_document_reprocess_task.sql` 未修改；运行中的 Spring Boot/Flyway 在隔离
   PostgreSQL 18.4 空库成功验证并顺序应用 12 个 migration，schema history 最终为 V12；
2. PostgreSQL catalog 与实际查询确认两张表的字段数量、`idx_payment_log_order` 的列顺序、
   `idx_payment_log_error`、固定 order/payment-log fixture；回滚式负例确认重复 `order_no` 和非法 `DEBUG`
   日志级别均被数据库约束拒绝；
3. 30 个 V26 聚焦测试覆盖 Service 的订单成功/404/无效 orderNo、支付日志默认 limit、1/20 边界、越界拒绝、
   空白过滤、双条件 AND、空结果和 DTO 映射，以及 Mapper SQL-shape 与 Controller 响应外壳；
4. Mapper SQL-shape 明确覆盖精确过滤、AND、`occurred_at DESC, id DESC`、参数化数据库侧 limit 和无 owner
   predicate；这些测试证明生成 SQL 形状，不单独冒充数据库执行证据；
5. JDK 21 与项目要求的 Mockito `-javaagent` 下，完整 backend Maven suite 为 315/315 通过；
6. 运行中的本地应用连接上述隔离 PostgreSQL，以一次性账号完成注册/登录后执行 14 项 curl 断言：未登录 401、
   fixture 安全 DTO、订单 404、默认 limit、trim 后双条件 AND、AND 不匹配/无匹配空数组、缺失/空白过滤和
   limit 0/21/非数字的 400 均通过；测试应用、数据库和临时数据随后已停止并清理。

`backend/http/demo-business.http` 已准备为可重复的 IDEA HTTP Client 输入，但本次没有逐项点击该文件；真实本地
HTTP 证据来自上述等价且更完整的 curl 断言。即使这些本地验收全部通过，也只能证明该 checkout 的只读 Demo
Business API，不证明生产并发、生产认证、真实订单/支付系统或检测效果。

HTTP 手工验收顺序为：先运行 `backend/http/user-auth.http` 的 `Login`，再依次运行
`backend/http/demo-business.http` 中订单成功、安全字段、404、按订单/错误码/双条件查询、AND 空结果、无匹配空
列表、缺失/空白过滤、limit 下界/上界/非数字和未认证请求。该脚本只读取 V12 fixture，不创建、修改或删除数据。

## 6. 明确不做

- `ToolRuntime`、`tool_definition`、`tool_call_log`、工具注册、JSON Schema 路由、PolicyGuard 或调用预算；
- `order_query`、`payment_log_query` 或任何工具执行；这些由 V27 基于本切片两个 Service 单独实现；
- Agent、AgentEngine、LLM、RAG、Trace、SSE、对话、任务、Episode、评测或报告生成；
- `mock_ticket`、`ticket_query` 或历史相似工单；
- `POST /api/v1/demo/seed`、管理员 demo 权限、动态 seed、fixture 管理或环境级重置；
- demo 数据新增、修改、删除、批量导入或任何其他写接口；
- 把 `user_no` 解释为平台 owner，按 JWT 用户过滤 fixture，或提供管理员跨 owner 视图；
- RabbitMQ、文档自动解析、worker、lease、定时任务、重试、PDF、MinIO 或对象存储；
- rerank、Hybrid Search、向量检索、Qdrant、embedding 或任何检索质量结论；
- 对真实订单系统、支付网关、生产 PostgreSQL 并发、生产认证或外部服务可用性作出声明。

## 面试问题与回答

### 问题 1：为什么 Demo API 需要登录，却不按 current user 做 owner 过滤？

**回答：** 认证门槛保护平台入口，数据归属则由业务契约决定。V26 的两张表是所有已认证用户共享的确定性 demo
fixture，且没有平台 `user_id`；`mock_order.user_no` 只是模拟订单系统客户号。若把 JWT principal 拼入 SQL，不但没有
可匹配列，还会把全局演示数据误写成多租户资源。Controller 因此依赖现有 Security 认证，但两个 Service 都不接收
owner。真实多租户订单权限未纳入本切片。

### 问题 2：为什么不直接返回 `MockOrder` 和 `MockPaymentLog` Entity？

**回答：** Entity 包含数据库 ID、模拟 `userNo` 和审计时间；日志 ID 虽用于同一 `occurredAt` 下稳定排序，也不是
客户端业务数据。V26 分别映射成六字段 `DemoOrderResponse` 和六字段 `DemoPaymentLogResponse`，Controller JSON
字段 allowlist 测试已证明当前 fixture 响应中不存在内部字段。这样 V27 可以复用安全业务结果，而不把数据库结构
固化为工具或 HTTP 契约。

### 问题 3：支付日志为什么把空白过滤条件当作未提供，并在两个条件同时出现时使用 AND？

**回答：** 先 trim 能让 `" order_1024 "` 与标准订单号得到同一精确查询；纯空白不能成为一次无边界扫描，所以按
未提供处理，两个条件都缺失时返回 `COMMON_PARAM_INVALID`。同时提供 `orderNo` 与 `errorCode` 表示进一步缩小结果，
Mapper 在一条 SQL 中用 AND，避免 OR 扩大数据集或两次查询合并造成重复和排序不稳定。本切片不提供全文、模糊或
前缀搜索。

### 问题 4：为什么排序还要加 `id DESC`，但 DTO 又不能返回 ID？

**回答：** `occurred_at` 可能相同，只按业务时间排序会让数据库任意交换并列行，导致 `LIMIT` 结果不稳定。内部
`id DESC` 提供确定的 tie-break，因此 Mapper 固定使用 `occurred_at DESC, id DESC`；索引仍按数据模型要求建立
`(order_no, occurred_at DESC)` 和 `error_code`。排序实现需要 ID，不等于 API 消费者需要知道 ID，DTO 因而继续隐藏
它。

### 问题 5：V26 的自动化或 HTTP 验收通过后能证明什么，不能证明什么？

**回答：** 聚焦 unit/mock、Controller 和 SQL-shape 证明参数分支、DTO allowlist、错误映射和预期 SQL 形状；
本次隔离 PostgreSQL 的 V1–V12 验证证明了实际 schema、约束、索引与 fixture；运行中的本地 HTTP 闭环又证明了
认证、MVC、Service 与该本地数据库组合后的可观察响应。三类证据必须分开陈述。它们仍不能证明真实订单/支付
提供方、生产并发和认证 SLA，更不能证明 V27 的 ToolRuntime、工具调用日志、Agent 或 RAG 已经实现；这些均未
纳入 V26。
