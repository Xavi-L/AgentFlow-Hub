# V40 基于执行快照的前置 RAG 与持久任务执行闭环接口包说明

> 状态：已实现并完成验收（2026-09-05）。JDK 21、PostgreSQL 18.4 全量 608/608 通过，无失败、错误或跳过。
> 路线位置：M4E，接续 V38/M4C Task 生命周期与 V39/M4D Trace。
> 验收使用真实后端组件/PostgreSQL 与可控模型、向量替身；不声称真实 provider E2E。

## 1. 目标与范围

```text
创建 task + execution_snapshot + reserved_final_tokens
  -> after-commit dispatch -> TaskRunner 条件领取 RUNNING
  -> AgentEngine.execute(TaskExecutionRequest)
  -> snapshot 前置 RAG -> CALL_TOOL / FINISH 循环 -> 独立最终生成
  -> Runner 条件写终态
  -> 完整答案、全部 ANSWER_CHUNK、TASK_COMPLETED 同事务发布
```

六项交付必须同时成立：Runner 调用真实 Engine；snapshot-scoped RAG；canonical decision/安全 Prompt；
完整 snapshot ToolRuntime；预算/取消/超时控制；真实 step、专项日志与持久事件。可以分别审查 RAG 与
Engine 接入，但两部分都完成并通过重点验收，才算 V40/M4E。

三个衔接问题一并处理：V20 保存失败等终态的超额消费；答案分片原子发布；非法 decision 留下安全 Trace。
创建继续复用 V37 的 READY gate：所有绑定 KB 都没有 READY 文档时仍以 `RAG_KNOWLEDGE_NOT_READY`
拒绝创建。执行期的空 RAG 不扩大为“无知识绑定也可创建 task”。

## 2. 执行入口与冻结契约

```java
TaskExecutionOutcome AgentEngine.execute(TaskExecutionRequest request);
```

`TaskExecutionRequest`：

```text
taskId, userId, agentId, userInput, executionSnapshot,
finalTokenReserve, deadlineAt, cancellationProbe
```

`TaskRunner -> AgentEngineTaskExecutionDelegate -> DefaultAgentEngine -> TaskSnapshotAgentExecutor`
是持久任务链。默认 `agentflow.task.execution.mode=engine`；`scripted` 仅供 V38/V39 fixture。
保留的 `execute(AgentExecutionCommand)` 是旧非持久兼容入口，旧 `BudgetGuard` 的 usage 处理不能代表 V40。

Runner 读取已持久 snapshot，核对 Agent ID、创建时 ACTIVE 状态、decision/tool/token 预算和
`reserved_final_tokens`，然后调用 Engine。运行期不重新读取当前 Agent Prompt、模型或 binding。
Engine 返回 outcome/消费计数，Runner 独占任务完成、失败、取消、超时终态。

当前支持 `agent-task-snapshot-v1`、`agent-decision-json-v1`、`agent-runtime-rules-v1`。
其中 decision 内容已统一为 `CALL_TOOL / FINISH`，不接受旧动作名称。
普通 Agent/binding 修改不改变已创建任务；资源禁用、软删除、schema/实现不兼容和旧 generation 清理
仍在对应执行边界生效。快照不授予绕过撤销的权限。

Runner/Engine 不持有跨外部调用数据库事务；领取、phase/event、Trace、tool log 和终态复用独立短事务。

## 3. Snapshot RAG

```java
SnapshotRagResult SnapshotRagService.retrieve(TaskExecutionRequest request);
SnapshotRagResult SnapshotRagService.retrieve(TaskExecutionRequest request, Runnable boundaryCheck);
```

结果为 `evidence`、`List<RagRetrievalHitRecord> hits`、`candidateCount`、`staleHitCount`、
`embeddingProfileCode`。Engine 传入共享取消/deadline 检查，负责将结果与 corpus snapshot 写入 Trace。

每个 KB 快照冻结 KB ID、embedding profile、chunk strategy 和 `(documentId,vectorGeneration)` 列表。
当前只支持 `dashscope-te-v4-1024-cosine`、`structured-token-v1`，使用对应的
`dashscope / text-embedding-v4`，并验证 query vector 为 1024 维，不根据当前 KB 配置重新选模型。

