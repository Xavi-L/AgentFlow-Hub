# AI Agent 应用开发学习知识图谱

适用背景：北航计算机大四，Java 基础较熟，有一段 AI 大模型/CV 实习经历，目标是在年底前争取深圳大厂开发类实习，方向优先为 AI 应用开发、Agent 应用开发、后端开发。

核心定位：

> 把自己塑造成一个懂大模型应用的 Java 后端开发，而不是只会调 API 的 AI demo 开发者，也不是偏模型训练但工程经验不足的算法候选人。

推荐项目主线：

> AgentFlow Hub：面向企业知识库与业务流程的 Java Agent 应用平台。

这个项目应同时体现三类能力：

- Java 后端工程能力：接口、数据库、缓存、异步任务、高并发、可观测性。
- AI 应用工程能力：RAG、Tool Calling、Agent 状态机、流式输出、评测。
- 项目表达能力：能讲清楚业务场景、架构设计、性能问题、失败处理、线上可维护性。

---

## 1. 知识总览

| 层级 | 主题 | 作用 | 项目中的体现 |
| --- | --- | --- | --- |
| 第一层 | Java 后端基本盘 | 证明你是靠谱的开发候选人 | 用户、知识库、任务、工具、权限等后端模块 |
| 第二层 | 数据库与缓存 | 支撑业务数据、状态管理、性能优化 | PostgreSQL/MySQL、Redis、索引、事务、缓存 |
| 第三层 | 高并发与工程化 | 对齐大厂开发岗位要求 | 限流、异步任务、消息队列、重试、幂等、SSE |
| 第四层 | RAG 知识库 | AI 应用最常见核心能力 | 文档解析、切分、向量检索、重排、引用溯源 |
| 第五层 | Agent 与工具调用 | 形成差异化亮点 | Planner、工具注册、权限、执行轨迹、状态机 |
| 第六层 | LLMOps 与评测 | 体现工程化和线上意识 | token 成本、延迟、trace、反馈、评测集 |

---

## 2. Java 后端基本盘

### 2.1 必学知识

- Java 基础
  - 集合框架：ArrayList、LinkedList、HashMap、ConcurrentHashMap。
  - 泛型、异常、反射、注解、枚举。
  - Stream、Optional、Lambda。
  - I/O、NIO 基础。

- JVM
  - JVM 内存结构：堆、栈、方法区、程序计数器。
  - 对象创建过程。
  - GC 基础：可达性分析、Minor GC、Full GC、常见垃圾收集器。
  - 类加载机制：加载、验证、准备、解析、初始化。
  - 常见排查：内存溢出、CPU 飙高、线程阻塞。

- Java 并发
  - 线程生命周期。
  - synchronized、ReentrantLock。
  - volatile、happens-before、Java 内存模型。
  - AQS 基础。
  - 线程池参数：corePoolSize、maximumPoolSize、queue、rejectionPolicy。
  - CompletableFuture。
  - ThreadLocal。
  - 并发容器。

- Spring Boot
  - Controller、Service、Repository 分层。
  - Spring MVC 请求流程。
  - Bean 生命周期。
  - 依赖注入。
  - AOP。
  - 事务传播机制。
  - 参数校验。
  - 全局异常处理。
  - 拦截器、过滤器。
  - 配置文件、环境隔离。

- Web 后端基础
  - RESTful API 设计。
  - 统一响应体。
  - 错误码设计。
  - 分页查询。
  - 参数校验。
  - 幂等接口。
  - JWT 登录认证。
  - RBAC 权限模型。

### 2.2 项目落地

在 AgentFlow Hub 中，对应模块可以设计为：

- 用户模块：登录、注册、JWT、角色权限。
- 项目空间模块：每个用户可以创建多个 Agent 应用。
- 知识库模块：管理文档、分片、向量索引。
- Agent 配置模块：选择模型、Prompt、工具、知识库。
- 任务模块：创建、执行、取消、查询 Agent 任务。
- 调用记录模块：保存请求、响应、工具调用、token 成本。

