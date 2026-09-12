# Agent 高级执行设置

## 目标与范围

让用户在 Agent 创建/配置页调整单次决策输出、最终回答输出、决策格式、模型思考策略和单次模型超时，
同时看见部署默认、平台允许范围及最终回答预留。配置贯穿公开 API、PostgreSQL、任务快照、模型请求和 Trace。

已有 `maxTokens` 始终表示任务累计输入及输出总预算；`timeoutSeconds` 表示任务从开始执行起的总时限。
高级配置不改变知识 READY 准入、owner 隔离、工具协议、独立最终生成、取消、幂等与未知写回读规则。

## 公开接口与字段

已有 `POST /api/v1/agents`、`PATCH /api/v1/agents/{agentId}` 及详情/创建/修改/启停响应新增五个可空字段：

| 字段 | 类型和语义 |
| --- | --- |
| `decisionMaxOutputTokens` | 整数，单次决策输出申请上限 |
| `finalMaxOutputTokens` | 整数，最终回答输出申请上限，自定义值不得低于最终预留 |
| `decisionResponseFormat` | `PROMPT_ONLY / JSON_OBJECT / JSON_SCHEMA` |
| `thinkingMode` | `PROVIDER_DEFAULT / DISABLED` |
| `modelCallTimeoutSeconds` | 整数，单次模型调用等待时限，单位秒 |

创建时省略或传 `null` 继承部署默认。PATCH 省略保留原值，显式 `null` 清除覆盖；响应明确返回 `null`。
未知字段、错误类型、无效枚举、超平台范围、不兼容模型选项在写入前返回 `COMMON_PARAM_INVALID`。
改变总预算或模型名称时也重新校验合并后的完整设置；停用、软删除和 owner 可见性遵循原契约。

新增鉴权读接口：`GET /api/v1/agents/execution-options?modelName=...`。
响应包含 `policyVersion=agent-execution-policy-v1`、规范化 `modelName`，以及：

- `defaults`：上述五个字段对应的部署默认，其中最终输出可空，表示自动使用最终预留；格式/思考默认已按当前模型能力处理。
- `limits`：`maxOutputTokens`、`maxModelCallTimeoutSeconds`。
- `capabilities`：`jsonObject`、`jsonSchema`、`disableThinking`。

此接口只声明部署允许的选项，不发起模型探测，不证明该模型当前可调用。
前端在模型名改变时立即作废旧选项，隔离迟到响应；读取失败保留草稿、提供重试，并阻止未确认策略的提交。
既有不兼容覆盖值保持可见，必须明确修改后保存，不会静默清除。

## 默认值、上限与兼容性

`agentflow.task.execution.*` 保留执行默认：决策输出默认 512，最终输出默认空，JSON/禁用思考默认关闭。
模型连接默认超时来自 `agentflow.llm.timeout`（当前默认 30 秒）；公开的秒值向上取整。

`agentflow.agent.execution-policy` 定义独立的平台限制和精确模型名白名单：

| 环境变量 | 默认值/作用 |
| --- | --- |
| `AGENTFLOW_AGENT_MAX_OUTPUT_TOKENS` | 16384；平台可设 2048–16384，兼容所有合法任务的最终预留 |
| `AGENTFLOW_AGENT_MAX_MODEL_CALL_TIMEOUT_SECONDS` | 600；平台允许单次模型超时的最大秒数 |
| `AGENTFLOW_AGENT_JSON_OBJECT_MODELS` | 空；经过部署方验证支持 JSON 模式的模型名列表 |
| `AGENTFLOW_AGENT_JSON_SCHEMA_MODELS` | 空；经过部署方验证支持本项目 Schema 的模型名列表 |
| `AGENTFLOW_AGENT_THINKING_DISABLED_MODELS` | 空；验证接受 `thinking.type=disabled` 的模型名列表 |

列表按精确模型名匹配，可使用逗号分隔。仅有 `openai-compatible` 标签不足以证明格式/思考选项兼容。
不兼容的继承默认回落到 `PROMPT_ONLY / PROVIDER_DEFAULT`；显式不兼容覆盖会被拒绝。
`PROVIDER_DEFAULT` 省略思考参数，不代表强制开启。两种 JSON 模式互斥，严格决策解析始终执行。
生产默认白名单为空；受控测试中的白名单仅证明测试协议，不是对真实模型的支持声明。
既有 V47 真实服务验收入口的预检也要求：启用 JSON/关闭思考默认时，当前模型必须在对应白名单，
避免先通过预检、实际调用却静默采用另一种模式。脚本不会自动授予模型能力。

