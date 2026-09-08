# V47 真实 provider 与 Qdrant 主路径 E2E 接口包说明

> 路线位置：M4G-D1，接续 V43/M4G-A、V45/M4G-B2 与 V46/M4G-C。
> 范围：一条真实 Chat、DashScope embedding、Qdrant 与浏览器的支付诊断成功主路径。
> 验收状态：2026-09-07 已取得一次智谱 GLM-5.2 + DashScope + Qdrant 正常应用成功主路径，浏览器 PASSED、存储 23/23 PASS，包含两工具、独立 final、有效引用与刷新一致性。此前七次真实任务失败与第八次 PostgreSQL 启动失败原样保留；第九次入口才实际创建本阶段唯一新任务并通过。仅证明该固定模型/材料的一次主路径，不代表统计可靠性或 V0.1 发布验收。详情见“验收记录”。

## 目标与冻结范围

通过既有浏览器页面完成：创建知识库 → 上传 Markdown 演示材料 → 显式解析 → 显式向量化 →
服务端读回 `retrievalReadiness=READY` → 创建 Agent → 分别绑定该知识库及两个内置工具 →
提交 `order_1024` 支付诊断 → 检索、工具、独立最终生成 → 刷新后答案/引用与 GET/Trace 一致。
TXT/MD 上传能力沿用 V45；本片主路径固定上传仓库中的 MD，不为两种扩展名重复消耗真实 provider 配额。

应用使用 `com.agentflow.AgentFlowApplication` 正常启动，使用 main runtime classpath、既有 Flyway
迁移与生产组件，不加载 `V43BrowserFixture`、`V45BrowserFixture`、`V46BrowserFixture` 或其受控 Gateway。
Chat 使用既有 OpenAI-compatible `LlmGateway`；embedding 使用 DashScope remote adapter；
向量 upsert/search 使用实际 Qdrant。工具经过 `DefaultToolRuntime` 及两个已有 builtin handler，
订单与支付日志数据来自既有 V12 `mock_order` / `mock_payment_log` 演示表。
这证明真实工具执行链路，不能表述成接入真实外部订单或支付系统。

本片增加独立验收脚本、浏览器测试和演示/契约材料，并针对真实运行暴露的单次输出额度、provider 响应格式差异和执行历史理解问题，
按下述约定增加服务端配置与提示适配。仅修复真实运行暴露且闭环必需的适配问题；
不增加业务 API、迁移、页面或执行协议，不把本片作为完整 M4G 或 V0.1 发布验收。

## 既有接口与数据契约

所有接口沿用 `/api/v1`、JWT owner scope、统一响应和现有无损 ID 处理，不绕过服务端准入或直接写业务表。
临时用户通过既有 `POST /auth/register` 准备；随后浏览器在登录页取得 JWT 并完成全部业务写入。

| 流程 | 沿用契约 | 必须核对的结果 |
| --- | --- | --- |
| 创建知识库、上传 MD | V45 页面及 knowledge-bases/documents 既有 API | 捕获新建 KB/document ID；上传后为 PENDING |
| 显式解析、向量化 | V45 既有操作和 GET 文档状态 | 解析与向量化各由页面显式发起；当前 generation 的 READY 读模型成立 |
| 创建 Agent | V46 `POST /agents` | 模型配置与有限预算读回一致 |
| 绑定知识库 | V46 `PUT /agents/{id}/knowledge-bases` | 仅绑定本次创建的 KB，读回一致 |
| 绑定工具 | V46 `GET /tools`、`PUT /agents/{id}/tools` | 从实际目录选择 ACTIVE/BUILTIN 的 `order_query` 和 `payment_log_query` ID |
| 提交任务 | V43 `POST /agents/{id}/tasks` | 一次提交固定输入；保存 task ID 与既有 Idempotency-Key |
| 最终证据 | `GET /tasks/{id}`、`GET /tasks/{id}/trace`、既有 SSE 与刷新页面 | 终态、答案、引用、检索与执行记录一致 |

固定任务输入：

```text
帮我分析 order_1024 支付失败的原因，并给出处理建议。
```

演示材料为 `scripts/fixtures/v47-payment-diagnosis.md`，小于 2 KiB。
文档仅说明 `E_PAY_TIMEOUT` 的通用含义、两工具核实顺序和处理建议，不预埋 `order_1024` 的查询结果。
Agent prompt 要求实际查询两个工具、区分查询事实与通用处理规则并引用检索材料；不能用 prompt 中的答案
或直接调用工具 HTTP 接口代替 Agent 的模型决策与 ToolRuntime。

## 成功判定与证据门槛

以下条件必须同时成立；绑定成功、文档解析成功或 task `COMPLETED` 均不能单独判定通过：

1. **真实依赖与当前文档**：正常主应用加载真实 Chat/DashScope/Qdrant adapter。上传、解析和向量化
   是本次浏览器实际操作；GET 确认文档 `READY`。检索 Trace 至少一个有效 hit，其 KB/document ID、
   `vectorGeneration` 与本次上传文档及任务冻结的 generation 一致；Qdrant collection 中能核对相应向量证据。
2. **两个实际工具调用**：Trace 恰有两次成功工具调用，分别为 `order_query`、`payment_log_query`，
   参数均为同一 `order_1024`，有对应 TOOL 步骤和工具事件。结果包含订单支付失败/超时信息与支付日志，
   不接受仅有绑定数组、伪造 observation 或测试直接注入结果。
3. **决策与独立最终生成**：决策调用包括两次 `CALL_TOOL` 和模型 `FINISH`，终止原因为 `ANSWERED`；
   随后独立 `FINAL_GENERATION` LLM 调用和对应步骤成功。保留实际 provider、requested/resolved model、
   provider request ID（若 provider 返回）、usage/quality 与耗时；缺失值如实保留，不补造。
4. **有效答案与引用**：最终答案非空，准确反映查询到的支付超时事实，给出核实渠道状态、避免重复支付等处理建议。
   至少一个实际 `[S#]` 引用映射至有效 RAG hit；task citations 的 document/chunk ID 必须匹配该 hit。
   不接受未知引用、只返回固定套话或与工具结果矛盾的答案。
5. **持久结果与刷新**：页面最终答案和引用与 GET task/Trace task 一致；刷新后仍由持久 GET/Trace
   恢复相同 task、答案及引用，不能仅验证刷新前的 SSE 草稿。

自动断言验证结构、身份/事实标识、引用映射和持久结果。答案是否完整、建议是否有根据及是否与工具结果矛盾，
仍需在实际运行后对照最终答案、工具结果和文档进行人工核查并记录；不能把关键词命中表述为完整语义质量验证。

该单次主路径只证明本次模型、文档、配置和依赖下的闭环。不证明模型泛化、检索召回率、引用准确率统计、
任意问题可靠性、真实支付业务正确性或线上服务水平。

## 独立入口、预检与有限预算

仓库根目录运行：

```sh
bash scripts/v47-real-provider-acceptance.sh --preflight-only
bash scripts/v47-real-provider-acceptance.sh
```

依赖 Node 22.12+、前端已安装依赖、Playwright Chromium/Chrome、Java 21、Maven、PostgreSQL binaries、
实际 Qdrant 和已配置的 Chat/embedding 服务。`scripts/v47-preflight.py` 负责环境预检：
检查配置、工具链、无页面浏览器启动/关闭、端口、Chat `/models` 与 Qdrant 可达性和既有 embedding profile 的配置兼容性，不能以一次付费生成来冒充预检，
也不能将可达或 models 目录可读解释为实际生成已成功。缺少凭据、端点不可达、不兼容或运行失败应以非零退出
和具体失败阶段记录，禁止回退 local embedding、in-memory vector、受控 Gateway 或 mock 成功。

沿用实际配置的 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_CHAT_MODEL`、
`DASHSCOPE_API_KEY` 与 `QDRANT_BASE_URL`（需要鉴权时还有 `QDRANT_API_KEY`）。可用 `V47_ENV_FILE`
指定可信本地 shell 环境文件；启动器 source 该文件，因此它是可执行 shell 输入，不应使用不可信下载文件。
默认 Chat 地址为 `http://127.0.0.1:1234/v1`；模型优先使用环境变量 `OPENAI_CHAT_MODEL`，
未设置时读取 `application-dev.yml` 中该变量的默认值（当前 `google/gemma-4-e4b`），避免验收入口覆盖应用配置。
若配置格式无法识别，要求显式提供模型名，不悄悄选择其他模型。本地 loopback 可无 key，
远程 Chat 必须提供 `OPENAI_API_KEY`；只读 `/models` 必须列出请求模型。Qdrant 默认 `http://127.0.0.1:6333`。
embedding 固定满足既有 active profile：DashScope `text-embedding-v4`、1024 维，Qdrant 同为 1024 维。
`DASHSCOPE_API_KEY` 必需；预检只检查其存在和配置，凭据有效性与 embedding 服务可用性由实际向量化检验。
真实 Chat 的生成/结构化输出兼容性也以实际运行证据为准。
`OPENAI_TIMEOUT` 控制单次 Chat 连接/读取超时（应用默认 PT30S），与 Agent 的任务总时限独立。
低速本地模型可在显式验收时设置 `OPENAI_TIMEOUT=PT120S V47_TASK_TIMEOUT_SECONDS=300`，
保持已有 token/step/tool 上限；增大等待时间不表示允许自动重试。
第四次运行后，本轮已授权将单次决策输出上限配置化：服务端属性
`agentflow.task.execution.decision-max-output-tokens`，环境变量 `AGENTFLOW_TASK_DECISION_MAX_OUTPUT_TOKENS`。
应用默认 512，当前合法范围 1–16384；第五、六次 V47 适配运行显式使用 2048，并使用 Chat 120 秒/任务 300 秒。
第五次真实运行已核对实际请求 cap 为 2048、两个工具成功；该次 FINISH 格式不合规，历史失败记录保留。
第五次之后另行授权 DECISION 的 JSON Schema 约束：服务端
`agentflow.task.execution.decision-json-schema-enabled` 默认 false，V47 显式设置
`AGENTFLOW_TASK_DECISION_JSON_SCHEMA_ENABLED=true`。原生兼容性 probe 已通过；第六次应用 E2E 已验证 schema 实际传输，但因重复工具意图失败。
后续用户授权使用 IDEA 中已配置的 `ZHIPU_API_KEY`，仅在启动进程时映射至既有 `OPENAI_API_KEY`，
显式选择智谱 `glm-5.2` 与 `https://open.bigmodel.cn/api/paas/v4`。该 provider 本次采用 DECISION
`json_object`、关闭 thinking、decision/final 配置上限各 8192；具体开关及预算边界见下文。
原生兼容性 probe 已 PASS；第七次应用 E2E 的三次合法 JSON 均重复选择 order_query，触发循环保护，结果为 FAILED。
凭据从进程环境注入，不写进演示材料、截图或证据 JSON。