### 2.3 面试要能讲清楚

- 一个 HTTP 请求进入 Spring Boot 后的完整处理链路。
- Spring Bean 是如何创建和注入的。
- 为什么同类内部方法调用会导致事务失效。
- 线程池参数如何设置，为什么不能直接使用 Executors 默认工厂。
- 接口变慢时如何排查。
- JWT 认证和权限校验放在哪一层。

---

## 3. 数据库与缓存

### 3.1 必学知识

- 关系型数据库
  - MySQL 或 PostgreSQL 基础。
  - 表设计：主键、外键、唯一约束、普通索引。
  - 索引原理：B+ 树、联合索引、最左前缀、覆盖索引。
  - SQL 优化：explain、慢查询、回表、索引失效。
  - 事务 ACID。
  - 隔离级别：读未提交、读已提交、可重复读、串行化。
  - MVCC。
  - 行锁、表锁、间隙锁。
  - 分页优化。

- Redis
  - 数据结构：String、Hash、List、Set、ZSet、Stream。
  - 缓存模式：Cache Aside。
  - 缓存穿透、击穿、雪崩。
  - 分布式锁。
  - 限流计数器。
  - 会话缓存。
  - 热点数据缓存。
  - Redis 持久化和淘汰策略。

### 3.2 项目落地

数据库可以存：

- 用户表。
- Agent 应用表。
- 知识库表。
- 文档表。
- 文档 chunk 表。
- 工具配置表。
- Agent 任务表。
- LLM 调用记录表。
- 工具调用记录表。
- 评测集和评测结果表。

Redis 可以用于：

- 登录态或 token 黑名单。
- 用户级 QPS 限流。
- Agent 任务临时状态。
- SSE 流式输出过程中的任务状态缓存。
- 热门知识库召回结果缓存。
- 分布式锁，避免同一个文档被重复解析。

### 3.3 面试要能讲清楚

- 为什么某条 SQL 没走索引。
- 联合索引字段顺序如何设计。
- 如何设计一个能支撑多用户、多知识库的表结构。
- Redis 和数据库如何保证相对一致。
- 缓存击穿如何处理。
- 分布式锁为什么要设置过期时间。

---

## 4. 高并发与工程化后端

### 4.1 必学知识

- 并发处理
  - 线程池隔离。
  - 异步任务。
  - CompletableFuture。
  - 定时任务。
  - 任务取消。

- 消息队列
  - Kafka、RabbitMQ、RocketMQ 选一个深入即可。
  - 生产者、消费者。
  - 消息确认。
  - 消息重试。
  - 死信队列。
  - 延迟队列。
  - 消息幂等。

- 限流与稳定性
  - 固定窗口。
  - 滑动窗口。
  - 令牌桶。
  - 漏桶。
  - 熔断。
  - 降级。
  - 超时控制。
  - 重试策略。

- 接口工程
  - 幂等设计。
  - 防重复提交。
  - 文件上传。
  - 对象存储，例如 MinIO、OSS、COS。
  - SSE 流式响应。
  - WebSocket 基础。
  - traceId。
  - 结构化日志。

### 4.2 项目落地

建议在项目中实现：

- Agent 任务异步执行。
- 用户提交任务后立即返回 taskId。
- 前端通过 SSE 接收 Agent 执行过程。
- Agent 每一步执行状态写入数据库或 Redis。
- 工具调用失败后按照策略重试。
- 用户级、应用级、模型级限流。
- 大模型接口超时后降级返回。
- 文档上传后通过消息队列异步解析和向量化。
- 所有请求带 traceId，串联用户请求、RAG 召回、LLM 调用、工具调用。

### 4.3 面试要能讲清楚

- Agent 任务为什么不适合同步阻塞执行。
- SSE 和 WebSocket 的区别。
- 如果 LLM 响应很慢，系统如何保证用户体验。
- 如何避免同一任务被重复消费。
- 如果消息队列消费失败怎么办。
- 如何设计接口限流。
- 如何排查一次 Agent 调用链路中的慢点。

