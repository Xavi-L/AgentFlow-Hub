# V36 AgentEngine 同步最小执行循环接口包说明

> 状态：本地实现与自动化验收完成。V35 已提交并推送为 `784c6d2`；V36 在该基线上新增 M4
> 首个 AgentEngine 核心切片。JDK 21、显式 Mockito javaagent 聚焦测试与完整 Maven suite 均已
> 执行；未新增公共 HTTP API、任务表、Trace、RAG 或 SSE，也未调用真实外部 LLM provider。

## 1. 切片目标与路线位置

V36 建立同步、进程内、非持久化的最小 Agent 决策循环：加载当前 owner 的 live Agent 配置，冻结
一次执行快照，向模型发送严格分区的 Thinking Prompt，解析 JSON 动作，经现有 `ToolRuntime` 执行
零到多次受控工具调用，再通过独立 Final Answer Prompt 生成最终回答。

路线顺序以 `spec-docs/agentflow-hub-implementation-roadmap.md` 为准：

```text
Agent App CRUD -> LLM Gateway -> AgentEngine 最小循环 -> Trace -> task API -> SSE
```

因此 V36 只交付可测试的内部执行核心，不把整个 M4 一次实现。完成后只能声明：

> 后端内部已经具备可测试的同步 Agent 决策循环，能够让模型选择受控工具、接收 observation 并生成最终回答。

仍不能宣称已创建正式 Agent 任务、运行前置 RAG、保存完整 Trace、通过 SSE 输出过程或完成 V0.1
Agent 闭环。

## 2. 内部调用契约

包路径固定为：

```text
com.agentflow.agent.engine
```

主接口：

```java
public interface AgentEngine {
    AgentExecutionResult execute(AgentExecutionCommand command);
}
```

输入：

```java
record AgentExecutionCommand(
    Long userId,
    Long agentId,
    String userInput
) {}
```

V36 没有 Controller，`userId` 是以后 task/worker 或已鉴权内部调用方传入的 owner 身份，不接受任何
HTTP body 自报 owner。null、非正 owner/Agent ID 或空白输入以内部 `INVALID_COMMAND` 失败，且不访问
Agent、工具、LLM 或 `ToolRuntime`。

成功结果：

```java
record AgentExecutionResult(
    String finalAnswer,
    int stepsUsed,
    int toolCallsUsed,
    long inputTokens,
    long outputTokens,
    long totalTokens
) {}
```

结果只存在于当前调用栈；V36 不创建 task ID、step ID、conversation ID，也不保存模型调用、Prompt、
decision 或最终回答。

## 3. Owner、live、状态与执行快照

执行开始时复用 `AgentAppMapper.selectVisibleOwnedById(agentId, userId)`。该 SQL 在数据库层同时限定：

```sql
WHERE id = ?
  AND user_id = ?
  AND deleted_at IS NULL
```

缺失、跨 owner 与已软删除都只得到 null，并统一映射为内部 `AGENT_NOT_FOUND / Agent is unavailable`，
不先按 ID 查询再在 Java 中区分原因。查询故意没有 status 条件，使引擎能区分 live `DISABLED`；
`DISABLED` 在加载工具注册表、调用 LLM 或执行工具前以 `AGENT_DISABLED` 拒绝。

从持久化对象立即复制不可变 `AgentExecutionConfigSnapshot`：

```text
systemPrompt
modelProvider
modelName
temperature
topP
maxSteps
maxToolCalls
maxTokens
timeoutSeconds
status
```

每次 Thinking 和 Final Answer 调用只读取该快照。运行期间即使原 `AgentApp` 对象或数据库配置变化，
本次执行也不会混用新旧模型、采样参数、Prompt 或预算。持久化值不满足 V16/V30-V32 约束时在任何
模型或工具调用前以 `INVALID_AGENT_CONFIG` 受控失败。

`DefaultAgentEngine.execute` 不开启跨流程事务。Agent 读取和 ACTIVE 工具读取各自在短数据库边界内完成，
LLM 与 Tool I/O 不持有 Agent 配置查询事务或行锁。