`VectorSearchRequest` 增加 document/generation 对列表。Qdrant 每个 OR 分支同时匹配 document 与
其 generation，不能拆成两个独立 allowlist；InMemory adapter 保持同语义。旧四参数 V7 请求保留兼容，
task RAG 不以空 documents 请求退化为整个 KB 检索。`VectorSearchHit` 增加 contentHash；旧三参数 hit
缺少 hash 时不能进入 task evidence。

`KnowledgeChunkMapper.selectSnapshotRetrievableChunks` 回读 canonical PostgreSQL 内容，验证：

- owner/KB/document 归属一致，KB live/ACTIVE，document live 且 parse status 为 COMPLETED；
- chunk 在冻结 document/generation 对内，且仍等于 document 当前 generation；
- vectorization COMPLETED、冻结 chunk strategy、vectorId/contentHash 均满足契约；
- 服务层比对 hit 的 vectorId/hash 与 PostgreSQL，并验证 canonical 正文 hash。

当前 document 无独立 ACTIVE/DISABLED 字段，可用性由 parse status、软删除和 generation 判断。
旧代清理、新代替换、撤销或无法验证的 hit 不进入 Prompt，不回退。失败候选计入 staleHitCount；有效但
低于 threshold、重复 locator 不算过期。同一 chunk 保留最高有效分数；跨 KB 按 score、KB ID、chunk ID
稳定排序，之后取 topK。

| 项目 | 当前边界 |
| --- | --- |
| query/topK | 非空白，query ≤16,384 UTF-8 bytes；topK 1–10，冻结 threshold，无 rerank |
| corpus | 最多 20 KB，每 KB 最多 1,000 个 document/generation 对 |
| candidates | 全局最多 200，单 KB 最多 4 × topK；按剩余 KB 分配容量，避免后续 KB 被跳过 |
| evidence | 总计 ≤12,000 UTF-8 bytes，包含 JSON 转义与不可信标记 |
| hit 正文/metadata | 正文 ≤2,000 UTF-8 bytes；fileName/titlePath 各 ≤256 bytes |

截断以 Unicode code point 为边界，metadata 标记 `contentTruncated` 和完整来源的 `sourceContentHash`。
仅实际进入 evidence 的 hit 获得连续 `S1`、`S2` citation，Prompt 使用 `[S1]`、`[S2]`。

执行时无有效 hit 返回 `evidence=""`、`hits=[]`，仍可调用业务工具；空结果不等于系统失败。
Embedding、vector 或整个 corpus/profile 契约失败则安全失败为 `RAG_RETRIEVAL_FAILED`；取消/deadline
保持原语义。创建期 READY gate 与执行期失效过滤是两个不同边界。

## 4. Decision、Prompt 与 citation

`AgentDecisionParser` 只接受完整单个 JSON object，拒绝前后缀、Markdown、重复 key、额外字段、
未知工具和非 object arguments：

```json
{"type":"CALL_TOOL","toolCode":"order_query","arguments":{"orderNo":"order_1024"},"reason":"检查订单"}
```

```json
{"type":"FINISH","answerPlan":"根据证据解释结果"}
```

reason ≤256 characters，answerPlan ≤2,048 characters。FINISH 只停止循环，必须另发 Final Generation，
不直接发布 decision 原文。最终生成独立计 LLM call/token，不占 decision turn。

`TaskPromptBuilder` 仅使用冻结 system prompt、runtime rules、原始任务、snapshot 工具描述/schema、
本次 evidence 和安全 observation。知识/工具输出标记为 UNTRUSTED_DATA，不能覆盖 runtime rules。
整体 messages 的 JSON UTF-8 大小 ≤64 KiB；工具 data 超限使用显式 truncated excerpt。

最终 citation 必须严格属于本次 hits 白名单。未知/畸形来源标记使任务失败，不能发布答案或伪造来源。
结构化 citations 仅收集答案实际使用的本次来源；无 evidence 不要求知识引用。存在性校验不等于
自然语言结论充分性、citation accuracy 或真实检索质量评估。

## 5. ToolRuntime 完整任务上下文

```java
ToolExecutionCommand.taskScoped(
    toolId, taskId, stepId, userId, agentId,
    executionSnapshot, arguments, deadlineAt, boundaryCheck
);
```