---

## 5. RAG 知识库

### 5.1 必学知识

- 文档处理
  - PDF、Word、Markdown、HTML、CSV、Excel 解析。
  - 文本清洗。
  - 去除页眉、页脚、噪声。
  - 标题层级识别。
  - 表格转文本。

- Chunking
  - 固定长度切分。
  - 滑动窗口。
  - 按标题切分。
  - 语义切分。
  - chunk metadata 设计。
  - chunk overlap。

- Embedding
  - Embedding 模型的作用。
  - 向量维度。
  - cosine similarity。
  - dot product。
  - 向量归一化。

- 向量数据库
  - pgvector、Qdrant、Milvus 选一个即可。
  - collection/index 概念。
  - metadata filter。
  - topK。
  - 相似度阈值。
  - 向量索引基础。

- 检索策略
  - 向量检索。
  - 关键词检索。
  - Hybrid Search。
  - Rerank。
  - Query Rewrite。
  - Multi-query Retrieval。
  - Context Compression。
  - 引用溯源。

- RAG 评测
  - 检索命中率。
  - 上下文相关性。
  - 答案忠实度。
  - 引用准确性。
  - 幻觉率。

### 5.2 项目落地

建议实现一条完整链路：

1. 用户上传文档。
2. 后端保存原始文件。
3. 异步解析文档。
4. 文本清洗。
5. 按规则切分 chunk。
6. 为每个 chunk 生成 embedding。
7. 写入向量数据库。
8. 用户提问时进行召回。
9. 对召回结果进行 rerank。
10. 将高质量上下文拼入 prompt。
11. LLM 生成回答。
12. 返回答案和引用来源。
13. 记录本次召回的 chunk、得分和最终回答。

### 5.3 面试要能讲清楚

- 为什么不能直接把整篇文档塞给大模型。
- chunk 太大和太小分别有什么问题。
- topK 如何选择。
- RAG 为什么仍然会幻觉。
- 如何提升召回质量。
- 为什么需要 rerank。
- 如何做引用溯源。
- 如何评估一个知识库问答系统的质量。

---

## 6. Agent 与工具调用

### 6.1 必学知识

- Tool Calling
  - Function Calling / Tool Calling 基本原理。
  - tool name、description、parameters。
  - JSON Schema。
  - 参数校验。
  - 工具返回格式设计。
  - 工具权限控制。
  - 工具超时控制。

- Agent 模式
  - ReAct。
  - Planner-Executor。
  - Router Agent。
  - Reflection 了解即可。
  - 多 Agent 协作了解即可。

- Agent 状态管理
  - 任务状态机。
  - 最大执行步数。
  - 最大 token 成本。
  - 最大工具调用次数。
  - 死循环检测。
  - 中断和取消。
  - 人工确认。

- 记忆系统
  - 短期记忆：当前会话上下文。
  - 长期记忆：用户偏好、历史任务、长期事实。
  - 记忆写入策略。
  - 记忆召回策略。
  - 记忆过期和删除。

- MCP
  - MCP 的基本概念。
  - MCP Server。
  - Tools。
  - Resources。
  - Prompts。
  - 与普通 Function Calling 的区别。

### 6.2 项目落地

建议先实现一个简化但工程化的 Agent 执行引擎：

- Agent 配置
  - 绑定模型。
  - 绑定系统 Prompt。
  - 绑定知识库。
  - 绑定可用工具。
  - 设置最大执行步数、最大 token 成本。

- 工具注册中心
  - 工具名称。
  - 工具描述。
  - 参数 schema。
  - 权限配置。
  - 超时时间。
  - 是否需要人工确认。

- Agent 执行流程
  - 接收用户任务。
  - 读取 Agent 配置。
  - 进行 RAG 检索。
  - 调用 LLM 生成下一步动作。
  - 判断是否需要调用工具。
  - 校验工具参数。
  - 执行工具。
  - 将工具结果写回上下文。
  - 循环直到完成、失败或达到限制。
  - 保存完整执行轨迹。

