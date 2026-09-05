# V39 Task-scoped Trace 与 ExecutionRecorder 接口包说明

> 状态：已实现并完成重点验收（2026-09-03）。JDK 21 聚焦单元/契约/回归 58/58，
> PostgreSQL 18.4 上 V39 Trace 6/6、V38 生命周期 8/8；不包含真实 LLM、Qdrant 或 provider E2E。
> 起始基线：`main@77ed0fd`（V38/M4C）。本切片修改 schema，但不要求真实 LLM、Qdrant 或 provider。

> V40 衔接：本文记录 V39 的基础能力与当时验收。当前真实任务执行接入见
> `41_AGENT_TASK_EXECUTION_PACKAGE_INTERFACE.md`；V39 的四参数 task command 现仅保留日志构造兼容。

## 1. 切片目标与路线位置

V39 对应路线图 M4D，在 V38 的持久 `agent_task` 与单一 `TaskEventAppender` 之上，先建立
AgentEngine 后续正式接入所依赖的 step、LLM/RAG 专项日志、ToolRuntime task/step 关联、持久化
`ExecutionRecorder` 和内部 Trace 聚合查询：

```text
V38/M4C task lifecycle + durable event cursor
  -> V39/M4D task-scoped Trace foundation（本切片）
  -> M4E pre-retrieval + V36 AgentEngine/ToolRuntime 正式接入
  -> M4F Task/Trace HTTP + SSE
```

V39 可以使用脚本化 Engine/recording fixture 验证 recorder 和数据库不变量，但不把脚本、mock、
本地 adapter 或迁移检查表述为真实 Agent、provider、Qdrant 或产品 E2E。

V39 可以按以下两个审查提交实现，但只有两部分都完成并验收后才能声明 V39/M4D 完成：

```text
feat(trace): add task steps and llm/rag schema
feat(trace): add execution recorder and tool task linkage
```

只完成 schema 时只能称为 V39-A。

## 2. V19 Schema 契约

迁移固定为：

```text
backend/src/main/resources/db/migration/V19__create_agent_execution_trace.sql
```

V19 只追加迁移，不回改 V1–V18。所有新表使用应用生成的 `BIGINT` 主键、`TIMESTAMPTZ` 时间和
PostgreSQL `JSONB`；Trace 默认保留，外键不使用 `ON DELETE CASCADE`。

### 2.1 `agent_step`

字段为：

```text
id, task_id, step_index, step_type, status, title, summary,
error_code, error_message, started_at, ended_at, latency_ms, created_at
```

`step_index` 从 0 开始，并由 `UNIQUE(task_id,step_index)` 保证 task 内唯一。
`UNIQUE(id,task_id)` 为专项日志和 tool log 的二列复合外键提供被引用键；
`UNIQUE(id,task_id,step_type)` 额外供 LLM callType/stepType 的数据库级映射使用。

固定 step type：

```text
PRE_RETRIEVAL
LLM_DECISION
TOOL_CALL
LLM_FINAL_GENERATION
```

固定 step status：

```text
RUNNING
SUCCESS
FAILED
SKIPPED
```

`SKIPPED` 在 V39 只作为 schema 保留终态；本切片规定的 recorder 接口不产生它。RUNNING 必须没有
结束时间、latency 或错误；SUCCESS/SKIPPED 必须有结束时间和非负 latency 且没有错误；FAILED 必须有
结束时间、非负 latency、非空安全 error code/message。`summary` 必须是小型 JSON object，不复制完整
Prompt、tool result 或 RAG hit。

### 2.2 `llm_call_log`

LLM 专项日志只记录 task 中的两类 chat completion：

```text
DECISION
FINAL_GENERATION
```

字段保存 task/step、provider、requested/resolved model、已脱敏 request snapshot、bounded response、
finish reason、provider request ID、三个 token 字段、usage quality、latency、terminal status 和安全错误。
V0.1 document embedding 不写入该表。

V19 同时使用两层外键：