入口为单次运行创建 loopback 临时 PostgreSQL 和独立 Qdrant collection，沿用既有迁移创建演示订单数据；
不清理或改写开发库、既有 collection，不共用 V43/V45/V46 控制目录。前后端与 PostgreSQL 进程结束后停止，
保留打印路径中的证据与日志，供核对本次结果。
默认前端/后端/PostgreSQL 端口为 5177/18047/55447，可分别用 `V47_FRONTEND_PORT`、`V47_BACKEND_PORT`、
`V47_PG_PORT` 覆盖；`V47_CONTROL_DIR` 必须是未运行过的目录。collection 默认生成随机 `v47_` 名称，
也可用 `V47_QDRANT_COLLECTION` 指定符合 `v47_[a-zA-Z0-9_]{8,80}` 且不存在的新名称；预检拒绝复用已有 collection。
退出时保留本次 Qdrant collection 与临时 PostgreSQL 数据，未自动删除 collection；核对证据后可按报告中的精确名称手动删除本次 collection。

| 限制 | 本片固定值/规则 |
| --- | --- |
| 主路径次数 | 单浏览器用例、单任务，Playwright retries=0；失败不自动重新生成任务 |
| 演示文档 | 默认一份小于 2 KiB 的 MD；自定义 `V47_DEMO_FILE` 仅允许单份 TXT/MD、最多 2048 bytes，解析后最多 4 chunks，仅一次显式解析和向量化 |
| Agent 预算 | `maxSteps=5`、`maxToolCalls=3`、`maxTokens=24000` |
| 单次 decision 输出上限 | `AGENTFLOW_TASK_DECISION_MAX_OUTPUT_TOKENS`：应用默认 512、范围 1–16384；第五/六次显式 2048，智谱适配显式 8192，实际请求仍受剩余任务/context 预算约束 |
| 单次 final 输出上限 | `AGENTFLOW_TASK_FINAL_MAX_OUTPUT_TOKENS`：默认不配置，沿用既有 final reserve 上限；显式配置范围 1–16384，智谱适配为 8192；额外额度只能使用实际余额，reserve 仍为 2048 |
| DECISION JSON Schema | `AGENTFLOW_TASK_DECISION_JSON_SCHEMA_ENABLED`：默认 false；第六次显式 true，智谱适配为 false；与 JSON object 模式互斥 |
| DECISION JSON object | `AGENTFLOW_TASK_DECISION_JSON_OBJECT_ENABLED`：默认 false；智谱适配显式 true，只要求 JSON object，不声明 schema 约束；独立 final 仍生成正文 |
| Provider thinking | `AGENTFLOW_TASK_PROVIDER_THINKING_DISABLED`：默认 false，不追加关闭参数；智谱适配显式 true，发送 `thinking.type=disabled` |
| 任务时限 | `V47_TASK_TIMEOUT_SECONDS` 默认 180 秒，范围 60–300 秒 |
| 实际成功工具调用 | 恰好两次；第三个配额用于避免第二次调用后触发工具预算终止 |
| 模型调用 | 正常预期 3 次 decision（两个 CALL_TOOL + FINISH）及 1 次独立 final；既有预算最多 5 次 decision + 1 次 final |

`maxToolCalls=3` 不表示需要调用第三次工具：既有执行器在进入下一次决策前检查工具额度。
将额度设为 2 会在两次工具调用后以 `MAX_TOOL_CALLS` 转入最终生成，无法证明模型正常 FINISH；
成功断言仍严格要求仅两个工具调用和 `ANSWERED`。不为验收修改执行协议。
`maxTokens` 是既有任务 Chat usage 预算，不是金额上限，也不包含 DashScope ingestion/query embedding；
本片通过小文档、单任务与禁止自动重跑限制这些调用，不承诺 provider 账单或未知失败调用零费用。

### 第四次失败后的必要适配

新增 `com.agentflow.agent.engine.TaskExecutionProperties` 承载服务端 decision 输出配置，
`TaskSnapshotAgentExecutor` 用配置值替代原有固定的 512 上限。每次实际请求的 `maxOutputTokens` 仍为：
配置上限、扣除当前输入及 final 输入/保留额度后的任务剩余 token 预算、context 剩余容量三者的最小值。
因此显式配置 2048 不保证每轮都能请求 2048，也不扩大 `maxTokens=24000` 或侵占既有 final reserve。
单次读取 120 秒、任务 300 秒、steps/tools 和单 JSON/EOF 决策协议均保持原有约定。

实际 cap 继续通过已有 Trace `requestSnapshot.maxOutputTokens` 留证；不新增公开 DTO 字段、数据库迁移或执行协议。
适配代码已实现，后端 focused 测试 25/25 通过；第五次实际 cap 2048 已生效，具体见验收记录。
不能用配置值变化推断模型一定生成合法决策或完整闭环成功。该次配置范围为 1–4096（智谱适配扩至 1–16384），
在 `preflight.json` 的 `budget.decisionMaxOutputTokens` 记录本次配置值，实际请求 cap 仍以 Trace 为准。

### 第五次失败后的 JSON Schema 适配

启用后，`AgentDecisionResponseSchema` 从本任务冻结的工具定义生成 `agent_decision_v1`：根为 object/oneOf，
每个可用工具对应一个 CALL_TOOL 分支，严格要求原有 `type/toolCode/arguments/reason` 四个字段；
arguments 沿用该冻结工具的 inputSchema。FINISH 分支只允许 `type/answerPlan` 两字段。
各分支拒绝额外字段；这是原有两类决策对象的响应约束，不新增动作或绕过 ToolRuntime。

内部 `LlmChatRequest` 增加可选 `responseSchema={name,schema}`，Gateway 只为 DECISION 设置
`response_format.type=json_schema`、`json_schema.name=agent_decision_v1`、`strict=true`。
FINAL_GENERATION 不携带 schema，继续独立生成答案正文。`LlmResponseSchema` 对输入/读取做防御性复制，
schema 必须为 JSON object，UTF-8 序列化体积不超过 64 KiB。

schema 的序列化字节纳入输入 token 保守估算、context/任务预算及未知 usage 的失败记账；
cap 2048、total 24000、final reserve、Chat 120 秒/任务 300 秒继续生效。
现有 Trace 请求 allowlist 与公开投影同步保留可选 `responseSchema.name/schema`，与实际 maxOutputTokens 一起留证；
不增加业务接口或迁移。parser、prompt 和单 JSON/EOF 契约保持不变，不剥代码围栏、不降级无 schema 请求，
也不增加自动修复或重试；provider 不支持或仍返回不合法决策时如实失败。
当前单独 probe 不能证明应用 schema 传输、预算/Trace 集成或完整任务已验收，须另记后续测试与真实运行结果。
Schema 适配现已通过下文 71 项重点测试与 4 项离线采证检查；它们覆盖本地/HTTP stub 边界，
不代替第六次正常应用、真实 provider 的主流程证据。存储采证新增 `response_schema`，
用 `decision_schema_mode_matches_requests` 和 `final_generation_keeps_text_format` 核对配置模式与实际调用格式。

### 智谱 GLM-5.2 的 provider 适配边界

本次适配显式选择 `decision-json-object-enabled=true`、`decision-json-schema-enabled=false` 与
`provider-thinking-disabled=true`，属性均位于 `agentflow.task.execution` 下，默认均为 false。
DECISION 发送 `response_format.type=json_object`；这与 `json_schema/strict=true` 的模式不同，
不携带冻结工具 schema，也不保证动作字段、参数或下一步选择正确。两种 JSON 模式互斥，冲突配置应拒绝启动。
独立 FINAL_GENERATION 不携带两种 JSON 格式约束，仍生成答案正文；显式关闭 thinking 的 provider 参数用于本次模型调用。
现有严格 parser、工具参数校验、ToolRuntime、重复意图保护、单 JSON/EOF 与独立 final 协议继续生效。
此处是预先显式选择兼容模式，不是请求失败后的自动降级，不剥围栏、不自动修复或重试。

用户允许按模型调高输出上限，本次 decision 与 final 分别显式配置 8192，上下界统一为 1–16384。
`final-max-output-tokens` 默认不配置，保留旧的 final reserve 上限行为；显式 8192 不表示每次可用 8192。
DECISION 仍为最终阶段保护既有 final 输入与 2048 输出 reserve；final 的额外输出额度仅可使用扣除已记账 usage
及本次输入的保守估算后尚余的任务/context 容量，不将请求前估算当作 provider 实际输入 usage。
总 `maxTokens=24000`、steps 5、tools 3、Chat 120 秒/任务 300 秒保持不变。
Trace 留存实际 cap 与显式 provider 模式，预检和存储证据必须区分配置值与实际发出的选项。

本次存储采证将新增请求选项与公开 Trace 逐字段核对；`final_generation_keeps_text_format`
在缺少 final 调用时明确为 false，不再以空集检查成立表示格式通过。第六次旧证据原样保留。
原生 probe、下列新增适配测试和默认受控回归各自只证明相应边界；第七次完整 E2E 的结果独立记录，
不借用此前 71/71、19/19 或第五次两工具成功作为本次完成证据。

### 执行历史提示与独立决策回放

第七次失败后，以单步真实 provider 回放检验“observations 的已完成动作语义需要更明确”这一假设。
`scripts/v47-decision-replay.py` 读取公开 `browser-evidence.json`，默认选第 2 次 DECISION，
要求来源模型为 `glm-5.2`、JSON object、thinking disabled、实际 cap 8192，并核对冻结工具 schema 与消息内工具定义。
A 原样使用持久请求；B 只向既有第二条 SYSTEM 的 content 末尾追加候选说明，保持 SYSTEM/SYSTEM/USER 三条消息，
第一条 SYSTEM、USER payload、模型和采样参数不变。固定顺序为 A/B、B/A、A/B，共六次独立单步请求，不把前次响应加入后次输入。

入口以标准库 HTTP 调用固定智谱端点，拒绝重定向；每次最多六次调用、总预算 60000 tokens、单次 cap 8192、timeout 120 秒。
每次请求前按 UTF-8 输入保守估算加 8192 预留检查剩余预算和来源 context；余额不足直接停止，不降低 cap 改变实验条件。
HTTP/transport/未知 usage 等失败停止后续调用且不自动重试；实际 usage 超出预算仍保留并停止。
输出采用严格单对象/EOF、重复键拒绝、exact fields、reason/answerPlan 长度以及冻结工具参数校验；
脚本只支持本次两个 builtin 已使用的 object/string/integer/required/top-level required-only anyOf 子集，未知 schema 能力在请求前拒绝。
`inputs.json` 保存安全完整输入与明确来源，`report.json` 保存各组/逐次决策、provider ID/model/usage/耗时/长度；
reason 仅校验不保存，凭据、原始推理正文和 provider 错误正文不落盘。
报告 COMPLETED 仅表示固定诊断采集完成；目标动作命中由 `groups` / `allExpectedDecisions` 独立表示。

