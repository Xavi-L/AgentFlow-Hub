# V48 任务失败与浏览器恢复 E2E 接口包说明

> 路线位置：M4G-D2，接续 V47/M4G-D1 真实 provider 与 Qdrant 成功主路径。
> 状态：2026-09-08 冻结范围；2026-09-09 已实现并完成受控验收，真实浏览器 22/22、PostgreSQL 803 项逐例检查及 3 项全局检查通过。
> 实施基线：2026-09-09 已 fetch `origin/main`，当时本地与远端均为 `a2d8a29`。本片没有修改生产执行路径；V47 真实成功基线独立保留。

## 目标与冻结范围

在真实浏览器、JWT、PostgreSQL 和现有任务执行链路上，用验收专用故障注入稳定复现失败，
验证任务停止推进、正确保留已发生的执行与用量事实，并在浏览器恢复观察后与持久结果收敛。
本片的“恢复”仅指断网、SSE 重连、刷新后的浏览器观察恢复，不恢复或重新执行 TaskRunner。

冻结以下五组工作：

1. 非法决策、重复工具循环、工具参数拒绝与执行失败、检索异常、最终引用非法。
2. token 耗尽、整体 deadline、用户取消，以及空检索可继续使用工具、次数上限可进入受限最终生成的语义对照。
3. 同一幂等键重复提交只产生一个任务及一次执行；浏览器断网、SSE 重连和刷新后，终态、答案、引用与 GET/Trace 一致。
4. 独立验收入口与逐例证据，标明受控边界，核对错误码、调用次数、用量、终态事件及停止发布答案。
5. 保留 V43/V45/V46 受控回归和 V47 真实成功基线；只修复本矩阵暴露且闭环必需的问题。
   若修改生产执行路径，补跑 V47 真实主路径。

范围依据为现行 `spec-docs/agentflow-hub-implementation-roadmap.md` 的 M4G 失败 E2E 目标、
V40–V43 已有执行/REST/SSE/前端契约，以及 V47 契约中的后续失败矩阵边界。
路线图的 “RAG empty” 在本片是非失败对照，不改变当前 Agent 空检索语义；V9 单轮知识问答的空上下文拒绝不套用到 Agent。
本片交付独立验收脚本、test-source 夹具、浏览器矩阵和采证器；下文矩阵为契约，实际结果单列于“验收记录”。

## 沿用接口与数据契约

继续使用 `/api/v1`、真实 JWT principal、owner scope 和既有公开 DTO；不新增业务字段或接口。

| 操作 | 既有接口 | 本片核对内容 |
| --- | --- | --- |
| 浏览器登录 | `POST /auth/login` | 真实后端签发 JWT；REST/SSE 使用 Bearer，不把 token 放入 URL |
| 配置任务前置资源 | V45/V46 已有知识库、Agent 和绑定接口/夹具 | 合法预算、ACTIVE Agent、创建时 READY gate 与冻结快照；预置数据须标明 |
| 创建/确认原提交 | `POST /agents/{agentId}/tasks` | 新建 201；同 owner、同 key、同 Agent 和原始输入返回原 task 200 |
| 幂等冲突 | 同一创建接口 | 同 key 改变原始输入或 Agent 为 `409 TASK_IDEMPOTENCY_CONFLICT`，不新增执行 |
| 请求取消 | `POST /tasks/{taskId}/cancel` | RUNNING 可以只确认 `cancelRequestedAt`，由 Runner 收敛；终态取消幂等 |
| 最终任务及历史 | `GET /tasks/{taskId}`、`GET /tasks` | 持久状态、原因、答案、引用及使用计数，不触发执行 |
| Trace 与事件 | `GET /tasks/{taskId}/trace`、`GET /tasks/{taskId}/events` | 公开 Trace、真实 SSE 持久回放及既有游标语义 |

幂等键在 owner 内唯一；同 key 的请求指纹包含 Agent ID 和**原始** `userInput`，保留空格。
未知 POST 结果保存原 key/Agent/input，浏览器显式确认原提交时只能复用这些值；不得因刷新或重连自动生成新 key。
确认同一创建请求不等于重试执行，FAILED/CANCELLED/TIMED_OUT 的原 task 也不能因此重跑。

沿用任务字段 `status/phase/terminationReason/errorCode/errorMessage`、
`decisionTurnsUsed/toolCallsUsed/inputTokens/outputTokens/totalTokens/tokenUsageQuality`、
`finalAnswer/citations/lastEventSequence`。运行中计数不承诺实时更新，验收以终态持久汇总与调用日志核对。
公开 ID 继续使用字符串；numeric long 的事件序号必须无损解析，以 `BigInt` 比较，不把服务端事件上界当作已处理游标。
引用只用已有 `citationId/documentId/chunkId/vectorGeneration`，详情关联本次 Trace 的 RAG hit 快照。
可空字段沿用当前 JSON 省略 null 的序列化与前端规范化方式，不强制响应新增显式 null。

