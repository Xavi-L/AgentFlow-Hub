# AgentFlow Hub Tool：V29 数据库定义驱动的 `report_generate` 闭环

> 当前状态：V29 的 V15 migration、受限字符串数组校验、确定性 Markdown Handler、代码
> allowlist、自动化测试、`.http` 验收输入和本切片契约均已实现。JDK 21 与 Mockito javaagent
> 下，45 个聚焦测试及 378 个完整 backend 测试全部通过。Spring Boot/Flyway 已在隔离
> PostgreSQL 18.4 空库顺序应用 V1–V15；登录后的真实 HTTP 与数据库验收通过 83 项断言，
> 最终形成 `SUCCESS=4 / REJECTED=15 / FAILED=1 / TOTAL=20`。这些是当前 checkout 的
> 单元、mock、SQL-shape、本地实库与 HTTP 证据，不外推生产并发、Markdown 渲染安全、
> LLM 生成质量或完整 M3/V0.1 能力。

V29 正式建立 M3 的第三个数据库定义驱动内置工具闭环：
`report_generate + ReportGenerateToolHandler`。它复用 V27/V28 已有的 `ToolRuntime`、工具定义
发现、调用日志和 HTTP 入口，但首次使用不读取业务表的确定性模板 Handler，证明该 Runtime
并非只能路由 PostgreSQL 查询工具。

V28 已由独立提交 `d68d60f feat(tool): add payment log query runtime` 建立基线，其父提交是 V27
的 `44f1e80 feat(tool): add order query runtime`。V29 只新增
`V15__add_report_generate_tool.sql`，不修改已应用的 V12–V14，也不创建表或 demo fixture。

## 1. 目标、范围与完成定义

最小有效参数为：

```json
{
  "title": "支付失败报告",
  "summary": "订单支付失败。"
}
```

完整有效参数为：

```json
{
  "title": "order_1024 支付失败分析报告",
  "summary": "订单支付失败。",
  "rootCause": "支付网关响应超时。",
  "suggestions": [
    "检查网关状态。",
    "确认订单未重复扣款。"
  ]
}
```

完成链路为：

```text
PostgreSQL tool_definition(V15 report_generate)
  -> ToolDefinitionService
  -> ToolArgumentValidator(input_schema + 同构字符串数组子集)
  -> ToolCallLogService(REJECTED 或 RUNNING)
  -> BuiltinToolExecutor 精确 code/handler allowlist
  -> ReportGenerateToolHandler
  -> 确定性 Markdown 模板
  -> ReportGenerateToolData
  -> ToolExecutionResult
  -> ToolCallLogService(SUCCESS / FAILED)
```

V29 的关键完成点有两个：

1. 第三个 M3 内置工具继续复用同一数据库定义、Runtime、allowlist、HTTP 和日志模型；
2. Runtime 首次路由一个不读取 PostgreSQL 业务表、不回调 HTTP、不调用 RAG/LLM 的纯模板 Handler。

工具定义仍是全局共享资源。现有 Spring Security 要求先登录，但 V29 没有 Agent owner、Agent 获取工具、
Agent 工具绑定、PolicyGuard 或管理员语义。独立测试调用的 `task_id`、`step_id` 继续为 SQL `NULL`。

## 2. V15 数据库契约

### 2.1 migration 边界

V15 只包含一次：

```sql
INSERT INTO tool_definition (...)
```

V15 不包含：

- `CREATE TABLE`、`ALTER TABLE` 或 `DROP TABLE`；
- 对 V12、V13、V14 的修改；
- `mock_order`、`mock_payment_log` 新 fixture；
- `ticket_query`、`knowledge_search`；
- Agent、task、step、Trace、SSE 或工具绑定表。

Flyway migration 保持不可变：已经存在的 V12–V14 不为 V29 回写。

### 2.2 固定工具定义

V15 插入一条固定定义：

| 字段 | 固定值 |
| --- | --- |
| `id` | `290000000000000001` |
| `tool_code` | `report_generate` |
| `name` | `Report Generate` |
| `type` | `BUILTIN` |
| `status` | `ACTIVE` |
| `permission_level` | `LOW` |
| `timeout_ms` | `10000` |
| `retry_count` | `0` |
| `requires_confirmation` | `false` |
| `config` | `{"handler":"reportGenerateTool","readonly":true}` |

