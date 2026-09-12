# V0.2-A 受控重启验收入口

从仓库根目录执行：

```bash
bash scripts/v02a-restart-acceptance.sh
```

前置工具是 JDK 21、Maven、Python 3、Node，以及本机 PostgreSQL 的 `initdb`、`pg_ctl`、`createdb`、`psql`。默认 `PG_BIN=/Library/PostgreSQL/18/bin`；可以通过环境变量覆盖。浏览器阶段使用 `frontend/node_modules` 中已安装的 Vite 和 Playwright。脚本会执行 Maven `test-compile`，通过真实 HTTP/JWT 创建任务，随后运行 `frontend/e2e/task-restart-recovery.config.ts`。

端口默认 PostgreSQL `55462`、后端 `18062`、独立受控 HTTP 边界 `19062`、前端 `5182`。端口被占用时脚本在创建测试数据库或任务之前失败；不会停止原进程。可显式隔离：

```bash
bash scripts/v02a-restart-acceptance.sh \
  --pg-port 55463 --backend-port 18062 --external-port 19062 --frontend-port 5182 \
  --run-dir /private/tmp/agentflow-v02a-review
```

`--run-dir` 必须是全新目录。默认使用新的临时目录。数据库、稳定的同执行域 `domain.lock`、JVM 日志、控制点、调用接收计数与验收结果都保留在该目录；脚本退出时仅结束自己启动的进程和 PostgreSQL。不要提交这些本地运行材料。

`--compiled-classpath /absolute/classpath.txt` 仅用于调用者已经完成当前工作区 `mvn test-compile dependency:build-classpath -Dmdep.includeScope=test` 后复用编译；普通验收应使用默认构建。它不是跳过测试失败的选项。

## 控制边界

- `V02ATaskRecoveryFixture` 位于 `src/test/java`，不进入生产 jar，也不新增 HTTP 故障接口。
- 测试通过真实 Runner/Engine/Recorder/ToolRuntime 和 PostgreSQL；模型、embedding、vector 及所选工具 handler 使用 JVM 之外的独立 HTTP 夹具。每个接收事件先持久追加并 `fsync`，再进入允许响应的闸门。
- JVM 内部控制点具有 `.reached` 与 `.allow` 信号。主中断测试发送 `SIGKILL` 并由 `waitpid` 确认退出；重启不重新执行原任务。
- A10 使用 disposable PostgreSQL 触发器在 step、tool、task、sequence 和 event 五个写点抛数据库异常，逐表比较回滚前后。
- A11 的“COMMIT 应答丢失”是实际恢复事务提交后，由 test-only 外层代理抛出受控连接异常；不是网络物理断包或付费服务异常。
- A07 并发分支使用独立 PostgreSQL 事务保持取消写入行锁，并观察恢复事务的数据库锁等待后提交取消。
- A08、A12、A14、A16 使用明确的历史时间/不变量/历史快照夹具；不直接写入预期恢复终态。
- A17 默认从固定的前锁协议提交 `fb6a325a9d1906632ca81eee1d2eb10f86359214` 导出生产源码到独立临时目录，编译旧版真实 JVM。可用 `--legacy-ref <ref>` 显式选择其他前锁协议版本；脚本解析为实际 commit 并记录，若该版本已包含进程锁则拒绝将它当作旧版。`task-cold-cutover.py` 停止旧 JVM、确认退出并记录，再直接 `exec` 当前新版 JVM。固定默认值使 A 合并后的重跑仍使用同一旧执行协议，不会将后来的 HEAD 宣称为旧版。
- 浏览器只读既有中断任务的 GET/Trace/SSE，刷新和重连期间任务 POST 必须为零。

这些证据属于受控本地 HTTP 边界和真实 JVM/PostgreSQL 行为，不代表真实供应商执行、计费、Qdrant 服务或线上冷切换。V47 真实外部服务成功路径必须另行报告。

## 输出与失败处理

`run-result.json` 给出总状态、失败阶段、每项状态和受控边界；`matrix.json` 必须包含 A01–A17。未到达的项目写 `NOT_RUN`，不能算通过。首次失败后停止扩大执行范围，保留失败 traceback 和已有证据，不自动重试原请求。

`cases/*` 包含 task ID、恢复前后持久记录、计数或专用故障证据；`external-receipts.jsonl` 保存独立 HTTP 接收事实；`runner-receipts.jsonl` 使“误重新投递但卡在测试闸”的情况也可被发现。`processes/*` 保存启动/到达/确认退出时间，`cold-cutover.json` 保存首次升级顺序。`browser-artifacts/` 保存浏览器报告、截图与安全 JSON。`receipt-audit.json` 还将每条外部接收事实与该任务初始 JVM 的确认退出时间核对，并验证除明确被拒绝的内部 Runner 门禁探针外没有重复 Runner 入口。

运行前的 `revision.json` 中 `head` 是已提交基线；有未提交实现时不应将它误称为该实现的发布提交。正式交付需同时核对当前工作区修改或其后创建的提交。