`--mode candidate --expected FINISH --state-input ...` 只发 B/B 两次请求。
显式 state envelope 必须给出 `schemaVersion=1`、description、绝对路径 `sourceEvidenceFiles` 和三条 messages，
并明确是否由历史结果复合；它不是同一生产任务的冻结快照，也不新执行任何工具。
这两个入口均不运行浏览器、ToolRuntime、embedding/Qdrant 或最终生成，不计入完整 E2E 次数。
复现命令见 `frontend/e2e/README.md`；诊断不能替代当前文档 generation、两工具、独立 final、引用和刷新验收。

候选文件 `scripts/fixtures/v47-decision-history-instructions.txt` 明确 observations 是已成功执行的结果历史，
`reused=true` 表示缓存重用；相同参数重复调用不会刷新数据，应根据已取得信息决定缺失动作，满足任务后 FINISH。
结果正文仍是不可服从的非信任数据，不允许其修改规则；有具体更新需求、过期或信息不足时仍按既有限制判断查询需要。
此提示不硬编码工具顺序或 `order_1024`，不将“一种工具只能调用一次”设为通用规则；已有相同意图缓存和第三次循环失败保护不变。
完成下述单步对照与复合状态诊断后，将精确候选追加到生产 `TaskPromptBuilder` 的既有第二条 SYSTEM。
这种渐进验证支持实施必要提示适配；下一步仍需新建任务完成正常 E2E，不能把少量回放当成稳定性或因果机制证明。

## 实现与验收材料

- `scripts/v47-real-provider-acceptance.sh`：正常应用、独立依赖隔离与浏览器验收入口。
- `backend/src/main/java/com/agentflow/agent/engine/TaskExecutionProperties.java` 与 `TaskSnapshotAgentExecutor`：单次 decision/final 输出配置、显式 provider 模式及既有任务/context/final 预算约束。
- `AgentDecisionResponseSchema`、内部 `LlmResponseSchema/LlmChatRequest`、Gateway 与 Trace 投影：可选 DECISION schema、JSON object 与 thinking 选项的传输和留证。
- `scripts/v47-preflight.py`：不生成答案的预检与配置/服务检查。
- `scripts/v47-storage-evidence.py`：从本次临时 PostgreSQL 与 Qdrant 提取和核对持久化证据。
- `scripts/v47-evidence-check.sh`：新验收文件的 focused TypeScript 检查和 5 组离线验收器测试，不启动应用或 provider。
- `scripts/v47-decision-replay.py` 与 `scripts/fixtures/v47-decision-history-instructions.txt`：有限单步 A/B 或候选 FINISH 诊断；证据与完整 E2E 分开。
- `frontend/e2e/real-provider.spec.ts`、`real-provider-evidence.ts` 与 `real-provider.config.ts`：主路径、Trace/GET/generation/工具/引用/刷新断言。
- `scripts/fixtures/v47-payment-diagnosis.md`：固定可复现的通用诊断材料。
- `frontend/e2e/README.md`：运行步骤、受控回归与真实主路径的证据边界。

运行材料应保留预检结果、失败阶段、实际配置中的非秘密模型/端点信息、KB/document/generation/Agent/task ID、
公开 GET/Trace、Qdrant 证据、usage 与截图。成功和失败都保存当时已取得的证据，不能用最终报告覆盖失败事实；
未发起任务时 task ID 保持缺失，不捏造检索、工具或 provider 结果。
具体产物为 `preflight.json`、`run-result.json`、`browser-evidence.json`、`storage-evidence.json` 和
`browser-artifacts/`，只会生成运行阶段已取得的材料；构建/后端/前端/PostgreSQL 日志位于同一临时目录。
`run-result.json` 区分 `PREFLIGHT_READY`、`BLOCKED`、`FAILED` 与 `PASSED`；`PASSED` 表示脚本的浏览器与
存储自动断言全部通过，最终切片验收还须在运行记录中说明实际答案的人工核对结果。

### 验收记录

2026-09-07 当前结论为 **一次真实成功主路径已通过**。下列序号按独立入口启动记录：第 1–7 次创建真实任务后 FAILED，
第 8 次在 PostgreSQL 启动阶段失败且未创建任务，第 9 次创建任务后 PASSED；共八个实际任务，七次失败、一次成功。
独立原生 probe、六次 A/B 与两次历史复合 FINISH 回放不计入这些应用任务，也不替代成功 E2E。

- `npm test`：前端原有 8 个测试文件 **76/76** 通过。
- `npm run build`：TypeScript 检查及生产构建通过。
- decision cap 适配后的后端 focused 测试 **25/25** 通过：`TaskSnapshotAgentExecutorTest` 19/19，
  `TaskExecutionPropertiesTest` 6/6。验证默认 512、显式配置/合法上下界、越界启动拒绝，以及执行器的剩余预算约束。
  预检 Python 语法检查及 `git diff --check` 通过；本适配没有前端代码变更，未重复上述 76/76 和 build 计数。
  这些是配置/执行器测试，不是第五次真实主路径验收结果。
- JSON Schema 适配重点测试 **71/71** 通过：Gateway 6、Gateway HTTP 10、Executor 23、Properties 7、
  Schema 3、Parser 3、TraceSanitizer 14、PublicTrace 5；日志 `/private/tmp/v47-json-schema-focused-tests.log`。
  Python 语法检查及 **4 项离线采证格式检查**通过：默认/禁用模式、启用但缺 schema 时拒绝、记录 schema、final 不携带 schema。
  这些结果验证代码和采证器的受控边界，既不属于原生兼容性 probe，也不是第六次真实 E2E 结果。
- 智谱适配的请求/执行器/Trace 等 9 类 Java 重点测试最终 **98/98** 通过：首轮非 HTTP 86 项通过，
  HTTP 测试因 sandbox 禁止 loopback 监听未能执行，允许本地监听后只重跑该类 **12/12** 通过。
  日志：`/private/tmp/v47-zhipu-core-focused-tests.log` 与 `/private/tmp/v47-zhipu-http-focused-tests.log`。
  Python 语法、**10 项请求选项边界检查**及 **3 项离线集成断言**通过；离线断言使用第六次证据副本，
  验证 PostgreSQL/公开 Trace 请求选项逐字段对比、缺 final 时失败、篡改 cap 时失败，原始证据未修改。
  这些受控结果不计为新增真实 provider 请求或完整 E2E。
- 智谱适配后以默认 provider 开关/输出配置运行 V46/V45/V43 受控浏览器回归，**19/19** 通过、**45.2 秒**；
  日志 `/private/tmp/v47-zhipu-controlled-browser.log`。与早前三次 19/19 分开记录，不代替智谱真实运行。
- 执行历史提示适配的 Java 重点测试 **46/46** 通过，日志 `/private/tmp/v47-history-focused-tests.log`。
  单步诊断入口的无网络自检覆盖固定 A/B 顺序、唯一提示变量、严格 JSON/参数边界、预算/未知 usage 与失败停止，
  日志 `/private/tmp/v47-decision-replay-offline-check.log`；这些验证不调用真实 provider。
- 执行历史提示适配后的默认 V43/V45/V46 受控浏览器回归 **19/19** 通过、**44.6 秒**，
  日志 `/private/tmp/v47-history-controlled-browser.log`；与下述正常 provider 新任务的成功证据分开记录。
- `bash scripts/v47-evidence-check.sh`：focused TypeScript 检查及验收器离线测试 **5/5** 通过，验证不接受缺失工具、错误 generation、缺失独立 final 等反例；
  这是验收断言自身的受控测试，不是 provider 或浏览器主流程运行证据。
- 2026-09-08 提交前复核：上述 9 类 Java 重点测试一次运行 **98/98** 通过，验收器 TypeScript 检查及离线测试 **5/5** 通过，Python 与 Shell 语法检查通过。
  日志为 `/private/tmp/v47-precommit-focused-tests.log`、`/private/tmp/v47-precommit-evidence-tests.log`；本次未重复调用付费 provider，真实主路径与受控浏览器证据沿用 2026-09-07 的已核验记录。
- 正常 main 启动 smoke：runtime classpath 无 test classes、remote/engine 模式、临时 PostgreSQL 既有 Flyway
  **20/20** 成功、health `UP`。证据：`/private/tmp/v47-normal-main-9vxdh19g/startup-smoke.json`。
  该检查没有创建任务或调用 Chat、embedding、Qdrant，只证明正常启动和迁移。
- 存储证据 SQL 在临时 PostgreSQL 上执行成功；空任务数据正确保留 `FAIL`，未提供独立 collection 时也触发 guard，
  没有把空证据判为成功。证据：`/private/tmp/v47-storage-sql-qg8_l685/storage-evidence.json`。
- `bash scripts/v46-browser-acceptance.sh`：本轮 V46/V45/V43 受控浏览器回归 **19/19** 通过，测试阶段 **46.5 秒**。
  证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v46-browser.5PdnaO`。
- decision cap 适配后，以应用默认 **512** 再次运行同一 V46/V45/V43 受控回归，**19/19** 通过、**48.7 秒**、exit 0。
  本次复跑证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v46-browser.X5X9dv`。
  该结果验证默认配置的受控回归，与前述 46.5 秒记录是两次独立运行，不能替代显式 2048 的真实主路径验收。
- JSON Schema 适配后，保持默认 schema 关闭/cap 512 的 V46/V45/V43 受控回归再次 **19/19** 通过，
  **47.3 秒**、exit 0；证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v46-browser.u5OIAK`。
  这是关闭 schema 的默认行为回归，不替代开启 schema 的第六次真实主流程。
- 较早的 V47 独立入口预检：`run-result.json` 为 **BLOCKED**，`exitCode=2`、`stage=preflight`、`automaticRetries=0`。
  历史预检证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real.Vmxspu`。
  当时启动环境缺少 `DASHSCOPE_API_KEY`；默认 Chat 的 `/models` 未取得 HTTP 响应，不能证明请求模型可用。
  实际 Qdrant 返回 HTTP 200、版本 1.19.0；本次随机 collection 查询返回 404，证明该名称尚未占用。

