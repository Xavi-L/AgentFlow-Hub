# V45 最小知识库管理前端接口包说明

> 路线位置：M4G-B2，接续 V44/M4G-B1 与 V43/M4G-A。
> 开工基线：`main@253e91b`，2026-09-06 已 fetch 并核对 `origin/main`。
> 本片完成只计最小知识库管理前端，不代表完整 M4G 或真实 provider/Qdrant E2E。

## 目标与冻结范围

在 V43 前端中增加 `/knowledge-bases` 与 `/knowledge-bases/:kbId`，提供当前用户知识库分页列表、
详情与创建，TXT/MD 单文件上传、显式解析与向量化，以及 V44 文档就绪状态的分页读取和详情展示。

创建表单仅填写名称和描述；profile/strategy 只读展示，无法解析或不支持时明确显示不兼容。
不得把解析完成、上传成功或轮询超时推断为 READY/FAILED；仅服务端 GET 的 READY 显示“可用于 Agent”。

## 页面与已有接口

全部复用 V43 同源 API 客户端、Bearer JWT、统一 ApiResponse、无损 JSON 解析和会话清理。
没有新增后端公开接口、迁移或自动入库调度。

| 页面操作 | 既有接口（前缀 `/api/v1`） | 输入与使用规则 |
| --- | --- | --- |
| 知识库列表 | `GET /knowledge-bases?page=N&pageSize=20` | 服务端分页、原排序与总数，无全库筛选/统计 |
| 知识库详情 | `GET /knowledge-bases/{kbId}` | 必须匹配当前路由 ID |
| 创建知识库 | `POST /knowledge-bases` | JSON 只含 `name`、`description`，服务端默认配置 |
| 单文件上传 | `POST /knowledge-bases/{kbId}/documents` | `multipart/form-data` 只有一个 `file` part，由浏览器生成 boundary |
| 解析待处理文档 | `POST /knowledge-bases/{kbId}/documents/process-pending` | 无请求体，知识库级同步批处理 |
| 向量化待处理分块 | `POST /knowledge-bases/{kbId}/chunks/vectorize-pending` | 无请求体，知识库级同步批处理 |
| 文档列表 | `GET /knowledge-bases/{kbId}/documents?page=N&pageSize=20` | 服务端分页 |
| 文档状态详情 | `GET /documents/{documentId}` | 必须同时匹配所选文档 ID 与当前知识库 ID |

创建名称 trim 后非空、最长 128 字；描述最长 4000 字。文件选择只允许 TXT/MD、一个非空文件；
服务端仍负责实际文件大小限制、存储、UTF-8 解析与业务校验，前端不猜测部署的可配置大小上限。
所有业务 ID 保留十进制字符串。generation、四项计数从原始 JSON 无损读取并规范为非负 long 字符串，
不经过不安全的 Number 转换。缺少 Readiness/计数、未知枚举或资源身份不匹配的响应拒绝展示。

## 只读配置与文档状态契约

知识库显示名称、描述、状态、provider/model、分块大小/重叠和 V44 的：

- `embeddingProfileCode`：当前支持 `dashscope-te-v4-1024-cosine`；
- `chunkStrategyVersion`：当前支持 `structured-token-v1`。

任一 null 或非当前支持值，明确提示配置不兼容且本页不能修复。禁用库明确提示不能用于 Agent。
配置兼容表示读模型映射兼容，不证明远端服务可用。页面不改变已有上传/解析/向量化接口对资源状态的准入规则。

文档列表和状态详情均直接消费：`parseStatus`、`vectorGeneration`、`vectorization.pending/processing/completed/failed`
和 `retrievalReadiness`。四个计数只描述当前 generation，页面不加入旧 generation 或自行重算 Readiness。

| Readiness | 页面文字 | 可用于 Agent |
| --- | --- | --- |
| NOT_READY | 未就绪 | 否 |
| INDEXING | 索引中 | 否 |
| READY | 已就绪 | 是 |
| DEGRADED | 部分失败 | 否 |
| FAILED | 不可用 | 否 |

`parseStatus=COMPLETED` 不等于 READY；DEGRADED 不能当作可用。一个 READY 文档仍不承诺整个 Agent
创建成功，Agent 状态、绑定、预算等要求由已有后端契约负责。

上传确认响应不包含完整 V44 读模型，页面不自行补计数/Readiness，而是刷新 GET。
批处理返回的 `discovered/claimed/completed/failed/skipped` 作为**本次操作**汇总展示，
不当作全库统计，也不用于覆盖某个文档的状态。

## 最小入库流程

1. 用户创建知识库，确认响应后进入其详情页。
2. 用户上传一个 TXT/MD 文件；当前上传只产生 PENDING，刷新 GET 不会启动解析。
3. 用户点击“解析待处理文档”，复用已有文件存储、解析和分块流水线；成功解析通常显示 INDEXING。
4. 用户点击“向量化待处理分块”，复用已有 embedding/vector Gateway；之后刷新 GET 确认状态。
5. 仅 GET 返回 READY 时显示“可用于 Agent”。真实模型/Qdrant E2E 留待后续。

