# V0.3-A Evaluation CLI

`python3 scripts/evaluation.py` 只提供 `run`、`resume`、`report`。使用 Python 3 标准库，通过普通 JWT HTTP config/task/Trace 接口协调任务。A 不实现 compare、episode、质量评分、Evaluation UI 或数据库评测平台。

连接和鉴权只从 `EVAL_BASE_URL`、`EVAL_TOKEN` 环境读取。地址可以是服务根地址或以 `/api/v1` 结尾；禁止含 userinfo、query、fragment 的地址，不跟随 HTTP 重定向。JWT 不通过命令行传入。以下命令假设环境已由本地秘密管理方式配置。

```sh
python3 scripts/evaluation.py run --suite /private/tmp/eval-suite.json --run-dir /private/tmp/eval-run-new
python3 scripts/evaluation.py resume --run-dir /private/tmp/eval-run-new
python3 scripts/evaluation.py report --run-dir /private/tmp/eval-run-new
```

`run` 要求全新目录；已有目录不会覆盖。`resume` 继续同一不可变计划，`report` 离线重新派生报告，不需要 JWT。进程退出码：`0` 表示全部样本完成协调（任务失败或明确准入拒绝也可以完成协调）；`2` 表示仍有未知提交/观察未完成/未发送样本或用户中断；`1` 表示计划、文件、身份、锁或其他操作错误。它们不是回答质量分数。

最小 suite 示例：先通过配置版本 API 发布并检查配置，将实际 Agent/version ID 写入该文件。

```json
{
  "agentId": "123",
  "configVersionId": "456",
  "suiteMode": "CONTROLLED_CONTRACT",
  "dataset": {"datasetId": "my-controlled-contract", "version": "v1"},
  "trials": 1,
  "cases": [
    {
      "caseId": "case-1",
      "userInput": "查询订单 ORDER-001 的支付状态。",
      "expected": {"taskStatus": "COMPLETED"},
      "requiredJudgments": ["tool-evidence"]
    }
  ],
  "budget": {
    "maxPlannedTasks": 1,
    "maxTotalTokens": 60000,
    "observationTimeoutSeconds": 120,
    "requestTimeoutSeconds": 10,
    "pollIntervalSeconds": 1,
    "maxNetworkChecks": 3
  },
  "materials": {"businessFixture": null, "corpusManifest": null},
  "ruleSet": null,
  "rubric": null,
  "metricSchema": null,
  "environment": {"applicationRevision": "development"}
}
```

`suiteMode` 必须显式选择 `CONTROLLED_CONTRACT` 或 `REAL_PROVIDER`；写成后者不证明使用了真实模型，实际调用身份保存在证据中。`cases`、`trials`（1–100）、输入、预期和判定项先一次性登记；case ID 不重复，试次从 1 开始。并发固定 1，按声明顺序执行，不随机抽样。`expected` 和判定/材料对象只作为声明保留；A 不判断其通过与否，缺项保留 null/未知。可以提供 `configHash` 来校验人工选中的版本；CLI 总会读取指定版本并用同一规范化算法验证返回的完整 config 和 hash。每个任务预算来自该不可变版本的 `budgets`，不会添加私有执行入口或更改任务预算。

`dataset.contentHash` 由全部源 case 内容（含标签和预期）重新计算。可选 `environment.effectiveConfigHash` 和 `environment.applicationRevision` 会与 Task/Trace 实际证据核对并记录漂移。`environment.executionSnapshot` 支持递归对象子集核对，例如 `{"chatModel":{"provider":"CONTROLLED","model":"fixture"}}`；数组按原顺序和长度核对，缺失字段不等于 null。其他 environment key 明确记录 `EXPECTED_CONDITION_UNSUPPORTED`，停止新增提交，不静默忽视预期。材料、规则描述只保存为声明。需固定业务材料、标注或做质量比较时，另行施工 V0.3-B，不能从本轮关联记录推导质量提升。

运行目录为当前用户独占的 `0700`；文件为 `0600`。目录包含不可变 `manifest.json`、追加 `journal.jsonl`、`artifacts/`、派生 `report.json` 和 `report.md`。manifest 保存真实 `/users/me` owner ID、CLI Git SHA/dirty 和脚本内容 hash、完整样本计划、每例预生成 key、原始输入与精确请求内容。鉴权 header 只在发送时追加；manifest 中的 headers 仅有 `Idempotency-Key`。先创建空 journal，再原子发布/fsync manifest；出现完整 manifest 时已有可恢复 journal。若初始化在 manifest 发布前中断，则没有任务请求发出，残留目录不视为可恢复计划。

