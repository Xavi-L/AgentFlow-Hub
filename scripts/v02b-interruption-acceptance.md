# V0.2-B 受控中断与终态保存验收

运行 `bash scripts/v02b-interruption-acceptance.sh`。需要 Java 21、Maven、本机 PostgreSQL binaries
（默认 `/Library/PostgreSQL/18/bin`）。默认端口为 PostgreSQL `55463`、应用 `18063`、
独立外部 HTTP 夹具 `19063`；可通过同名 `--pg-port`、`--backend-port`、`--external-port` 参数覆盖。
每次必须使用新的目录；失败后保留目录，不复用旧数据库。

如本轮已经集中编译，可传 `--compiled-classpath /absolute/path/classpath.txt`；此文件由
`mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=...`
生成，且 `backend/target/classes` 与 `test-classes` 必须对应当前工作树。

应用通过 test-source `V02BInterruptionFixture` 启动，继承 A 的 JWT/真实任务入口、种子数据、
Runner receipt 与恢复门闸。外部模型、embedding/vector 和工具响应由独立 Python HTTP
服务器控制；gateway 实际工作体忽略中断，直到独立 HTTP 请求真正返回才退出。
该夹具证明本地迟到结果与许可边界，不证明真实供应商取消、执行、计费或可用性。

本进程矩阵覆盖：

- B01：LLM、工具、embedding 和 vector 忽略中断，取消收尾后再释放；持久结果与调用次数不变。
- B02：许可设为 1，旧工作取消后仍占用许可；后续等待取消/超时不会新开外部工作；退出后许可可复用。
- B03：可用模型 usage 在正常返回边界遇持久取消时保留。获得许可前/获得后未进入体的细粒度竞态、
  重复释放与嵌套单许可由 `TaskExternalCallDeadlineTest` 提供独立受控调度证据，不能只用本脚本代替。
- B04：单次模型超时与整体 task deadline 的终态/错误码区分，迟到返回不改变终态。
- B05：真实 PostgreSQL trigger 注入 `40001`；首次终态写回滚、第二次成功；首次观察时间不变。
- B06：事务 COMMIT 返回后丢弃应答；以及终态事件插入故障导致答案、task、事件完整回滚后重试。
  COMMIT 丢应答场景等待 `TaskSettlementService.settle` 的真实返回信号后再核对事务次数。
- B07：第一次终态写失败后的第二次入口门闸内跨越 deadline、接收取消、或让其他终态先提交。
- B08：暂时故障连续失败 3 次；非暂时 `23514` 只尝试 1 次；准入关闭，实际 Spring
  `taskExecutionHealthIndicator.health()` 为 `DOWN`，数据库仍非终态，重启按 A 收尾。
  每个变体另有已提交且在 Runner 领取前受控阻塞的 QUEUED 任务，门禁关闭后放行并验证
  原拒绝路径持久化 `TASK_DISPATCH_REJECTED`，无 startedAt 或外部调用。
- B09：第二次终态保存入口的到达信号之后，实际 `SIGKILL` JVM 并 `waitpid` 确认退出，再启动新 JVM。
  已持久成功 LLM 日志保留，丢失的内存 outcome 不恢复成成功，A 收尾为中断失败/UNKNOWN，无重新执行或重发。
- B10：工具 handler 在迟到返回后实际尝试 SUCCESS/step 更新，另有显式 mapper 迟到写，均不能覆盖
  既有终态。一个有界背景观察者跨越 handler 阻塞、开始放行、实际迟到写结束三个阶段，各读取
  一轮 GET/Trace/SSE；三轮结果全部一致，每阶段等待最多 15 秒，不使用无界高频轮询。

PostgreSQL `SEQUENCE` 只用于持久故障注入次数（事务回滚不归还 `nextval`）；脚本不会直接写 task
目标终态。B07 的竞争终态使用原生命周期服务测试命令。B09 使用 A 的实际恢复实现，不能用异常或
Spring context 重建替代 kill。故障接口仅存在 test source 和本地控制目录，不添加生产 HTTP 路由。

结果保存在输出目录的 `run-result.json`、`matrix.json`、`cases/`、`processes/`、`b-attempts/`、
`external-receipts.jsonl`、`schema.json` 与 `revision.json`。脚本在第一个未解决失败处停止；后续条目标
`NOT_RUN`，不会补造 PASS。结束时另生成 `receipt-audit.json`，逐个核对 Runner 只进入一次、所有
外部接收记录在其原始 JVM 已确认退出之前；不以配置声明代替零重发证据。本脚本与相关 Java 竞态测试、真实 PG 测试、A/V48/前端回归一起构成 B
受控证据；V47 真实服务证据需单独执行/报告，缺凭据时为 `BLOCKED`。
