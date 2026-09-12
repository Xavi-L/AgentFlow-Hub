# V0.3 施工契约：配置版本、评测关联与最小回归基线

> 文档日期：2026-09-12。审查基线：`main@dce66e3365f45f2492d1b10d7c03118ac1564514`。
> 状态：**待实现的施工契约**，不表示版本管理、Evaluation 或 Episode 已经实现或通过验收。
> 版本范围来源：[Project Spec §7.2](../spec-docs/agentflow-hub-project-spec.md)；边界继承 [Harness Design](../spec-docs/agentflow-hub-agent-harness-design.md)。
> 顺序：先完成 [V0.2-A](../V0.2-slice-docs/01_TASK_RECOVERY_AND_INTERRUPTION_PACKAGE_INTERFACE.md) 的中断收尾，再分别施工 **V0.3-A 版本与评测关联**、**V0.3-B 最小质量回归基线**。相关中断场景依赖 V0.2-B 的验收，不把其缺口藏到评测里。

## 1. 目标与已存在的基础

第一目标是每一条评测知道“计划测什么、实际执行了什么、使用什么配置和材料、按什么规则判断、与另一轮能否比较”。质量提高是数据结论，不是本版本功能交付后的默认结论。

[AgentTaskExecutionSnapshot](../backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskExecutionSnapshot.java) 已记录 agent、runtime、chatModel、retrieval、tools 与 executionSettings；[高级设置切片](../V0.1-slice-docs/51_AGENT_ADVANCED_SETTINGS_PACKAGE_INTERFACE.md)已经使用 `agent-task-snapshot-v2` 冻结解析后的设置和来源，兼容历史 v1。

[AgentTaskSnapshotResolver](../backend/src/main/java/com/agentflow/agent/snapshot/AgentTaskSnapshotResolver.java)在短事务中解析依赖；`applicationRevision` 可回落为 `development`。这个字符串不能单独证明实际代码身份。不得把已有快照当作空白重写，也不得把历史缺失字段用今天默认补齐。

[AgentTaskApplicationService](../backend/src/main/java/com/agentflow/agent/task/service/AgentTaskApplicationService.java)已先查同 owner 的幂等请求，再执行独立创建事务。保留该顺序及并发唯一约束冲突后回读的模式；评测不获得绕过正常任务准入的特殊通道。

### 1.1 三个对象必须分开

| 对象 | 负责回答的问题 | 不负责证明 |
| --- | --- | --- |
| 不可变配置版本 | 用户选择了哪组 prompt、参数、预算、覆盖值和绑定选择 | 依赖数据永远不变、provider 永远不变 |
| 任务实际执行快照 | 这个 task 最终解析出哪些参数、工具定义和文档 generation | 崩溃后能继续执行、输出完全确定 |
| 评测运行清单与证据 | 本轮样本、数据、语料、评分规则、环境与实际 task 的关联 | 仅凭结构合法就证明回答正确 |

同一配置版本中的某项值可声明继承部署默认。因此相同 `configVersionId` 不保证不同时间创建任务的有效配置完全相同；比较必须读取实际快照与运行清单。

### 1.2 本轮选择的最小产品形态

A 实现配置版本 API、普通任务的版本关联，以及**本地 Evaluation CLI + 持久运行文件**。B 补固定标注集、指标/人工判定、报告、显式比较检查和动态 Episode 导出。

首版不建 Evaluation Web UI，不建 `eval_run/eval_case_result/agent_episode` 数据库平台。配置版本确实需要数据库持久化；评测运行采用可校验、可重入的本地清单与日志，避免为轻量基线新增调度平台。以后增加 Evaluation API 或数据库存储必须保持本文逻辑身份和指标定义，不能因此改变 task 事实所有权。

本文 A/B 完成不自动覆盖未来回归平台、自动配置搜索或质量提升承诺。

## 2. 给 Codex 的实施顺序与不变量

先核对施工 HEAD；先更新受影响的 Data Model、Backend API、Engine/Frontend 和 Harness 投影，再实现 migration、代码和测试。本文给出版本限定目标，不把新 API 写成当前已经存在。已应用 migration 不修改；按实际主线分配后续编号。

按以下顺序交付，每一步有独立可验收增量：

1. 不可变配置表示、版本发布/读取、普通 task 创建与幂等兼容。
2. A 的运行清单、逐例提交关联、未知提交核对、只读取证与无评分报告。
3. B 的固定材料、标注和规则指标、人工质量结果与覆盖率。
4. B 的显式比较条件检查、动态 Episode 导出与完整回归证据。

禁止先实现“自动找最优配置”再补数据版本；禁止 Evaluation 直接调用 Engine 私有方法、直接写 task 终态或通过修改当前 Agent 来轮流模拟历史配置。

## 3. V0.3-A：不可变配置版本

### 3.1 配置草稿与版本发布

