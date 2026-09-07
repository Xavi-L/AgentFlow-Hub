# V46 最小 Agent 配置前端接口包说明

> 路线位置：M4G-C，接续 V43/M4G-A 任务运行前端与 V45/M4G-B2 知识库管理前端。
> 开工基线：`main@2ba3a88`，2026-09-06 已 fetch 并核对 `origin/main`，无分歧。
> 本片范围是最小 Agent 配置前端，不代表完整 M4G；真实模型/Qdrant E2E 留待后续。

## 目标与冻结范围

新增 `/agents` 与 `/agents/:agentId`，提供当前用户的分页列表、详情、创建、配置编辑和启停。
详情分别编辑并保存公开配置、知识库绑定、工具绑定，并进入既有 `/agents/:agentId/run` 创建任务，
沿用任务页的答案、恢复、取消及 Trace 展示。

provider 固定为 `openai-compatible`；模型名称由用户填写部署支持的值，不请求或虚构模型目录、
模型 profile 接口。绑定使用已有公开 GET/PUT，工具只开放已有目录中受支持的两个内置工具。
本片不增加后端公开接口、migration 或执行引擎能力。

## 页面与已有接口

复用已有同源 API 客户端、Bearer JWT、统一 ApiResponse、无损 JSON 解析、会话清理与受保护路由。
当前用户身份只来自 JWT，页面不提交 owner/userId。路径中的业务 ID 及绑定 ID 始终为十进制字符串，
不得转换为可能损失 long 精度的 Number。

| 页面操作 | 既有接口（前缀 `/api/v1`） | 输入与使用规则 |
| --- | --- | --- |
| Agent 分页列表 | `GET /agents?page=N&pageSize=20` | 使用服务端 `items/page/pageSize/total/hasNext`，保留原有排序 |
| Agent 详情 | `GET /agents/{agentId}` | 响应 ID 必须匹配当前路由 ID；展示公开配置与 `ACTIVE/DISABLED` |
| 创建 Agent | `POST /agents` | 只发送下表的公开配置；明确 201 响应后进入新 Agent 详情 |
| 保存配置 | `PATCH /agents/{agentId}` | 本页面发送完整公开配置；接口本身仍是已有的部分更新契约 |
| 启用/停用 | `POST /agents/{agentId}/enable`、`POST /agents/{agentId}/disable` | 无请求体；独立确认状态 |
| 读取/替换知识库绑定 | `GET/PUT /agents/{agentId}/knowledge-bases` | PUT 为 `{ knowledgeBaseIds: string[] }` 全量替换 |
| 知识库分页选项 | `GET /knowledge-bases?page=N&pageSize=20` | 复用 V45 列表与只读兼容性判断 |
| 读取/替换工具绑定 | `GET/PUT /agents/{agentId}/tools` | PUT 为 `{ toolIds: string[] }` 全量替换 |
| 工具选项 | `GET /tools` | 从实际返回目录筛选，不硬编码工具 ID |
| 运行衔接 | `POST /agents/{agentId}/tasks` | 从既有运行页提交，沿用 V43 task Idempotency-Key 契约 |
| 答案与 Trace | `GET /tasks/{taskId}`、`GET /tasks/{taskId}/trace` 及已有 SSE | 继续使用 V43 已有页面与状态恢复逻辑 |

列表响应校验分页身份和必要字段；详情、启停响应校验资源身份，启停还校验目标状态。
后端 Jackson 使用 NON_NULL，空 `description` 可能被省略；前端将缺省描述规范为 null，
同时继续拒绝其他非法类型。这个分支由真实浏览器空描述记录和 API 回归验证。
绑定响应必须提供对应 ID 数组；响应损坏或无法确认的写入不当作成功，也不自动重发。
Agent 删除接口虽在后端存在，本片不增加删除入口或调用。

## 配置字段与数据契约

以现有 `CreateAgentAppRequest`、`UpdateAgentAppRequest` 和 `AgentAppService` 为准：