## 验收链路与受控边界

独立入口使用 test-source fixture 启动真实应用组件，链路为：

```text
浏览器登录与提交 → JWT/公开 Controller → 幂等创建事务与冻结快照
→ after-commit dispatch → TaskRunner claim → TaskSnapshotAgentExecutor
→ SnapshotRagService / LlmGateway / DefaultToolRuntime
→ 持久 Trace、用量、终态事务和事件 → SSE / GET → 浏览器刷新核对
```

- 数据库为全新的 disposable loopback PostgreSQL，应用现有 V1–V20；不得使用开发库或修改 migration。
  可以预置用户、Agent、绑定、READY 文档和演示数据，逐项标注 fixture 来源；被验收任务由浏览器通过真实 API 创建。
  不直接写任务终态、答案、Trace 或事件来伪造执行结果。
- LLM 的决策、最终文本、usage 及等待闸门由验收专用 `LlmGateway` 控制；仍经过生产 parser、预算检查、引用校验和 recorder。
  embedding/vector 的响应或异常在对应 Gateway 边界注入，保留真实 `SnapshotRagService` 的检索、来源回查和冻结 generation 约束。
  检索异常必须发生在创建准入通过后的执行期，不能用 READY gate 拒绝替代。
- 工具参数拒绝使用真实冻结 schema 与校验器。执行失败在既有 builtin handler/调用边界注入，
  保留真实 `DefaultToolRuntime`、task-scoped command、日志、预算与中断检查；不能直接替换整个 Runtime 返回伪造失败。
  正常工具调用继续查询现有演示表，不宣称接入外部订单或支付系统。
- 控制信号使用 test-source 状态、独立本地控制文件或等效进程内闸门，按 case/task 隔离；不增加生产故障开关、业务接口或执行协议。
  闸门必须有“已进入调用”和“允许返回/已退出”的可观察信号与有限等待，避免仅靠固定 sleep 猜时序。
- 浏览器网络故障用 Playwright 断网、丢响应或中断实际 SSE 连接注入。任务数据和事件仍来自真实后端，
  不用合成成功 HTTP/SSE 响应代替持久链路；仅标注为浏览器传输受控故障，不声称覆盖物理网络故障。
  R03 的 test-only `window.fetch` 包装把独立 AbortController 连接到真实 SSE fetch，保留 runtime 原 signal 未取消；
  异常中断后仍由现有 runtime 重连。包装同时读取真实响应副本采证，不合成帧；重连请求由测试闸门暂缓到后台终态已落库。
- 入口隔离控制目录、端口、日志和数据库，拒绝复用已有 `pgdata`，结束后停止本次进程并保留证据。
  故障组件只在验收 test classpath 装配，不进入正常 main 应用或 V47 真实 provider 入口。

这套矩阵证明受控外部边界下的完整应用行为，不是对真实 provider/Qdrant 故障的在线复现，也不是故障概率或可靠性统计。

## 失败路径矩阵

下表均以独立新 task、合法准入、足够预算和未触发取消/deadline 为前提，除 F05 外检索成功。
模型脚本只给出当前案例所需最短序列；多余模型或 handler 调用应令案例失败，不能隐式回退到成功响应。

计数分别记录：`D=decisionTurnsUsed`，`T=toolCallsUsed`，`H=实际 handler 进入次数`，
`F=FINAL_GENERATION 调用次数`。T 是实际调用机会消费，参数拒绝也消费一次；不能用 T 推导 H 或工具日志状态。
每例还需对照实际 Gateway 调用计数与 `llm_call_log`，不能把 DECISION 和 FINAL_GENERATION 混计。

