# AgentFlow Hub

Spring Boot + Vue 的 Agent/RAG 演示项目：上传知识文档、配置 Agent 与工具绑定，执行任务，
通过 SSE 观察进度，并回查答案、引用和 Trace。内置订单与支付日志是演示业务数据。

已交付 V48：V43/V45/V46 提供受控前端回归，V47 保留一次真实模型 + DashScope + Qdrant
成功主路径，V48 提供任务失败与浏览器观察恢复的受控矩阵。详细范围和历史证据见
[实施路线图](spec-docs/agentflow-hub-implementation-roadmap.md) 与
[浏览器验收说明](frontend/e2e/README.md)。2026-09-09 已完成
[V0.1 的 13 项 Release Gate 总验收](release-docs/V0.1_RELEASE_GATE.md)，13/13 通过；
结论限于规范的单知识库支付诊断演示。2026-09-10 已推送 annotated tag `v0.1`，
固定于 `1d062df`；范围和已知问题见[发布说明](release-docs/V0.1_RELEASE_NOTES.md)。
随后完成 [V49 知识库绑定上限统一](V0.1-slice-docs/50_KNOWLEDGE_BINDING_LIMIT_PACKAGE_INTERFACE.md)：
写入、任务快照和执行统一为 20，历史超限配置保留完整读取和删减修复；重点测试、浏览器及新真实主路径回归通过。
V49 是该 tag 之后的改动，不包含于 `v0.1`。

## 环境

以下命令从仓库根目录执行，使用 Bash 或 Zsh。

| 依赖 | 用途 |
| --- | --- |
| JDK 21、Maven | 后端编译与运行；用 `mvn -v` 确认实际使用 Java 21 |
| Node.js 22.12+、npm | 前端构建与浏览器验收 |
| PostgreSQL | 业务库和 Flyway；现有验收使用 PostgreSQL 18 |
| Redis | 已配置的基础设施及 Actuator 健康检查；当前任务执行不使用 Redis 队列 |
| Qdrant | `remote` 模式的真实向量存储；可用现有 Compose 启动 |
| OpenAI-compatible Chat、DashScope embedding | 实际模型决策、最终生成和文档向量化 |
| Python 3、curl、Chrome/Chromium | 独立浏览器验收脚本 |

macOS 可执行 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`；其他环境设置对应 JDK 路径。
受控验收会启动专用 PostgreSQL 并替换模型/向量 Gateway，不需要模型密钥或 Qdrant。

## 本地配置与依赖

保留已有本地配置；首次使用时复制模板：

```bash
test -f .env.local || cp .env.example .env.local
```

编辑 `.env.local`，填写数据库密码以及用 `openssl rand -base64 32` 生成的私有
`JWT_SECRET_BASE64`。真实演示还需填写 Chat endpoint/model/key 和 `DASHSCOPE_API_KEY`。
模板没有可用密钥，Chat 默认地址要求自行启动本地模型服务。

Spring Boot 不会自动读取 `.env.local`；在启动后端的同一终端导出：

```bash
set -a
. ./.env.local
set +a
```

模板采用 shell 赋值语法，包含空格或 shell 特殊字符的值需加单引号。
配置项对应已提交的 `backend/src/main/resources/application-dev.yml`；默认 profile 为 `dev`。
优先通过环境变量配置本机值，不必修改仓库中的 YAML。

启动本机 PostgreSQL 后，用有创建角色/数据库权限的账号准备一个专用空库。
下面假设管理员为 `postgres`，应用账号/数据库与模板默认值一致；已有专用库可跳过创建：

```bash
createuser -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U postgres --pwprompt "$POSTGRES_USER"
createdb -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U postgres --owner="$POSTGRES_USER" "$POSTGRES_DB"
```

`--pwprompt` 输入的应用角色密码应与 `.env.local` 一致。后端启动时由 Flyway 按顺序执行
`backend/src/main/resources/db/migration/`，无需手工建业务表。

真实向量化使用现有 Compose 启动 Qdrant：

```bash
docker compose up -d qdrant
```

该 Compose **仅包含 Qdrant**，监听本机 `6333/6334` 并使用命名卷；PostgreSQL、Redis 和模型服务
需要分别准备。embedding 模型、维度和 Qdrant collection 必须匹配，模板为
`text-embedding-v4 / 1024`；更换模型/维度应使用匹配的新 collection。

## V0.2-A 受控启动与首次冷切换

每个创建、领取或执行任务的 JVM 都必须配置 `AGENTFLOW_TASK_RECOVERY_LOCK_PATH`，使用同一数据库执行域共用的稳定本地绝对路径（例如 `/var/lib/agentflow/task.lock`，开发机可选自己有权限的持久目录）。不要删除/替换持锁文件，不放到每次重启新建的目录，不使用 NFS/SMB 或不同容器锁挂载。文件锁只支持同一宿主机的受控执行域，不是跨主机 fencing。

`AGENTFLOW_TASK_RECOVERY_MODE=DISABLED` 是默认值，仍须取得进程锁。发现遗留 QUEUED/RUNNING 时保持任务门禁关闭且不写恢复状态；明确确认部署前提后设为 `CONTROLLED_SINGLE_HOST`，新 JVM 才将遗留任务原子收尾为失败或取消。不会续跑、重新投递或重发模型/工具请求。

首次从不参与锁协议的旧版本升级，先确认该数据库所有执行 JVM，停止并等待它们真正退出。单个已明确识别的旧 JVM 可使用以下入口，输出保留到执行域的持久运维记录目录；有多个旧进程时必须先逐一停妥，不能只检查其中一个：

```bash
python3 scripts/task-cold-cutover.py \
  --old-pid 12345 \
  --lock-path /absolute/persistent/agentflow/task.lock \
  --record /absolute/persistent/agentflow/cold-cutover.json \
  -- "$JAVA_HOME/bin/java" -Dspring.devtools.restart.enabled=false \
  -jar backend/target/agentflow-hub-backend-0.0.1-SNAPSHOT.jar