```text
(step_id, task_id) -> agent_step(id, task_id)

call_type -> generated expected_step_type
(step_id, task_id, expected_step_type)
  -> agent_step(id, task_id, step_type)
```

其中 `DECISION -> LLM_DECISION`，`FINAL_GENERATION -> LLM_FINAL_GENERATION`。因此数据库既拒绝跨 task
step，也拒绝把 callType 挂到错误 stepType；不能只靠 Java 约定。

`request_snapshot` 必须是 JSON object。status 在 V39 冻结为 terminal-only 的 `SUCCESS/FAILED`。
SUCCESS 不保存错误；FAILED 保存非空安全错误。usage quality 允许：

```text
EXACT / ESTIMATED / MIXED / UNKNOWN
```

`UNKNOWN` 必须对应三个 token 字段全部为 `NULL`；其他 quality 必须三个字段全部非负且
`total_tokens = input_tokens + output_tokens`。已知 `0/0/0` 与 UNKNOWN 是不同事实，不能互相伪装。

### 2.3 `rag_retrieval_log` 与 `rag_retrieval_hit`

retrieval log 保存 query、embedding profile、本次 corpus snapshot、topK、threshold、candidate/valid/stale
数量、latency、terminal status 和安全错误，并通过 `(step_id,task_id)` 复合外键证明 step 属于同一 task。
status 同样只允许 `SUCCESS/FAILED`；corpus snapshot 必须为 JSON object，计数和 latency 非负，最终有效
hit 不超过 topK，valid/stale 总数不超过 candidate 数。

每个 hit 保存：

```text
rank_no, citation_id,
chunk_id_snapshot, document_id_snapshot, knowledge_base_id_snapshot,
vector_generation, score, bounded content_snapshot,
metadata_snapshot, created_at
```

同一 retrieval 内 rank 和 citation ID 分别唯一。`metadata_snapshot` 必须是 JSON object。
chunk/document/knowledge-base ID 是历史快照，不对当前 source 表建立外键；文档软删除、状态修改或
vector generation 变化都不能使已提交 hit 消失。

一条 retrieval log 和它的全部 hits 必须由 `recordRagRetrieval` 在同一独立短事务中提交。任一 hit
插入或数量检查失败时整组回滚，不能留下半套 hit。普通 FK 本身不能证明 hit 数完整，事务实现还必须
核对期望与实际插入数量。

### 2.4 `tool_call_log` task/step 关联

V19 保留 V13 的所有字段、standalone 行和 lifecycle CHECK，只追加：

```text
CHECK (
  (task_id IS NULL AND step_id IS NULL)
  OR
  (task_id IS NOT NULL AND step_id IS NOT NULL)
)

FOREIGN KEY (step_id, task_id)
  REFERENCES agent_step(id, task_id)

INDEX (task_id, created_at, id)
```

因此开发/管理用 standalone 调用继续使用 `(NULL,NULL)`；AgentTask 调用必须同时提供 taskId/stepId，
数据库拒绝半关联和 Task A 挂 Task B step。V19 不回改 V13、不清洗历史 arguments，也不新增
arguments-object CHECK；新写入的 arguments/result 由统一 sanitizer 在持久化前处理。

## 3. Step 分配与单终态

绑定单个 task 的 recorder 只允许为 RUNNING task 创建 step。`startStep` 在一个
`REQUIRES_NEW` 短事务中严格按以下顺序执行：

```sql
SELECT id
FROM agent_task
WHERE id = :taskId
  AND status = 'RUNNING'
FOR UPDATE;

SELECT COALESCE(MAX(step_index), -1) + 1
FROM agent_step
WHERE task_id = :taskId;

INSERT INTO agent_step (..., step_index, status, ...)
VALUES (..., :allocatedIndex, 'RUNNING', ...);
```

必须先获得 task 行锁，再计算 `MAX+1`；禁止无锁分配。所有 step 插入入口都必须经过该事务服务，
`UNIQUE(task_id,step_index)` 是最终数据库防线。

完成或失败使用条件更新：