| 编号 | 确定性触发 | 预期 task 终态 / 原因 / errorCode | 关键调用与持久证据 |
| --- | --- | --- | --- |
| F01 | 首个决策分别返回非法 JSON、JSON 合法但不符合 decision schema 的对象 | `FAILED / SYSTEM_ERROR / AGENT_INVALID_DECISION` | 每个变体 D=1、T=H=F=0；DECISION 日志 FAILED，已发生 usage 保留，无后续调用 |
| F02 | 同一 `toolCode + canonical(arguments)` 意图连续三次，预算允许三轮决策 | `FAILED / SYSTEM_ERROR / AGENT_DUPLICATE_TOOL_LOOP` | D=3、T=H=1、F=0；第一轮实际执行并有一条成功工具日志，第二轮 `reused=true` 且不新增 handler/log，第三轮停止 |
| F03 | 合法 CALL_TOOL 和 object arguments，但缺少必填参数或违反冻结 schema | `FAILED / SYSTEM_ERROR / TOOL_ARGUMENT_INVALID` | D=1、T=1、H=F=0；一条 REJECTED 工具日志，保留 TOOL_CALL 失败与错误，不退还调用机会 |
| F04 | 参数合法，handler 进入后抛出受控普通执行异常 | `FAILED / SYSTEM_ERROR / TOOL_EXECUTION_FAILED` | D=T=H=1、F=0；实际工具日志由 RUNNING 转 FAILED，无自动重试，不进入最终生成 |
| F05 | 执行期 embedding 或 vector search 边界抛出检索异常；分别覆盖两种边界 | `FAILED / SYSTEM_ERROR / RAG_RETRIEVAL_FAILED` | D=T=H=F=0；PRE_RETRIEVAL 与检索日志 FAILED；不发 decision/工具/final 调用 |
| F06 | 合法 FINISH 后独立 final 返回未知 `[S999]`，另例返回畸形 `[[S1]]` 等引用标记 | `FAILED / SYSTEM_ERROR / AGENT_INVALID_CITATION` | 每个变体 D=1、T=H=0、F=1；final usage 保留，最终生成日志/步骤失败，不发布该正文或结构化引用 |

F01 是决策解析失败，F03 是工具参数语义校验失败，不能用非 object arguments 让 F03 提前落到 F01。
F04 固定普通 handler 异常，避免业务异常保留自身业务码或工具局部 timeout 被误当作本例。
F06 必须有当前有效 hit 作为白名单对照；验证引用存在性，不扩展为自然语言引用准确率评估。

上述失败均只允许一条 `TASK_FAILED`，且 `finalAnswer` 为空、`citations=[]`、
`ANSWER_CHUNK=0`、`TASK_COMPLETED=0`。此前成功的 RAG、decision 或工具事实不得因后续失败回滚。

## 预算、中断与非失败对照

沿用现有 `maxSteps → maxDecisionTurns` 映射，它不是 Trace step 总数。
只有发出 decision 才消费 turn；独立最终生成单独计 LLM 与 token，不消费 decision turn。
冻结 final reserve 及实际输出 cap 以当前 task snapshot/LLM 请求为准，不照抄旧文档的默认 512 作为固定上限。

| 编号 | 触发与预期 | 必须核对的边界 |
| --- | --- | --- |
| B01a | 合法公开配置及输入使第一次 decision 前剩余总预算不足；`FAILED / TOKEN_BUDGET_EXHAUSTED / AGENT_TOKEN_BUDGET_EXHAUSTED` | 记录实际输入估算、冻结 reserve 和预算；D=T=H=F=0，LLM 调用 0，零消费不得伪造为 provider EXACT usage |
| B01b | 一次合法 FINISH 响应报告一致但超过总预算的 usage；同 B01a 终态 | D=1、T=H=F=0；真实执行器先累计受控 reported usage，再拒绝继续；保存超额值，不截到上限。可固定 maxTotalTokens=50000、usage=50000+20=50020 |
| B02 | 已进入独立最终生成后，由受控等待跨越 Runner 的整体 deadline；`TIMED_OUT / DEADLINE_EXCEEDED`，task errorCode 为空 | D=1、F=1、T=H=0；只有 `TASK_TIMED_OUT`，在途调用用量按实际可观察信息计账；释放晚到结果后仍无答案发布 |
| B03 | 已进入独立最终生成时，浏览器点击取消，确认请求写入并等待任务终止，再释放受控晚到工作；`CANCELLED / USER_CANCELLED`，task errorCode 为空 | D=1、F=1、T=H=0；只接受服务端最终状态，不能把 cancel HTTP 200/RUNNING 直接显示为 CANCELLED；只有 `TASK_CANCELLED`，晚到结果不得发布 |
| C01 | 创建时 READY，执行期检索正常返回空 hit，随后合法工具调用、FINISH 和无知识引用 final | `COMPLETED / ANSWERED`；检索日志 SUCCESS 且 hits=[]，D=2、T=H=1、F=1，citations=[]；不强行改成 RAG 失败 |
| C02 | maxSteps=4、maxToolCalls=3；依次 A、A 复用、B、B 复用，随后受限 final | `COMPLETED / MAX_DECISION_TURNS`；D=4、T=H=2、F=1，两个复用不新增工具日志；预算充足、final 引用合法 |
| C03 | maxSteps=3、maxToolCalls=1；首次 A 成功后受限 final | `COMPLETED / MAX_TOOL_CALLS`；D=T=H=F=1；不得伪造模型 FINISH，不再发第二次决策 |

C02 中 A/B 使用两个不同受支持工具意图，避免第三次同意图先触发循环保护。三个 C 案例是正确行为对照，
不计为失败终态。受限最终生成依然需要输入容量、final reserve、整体 deadline 和引用校验通过；次数上限不是成功保证。

