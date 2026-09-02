# AI Agent Java 后端学习指南

> 文档状态：**INFORMATIVE / NON-NORMATIVE**  
> 本文件只用于学习和面试复盘，不定义产品范围、Schema、模块边界或里程碑。  
> 发生冲突时，以 `spec-docs/README.md` 列出的规范性文档为准。

---

## 1. 使用方式

学习应跟随当前项目施工，而不是把所有相关技术一次学完。推荐顺序：

```text
当前切片需要什么
-> 理解对应原理
-> 完成实现和测试
-> 用 Trace/E2E 验证
-> 总结可解释的工程取舍
```

不要因为某项技术常见于“企业级架构”就提前引入。RabbitMQ、Redis、MinIO、MCP、Kubernetes 和多 Agent 只有在规范与路线图进入对应阶段后才成为学习重点。

---

## 2. Java 与 Spring Boot 基础

### 必须掌握

- Java 集合、泛型、异常、枚举、record；
- I/O、字符编码、UTF-8；
- Stream/Optional 的合理使用；
- 线程、中断、Future、CompletableFuture；
- 线程池参数、队列和拒绝策略；
- JVM 内存、类加载和常见故障定位；
- Spring Bean、依赖注入、配置属性；
- Spring MVC 请求链路；
- Bean Validation；
- 全局异常处理；
- Spring Security/JWT；
- `@Transactional`、传播、代理和锁边界；
- 测试分层：unit、slice、integration、E2E。

### 在项目中的落点

- `ApiResponse`、错误码和 traceId；
- owner-scoped service；
- TaskRunner 有界线程池；
- 外部 I/O 不持有长事务；
- 条件更新领取 task；
- 取消/完成竞态；
- provider/tool timeout。

### 需要讲清楚

- 为什么 AgentEngine 不应直接拥有 HTTP、SSE 和 task 状态；
- 为什么线程池提交要在 task 创建事务提交后；
- 为什么两个 runner 需要条件更新竞争领取；
- 为什么 Java interrupt 不等于一定能中断外部 HTTP/数据库调用；
- 为什么同类内部调用可能使 `@Transactional` 失效。

---

## 3. PostgreSQL 与数据建模

### 必须掌握

- 主键、唯一约束、外键、CHECK；
- 复合外键；
- B-tree 和 partial index；
- 事务隔离、MVCC、行锁；
- 乐观锁和条件更新；
- JSONB 的适用边界；
- Flyway migration 不可变原则；
- `EXPLAIN` 和查询计划；
- 软删除与历史数据保留。

### 在项目中的落点

- document/chunk owner-scope 复合 FK；
- Agent binding 同 owner 约束；
- AgentTask status/phase/termination CHECK；
- task 内 stepIndex 和 event sequence 唯一；
- tool/LLM/RAG log 与同一 task/step 的一致性；
- idempotency key；
-历史 Trace 不随文档/工具删除而消失。

### 需要讲清楚

- 为什么只在 Service 校验 owner 不够；
- 为什么 TaskStatus 和 TaskPhase 要拆开；
- 为什么事件不是 task 的第二个真相源；
- 为什么 Trace snapshot 不应使用危险级联删除；
- 为什么已应用 migration 不能直接修改。

---

## 4. RAG 知识库

### 必须掌握

- 文件上传和 storage key；
- UTF-8 严格解码；
- TXT/Markdown 结构解析；
- 固定长度、结构感知和语义分块的差异；
- token estimation；
- content hash；
- embedding、维度和向量空间；
- cosine similarity；
- Qdrant collection、point、payload、filter；
- topK、threshold；
- PostgreSQL/Qdrant 部分失败；
- citation 和 evidence snapshot。

### 当前项目基线

V0.1 使用：

```text
structured-token-v1
DashScope text-embedding-v4
1024 dimensions
Cosine
agentflow_chunks_te_v4_1024
```

语义分块、rerank 和 Hybrid Search 不是当前必做。

### 需要讲清楚

- 为什么 parse COMPLETED 不等于可检索；
- 为什么不同 embedding 模型不能混写一个 collection；
- 为什么 Qdrant 不是正文权威；
- deterministic UUID 如何支持幂等 upsert；
- vectorGeneration 为什么是删除 fence，而不是 point ID 的一部分；
- 为什么 hit 还要回查 PostgreSQL；
- 为什么 semantic chunking 必须通过评测证明优于 baseline。

---

## 5. Agent Runtime

### 必须掌握

- Tool Calling 的模型/后端职责；
- ReAct/Planner 的基本思想；
- 有限状态机；
- immutable execution snapshot；
- Prompt 分区；
- structured output；
- decision turn、tool call 和 Trace step 的区别；
- token budget；
- deadline/cancellation；
- duplicate tool loop；
- final generation；
- failure taxonomy。

### 当前执行链

```text
AgentTaskApplicationService
-> TaskDispatcher
-> TaskRunner
-> AgentEngine
-> RetrievalService / LlmGateway / ToolRuntime
-> ExecutionRecorder
```

模型动作：

```text
CALL_TOOL
FINISH
```

### 需要讲清楚

- 为什么 `FINISH` 不是最终用户答案；
- 为什么 final generation 不计 decision turn，但仍计 token；
- 为什么要为 final generation 预留 token；
- 为什么 runtime configuration 必须在 task 创建时冻结；
- 为什么 provider usage 缺失不能按 0；
- 为什么模型不能控制 TaskStatus。

---

## 6. ToolRuntime

### 必须掌握