页面明确提示：部署为 remote 时，既有向量化操作仍会调用对应 embedding/vector 服务。
本片的受控替身只在 test-source 浏览器夹具中生效，不切换生产部署的服务实现。

## 请求生命周期、轮询与未知结果

每个页面持有独立 request scope；列表、文档详情、写操作分别使用请求通道。新读取取消同通道旧请求，
响应落地前同时检查通道身份、页面存活和 session revision。翻页不允许旧页响应覆盖新页；
更换文档不允许旧详情覆盖新详情；离页取消请求与轮询；退出/401 清除 JWT、未知提交记录并终止请求。
取消浏览器等待不代表取消服务端同步操作。

手动刷新可开启一个新的观察窗口。仅当前页或当前所选文档为解析 PROCESSING/REPROCESSING、
或 Readiness INDEXING 时执行 GET 轮询，每 3 秒一次，最多 20 次且最多 60 秒启动窗口。
不重叠轮询；PENDING 文档不会因为上传而被自动调度。终态/不需观察、读取失败、窗口耗尽或离页停止轮询。
达到上限只提示可手动刷新，保留已读状态；网络失败也不伪造文档失败。窗口上限不是后端执行期限，
已经发出的单次 HTTP 仍受客户端 20 秒超时约束。

创建、上传与两个显式批处理均不自动重发，双击只提交一次。开始写入前将 operation label 和 owner
写入当前标签页 sessionStorage（不可用时退化为当前内存）；不保存文件内容、凭据或伪造 Idempotency-Key。
明确成功或明确业务拒绝后移除记录；超时、断网、5xx、损坏成功响应或离页时保留“结果待确认”。
刷新页面或返回页面后仍禁止再次提交，直到用户核对列表/状态并主动选择允许新的提交。
该动作只解除本地保护，不声称确认了原请求的服务端结果；同名知识库/文件名不构成幂等关联证据。

这与 V43 创建 task 不同：task 有服务端 Idempotency-Key 契约，V45 的知识库创建和上传没有该契约，
不能套用 V43 的同 key 重试逻辑。

## 实现与验收入口

- `frontend/src/lib/knowledge-api.ts`：八个现有接口、只读 DTO、身份与无损字段校验。
- `frontend/src/lib/knowledge-requests.ts`：页面请求隔离、未知写入保护、有界 GET 轮询。
- `frontend/src/pages/KnowledgeBasesPage.vue`、`KnowledgeBasePage.vue`：列表/创建、详情/三步入库。
- `frontend/src/components/KnowledgeConfiguration.vue`、`DocumentStatus.vue`：配置与五态/计数展示。
- `frontend/src/lib/knowledge.test.ts`、`frontend/e2e/knowledge.spec.ts`：边界单测与真实浏览器验收。
- `backend/src/test/java/com/agentflow/acceptance/V45BrowserFixture.java`：继承 V43 fixture，保留真实
  JWT、文件存储、解析、数据库 claims 和持久状态；仅模型/embedding/vector 边界受控。
- `scripts/v45-browser-acceptance.sh`：复用 V43 独立库启动器，在同一环境运行知识库验收和 V43 回归。

```bash
cd frontend
npm test
npm run build
cd ..
bash scripts/v45-browser-acceptance.sh
```

运行环境沿用 V43：Node 22.12+、Java 21、Maven、PostgreSQL binaries 和 Playwright Chromium/Chrome。
V45 默认前端 5175、后端 18045、PostgreSQL 55445，可通过 `V45_FRONTEND_PORT`、`V45_BACKEND_PORT`、
`V45_PG_PORT` 覆盖。`V45_CONTROL_DIR` 可指定全新临时目录。文件存储也固定在该临时目录中。
脚本只监听 loopback，结束清理前端、后端和临时 PostgreSQL；日志/浏览器截图和证据 JSON 保留在输出目录。

### 验收记录

2026-09-06 已完成本片验证：

- `npm test`：6 个文件 **32/32** 通过，其中 V45 新增 9 项边界测试，保留 V43 全部 23 项。
- `npm run build`：`vue-tsc --noEmit` 和 Vite 7.3.6 生产构建通过。
- `bash scripts/v45-browser-acceptance.sh`：真实 Chrome/Playwright **10/10** 通过，测试阶段 **36.3 秒**，
  包含 V45 7 项和 V43 3 项；不含启动/编译耗时。PostgreSQL 18.4 全新库成功应用既有 V1–V20 migration。

本次真实闭环创建知识库 `2096614055920672770`，上传 `policy.txt` 与 `refund.md`，
文档 ID 分别为 `2096614056361074690`、`2096614056801476610`。观察上传后均为 PENDING；
显式解析得到两个实际文件 chunk，文档为 INDEXING；显式向量化后均为 READY，当前 generation=0，
每份文档的四项计数为 `0/0/1/0`。文档列表与 GET detail 一致，浏览器页面仅给 READY 展示可用于 Agent。
全过程正好 5 个知识库写请求（创建、两次上传、解析、向量化），全部携带 Bearer JWT。