| 公开字段 | 本页面校验与提交方式 | 初始草稿值 |
| --- | --- | --- |
| `name` | 必填、非空白，最多 128 字符；提交时 trim | 空 |
| `description` | 最多 4000 字符；trim 后为空提交 null | 空 |
| `systemPrompt` | 必填、非空白，最多 20000 字符；保留实际正文和空白，不 trim 正文 | 空 |
| `modelProvider` | 固定 `openai-compatible`；后端最长 64 字符且只接受此值 | 固定值 |
| `modelName` | 必填、非空白，最多 128 字符；提交时 trim | 空 |
| `temperature` | 0–2，最多 3 位小数 | 0.2 |
| `topP` | 大于 0 且不超过 1，最多 3 位小数 | 0.8 |
| `maxSteps` | 1–20 的整数 | 6 |
| `maxToolCalls` | 0–20 的整数，且严格小于 `maxSteps` | 4 |
| `maxTokens` | 256–100000 的整数 | 8000 |
| `timeoutSeconds` | 1–600 的整数 | 120 |

沿用公开字段 `maxSteps/maxTokens`，不暴露或另造引擎内部预算别名。
`maxToolCalls < maxSteps` 为最终回答保留步骤，因此实际可接受的最大工具调用预算是 19。
数字编辑值以文本保存在草稿，空值、未完成输入、科学计数法或超界值不会被悄悄替换为默认值。
默认值只用于新建草稿；页面校验通过后才转换为请求数字。服务端继续执行最终业务校验。

创建/编辑请求以字段 allowlist 构造，不携带响应中的 `id/status/createdAt/updatedAt`，
不混入知识库、工具绑定、owner、config JSON 或凭据。保存配置只证明已有接口接受了该配置，
不验证模型可调用性，也不证明运行成功。

## 依赖绑定契约

知识库与工具 PUT 都是各自集合的全量替换，空数组用于显式清空。知识库最多 50 个，工具最多 20 个。
前端使用无重复 ID 列表，保留选择顺序；后端以此顺序写入 priority，因此读回核对也比较顺序，
不能只比较集合成员。

知识库选择与当前页选项独立保存。翻页、新一页响应到达、重新加载页面或刷新选项时，
不会用当前页 rows 重建已选数组。跨页已选 ID、未加载到名称的 ID、已失效绑定仍保留在已选区，
用户必须明确移除；“未在已加载列表中找到”不能单独证明该资源已删除。
当前选项中禁用、配置不兼容的知识库不能新增选择。已有失效绑定不会被静默过滤后发送一个较短数组；
服务端拒绝替换时保留草稿和原有已读绑定，分别显示错误。

知识库兼容性复用 V45 的 `embeddingProfileCode` 与 `chunkStrategyVersion` 读模型。
配置兼容、知识库启用及绑定成功都不等于具有 READY 语料。页面提示用户去知识库详情检查文档
`retrievalReadiness`；本片不根据绑定成功、解析完成或目录中的库状态推导 READY。
创建任务时的 Agent、绑定、文档与快照准入仍由已有后端负责。

工具选项只保留 `toolCode` 为 `order_query` 或 `payment_log_query`、`type=BUILTIN`、
`status=ACTIVE` 的目录记录，提交其实际 `id`。不把 toolCode 当作 ID，不虚构目录记录。
已绑定但现在不在可选列表中的工具 ID 保留并提示明确移除；未移除时禁止工具绑定保存。
不开放 `report_generate`、HTTP、MCP 或工具调试入口。

## 分区保存、草稿与请求生命周期

配置、知识库绑定、工具绑定有各自的保存按钮、busy/error/notice 和待确认状态；启停使用第四个独立通道。
一个分区失败或待确认不应阻止其他分区的独立操作，也不会取消其他分区的请求。
不存在“一次保存全部”事务或自动补偿。一个分区成功不能被展示为整个 Agent 已保存完成。

配置、知识库选择、工具选择以 owner + Agent ID + 分区保存于内存与当前标签页 sessionStorage，
新建草稿单独使用 `new` 资源键。离页、翻页与刷新保留草稿；退出登录、401 或会话清理时清除。
sessionStorage 不可用时退化为当前内存，不承诺此时刷新后保留。配置草稿包含用户实际编辑的 prompt
等必要内容，草稿与待确认记录不复制会话 JWT 或其他凭据。