现有 Agent 编辑接口继续操作可变草稿，保持现有字段省略/null、启停、owner 隔离和绑定保存语义。草稿编辑不修改任何已发布版本。所谓“修改配置产生新版本”，是指修改后的内容被发布或用于新任务时必须形成新的不可变记录；不是每次修改名称/描述都制造一个版本。

新增 `POST /api/v1/agents/{agentId}/config-versions`：在一个一致的数据库读取视图中捕获当前已提交草稿，校验配置并发布不可变版本。首版请求体为 `{}`；不接收任意 provider 地址、密钥或绕过 schema 的配置 blob。

显式发布返回版本及完整安全配置投影；调用者据此选择版本。不宣称连续多个现有编辑接口构成一个交互大事务。需要稳定评测时，先发布并检查所选版本，再提交任务。

按 `(owner, agent, configHash)` 去重复用同一内容版本；响应丢失后再次发布相同内容不产生不同语义版本。不同内容形成新版本；版本不可 UPDATE，不提供修改/删除版本 API。名称与可选显示标签不能作为执行身份。

### 3.2 配置内容与持久化

新增 `agent_config_version`，最小逻辑字段如下，物理类型采用项目既有 ID/时间规范：

| 字段 | 约束 |
| --- | --- |
| `id` | 不可变 ID，公开为字符串 |
| `user_id / agent_id` | owner-scoped 关联；不得引用另一 owner 的 Agent |
| `schema_version` | `agent-config-v1` |
| `config_hash` | 对规范化配置内容的 SHA-256；与 schema version 一起识别解释规则 |
| `config_json` | 完整不可变配置，不是指向可变 Agent 字段的指针 |
| `created_at` | 发布/首次捕获时间，不参与内容哈希 |

唯一约束为 `(user_id, agent_id, schema_version, config_hash)`；通过一致快照读取、必要的行锁与唯一约束处理并发发布，不以应用端“先查没有”替代数据库约束。发布过程不得包含 LLM、Qdrant 或工具 I/O。

`config_json` 必须覆盖：systemPrompt、模型选择及 temperature/topP、总决策/工具/token/时间预算、五项高级设置的原始覆盖值（包括明确继承的 null）、知识库绑定选择、工具绑定选择及现有影响执行的绑定参数。不能只给 Agent 主表加版本号而把绑定留在可变引用外。

配置版本冻结的是**配置选择**。实际文档 generation、工具当前 schema/implementationVersion、运行时规则、部署默认与能力策略由创建 task 时解析并写入实际快照；版本不归档全部外部依赖。该区别必须在 API 与报告中可见。

不保存密码、JWT、API key、连接串或带凭据地址；执行密钥仍从部署读取。旧版本不绕过当前 owner、Agent 启停、工具 hard validation 和知识 READY 准入；相关资源不可用时明确拒绝，不偷偷退回当前草稿或替换工具。

### 3.3 普通任务关联与实际快照

现有 `POST /api/v1/agents/{agentId}/tasks` 增加可选 `configVersionId`：

- 显式提供：必须属于同 owner、同 Agent，解析配置时只读该不可变配置内容；不得再从当前草稿拼接 prompt、预算或绑定。
- 省略/null：兼容旧调用，在创建事务的一致视图中捕获当前草稿，创建或复用配置版本，再生成任务实际快照。不要强制旧 UI 先调用发布接口。
- 新创建 task 都持久保存所用版本；历史 task 保持 nullable，不反向创建“当年的版本”。
- 配置捕获、依赖解析、task/原始事件写入与 after-commit dispatch 必须保持原有创建原子边界；失败不留下假成功 task 或提前执行。
- 依赖解析仍使用当前安全准入与部署策略；不同实际默认/generation/工具定义进入各自快照，不改写版本和旧 task。

新增 `agent_task.config_version_id`（历史可空，owner+agent 一致的复合外键）与 `effective_config_hash`（历史可空）。Task 详情与 Trace 增加 `configuration={configVersionId,configHash,effectiveConfigHash}` 投影；现有 `executionSnapshot` 继续保存全部实际值。

本片选择把版本关联放在 task 与独立投影中，**不为了产品名 V0.3 把 snapshotVersion 改成 3**。当前 v2 结构不变；以后真的改变快照结构时才升级格式并提供历史读取策略。读取历史 v1/v2 缺少的配置身份时显示未知/缺失，不从当前 Agent 推导。

### 3.4 哈希契约

冻结 `config-canonical-json-v1`，两种哈希都记录算法版本，不能依赖任意 JSON 序列化器的偶然输出：

- 对象键递归排序；规范化数值表示，等值的 `1/1.0` 与负零一致；拒绝非有限数值。
- 字符串保留原字符、大小写、空白与换行；不能 trim prompt/userInput，也不能对语义文本偷偷作 Unicode 归一化。
- 有语义顺序的数组保留顺序；仅对明确无序的绑定集合按稳定 ID 排序。
- null 与缺失是否等价由配置 schema 决定：先按该 schema 形成完整字段表示，再哈希，不用今天的部署默认代替 null。
- 保存规范化算法的跨 Java/CLI golden vectors，至少覆盖键顺序、数字、中文、换行、空白、null 和绑定顺序。