```

将示例 PID 和路径替换为该执行域的实际值。入口发送 SIGTERM、确认 JVM 退出、记录切换时间再 exec 新 JVM；超时或旧 PID 不是 Java 进程会拒绝替换。新版本持锁直到 JVM 真正退出，关闭 Spring context 或数据库断线不会释放。升级使用完整 JVM 重启，禁用 DevTools context 热重启。

任务门禁位于创建、取消及内部领取/调度入口，关闭时写接口返回 `503/TASK_EXECUTION_NOT_READY`，无新 task/幂等键/dispatch 副作用。GET/Trace/SSE 仍按 owner 授权读取；`/actuator/health/liveness` 不能代表可接收任务，health 的 `taskExecution` 会报告 DOWN。锁/迁移失败拒绝启动，收尾/读取失败保留只读诊断，日志给出阶段和适用 task ID；修复环境或证据异常后以同一锁路径重启即可处理剩余候选，已提交终态保持不变。

Task/Trace 的 recovery 表示本地中断收尾，RUNNING 的调用结果和记录完整性未确认。已记录 tokens 可能不完整，收尾时间也不等于崩溃时间或实际执行时长。独立受控验收入口见 `scripts/v02a-restart-acceptance.sh`；结果和明确未执行项见 V0.2 切片文档，不等于整个 V0.2 已发布。

V0.2-B 使用 `AGENTFLOW_TASK_MAX_CONCURRENT_EXTERNAL_CALLS=4` 限制实际尚未退出的本地 LLM、任务检索和工具工作；调用超时或取消不会提前归还许可。依赖永久不响应时，可能需要按上述协议受控重启，不能把 Future 取消理解成供应商已停止。

终态保存只对暂时数据库错误尝试最多 3 次，退避 100ms/500ms，回读处理未知 COMMIT，保留首次 outcome、usage 和完成观察时间；不会重跑 Engine 或重发调用。耗尽或非暂时错误会以 `TASK_SETTLEMENT_PERSIST_FAILED` 记录 task/次数/阶段并关闭任务准入，写接口返回 503，任务执行健康状态降级。运维应先修复数据库并检查原 task 持久事实，必要时受控重启由 A 收尾；不要把未确认结果手工标成功或重新投递原任务。默认数据库连接获取 5s、连接 5s、socket 15s、语句 10s、锁等待 5s；部署覆盖时仍须保留有限超时。

B 的独立受控验收运行 `bash scripts/v02b-interruption-acceptance.sh`，用新目录/隔离 PostgreSQL，含 B09 实际 JVM kill/restart；细粒度许可竞态另由 Java 测试覆盖，详见 [验收入口说明](scripts/v02b-interruption-acceptance.md) 与 [切片证据](V0.2-slice-docs/01_TASK_RECOVERY_AND_INTERRUPTION_PACKAGE_INTERFACE.md#10-b-实际施工与验收记录2026-09-12)。受控 B01–B10 通过不替代 V47 真实服务回归，也不表示整个 V0.2 完成。

## 构建与启动

后端（已在当前终端导出配置）：

```bash
mvn -f backend/pom.xml -DskipTests package
java -jar backend/target/agentflow-hub-backend-0.0.1-SNAPSHOT.jar
```

另一终端检查 `curl --fail http://127.0.0.1:8080/api/v1/health`，预期 `data.status` 为 `UP`。
这是应用存活检查；不表示模型、向量服务或全部业务已经验收。

前端在另一终端启动：

```bash
npm --prefix frontend ci
npm --prefix frontend run dev
```

访问 `http://127.0.0.1:5173`。Vite 将 `/api` 代理到 `http://127.0.0.1:8080`。
后端端口改变时，用 `AGENTFLOW_API_TARGET=http://127.0.0.1:新端口 npm --prefix frontend run dev`。
上传文件默认保存在 `~/.agentflow-hub/documents`，可通过 `AGENTFLOW_DOCUMENT_STORAGE_ROOT` 修改。