每次网络提交前先追加、flush、fsync 提交意图。journal 有递增序号和逐记录 hash 链；manifest、artifact 均核对内容 hash。崩溃形成的无换行末尾可以忽略；`resume` 持锁截除该尾部再追加，`report` 只报告忽略的字节数。完整中间行损坏、身份/序号/hash 不匹配会明确拒绝。`.lock` 使用本地 `flock` 排他锁，包含 report 在内的写入命令不能并发操作同一目录，崩溃由操作系统释放锁。

初次明确 4xx 业务拒绝记录为 `ADMISSION_REJECTED`，在原 run 不重试。5xx、断连、无效成功响应按 `SUBMISSION_UNKNOWN` 处理；最多核对 3 次（初始发送另计，因此最多 4 次 POST），每次发送前先持久化核对计数，所有 resume 共用该上限。核对严格重用完整原请求和原 key。已经未知的提交即使核对时得到 401、404、409，也不能证明第一次没有创建任务，继续保留未知；绝不自动换 key。核对成功只关联原 task。

`resume` 可以首次发送原计划的 `PLANNED` 样本。已经关联 task 的样本只 GET 原 Task/Trace；FAILED/CANCELLED/TIMED_OUT/COMPLETED 都不会重跑。有限观察时间届满记录 `OBSERVATION_INCOMPLETE`，不取消任务、不伪造任务 `TIMED_OUT`。Task 与 Trace 的 task ID、状态、事件水位及配置投影一致，且取得实际快照时，才记录收敛证据与持久终态。

总 token 上限和最大计划任务数先固定。每个新增提交都预留该版本的最大 task token 预算；已终态任务只有 `EXACT` 用量可用于计算剩余预算。未知提交、未完成观察、UNKNOWN/ESTIMATED 等非精确用量、配置/构建预期漂移都会停止新增提交，并保留余下 `PLANNED`。`plannedMaximumTokens` 记录全计划的最坏预算；总 token 上限小于它时，实际精确用量可能允许后续样本继续，但无法安全预留下一例时必须停止。停止原因进入报告，不缩减样本分母。

报告从 manifest 全集出发，逐例保留准入拒绝、无 task 的未知提交、已关联失败和中断、观察未完成及未发送样本，列出 `N_planned/N_submitted/N_admissionRejected/N_linked/N_terminal/N_incomplete` 和原 task 状态分布。`N_scored` 固定为 0，`qualityEvaluation=NOT_EVALUATED`。每例实际配置、effective hash、持久原因、usage、恢复事实、调用模型来自 Task/Trace；原始安全快照和结果保存在 artifact 中，不能用预期条件补写实际证据。

CLI 的 Git SHA/dirty 只能证明 CLI 本身；普通 Task/Trace 目前只提供服务端 `applicationRevision`，缺少服务端 clean/dirty 或镜像 digest 的可验证关联时，报告明确 `strictBuildIdentity.available=false`。`development`、手填 SHA、分支/镜像标签以及 CLI 本机 Git 都不会被提升为严格服务端构建身份。

为同时保留原始幂等输入并避免将凭据写入运行文件，CLI 在创建目录和 POST 之前拒绝检测到秘密的输入/预期/材料/配置；不会先改写输入再当成原实验。检测包括当前 JWT、常见凭据赋值、Bearer/JWT/API key 格式和带凭据 URL，也检测 JSON key。Trace/异常的持久化路径二次脱敏，秘密字段值替换，含秘密的 key 删除，错误不回显响应正文、连接地址或异常原文。原样、无标识的任意随机串无法自动判定为秘密，因此评测材料仍须使用无真实凭据的样本。resume 必须保持服务地址身份和实际 owner 一致；Trace 不可见时只报告未完成，不读取内部接口或数据库绕过鉴权。

验证命令：

```sh
python3 -m unittest discover -s scripts -p 'test_evaluation.py' -v
```

测试使用受控 loopback HTTP 服务、独立 CLI 子进程和真实文件 `fsync/flock`，覆盖共享 golden vectors、失败全集分母、POST 响应丢失、意图落盘后/POST 后且 taskId 写前 `SIGKILL`、恢复未发送样本、已关联失败不重跑、跨 resume 核对上限、401/404/409 不能消除提交未知、观察耗尽、尾行/中间损坏、单写者锁、预算停止、缺失严格构建身份、敏感内容与 Trace 访问拒绝。这是 CLI 工程受控证据，不是真实 PostgreSQL、V47 provider/Qdrant 或回答质量验收的替代品。