```sql
UPDATE agent_step
SET status = :terminalStatus,
    summary = :summary,
    error_code = :errorCode,
    error_message = :safeMessage,
    ended_at = :endedAt,
    latency_ms = :latencyMs
WHERE id = :stepId
  AND task_id = :taskId
  AND status = 'RUNNING';
```

影响行数必须恰好为 1。数据库 CHECK 约束终态字段形状，条件 UPDATE/CAS 证明 step 只能由 RUNNING
进入一次终态。`StepHandle` 必须携带 taskId，且只能交给创建它的 task-bound recorder。

## 4. `ExecutionRecorder` 契约与事务边界

V39 使用 task-bound factory：

```java
public interface ExecutionRecorderFactory {
    ExecutionRecorder open(long taskId);
}
```

```java
public interface ExecutionRecorder {
    StepHandle startStep(StepType type, String title);
    void completeStep(StepHandle step, StepSummary summary);
    void failStep(StepHandle step, String errorCode, String safeMessage);
    void recordLlmCall(LlmCallRecord record);
    void recordRagRetrieval(RagRetrievalRecord record);
    void appendEvent(TaskEventRecord event);
}
```

`PersistentExecutionRecorder(taskId)` 不允许调用者在每个方法重新提供或替换 taskId。每次操作由独立
Spring transaction collaborator 以 `REQUIRES_NEW` 执行，不能依赖同类 self-invocation 触发代理：

```text
startStep 提交
  -> 数据库事务外执行外部调用
  -> recordLlmCall / recordRagRetrieval / ToolRuntime terminal log 提交
  -> completeStep 或 failStep 提交
```

后续步骤或 Trace 写入失败不能回滚此前已提交的 step/log。真正外部 LLM、RAG 或 Tool 调用前，如果
对应 RUNNING step 或 ToolRuntime RUNNING log 未成功提交，必须停止，不调用外部系统。外部调用已经发生
后若 terminal Trace 持久化失败，不得向上游返回普通成功，也不得删除或回滚此前已提交 Trace。

`appendEvent` 必须委托 V38 唯一 `TaskEventAppender`，由调用方提供独立短事务；不得新增第二个
sequence allocator，不得使用 `MAX(sequence_no)+1`。V39 只建立该能力，不正式产生 M4E 的
RAG/decision/tool/final-generation 事件流。

## 5. Trace 脱敏与大小限制

统一 `TracePayloadSanitizer` 处理 step summary、LLM、RAG、tool 和 event 的结构化快照；各 writer 不得
各自维护不同敏感 key 集合。sanitizer 递归遍历 JSON object/array，对字段名执行：

```text
Unicode/ASCII 字母按 Locale.ROOT lowercase
移除连字符 '-' 和下划线 '_'
只做 canonical key 的精确匹配，不做包含 token 的 substring 匹配
```

以下输入形式例如 `Api-Key`、`api_key`、`apiKey` 都归一为 `apikey`。固定敏感 canonical key 为：

```text
authorization
proxyauthorization
cookie
setcookie
apikey
xapikey
password
secret
clientsecret
accesstoken
refreshtoken
```

命中值统一替换为字符串 `[REDACTED]`。因为采用精确匹配，`maxTokens`、`inputTokens`、
`tokenUsage` 等正常字段不会被误删。

generic sanitizer 不是任意对象序列化器。LLM request snapshot 必须先由白名单 DTO 只构造允许的
messages/roles、模型和采样/输出上限；不得序列化 Spring AI/provider 框架对象，不得保存 API key、
Authorization、Cookie、完整内部 endpoint、SQL、stack 或绝对文件路径。endpoint 和自由文本中的秘密
不能仅靠 key sanitizer 可靠发现，因此这些字段不得进入 snapshot 数据流。

禁止持久化或展示自由形式 chain-of-thought。允许保存结构化 decision type、toolCode、简短 reason、
answerPlan 和经脱敏/bounded 的模型可见响应；provider 隐藏 reasoning、框架内部 reasoning metadata 或
任意 CoT 字段不得传给 recorder。