## 4. 可用工具与模型可见边界

尚无 Agent 工具绑定表，因此 V36 按路线图简化为一次执行开始时加载所有 ACTIVE、未删除内置工具。
`ToolDefinitionService.listActive()` 返回的运行时定义包含 handler、config、timeout、retry、权限与输出
schema；这些对象不得直接进入 Prompt。引擎立即投影为不可变 `AgentToolSpec`：

```text
toolId
toolCode
name
description
inputSchema
```

`inputSchema` 和 observation data 均做 defensive copy。ACTIVE 但非 `BUILTIN` 的定义不进入 V36 快照；
重复 builtin tool ID/code、非 ACTIVE 定义或非法安全投影以
`TOOL_FAILURE / Tool registry is unavailable` 停止，不向模型暴露部分或歧义列表。

模型只能返回 `toolCode` 提出意图。`AgentDecisionParser` 必须把它精确解析到本次安全快照中的 tool ID；
实际执行始终调用：

```java
ToolRuntime.execute(ToolExecutionCommand.standalone(toolId, arguments))
```

现有 `ToolRuntime` 会重新确认数据库中的 ACTIVE 定义、校验 JSON Schema、经过精确 builtin allowlist 并
保存 V27–V29 的 `tool_call_log`。V36 不绕过该边界，也不把未来 task/step ID 伪造进工具日志。

成功工具结果只把以下 observation 带入后续 Prompt：

```text
toolCode
summary
data
```

失败结果、异常 cause、内部错误详情、latency 和工具配置不进入 Prompt。

## 5. Prompt 分区与严格 JSON 决策协议

每个 Thinking 请求固定为三条消息：

1. `SYSTEM`：快照中的 Agent `systemPrompt`；
2. `SYSTEM`：后端固定执行规则、严格 JSON schema、可用工具限制和数据/指令边界；
3. `USER`：一个 JSON payload，分开承载 `userInput`、五字段 `availableTools` 与 `observations`。

模型必须只返回一个完整 JSON 对象。工具调用：

```json
{
  "type": "TOOL_CALL",
  "toolCode": "order_query",
  "arguments": {
    "orderNo": "order_1024"
  },
  "reason": "需要查询订单状态"
}
```

最终回答决策：

```json
{
  "type": "FINAL_ANSWER",
  "answerDraft": "现有信息已经足以生成最终结论"
}
```

解析器拒绝：

- JSON 前后正文、Markdown fence、多个根值或语法错误；
- 重复字段、未知 type、未知字段、缺失字段或非字符串字段；
- 空白工具名、空白 reason、空白 answerDraft；
- 非对象 `arguments`；
- 不在本次安全工具快照中的 `toolCode`。

上述情况统一为无 cause 的 `INVALID_DECISION / Model decision is invalid`，异常不携带原模型响应。
V36 不启用原生 `tool_calls`，也不实现格式修复调用；非法 decision 立即失败。因此不存在被误称为 V35
Gateway 自动重试的第二次 provider 请求。

收到 `FINAL_ANSWER` 后，引擎不会直接返回 `answerDraft`。它构造独立的三消息 Final Answer Prompt：
Agent system prompt、固定最终生成规则，以及包含原用户输入、answerDraft、全部安全 observations 的 JSON
数据；第二次 `LlmGateway.chat` 的纯文本 content 才是最终结果。

## 6. 同步执行流程

```text
AgentEngine.execute(command)
  -> 校验内部 command
  -> owner + id + deleted_at IS NULL 加载 Agent
  -> 冻结 AgentExecutionConfigSnapshot
  -> DISABLED 拒绝
  -> 加载并投影 ACTIVE 安全工具快照
  -> Thinking LlmGateway.chat
  -> AgentDecisionParser
       -> TOOL_CALL
            -> 预算接受并计数
            -> ToolRuntime.execute
            -> summary/data 写入 observation
            -> 下一轮 Thinking
       -> FINAL_ANSWER
            -> Final Answer Prompt
            -> LlmGateway.chat
            -> 返回 AgentExecutionResult
```