### 6.3 可实现的工具示例

- SQL 查询工具：查询业务数据。
- 知识库检索工具：主动查询指定知识库。
- 网页抓取工具：读取网页内容。
- 报告生成工具：生成 Markdown 或 PDF。
- 文件解析工具：解析上传文件。
- 日志分析工具：分析错误日志。
- 简历评分工具：根据 JD 对简历打分。
- 工单查询工具：模拟企业内部工单系统。

### 6.4 面试要能讲清楚

- Agent 和普通 Chatbot 的区别。
- Agent 什么时候应该调用工具。
- 工具参数由谁生成，如何校验。
- 工具执行失败怎么办。
- 如何避免 Agent 无限循环。
- 如何限制 Agent 的 token 成本。
- 如何让用户看到 Agent 正在执行什么。
- 如何记录一次 Agent 任务的完整执行链路。
- MCP 解决了什么问题。

---

## 7. LLMOps 与评测

### 7.1 必学知识

- Prompt 管理
  - Prompt 模板。
  - Prompt 变量。
  - Prompt 版本。
  - System Prompt 与 User Prompt。
  - Few-shot 示例。

- 调用观测
  - 模型名称。
  - 输入 token。
  - 输出 token。
  - 总成本。
  - 首 token 延迟。
  - 总耗时。
  - 错误码。
  - 重试次数。

- 执行链路
  - 用户请求 trace。
  - RAG 召回记录。
  - LLM 调用记录。
  - 工具调用记录。
  - Agent step 记录。
  - 最终答案记录。

- 评测
  - 构造测试问题集。
  - 构造标准答案。
  - 评估检索是否命中。
  - 评估答案是否忠实。
  - 评估工具调用是否正确。
  - 人工反馈。
  - 自动化回归评测。

- 可了解工具
  - Langfuse。
  - Arize Phoenix。
  - OpenTelemetry。
  - Prometheus。
  - Grafana。

### 7.2 项目落地

建议后台提供这些能力：

- 查看每次 Agent 任务的完整 trace。
- 查看每一步 LLM 调用的 prompt、响应、token、耗时。
- 查看每次 RAG 召回的 chunk、分数、来源。
- 查看每次工具调用的入参、出参、耗时、错误。
- 对回答进行点赞、点踩、反馈。
- 维护一组评测问题。
- 一键运行评测集。
- 对比不同 Prompt 版本或不同检索参数的效果。

### 7.3 面试要能讲清楚

- 为什么 AI 应用需要评测，而不是只看能不能回答。
- 如何评估 RAG 效果。
- 如何评估 Agent 是否完成任务。
- 如何发现 Prompt 改动导致效果退化。
- 如何控制 token 成本。
- 如何排查一次 Agent 失败。

---

## 8. DevOps 与部署

### 8.1 必学知识

- Git
  - 分支管理。
  - commit 规范。
  - pull request。
  - conflict 处理。

- Docker
  - Dockerfile。
  - docker compose。
  - 镜像、容器、网络、卷。
  - 多服务本地部署。

- Linux
  - 常用命令。
  - 进程查看。
  - 端口查看。
  - 日志查看。
  - systemd 了解即可。

- CI/CD 了解
  - GitHub Actions。
  - 自动测试。
  - 自动构建镜像。

- Nginx 了解
  - 反向代理。
  - 静态资源。
  - HTTPS。

### 8.2 项目落地

至少做到：

- 后端、数据库、Redis、向量数据库可以用 docker compose 一键启动。
- README 写清楚本地启动方式。
- 提供 API 文档。
- 提供基础测试。
- 提供演示数据。
- 提供架构图。

Kubernetes 可以了解，但不建议作为初期重点。

---

## 9. 推荐技术栈

### 9.1 后端主栈

- Java 21。
- Spring Boot 3。
- MyBatis-Plus 或 JPA。
- Spring Security。
- PostgreSQL 或 MySQL。
- Redis。
- RabbitMQ 或 Kafka。
- Docker Compose。