V39 默认可配置 UTF-8 字节上限为：

| 类别 | 默认上限 |
| --- | ---: |
| step summary、RAG hit metadata 等小型 JSON | 16 KiB（16,384 bytes） |
| tool arguments、tool result | 64 KiB（65,536 bytes） |
| LLM request、LLM response、RAG corpus snapshot | 256 KiB（262,144 bytes） |
| 单个 RAG hit content | 16 KiB（16,384 bytes） |

大小在脱敏后、入库前，以实际序列化结果的 UTF-8 bytes 计算。V39 统一选择 **fail-fast**，不静默截断：

- pre-call snapshot 超限：不写 RUNNING 事实，不调用外部系统；
- post-call response/result/hit 超限：超限内容不入库，当前 Trace 操作安全失败；
- post-call 失败不回滚此前独立事务已经提交的 step/log；
- JSON 不允许按原始 byte 数组截断后写入非法 JSON。

配置可以降低或提高业务上限，但不得关闭限制；变更只影响新 Trace，不改写历史记录。

## 6. ToolRuntime task command

`ToolExecutionCommand` 必须同时支持：

```text
standalone(toolId, arguments)       -> taskId=null, stepId=null
taskScoped(toolId, taskId, stepId, arguments)
```

构造时即校验 taskId/stepId 必须同时为空或同时为正数；数据库 pair CHECK/FK 是第二道防线。
V40 实际执行还必须传入 owner、agent、完整 execution snapshot、deadline 和共享取消检查；
上述四参数 factory 可供旧日志构造使用，不能直接执行当前持久任务。
AgentTask 工具路径在 handler I/O 前先完成 sanitizer、大小检查和 RUNNING tool log 的独立提交。
arguments/result 快照使用安全副本，不能因为脱敏而改写实际传给受控 handler 的原始已验证参数。

V39 不把 V36 Engine 改成调用 `taskScoped`；正式接入属于 M4E。standalone Tool Controller 和现有工具
回归必须继续合法。

## 7. 内部 Trace 聚合查询骨架

内部 `TaskTraceQueryService` 只接受可信 `userId + taskId`，首先以
`agent_task.id + agent_task.user_id` 查询 owner-scoped task；不存在、跨 owner 或不可见统一返回 not found。
不得先按 taskId 读取完整 Trace 再在内存判权。

聚合顺序固定为：

```text
steps:                  step_index ASC
LLM/RAG/tool logs:      created_at ASC, id ASC
RAG hits:               rank_no ASC
```

返回值使用不可变内部 DTO 和 defensive copy 的 JSON tree/list。V39 不提供 Controller，不将内部 DTO
直接作为未来 HTTP contract；M4F 再设计公开 Task detail/Trace API 和访问控制投影。

## 8. 重点验收结果

本切片已完成以下重点验收：

1. 空真实 PostgreSQL 完整应用 V1–V19；
2. 并发 `startStep` 不产生重复 `step_index`；
3. 跨 task 的 LLM/RAG/tool step 关联被数据库拒绝，错误 LLM callType/stepType 也被拒绝；
4. standalone tool `(NULL,NULL)` 合法，任一半关联非法；
5. 并发 complete/fail 只有一个 RUNNING step 进入终态；
6. retrieval log 与全部 hits 同事务提交或回滚；
7. 后续 Trace 失败不回滚此前已提交 Trace；
8. 源 document 状态、generation 或软删除变化后 hit snapshot 仍存在；
9. 敏感 key、CoT 和超限内容不进入数据库；
10. owner scope、step/log/hit 聚合顺序稳定；
11. V38 生命周期与现有 standalone ToolRuntime 重点回归通过。