`maxSteps` 保证循环最终终止。一次 decision LLM 调用计一个 step；最终纯文本生成调用消耗 token 与
deadline，但不再计 decision step。模型可以第一轮直接返回 `FINAL_ANSWER`，因此工具调用次数可以为零。

## 7. BudgetGuard 精确语义

`BudgetGuard` 是每次执行独立创建的内存对象：

| 预算 | V36 语义 |
| --- | --- |
| `maxSteps` | 每次 Thinking 决策调用前检查并递增；达到上限后不再调用 LLM |
| `maxToolCalls` | decision 已严格解析且 toolCode 可用后、`ToolRuntime` 前检查并递增 |
| `maxTokens` | 累加每次 provider 返回的 input/output/total usage；以 total 判断整体上限 |
| `timeoutSeconds` | 从 `execute` 开始形成整体 deadline，在每次 LLM/Tool 前后检查 |

单次输出上限不是 `agent_app.max_tokens` 的复制：

```text
Thinking:    min(512,  remaining task tokens)
FinalAnswer: min(2048, remaining task tokens)
```

若一次调用报告的 total usage 会超过任务预算，该调用结果不再被解析或返回，执行以
`TOKEN_LIMIT_EXCEEDED` 停止。若恰好消耗全部预算，后续 LLM 或 Tool I/O 都在调用前停止。

provider usage 三个字段任一未知时，V35 将其表达为完整 unknown；V36 不把它当作 0，而是立即以
`TOKEN_USAGE_UNKNOWN` 结束。本切片不做 token 预估或成本估算。

deadline 在精确时刻视为已经耗尽。V36 会在同步 LLM/Tool 返回或抛错后再次检查，所以跨过 deadline 的
结果不会继续流转；但它不能硬中断已经阻塞的调用。异步取消、线程池 Future timeout、socket 动态超时与
worker 级中断属于后续 task/worker 切片。

## 8. 稳定内部失败与脱敏

`AgentExecutionException` 只保留 `AgentFailureType` 和固定安全消息，不接受 cause：

| 类型 | 条件 |
| --- | --- |
| `INVALID_COMMAND` | 内部 owner/Agent/input 非法 |
| `AGENT_NOT_FOUND` | owner/live scoped 查询未命中 |
| `AGENT_DISABLED` | live Agent 状态为 DISABLED |
| `INVALID_AGENT_CONFIG` | 持久化执行配置不满足快照约束 |
| `INVALID_DECISION` | 模型 JSON 或动作协议非法 |
| `STEP_LIMIT_EXCEEDED` | decision 轮数已耗尽 |
| `TOOL_CALL_LIMIT_EXCEEDED` | 合法工具意图超出次数预算 |
| `TOKEN_LIMIT_EXCEEDED` | token 已耗尽或本次 usage 超预算 |
| `TOKEN_USAGE_UNKNOWN` | provider 没有可核算 usage |
| `EXECUTION_TIMEOUT` | 调用边界到达整体 deadline |
| `LLM_FAILURE` | LLM 调用失败或返回空结果 |
| `TOOL_FAILURE` | 工具注册表、执行、校验或结果契约失败 |

LLM/provider 异常、工具业务异常、Prompt、模型原响应、内部 URL/key/config 与数据库细节均不进入
`AgentExecutionException` 消息或 cause chain。V36 没有公共执行入口，因此不新增 `ErrorCode` 或修改
`GlobalExceptionHandler`。

## 9. Spring AI、schema 与实现文件边界

V36 继续复用 V35 `LlmGateway`。每次请求都显式携带快照中的 provider/model/temperature/topP 和当前
剩余预算计算出的 `maxOutputTokens`。V35 的 default/request options 仍固定
`internalToolExecutionEnabled=false`，无 tool callback/schema，且 provider 最大尝试次数仍为 1；
Spring AI 不会自动执行 V36 工具。

V36 新增：

