# AgentFlow Hub V43 / M4G-A

Vue 3 + TypeScript + Vite 的最小任务前端。登录、已有 Agent 任务入口、任务列表/运行恢复、最终答案与引用、只读 Trace。
接口契约见 [`44_FRONTEND_TASK_RUNTIME_PACKAGE_INTERFACE.md`](../slice-docs/44_FRONTEND_TASK_RUNTIME_PACKAGE_INTERFACE.md)。

## 本地开发

需要 Node.js 22.12+ 与已配置好的 AgentFlow 后端。

```bash
cd frontend
npm ci
npm run dev
```

Vite 默认监听 `127.0.0.1:5173`，将 `/api` 代理到 `http://127.0.0.1:8080`。
其他本地后端可用 `AGENTFLOW_API_TARGET=http://127.0.0.1:18043 npm run dev`。
前端不含 provider 密钥；登录使用后端已有账号。JWT 和结果未知的创建请求只保存在当前标签页的
`sessionStorage`，退出或认证失效时清除，并终止请求、连接与重连。

```bash
npm test
npm run build
```

部署构建产物为 `frontend/dist`。静态服务需要把前端路由回退到 `index.html`，并将同源 `/api`
转发到后端；SSE 代理需要允许长连接且不缓冲事件。Vite 开发代理不属于生产部署配置。

## 真实浏览器受控验收

仓库根目录运行：

```bash
bash scripts/v43-browser-acceptance.sh
```

脚本启动全新、仅 loopback 可访问的 PostgreSQL 库和 test-source 后端夹具，再启动 Vite 和
Playwright Chromium，完成后清理进程。使用 Java 21、Maven、PostgreSQL 18 和前端 npm 依赖。
macOS 默认使用已安装的 Chrome；其他环境可先在 `frontend` 执行 `npx playwright install chromium`。
PostgreSQL 路径可通过 `PG_BIN` 指定，Java 路径可通过 `JAVA_HOME` 指定。

默认端口为前端 `5173`、后端 `18043`、PostgreSQL `55443`。已有服务占用时设置
`V43_FRONTEND_PORT`、`V43_BACKEND_PORT`、`V43_PG_PORT`。脚本输出临时证据目录，保留截图、
浏览器报告和后端日志。测试账号与固定 JWT secret 仅存在于隔离验收夹具，不能用于部署。

也可单独启动后端 `bash scripts/v43-browser-backend.sh`，按其输出启动前端；用对应
`V43_CONTROL_DIR` 运行 `npm run test:e2e`。模型继续执行由该临时目录的 `release-model` 文件控制，
没有新增公开 HTTP 测试接口。

验收使用真实 JWT、HTTP、TaskRunner、AgentEngine、快照 RAG、ToolRuntime、持久 Trace/SSE 和
PostgreSQL；模型、embedding、向量检索使用可控实现。它证明 **M4G-A**，不证明真实
provider/Qdrant E2E，也未覆盖知识库管理、Agent 编辑、多轮对话或执行重试。