`configHash` 只针对不可变原始配置。`effectiveConfigHash` 针对实际快照中影响执行的值及规则/策略、模型、工具和检索依赖身份，排除 task ID、创建时间、显示标签和配置版本 ID 等观察性字段；不把相同实际值的来源标签误当成数值变化。完整实际快照和来源仍须保留。

哈希是完整性与快速检查手段，不代表模型输出相同，也不能取代字段级差异报告。跨重建数据库的材料等价性另见 §6，不直接比较雪花 ID 就认定材料不同或相同。

### 3.5 幂等指纹兼容

保留 `(owner, clientRequestId)` 唯一性以及原始 userInput 的精确字节语义。**先回读已有请求，再解析今天的配置**，否则用户只是确认旧提交也可能受到新配置/禁用状态影响。

- 未显式选择版本的请求继续使用原有 Agent ID + 原始 userInput 指纹规则，不为历史请求重算指纹。
- 显式 `configVersionId` 使用带域/版本标记的新指纹，包含 Agent ID、原始 userInput 与请求中显式版本 ID。省略与显式指定即使最终命中同版本，也视为不同请求形态；相同 key 切换形态返回冲突。
- 同 key 改 Agent、输入或显式版本：`409 / TASK_IDEMPOTENCY_CONFLICT`。
- 同一已成功创建的请求确认时，即使草稿/默认/启停状态后来变化，也返回原 task；不得重新解析后创建第二次执行。
- 发布版本的内容去重，不等于 task 幂等；不能拿 configHash 充当 clientRequestId。

新增版本 API 沿用鉴权与响应 envelope：

| 接口 | 行为 |
| --- | --- |
| `POST /agents/{agentId}/config-versions` | 新内容 201；相同内容复用 200；返回不可变版本 |
| `GET /agents/{agentId}/config-versions` | owner-scoped、分页列表，不泄露其他 owner 内容 |
| `GET /agents/{agentId}/config-versions/{configVersionId}` | owner+agent-scoped 详情 |
| `POST /agents/{agentId}/tasks` | 新建 201、确认原请求 200；新增可选版本选择 |

以上路径均以 `/api/v1` 为前缀。不存在、跨 owner 或不属于该 Agent 的版本统一按不可见资源返回 404，不泄露存在性；非法请求形态沿用 `COMMON_PARAM_INVALID`。历史版本当前不满足执行准入时返回对应既有安全错误，不自动回落。

## 4. V0.3-A：Evaluation CLI 与逐例运行关联

### 4.1 入口与存储选择

计划入口为 `python3 scripts/evaluation.py`，子命令 `run/resume/report/compare/episode`；本文件不是声称该脚本已存在。A 实现前 3 个，B 实现后 2 个和正式指标。

CLI 通过普通鉴权 HTTP task/config/Trace 接口工作，不导入执行器内部方法。连接地址和凭据从环境读取，凭据不进入命令行、运行文件、错误日志或报告。生产执行默认仍由任务入口的预算与 owner 规则控制。

每次运行写入显式指定的全新私有目录；已有目录不覆盖，`resume` 才能继续同一运行。目录至少包含：

```text
manifest.json          # 不可变计划、样本及内容哈希
journal.jsonl          # 追加式、可恢复的运行事件
artifacts/             # 按 case/任务保存的脱敏 Trace/结果证据及哈希
report.json            # 派生机器可读报告
report.md              # 派生可读报告
```

本地 journal 是评测协调记录，不是 task 执行事实源。manifest 写入采用临时文件、原子重命名和必要的落盘；发送网络请求前，对应提交意图必须持久化。journal 每行有单调序号，写入 flush/fsync；读取时允许忽略崩溃形成的不完整末行，但中间损坏必须报告，不静默拼接。单个运行目录只允许一个 CLI 写者，使用本地排他锁；report 可重复生成。

### 4.2 不可变运行清单

manifest 使用 `schemaVersion=agent-eval-run-v1`，最小内容如下：

| 维度 | 必须记录 |
| --- | --- |
| 运行身份 | evalRunId、创建时间、执行 owner 的非秘密标识、suiteMode、CLI 代码/构建标识 |
| 计划全集 | 每条 caseId、trialIndex、原始输入、预期结果/所需判定项；先登记全部样本 |
| 配置选择 | Agent ID、显式 configVersionId/configHash；评测不得默认追踪“最新草稿” |
| 数据集 | datasetId/version/contentHash；固定 case 集及标签版本 |
| 判定 | ruleSetVersion/hash、rubricVersion/hash、指标 schema/version；缺失人工判定不能默认通过 |
| 材料 | 业务 fixture 与语料 manifest 的版本、文件 hash、初始化方式 |
| 环境预期 | 构建身份、模型/provider 配置身份、RAG/工具预期、并发/顺序/随机设置 |
| 预算与观察 | 每个 task 预算、总计划上限、有限观察时限、最大网络核对次数 |
| 提交身份 | 每个 case execution 唯一且预先生成的 clientRequestId 与完整请求内容 |