完整上下文包含 owner/Agent、完整 immutable snapshot、deadline 和共享检查。
V39 identity-only command 可用于 log fixture，但不能执行持久 task 工具；standalone 入口继续独立。
Runtime 验证 toolId 在 snapshot 中唯一、当前定义 live/ACTIVE、toolCode/handler 兼容、冻结 schema 自身
hash 正确且等于当前 schema hash、实现为受支持 builtin-v1、readonly/confirmation 契约兼容；参数按
冻结 schema 验证。当前 Agent-tool binding 不参与重新授权。

实际执行使用冻结 name/description/schema 与支持的 handler 映射。当前工具 timeout 只可收紧冻结
值，等待还受剩余 task deadline 限制。schema/实现变化在工具层以 TOOL_SNAPSHOT_MISMATCH 拒绝，
不得用新 schema 解释旧 decision。

重复 key 为 `toolCode + canonical(arguments)` 指纹，object key 顺序不产生新意图：

1. 首次实际执行，缓存成功 observation，toolCallsUsed 增加；
2. 第二次重新验证 snapshot/撤销后复用，保留 TOOL_CALL step 与 `reused=true` 的 TOOL_STARTED/
   TOOL_FINISHED；无新 handler 调用、无第二条实际 tool_call_log，toolCallsUsed 不增加；
3. 第三次以 AGENT_DUPLICATE_TOOL_LOOP 失败。

首次真实工具调用在 RUNNING log 提交后进入 handler，terminal log 提交后再发布工具完成事件。

## 6. 预算、取消、超时与 V20

`max_steps` 映射为 maxDecisionTurns，不是 Trace step 数。实际发出 decision 即消费一次 turn，包括
provider 错误和非法 JSON；首次工具调用在进入 Runtime 前消费机会，参数拒绝不退还。

每次 decision 前估算输入，并为最终输入与 task 创建时持久冻结的 final reserve 留出容量。decision
输出 cap ≤512 tokens；最终输出使用完整 frozen reserve，同时检查剩余总预算和 model context window。
decision/tool 次数耗尽时加入预算说明，尝试一次受限最终生成；成功 terminationReason 为
MAX_DECISION_TURNS/MAX_TOOL_CALLS。若最终输入和 reserve 放不下，则 TOKEN_BUDGET_EXHAUSTED 失败。

provider usage 一致时写 EXACT；缺失时 `TaskTokenEstimator` 按 UTF-8 byte 保守估算并加 message framing，
写 ESTIMATED，不能记零。调用已发出但响应不可用时，以输入估算加输出 cap 计账。混合调用聚合为 MIXED。
这是保守 estimator，不能表述为精确 tokenizer 或 provider 账单。未调用模型的零消费是另一种事实。

先累计返回 usage，再检查取消/deadline、总预算和 decision/citation。非法响应、取消、超时或超额都
不能丢失已观察消费。追加迁移为：

```text
backend/src/main/resources/db/migration/V20__preserve_task_token_overruns.sql
```

不回改 V18/V19。所有状态继续要求 token 非负和 BIGINT 加法一致性；QUEUED/RUNNING/COMPLETED 保持
`total_tokens <= max_total_tokens`，只有 FAILED/CANCELLED/TIMED_OUT 可保存超额事实。超额后停止
后续调用，不能截成预算上限，也不能放宽成功任务防线。

Runner 只计算一次 `startedAt + snapshot.timeoutSeconds`；排队时间不计入执行 deadline。同一 request
的 deadline/probe 贯穿 RAG、decision、工具和最终生成。外部调用前后检查，等待期间也观察取消。
RAG 的 embedding/vector/PG 检查、Engine 外部等待和 ToolRuntime handler 等待均复用该边界。

超时/取消停止调用方推进，并向外部工作发出 best-effort interrupt；不承诺强杀任意 Java handler、
数据库操作或 provider 请求。未确认消费只能按 ESTIMATED 表达，不提供 provider cancel 确认或恢复协议。
Runner 落终态前再次检查信号；timeout/failed 条件更新若输给并发取消请求，必须重新观察取消并收敛，
不能因一次 CAS 返回 false 就把任务遗留在 RUNNING。