历史预检在应用启动前停止，没有发起任务。随后配置好实际依赖，以同一入口显式进行一次真实运行，
该运行预检为 `PREFLIGHT_READY`，结果取代“尚未执行”的当前状态，但不覆盖此前 BLOCKED 记录。

#### 第一次真实运行：1.7B 首个模型决策格式不符合契约

证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-hqs0ksyt`。
独立入口为 `bash scripts/v47-real-provider-acceptance.sh`。正常 main 启动、既有 Flyway **20/20** 成功，
浏览器单用例 **16.4 秒失败**；`storage-evidence.json` 为 `FAIL`，`run-result.json` 为 `FAILED`、
`exitCode=1`、`automaticRetries=0`。报告的 `stage=storage-evidence` 是退出前最后采证阶段；实际任务失败点是首个 `LLM_DECISION`。

| 本次证据 | 实际结果 |
| --- | --- |
| 上传/解析/向量化 | 浏览器实际上传 1293-byte MD，产生 1 chunk；显式解析、真实 DashScope 向量化后 GET 为 READY |
| 浏览器创建资源 | `knowledgeBaseId=2096862301272383489`、`agentId=2096862308037795841` |
| 文档 | `documentId=2096862301670842370`，当前 `vectorGeneration=0` |
| Qdrant | 独立 collection `v47_294c5f0b7d5e4d51908314c8b41f105d`，1024 维 Cosine；point 与 PostgreSQL/current generation/RAG hit 一致 |
| 实际检索 | `candidateCount=1`、`validHitCount=1`、`staleHitCount=0`、score `0.66849273`，命中本次文档 generation 0 |
| 任务 | `taskId=2096862310168502274`，`FAILED` / `SYSTEM_ERROR` / `AGENT_INVALID_DECISION` |
| Chat | requested/resolved model 均为 `qwen/qwen3-1.7b`；实际发起 1 次 DECISION，耗时 11496 ms |
| Provider 请求 | `chatcmpl-1u5b2pon4vd0hciuibqee87`，`finishReason=stop` |
| Chat usage | `EXACT`，input 970 + output 372 = total 1342 tokens；provider 报告 output 中 reasoning tokens 292，仅保留统计，不保留推理正文 |
| 未完成门槛 | ToolRuntime 调用 0、独立 FINAL_GENERATION 0，无最终答案/引用；未完成成功答案的刷新恢复验证 |

按 provider request ID 匹配本机 LM Studio 服务响应后，确认 `content` 是两个相邻的顶层 `CALL_TOOL` JSON：
一个请求 `order_query`，另一个请求 `payment_log_query`，参数都为 `order_1024`。没有 think 标签或 Markdown fence，
也不是输出 token 截断。脱敏摘要保存在 `provider-response-summary.json`，只含两个决策对象及形状/usage 统计。

`AgentDecisionParser.parse` 的现有契约要求一个 JSON object，且 `parser.nextToken()` 必须为 null；
本次响应存在第二个顶层对象，触发 `AGENT_INVALID_DECISION`。这次失败原因已定位到模型输出违反单决策契约，
没有证据要求修改 adapter，也不应通过取第一个对象、拼装多个动作或放宽 parser 来改变执行协议。
凭据与 transport 已完成本次真实调用，不能继续将当前失败归因为此前的环境缺失。

本次 GET task 与 Trace 中的失败状态一致，只能证明失败持久记录收敛，不能算成功答案/引用刷新主路径通过。
已核对截图：知识库 READY/generation 0/1 completed，任务 FAILED/6 of 6 events/无答案；
退出后本次 5177/18047/55447 端口均无监听，证据与本次 collection 按约定保留。
已证明本次浏览器知识库入库、真实 embedding/Qdrant/current generation 检索及真实 Chat 请求；
未证明两个工具执行、模型 FINISH、独立最终生成、有效答案和成功刷新闭环，因此 V47 成功验收仍未完成。
没有回退 mock、自动重跑或调整执行协议；前述 76/76、5/5 和 19/19 保持原本轮记录，未在这次失败后重复计数。

#### 第二次真实运行：9B 首轮成功，第二轮达到读取超时

用户将应用默认模型改为 `qwen/qwen3.5-9b` 后，显式进行一次新验收，保持同一 prompt、
`maxTokens=24000`、任务 180 秒及 Chat 默认 30 秒读取超时。证据目录：
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-9b-bk211vvr`。

- task `2096886820204658690`；KB `2096886811614724097`、document `2096886812046737410`、
  Agent `2096886818136866818`。真实 embedding/Qdrant 命中当前 generation 0，1 candidate/1 valid/0 stale，score 0.66849273。
- 首轮 requested/resolved model 均为 `qwen/qwen3.5-9b`，返回有效的单个 `order_query` 决策，
  耗时 24981 ms，EXACT 948 input + 255 output = 1203 tokens。ToolRuntime 的 `order_query` 成功，
  读到 `CREATED`、`PAY_FAILED`、`E_PAY_TIMEOUT`。
- 第二轮在 30019 ms 后记录 `AGENT_LLM_FAILED`，LM Studio 同时记录客户端断开并取消生成，
  与 `OPENAI_TIMEOUT=PT30S` 的读取上限吻合。应用没有取得第二轮响应，不将服务端取消后的不完整文本当作已收到决策。
- task 为 FAILED/SYSTEM_ERROR，已用 2 轮决策、1 次工具；`payment_log_query`、FINISH、final 和成功答案恢复未完成。
  任务记账 5960 tokens、quality=MIXED：1203 为实际 usage，第二次失败的 4757 为既有保守估算，不能当作 provider 实际账单。
- 浏览器用例 58.8 秒失败，GET 与 Trace 失败状态一致。根助手在进程运行时修改启动脚本，导致后续采证命令读取位置受干扰；
  原始 `run-result.json` 保留 FAILED/exit 127/stage browser。该脚本问题发生在任务失败之后，不能解释模型调用失败。
  随后仅重启保留的临时 PostgreSQL，补采只读 PostgreSQL/Qdrant 证据并关闭数据库；
  `storage-evidence.json` 为 FAIL、collector errors 为空，`storage-recovery.json` 记录恢复过程，未重跑模型。

本次运行显式指定应用中的 9B 模型。启动器随后修正为在环境变量缺省时读取应用配置，避免旧 1.7B 默认值覆盖新配置。
9B 在首轮比本次 1.7B 结果更符合协议，但同时变化了模型系列和规模；单次比较不能把差异完全归因于参数量。
约 255 output tokens / 24.981 秒与用户描述的约 10 tokens/s 一致，支持增加有上限的调用等待时间，不能据此保证完整链路通过。

#### 第三次真实运行：9B 延长超时后触发重复工具意图保护

用户指出本地约 10 tokens/s 的速度后，以 `OPENAI_TIMEOUT=PT120S V47_TASK_TIMEOUT_SECONDS=300`
显式运行一次；模型、prompt、5 steps/3 tools/24000 tokens 上限不变，自动重试 0。
启动器从内存中的固定脚本副本执行，避免运行中磁盘文件变化干扰读取。证据目录：
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-9b-timeout-a8uub6n_`。

- 预检 PREFLIGHT_READY，实际模型为 `qwen/qwen3.5-9b`，验证了启动器从应用配置读取新默认模型的分支。
- task `2096888399116828674`；KB `2096888390048743426`、document `2096888390489145346`、
  Agent `2096888396986122242`。上传、解析、真实 embedding 与 Qdrant 检索成功，仍命中本次 generation 0 的 1 个有效 chunk。
- 三轮 Chat 均返回单个合法 JSON，耗时分别 16123 / 20358 / 24111 ms，finishReason 均为 stop，
  每轮分别为 1133 / 1287 / 1401 tokens；合计 3821、quality=EXACT。本次没有观察到读取或任务总时限超时。
- 三轮均请求 `order_query({"orderNo":"order_1024"})`。第一次实际经过 ToolRuntime 成功；
  第二次由既有去重规则复用 observation（事件 reused=true，不新增工具调用日志）；
  第三次相同意图触发 `AGENT_DUPLICATE_TOOL_LOOP`，因此只有 1 次实际工具调用。
- 第二/三轮持久化的 application requestSnapshot 分别包含 1/2 个已成功的订单 observation，
  每轮 availableTools 都包含 `payment_log_query`。不存在持久请求漏传订单结果或遗漏支付日志工具的证据。
  LM Studio 请求日志正文被截断，未据此声称全文核对了服务端收到的 payload；响应按每轮 provider request ID 匹配，
  完整三次决策和应用 observation 摘要保存在 `provider-response-summary.json`，不保存 reasoning 正文。
- task FAILED/SYSTEM_ERROR，3 轮决策、1 次实际工具、无 payment_log_query/FINISH/final/最终答案/引用。
  GET 与 Trace 的失败状态一致；浏览器约 65 秒失败，`run-result.json` 为 FAILED/exit 1/stage storage-evidence，
  storage 采证正常完成，errors 为空、状态 FAIL。本次专用端口清理，数据库与 collection 按约定保留。

等待时间问题与模型决策问题是两个已观察到的边界：30 秒读取限制曾中断第二次运行，延长后第三次运行仍因重复意图失败。
不能把 9B 首轮格式正确或其中一个工具成功写成完整成功，也不能据单次模型比较推断普遍能力。
后续应针对已有 observation 到下一动作的 prompt/模型行为做有边界的修正与独立验收；本轮未放宽 parser、循环保护或改为强制执行指定工具。

#### 第四次真实运行：4B 第二轮输出额度耗尽，未产出决策

用户将应用默认模型改为 `google/gemma-4-e4b` 后，启动器跟随应用配置再次显式运行一次，
保持原 prompt、5 steps/3 tools/24000 tokens，`OPENAI_TIMEOUT=PT120S`、`V47_TASK_TIMEOUT_SECONDS=300`，
单任务、自动重试 0。证据目录：
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-4b-e0rjnjjt`。

- 预检 PREFLIGHT_READY；MD 1293 bytes、1 chunk，显式解析及真实 DashScope 向量化至 READY。
  PostgreSQL/Qdrant/RAG 命中本次当前 generation 0，score 0.66849273，未更改演示输入。
- task `2096891315613560833`。首轮 requested/resolved model 均为 `google/gemma-4-e4b`，
  18733 ms 返回合法 `order_query` 决策；request ID `chatcmpl-kq7vee8pxyyreypqmk1ac`，
  EXACT 982 input + 258 output = 1240 tokens。ToolRuntime 9 ms 成功，订单结果含 PAY_FAILED/E_PAY_TIMEOUT。