运行身份与内容哈希不依赖用户可修改的显示名称。变更样本、配置、材料、规则或预期条件必须创建新 evalRunId，不能原地修改 manifest 后保留旧分数。

首版默认每个 case 一个 trial、并发 1；支持显式预先声明重复次数，不能失败后临时增加成功样本并丢掉旧结果。不同 run/trial 使用不同 clientRequestId；同一个 case execution 在响应未知时只复用原 key。

### 4.3 关联模型与状态

逻辑关系冻结为：

```text
evalRunId
  └─ caseExecutionId = (caseId, trialIndex)
       ├─ 原始提交意图 + clientRequestId
       ├─ taskId（任务创建成功时）
       └─ admissionError / submissionUnknown（尚无 taskId 时）
```

记录协调状态，不能拿任务状态替代：

| 状态 | 含义与后续动作 |
| --- | --- |
| `PLANNED` | 已登记、尚未提交 |
| `SUBMITTING` | 完整原请求已落盘，网络提交可能尚未确认 |
| `SUBMISSION_UNKNOWN` | 响应未知；不宣称任务不存在，只核对同一原请求 |
| `ADMISSION_REJECTED` | 收到明确、可归属本 case 的准入拒绝响应，无 taskId |
| `TASK_LINKED` | 已关联原 task，后续仅观察同一 task |
| `OBSERVATION_INCOMPLETE` | 观察时限耗尽/服务不可用；并不改变 task.status |
| `TASK_TERMINAL` | 已取得持久 task 终态及需要的证据 |

评测执行完成、任务终态和指标是否已判定是不同维度。CLI 观察超时不得伪造 `TASK_TIMED_OUT`，也不得默认取消任务；服务端 deadline 仍由原任务负责。

`run` 只执行预先登记的计划。`resume` 校验 manifest 哈希与运行锁，核对未知提交、继续观察已关联 task，并可提交原计划中尚未发送的 case；它不是任务断点续跑。对于 `SUBMITTING/SUBMISSION_UNKNOWN`，使用完全相同的请求/key 调用正常幂等入口核对；若原请求确实未被创建，这只创建其第一次执行。最多 3 次网络核对后仍未知则保留该状态，禁止换 key。

已经关联且失败/取消/超时的 task 不重跑。已经明确 `ADMISSION_REJECTED` 的 case 不因环境后来修好而在原 run 重试；重新实验建立新 run。对不确定的 5xx/断连不能直接判定准入拒绝，应按响应语义分类为提交未知。

### 4.4 实际执行证据不能只抄 manifest

task 创建后读取 Task/Trace，记录真正的版本关联、effectiveConfigHash、executionSnapshot、终态/原因、usage 完整性及实际调用模型信息。manifest 是预期条件，实际证据不同就记录漂移；不得把希望使用的配置写成实际已使用。

至少采集 applicationRevision，并关联可验证的 Git SHA + clean/dirty 信息或不可变构建/镜像 digest。单独的 `development`、分支名、镜像 tag 或手填显示标签不足以构成严格构建身份；可以运行和报告，但比较时列出缺失依据。

保留全部 case，包括无 taskId 的准入失败、观察未完成、v0.2 中断收尾、正常失败和最终成功。不存在“从成功 task 列表反推本轮样本总数”的实现。

总评测预算只按预先声明的上限管理。发现足够证据表明已不能继续安全执行时停止新增提交，保留剩余 `PLANNED` 并标记运行未完成；未知用量不是剩余预算充足的证据。固定阈值与退出原因写入报告，不能静默少跑后缩小分母。

### 4.5 A 验收矩阵

