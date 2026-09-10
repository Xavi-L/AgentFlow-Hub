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
随后完成 [V49 知识库绑定上限统一](slice-docs/50_KNOWLEDGE_BINDING_LIMIT_PACKAGE_INTERFACE.md)：
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

V46 入口包含 V43/V45/V46 的 19 个受控浏览器回归；最后一条是 V48 单例诊断，
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
[V47 契约](slice-docs/48_REAL_PROVIDER_E2E_PACKAGE_INTERFACE.md)。缺少条件应记录 BLOCKED，
运行失败保留 FAILED，不用受控结果替代。修改生产执行路径后须用新任务补跑真实主路径。

## 仓库与交付

`backend/` 为服务端，`frontend/` 为页面，`scripts/` 为独立验收入口，`slice-docs/` 为各切片契约，
`spec-docs/` 为项目设计与路线图。更多页面行为及静态服务的路由/SSE 代理要求见
[前端说明](frontend/README.md)。

根 `.gitignore` 排除 `target/`、`out/`、IDE/系统文件、前端依赖/构建/报告和本地 `.env` 文件。
历史生成物已停止 Git 跟踪，本地文件保留；构建后产生这些文件不应污染 `git status`。
交付收尾及总验收不增加版本切片。13 项门槛的证据、首次失败与修正后的复验见
[总验收报告](release-docs/V0.1_RELEASE_GATE.md)；验收材料保存于 `1d062df`，annotated tag `v0.1`
已推送并固定到该提交。后续 V49 的独立证据见[验收摘要](release-docs/evidence/v49-2026-09-10.json)。