首次使用通过已有注册接口创建本地账号，再在页面登录（示例密码请自行替换）：

```bash
curl --fail-with-body http://127.0.0.1:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  --data '{"username":"demo_user","password":"Change-this-local-password","displayName":"Demo"}'
```

## 演示顺序

1. 登录后进入知识库页，创建知识库，上传 TXT/MD；显式执行解析、向量化，等到服务端返回
   `retrievalReadiness=READY`。上传成功或 `COMPLETED` 本身不足以证明可检索。
2. 在 Agent 页创建 Agent，填写与已配置 Chat 服务匹配的 `openai-compatible` 模型、提示词和预算。
   展开“高级执行设置”可调整决策/最终输出上限、决策格式、思考策略和单次模型超时；留空继承部署默认。
   页面展示平台限制与最终回答预留，格式和思考选项取决于部署的模型能力白名单，详见[高级设置契约](V0.1-slice-docs/51_AGENT_ADVANCED_SETTINGS_PACKAGE_INTERFACE.md)。
   配置、知识库绑定、工具绑定分别保存，选择 READY 知识库和 `order_query`、`payment_log_query`，启用 Agent。
3. 从 Agent 详情进入运行页，提交“帮我分析 order_1024 支付失败的原因，并给出处理建议。”。
   按需要准备支付诊断文档，并要求模型结合两个工具结果和文档回答；工具查询的是库内演示数据。
4. 查看任务进度、终态、答案与引用，打开 Trace 核对 RAG/工具/独立最终生成；刷新页面核对同一任务。
   断网/SSE 重连恢复的是观察，不会自动重新执行任务。创建结果未知时按页面提示核对原请求。

需要复现已经验收的固定文档、模型参数和完整工具链时，使用下方 V47 入口及其说明；任意模型
或自选文档的运行结果不自动继承 V47 的验收结论。

## 验证入口

```bash
npm --prefix frontend test
npm --prefix frontend run build
bash scripts/v46-browser-acceptance.sh
bash scripts/v48-failure-recovery-acceptance.sh --case F01_JSON
```

V46 入口当前包含 V43/V45/V46、绑定上限与高级设置的 23 个受控浏览器回归；最后一条是 V48 单例诊断，
完整 22 例矩阵使用 `bash scripts/v48-failure-recovery-acceptance.sh`。
这些入口使用真实浏览器、JWT、PostgreSQL 与执行链路，模型/embedding/vector 及指定故障受控。
它们默认创建新的临时库和证据目录，完成后清理进程、保留证据，不使用开发业务库。

验收需要 PostgreSQL 的 `initdb/pg_ctl/createdb/psql`；`PG_BIN` 默认 `/Library/PostgreSQL/18/bin`，
其他安装位置请覆盖。macOS 默认使用已安装 Chrome，其他环境可在 `frontend` 执行
`npx playwright install chromium`。端口、证据文件及受控边界见[验收说明](frontend/e2e/README.md)。

真实 provider 验收先做只读预检，再按说明配置并运行：

```bash
bash scripts/v47-real-provider-acceptance.sh --preflight-only
bash scripts/v47-real-provider-acceptance.sh
```

完整 V47 会调用真实模型和 embedding，可能计费；具体变量、专用 Qdrant collection 和前置条件见
[V47 契约](V0.1-slice-docs/48_REAL_PROVIDER_E2E_PACKAGE_INTERFACE.md)。缺少条件应记录 BLOCKED，
运行失败保留 FAILED，不用受控结果替代。修改生产执行路径后须用新任务补跑真实主路径。

## 仓库与交付

`backend/` 为服务端，`frontend/` 为页面，`scripts/` 为独立验收入口，`V0.1-slice-docs/` 保存
V0.1 阶段及后续维护切片的契约和验收记录；目录归属不表示其中所有改动都已包含于 `v0.1` tag。
`spec-docs/` 为项目设计与路线图；V0.2、V0.3 的版本范围见[项目规格](spec-docs/agentflow-hub-project-spec.md#7-v02v03-与-v10-边界)。
后续切片目录为 `V0.2-slice-docs/`、`V0.3-slice-docs/`；首份施工契约已建立，实际实现和验收状态分别记录。
更多页面行为及静态服务的路由/SSE 代理要求见
[前端说明](frontend/README.md)。

根 `.gitignore` 排除 `target/`、`out/`、IDE/系统文件、前端依赖/构建/报告和本地 `.env` 文件。
历史生成物已停止 Git 跟踪，本地文件保留；构建后产生这些文件不应污染 `git status`。
交付收尾及总验收不增加版本切片。13 项门槛的证据、首次失败与修正后的复验见
[总验收报告](release-docs/V0.1_RELEASE_GATE.md)；验收材料保存于 `1d062df`，annotated tag `v0.1`
已推送并固定到该提交。后续 V49 的独立证据见[验收摘要](release-docs/evidence/v49-2026-09-10.json)。