`LOW` 和 `timeout_ms=10000` 来自工具系统设计。当前 Handler 只根据调用方已经提供的文本组装 Markdown，
不访问业务数据或外部系统。10000ms 仍只是持久化配置；V29 没有 future、定时取消或 `TIMEOUT` 状态产生。
`retry_count=0` 与当前同步 Runtime 没有重试循环一致。

### 2.3 输入 schema

V15 固定保存：

```json
{
  "type": "object",
  "properties": {
    "title": {
      "type": "string",
      "minLength": 1,
      "maxLength": 255
    },
    "summary": {
      "type": "string",
      "minLength": 1,
      "maxLength": 4000
    },
    "rootCause": {
      "type": "string",
      "minLength": 1,
      "maxLength": 4000
    },
    "suggestions": {
      "type": "array",
      "minItems": 1,
      "maxItems": 20,
      "items": {
        "type": "string",
        "minLength": 1,
        "maxLength": 1000
      }
    }
  },
  "required": ["title", "summary"],
  "additionalProperties": false
}
```

`title` 和 `summary` 必填；`rootCause`、`suggestions` 可独立省略。任何已提供字段仍必须满足类型、非空白
和长度/数量边界。V29 不对输入文本做事实核验、Markdown escaping、HTML sanitization、去重或改写。

### 2.4 输出 schema

V15 的 `output_schema` 只描述：

```json
{
  "type": "object",
  "properties": {
    "markdown": {"type": "string"}
  },
  "required": ["markdown"],
  "additionalProperties": false
}
```

V29 与前两切片一样不执行 output schema validator。真实输出边界由
`ReportGenerateToolData(String markdown)` 的静态类型和 Handler 单一构造点保证。

## 3. `ToolArgumentValidator` 的 V29 增量

### 3.1 保留的既有子集

V29 保留：

- V27 的 `object`、`string`、`integer`、`required`、长度/数值边界和
  `additionalProperties=false`；
- 嵌套 object 递归；
- positive `minLength` 下对 whitespace-only string 的拒绝；
- V28 的根级 required-only `anyOf`，继续服务 `payment_log_query`。

### 3.2 唯一新增的 array 能力

V29 只支持同构字符串数组，并要求：

1. property schema 的 `type` 精确为 `array`；
2. `items` 必须是单一 JSON object schema，不能是 tuple schema 数组；
3. `items.type` 必须精确为 `string`；
4. 可使用非负整数 `minItems`、`maxItems`，且 minimum 不得大于 maximum；
5. 每个实际元素逐个复用 string 校验，包括类型、Unicode code point 长度与非空白检查。

`items` 缺失、tuple items、嵌套数组、`contains`、`uniqueItems` 或其他 item type 被视为不受支持的工具
定义并 fail closed。V29 没有实现数组 tuple、嵌套 array、完整 JSON Schema array/composition engine、
`contains`、`uniqueItems`、`oneOf`、`allOf`、条件 schema、pattern 或 format。

### 3.3 Runtime 拒绝位置

`DefaultToolRuntime` 的顺序仍为：

```text
解析 ACTIVE 且未软删除的定义
  -> 校验全部参数和全部数组元素
  -> 参数不合法：直接写 REJECTED
  -> 参数合法：才写 RUNNING 并执行 Handler
```

因此缺少 title/summary、空白、类型错误、超长、空 suggestions、元素非 string、空白/超长元素、超过
20 项和多余字段都映射为：

```text
HTTP 400 / TOOL_ARGUMENT_INVALID / tool_call_log.REJECTED
```

它们不会先形成 `RUNNING`，也不会因 Handler 假设输入已校验而被误分类为 `FAILED`。

## 4. Handler、模板与 allowlist

### 4.1 精确 allowlist

`BuiltinToolExecutor` 现在只接受三个代码所有的二元组：

```text
order_query       + orderQueryTool
payment_log_query + paymentLogQueryTool
report_generate   + reportGenerateTool
```

tool code 和数据库 `config.handler` 必须同时精确匹配，才调用对应构造器注入的 Handler。数据库不能按
任意 Bean 名、类名或反射扩展执行能力。错误 code/handler 组合、未知 handler 或非 `BUILTIN` 定义进入
既有安全未知执行异常路径：HTTP 返回 `TOOL_EXECUTION_FAILED`，日志记录 `FAILED`，内部 handler 值不进入
公共响应或持久化错误信息。