## 7. Trace、事件与答案原子性

| 动作 | stepType | 专项日志/语义事件 |
| --- | --- | --- |
| RAG | PRE_RETRIEVAL | rag_retrieval_log + hits，RAG_FINISHED |
| decision | LLM_DECISION | llm_call_log/DECISION，DECISION_FINISHED |
| 工具/复用 | TOOL_CALL | 实际调用写 tool_call_log；TOOL_STARTED/TOOL_FINISHED 标 reused |
| 最终生成 | LLM_FINAL_GENERATION | llm_call_log/FINAL_GENERATION，FINAL_GENERATION_STARTED |

复用 V39 ExecutionRecorder：先提交 RUNNING step，事务外调用，再提交专项日志、step 终态与语义事件。
retrieval log/hits 同事务写入；此前已提交事实不因后续失败回滚。Trace 失败不能当普通业务成功返回，
也不代表拥有数据库不可用时的补偿队列。

非法 decision 仍写 FAILED LLM log，保存 usage、quality、latency 与安全诊断；response_text 不存非法
原文，不把它再次作为 JSON 送入 sanitizer。合法 decision 也只存安全动作投影，不保存自由 reasoning/CoT。

所有事件由唯一 TaskEventAppender 的 `UPDATE ... RETURNING last_event_sequence` 分配，禁止第二套
allocator 或无锁 MAX+1。`AgentTaskLifecycleTransactionService.complete` 在一个完成事务执行：

```text
条件写 COMPLETED + final_answer + citations + 计数/usage
  -> 全部 ANSWER_CHUNK[0..n-1] -> TASK_COMPLETED -> COMMIT
```

chunk payload 为 `{"chunkIndex":0,"text":"..."}`，序号从 0 连续增长，按事件 sequence 拼接必须还原
final_answer。每条按实际 JSON UTF-8 bytes 限制为 16,384 bytes，包含 metadata/转义开销，安全处理
Unicode 补充字符。中间 chunk/event 失败时，全部分片、答案、citations、终态及游标更新一并回滚。
不能先提交半套答案；这不是 provider token streaming。

## 8. 重点验收与可复现命令

AgentTaskExecutionPostgresIntegrationTest 使用真实 Runner、Engine、ToolRuntime、snapshot resolver、
recorder/PostgreSQL；仅模型、embedding、vector 边界可控替换。核心必须产生一次 RAG、决策循环、
两次实际工具、一次最终生成，且 task、usage、Trace、citations 和事件一致。

重点覆盖 snapshot 不漂移、KB/document/tool 撤销、无 hit/旧 generation、schema/实现变化、预算边界、
重复调用、缺失 usage、未知 citation、取消/超时及其竞态、非法 decision 安全日志、答案分片故障回滚，
并回归 V38 生命周期/V39 Trace。BUILTIN 工具访问本地示例业务数据，不是外部支付服务验收。

从仓库根目录使用 JDK 21 与显式 Mockito javaagent；其他环境替换本地运行时路径：

```bash
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  mvn -f backend/pom.xml \
  -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar \
  '-Dtest=SnapshotRagServiceTest,TaskSnapshotAgentExecutorTest,TaskScopedToolRuntimeTest,AgentDecisionParserTest,AgentPromptBuilderTest,DefaultAgentEngineTest,InMemoryVectorStoreGatewayTest,QdrantVectorStoreGatewayTest,V18AgentTaskMigrationContractTest,V19AgentExecutionTraceMigrationContractTest' \
  test
```

PG 测试清理测试表，只能使用独立可丢弃数据库。以下地址/用户是本次使用的本地验收配置。
临时实例验收后已停止，可用 `pg_ctl -D /tmp/agentflow-v40-pg start` 重启：

```bash
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  mvn -f backend/pom.xml \
  -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar \
  -Dagentflow.postgres.integration=true \
  -Dagentflow.postgres.url=jdbc:postgresql://localhost:55440/agentflow_v40_test \
  -Dagentflow.postgres.user=v40 -Dagentflow.postgres.password= \
  '-Dtest=AgentTaskExecutionPostgresIntegrationTest,AgentTaskPostgresIntegrationTest,AgentExecutionTracePostgresIntegrationTest' \
  test
```