整体 deadline 从 Runner claim 后的 `startedAt + snapshot.timeoutSeconds` 计算，排队时间不计入。
B02 要证明整体时限到达，不能仅触发 provider 超时或 `TOOL_TIMEOUT` 后就标为 TIMED_OUT。
`TASK_CANCELLED/TASK_TIMED_OUT` 可存在于内部边界异常、步骤或专项日志，不能据此要求 task 顶层具有同名 errorCode。
已有 QUEUED 取消、终态重复取消及状态竞态回归继续保留；本片以 RUNNING/final 在途的浏览器场景补强停止发布验证。

usage 使用既有 `EXACT/ESTIMATED/MIXED/UNKNOWN`：受控 provider 报告的精确数值只证明计账路径，不代表外部账单。
已发出请求却拿不到可用响应时，按输入估算加输出 cap 记 ESTIMATED，不能清零；所有调用之和与 task 持久汇总一致。
这里的 task token 汇总只包括 DECISION 与 FINAL_GENERATION 的 LLM 消费，不包括检索 embedding 或外部服务的总消耗。
B02/B03 至少包含响应不可用的在途分支，与 F01/F06/B01b 的已知 usage 分支互为边界对照。
V20 已允许失败、取消或超时保存超额事实，本片不新增迁移或放宽 COMPLETED 的预算约束。

B02/B03 必须观察闸门进入，再制造 deadline/取消，最终释放受控晚到工作并等待它退出，之后再次读取数据库与 GET/Trace。
验证不新增后续 decision/工具/final 调用、不出现答案或第二个终态；不能以短暂无输出代替停止证明。
取消和超时只承诺调用方停止推进并 best-effort interrupt，不承诺强杀 provider、Java handler 或数据库操作。

## 幂等与浏览器恢复矩阵

每个 F/B 失败或中断案例均做终态 GET/Trace/PostgreSQL 核对和浏览器刷新。额外恢复场景复用下表代表案例，
不要求“全部故障 × 全部网络动作”的组合。

| 编号 | 浏览器动作/受控传输边界 | 必须成立 |
| --- | --- | --- |
| R01 | 同 owner、同 key、同 Agent/原始输入并发提交，终态后再提交；同 key 不同输入作冲突对照 | 新建恰一条 201，其余同请求为 200 且同 taskId；只有一条 task、TASK_CREATED、TASK_STARTED 和一轮执行链路。409 冲突无新增执行；至少在 FAILED 终态后核对不重跑 |
| R02 | 后端 POST 已真实提交后丢弃响应，刷新运行入口，再显式确认原请求 | 待确认的原 key/Agent/input 保留，返回原 task 200；刷新或上线本身不自动 POST，不生成新 key，不产生第二次执行 |
| R03 | 页面已处理若干事件时，让真实 SSE 异常中断，后端继续运行，再允许重连 | 从最后成功处理游标恢复，事件不丢、不重复展示；请求/实际收到事件均留证。至少让一个 FAILED 终态在断开窗口内产生；仅看到“重新连接”文案不算通过 |
| R04 | 浏览器 offline 时后端完成，随后 online | 用有合法引用的受控 COMPLETED 对照及一个失败/中断案例；两者分别恢复真实终态，成功答案/引用与 GET/Trace 一致，失败仍无答案；断网不触发服务端取消或新任务 |
| R05 | RUNNING 中刷新，以及已终态后刷新；另让一次终态 GET 暂时失败再手动恢复 | RUNNING 从 Trace 重建后继续 SSE；COMPLETED/FAILED/TIMED_OUT/CANCELLED 均按各自案例恢复。GET 未收敛时显示终态待同步；恢复成功后才 settled，不把暂存 draft 当最终答案 |

R01 的并发重复请求可以由已登录浏览器上下文发送真实 HTTP，用 PostgreSQL 和独立调用计数证明唯一执行；
按钮防双击本身不足以证明后端幂等。R02 必须证明首次请求确实已提交，不以尚未送达后端的 abort 代替响应丢失。
R03 要有非零已处理游标及至少一个断开期间新持久事件，避免刚打开页面或已收齐终态时断开而没有实际验证 replay。
使用闸门固定异常中断、后台产生事件、重新订阅的顺序；正常 EOF 后若 GET 已见终态，现有实现可直接 GET/Trace 收敛，
该正确分支不算 R03 的 SSE replay 证据，也不能据此要求生产代码强行重连。