草稿与最近读取/确认的服务端状态分离。初次读取只在没有该分区草稿时初始化编辑器；
普通刷新、保存响应和未知结果读回不覆盖更新的草稿。页面分别显示是否存在未保存差异和已读服务端值。
不同 owner 或已过期页面不能读取、写入或删除另一个会话的草稿。

每页复用 `requestScope`：列表、详情、知识库选项、两类绑定读取及各类写入各自隔离。
新读取取消同通道旧读取；响应落地前核对当前通道、页面存活和 session revision。
路由切换时按完整路由重新创建页面，离页取消请求；退出清除登录状态并取消在途请求。
即使传输层忽略 abort，旧页或旧会话的晚到响应也不能覆盖当前页面。取消等待不撤销服务端写入。

## 未知写入结果与读回核对

Agent 创建、配置 PATCH、绑定 PUT 与启停不使用 task 的幂等重试策略，不发送伪造的 Idempotency-Key。
写前先持久化 owner、资源、分区、操作名和本次精确预期值，然后才发请求；同分区双击被合并保护。
明确成功或明确业务拒绝解除待确认记录。超时、断网、5xx、损坏成功响应，或离页后未能确认响应，
保留“结果待确认”；重新进入或刷新后仍禁止该分区再次写入。

核对只能显式发 GET，不自动重发原写请求：

1. 配置、绑定、启停的读回值与写前保存的预期值一致时，可解除待确认。
   这只证明**当前服务端状态匹配**，不证明原请求的完成时间、响应归属或唯一执行次数。
2. 读回成功但不一致时继续保留待确认，展示最近服务端值与本地草稿。
   不把不一致解释为“未提交”，也不立即恢复保存按钮。只有用户核对后明确接受当前状态，才允许新的保存；
   页面继续提示原请求仍可能晚到。
3. GET 失败时保持待确认，不能使用一次失败的读回解除保护。
4. 创建没有可用于归因的服务端幂等契约。列表出现同名或同配置记录不能自动确认原创建；
   用户须成功读取列表并核对，再明确选择允许新的创建。该选择仅解除本地保护，不能证明原请求未提交。

读回相等比较的是本次发送前的快照，不能改用用户在未知期间继续编辑的新草稿。
三个保存分区分别核对、分别解除，不因其中一个 GET 匹配而清除另一个分区的待确认状态。

## 运行衔接与状态边界

详情页对 ACTIVE Agent 提供既有运行页入口，运行页预选该 Agent。创建任务使用服务端已保存的配置与绑定，
本地未保存草稿不参与任务提交。继续沿用 V43 已有 task 幂等提交、SSE/GET 恢复、答案、取消与 Trace 页面。

启停只改变后续新任务的准入，不取消已经运行的任务。配置或绑定保存也不重新配置已有任务；
既有任务使用其创建时冻结的执行快照。本片不修改生命周期、快照、执行引擎或任务取消语义。

## 实现与验收入口

- `frontend/src/lib/agent-api.ts`：公开 DTO、完整配置 allowlist、校验、已有接口和有序绑定比较。
- `frontend/src/lib/agent-requests.ts`：owner 分区草稿、独立 mutation 通道、未知结果保护与显式 GET 核对。
- `frontend/src/pages/AgentsPage.vue`、`AgentPage.vue`：分页创建、详情、三分区保存、启停及运行入口。
- `frontend/src/components/AgentConfigurationForm.vue`、`AgentWriteResult.vue`：共享编辑器与分区结果展示。
- `frontend/src/lib/agent-api.test.ts`、`agent-requests.test.ts`：公开字段、校验、依赖选择与状态边界测试。
- `frontend/e2e/agents.spec.ts`：真实浏览器验收入口，保留 V43 `task-runtime.spec.ts` 和 V45 `knowledge.spec.ts` 回归。
- `backend/src/test/java/com/agentflow/acceptance/V46BrowserFixture.java`：继承 V43/V45 受控 provider，
  使用实际 JWT、公开 Agent/绑定/task API、PostgreSQL、任务运行器与持久 Trace。