最终预留为 `min(2048, max(1, floor(maxTokens / 4)))`。例如总预算 8000 时预留 2000：
自定义最终输出不得小于 2000；继承时取预留与部署最终默认值的较大者。
每轮实际申请额度还取配置额度、扣除本轮输入及必要预留后的剩余总预算、上下文余量的最小值。
配置上限不是每轮必得额度，也不承诺增大后任务一定成功。

## 持久化、快照与执行

新增迁移 `V21__add_agent_execution_settings.sql`，给 `agent_app` 添加五个可空列及 CHECK。
旧迁移保持不变；历史 Agent 自动继承默认；不修改既有历史任务内容。

新任务使用 `agent-task-snapshot-v2`，追加 `executionSettings`，固定解析后的五项值、策略版本与 `sources`。
来源为 `AGENT_OVERRIDE / DEPLOYMENT_DEFAULT / DEPLOYMENT_DEFAULT_FALLBACK / FINAL_RESERVE`。
修改 Agent 或后续部署默认不改变已经生成的 v2 快照；执行器与公开 Trace 使用该快照。

v2 缺失或包含非法设置在外部 I/O 前拒绝。历史 v1 快照仍可读，保留原运行时默认的兼容执行路径，
不伪造当时未记录的高级设置。Trace 中 v1 没有 `executionSettings`，不得当作已冻结这些参数的证据。

显式模型超时通过独立同步 HTTP 客户端配置生效，不并发修改共享客户端。
外层等待同时受单次时限、任务剩余时间和取消状态约束；I/O 中断是协作式的，不保证供应商停止计算。
底层只有一次请求尝试，没有自动模型重试。受控 `ChatModel`/`LlmGateway` 覆盖仍可用于测试，不能绕过它调用真实服务。

## 失败与可观测性

- `AGENT_LLM_OUTPUT_LIMIT`：供应商 `finish_reason=length`，包括空正文或已有但被截断的正文。
- `AGENT_LLM_EMPTY_RESPONSE`：非长度截断情况下，模型没有可用正文。
- `AGENT_LLM_TIMEOUT`：单次模型等待/传输超时；任务整体期限到达仍为 `TASK_TIMED_OUT`。
- `AGENT_LLM_REJECTED`：供应商拒绝请求。
- 决策协议错误仍为 `AGENT_INVALID_DECISION`；其他传输/响应异常保留已有安全兜底错误码。

失败日志保留可用的安全元数据：usage、finishReason、模型/请求 ID 和耗时；有实际 usage 时按实际计账，
没有时按请求预算保守估算。失败正文、隐藏思考、凭据和服务地址不进入 Trace。
公开 LLM 请求 Trace 额外允许 `timeoutSeconds`；最终回答仍不发送决策 JSON 格式约束。

## 实现与验收

重点验证公开字段类型/null、owner 边界、合并校验、最终预留、兼容白名单、模型切换迟到响应、
旧草稿与未知写回读；执行侧验证 v2 JSON 往返及配置冻结、v1 兼容、长度/空正文故障元数据、
HTTP 并发超时隔离和单次/任务超时的区分。

浏览器验收复用隔离 PostgreSQL、真实 JWT/API、任务执行与 Trace，模型及向量边界受控。
2026-09-11 验证结果：

- JDK 21 执行 `mvn -f backend/pom.xml test`：789 项，失败/错误均为 0，其中 46 项需专用环境的测试按既有配置跳过。
- `npm --prefix frontend test`：91/91；`npm --prefix frontend run build` 通过。
- `python3 -B -m unittest discover -s scripts -p 'test_v47_preflight.py' -v`：3/3，离线验证默认兼容、精确模型匹配及不同能力之间不串用白名单。
- `bash scripts/v46-browser-acceptance.sh`：全量 23/23，耗时 56.6 秒；新的空 PostgreSQL 成功应用 V1–V21。
- 新增三例覆盖五项覆盖值保存/刷新/显式清空、PATCH 响应丢失后的只读核对、平台与最终预留校验、
  模型能力切换/迟到响应隔离，以及创建任务后修改 Agent 不影响冻结配置。
- 任务 `2098429803387363330` 的决策请求仍为 2048 tokens、最终请求为 4096 tokens，超时均为 120 秒；
  此时 Agent 已改为 512/2048 tokens 和 2 秒，模型调用与公开 Trace 均保持原值。