恢复继续遵守 V43：先读取 Trace 的同一快照，从 0 验证并应用连续事件，再用已处理游标订阅 SSE。
既有终态收敛要求 GET task 与 Trace 的 task 在状态、答案、引用和事件上界上一致才标记 settled；
本片同时在验收证据中核对终止原因、错误码和用量，不能只检查页面状态标签。
源文档详情来自历史 RAG hit，不要求查询当前源资源才能显示引用。
自动重连仍为 1/2/4/8/16 秒、至多 5 次，gap/协议错误停止后手动恢复；不改成无限重连或自动执行重试。
已有游标无损解析、去重/gap、旧响应隔离、JWT 失效及有界重连重点回归保留，不另增完整传输协议矩阵。

## 逐例证据与通过标准

入口生成运行摘要、逐例结构化证据和可查看的浏览器截图/公开 Trace，并保留后端/PostgreSQL 日志。
每个 case 至少记录以下内容：

| 证据组 | 必填内容及判定 |
| --- | --- |
| 身份与边界 | runId/caseId/taskId/agentId、代码版本、配置与冻结预算、注入组件/动作/时机、预置资源来源；凭据不进入证据 |
| HTTP 与观察 | 新建/复用/冲突/取消 HTTP 状态、业务拒绝码、提交次数、SSE 重连请求和已处理游标、刷新前后 DOM 与 GET/Trace；不把 HTTP 201/200 当任务成功 |
| 任务结果 | status/phase/terminationReason/errorCode、finalAnswer/citations、cancelRequestedAt/startedAt/completedAt、lastEventSequence；数据库、公开 task 与 Trace.task 对齐 |
| 调用与用量 | D/T/H/F、实际 Gateway 调用数、DECISION/FINAL_GENERATION 日志、工具状态与 reuse、retryCount、input/output/total 与质量；预期和实际分列 |
| 事件与停止 | sequence 连续且唯一、上界与持久事件一致、唯一匹配终态；失败/取消/超时 ANSWER_CHUNK 与 TASK_COMPLETED 均为 0；释放晚到工作后再次核对 |
| 答案与引用 | 成功时 ANSWER_CHUNK 按序拼接等于持久 finalAnswer，引用逐项映射到本次有效 hit；失败正文不得进入答案区或被恢复为 final |
| 结果分类 | 逐例 PASSED/FAILED/BLOCKED/NOT_RUN、断言差异、缺失证据、日志/截图路径；运行整体结论不能隐去失败或跳过 |

PostgreSQL 只读采证覆盖 `agent_task/agent_task_event/agent_step/llm_call_log/rag_retrieval_log/rag_retrieval_hit/tool_call_log`，
并核对创建时的 snapshot。公开 GET/Trace 是前端契约证据，数据库及受控调用计数是独立交叉证据，不能只把同一 JSON 比较两遍。
工具复用有步骤/事件而没有新 handler/log；参数拒绝有 REJECTED log 而没有 handler，采证器必须保留这种差别。
失败的 LLM/Trace 仍保留安全诊断和已观察用量；原始推理、JWT、密码、API key 等不进入公开证据。

预期任务 FAILED 且所有断言满足时，该**案例**为 PASSED；基础设施缺失或无法启动为 BLOCKED，
发生非预期终态、错误码/计数不符、恢复不一致或采证断言不满足为 FAILED。缺失必需证据不能算 PASSED。
必须矩阵中仍有 BLOCKED/FAILED/NOT_RUN 时，不得宣称 V48 完成；修复后独立运行并保留此前失败历史，不覆盖为一次成功。
Playwright 自动重试为 0，任务/模型/工具自动执行重试为 0；既有 SSE 重连及显式同 key 确认原提交另行统计。

## 实现入口与回归

以下文件已实现；启动参数与证据说明同步记录于 `frontend/e2e/README.md`：

| 文件 | 职责 |
| --- | --- |
| `scripts/v48-failure-recovery-acceptance.sh` | 独立前后端/临时 PostgreSQL 启动、故障矩阵调度、有限等待、清理本次进程及汇总 |
| `backend/src/test/java/com/agentflow/acceptance/V48FailureRecoveryFixture.java` | 验收专用依赖、逐例脚本与闸门，复用生产创建/执行/持久化组件 |
| `frontend/e2e/failure-recovery.config.ts`、`failure-recovery.spec.ts` | 独立 testMatch、真实浏览器故障/恢复动作与公开结果断言，workers=1、retries=0 |
| `frontend/e2e/failure-recovery-evidence.ts` | 独立固定的预期矩阵、公开 Task/Trace、调用与用量、事件/答案/引用不变量 |
| `scripts/v48-storage-evidence.py` | 只读数据库证据、计数/事件/答案不变量核对；按需复用现有采证辅助代码 |
| `scripts/v48-storage-evidence-test.py` | 4 组离线采证反例，验证重复任务、失败后答案、用量截断、公开结果分歧和缺失矩阵不能通过 |