- `scripts/v46-browser-acceptance.sh`：复用 V43 独立临时库启动器，一次运行 V46 与 V43/V45 回归。

```bash
cd frontend
npm test
npm run build
cd ..
bash scripts/v46-browser-acceptance.sh
```

沿用 Node 22.12+、Java 21、Maven、PostgreSQL binaries 与 Playwright Chromium/Chrome。
V46 默认前端 5176、后端 18046、PostgreSQL 55446，可用 `V46_FRONTEND_PORT`、`V46_BACKEND_PORT`、
`V46_PG_PORT` 覆盖；`V46_CONTROL_DIR` 指定独立临时目录。测试 fixture 只允许 disposable loopback 数据库，
启动器结束后清理前后端与 PostgreSQL 进程，并保留日志、截图和证据 JSON。

### 验收覆盖

- 浏览器通过真实 JWT 从 `/agents` 创建 Agent，分别保存配置、知识库与工具绑定，从详情进入运行页创建任务，
  最终答案、引用和 Trace 与实际 GET 持久结果一致。
- 验证三个分区的请求和结果独立；绑定保存不覆盖未保存配置；启停不取消已运行任务。
- 验证 Agent/知识库分页、跨页已选 ID、有序绑定、失效绑定保留、实际工具目录白名单与预算/长度边界。
- 验证真实写入已提交后丢弃响应，reload 后仍待确认且不自动重发；配置、绑定与启停通过 GET 独立核对；
  创建列表同名不被当作幂等归因。读回不一致/失败需保留保护，明确接受后才解除。
- 验证配置和两类绑定草稿在离页、刷新后保留；晚到响应隔离，退出清理草稿/待确认记录，缺失资源与 JWT 拒绝。
- 执行 V43 运行恢复/取消/Trace 与 V45 知识库管理回归，并检查真实浏览器截图布局。

### 验收记录

2026-09-06 已完成本片验证：

- `npm test`：8 个文件 **76/76** 通过。V46 新增 API/字段/请求边界 35 项、草稿与未知写入 9 项，
  保留 V43/V45 的 32 项。读回不一致、失败和显式接受边界由单元测试验证。
- `npm run build`：`vue-tsc --noEmit` 与 Vite 7.3.6 生产构建通过。
- `bash scripts/v46-browser-acceptance.sh`：真实 Chrome/Playwright **19/19** 通过，测试阶段 **47.3 秒**，
  包含 V46 9 项、V45 7 项、V43 3 项；不含编译与服务启动耗时。PostgreSQL 18.4 全新库成功应用既有 V1–V20。

闭环 Agent `2096626487825248258` 由浏览器创建；知识库绑定为 `430000000000000005`，工具通过
实际列表选择 `order_query` 的 ID `270000000000000001`。配置草稿在两次绑定保存后仍保留，
直到独立 PATCH 才进入服务端。页面随后创建 task `2096626491226828801`，答案与引用和 GET/Trace 一致，
持久执行包含 **5 steps、15 events、1 次 RAG、3 次 LLM、1 次工具调用**。

任务 RUNNING 时浏览器停用 Agent，真实 200 提交响应被丢弃后仅 GET 核对，再启用 Agent；
两个操作后原 task 均仍为 RUNNING，释放受控模型后正常 COMPLETED。主流程记录 7 次带 Bearer 的写请求：
创建、两个绑定 PUT、配置 PATCH、创建任务、停用、启用，没有“保存全部”请求。

其余浏览器覆盖 Agent/知识库分页与预算拦截、跨页完整 ID 数组、失效知识库 ID 原样保留且服务端拒绝后不删除；
创建/配置/两个绑定响应丢失后不重发，reload 保留待确认与更新的草稿；创建读回与翻页交错不覆盖新页；
配置 PATCH 提交后离页实测 browser abort，返回只读回核对，晚到响应不覆盖新草稿；退出清空草稿与未知记录，
缺失 Agent 与真实 JWT 拒绝均不能显示受保护表单。V43/V45 全部原有浏览器回归通过。