- JSON Schema；
- schema canonicalization/hash；
- allowlist 路由；
- Agent binding；
- per-tool timeout；
- 参数错误与系统错误的区别；
- side effect 和 idempotency；
- retry 的安全条件；
- tool result normalization；
- result/argument 脱敏和大小限制。

### 当前工具

```text
order_query
payment_log_query
```

`report_generate` 不属于 V0.1 Agent 工具。

### 需要讲清楚

- 为什么数据库 handler 字符串不能动态执行任意 Bean；
- 为什么 ToolRuntime 校验 current ACTIVE 但参数仍按 task snapshot schema；
- 为什么 schema 变化应失败而不是静默采用；
- 为什么 maxToolCalls 和重复循环属于 AgentEngine；
- 为什么 Policy 不应重复处理存在、绑定和 schema；
- 为什么 timeout 后重试非幂等写操作可能产生重复副作用。

---

## 7. Trace 与 SSE

### 必须掌握

- task、step、专项调用日志和 event 的区别；
- append-only event sequence；
- SSE wire format；
- `Last-Event-ID`；
- 断线恢复和去重；
- snapshot + delta；
- terminal event 与数据库提交顺序；
- Prompt/response 脱敏；
- 不保存 chain-of-thought；
- exact/estimated token usage。

### 项目中的事实所有权

```text
agent_task             当前任务事实
agent_step             语义步骤顺序
llm/rag/tool logs      外部调用事实
agent_task_event       SSE 投影
Episode                后续聚合视图
```

### 需要讲清楚

- 为什么 POST 返回后再连接 SSE 会产生竞态；
- 为什么 V0.1 使用数据库事件 replay 比纯内存 emitter 更稳；
- 为什么不能逐 token 落库；
- 为什么最终答案以 task.finalAnswer 为准；
- 为什么 event 不能代替 LLM/RAG/tool log；
- 为什么不应向前端展示自由思维链。

---

## 8. 可靠性与并发

### 当前阶段重点

- 有界线程池；
- after-commit dispatch；
- conditional claim；
- idempotency key；
- task version；
- provider/tool timeout；
- cancel requested；
- stale RUNNING task 的后续恢复策略；
- Qdrant outcome unknown；
- generation-fenced delete。

### 后续再学习

- RabbitMQ ack/retry/dead letter；
- transactional outbox；
- Redis fan-out/限流；
- 多实例 worker；
- 分布式锁；
- OpenTelemetry。

先理解问题再引入组件。消息队列不能自动解决幂等、取消、外部副作用或数据库状态机。

---

## 9. 安全

### 必须掌握

- owner scope；
- authenticated resource enumeration；
- prompt injection；
- tool allowlist；
- SSRF；
- secret handling；
- path traversal；
- 文件类型/MIME/大小限制；
-日志和 Trace 脱敏；
-高风险写操作与人工确认；
-最小权限。

### 当前项目要求

- 用户输入、知识库内容和工具结果都视为不可信数据；
- Runtime Rules 由后端固定；
- 模型不能控制完整 URL、SQL、handler 或 provider credential；
- API/Trace 不返回内部异常和 secret；
- V0.1 只读工具，不伪装已实现完整审批系统。

---

## 10. 测试策略

### Unit

- parser/chunker；
- vector identity；
- decision parser；
- budget；
- schema validator；
- event reducer；
- DTO validation。

### Repository/Migration Contract

- owner-scope FK；
- status CHECK；
- task claim；
- event sequence；
- task/step log 一致性；
- Flyway 顺序。

### Integration

- PostgreSQL；
- Qdrant；
- provider HTTP stub；
- TaskRunner + Trace；
- SSE replay。

### Real E2E

- 实际 embedding provider；
- 实际 chat provider；
- 实际 Qdrant；
- 两个真实 tool handler；
- 浏览器/脚本 SSE；
- 支付失败诊断固定任务。

需要始终区分：mock contract test、真实连通性和真实任务质量。

---

## 11. 面试表达框架

建议围绕真实工程取舍，而不是堆名词：

1. **为什么模块化单体**：个人项目优先完整性，接口隔离保留演进空间；
2. **为什么自研执行循环**：模型只产生动作，预算、工具、状态和 Trace 由后端掌控；
3. **为什么 execution snapshot**：运行中配置变化不能造成混合版本；
4. **为什么状态/阶段拆分**：生命周期稳定，阶段高频变化；
5. **为什么数据库事件 replay**：避免 SSE 订阅竞态和断线丢失；
6. **为什么 PostgreSQL + Qdrant**：正文和向量索引职责分离；
7. **为什么只做两个工具**：先证明安全闭环，再扩展平台；
8. **如何处理部分失败**：短事务、稳定 ID、generation fence、补偿和明确 outcome unknown；
9. **如何防 Agent 失控**：decision/tool/token/deadline/duplicate loop；
10. **如何证明有效**：Trace、真实 E2E 和后续 Evaluation，而不是只展示 happy path。

---

## 12. 当前学习优先级

跟随 Roadmap：

```text
1. 复合外键、CHECK、幂等和条件更新
2. TaskRunner/线程池/取消/timeout
3. Agent execution snapshot 与 Prompt contract
4. Task-scoped RAG
5. ToolRuntime snapshot consistency
6. Trace 数据建模
7. SSE sequence/replay
8. Vue event reducer 和刷新恢复
9. 真实 E2E 排障
10. V0.1 后再学习 MQ、Evaluation、Policy 和 MCP
```

本指南中的任何主题都不能单独扩大项目范围。学习完成的判断标准是能解释并验证当前切片，而不是“看过更多概念”。