```bash
# 单例诊断；只证明被选案例，不代表全矩阵通过
bash scripts/v48-failure-recovery-acceptance.sh --case F01_JSON
# 完整验收，每次使用全新目录和数据库
bash scripts/v48-failure-recovery-acceptance.sh
# 采证器反例检查，不调用模型或启动数据库
python3 scripts/v48-storage-evidence-test.py
```

依赖 Java 21、Maven、Node 22.12+、PostgreSQL binaries 和 Playwright Chromium/Chrome。
默认端口为 5178/18048/55448，分别可用 `V48_FRONTEND_PORT/V48_BACKEND_PORT/V48_PG_PORT` 覆盖；
`V48_CONTROL_DIR` 默认为新建临时目录，拒绝已有 `run-started` 或 `pgdata`。
数据库固定为 loopback `agentflow_v48_browser`，fixture 用户、Agent、绑定和 READY 语料来自明确的种子数据。
入口先检查独立浏览器 TypeScript、编译 test classpath，再启动真实应用和浏览器；无论浏览器是否通过均尝试只读采证。
`run-result.json` 区分 single-case/full-matrix 与 BLOCKED/FAILED/PASSED，单例采证 `fullMatrixPassed=false`。
`manifest.json` 仅在本地私有运行目录保存 fixture 登录信息，逐例公开证据不复制密码/JWT。

默认 Playwright 配置已显式排除 V47 和 V48 spec，V48 独立配置只匹配 `failure-recovery.spec.ts`。
V47 独立配置仍只运行真实成功主路径；三个入口互不隐式启动。

验收需同时覆盖 V48 完整受控矩阵与证据/截图、
`bash scripts/v46-browser-acceptance.sh` 的 V43/V45/V46 受控回归，以及变更范围对应构建和重点测试。
不以既有单元/数据库测试通过替代新浏览器矩阵，也不为纯夹具/文档变更无差别重复全部后端测试。

V47 真实成功基线沿用 [48_REAL_PROVIDER_E2E_PACKAGE_INTERFACE.md](48_REAL_PROVIDER_E2E_PACKAGE_INTERFACE.md)
中的 2026-09-07 固定模型成功记录及已保留失败历史；本轮阅读到的记录不是 V48 新执行结果。
若后续改变生产执行路径，包括创建/调度、RAG、决策/Prompt、工具、预算/中断、最终引用校验、Trace/终态发布，
先运行 `bash scripts/v47-real-provider-acceptance.sh --preflight-only`，预检通过后补跑同入口的真实主路径。
必须用修改后的代码、正常 main 应用、真实 Chat/DashScope/Qdrant 和独立新 task，保持两工具、独立 final、有效引用及刷新一致性的原门槛。
不可达、缺凭据或失败如实记录，不能用 V48 受控 PASS 或旧成功任务代替；该必需回归未通过时 V48 不完成。
仅验收夹具/浏览器测试/文档变化且没有修改生产执行路径时，不强制再次消耗真实 provider 配额。

## 验收记录

2026-09-09 在基线 `a2d8a29` 加本片验收代码上完成。全部受控运行使用真实 Chrome/Playwright、JWT、
独立 PostgreSQL 和生产执行组件；未更改生产 Java/前端运行代码、API、迁移或执行协议。
唯一启动脚本修复是端口预检使用 `SO_REUSEADDR + bind + listen`，避免已关闭连接的残留状态被当作活动监听。
本轮无需触发条件性的 V47 真实主路径重跑，既有真实成功与失败历史保持原样。

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| 前端已有单元测试 | 8 文件，76/76 | `/private/tmp/v48-frontend-tests.log` |
| 前端生产构建 | vue-tsc 与 Vite 通过 | `/private/tmp/v48-frontend-build.log` |
| 后端重点测试 | 42/42，无失败/跳过：Executor 30、Runner 1、TaskScopedToolRuntime 10、CreationTransaction 1 | `/private/tmp/v48-focused-backend.log` |
| 默认受控浏览器回归 | V46/V45/V43 19/19，50.9 秒 | `/private/tmp/v48-v46-regression.log`，运行目录 `agentflow-v46-browser.M6DNBv` |
| V48 F01 单例 | 1/1，2.4 秒；早期采证器 32 项通过，明确 fullMatrixPassed=false | `/private/tmp/v48-f01-smoke.log`，目录 `agentflow-v48-browser.caD6VY` |
| V48 完整矩阵 | 22/22，34.0 秒；803 项逐例 + 3 项全局检查通过，collectorErrors=[] | `/private/tmp/v48-full-matrix-2.log`，目录 `agentflow-v48-browser.YJIcUU` |
| 采证器离线反例 | 4 组通过；其中缺例反例预期产生 FAIL，不是实际验收失败 | `/private/tmp/v48-evidence-checks.log` |