### 9.2 AI 应用栈

- Spring AI 或 LangChain4j。
- OpenAI-compatible API。
- pgvector、Qdrant 或 Milvus。
- Rerank 模型 API。
- SSE 流式输出。
- JSON Schema Tool Calling。
- MCP 作为进阶扩展。

### 9.3 前端

前端不是你的主线，但最好有一个能演示的平台：

- Vue 3 或 React。
- 基础管理后台。
- Agent 对话页面。
- 知识库管理页面。
- 工具管理页面。
- Trace 详情页面。

前端目标是“能展示项目价值”，不是做复杂视觉效果。

---

## 10. 学习优先级

### P0：必须掌握

- Java 并发。
- Spring Boot。
- SQL、索引、事务。
- Redis。
- REST API 设计。
- JWT 权限。
- RAG 基础。
- Tool Calling。
- SSE 流式响应。
- Docker Compose。

### P1：项目亮点

- 消息队列。
- Agent 状态机。
- 工具注册中心。
- 向量数据库。
- Hybrid Search。
- Rerank。
- 执行链路追踪。
- token 成本统计。
- Prompt 版本管理。
- 评测集。

### P2：加分但不要过早深挖

- Kubernetes。
- 多 Agent 协作。
- MCP 完整协议实现。
- OpenTelemetry 全链路追踪。
- 分库分表。
- 模型微调。
- Spring 源码通读。
- 复杂深度学习论文复现。

---

## 11. 不建议优先投入的方向

为了年底前找开发类实习，不建议现在把主要精力放在：

- 从零训练大模型。
- CV 模型结构细节。
- RLHF、DPO、PPO。
- 复杂多 Agent 框架堆叠。
- Kubernetes 深水区。
- Spring 源码完整通读。
- 大量前端动效。
- 只做一个普通聊天机器人。

这些方向不是没价值，而是对“深圳大厂开发/AI 应用开发实习”的转化率不如 Java 后端 + Agent 工程化。

---

## 12. 项目模块与知识映射

| 项目模块 | 需要学习的知识 | 简历亮点 |
| --- | --- | --- |
| 用户与权限 | Spring Security、JWT、RBAC、Redis | 完整后端权限体系 |
| 知识库管理 | 文件上传、对象存储、异步任务 | 企业知识库基础能力 |
| 文档解析 | PDF/Word/Markdown 解析、文本清洗 | 数据治理和预处理能力 |
| 向量检索 | Embedding、pgvector/Qdrant/Milvus | RAG 检索核心 |
| 召回优化 | Hybrid Search、Rerank、Query Rewrite | 提升 RAG 质量 |
| Agent 执行引擎 | 状态机、Tool Calling、Planner | Agent 工程化能力 |
| 工具平台 | JSON Schema、权限、超时、重试 | 可扩展工具调用体系 |
| 流式响应 | SSE、异步任务、任务状态 | 更好的用户体验 |
| 调用观测 | traceId、日志、token、latency | LLMOps 意识 |
| 评测平台 | 测试集、自动评测、反馈 | AI 应用质量保障 |
| 部署 | Docker Compose、环境变量、README | 可运行、可展示 |

---

## 13. 自检清单

### 13.1 后端自检

- 能否独立设计一个多表业务模块？
- 能否解释接口从请求到响应的链路？
- 能否写出统一异常处理和参数校验？
- 能否处理分页、排序、筛选？
- 能否解释事务失效场景？
- 能否设计合理索引？
- 能否使用 Redis 做缓存和限流？
- 能否用线程池处理异步任务？
- 能否解释消息队列的失败重试？

### 13.2 RAG 自检

- 能否解释 RAG 的完整链路？
- 能否设计 chunk metadata？
- 能否说明 chunk size 对效果的影响？
- 能否解释向量检索和关键词检索的区别？
- 能否解释为什么需要 rerank？
- 能否返回答案引用来源？
- 能否记录并分析每次召回结果？
- 能否构造一组 RAG 评测问题？