### 4.2 确定性模板规则

`ReportGenerateToolHandler` 不注入业务 Service、Mapper、HTTP client、RAG 或 LLM Gateway。它只按以下规则
拼接调用参数：

- `title` 始终生成一级标题；
- `summary` 始终生成“结论”章节；
- 缺少 `rootCause` 时不生成“原因分析”章节；
- 缺少 `suggestions` 时不生成“处理建议”章节；
- 建议从 1 开始，按输入顺序编号；
- 不去重、不排序、不补充业务内容；
- `ToolExecutionResult.summary` 固定为 `已生成 Markdown 处理报告。`。

完整输入逐字生成：

```markdown
# order_1024 支付失败分析报告

## 结论
订单支付失败。

## 原因分析
支付网关响应超时。

## 处理建议
1. 检查网关状态。
2. 确认订单未重复扣款。
```

实际 `data` 只含：

```json
{
  "markdown": "# order_1024 支付失败分析报告\n\n## 结论\n订单支付失败。\n\n## 原因分析\n支付网关响应超时。\n\n## 处理建议\n1. 检查网关状态。\n2. 确认订单未重复扣款。"
}
```

仅必填字段时固定为：

```json
{
  "markdown": "# 支付失败报告\n\n## 结论\n订单支付失败。"
}
```

## 5. HTTP 与日志契约

V29 复用既有入口，不新增路由：

```http
GET /api/v1/tools
POST /api/v1/tools/{toolId}/test
```

`GET /tools` 返回 V13 `order_query`、V14 `payment_log_query` 和 V15 `report_generate` 三个 ACTIVE、
未删除工具。三个 BIGINT ID 都以十进制 string 输出；安全 DTO 不返回 `config`、handler 或审计字段。
报告工具公开 `LOW`、10000ms、输入/输出 schema，但 10000ms 不代表已经执行真正超时控制。

场景矩阵：

| 场景 | HTTP / code | 日志 |
| --- | --- | --- |
| 仅 `title`、`summary` | 200 / `OK` | `SUCCESS` |
| 四字段完整报告 | 200 / `OK` | `SUCCESS` |
| 可选章节省略 | 200 / `OK` | `SUCCESS` |
| 缺 `title` 或 `summary` | 400 / `TOOL_ARGUMENT_INVALID` | `REJECTED` |
| string 空白、类型错误、超长 | 400 / `TOOL_ARGUMENT_INVALID` | `REJECTED` |
| suggestions 非 array、空数组 | 400 / `TOOL_ARGUMENT_INVALID` | `REJECTED` |
| 元素非 string、空白、超长或数组超量 | 400 / `TOOL_ARGUMENT_INVALID` | `REJECTED` |
| 多余字段 | 400 / `TOOL_ARGUMENT_INVALID` | `REJECTED` |
| 工具不存在、DISABLED 或软删除 | 404 / `TOOL_NOT_FOUND` | 不写 |
| 未知 handler/执行异常 | 500 / `TOOL_EXECUTION_FAILED` | `FAILED` |
| malformed body | 400 / `COMMON_REQUEST_BODY_INVALID` | 不写 |
| 未登录 | 401 / 既有认证响应 | 不写 |

当前 Java 实际只产生 `RUNNING / SUCCESS / REJECTED / FAILED`；请求结束后没有遗留 RUNNING。
`PENDING / TIMEOUT` 只是 V13 数据库状态词汇，V29 不实际产生。

## 6. 文件与接口变更

新增：

- `backend/src/main/resources/db/migration/V15__add_report_generate_tool.sql`；
- `backend/src/main/java/com/agentflow/tool/ReportGenerateToolHandler.java`；
- `backend/src/main/java/com/agentflow/tool/ReportGenerateToolData.java`；
- `backend/src/test/java/com/agentflow/tool/ReportGenerateToolHandlerTest.java`；
- `backend/src/test/java/com/agentflow/tool/V15ReportGenerateToolMigrationContractTest.java`；
- `slice-docs/30_REPORT_GENERATE_TOOL_PACKAGE_INTERFACE.md`。

修改：

- `ToolArgumentValidator`：增加同构字符串数组与 `minItems/maxItems`；
- `BuiltinToolExecutor`：增加第三个精确 allowlist 二元组；
- 既有 Runtime、Controller、definition、validator 和 executor 测试；
- `backend/http/tools.http`：增加三工具发现和 V29 手工验收请求。