上述浏览器时间均为 Playwright 阶段，不含编译、服务启动与清理。完整入口也通过新的浏览器 TypeScript 检查与
包含最终 worker-exit 闸门实现的 Java test-compile；没有为本片重跑无关的完整后端测试。

提交前再次通过 V48 浏览器 TypeScript 检查、Shell/Python 语法检查、采证器4组离线反例和 diff 检查；
日志为 `/private/tmp/v48-precommit-typescript.log` 与 `/private/tmp/v48-precommit-evidence.log`。
已重新读取下述完整运行结果，确认22例、803项逐例及3项全局检查仍为PASS，无跳过或collector错误。
本次只调整交付说明，未重复浏览器矩阵或调用真实provider，实际E2E证据仍为上述2026-09-09运行。

完整证据目录为：
`/private/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v48-browser.YJIcUU`。
根目录保留 `run-result.json`（PASSED/exit 0/full-matrix）、`storage-evidence.json`（PASS/fullMatrixPassed=true）、
`browser-results.json` 和服务日志；每个 `cases/<caseId>/` 保存独立调用 `calls.jsonl`、闸门信号、
`browser-evidence.json`、`storage-evidence.json` 与 `runtime.png`。
证据目录属于本次临时运行，复现依赖仓库入口而不是该临时路径长期存在。

关键逐例事实：

| 案例 | 实际证据 |
| --- | --- |
| F01_JSON/SHAPE | 各一轮非法决策，15 EXACT tokens，6 条事件，唯一 TASK_FAILED，无工具/final/答案 |
| F02 | 3 次 decision、1 次 handler/成功工具日志、1 次复用；45 EXACT tokens，17 条事件，第三轮 AGENT_DUPLICATE_TOOL_LOOP |
| F03/F04 | 各消费 1 次工具机会；F03 为 REJECTED 且 handler=0，F04 handler=1 且 FAILED；均 15 tokens、10 条事件、无 final |
| F05_EMBED/VECTOR | 两个真实 RAG Gateway 故障边界分别触发，LLM/handler=0，任务用量 0 UNKNOWN，4 条事件，RAG_RETRIEVAL_FAILED |
| F06_UNKNOWN/MALFORMED | 各 1 DECISION + 1 FINAL，30 EXACT tokens，9 条事件；非法引用不进入答案/引用/ANSWER_CHUNK |
| B01_PRE/OVER | 预检例 maxTokens=256、冻结 reserve=64，0 LLM、0 UNKNOWN；超额例完整保留 50000+20=50020 EXACT，超过预算50000，不进入final |
| B02/B03 | 各 D=1/F=1，独立 final 在途超时/取消；任务 1209 input + 2053 output = 3262 MIXED，顶层 errorCode为空，9 条事件，无答案 |
| C01 | 空 hit 后真实工具执行，D=2/T=H=1/F=1，COMPLETED/ANSWERED，45 tokens、15 条事件，citations=[] |
| C02/C03 | 分别 COMPLETED/MAX_DECISION_TURNS（D4/T2/F1，75 tokens、28 事件）、MAX_TOOL_CALLS（D1/T1/F1，30 tokens、13 事件） |
| R01 | 三个并发同 key 请求为一条201、两条200；同 key 不同输入409；FAILED后再提交200。只有原 task `2097583615968735233`、一次执行和15tokens |
| R02 | 首次201已提交后丢响应，刷新仍保留原 key/Agent/input；显式确认200，原 task `2097583620062375937`，无重复执行 |
| R03 | 原 task `2097583624529309697` 已处理游标5；独立SSE异常中断时后台落下序号6 TASK_FAILED，再用游标5真实重连并补到终态；无新task |
| R04_SUCCESS/FAILED | 浏览器offline时页面仍保持原RUNNING观察，后台分别已COMPLETED/FAILED；online后恢复，成功引用S1映射当前generation1，失败仍无答案 |
| R05 | RUNNING游标8刷新恢复；人为中断一次终态GET后保持“终态待同步”，手动恢复后与GET/Trace一致，未提前显示持久最终答案 |

B02 task `2097583553570074625` 与 B03 task `2097583601234145282` 的已知 decision 各15tokens，
在途 final 以输入估算1199 + 输出cap2048记3247 ESTIMATED。取消/超时后释放的受控响应虽然报告15tokens，
未被已经停止的执行器采纳，不能覆盖终态账目。test-only watcher 确认实际 Gateway worker 退出后才发出 exited，
浏览器再次读取 Task/Trace；PostgreSQL 的采证快照也晚于退出信号。两例此前终态与空答案保持不变。