### 13.3 Agent 自检

- 能否解释 Agent 和普通 Chatbot 的区别？
- 能否设计工具 schema？
- 能否限制工具调用权限？
- 能否处理工具调用失败？
- 能否限制最大执行步数？
- 能否保存 Agent 执行轨迹？
- 能否支持任务取消？
- 能否解释 MCP 的基本价值？

### 13.4 LLMOps 自检

- 能否记录 prompt、响应、token、耗时？
- 能否统计不同用户或应用的调用成本？
- 能否展示一次任务的完整 trace？
- 能否对 Prompt 版本做对比？
- 能否设计评测集？
- 能否发现一次 Agent 失败发生在哪一步？

---

## 14. 面试叙事模板

项目介绍可以按照这个结构讲：

1. 背景
   - 企业内部文档、业务系统、工单、日志分散，普通问答机器人无法完成复杂任务。

2. 目标
   - 构建一个支持知识库 RAG、工具调用、任务编排、流式响应和执行追踪的 Agent 应用平台。

3. 架构
   - 后端使用 Spring Boot，数据库存储业务配置，Redis 管理状态和限流，消息队列处理异步任务，向量数据库支撑 RAG 检索。

4. 核心模块
   - 知识库模块、Agent 执行引擎、工具注册中心、LLM 调用观测、评测模块。

5. 难点
   - 文档切分和召回质量。
   - Agent 工具调用的稳定性。
   - 长任务的流式反馈和状态管理。
   - token 成本和执行链路追踪。

6. 结果
   - 支持多用户创建 Agent 应用。
   - 支持文档上传、向量化、问答引用。
   - 支持工具调用和完整执行轨迹。
   - 支持调用成本、延迟、失败原因统计。

---

## 15. 简历表达示例

可以后续根据真实实现修改为：

> 基于 Spring Boot 构建企业级 Agent 工作流平台，支持知识库 RAG、工具调用、任务编排、SSE 流式响应与执行链路追踪。设计文档解析、chunk 切分、向量检索、rerank、引用溯源等 RAG 流程，并实现工具注册、参数校验、权限控制、失败重试和 token 成本统计。通过 Redis 限流缓存、消息队列异步处理、Docker Compose 多服务部署提升系统稳定性与可维护性。

更偏后端的版本：

> 负责 Agent 应用平台后端架构设计与核心模块开发，基于 Spring Boot 实现多租户应用管理、知识库管理、异步任务执行、SSE 流式响应、Redis 限流缓存和调用链路追踪。针对文档解析和 Agent 执行耗时较长的问题，引入消息队列进行异步解耦，并设计任务状态机、重试机制和幂等控制，提升系统稳定性。

更偏 AI 应用的版本：

> 设计并实现面向企业知识库的 RAG 与 Agent 执行引擎，支持文档解析、向量化、Hybrid Search、rerank、引用溯源、Tool Calling 和 Prompt 版本管理。沉淀 LLM 调用日志、token 成本、召回结果和工具执行轨迹，构建评测集对 RAG 命中率和 Agent 任务完成率进行持续评估。

---

## 16. 最终学习主线

建议始终围绕这条主线学习：

> Java 后端基础 -> 数据库/缓存 -> 高并发与异步任务 -> RAG 知识库 -> Agent 工具调用 -> LLMOps/评测 -> 系统设计表达。

学习时不要只看教程。每学一块，都要在 AgentFlow Hub 中落一个真实模块：

- 学 Spring Boot，就做用户、知识库、任务接口。
- 学 Redis，就做限流、缓存、任务状态。
- 学消息队列，就做文档解析和 Agent 异步执行。
- 学 RAG，就做上传、切分、向量化、召回、引用。
- 学 Agent，就做工具注册、状态机、执行轨迹。
- 学 LLMOps，就做 token 统计、trace、评测集。

最终目标不是“我学过这些技术”，而是：

> 我用这些技术解决了一个真实 AI 应用系统中的工程问题。