| ID | 场景 | 必须成立 |
| --- | --- | --- |
| A01 | 发布配置后修改 prompt/预算/五项覆盖/绑定 | 旧版本不变；新任务按所选版本，旧 task 快照不变 |
| A02 | 同配置版本、部署默认改变 | 实际快照/hash 差异可见；不能只凭版本 ID 判为相同执行 |
| A03 | 配置草稿与绑定并发更新/发布/创建 task | 捕获一致的已提交视图，无跨读取时点拼接；唯一约束防重复内容版本 |
| A04 | 版本/Agent 跨 owner、Agent 停用、知识不 READY、工具不合法 | 不越权、不静默替换、不因旧版本绕过当前安全准入 |
| A05 | 历史 v1/v2 task 与旧式幂等请求 | 缺失字段保持缺失；原 fingerprint 与原 task 不重写 |
| A06 | 同 key 换显式版本、改变请求形态、原始输入空格变化 | 明确 409；原请求确认不受后续草稿改变影响 |
| A07 | 发布/任务创建应答丢失、并发重复提交 | 版本内容去重；同 case 只有一个 task、一次执行 |
| A08 | 成功、准入失败无 taskId、进程中断三个样本 | manifest 全部保留，逐例有关联/原因，不仅列成功任务 |
| A09 | CLI 在提交意图落盘后/POST 后/写 taskId 前退出并 resume | 只用原请求核对，不换 key；已终态任务不重跑 |
| A10 | 观察超时、journal 尾行不完整、文件中间损坏、两个 CLI 同目录 | 不伪造 task 超时；有限恢复或明确拒绝；无双写 |
| A11 | applicationRevision 为 development 且无其他构建证据 | 可记录，但不能冒充严格版本身份 |
| A12 | 哈希 golden vectors、标签修改、无序绑定重排与实际 prompt 差异 | 规范化跨端一致；无关标签不影响，有意义内容变化可识别 |
| A13 | 运行文件含秘密的输入/异常夹具、越权 Trace 查询 | 凭据不落文件；owner 规则不绕过；敏感内容安全处理 |

A 可用受控 suite 证明工程关联，不要求此时给出回答质量分数或真实模型质量提升结论。

## 5. V0.3-B：评测样本与判定规则

### 5.1 数据集结构与首版规模

创建有版本的 `eval/` 文本材料，首版 `payment-diagnosis-v1` 至少 12 条有明确预期的业务案例。它是小规模工程回归基线，不是行业 benchmark。覆盖正常诊断、缺少订单号、订单不存在、证据不足、支付结果未知、知识与工具证据不一致等有区别的情况。

每例至少含 caseId、原始输入、预期 task 状态/行为、需要的工具集合/可选顺序、引用与检索标注（适用时）、答案必需事实/禁止断言、质量 rubric 引用。必要输入缺失时可以预期 FINISH 后澄清，不能逼模型编造查询参数。

确定性故障注入、非法协议、取消/超时等另用 `suiteMode=CONTROLLED_CONTRACT`；真实模型业务评测使用 `REAL_PROVIDER`。两类 suite 不汇总成同一个“真实模型成功率/回答质量”。固定数据缺少标注时标记未评估，不批量自动生成参考答案后当成人工事实。

一条样本可以预期任务失败/拒绝。任务 `FAILED` 不一定表示测试失败；反之 `COMPLETED` 不一定表示预期行为或答案正确。若预期准入拒绝，使用明确的 `expectedAdmissionError`，不要求虚构 taskId。

### 5.2 固定材料不等于只记 generation

业务 fixture、源知识文件、chunk 策略、embedding profile/维度、索引构建参数、工具实现和初始化脚本须可定位并有内容哈希。验收只能向明确的 disposable/专用评测库初始化，不允许 reset 用户开发或生产数据。

保存 `vectorGeneration` 只能指认任务当时选中的代次，不能保证代次仍可重建。manifest 必须保存材料位置、bytes/contentHash、初始化方法以及本轮 `documentId/generation/chunkId` 与稳定 evidenceKey 的映射；严禁只有不可访问的本地路径或已经删除的语料代次。

最小受控比较优先在同一固定语料代次与固定业务数据上执行两组配置，不在两组之间改数据。跨重建数据库时，物理 ID/generation 可以不同，但只有在源文件、分块、embedding/索引配置和 evidenceKey 映射可核对等价时才可作为相同控制材料；缺映射则 NOT_COMPARABLE，不能简单忽略 generation。

发现共享评测业务数据/语料在运行中变化，应停止新增提交并记录 `ENVIRONMENT_DRIFT`。已完成样本仍保留，整轮的严格比较资格失效；不能事后用最新材料补写旧 manifest。

## 6. B 指标：定义、分母与未评估

每个指标项保存 `metricVersion`、value（可为 null）、`PASS/FAIL/NOT_EVALUATED/NOT_APPLICABLE`、reason、证据引用和需要的判定来源。`NOT_EVALUATED` 不等于失败，也绝不等于通过；`NOT_APPLICABLE` 必须由样本/rubric 的适用性规则支持。

### 6.1 执行与预期行为

| 指标 | 冻结定义 |
| --- | --- |
| 实际任务分布 | 按原 task.status 统计；无 task 的样本单列准入/提交状态 |
| 预期行为匹配 | 比较样本声明的准入错误或 task 状态/原因、工具要求与必要停止/澄清行为 |
| 工具集合 | 默认 EXACT 集合；可显式 SUBSET；读取真实调用日志，不把模型提出的意图当作 handler 已执行 |
| 工具顺序 | 仅在样本声明时检查；按持久 step/调用顺序，不能依赖事件到达顺序 |
| 引用白名单 | 引用格式/ID 均存在于本 task 的合法 RAG hit 集；不代表引用支持结论 |
| 答案存在性 | 需要答案的样本检查权威 finalAnswer；要求拒绝且不应有答案的样本按预期反向判断 |