- 第二轮在 29335 ms 记录 `AGENT_LLM_FAILED`。匹配 LM Studio 响应
  `chatcmpl-oza4o7a8autmy89i3e3fvi` 后，确认请求 `max_tokens=512`，响应 `finish_reason=length`、
  `content` 为空；provider usage 为 1084 input + 511 output = 1595 tokens，其中 reasoning tokens 为 509。
  这次输出额度耗尽后没有可用决策，gateway 因空 content 拒绝返回结果；不是 120 秒读取超时。
  `provider-response-summary.json` 仅记录 usage、响应形状及请求标识，不保存推理正文。
  服务端关联依据是首轮 request ID 及连续第二次请求/响应的模型与时间；请求消息日志被截断，未据此宣称完整 wire prompt 已核验。
- Gateway 未返回 `LlmChatResult`，Trace 第二轮因此仍按既有失败规则保守记账为
  ESTIMATED 4245 input + 512 output cap = 4757 tokens，任务总计 **5997 / MIXED**。
  服务端两次报告合计 **2835 tokens** 是另一项观测，不改写任务 Trace，也不把 5997 或 2835 当作已核定账单。
- task FAILED/SYSTEM_ERROR/AGENT_LLM_FAILED，实际工具 1 次；没有 payment_log_query、FINISH、
  独立 final、答案/引用或成功刷新证据。浏览器单用例 **53.1 秒失败**；storage 为 FAIL、errors=[]，
  run-result 为 FAILED/exit 1/automaticRetries 0。采证成功不代表业务验收成功。
  截图已核对任务 FAILED/11 of 11 events/无答案；退出后 5177/18047/55447 均已停止监听。

这次证明首轮合法决策和实际 order_query 成功，未证明完整双工具闭环；单次耗时和失败形态不能用于推断
模型普遍速度或能力。保留前三次历史，不自动重跑、不调整原 prompt/步骤/工具/token 预算或放宽执行协议。

#### 第五次真实运行：2048 已生效，两个工具成功，FINISH 围栏格式失败

保持 `google/gemma-4-e4b`、原 prompt、5 steps/3 tools/24000 tokens、Chat 120 秒/任务 300 秒，
显式设置 `AGENTFLOW_TASK_DECISION_MAX_OUTPUT_TOKENS=2048` 后进行一次独立验收，自动重试 0。
证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-4b-2048-jjvz83tf`。

- task `2096902272884645889`；KB `2096902264152104961`、document `2096902264617672706`、
  Agent `2096902270825242625`；独立 collection `v47_018716923bcf47938341e1cc72829978`。
  实际 DashScope/Qdrant/RAG 命中本次 generation 0，score 0.66849273，当前文档和持久 point/hit 一致。
- `order_query` 与 `payment_log_query` 各实际经过 ToolRuntime 一次、均为 SUCCESS，关联本 task/step、retryCount=0。
  order_query 耗时 11 ms，返回 PAY_FAILED/E_PAY_TIMEOUT；payment_log_query 耗时 24 ms，
  返回既有演示日志的 pay-trace-1024、3000ms 与 E_PAY_TIMEOUT。两个工具执行的成功门槛在本次已有实际证据。
- 三次 DECISION requested/resolved model 均为 `google/gemma-4-e4b`，Trace 与服务端请求选项的实际 cap 均为 **2048**；
  耗时分别为 15589 / 26078 / 26567 ms，finishReason 均为 stop。每轮 EXACT usage 分别为
  1240 / 1559 / 1667 tokens，合计 **4466 = 3273 input + 1193 output**，并非费用核验。
- 第三轮 request ID `chatcmpl-qulcp6jxt114qqxatv7nq` 对应响应的内部对象为 `FINISH` + `answerPlan`，
  但完整 `content` 被 Markdown 的 json 代码围栏包裹，不符合整个响应必须为单个 JSON 的现有契约，
  因此触发 `AGENT_INVALID_DECISION`。没有被接受的 FINISH 事件，没有独立 FINAL_GENERATION、最终答案或引用。
  `TaskPromptBuilder` 已明确要求单 JSON 且禁止前后缀/Markdown；本次模型违反已有格式约束，不是 prompt 未约束格式。
- `provider-response-summary.json` 按每轮已持久化 request ID 匹配响应，并核对相邻请求的 max_tokens=2048；
  只保留 usage、形状和安全决策摘要，不保存推理正文。服务端消息正文日志被截断，未宣称完整 wire prompt 已核验。
- PostgreSQL、GET 与 Trace 的 FAILED 状态一致，16 个持久事件连续、最后为 TASK_FAILED；
  未完成成功答案刷新闭环。浏览器 CLI 报告约 1.2 分钟失败，证据起止约 74.2 秒；
  storage 为 FAIL/errors=[]，run-result 为 FAILED/exit 1/automaticRetries 0。
  本次 5177/18047/55447 及对应受控验收端口均已无监听，数据库与 collection 按约定保留。
  已检查失败截图：FAILED/16 of 16 events/两个工具 SUCCESS/无答案，与 JSON 证据一致。

本次证明确实使用了 2048 cap，未再观察到 length/空正文，且完成两个工具执行；仍因 FINISH 格式失败，
不能将其写成完整成功。第二轮实际 completion 为 475，低于旧 cap 512，且单次生成存在波动，
因此不能由这一次对比证明提高 cap 已普遍或完全解决截断。parser 与 prompt 保持不变；第五次退出时未自动重试，第六次由用户后续明确发起。

#### 独立 JSON Schema 兼容性 probe：不计入应用 E2E 次数

第五次失败后，单独对实际 `google/gemma-4-e4b` 发起 **1 个请求、0 重试** 的原生 response_format 兼容性 probe，
上限 2048 tokens/120 秒。证据目录：
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/v47-json-schema-compat-_gomzhte`。
HTTP 200、约 **4.7 秒**、request ID `chatcmpl-hrjuluzdcs59l3fvijsp06`、finishReason=stop，返回裸 FINISH 对象；
provider usage 为 77 input + 26 output = **103 tokens**，reasoning tokens 统计为 0，不保留推理正文，结果 PASS。

该请求实际生成了响应，独立于“不生成答案”的预检；未调用 embedding/Qdrant、未创建业务任务或执行工具。
它只证明本次服务/模型接受此原生 schema 请求并返回匹配对象，不证明应用适配、两个工具决策、独立最终生成或浏览器闭环。
该 probe 独立计为一次模型请求，不计入下面真实应用 E2E 的运行次数。

#### 第六次真实运行：Schema 已传输，合法决策重复同一工具

证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-4b-schema-6jee66ry`。
预检 READY，`google/gemma-4-e4b`、schema 开启、cap 2048、total 24000、Chat 120 秒/任务 300 秒，原 prompt 不变。

- task `2096937495382958081`；KB `2096937486600085505`、document `2096937487048876034`、
  Agent `2096937493243863041`；collection `v47_ecf73ab7535245be83c6bbe16b85f76a`。
  独立核对确认 generation 0、文档/chunk/point 身份与 PostgreSQL/Qdrant/RAG 一致。
- 三次 DECISION 均为 SUCCESS 的合法裸 JSON、finishReason=stop、实际 cap 2048；按三个持久 provider request ID
  核对实际请求均为 json_schema/strict=true，完整 schema 与 PostgreSQL 的 responseSchema 逐项一致。
  `provider-response-summary.json` 保留该核对和安全摘要，不保留推理正文；消息日志截断，未声称完整 wire prompt 已核验。
  冻结工具分支与 schema、PostgreSQL 与公开 Trace 的 schema 均核对一致；既有安全投影省略 reason，不表示 provider 少返回该字段。
- 三次均请求 `order_query(order_1024)`：首次实际 ToolRuntime 成功 6 ms，第二次复用缓存，
  第三次触发 `AGENT_DUPLICATE_TOOL_LOOP`。只有 1 条实际工具日志，payment_log_query 未执行。
  应用 requestSnapshot 每轮均含两个 availableTools，第二轮已有订单结果，第三轮另含 reused 结果。
- 三次耗时 4517 / 3401 / 4978 ms，EXACT usage 为 1048 / 1146 / 1250，合计 **3444 = 3254 input + 190 output**。
  三次 reasoning tokens 统计均为 0，无围栏、length 或超时；这些观测不能证明 schema 导致循环或保证语义正确性。
- 浏览器 **19.4 秒失败**；task FAILED/AGENT_DUPLICATE_TOOL_LOOP，无 FINISH、final、答案/引用或成功刷新。
  storage 为 FAIL/errors=[]，run-result 为 FAILED/exit 1/automaticRetries 0；本次及受控验收端口均已停止。
  `final_generation_keeps_text_format=true` 在本次只是无 final 调用时的空集检查成立，不能当作真实 final 已执行。
  GET task 与 Trace.task 一致，事件 1–17 连续；已检查 FAILED/17 of 17 events/实际工具一次与复用一次/无答案截图，未到达刷新 checkpoint。

本次验证 schema 的真实请求与合法 JSON 形式，未验证模型会按 observation 选择下一动作。
第五次两个工具各成功的历史证据保留，但不能挪用成第六次两工具成功。本轮只有一个独立单请求 probe 和一次业务 E2E，
没有自动发起第七次；默认文本/final 格式仍仅有 71 项重点测试与 19/19 受控回归的相应证据。

#### 智谱 GLM-5.2 独立原生 probe：不计入应用 E2E 次数

用户后续授权读取 IDEA 中的 `ZHIPU_API_KEY` 并允许按模型调高输出上限。本次显式使用
`https://open.bigmodel.cn/api/paas/v4` 与 `glm-5.2`；`GET /models` 为 HTTP 200，目录列出该模型。
证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/v47-zhipu-glm52-probe-6jhr1dnp`。

独立生成请求使用 `response_format.type=json_object`、`thinking.type=disabled` 与 `max_tokens=8192`。
HTTP 200、finishReason=stop、约 **2.188 秒**，request ID `202609072116078ee2823883904f3b`；
provider usage 为 **124 input + 23 output = 147 tokens**。返回 JSON 无代码围栏、无 `reasoning_content`，probe 为 PASS。
这证明该次智谱请求的基本生成兼容性，不是 JSON Schema 约束的证明，也不能将关闭 thinking 的配置
表述为对模型内部计算过程的观测。凭据和推理正文不写入材料。

该生成请求独立于只读环境预检，未创建业务任务或执行 embedding/Qdrant/ToolRuntime。
此前六次 FAILED 保留，不能由商业 API 可达或单请求 PASS 判定 V47 成功。

#### 第七次真实运行：智谱 GLM-5.2 合法 JSON，仍重复工具意图

证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-zhipu-glm52-4bawq2af`。
本次预检 READY，使用 `glm-5.2`、DECISION json_object、thinking disabled、schema 关闭，decision/final
配置上限各 8192；保留总 24000、steps 5/tools 3、final reserve 2048、Chat 120 秒/任务 300 秒和原 prompt。
正常主应用运行，不加载受控 Gateway，自动重试 0。