其余 V45 浏览器验证覆盖：知识库/文档真实分页、全部五态、旧 generation 排除、
`9007199254740993` generation 无损展示、配置不兼容与禁用；真实非法 UTF-8 文件解析失败和受控
embedding 失败；真实 201 已提交后丢弃创建/上传响应，页面 reload 后仍不重发；真实解析已提交但
浏览器等待超过 20 秒，显示结果待确认，手动刷新看到 INDEXING；浏览器时钟推进触发轮询上限，
旧页晚到响应不覆盖新页；离页实测请求 abort，退出清理、404 与真实 JWT 拒绝。

V43 回归 task `2096614184127963138`：首次创建响应丢失后原 key 返回同 task，断网/上线、页面刷新
和 Trace 往返后收敛为 15 条连续无重复事件、5 steps，最终答案/引用与 GET/Trace 一致；取消和认证清理通过。
本片还修复了登录的返回地址竞态：保存登录前目标，旧会话清理在同步替换 JWT 后仍未认证才跳转登录，
避免登录知识库深链接后被并发的登录页跳转送回任务页。上述 V43 回归证明原任务入口仍正常。

本次临时证据目录：
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v45-browser.66ahTD`。
`backend.log` 与 `postgres.log` 记录后端/数据库启动、迁移和清理；
`browser-artifacts/knowledge-real-browser-cre-db43b-licitly-vectorizes-to-READY/` 包含
`knowledge-evidence.json` 与 `knowledge-ready.png`；分页/五态截图为
`browser-artifacts/knowledge-pagination-five--758ed-configuration-use-real-GETs/readiness-states.png`。
V43 证据在 `browser-artifacts/task-runtime-real-login-un-27841-fresh-converge-to-GET-Trace/`。
已检查真实浏览器截图的配置、入库操作、文档计数和状态详情布局。脚本结束后临时 PostgreSQL 已关闭，
前后端进程已按脚本清理。临时产物不作为长期交付依赖，复现入口是仓库脚本与测试。

证据边界：TXT/MD 创建至 READY 使用真实 HTTP/JWT、文件存储/解析与 PostgreSQL，embedding/vector
upsert 为受控替身；全部五态/旧 generation 等部分来自数据库夹具。断网、延迟、晚到响应和时钟推进
为浏览器控制注入，不声称物理网络故障覆盖或真实 provider/Qdrant E2E。

## 明确不做

Agent 配置、检索调试、编辑/删除/重处理、全库筛选统计、新后端接口、迁移、自动入库调度，
以及真实模型/Qdrant E2E。保持 V43 任务运行、恢复、取消与 Trace 既有行为。

## 面试问题与回答

**问题 1：为什么上传后轮询不能使文档变成 READY？**

回答：已有上传接口只持久化原文件和 PENDING 记录，GET 没有解析或向量化副作用。必须显式调用
知识库级 process-pending 和 vectorize-pending，页面才能在后续 GET 观察到真实状态推进。
自动入库调度未纳入本切片。

**问题 2：为何 parseStatus=COMPLETED 仍不能显示“可用于 Agent”？**

回答：它只证明解析结束。当前 generation 可能还有 pending/processing/failed chunk，也可能为空、
配置不兼容或策略不一致。V44 已计算五种 Readiness，前端直接展示且仅 READY 显示可用于 Agent。

**问题 3：知识库创建或上传超时能否像 V43 创建任务一样重试？**

回答：不能复用那套策略。V43 task 有服务端幂等契约，知识库创建/上传没有，超时可能发生在提交之后。
本片保存 owner 隔离的待确认标记、禁止自动重发，让用户刷新核对后主动决定是否发起新操作。

**问题 4：AbortController 为什么还要配合响应身份检查？**

回答：取消和响应完成存在竞态，传输层也可能在取消后回传结果。只有当前页面、当前会话和该通道最新请求
都匹配才允许落地，因此晚到的旧页、旧文档或旧登录响应不能覆盖新页面。

**问题 5：为什么有界轮询停止时不标记失败？**

回答：观察窗口耗尽只说明前端停止自动读取，不证明后端失败。保留最近 GET 的事实并提示手动刷新，
POST 超时同样只表示结果待确认。取消请求也不取消后端解析/向量化执行。

**问题 6：V45 的真实浏览器验收证明了什么，哪些仍未证明？**

回答：验收入口使用真实浏览器、JWT、PostgreSQL、文件上传/存储/解析与持久向量化状态，
embedding/vector 使用受控替身；异常状态部分由数据库夹具提供，请求故障由浏览器网络控制注入。
具体已运行结果以本文件验收记录为准。真实模型/Qdrant E2E、Agent 配置及完整 M4G 仍是后续规划。