```text
backend/src/main/java/com/agentflow/agent/engine/AgentEngine.java
backend/src/main/java/com/agentflow/agent/engine/DefaultAgentEngine.java
backend/src/main/java/com/agentflow/agent/engine/AgentExecutionCommand.java
backend/src/main/java/com/agentflow/agent/engine/AgentExecutionResult.java
backend/src/main/java/com/agentflow/agent/engine/AgentExecutionConfigSnapshot.java
backend/src/main/java/com/agentflow/agent/engine/AgentExecutionContext.java
backend/src/main/java/com/agentflow/agent/engine/AgentDecision.java
backend/src/main/java/com/agentflow/agent/engine/ToolCallDecision.java
backend/src/main/java/com/agentflow/agent/engine/FinalAnswerDecision.java
backend/src/main/java/com/agentflow/agent/engine/AgentDecisionParser.java
backend/src/main/java/com/agentflow/agent/engine/AgentPromptBuilder.java
backend/src/main/java/com/agentflow/agent/engine/BudgetGuard.java
backend/src/main/java/com/agentflow/agent/engine/AgentObservation.java
backend/src/main/java/com/agentflow/agent/engine/AgentToolSpec.java
backend/src/main/java/com/agentflow/agent/engine/AgentExecutionException.java
backend/src/main/java/com/agentflow/agent/engine/AgentFailureType.java
backend/src/test/java/com/agentflow/agent/engine/AgentDecisionParserTest.java
backend/src/test/java/com/agentflow/agent/engine/AgentPromptBuilderTest.java
backend/src/test/java/com/agentflow/agent/engine/BudgetGuardTest.java
backend/src/test/java/com/agentflow/agent/engine/DefaultAgentEngineTest.java
slice-docs/37_AGENT_ENGINE_CORE_PACKAGE_INTERFACE.md
```

没有修改 V1–V16 Flyway migration，也没有新 migration；schema version 继续为 16。没有修改 Controller、
公共 DTO、Agent HTTP 文件、ErrorCode、Spring AI 配置或既有 ToolRuntime。

## 10. 验收标准与当前证据

2026-09-02 使用 Microsoft OpenJDK 21.0.11 与显式 Mockito `-javaagent` 实际执行：

```text
V36 engine/parser/prompt/budget tests: 26/26 passed
complete backend Maven suite:          483/483 passed
Failures: 0, Errors: 0, Skipped: 0
```

聚焦测试已覆盖：

1. 非法 command 在 repository 前拒绝；owner/live scoped miss 统一失败，DISABLED 在工具注册表、LLM、Tool 前拒绝；
2. 执行期间修改原 Agent 对象不改变已冻结 model、采样参数、Prompt 与预算；
3. system prompt、固定规则、用户输入、安全工具与 observations 分区；
4. 工具 JSON 对象只有 `toolId/toolCode/name/description/inputSchema`，内部字段不进入 Prompt；
5. `order_query -> payment_log_query -> FINAL_ANSWER -> final generation` 脚本化闭环；
6. 工具 summary/data 在下一轮 Thinking 和 Final Answer Prompt 中可见；
7. 首轮 `FINAL_ANSWER` 的零工具路径；
8. 前后正文、Markdown fence、重复/未知字段、未知 type/tool、非对象 arguments 的严格拒绝；
9. 对象 arguments 确实进入 `ToolRuntime`，schema rejection 被安全映射；
10. step/tool/token/deadline 耗尽后不再调用后续外部组件；
11. 单次 512/2048 上限与剩余 task token 取最小值；
12. unknown usage 受控失败，不能伪装为零；
13. LLM/Tool 异常不携带 Prompt、响应、provider 或工具内部细节；
14. schema/observation defensive copy 与完整执行 usage 汇总。

完整 suite 包含 V27–V34 Agent/Tool 回归、V35 `LlmGateway` 与 Spring AI 禁止内部工具执行/单次请求边界、
V9 adapter 及此前全部测试；其中 V35 本地 HTTP stub 的 8 个 loopback wire-contract 用例也全部通过。
V36 新测试使用 mock `LlmGateway` 与 `ToolRuntime` 做确定性编排，证明本地同步循环与接口契约；本轮没有
配置或调用真实 provider，因此不构成真实外部服务、模型决策质量或生产端到端验收。