- task `2096953425345351681`、Agent `2096953423323697154`、KB `2096953416898023425`、
  document `2096953417262927873`、chunk `2096953417648803842`；collection `v47_d048f077e0da4730a933232a1c7a3f05`。
  真实 DashScope/Qdrant/RAG 命中当前 generation 0：1 valid / 0 stale，score 0.66849273；文档/chunk/point 身份一致。
- 三次 DECISION 均 SUCCESS、合法 JSON、finishReason=stop，requested/resolved model 均为 `glm-5.2`；
  每轮记录 cap 8192、json_object、thinking disabled，responseSchema 缺失。PostgreSQL 与公开 Trace 请求选项逐字段一致。
  这是应用记录与公开 Trace 的一致性证据；wire 序列化另由重点 HTTP 测试验证，未取得智谱服务端请求日志，
  不将应用快照表述为厂商侧完整 wire payload 核对。
- 三次调用明细如下，合计 **EXACT 3314 = 3182 input + 132 output tokens**，不是核定账单：

  | 轮次 | Provider request ID | 耗时 | Input / output / total |
  | --- | --- | --- | --- |
  | 1 | `202609072127117ca4599cee484941` | 1767 ms | 976 / 48 / 1024 |
  | 2 | `202609072127125ad53690a0984d5d` | 1447 ms | 1061 / 44 / 1105 |
  | 3 | `20260907212714fba4f6be8c014458` | 1959 ms | 1145 / 40 / 1185 |

- 三次均选择 `order_query(order_1024)`：首次经 ToolRuntime 实际执行成功，耗时 6 ms；
  第二次复用缓存；第三次触发 `AGENT_DUPLICATE_TOOL_LOOP`。实际工具日志仅 1 条，payment_log_query 未执行。
  第二轮应用请求已有订单 observation，第三轮含原结果与 cached 结果；每轮均提供 payment_log_query，未发现漏传。
- 浏览器 **9.9 秒 FAILED**，阶段 `verify-persisted-evidence`；run-result 为 FAILED/exit 1/stage storage-evidence，
  storage 为 FAIL/errors=[]。无 FINISH、独立 FINAL_GENERATION、最终答案/引用或成功刷新；缺 final 的格式检查为 false。
  GET task 与 Trace.task 一致，17 个事件连续、最后为 TASK_FAILED。已核对 failure-state.png，显示失败且无最终答案。
- `provider-response-summary.json` 保留请求 ID、模式、usage 和安全结果摘要，无凭据或推理正文。
  本次 18047/5177/55447 及受控验收端口均已停止，运行证据、临时数据库与 collection 保留。

本轮 JSON 结构合法，三个请求均未达到超时或输出上限；不能将此次失败归因为低速、本地小参数模型或输出截断。
商业 API 切换未使本次任务完成，也不能由一次运行推断该 provider 的普遍能力，或证明关闭 thinking 导致循环。
已有 observation 与可选工具均存在，重复选择的内部原因仍未证实。第七次结束时尚未修改 prompt 或再发起任务；
后续针对“已完成动作/当前状态”开展下述独立诊断。第五次 payment_log_query 成功属于该次历史，不能挪用至第七次。

#### 执行历史提示的真实单步 A/B：不计入应用 E2E 次数

来源是第七次 task `2096953425345351681` 的第 2 次 DECISION，call `2096953439882809346`、
原 provider request ID `202609072127125ad53690a0984d5d`；此状态已有一次 order_query 结果，尚无 payment_log_query 结果。
证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/v47-history-paired-dx53hc5r/replay`。
候选为 983 UTF-8 bytes，只改变第二 SYSTEM content；A/B 输入保守估算分别 4245/5229 tokens，
实际请求均为 GLM-5.2、temperature 0.1、top_p 0.8、json_object、thinking disabled、max_tokens 8192。

| 组别 | JSON 与参数合法 | 目标 payment_log_query(order_1024) | 实际观察 | Provider total tokens |
| --- | --- | --- | --- | --- |
| A 原提示 | 3/3 | 0/3 | 三次重复 order_query(order_1024) | 3305 |
| B 追加执行历史语义 | 3/3 | 3/3 | 三次选择 payment_log_query(order_1024) | 3856 |

固定顺序 A/B、B/A、A/B 的六次响应均 stop，合计 **6883 input + 278 output = 7161 tokens**，自动重试 0。
逐次 provider ID、耗时和严格解析后的安全决策见 report.json。该结果支持本次状态下追加提示能够改善动作选择的判断，
但不是统计可靠性评估，不能推广到所有模型、工具或任务，也不证明先前失败的唯一内部机制。
六次请求均未执行所选工具；B 3/3 不能表述为新增三次 payment_log_query 的 ToolRuntime 证据。

#### 两结果历史复合状态的 FINISH 回放：不计入应用 E2E 次数

证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/v47-history-finish-968qb6_y`，
其中 `two-results-state.json` 明示复合来源，`replay/inputs.json` 与 `replay/report.json` 保存诊断输入和结果。
原三条消息与 order_query observation 来自第七次 task `2096953425345351681` 第 2 次 DECISION；
payment_log_query observation 来自第五次 task `2096902272884645889` 第 3 次 DECISION 中已有的真实成功工具结果。
仅为诊断把剩余预算设为 3 次 decision / 1 次 tool；这不是同一任务的生产快照，没有从数据库补造新工具日志。

保持同一智谱选项与候选，只发 B/B 两次请求，均返回合法裸 FINISH/stop、answerPlan 满足既有长度约束。
request ID 分别为 `20260907223801ba11db47ca794e6e`（4783 ms，1333/247/1580 tokens）和
`20260907223806547d02f43570455d`（5817 ms，1333/227/1560 tokens）；
合计 **2666 input + 474 output = 3140 tokens**，自动重试 0。两份 answerPlan 均指向订单/支付日志事实、资料引用和未知项说明。
此结果只支持在该显式两结果状态下候选能选择 FINISH；没有调用独立最终生成，也没有新任务、检索、引用或刷新成功证据。
两个诊断合计八次真实 Chat 请求、10301 reported tokens，独立于此前七次应用 E2E；历史支付结果仍不可计入后续新任务验收。

#### 第八次入口启动：PostgreSQL Unix socket 路径超限，未创建任务

证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-zhipu-history-1dij76aj`。
预检 PREFLIGHT_READY，run-result 为 FAILED/exit 1/stage postgres；PostgreSQL 日志明确 Unix-domain socket
路径超出 103-byte 上限。该次正常应用和浏览器尚未启动，没有 task、embedding、Chat 付费调用或工具执行，
因此不存在 browser-evidence.json/storage-evidence.json，也不能归类为模型失败。

必要入口适配是在 `pg_ctl` 中关闭未使用的 Unix socket；既有建库、应用与采证客户端均通过 loopback TCP 连接，
不改变业务 API、数据库迁移或执行协议。修复后在全新目录重新启动第九次入口，未重放不明结果任务；
本阶段计划的唯一新任务只在第九次实际创建，各入口 automaticRetries 均为 0。

#### 第九次入口 / 第八个实际任务：执行历史提示后的真实主路径 PASSED

证据目录：`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-zhipu-history-9yxypy7d`。
预检 PREFLIGHT_READY，正常主应用未加载受控 Gateway；浏览器 PASSED/complete，约 **22.8 秒**，
run-result PASSED/exit 0/stage complete，storage PASS/errors=[]、**23/23 检查通过**，taskSubmissions=1、automaticRetries=0。

- task `2096972424179240962`、Agent `2096972422098866178`、KB `2096972415597694978`、
  document `2096972415962599426`、chunk `2096972416348475394`；collection `v47_3eca76909da441e693ca3cb92005d350`。
  浏览器上传原 1293-byte MD，显式解析及 DashScope text-embedding-v4/1024 向量化，GET 为 READY，generation 0。
  真实 Qdrant/RAG 取得 1 candidate/1 valid/0 stale，score 0.66849273；持久 point、chunk 和本次 generation 一致。
- Agent 业务 prompt、任务输入与预算沿用第七次；生产第二 SYSTEM 使用经回放验证的精确通用执行历史说明。
  GLM-5.2、temperature 0.1/topP 0.8、context 32768、总 24000、steps 5/tools 3、final reserve 2048、
  Chat 120 秒/任务 300 秒保持不变。四次实际 cap 均为 8192；三次 DECISION 为 json_object，
  独立 FINAL_GENERATION 无 JSON 格式约束，均显式 thinking disabled，PostgreSQL 与公开 Trace 选项一致。
- 新任务依次完成 order_query → payment_log_query → 被严格 parser 接受的 FINISH → 独立最终生成，
  四次 LLM 均 SUCCESS，requested/resolved model 均为 glm-5.2。精确 usage 合计 **4759 input + 965 output = 5724 tokens**：

  | 阶段 | Provider request ID | 耗时 | Input / output / total |
  | --- | --- | --- | --- |
  | DECISION 1：order_query | `20260907224240ed49149e514348bf` | 1379 ms | 1149 / 41 / 1190 |
  | DECISION 2：payment_log_query | `2026090722424216deef0149d04811` | 2594 ms | 1232 / 61 / 1293 |
  | DECISION 3：FINISH | `20260907224244b43d8954f14245b1` | 4012 ms | 1335 / 190 / 1525 |
  | FINAL_GENERATION | `20260907224248a0a682b4f66049a7` | 10763 ms | 1043 / 673 / 1716 |

- 本次 order_query、payment_log_query 各有一次独立 SUCCESS 的 ToolRuntime 日志，各 5 ms、retryCount=0，
  参数 orderNo 均为 order_1024；不使用历史回放结果，也没有缓存重用充数。
  前者返回 CREATED/PAY_FAILED、199.00 CNY、E_PAY_TIMEOUT；后者返回 pay-trace-1024、ERROR、
  `Payment gateway response timeout after 3000ms` 及既有演示时间。
- 最终任务 COMPLETED/ANSWERED，decisionTurnsUsed=3、toolCallsUsed=2；20 条持久事件连续，末条 TASK_COMPLETED。
  任务从 startedAt 到 completedAt 约 **19.056 秒**；独立 final 正文与持久 finalAnswer 一致，
  唯一引用 S1 映射本次 document/chunk/generation 0 的实际 RAG hit。
  已完成 `refresh-and-trace-convergence`，刷新前后 task 和 Trace 完全一致，浏览器渲染/事件与 GET/Trace 收敛。
- 答案内容逐项对照两项工具与检索文档：写出目标订单、错误码、3000ms、traceId 与金额；
  把先核对渠道最终状态、状态未明不要重复扣款、按幂等规则处理或人工核对明确列为文档建议；
  保留是否实际扣款、渠道最终状态与延迟根因未知，没有声称已接入外部渠道或具备自动补单能力。

此记录证明固定 GLM-5.2、当前演示文档与既有业务数据的一次完整成功主路径。
提示适配后的成功与前述单步对照形成递进证据，但不能外推为跨模型可靠性、引用准确率统计或生产支付正确性；
也不能由一次成功覆盖前七次任务失败或第八次启动失败。
本轮八次单步诊断加新任务四次生成共 **12 次 Chat 请求、16025 reported tokens**（10301 + 5724）；
这只统计 Chat，不含 embedding 用量，也不代表核定账单。

### 受控回归

保留 `bash scripts/v46-browser-acceptance.sh`，它运行 V46 以及 V43/V45 浏览器回归；
真实 provider 测试使用独立配置，不进入受控回归，不在无真实依赖时悄悄改用 fixture。
前端构建与必要边界测试覆盖新验收代码和实际暴露的必要适配；不为本片重复扩展完整失败矩阵。
实际回归状态以本轮执行记录为准；上文分别保留适配前 46.5 秒、cap 适配后 48.7 秒、Schema 适配后
默认 schema 关闭/cap 512 下 47.3 秒，以及智谱适配后默认配置下 45.2 秒的四次 19/19 受控结果。
执行历史提示适配后的第五次默认受控回归另为 44.6 秒、19/19；不覆盖上述历史记录。

## 明确不做

完整失败 E2E（invalid decision、RAG empty、tool argument rejection/failure、token/deadline/cancel/reconnect 等
系统性矩阵）、仓库生成物清理、V0.1 发布验收或 tag；新业务 API、迁移、页面、执行协议、任意工具、
真实外部支付接入、provider streaming、多轮对话、自动任务重试、自动参数调优、多模型效果评测或检索质量基准。
本片保留现有受控回归，但不将其作为真实依赖成功的替代证据。

# Debug记录

可以。**这份文档本质上是在记录：一个本来应该“查订单 → 查支付日志 → 整理答案”的 Agent，接上真实模型后，为什么总在中途失败，以及开发者怎样一步步排查，最后跑通。**

先把那些版本编号、长串 ID、日志路径放到一边。我们主要看三件事：**本来应该发生什么，实际发生了什么，凭什么判断问题出在哪里。**

## 一、先弄清楚：这个 Agent 正常工作时应该怎样跑？

用户提出的问题是：

> 帮我分析 `order_1024` 支付失败的原因，并给出处理建议。

系统不能凭空编答案，而是要结合两类信息：

**一类是知识库中的通用说明**，例如支付超时通常是什么意思、应该怎样处理。这份材料没有提前写好这个订单的查询结果。

**另一类是工具实际查到的订单情况**：`order_query` 查订单，`payment_log_query` 查支付日志。这里的工具确实会执行代码，但查询的是项目里的演示数据库，不是真实支付公司的系统。

整个过程可以理解成下面这段对话。**这是帮助理解的示意，不是原始模型输出：**

```text
系统：我已经找到了有关支付超时的说明。接下来干什么？
模型：先查订单。
系统：执行 order_query，把查询结果记下来。