报告分母固定从 manifest 的 `N_planned` 出发，逐项列出提交、准入失败、关联、终态、未完成和被评分数量。`completedTaskRate = COMPLETED / N_planned` 明确只是运行结果比例，预期拒绝案例也可能拉低它，不命名为“质量成功率”。

`expectedBehaviorPassRate = 预期行为通过数 / N_planned`；尚未评估项保留原状态与覆盖率，不伪装成已判定失败。运行未完成时该比例只能作为进度信息，不能发布最终比较结论。

### 6.2 检索指标

首版只要求 Hit@K 和 MRR@K，不同时引入 reranker 或复杂评价库。eligible 样本必须有非空、经审核的相关 evidenceKey 集，K 与检索配置写入运行记录。

```text
Hit@K(case) = 前 K 个有效返回项中有相关证据 ? 1 : 0
MRR@K(case) = 首个相关证据的名次倒数；无命中为 0
```

使用任务实际有效命中顺序和稳定证据映射，不把 stale/owner 不匹配/被丢弃候选当命中。成功检索返回空集得 0；已记录的检索执行失败单列执行错误，并在端到端检索指标中记 0，报告不得把它解释成纯排序相关性问题。

任务未创建、未进入检索，或 Trace/映射缺失时，不伪造排名，记 NOT_EVALUATED 并单列原因。报告同时给 `N_relevanceAnnotated/N_retrievalScored/N_retrievalFailed/N_notEvaluated`；平均值分母为实际可评分的标注样本，比较时要求相同 eligible 集与完整评分覆盖，不能通过少评失败样本抬高均值。

没有相关性标注的样本不参与 Hit/MRR，指标为 null/NOT_EVALUATED；零分与缺失必须不同。

### 6.3 回答质量与人工判定

首版采用确定性规则 + 版本化人工 rubric，不引入自动 LLM judge。事实正确性、证据支持关系、处理建议合理性与不确定性表达分别判定；人工记录包含 case/task、rubric 版本、判定人标识、判定时间、结论和理由。

必须包含以下反例：

- 工具成功返回“支付结果未知”，答案却写“确认没有扣款”：工具执行指标可能通过，回答质量 FAIL。
- 引用 ID 合法，但引用片段不支持答案中的退款期限：白名单检查通过，证据支持性 FAIL。
- 缺少订单号，模型不猜测并说明需要补充：按样本预期可通过，不能因为没有调用工具就算失败。
- 预期非法参数被拒绝，任务按预期 FAILED：行为测试通过；不要求答案的质量项为 NOT_APPLICABLE。

需要答案而没有权威答案时，答案可用性 FAIL（MISSING_REQUIRED_ANSWER），不能因为缺少正文就从总报告消失。对正文尚未人工判断的事实正确性记录 NOT_EVALUATED，不自动推断通过。历史用词规则、引用合法或字数符合均不能替代语义判断。

人工修订采用追加的判定 revision，保留旧记录；最终报告记录所用 revision/hash。修订 rubric 或规则应生成新的报告判定版本，不能静默覆盖旧分数。

`qualityPassRate = PASS / (PASS + FAIL)`，分母为适用且已判定的样本；同时报告 `qualityCoverage = (PASS + FAIL) / N_qualityApplicable`。零分母时 value=null；覆盖率不足时不得给出无保留的整体质量结论，严格比较要求适用样本全部有判定。

### 6.4 用量与性能

保留 input/output/total tokens、调用次数与延迟，区分 EXACT/ESTIMATED/UNKNOWN，并继承 V0.2 的记录完整性。恢复任务的 `completedAt-startedAt` 包含停机间隔，不当作真实执行延迟；未确认总用量不参与精确成本节省百分比。

首版以原始 tokens 和已观测 latency 为主，不引入会变动的货币价格模型。缺失或估算用量分别汇总并报告覆盖率；不得把缺失填 0 后计算“更便宜”。没有足够样本时不夸大分位数或吞吐结论。

## 7. 比较条件与结论门禁

`compare` 只比较调用者明确给出的两份已有报告，不搜索配置、不执行新任务、不自动挑最高分。

### 7.1 显式声明允许变化的变量

比较清单必须声明具体 `changedPaths`（例如 systemPrompt 或某一个模型参数），其余必要条件保持一致。不能用根路径、通配符或“全部配置”绕过控制检查；版本 ID/hash 自身的变化是身份信息，不替代真实内容差异说明。

至少核对：样本/标签及 trial 计划、ruleSet/rubric/metric 版本、业务数据、语料及 evidence 映射、工具实现/schema、embedding/索引/检索设置、实际模型与有效参数、runtime/policy、构建身份，以及并发/顺序和资源限制。目标就是某一项时，该项允许变化，但所有实际变化仍要列出。