已目视检查 F01 失败无答案、B02 超时无答案、R04_SUCCESS 恢复后答案/引用的截图布局，
其余逐例截图及 DOM/事件/GET/Trace 检查随证据保留。22 个任务只有5个正确成功对照，其余为预期失败/取消/超时；
“22/22通过”是验收案例通过，不是22个Agent成功，也不表示真实外部服务的故障可靠性。

首次完整入口的预检阻断历史单独保留：
`/private/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v48-browser.W1fKHe`，
`run-result.json` 为 BLOCKED/stage preflight，错误为 `Address already in use`；当时未见活动监听，
尚未启动数据库或创建任务。调整端口监听探测后换全新目录完成上述完整运行，没有重试不明结果任务。
这个预检阻断不算模型/任务失败；F01单例也没有被合并冒充全矩阵。所有执行自动重试均为0。

## 明确不做

新业务接口、migration、新页面、执行协议变更、provider token streaming、多轮对话、新工具种类、外部支付接入、
自动任务/模型/工具执行重试、自动参数调优、后台任务重启、崩溃恢复、陈旧 RUNNING 恢复、多实例调度或可靠性统计。
不扩大为任意 provider/网络/数据库故障矩阵，不以验收夹具绕过生产校验；仅修复当前矩阵暴露的必要问题。
若需要上述范围外能力才能继续，应单独冻结后续切片，不静默扩大 V48。
仓库生成物清理、V0.1 发布验收与 tag 继续留待后续；V48 完成也不等于完整 M4G 或 V0.1 Release Gate 通过。

## 面试问题与回答

**问题 1：为什么浏览器恢复不会重复执行任务？**

回答：已有恢复链路读取持久 Trace，重建已处理游标后订阅 SSE，并通过 GET 收敛结果；这些读操作不调度 Runner。
未知创建只能显式用原 key/Agent/原始输入确认，后端同指纹返回原 task 而不重新 dispatch。
V48 的 R01/R02 已通过并发 POST、已提交后丢响应和终态后重复提交交叉核对 task/event/实际调用次数，证明本次受控场景没有重复执行；
不扩展为崩溃恢复、多实例或外部副作用的唯一执行保证。

**问题 2：空检索、决策次数和工具次数耗尽为什么不一律 FAILED？**

回答：当前 Agent 空检索表示没有可用知识证据，仍能从业务工具获取事实；检索调用异常才是 `RAG_RETRIEVAL_FAILED`。
次数耗尽可进入一次受限最终生成，成功原因分别为 `MAX_DECISION_TURNS/MAX_TOOL_CALLS`，仍受 token、deadline 和引用校验限制。
C01–C03 已作为正确行为对照通过真实浏览器和持久执行链路验收，避免故障测试改变现有语义；不代表受限生成必然成功。

**问题 3：重复三次同一工具，为什么只应看到一次实际执行？**

回答：当前实现按 toolCode 和 canonical arguments 判定意图。首次实际执行，第二次重新检查授权/快照后复用成功结果，
仍有 reused 步骤和事件，第三次报 `AGENT_DUPLICATE_TOOL_LOOP`。因此 F02 预期三次决策、一条实际工具日志和一次 handler，
不能把工具意图、机会计数、Trace step 或缓存复用都统计成实际工具执行。

**问题 4：取消或超时后怎样证明不会发布晚到答案？**

回答：已有 Runner/执行器在安全边界观察信号，成功答案与分片、终态事件通过同一完成事务发布。
V48 的 B02/B03 已在 final 进入后制造取消/deadline，先核对终态，再释放晚到工作并确认线程退出后采证，未出现答案、引用、ANSWER_CHUNK 或 TASK_COMPLETED。
这证明受控场景下调用方停止推进，不承诺强杀外部请求；task 顶层取消/超时 errorCode 为空，内部诊断码另行核对。

**问题 5：失败请求为什么还需要计 token，超额为何不能截到预算？**

回答：非法决策、非法引用或中断前可能已经发生模型消费。已有执行器先累计观察到的 usage，再判断失败；响应不可用则保守估算并标明质量。
V20 允许失败/取消/超时保存超额事实，成功仍受预算约束。本次 B01 保留50020超额、B02/B03各保留3262 MIXED；
这些用量和日志已核对，不能把受控 EXACT 数值说成真实账单。

**问题 6：V48 的受控 E2E 与 V47 的真实成功基线分别能证明什么？**

回答：V48 将真实浏览器、JWT、数据库和执行链路与专用故障边界组合，本次22/22案例验证了确定性失败及观察恢复，详见验收记录。
V47 已记录一次固定模型和材料下真实 Chat/DashScope/Qdrant 成功主路径，两者证据分别保留。
生产执行路径修改后必须用新任务重跑 V47，旧成功或 V48 受控 PASS 均不能替代；本片不证明线上可靠性或 V0.1 发布就绪。