未修改：

- V12、V13、V14 migration；
- V26 demo fixture、Service、Mapper 和 DTO；
- 现有 HTTP route；
- V13 `tool_call_log` 表和生命周期约束。

## 7. 实现与验收证据

### 7.1 聚焦自动化测试

JDK 21 与 Mockito `-javaagent` 下，以下聚焦组合为 45/45 通过：

- `ToolArgumentValidatorTest`；
- `BuiltinToolExecutorTest`；
- `ReportGenerateToolHandlerTest`；
- `DefaultToolRuntimeTest`；
- `ToolControllerTest`；
- `ToolDefinitionServiceTest`；
- `V15ReportGenerateToolMigrationContractTest`。

覆盖内容包括：

- 完整 Markdown 的逐字结果和固定中文 summary；
- 仅必填字段、独立省略可选章节；
- 建议顺序、编号、重复项保留；
- title/summary/rootCause/suggestion 的类型、空白和长度边界；
- suggestions 的 array 类型、1–20 数量和逐元素 string 校验；
- tuple、嵌套 array、contains、uniqueItems 和非法数量定义 fail closed；
- 三个 code + handler 精确路由且互不交叉；
- Runtime 在 RUNNING 前记录数组错误为 REJECTED；
- 三工具列表、string ID、LOW/10000ms 和安全 HTTP DTO；
- V15 只插入一个定义且不修改表或 demo fixture。

这些测试包含 Mockito 与 SQL 文本/shape 证据，不单独视为真实 PostgreSQL 或生产验收。

### 7.2 完整 Maven suite

同一 JDK 21 与 Mockito javaagent 下，完整 backend suite 为：

```text
Tests run: 378, Failures: 0, Errors: 0, Skipped: 0
```

### 7.3 PostgreSQL 18.4 空库迁移

一次性隔离 cluster 从 `<< Empty Schema >>` 启动，Spring Boot/Flyway：

1. validate 15 个 migration；
2. 顺序执行 V1、V2……V15；
3. 成功应用 15 个 migration；
4. 最终 schema version 为 15；
5. `tool_definition` 恰有 V13、V14、V15 三条定义；
6. `mock_order`、`mock_payment_log` 仍各只有 V12 的一条 fixture；
7. V15 的 ID、schema、LOW、10000ms、zero retry 和精确 handler 由 SQL/HTTP 断言核验。

Flyway 对 PostgreSQL 18.4 输出“当前版本测试到 PostgreSQL 17”的升级建议警告；这不影响本次实际迁移成功，
但本地成功不等于该版本组合已有 Flyway 官方生产兼容保证。

### 7.4 登录后的真实 HTTP 与数据库日志

运行中的应用连接上述隔离库，在端口 18082 注册一次性用户、登录并执行 curl + jq + psql 验收。
83 项断言全部通过：

- 未登录 discovery/test 为 401 且不写日志；
- 登录后列表恰有三个安全工具，ID 均为 string，报告工具为 LOW/10000ms；
- 完整报告逐字匹配固定 Markdown，建议保持输入顺序；
- 仅必填报告省略两个可选章节；
- missing、blank、wrong type、title/summary/rootCause/suggestion 超长、suggestions 非 array、空数组、
  非 string/空白/超长元素、21 项和额外字段形成 15 条 REJECTED；
- `order_query`、`payment_log_query` 各经共享 Runtime 成功回归一次；
- 临时把 report handler 改为未知值后得到安全 500 和一条 FAILED，随后恢复原定义；
- 不存在、临时 DISABLED、临时软删除、malformed 和未登录均不增加日志；
- 20 条调用的 task/step 全为 NULL、retry count 全为 0；
- 没有遗留 RUNNING，也没有产生 PENDING/TIMEOUT；
- 两条 report SUCCESS 都持久化 `data.markdown`，FAILED 结果不含临时未知 handler 值。

最终实库计数为：

```text
FAILED=1, REJECTED=15, SUCCESS=4, TOTAL=20
order_query=1, payment_log_query=1, report_generate=18
```

`backend/http/tools.http` 是可重复的 IDEA HTTP Client 输入；本次真实证据来自独立 curl/jq/psql 断言，
不把“脚本已准备”写成“IDEA 文件已逐项点击”。隔离应用、数据库、临时账号和验收文件在通过后已关闭并清理。