同 configVersion 但有效值不同不能被漏检；不同 configVersion 但只有显示身份不同不应自动解释为模型改进。校验以实际快照、实际材料和构建证据为准，不只比较 manifest 的声明。

### 7.2 比较状态与缺失信息

报告包含 `comparability=COMPARABLE|NOT_COMPARABLE`、`missingConditions[]`、`unexpectedDifferences[]`、`limitations[]`。

以下情况必须 NOT_COMPARABLE：必要版本/材料无法验证、只有 development 且无构建身份、出现未声明配置差异/环境漂移、不同 eligible 样本集、计划未完成、严格比较所需判定覆盖不足、关键 Trace 被删除或截断且无法评分。

NOT_COMPARABLE 仍输出两侧原始逐例结果、已观测指标和缺失原因；不输出归因性的提升/退步百分比、胜负标签或“新版本更好”的自动结论。不能为了获得一个分数自动填补历史默认、排除失败案例或降低判定要求。

COMPARABLE 只说明本地记录的比较条件满足契约，可给逐例配对差异与描述性指标差异；不等于确定性重放或统计显著。远端 provider 未公开内部模型修订时，记录别名/实际返回模型和该限制，不声称控制了不可观测的权重变更；若所声明比较必须排除此变化，而无法提供固定模型身份，仍应 NOT_COMPARABLE。

### 7.3 质量提升的证据边界

第一次完整基线不生成“提升”结论。单次分数更高最多报告“在本数据集、本轮观测中更高”；不能写“稳定提高”或因果归因。

需要更强结论时另行设计固定配对数据上的重复试验、预先声明的样本/统计方法、人工盲评或一致性检查，保留所有失败与变异。B 不自动进行显著性检验，也不把增加输出上限、引用数量或工具成功次数当作质量提升代理。

## 8. Episode：从 Trace 导出，不新建执行事实

B 冻结一个只读接口 `GET /api/v1/tasks/{taskId}/episode`，CLI `episode` 子命令负责保存 JSON；首版不增加第二个 export HTTP 路径。接口沿用 JWT/owner 与响应 envelope。

仅对终态 task 导出；非终态返回 `409 / TASK_NOT_TERMINAL`，不可见资源按既有 404。通过一致的只读数据库视图聚合 task、原始实际 snapshot、配置身份、steps、LLM/RAG/tool 记录、events、预算/完整性、权威答案/引用和已有恢复信息，不查询今天的 Agent 来补历史。

沿用 Harness 的 `schemaVersion=agent-episode-v1` 思路，增加明确的 `sourceTaskId/sourceTraceHash/exportedAt/completeness`。sourceTraceHash 对规范化的源数据投影计算，排除 exportedAt 等导出时间字段；同一稳定源事实重复导出应得到相同 hash。质量评分仍属于报告，不反向写入 task。

导出与评测取证遵守既有脱敏、正文容量及 owner 限制；不能导出供应商凭据、隐藏思考、未脱敏请求或其他用户的文档。分页/截断不能伪装为完整 Trace。内容超出冻结的导出容量时，明确 `422 / EPISODE_EXPORT_LIMIT_EXCEEDED`，或按后续独立分页契约扩展；本片不默默截断后计算完整哈希/质量分数。

中断 task 的 Episode 必须保留“本地记录已收尾但执行/用量完整性未确认”的区别。Trace 已被 retention 删除时，只能展示真实缺失/可用的历史导出，不能从当前业务数据重新拼出假的旧证据。评测应在取证后保存带 hash 的脱敏证据包；这是导出副本，不是第二套 task 状态源。

## 9. V0.3-B 验收矩阵

| ID | 场景 | 必须成立 |
| --- | --- | --- |
| B01 | 至少 12 条固定业务样本与受控故障 suite | 有标签/rubric 和材料 hash；两类证据不混为真实质量 |
| B02 | 工具成功但答案断言未扣款、引用合法但不支持结论 | 分层指标能够给出执行通过/质量失败 |
| B03 | 预期失败、预期准入拒绝、缺输入正确澄清 | 不以 task COMPLETED 作为统一通过标准 |
| B04 | 空检索、相关项位于第 1/第 K/第 K+1 位、检索执行失败 | Hit/MRR 值及失败分类正确，无 off-by-one |
| B05 | 未标注、未人工评、未创建、观察未完成与零分母 | 缺失不填 0/通过；coverage 与 planned 总数正确 |
| B06 | config ID 相同但部署默认变化；不同版本实际仅目标 prompt 变化 | 前者识别漂移；后者按声明的具体变量可比较 |
| B07 | dataset/rubric/语料/工具/构建任一未声明变化或缺失 | NOT_COMPARABLE，有原因，无自动提升结论 |
| B08 | 只保留 generation 而材料丢失；另一例材料重建且映射完整 | 前者不可比；后者能核对内容等价，不靠物理 ID 猜测 |
| B09 | 一侧少了失败样本、删去未判定样本或只展示成功 task | manifest 对账发现缺失，阻断误导性比较 |
| B10 | 中断 task 含部分已知 usage、恢复 completedAt 很晚 | 用量不归零、不假装精确；停机时间不当执行延迟 |
| B11 | 同 task 重复 Episode 导出、越权、非终态、超容量、retention 缺失 | 哈希稳定或明确差异；权限与缺失准确；无执行副作用 |
| B12 | 修改人工判断/rubric 后重出报告 | 判定 revision/hash 可追溯，旧分数不静默覆盖 |
| B13 | CLI report/compare/episode 在网关调用计数监测下运行 | 零新增模型/工具请求；只能读取与聚合 |
| B14 | 单轮分数较高、样本量小或 provider 内部版本不可观测 | 不写稳定提升/因果/显著性结论，限制明确 |
| B15 | 完整 REAL_PROVIDER 业务基线，保留全部成功与失败 | 至少 12 条计划都有终局协调记录；适用质量项完成判定并保留证据，实际分数高低不伪造 |