## 11. 明确不做

- `agent_task`、`agent_step`、`llm_call_log`、`rag_retrieval_log` 或新 Flyway migration；
- 正式任务创建、查询、取消 API 或 Agent 执行 Controller；
- 异步线程池、RabbitMQ、Future 硬超时或运行中调用中断；
- SSE、任务事件、Trace 或 decision/Prompt/answer 持久化；
- Agent 与知识库/工具绑定表；
- 前置 RAG、citation 或知识上下文注入；
- conversation 或多轮用户对话；
- Spring AI 原生 tool schema、`tool_calls` 或自动工具执行；
- decision 格式修复调用、Gateway 自动重试、fallback 或熔断；
- 公共 Agent 执行错误码或 HTTP 异常映射；
- 真实外部 provider 端到端验收。

## 面试问题与回答

### 问题 1：为什么 V36 要在开始时冻结配置，而不是每轮重新读 Agent？

**回答：** 多轮中重新读取会把一次执行拆成混合版本，例如第一轮使用旧 system prompt、第二轮使用新模型、
最后生成又使用新 token 预算，结果难以复现。V36 把十个执行相关字段复制成不可变 snapshot，所有 LLM 请求和
`BudgetGuard` 只读该对象；测试还在第一轮后修改原 `AgentApp`，证明后续请求仍使用原快照。并发配置更新只
影响下一次执行，正式 task snapshot 持久化未纳入本切片。

### 问题 2：为什么工具列表不能直接序列化 `ToolDefinition`？

**回答：** `ToolDefinition` 包含 handler、内部 config、timeout、retry、权限、确认标记和 output schema；这些
既不是模型选择工具所必需，也可能暴露实现或连接信息。V36 先投影为五字段 `AgentToolSpec`，并对 schema 做
defensive copy。模型只选 `toolCode`，执行仍由 `ToolRuntime` 重新确认 ACTIVE 定义并走 builtin allowlist。

### 问题 3：为什么 FINAL_ANSWER decision 后还要再调用一次模型？

**回答：** Thinking 响应受严格 JSON 协议约束，`answerDraft` 只表示“证据足够”和生成计划，不适合作为最终
用户文案。独立 Final Answer Prompt 能带入完整 observations 并要求只输出最终文本。该调用不再计 decision
step，但仍消耗 token、受剩余 output cap 与整体 deadline 约束。

### 问题 4：unknown usage 为什么必须失败，而不能用 maxOutputTokens 或 0 估算？

**回答：** `maxOutputTokens` 只限制输出，不包含输入 Prompt token；0 又会把“provider 未报告”伪造成“真实
零消耗”。继续循环将无法证明没有超过任务 `maxTokens`。V36 因此在任一次 LLM 调用 usage unknown 时立即以
`TOKEN_USAGE_UNKNOWN` 停止。token estimator 或 provider-specific fallback 未纳入本切片。

### 问题 5：V36 的 timeout 能保证阻塞调用在 deadline 时立即终止吗？

**回答：** 不能。V36 是同步内核，只在每次 LLM/Tool 调用前后检查整体 deadline；跨过 deadline 的结果不会
再被解析或进入下一步，但正在阻塞的线程不会被 `BudgetGuard` 中断。Future 取消、线程池隔离和 worker 级硬
超时属于后续 task/worker 切片，不能把步骤边界检查描述为硬取消。

### 问题 6：为什么 V36 不使用 Spring AI 原生 tool calling？

**回答：** 当前规格允许先使用严格 JSON decision，而且 V35 已把 default/request
`internalToolExecutionEnabled` 固定为 false、未注册 callback/schema。这样每个工具意图必须先经过自研解析、
可用列表映射、次数/token/deadline 检查，再显式调用现有 `ToolRuntime`。本切片可以证明后端掌控执行循环，
但尚未证明真实模型稳定遵守 JSON 协议或完成生产 Agent 闭环。