### 实际结果

2026-09-05 使用 JDK 21、显式 Mockito javaagent 与 PostgreSQL 18.4，在新空库实际应用 V1–V20 共
20 个 migration。以下全量命令启用 opt-in PostgreSQL 测试，执行 608/608 通过，Failures=0、Errors=0、
Skipped=0，耗时 11.078 秒；`git diff --check` 通过。

```bash
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  mvn -f backend/pom.xml \
  -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar \
  -Dagentflow.postgres.integration=true \
  -Dagentflow.postgres.url=jdbc:postgresql://localhost:55440/agentflow_v40_test \
  -Dagentflow.postgres.user=v40 -Dagentflow.postgres.password= \
  test
```

全量包含 V40 PostgreSQL 15 项、V38 PostgreSQL 8 项、V39 PostgreSQL 6 项，以及 RAG unit 8 项、
snapshot executor 16 项、task tool 10 项、Runner 竞态 unit 1 项。验收发现并修复答案分片中 emoji 的
序列化计数边界，以及 Runner timeout CAS 输给取消请求后可能未收敛的竞态。

这些结果证明真实持久后端组件与 PostgreSQL 上的闭环、约束和回滚行为；模型、embedding、vector 使用
可控替身。真实模型/Embedding/Qdrant provider E2E 未纳入本切片，不声称通过。

## 9. 明确不做

- 公共 Task/Trace Controller、HTTP/SSE payload contract、回放接口和前端；
- 真实 provider E2E、检索召回率/泛化或 citation accuracy 结论；
- query rewrite、rerank、hybrid search、运行中换 corpus 或新 generation 回退；
- provider token streaming、请求取消确认；
- 重试、retry attempt、outbox、崩溃恢复、stale RUNNING 扫描或多实例调度；
- 新工具种类、写操作工具、人工审批系统、动态 PolicyGuard；
- CoT 存储/展示、历史 Trace 删除/retention job；
- 修改 V1–V19 已发布 migration，或允许成功任务超预算。

## 面试问题与回答

### 问题 1：快照已冻结工具和知识，为什么运行时还要查数据库？

回答：冻结的是依赖选择，普通 Agent/binding 修改不能漂移它；紧急撤销仍生效。工具检查当前状态、
schema/实现，RAG 检查 owner、生命周期、generation 与 canonical hash，不重新选择 binding 或新代。

### 问题 2：向量命中为什么还要回查 PostgreSQL？空结果为什么能继续？

回答：向量 point 可能过期，payload 不是正文权威；只有 PostgreSQL 能证明的当前有效 hit 才进入证据。
无证据可以继续查订单/支付工具，provider/corpus 契约失败才是系统失败；此流程不证明真实召回质量。

### 问题 3：为什么 FINISH 后还要调用模型？reserve 能保证一定有答案吗？

回答：FINISH 只有 answerPlan，最终生成独立读取证据和 observation。每轮预留最终输入和 frozen reserve，
次数用尽后可尝试受限生成，但预算不足或 provider 失败仍须失败，不能把 reserve 当输出保证。

### 问题 4：数据库为什么允许保存超额 token？

回答：消费已发生，预算只能阻止后续调用。V20 仅放宽 FAILED/CANCELLED/TIMED_OUT，上限仍保护成功
任务，非负/加法不变量保留；缺失 usage 写 ESTIMATED，不记零，也不冒充精确账单。

### 问题 5：重复调用为什么是首次执行、第二次复用、第三次失败？

回答：同一 canonical 意图的一次复用避免重复工具消费，第三次说明循环未推进。复用仍验证撤销并记录
reused step/event，无新 tool log/计数；这是任务内防循环策略，不是通用 retry 或跨任务缓存。

### 问题 6：如何同时保留错误 Trace，又避免发布半套答案？

回答：非法 decision 的安全 FAILED log 保留 usage/latency，原文不再进 JSON sanitizer；早期事实由短
事务保留。答案发布则必须同事务提交 final_answer、citations、全部 chunk 和 TASK_COMPLETED，中间失败
整体回滚。数据库故障注入测试是否通过，以本节实际验收结果为准。