系统：订单已经查到了。接下来干什么？
模型：再查支付日志。
系统：执行 payment_log_query，把查询结果记下来。

系统：订单和支付日志都查到了。接下来干什么？
模型：信息够了，可以结束查询。

系统：好，现在根据这些资料，正式给用户写一份答案。
模型：输出最终分析和建议。
```

这里有个容易混淆的地方：

**“模型决定下一步做什么”和“程序真正执行工具”，不是同一件事。**

模型返回 `CALL_TOOL`，相当于交一张“请执行这个工具”的指令单；程序检查指令后才真正执行。模型返回 `FINISH`，相当于说“可以停止查询了”，之后系统还会**单独再调用一次模型，生成给用户看的正文**。

所以，正常预期是 **3 次决策调用，加 1 次正式写答案的调用**，而不是一次模型请求完成所有事情。

------

## 二、前七次运行，是怎样一步步暴露问题的？

### 第一次：模型一下子交了两张指令单，程序只允许一张

第一次用的是文档中的 **Qwen 1.7B** 模型。

知识库上传、向量化、检索都成功了，模型也确实返回了内容。但它一口气返回了两个独立的 JSON 对象：

```text
第一张指令单：查订单。
第二张指令单：查支付日志。
```

人看着可能觉得：“挺好啊，它知道两个都要查。”

但程序事先约定的是：

> **每轮只能决定一个动作。执行完这个动作，拿到结果之后，再决定下一步。**

负责读取模型指令的程序，也就是文档中的 `parser`，读完第一个 JSON 后发现后面还有第二个，于是判定格式不合法，任务失败。

**他们怎么确定的？**
通过这次模型请求的编号，找到对应的模型服务响应，确认确实是两个相邻的 JSON，不是网络问题，也不是输出被截断。

所以，这次定位到的问题是：**模型没有遵守“一次只交一张指令单”的规则。** 文档没有选择“偷偷只取第一张”来蒙混过去，因为那会改变原本的执行规则。

### 第二次：换了模型，第一步做对了，但第二步等不及了

接着换成 **Qwen 9B**。

这次模型第一轮按要求只返回了一个动作，程序成功查到了订单，里面有支付失败、支付超时的信息。

但是第二轮询问模型时，程序等了大约 **30 秒**，还没收到回答，就报错了。

这里并不是看到“模型调用失败”就猜模型坏了，而是核对了两边的记录：

> 程序在约 30 秒时失败；模型服务同时记录客户端断开，并取消生成。

这个时间又刚好对应配置里的 **30 秒读取超时**。

因此，这次问题是：

> **程序愿意等的时间太短，而本地模型生成得比较慢。**

后续把单次模型调用的等待上限改成了 120 秒，整个任务的时间上限改成了 300 秒。两个上限不同：一个管“这次问模型能等多久”，另一个管“整个任务能跑多久”。

### 第三次：终于等到回答了，结果模型一直要求查同一个订单

延长等待时间后，这次没有超时，三轮模型回复也都是合法 JSON。

但出现了另一种问题：

```text
第一轮：查 order_1024。
第二轮：再查 order_1024。
第三轮：还是查 order_1024。
```

系统对这种重复请求有保护：

```text
第一次：真正执行查询。

第二次：发现工具和参数完全一样，
        直接把上次结果再拿出来，不重复查。

第三次：又提出同样的请求，
        判断可能陷入死循环，终止任务。