本次临时证据目录：
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v46-browser.YzK8s2`。
`browser-artifacts/agents-page-created-Agent--40a91--persisted-answer-and-Trace/` 中保存
`agent-evidence.json`、`configured-agent.png`、`agent-created-trace.png`；已检查配置/绑定与 Trace/答案截图布局。
同目录还保存 V43/V45 回归产物，`backend.log`、`postgres.log` 保留实际启动与迁移记录。
验收脚本补充临时 PostgreSQL 的同步清理，并拒绝复用已有 `pgdata`；完成后确认 5176/18046/55446 均无监听。
临时产物不是长期交付依赖，复现入口是仓库脚本和测试。

证据边界：JWT、公开 HTTP、Agent/绑定持久化、任务生命周期和 Trace 使用真实后端与 PostgreSQL；
模型、embedding、vector 检索/upsert 由 test-source 受控替身提供。分页、历史失效绑定和部分文档状态
来自数据库夹具；丢失响应、延迟和断网由浏览器控制注入。不得将这些结果称作真实 provider、真实 Qdrant
E2E、物理网络故障覆盖、线上能力或检索效果证明。夹具不改变生产服务实现。

## 明确不做

Agent 删除、Prompt 版本、工具调试、任意 provider/HTTP/MCP 工具、模型目录或 profile 接口、
新后端公开接口、migration、执行引擎改造、原子“保存全部”、自动重发未知写入、真实模型/Qdrant E2E。
不把绑定成功等同于 READY，不把本片完成等同于完整 M4G。

## 面试问题与回答

**问题 1：为什么配置、知识库和工具绑定不做一个“保存全部”？**

回答：现有公开契约是配置 PATCH 与两个独立的全量替换 PUT，没有跨接口事务或补偿契约。
页面分别保存和显示结果，独立通道避免一个分区的请求取消另一个分区。一次成功只能确认该分区，
不能把部分完成描述成整个 Agent 配置原子提交。

**问题 2：未知写入后 GET 值相等，能否证明原请求成功；值不相等能否直接重试？**

回答：相等只证明当前服务端值与写前预期匹配，不证明原请求的归属或执行次数。不相等也可能因为原请求
尚未提交或服务端值后来改变，因此不能自动重发。页面先保留待确认和草稿，成功读回后由用户明确接受当前值
才解除保护。创建没有归因 ID，同名列表项也不能自动解除未知创建。

**问题 3：如何避免知识库翻页导致已选或失效绑定丢失？**

回答：已选 ID 数组独立于当前页 rows 保存，只在用户勾选或明确移除时改变；翻页只更新选项。
无法从已加载页得到名称的 ID 仍显示并保留，不推断为应删除。PUT 使用完整有序数组，服务端拒绝失效绑定时
保留原草稿与已读结果。绑定顺序是 priority，读回比较同时核对成员和顺序。

**问题 4：为什么保留草稿还需要 AbortController 和 session revision？**

回答：草稿只解决编辑内容保留，不能阻止旧请求覆盖新页面。每个请求通道同时检查页面、当前请求和会话版本，
离页/退出取消请求，即使网络层晚到仍忽略旧响应。owner + 资源 + 分区隔离存储，退出清空，旧编辑器无法写入新会话。

**问题 5：绑定知识库后为何仍可能无法创建或完成 Agent 任务？启停会终止旧任务吗？**

回答：绑定成功只证明绑定接口接受 ID，不代表有 READY 文档、模型可调用或任务满足全部准入条件。
这些仍由现有后端创建快照和运行逻辑判断。任务使用创建时快照；启停只影响后续任务准入，不调用旧任务取消，
本片也没有改变执行引擎和任务生命周期。

**问题 6：本片浏览器验收能证明什么，还有什么未证明？**

回答：本次 19/19 浏览器验收验证了真实浏览器、JWT、公开 API 和 PostgreSQL 下的“页面创建 Agent → 配置绑定 → 创建任务
→ 答案/Trace”，并保留 V43/V45 回归；具体数量与证据见本文件运行记录。模型与向量边界使用受控替身，
部分状态来自夹具，不证明真实模型/Qdrant E2E、线上运行或检索质量，这些未纳入本切片。