B15 中服务故障导致某些样本无 task 时，仍作为本轮失败事实保留；不通过重跑成功后替换原记录。修复后建立新 run。某一轮有未完成提交/观察则如实标为未完成，不能充当完整基线。

## 10. 交付与验收证据

A 交付配置版本表/关联迁移、API 与正常任务路径改造、哈希测试、CLI 运行目录协议及受控关联报告。B 交付固定样本/材料与初始化脚本、版本化规则与 rubric、机器/可读报告、比较检查器和动态 Episode 取证。

至少运行受影响 Java/真实 PostgreSQL 迁移与 owner/幂等测试、CLI 单元/文件恢复测试、前端兼容测试和构建、正常任务与历史快照回归。任务创建/执行路径修改须补跑 V47 真实成功主路径；保留 V48 失败与观察恢复回归。缺少真实 provider/环境时标注未执行，不把本地夹具当作真实业务基线。

报告与证据必须注明代码 SHA/构建、migration、样本/材料/规则版本、所选与实际配置、各 case execution 到 task 或准入错误的关联、planned/评估分母、完整性和比较资格。验收文件不得提交真实凭据、个人业务数据、隐藏思考或仅作者电脑可访问的临时路径。

| 里程碑 | 完成门槛 | 当前状态 |
| --- | --- | --- |
| V0.3-A | A01–A13；旧任务冻结、无样本漏记、无重复执行、历史兼容 | 未实现 / 未验收 |
| V0.3-B | B01–B15；指标分层、材料可核对、比较门禁及真实基线证据 | 未实现 / 未验收 |

没有已知好结果不是修改标签或删除失败样本的理由。该版本验收证明评测系统如实工作，不以虚构“相较 v0.1 提升 X%”作为完成条件。

## 11. 明确不做

不新建第二个 Agent runtime，不自动续跑或重试 task，不实现 Evaluation UI/数据库实验平台、Episode 持久化缓存、自动 A/B 编排、自动 prompt/model 搜索、LLM judge、模型训练、rerank/Hybrid Search、工具写操作或供应商账单对账。不承诺零方差输出或仅凭合法引用提高答案质量。

## 面试问题与回答

**问题 1：已经有 execution snapshot，为什么还需要配置版本？**

回答：快照记录单次任务解析出的实际值，配置版本提供可选择、不可变的配置身份。二者分工不同；A 的目标是在普通任务创建时关联两者，而不是用版本号替代快照或覆盖历史。

**问题 2：同一个版本为什么仍可能不能直接比较？**

回答：配置中的 null 可以继承不同部署默认，语料、工具和运行环境也可能变化。必须核对实际快照、材料和构建身份；只比较 configVersionId 不足以控制变量。

**问题 3：评测为什么先登记样本再创建任务？**

回答：准入失败可能没有 taskId，响应丢失也不能证明创建失败。先持久计划和原请求 key，才能保留所有失败并通过正常幂等入口核对，避免报告只统计成功任务。

**问题 4：工具成功、引用合法为什么仍可能质量不合格？**

回答：这些只证明执行和协议。支付结果未知却回答未扣款，或引用内容不支持结论，都需要质量 rubric 判失败。未人工判定的项不能默认通过；相关能力属于 B 的待验收目标。

**问题 5：Episode 导出是否能用于断点续跑？**

回答：本文 Episode 只是从 Trace 动态读取并导出的证据包，不保存完整可恢复执行状态，也不核对外部副作用。自动续跑、确定性重放均未纳入本片。

**问题 6：怎样才能说新配置质量提高？**

回答：先满足同样本、同材料、同判定规则及除目标变量外的必要条件，保留全部失败和覆盖率，才能作本轮描述性比较。一次分数更高不等于稳定提高；更强结论需要另外设计重复试验与统计/人工评审证据。