2026-09-03 的可复现证据为：空临时 PostgreSQL 18.4 数据库完整应用 V1–V19，
`AgentExecutionTracePostgresIntegrationTest` 6/6 覆盖上述 Trace 数据库、并发、短事务和聚合边界；
`AgentTaskPostgresIntegrationTest` 8/8 覆盖 V38 生命周期；sanitizer、recorder、ToolRuntime、AgentEngine、
Tool Controller 和静态 migration contract 的 JDK 21 聚焦测试合计 58/58。按本切片边界未运行真实外部服务
E2E，也未以 mock 或静态检查替代 PostgreSQL 验收。

测试不调用真实 LLM、Qdrant 或 provider。静态 migration contract test 不能代替 PostgreSQL 外键、行锁、
事务回滚或竞态证据。

## 9. 明确不做

- 将 V36 AgentEngine 正式接入 TaskRunner；
- 真正执行前置 RAG、Qdrant 检索或 citation 验证；
- 修改 decision 协议、预算估算或重复工具调用控制；
- 正式产生 M4E 的 RAG/decision/tool/final-generation 事件流；
- Task/Trace 公共 Controller 或公开 DTO；
- SSE、Last-Event-ID 或事件回放接口；
- 前端 Task/Trace 页面；
- 真实 LLM、Embedding、Qdrant 或 provider E2E；
- chain-of-thought 存储或展示；
- tool retry attempt 表或覆盖式重试；
- crash recovery、stale RUNNING 扫描、outbox 或多实例调度；
- 用户删除 Trace、retention job 或合规删除策略；
- 对 RAG snapshot source ID 增加强制 FK；
- 修改或清洗 V13 历史 tool arguments/result。

## 面试问题与回答

### 问题 1：为什么 `MAX(step_index)+1` 在这里可以使用？

回答：它不是无锁使用。`startStep` 先以 `SELECT ... FOR UPDATE` 锁住对应 RUNNING task 行，同一 task 的
分配因此串行化，然后才读取 MAX 并插入；`UNIQUE(task_id,step_index)` 再提供最终数据库防线。绕过该
task 行锁直接计算 MAX 仍然是错误实现。

### 问题 2：为什么 LLM log 同时需要二列和三列复合外键？

回答：二列 FK 明确实现所有专项日志一致的“step 属于同一 task”契约；三列 FK 再把 generated
`expected_step_type` 与实际 stepType 对齐，使 `DECISION` 不能挂到 final-generation step。只用二列 FK
无法证明 callType 的语义类型。

### 问题 3：为什么每次 recorder 操作使用独立短事务？

回答：LLM、RAG 和工具 I/O 可能很慢，不能持有数据库事务。先提交 RUNNING step/log，事务外调用，
再独立提交专项日志和终态；后续失败不会抹掉已经发生的早期执行事实。代价是可能留下 RUNNING 痕迹，
但 V39 明确不把 crash recovery 纳入本切片。

### 问题 4：为什么 RAG hit 的 source ID 不建立外键？

回答：这些 ID 表达检索发生时的历史快照。当前 chunk/document 可能软删除、重处理或切换 generation；
若 Trace 依赖当前 source FK 或级联删除，历史决策将不可解释。真实性由 task corpus snapshot、generation、
content/metadata snapshot 共同保留，而不是把历史强绑到当前行。

### 问题 5：为什么大小超限选择失败而不是直接截断？

回答：静默截断会让审计内容失真，直接截断 JSON bytes 还可能产生非法 JSON。V39 在脱敏后按实际 UTF-8
序列化 bytes fail-fast；预调用失败阻止外部 I/O，调用后失败不把超限内容写库，也不回滚此前已提交
Trace。V40 已在检索 evidence 装配和工具 observation 投影中加入有界截断及显式标记，
详见 41 契约；Recorder 自身继续 fail-fast，不静默改写传入的审计快照。

### 问题 6：V39 的 Trace 查询为什么不提供 Controller？

回答：M4D 只证明 owner-scoped 聚合、稳定排序和不可变内部投影。公开详情、字段裁剪、HTTP 错误和 SSE
回放属于 M4F；提前暴露 Controller 会把尚未冻结的内部日志结构误当成长期公共 API。