```

因此，**三次要求调用工具，不等于工具真的执行了三次**。这次实际只查了一次订单。

这里最重要的排查动作是：

> **模型是不是根本没看到上一次的查询结果？或者系统忘了告诉它还有支付日志工具？**

他们检查程序保存的请求内容，发现第二轮已经包含订单结果，每轮也都列出了支付日志工具。

所以，至少从应用保存的请求来看，**没有发现“组装请求时漏掉查询结果、漏掉工具”的问题**。但模型为什么仍然重复选择，还没有查明。

这也说明：**等待时间的问题解决后，模型如何选择下一步的问题，才清楚地露了出来。**

### 第四次：又换了模型，这次不是等得不够久，而是输出额度用完了

随后换成文档中的 **Gemma 4B**。

第一轮正常，订单查到了。第二轮又报“模型调用失败”。

注意：**同样的报错名字，不一定是同样的原因。**

这次等了约 29 秒就结束了，远没到新的 120 秒超时限制。查看模型服务响应，发现：

```text
本次允许的输出额度：512 tokens
服务端报告的输出量：511 tokens
其中被标记为 reasoning 的部分：509 tokens
可供程序读取的正式 content：空
结束原因：length，也就是碰到了长度限制
```

你可以把 token 暂时理解成“文字处理额度”，但它不严格等于字数。

打个比方：**只给了有限的纸，服务端统计中被归为推理输出的部分几乎把纸用完了，最终供程序读取的指令没有交出来。**

所以这次不是“还没等到”，而是“生成已经结束，却没有可用指令”。

于是代码做了一项调整：

> 原来每轮决策最多输出 512 tokens，写死了；现在改成可配置，本次尝试设为 2048。

不过，**单轮额度调大，不等于整个任务可以无限花额度**。任务总预算和给最终答案预留的预算仍然保留。

### 第五次：两个工具终于都查到了，但“结束指令”外面多了一层包装

把单轮输出额度设成 2048 后，这次走得更远：

```text
查订单：成功。
查支付日志：成功。
告诉系统可以结束查询：失败。
```

失败点很细，但对程序来说很明确：

模型确实返回了 `FINISH`，但它把 JSON 包在了 Markdown 代码块里面，也就是加了平时聊天里常见的那种“代码展示框”。

**人看到的是一份格式漂亮的 JSON，程序看到的却是“JSON 外面还有别的字符”。**

而程序的要求是：

> 整个回复必须就是一个 JSON 对象，不能有解释文字，也不能有 Markdown 包装。

他们查看实际响应后确认了这一点，也检查了提示词：原本已经明确禁止 Markdown，并不是开发者忘了提出要求。

所以，这次是**模型没有遵守已有的输出格式要求**。虽然两个工具成功执行了，但结束指令没被接受，后面的正式答案生成就没有启动。

### 第六次：给模型一份更明确的输出格式，但它又开始重复查订单

既然“用文字告诉模型不要加包装”还不够，他们接着尝试 **JSON Schema**。

白话说，就是：

> 不只是口头说“按格式写”，而是把一份机器可读的表格规范交给模型服务：允许哪些动作，每种动作必须有哪些字段，不允许多出哪些东西。

他们先单独发了一个小请求，确认当前服务能接受这种格式要求，再把它接进完整应用。这个小测试只证明“这种请求方式能用”，不代表整个 Agent 已经成功。

完整运行后，三次回复确实都成了合法、没有代码块包装的 JSON。

但内容又变成了：

```text
查订单。
再查订单。
还是查订单。
```

最后再次触发重复调用保护。

这一步特别值得理解：

> **“指令单填得符合格式”，和“指令单上写的是正确的下一步”，是两回事。**

JSON Schema 约束的是输出形式，不能仅凭这个就保证模型理解了“订单已经查过了，现在该查支付日志”。这次记录也没有证明 Schema 导致了重复，只是证明：**格式问题没出现，但动作选择仍然有问题。**

### 第七次：换成云端 GLM，依然重复查订单

接下来换成智谱 **GLM-5.2**，按该次服务的兼容方式配置 JSON 输出，并调高输出上限。

这里使用的 `json_object` 可以理解成“要求返回一个 JSON 对象”，**不是上一轮那种细到字段的 JSON Schema 约束**。程序自身的严格检查仍然保留。

这次三个请求都很快，JSON 也合法，没有超时，没有碰到输出上限。

但还是：

```text
查订单 → 重复查订单 → 再次重复 → 被系统终止。
```

检查应用保存的请求，订单结果和支付日志工具也都在。

到这里，已经不能简单地把这次失败解释为：

> “本地电脑太慢”“小模型不行”或者“输出额度不够”。

**换了服务和模型，重复行为仍然出现了。接下来该认真检查的，是系统有没有把“哪些事情已经做完了”向模型说明白。** 这只是进一步排查的方向，还不是已经证明了模型内部出错的原因。

------

## 三、真正关键的 debug：把出错的那一步单独拿出来，做对照

前面每次都要重新创建知识库、上传文档、创建 Agent、运行任务，才能看到第二轮决策到底会怎样。

但此时问题已经缩小了：

> **订单已经查到了，为什么模型下一步仍然要查订单？**

于是，他们没有继续一遍遍跑完整流程，而是把第七次失败时的**第二轮模型输入**保存下来，单独拿它做实验。

这就是文档说的“决策回放”：

> 把当时那道题重新交给模型做，但不真的运行工具，也不重新跑浏览器和知识库流程。

### 1. 保持现场不变，只改一处说明

实验分成两个版本：

**A 版本：**原来的输入，不修改。

**B 版本：**同样的输入，只在系统提示中追加一段“如何理解执行历史”的说明。

追加内容用白话概括，大致就是：

> 下面的 `observations`，是已经执行成功的工具结果，不是还需要你执行的事情。
> `reused=true` 表示拿出了缓存结果，不是重新查了一遍。
> 对同一个工具使用相同参数，不会刷新数据。
> 你应该根据已经得到的信息，判断还缺什么；信息足够时，就返回 `FINISH`。

**这里的 `observation`，你直接把它理解为“前面已经查到的结果”就行。**

这段提示没有硬编码“必须先查订单，再查支付日志”，也没有规定“任何工具永远只能用一次”。它补充的是一条更通用的说明：**把已完成的工作当成历史，而不是从头再做。**

### 2. 两种输入分别试三次，看模型下一步怎么选

结果很直观：

| 输入版本   | 三次实际选择         |
| ---------- | -------------------- |
| 原提示 A   | 三次都重复选择查订单 |
| 加说明的 B | 三次都选择查支付日志 |

模型、已有查询结果和采样参数保持一致，主要变化就是补充的那段说明。

因此，这个小实验提供了比“换了模型，好像变好了”更清楚的证据：

> **在这个已经查过订单的状态下，补充执行历史说明，确实改善了接下来的动作选择。**

但样本很少，不能据此说以后所有任务都稳定，也不能证明先前重复的唯一内部原因就是这一点。另外，这六次实验只是让模型“选动作”，没有真的执行所选工具。

### 3. 再检查：两个结果都有了，它知不知道该停？

还有一个问题：

> 现在它会继续查支付日志了，但查完之后会不会又继续乱查？

他们于是构造了另一份诊断输入：把历史上已经真实查到的订单结果和支付日志结果放在一起，再问模型下一步做什么。

加了说明后，两次都返回了合法的 `FINISH`。

这只能说明：

> **在这份“两个结果都已经齐全”的测试输入下，它知道应该停止查询。**

不能把它当成新的完整任务已经成功，因为这些结果来自不同的历史任务，这个测试也没有真正执行工具、生成最终答案或验证页面刷新。

**到这一步，才把这段说明正式加入程序，准备重新跑一次完整流程。**

------

## 四、正式复测：先遇到数据库启动问题，然后终于跑通

### 第八次：还没轮到模型，数据库就没启动起来

这次失败与模型完全无关。

临时 PostgreSQL 准备使用的一条本地通信路径太长，超过了 **103 字节**限制，因此启动失败。

当时应用和浏览器都还没启动，更没有创建任务，所以不能把它算成“模型又失败了一次”。

修复也没有动业务逻辑：原本应用和采证程序都是通过本机 TCP 连接数据库，并不需要那条 Unix socket 通信路径，于是把未使用的 socket 关闭即可。

### 第九次：用新任务，从头到尾独立跑通

修好启动问题后，重新开始了一次完整测试。

这次不是把以前的成功片段拼起来，而是**新建知识库、新上传资料、新建任务，重新执行工具**。

最终顺序是：

```text
检索本次上传的知识库材料
        ↓
模型选择查订单 → 工具实际执行成功
        ↓
模型选择查支付日志 → 工具实际执行成功
        ↓
模型返回合法 FINISH
        ↓
单独调用模型，生成最终答案
        ↓
答案保存成功，引用对应本次资料
        ↓
刷新页面，答案和引用仍然一致
```

浏览器测试约 22.8 秒完成，存储检查 **23/23 通过**。两个工具在这个新任务里各真实执行了一次，没有拿历史结果或者缓存重用来充数。

他们还人工核对了最终答案：订单、错误码、超时时间等事实是否对应工具结果；处理建议是否来自资料；不知道的事情有没有明确保留为未知，而不是编造“已经扣款”“已经查明支付渠道最终状态”。

所以，准确的结果是：

**启动入口九次，其中一次数据库没启动、没有创建任务；实际创建八个任务，七次失败，一次成功。** 这证明固定模型和固定材料下有一条完整流程跑通了，不等于整个项目已经达到发布标准。

## 五、这段 debug 最值得你理解的是什么？

### 不是看见一个“失败”标签，就直接猜原因

文档里两次都出现了“模型调用失败”，但实际原因不同：

**一次是等待超时，另一次是输出额度耗尽后没有正文。**

必须继续看时间、实际响应、结束原因，才能区分。单看报错名字，很容易改错方向。

### 不是“某一小段成功”，就等于“整个任务成功”

这份文档反复强调边界，是因为这里有很多层“成功”：

> 服务能连上，不等于模型能正常生成。
> 模型能输出合法 JSON，不等于下一步选对了。
> 模型选了工具，不等于工具实际执行了。
> 两个工具执行了，不等于最终答案已经生成并保存。
> 页面暂时显示答案，不等于刷新后还能恢复。

因此，前面写着很多“测试全部通过”，后面真实任务仍然失败，并不矛盾。那些测试分别验证配置、程序逻辑和受控流程，不能代替真实模型参与的完整任务。

### 最关键的进步，是把“大问题”缩成了一个可以验证的小问题

起初的问题很大：

> “为什么我的 Agent 跑不通？”

后来缩小成：

> “订单结果已经在输入里了，为什么模型还重复查订单？”

再进一步变成一个可验证的假设：

> “给它补充明确的执行历史说明，下一步选择会不会改变？”

最后才是：

> “小实验有效了，把修改放回真实系统，再重新检查完整流程。”

**这就是这段记录里最有价值的 debug 思路：不是不断碰运气重跑，而是用日志缩小问题范围，针对一个具体假设做对照，再用新任务确认修改真的能让整条流程走通。**

## 面试问题与回答

**问题 1：为什么正常应用启动比在 V46 fixture 中替换一个模型更适合作为本片验收入口？**

回答：V46 的模型、embedding 和向量边界受控，其通过只能证明对应浏览器/后端/数据库链路。
本片要求正常 main runtime classpath 与真实 Chat、DashScope、Qdrant 全部同时参与，避免测试 Bean
仍接管某个边界。还必须用实际检索、工具和 final Trace 证明执行，不能仅凭启动配置声明真实 E2E。

**问题 2：知识库 READY 或 task COMPLETED 为什么不足以判定通过？**

回答：READY 只证明服务端当前文档可用于检索，不证明任务实际命中了它；COMPLETED 也可能是预算终止后给出回答。
本片同时核对冻结 generation 与 RAG hit、两次成功 ToolRuntime 调用、`ANSWERED`、独立 final、有效引用和刷新恢复。
缺少任意一项都不满足本片成功门槛。

**问题 3：两个工具为什么给三个调用配额，又要求恰好两次实际调用？**

回答：已有执行器在每轮决策前检查已用工具数是否达到额度。额度 2 会在完成两次查询后立即退出决策循环，
无法观察模型 FINISH。额度 3 留出正常决策空间，成功断言仍要求两次工具和 `ANSWERED`，避免把预算耗尽当作正常完成。

**问题 4：如何证明答案利用了检索与工具，而不是演示文档已经写好答案？**

回答：演示文档仅给通用超时规则，不含目标订单查询事实。成功验收要求核对同一 `order_1024` 的两项 ToolRuntime 参数/结果、
当前文档 generation 的有效 RAG hit，以及答案事实、有效引用和持久结果。第九次入口的新任务独立取得两项工具结果，
经 FINISH 和独立 final 产出含有效 S1 的答案，刷新与 GET/Trace 一致；答案事实、建议与未知项已逐项对照。
第五次两工具成功但无 final、第六七次合法 JSON 却重复工具，均保留为失败，不能挪用历史结果给新任务验收。
第九次只证明一条固定主路径，不等于统计意义上的模型事实性或引用准确率评估。

**问题 5：为什么先做有限决策回放，再验证完整 E2E？失败如何归类？**

回答：第七次 JSON 合法且已有订单结果，仍重复查询；固定同一输入，仅给 B 的第二 SYSTEM 增加执行历史说明，
得到 A 0/3、B 3/3 选择目标支付日志工具，支持先实施该提示适配。两结果 FINISH 的 B 2/2 来自显式历史复合状态，
没有执行工具或最终生成，不能当作同任务成功。第九次随后新建任务，才独立完成两工具、final、引用和刷新验收。
前七次任务失败、第八次 PostgreSQL 启动失败与第九次成功按实际阶段分开记录；第八次没有 task 或付费调用，不能归咎于模型。
预检、原生 probe、本地测试、回放与完整 E2E 各自证明不同边界，不自动重试、放宽 parser 或回退 mock。
JSON object 不等于 JSON Schema；配置 8192 仍受 24000/context 约束，既有 final reserve 2048 不变。

**问题 6：本片的“真实工具”是否代表真实订单和支付系统？**

回答：已有 builtin handler 经 ToolRuntime、参数校验和 Trace 实际执行，数据仍来自既有 V12 演示表，不是外部支付系统。
第九次成功任务中两项工具各有一次 SUCCESS、5 ms、retryCount=0 的本次调用，并在其后独立生成答案。
此前第六七次缓存复用不算新增执行；历史复合回放中的第五次支付结果也不属于第九次，不能用绑定或模型选择代替实际工具日志。
真实外部支付接入、生产业务正确性、完整失败矩阵和 V0.1 发布验收均未纳入本片。