## 8. 明确不做

- LLM 报告生成、事实核验、引用核验或生成质量评估；
- 自动把订单、支付日志或知识库结果注入报告；
- `ticket_query`、`knowledge_search`；
- Agent、Agent CRUD、AgentEngine、Agent 获取工具或工具绑定；
- PolicyGuard、调用预算或人工确认；
- SSE、Trace、task/step 表及外键；
- PDF、DOCX、文件导出、下载或模板管理；
- Markdown 渲染、HTML sanitization 或前端安全策略；
- output schema 校验；
- tuple、嵌套数组、contains、uniqueItems 或完整 JSON Schema array/composition engine；
- 真正超时、重试、取消及 RUNNING 恢复；
- 工具 CRUD、日志查询接口或动态 Bean/类名/反射执行；
- 前端工具列表、详情抽屉或测试面板；
- HTTP/MCP 工具或真实外部服务验收。

因此，V29 完成后可以准确宣称：三个 M3 后端内置工具都能经过同一数据库定义、Runtime、精确 allowlist
和日志模型执行，并首次证明该 Runtime 不只支持数据库查询 Handler。不能宣称整个 M3 或 V0.1 已完成，
因为 Agent 获取工具、SSE，以及路线图中的前端只读列表和详情仍未落地。

## 面试问题与回答

### 问题 1：为什么 report_generate 先使用模板，而不是再次调用 LLM？

**回答：** 工具系统设计明确推荐 V1.0 先用模板。V29 的目标是验证第三个数据库定义驱动 Handler 和统一
Runtime/日志闭环，而不是引入第二次模型调用、事实幻觉、provider 超时和提示词版本等新变量。当前输出对同一
输入逐字确定；LLM 生成和事实核验未纳入本切片。

### 问题 2：为什么数组校验只实现同构 string items，而不接入完整 JSON Schema？

**回答：** V29 唯一新增需求是 `suggestions: string[]` 及 1–20 项边界。validator 明确要求单一 object items、
items.type=string，并拒绝 tuple、嵌套 array、contains 和 uniqueItems。这样参数错误能在 RUNNING 前稳定形成
REJECTED，同时不把一个小切片伪装成 JSON Schema draft 实现；复杂 schema 应另行评估成熟引擎。

### 问题 3：如何证明建议顺序没有被重排或去重？

**回答：** Handler 按 `JsonNode` 数组索引从 0 顺序读取，输出序号使用 `index + 1`，中间没有 Set、sort 或
distinct。单元测试用“第一项、第二项、重复第一项”验证三项原样保留；真实 HTTP 的完整报告也逐字断言两条建议
顺序。该证据只证明当前模板行为，不代表对建议内容做过事实审核。

### 问题 4：数据库里的 handler 为什么仍不能执行任意 Spring Bean？

**回答：** `config.handler` 只是定义元数据。`BuiltinToolExecutor` 只接受三个代码所有的 code/handler 二元组，
Handler 全部通过构造器注入，没有 Bean 名查找或反射。实库临时未知 handler 返回安全
`500 / TOOL_EXECUTION_FAILED` 并记录 FAILED，公共响应和持久化错误都没有泄露注入值。

### 问题 5：为什么参数错误必须在写 RUNNING 前完成？

**回答：** REJECTED 表示请求从未成为有效执行，FAILED 表示有效调用进入 Handler 后失败。若数组元素、缺字段或
超长文本由 Handler 才发现，就会先写 RUNNING 再写 FAILED，污染执行质量和故障统计。V29 先遍历校验全部元素，
15 类实库负例都直接形成 REJECTED，且没有遗留 RUNNING。

### 问题 6：V29 完成后对 M3 能宣称到什么程度？

**回答：** 可以宣称 `order_query`、`payment_log_query`、`report_generate` 三个后端内置工具都经过同一
数据库定义、Runtime、allowlist、HTTP test 入口和日志模型执行；报告工具还证明 Runtime 支持非数据库查询
Handler。不能宣称 M3/V0.1 全部完成，因为 Agent 获取工具、SSE、前端只读列表/详情，以及工具绑定等能力仍未
实现；10000ms 和 retry_count 也只是配置，不是真正超时或重试证据。