本地证据目录为 `/tmp/agentflow-advanced-browser-20260911-r2`，包含 `backend.log`、`browser-artifacts`、
`advanced-llm-requests`；任务用例输出 `advanced-settings-evidence.json` 及截图。
首轮发现策略仍在读取时提交按钮可点击的交互回归，已改为加载期间禁用提交，并补充创建/编辑页面回归测试；
新增用例的登录等待与中文提示断言也已修复。最终 23/23 结果来自修复后新建的独立数据库。
本切片未执行真实 Gemma/LM Studio 或付费 provider 验收。

部署时重启后端，由 Flyway 应用 V21；刷新前端后从 Agent 创建/详情页展开“高级执行设置”。
已有 Agent 的五字段自动继承默认，已有任务保持原快照。

### 2026-09-12 缺少查询参数的决策修正

用户提供的任务 `2098649389438439425` 已成功检索到 1 个有效片段，但没有订单号或支付错误码。
模型以 `finish_reason=stop` 返回普通文本澄清，严格解析器因此报 `AGENT_INVALID_DECISION`，工具未被调用。
这条日志证明的是决策格式违规；没有输出截断或检索失败的证据。

新任务记录 `promptRulesVersion=agent-runtime-rules-v2`：明确 `FINISH` 只结束工具规划，不代表已解决用户问题；
必要参数无法从输入、证据、观察或合法工具调用获得时，用 `FINISH.answerPlan` 指明缺失信息，
由独立最终生成说明限制并请求补充。不猜测标识符、不凭无关知识示例填参，不新增 WAIT 状态或多轮等待。
已创建任务保留 v1 原提示规则，JSON 决策协议仍为 `agent-decision-json-v1`。

回归测试覆盖新旧提示规则下的零工具澄清收尾，以及此次普通文本/stop 响应仍失败且不重试、保留 2838 tokens 用量。
JDK 21 配合 Mockito `-javaagent` 验证：执行器、解析器与快照解析器的 49 项重点测试通过；
全量 Maven 792 项，失败/错误 0，46 项专用环境测试跳过。日志位于
`/tmp/agentflow-missing-input-focused-r2.log` 和 `/tmp/agentflow-missing-input-tests.log`。
这些测试使用受控 Gateway，不能证明 Gemma 在新提示下必然服从协议；未重放用户失败任务或发起真实模型调用。

## 明确不做

不新增管理员平台、不从前端写 application 文件，不开放数据库、Redis、密钥、服务地址、JWT、
线程池、SSE、日志/Trace 容量、存储路径、embedding 模型/维度或 Qdrant collection。
不新增模型自动探测、任意 provider、协议容错解析、自动重试、任务重放或多轮对话。

## 面试问题与回答

**问题 1：为什么总 token 预算已经可调，还要单次输出额度？**

回答：总预算累计所有模型请求的输入和输出；单次额度限制一轮生成的消耗。两者共同约束，每轮还要给最终回答留预算。
只修改总预算不会提高默认的单次 512 上限。

**问题 2：为什么配置需要进入任务快照？**

回答：用户修改 Agent 或运维修改默认值后，已创建任务应继续使用自己的参数。v2 在创建时解析并冻结有效值和来源；
历史 v1 没有该信息，继续读原快照并明确保留兼容边界，不用现在的值冒充历史证据。

**问题 3：为什么不能把两种 JSON 格式和思考策略直接作为通用开关？**

回答：格式互斥，且不同 OpenAI-compatible 服务不保证接受同一扩展参数。按精确模型名验证能力后开放，
`PROVIDER_DEFAULT` 只表示不发送思考参数；严格解析器仍拒绝不合约的输出。

**问题 4：为什么单次超时需要改 HTTP 调用链？**

回答：原客户端在构建时固定连接/读取超时。只增加表单或外层等待不会延长底层 30 秒限制；
显式请求创建隔离客户端，并结合任务剩余期限限制等待，避免并发任务相互修改超时。

**问题 5：为什么遇到长度限制要保留 usage，同时不保存正文？**

回答：请求可能已经消耗 tokens，实际返回的 usage 应进入预算和 Trace；截断正文不等于有效决策，
隐藏思考也不是正式输出。仅保留安全故障元数据，并给用户展示可操作的错误码和建议。

**问题 6：缺少订单号时，需要增加新的决策动作吗？**

回答：本次修正复用 `FINISH`，将缺失参数写入 `answerPlan`，再由最终生成给出补充信息提示。
任务完成只表示这一轮回答已生成，不代表查明了订单故障，也不进入等待或自动续跑状态。
模型仍须返回合法 JSON；提示规则改进不等于真实模型遵守协议的保证。
