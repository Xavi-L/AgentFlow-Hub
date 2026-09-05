# AgentFlow Hub 数据库与领域模型设计

> 文档状态：**NORMATIVE**  
> 权威范围：PostgreSQL/Qdrant 数据形状、约束、索引、外键和快照字段  
> 最近审查基线：`main@f276549`，当前 Flyway schema version 为 16  
> TaskStatus/TaskPhase 以 Agent Engine Design 为准；向量身份和 readiness 以 RAG Design 为准。

---

## 1. 建模原则

### 1.1 已应用 migration 不可变

`V1`–`V16` 保持不可变。本文中的新增字段、约束和表必须通过更高版本 migration 实现，不得回改历史 migration。

本文同时区分：

- **CURRENT**：V16 schema 已存在；
- **V0.1 TARGET**：完成 Agent 闭环前必须新增；
- **DEFERRED**：V1.x 后再新增。

### 1.2 主键和时间

- 核心表使用 `BIGINT id`；
- Java 侧继续使用 MyBatis-Plus `ASSIGN_ID`；
- 时间使用 `TIMESTAMPTZ`；
- 业务排序总是增加 `id` 作为稳定 tie-breaker；
- API 将 BIGINT 序列化为字符串，数据库仍保持 BIGINT。

### 1.3 状态必须受数据库约束

状态使用 `VARCHAR` + CHECK，不使用 PostgreSQL enum。新增状态时通过新 migration 修改 CHECK。

状态字段只能表达一个事实：

- task 生命周期使用 `status`；
- task 当前执行阶段使用 `phase`；
- 文档软删除使用 `deleted_at`，不再额外使用 `DELETED` parse status；
- parsing、vectorization 和 retrieval readiness 不混用。

### 1.4 Owner scope 由数据库证明

只要子表冗余保存 `user_id`，就必须通过复合外键证明它与父资源 owner 一致。现有 `knowledge_document` 和 `knowledge_chunk` 的复合外键模式是标准做法，后续 Agent binding 和 task 继续采用。

禁止只在 Service 中“先查 owner 再插入”而不给数据库约束。

### 1.5 JSONB 的使用边界

JSONB 用于：

- immutable execution snapshot；
- corpus/tool/model snapshot；
- provider request/response metadata；
- event payload；
- 可选扩展配置。

核心可查询状态、外键、预算和时间不得只藏在 JSONB 中。

### 1.6 历史 Trace 不随配置删除

配置类资源可以软删除；Trace 默认不软删除。历史 hit、tool/LLM call 和 task 必须在源 Agent、文档或工具被修改/删除后仍可解释。

因此：

- Trace 保存必要 snapshot；
- 不对历史 Trace 使用危险级联删除；
- 对已删除源对象的 ID 可以只保存 snapshot value，或使用 nullable FK/`ON DELETE SET NULL`；
- task 自身在 V0.1 不提供删除接口。

---

## 2. 聚合边界

| 聚合 | 根对象 | 主要成员 |
| --- | --- | --- |
| User | `app_user` | 用户身份与基础角色 |
| Knowledge | `knowledge_base` | document、chunk、删除/重处理任务 |
| AgentApp | `agent_app` | knowledge/tool bindings |
| AgentTask | `agent_task` | step、LLM/RAG/tool logs、events |
| Tool | `tool_definition` | binding、call log |
| DemoBusiness | mock tables | order、payment log |

`AgentTask` 是一次执行唯一根对象。Event、Episode 和前端状态都不能成为第二个任务真相源。

---

## 3. CURRENT：用户与知识库

### 3.1 app_user

现有字段：

```text
id
username
email
password_hash
display_name
role                 USER / ADMIN
status               ACTIVE / DISABLED
last_login_at
created_at
updated_at
deleted_at
```

约束：

```text
UNIQUE(username)
UNIQUE(email)
CHECK role/status
```

V0.1 不增加组织、多租户或复杂 RBAC。

### 3.2 knowledge_base

现有字段：

```text
id
user_id
name
description
embedding_provider
embedding_model
chunk_size
chunk_overlap
status               ACTIVE / DISABLED
metadata
created_at
updated_at
deleted_at
```

现有复合唯一键 `(id, user_id)` 必须保留，供 document owner-scope FK 使用。

逻辑上的 `embeddingProfileCode` 由 V0.1 固定 provider/model/collection 配置解析，不允许客户端自由组合。后续引入 `embedding_profile` 表时，再将 provider/model 迁移为 profile 引用。

### 3.3 knowledge_document

现有字段加 V11 generation：

```text
id
user_id
knowledge_base_id
file_name
file_type             TXT / MD
mime_type
file_size
storage_bucket
storage_object_key
parse_status          PENDING / PROCESSING / COMPLETED / FAILED / REPROCESSING
parse_error
vector_generation
created_at
updated_at
deleted_at
```

关键约束：

```text
FOREIGN KEY (knowledge_base_id, user_id)
  REFERENCES knowledge_base(id, user_id)

UNIQUE(storage_bucket, storage_object_key)
UNIQUE(id, knowledge_base_id, user_id)
```

`chunk_count`、`char_count`、`token_count` 在 V0.1 通过 chunk 聚合查询，不复制为可漂移的 document 字段。出现明确性能需求后再增加缓存列和一致性规则。

### 3.4 knowledge_chunk

现有字段：

```text
id
user_id
knowledge_base_id
document_id
chunk_index
content
title_path
char_count
token_count
vectorization_status
vectorization_error
content_hash
vector_id
vector_generation
created_at
updated_at
```

V0.1 TARGET 新增：

```text
chunk_strategy_version VARCHAR(64) NOT NULL
```

历史 V4–V16 数据回填为：

```text
structured-token-v1
```

关键约束：

```text
FOREIGN KEY (document_id, knowledge_base_id, user_id)
  REFERENCES knowledge_document(id, knowledge_base_id, user_id)

UNIQUE(document_id, chunk_index)
CHECK chunk_index >= 0
CHECK content nonblank
CHECK char_count/token_count > 0
CHECK content_hash is lowercase SHA-256
CHECK vectorization state fields are mutually consistent
CHECK vector_generation >= 0
```

不新增通用 `metadata JSONB` 作为 V0.1 前置。页码、block range 等在支持 PDF/更丰富 parser 时通过显式字段或版本化 metadata migration 增加。

### 3.5 knowledge_document_deletion_task / reprocess_task

现有 V10/V11 持久补偿表继续保留。它们属于 Knowledge 维护流程，不是 AgentTask，也不进入通用 task 状态机。

要求：

- task 与 document 使用 owner-scope 复合 FK；
- 同一 document 同时最多一个 active cleanup task；
- generation fence 为 BIGINT 非负；
- lifecycle CHECK 保证时间字段和 cleanup status 一致；
- 历史 Agent retrieval hit 不级联删除。

---

## 4. CURRENT：Tool 与 AgentApp

### 4.1 tool_definition

现有字段：

```text
id
tool_code
name
description
type                    BUILTIN / HTTP / MCP
input_schema
output_schema
config
timeout_ms
retry_count
requires_confirmation
permission_level        LOW / MEDIUM / HIGH
status                  ACTIVE / DISABLED
created_at
updated_at
deleted_at
```

V0.1 只执行 `BUILTIN`，且 Agent snapshot 只允许 `order_query`、`payment_log_query`。

`tool_code` 全局唯一。已发布 code 不原地重命名；语义不兼容变化创建新 code 或提升 implementation version。

### 4.2 tool_call_log

现有字段：

```text
id
task_id nullable
step_id nullable
tool_id
tool_code snapshot
tool_name snapshot
arguments
result
status
retry_count
latency_ms
error_code
error_message
started_at
finished_at
created_at
```

V0.1 TARGET：

- 在 `agent_task`/`agent_step` 建立后，为非空 task_id/step_id 增加 FK；
- 不强制旧 standalone tool test log 必须关联 task；
- 增加索引 `(task_id, created_at, id)`；
- result/arguments 进入数据库前执行大小限制和脱敏；
- V0.1 不做多 attempt 重试，因此一行代表一次实际 handler 调用。

未来启用重试时新增 `tool_call_attempt`，不要只反复覆盖同一行并丢失 attempt 证据。

### 4.3 agent_app

现有字段：

```text
id
user_id
name
description
system_prompt
model_provider
model_name
temperature
top_p
max_steps
max_tool_calls
max_tokens
timeout_seconds
status                  ACTIVE / DISABLED
config
created_at
updated_at
deleted_at
```

语义映射：

```text
max_steps       -> maxDecisionTurns
max_tokens      -> maxTotalTokens
```

V0.1 TARGET 增加：

```text
UNIQUE(id, user_id)
```

用于 binding/task owner-scope 复合 FK。

V0.1 不新增 prompt version 表。execution snapshot 保存 task 实际使用的 system prompt 和模型配置。

---

## 5. V0.1 TARGET：Agent Bindings

### 5.1 agent_knowledge_binding

```text
id                    BIGINT PK
user_id               BIGINT NOT NULL
agent_id              BIGINT NOT NULL
knowledge_base_id     BIGINT NOT NULL
priority              INT NOT NULL DEFAULT 0
created_at            TIMESTAMPTZ NOT NULL
updated_at            TIMESTAMPTZ NOT NULL
```

约束：

```text
FOREIGN KEY (agent_id, user_id)
  REFERENCES agent_app(id, user_id)

FOREIGN KEY (knowledge_base_id, user_id)
  REFERENCES knowledge_base(id, user_id)

UNIQUE(agent_id, knowledge_base_id)
CHECK priority >= 0
```

V0.1 首个 Agent 只绑定一个知识库，但表保持多对多，避免以后从单字段迁移。

绑定只表示 Agent 可以使用该知识库。task 创建时仍要解析当时 READY 的 document generation 并写 execution snapshot。

### 5.2 agent_tool_binding

```text
id                    BIGINT PK
user_id               BIGINT NOT NULL
agent_id              BIGINT NOT NULL
tool_id               BIGINT NOT NULL
enabled               BOOLEAN NOT NULL DEFAULT TRUE
priority              INT NOT NULL DEFAULT 0
created_at            TIMESTAMPTZ NOT NULL
updated_at            TIMESTAMPTZ NOT NULL
```

约束：

```text
FOREIGN KEY (agent_id, user_id)
  REFERENCES agent_app(id, user_id)

FOREIGN KEY (tool_id)
  REFERENCES tool_definition(id)

UNIQUE(agent_id, tool_id)
CHECK priority >= 0
```

V0.1 不增加 `config_override`。出现真实 per-Agent 工具配置需求后再增加，并定义 merge/schema 规则。

---

## 6. V0.1 TARGET：agent_task

### 6.1 字段

```text
id                         BIGINT PK
user_id                    BIGINT NOT NULL
agent_id                   BIGINT NOT NULL
client_request_id          VARCHAR(128) NOT NULL
request_fingerprint        CHAR(64) NOT NULL
status                     VARCHAR(32) NOT NULL
phase                      VARCHAR(32)
termination_reason         VARCHAR(64)
user_input                 TEXT NOT NULL
execution_snapshot         JSONB NOT NULL
max_decision_turns         INT NOT NULL
max_tool_calls             INT NOT NULL
max_total_tokens           INT NOT NULL
reserved_final_tokens      INT NOT NULL
decision_turns_used        INT NOT NULL DEFAULT 0
tool_calls_used            INT NOT NULL DEFAULT 0
input_tokens               INT NOT NULL DEFAULT 0
output_tokens              INT NOT NULL DEFAULT 0
total_tokens               INT NOT NULL DEFAULT 0
token_usage_quality        VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'
final_answer               TEXT
citations                  JSONB NOT NULL DEFAULT '[]'
error_code                 VARCHAR(64)
error_message              VARCHAR(500)
cancel_requested_at        TIMESTAMPTZ
started_at                 TIMESTAMPTZ
completed_at               TIMESTAMPTZ
last_event_sequence        BIGINT NOT NULL DEFAULT 0
created_at                 TIMESTAMPTZ NOT NULL
updated_at                 TIMESTAMPTZ NOT NULL
version                    INT NOT NULL DEFAULT 0
```

`token_usage_quality`：

```text
EXACT
ESTIMATED
MIXED
UNKNOWN
```

### 6.2 外键、唯一性与幂等指纹

```text
FOREIGN KEY (agent_id, user_id)
  REFERENCES agent_app(id, user_id)

UNIQUE(user_id, client_request_id)
```

`request_fingerprint` 对以下 fixed-version canonical JSON 的 UTF-8 bytes 计算 lowercase SHA-256：

```json
{
  "version": "agent-task-request-v1",
  "agentId": "1001",
  "input": "原始 userInput"
}
```

对象 key 顺序、字符串转义和 ID 字符串表达必须固定。`userInput` 校验非空白后按原始值参与 fingerprint，不做静默 trim。

### 6.3 状态约束

```text
status IN (QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT)
phase IN (PREPARING, RETRIEVING, DECIDING, EXECUTING_TOOL, GENERATING)
```

必须通过 CHECK 保证：

- `QUEUED`：phase/started_at/completed_at/termination_reason 均为空；
- `RUNNING`：phase 和 started_at 非空，completed_at/termination_reason 为空；
- 终态：phase 为空，completed_at 和 termination_reason 非空；
- `COMPLETED`：final_answer 非空，error_code/error_message 为空；
- `FAILED`：final_answer 为空，error_code/error_message 非空；
- `CANCELLED`：terminationReason=`USER_CANCELLED`；
- `TIMED_OUT`：terminationReason=`DEADLINE_EXCEEDED`；
- used counters 非负且不超过配置上限；
- token 三字段非负且 `total_tokens = input_tokens + output_tokens`；
- `QUEUED/RUNNING/COMPLETED` 的 `total_tokens <= max_total_tokens`；
- `FAILED/CANCELLED/TIMED_OUT` 允许保存真实或保守估算的超额消费，仍须满足 token 非负与加法一致性；
- `last_event_sequence >= 0`；
- citations 为 JSON array；
- execution_snapshot 为 JSON object；
- `max_tool_calls < max_decision_turns`；
- `reserved_final_tokens < max_total_tokens`。

V40 使用追加迁移 `V20__preserve_task_token_overruns.sql` 调整 token CHECK，不回改 V18。
预算是调用准入和成功终态的防线，不能为了满足上限而丢失已经发生的模型消费。调用后发现超额时，
Engine 保留实际 usage 并停止后续调用，由 Runner 写入失败、取消或超时终态；不得截断为预算上限或改记 0。

### 6.4 索引

```text
UNIQUE(user_id, client_request_id)
INDEX(user_id, created_at DESC, id DESC)
INDEX(agent_id, status, created_at, id)
INDEX(status, created_at, id) WHERE status IN ('QUEUED', 'RUNNING')
INDEX(cancel_requested_at) WHERE status = 'RUNNING' AND cancel_requested_at IS NOT NULL
```

V0.1 不把 conversation_id 加入 task。多轮对话后续通过新 migration 增加 nullable 关联。

---

## 7. V0.1 TARGET：agent_step

```text
id                BIGINT PK
task_id           BIGINT NOT NULL
step_index        INT NOT NULL
step_type         VARCHAR(32) NOT NULL
status            VARCHAR(32) NOT NULL
title             VARCHAR(255) NOT NULL
summary           JSONB NOT NULL DEFAULT '{}'
error_code        VARCHAR(64)
error_message     VARCHAR(500)
started_at        TIMESTAMPTZ NOT NULL
ended_at          TIMESTAMPTZ
latency_ms        BIGINT
created_at        TIMESTAMPTZ NOT NULL
```

StepType：

```text
PRE_RETRIEVAL
LLM_DECISION
TOOL_CALL
LLM_FINAL_GENERATION
```

StepStatus：

```text
RUNNING
SUCCESS
FAILED
SKIPPED
```

约束：

```text
FOREIGN KEY(task_id) REFERENCES agent_task(id)
UNIQUE(task_id, step_index)
UNIQUE(id, task_id)
CHECK step_index >= 0
CHECK terminal step has ended_at/latency
CHECK success has no error
CHECK failed has safe error
```

`UNIQUE(id,task_id)` 为 LLM/RAG/tool 日志的复合外键提供被引用键，使数据库能证明 `step_id` 确实属于同一个 `task_id`。

`summary` 只保存小型语义摘要和专项日志 ID，不复制完整 Prompt、tool result 或 hit content。

---

## 8. V0.1 TARGET：LLM Trace

### 8.1 llm_call_log

```text
id                       BIGINT PK
task_id                  BIGINT NOT NULL
step_id                  BIGINT NOT NULL
call_type                VARCHAR(32) NOT NULL
provider                 VARCHAR(64) NOT NULL
requested_model          VARCHAR(128) NOT NULL
resolved_model           VARCHAR(128)
request_snapshot         JSONB NOT NULL
response_text            TEXT
finish_reason            VARCHAR(64)
provider_request_id      VARCHAR(255)
input_tokens             INT
output_tokens            INT
total_tokens             INT
usage_quality            VARCHAR(32) NOT NULL
latency_ms               BIGINT NOT NULL
status                   VARCHAR(32) NOT NULL
error_code               VARCHAR(64)
error_message            VARCHAR(500)
created_at               TIMESTAMPTZ NOT NULL
```

CallType：

```text
DECISION
FINAL_GENERATION
```

约束至少包括：

```text
FOREIGN KEY (step_id, task_id)
  REFERENCES agent_step(id, task_id)
CHECK request_snapshot is object
CHECK usage fields/quality are mutually consistent
CHECK success/error fields are mutually consistent
```

V0.1 的 document embedding 不写入这张 task-scoped 表。知识库向量化继续由 chunk 状态和服务日志观察；未来需要统一 provider observability 时增加 `scope_type/scope_id`，不要把所有 embedding 强行伪造成 AgentTask call。

`request_snapshot` 必须脱敏、大小受限，并保存消息角色/正文快照；不保存 API key、Authorization、完整 endpoint 或框架内部对象。

---

## 9. V0.1 TARGET：RAG Trace

### 9.1 rag_retrieval_log

```text
id                         BIGINT PK
task_id                    BIGINT NOT NULL
step_id                    BIGINT NOT NULL
query                      TEXT NOT NULL
embedding_profile_code     VARCHAR(128) NOT NULL
corpus_snapshot            JSONB NOT NULL
top_k                      INT NOT NULL
similarity_threshold       NUMERIC(8,6) NOT NULL
candidate_count            INT NOT NULL
valid_hit_count            INT NOT NULL
stale_hit_count            INT NOT NULL
latency_ms                 BIGINT NOT NULL
status                     VARCHAR(32) NOT NULL
error_code                 VARCHAR(64)
error_message              VARCHAR(500)
created_at                 TIMESTAMPTZ NOT NULL
```

约束至少包括：

```text
FOREIGN KEY (step_id, task_id)
  REFERENCES agent_step(id, task_id)
CHECK corpus_snapshot is object
CHECK counts/latency are nonnegative
CHECK status/error fields are mutually consistent
```

### 9.2 rag_retrieval_hit

```text
id                         BIGINT PK
retrieval_id               BIGINT NOT NULL
rank_no                    INT NOT NULL
citation_id                VARCHAR(32) NOT NULL
chunk_id_snapshot          BIGINT NOT NULL
document_id_snapshot       BIGINT NOT NULL
knowledge_base_id_snapshot BIGINT NOT NULL
vector_generation          BIGINT NOT NULL
score                      NUMERIC(10,8) NOT NULL
content_snapshot           TEXT NOT NULL
metadata_snapshot          JSONB NOT NULL
created_at                 TIMESTAMPTZ NOT NULL
```

约束：

```text
FOREIGN KEY(retrieval_id) REFERENCES rag_retrieval_log(id)
UNIQUE(retrieval_id, rank_no)
UNIQUE(retrieval_id, citation_id)
```

不对 source chunk/document 建强制级联 FK。历史 snapshot 必须在源文档删除后继续存在。

---

## 10. V0.1 TARGET：agent_task_event

```text
id                BIGINT PK
task_id           BIGINT NOT NULL
sequence_no       BIGINT NOT NULL
event_type        VARCHAR(64) NOT NULL
payload           JSONB NOT NULL
created_at        TIMESTAMPTZ NOT NULL
```

约束与索引：

```text
FOREIGN KEY(task_id) REFERENCES agent_task(id)
UNIQUE(task_id, sequence_no)
CHECK sequence_no > 0
CHECK payload is object
INDEX(task_id, sequence_no)
```

### 10.1 Sequence 分配

V0.1 使用 `agent_task.last_event_sequence` 作为唯一游标。每次 append 在同一短事务中：

```sql
UPDATE agent_task
SET last_event_sequence = last_event_sequence + 1
WHERE id = :taskId
RETURNING last_event_sequence;

INSERT INTO agent_task_event(task_id, sequence_no, event_type, payload, created_at)
VALUES (:taskId, :returnedSequence, :eventType, :payload, CURRENT_TIMESTAMP);
```

该 UPDATE 的行锁串行化同一 task 的事件 append。禁止使用 `SELECT max(sequence_no)+1`。状态/phase/终态变化需要事件时，业务字段更新、cursor increment 和 event insert 必须放在同一事务。

`last_event_sequence` 是事件游标，不等同于 task 乐观锁 `version`；单独 append 展示事件时不自动修改 `version`。

Event 是供后续 SSE 使用的持久投影，不复制完整 Trace。V40 的同步 Final Generation 完成后，
`ANSWER_CHUNK` 按每条 payload 的实际 JSON UTF-8 bytes 限制为 16 KiB，必要时按 Unicode code point
分片，`chunkIndex` 从 0 连续递增。所有答案分片、`agent_task.final_answer`、citations、成功终态和
`TASK_COMPLETED` 必须在同一个完成事务中提交；任意中间分片失败时整组回滚，不留下部分可见答案。
这不是 provider token streaming，V40 不提供 SSE 接口。

---

## 11. Tool log 与 AgentTask 关联

Task/step schema 完成后，通过新 migration 保留现有 nullable 字段并增加：

```text
FOREIGN KEY (step_id, task_id)
  REFERENCES agent_step(id, task_id)
```

复合 FK 允许 `(NULL,NULL)`，兼容已有 standalone 工具测试调用；但 AgentTask 路径必须同时提供 task_id 和 step_id。不得出现 task_id 非空而 step_id 为空，或只填 step_id 的半关联状态。可通过 CHECK 约束：

```text
(task_id IS NULL AND step_id IS NULL)
OR
(task_id IS NOT NULL AND step_id IS NOT NULL)
```

AgentTask 路径还要求：

- tool snapshot code/name 保留；
- arguments/result 为 JSON object；
- status/时间/error 字段满足现有 V13 lifecycle CHECK；
- result/arguments 大小受限且已脱敏。

同样的复合 task/step 一致性已直接定义在 LLM 和 RAG log 中。

---

## 12. DemoBusiness

V0.1 继续使用：

```text
mock_order
mock_payment_log
```

`mock_ticket` 延后。

这些表是共享 demo 数据，不表示真实用户订单。工具结果和前端必须明确为 demo 场景，避免将 `app_user.id` 与模拟订单用户混为同一身份域。

---

## 13. Qdrant 数据模型

V0.1 collection：

```text
agentflow_chunks_te_v4_1024
```

契约：

```text
vector size = 1024
distance = Cosine
point ID = RAG Design 定义的 deterministic UUIDv8
```

Payload：

```text
chunkId
documentId
knowledgeBaseId
userId
chunkIndex
vectorGeneration
contentHash
embeddingProvider
embeddingModel
titlePath optional
```

过滤必须使用与写入相同的 JSON 字段名和数值类型。

PostgreSQL 仍为：

- 正文；
- owner；
- live/deleted；
- generation；
- vectorization status；
- contentHash；
- 当前 vectorId；
- task/hit snapshot。

---

## 14. DEFERRED 数据模型

以下不进入 V0.1 migration：

### 14.1 Prompt 版本

```text
agent_prompt_version
```

只有出现 Prompt 回滚/对比需求时增加。V0.1 已通过 execution snapshot 保证历史 task 可解释。

### 14.2 Conversation

```text
agent_conversation
agent_message
```

多轮对话进入产品范围后再增加。message 只保存用户/助手消息，工具和 RAG 仍属于 task Trace。

### 14.3 Episode

V1 初期通过 Trace API 动态聚合，不建表。只有导出成本、不可变归档或评测复用出现明确需求时，再增加可缓存的 `agent_episode`，并标明它是派生快照。

### 14.4 Evaluation

```text
eval_dataset
eval_case
eval_run
eval_result
```

进入 V1.x 后单独设计，不作为 V0.1 task schema 前置。

### 14.5 Tool Policy / Approval

```text
policy_check_log
approval_request
```

只有实现动态风险规则或人工确认时增加。普通工具存在、绑定、ACTIVE、schema 和预算校验不写成 PolicyGuard 日志。

### 14.6 Embedding Profile

```text
embedding_profile
knowledge_base.embedding_profile_id
```

在支持第二个向量空间前完成，不提前建设空注册中心。

### 14.7 Task Retry Chain

```text
agent_task.retry_of_task_id
```

V0.1 重试由客户端创建一个完全独立的新 task，不保存链。出现历史聚合或自动重试产品需求后，再增加 nullable self-reference 和循环约束。

---

## 15. 建议 migration 顺序

从 V16 之后按依赖顺序：

1. 为 `agent_app` 增加 `(id,user_id)` unique，并为 chunk 增加 `chunk_strategy_version`；
2. 创建 `agent_knowledge_binding`、`agent_tool_binding`；
3. 创建 `agent_task`；
4. 创建 `agent_task_event`，使 task 创建、dispatch、取消和终态从第一天就有持久 sequence；
5. 创建 `agent_step`；
6. 创建 `llm_call_log`、`rag_retrieval_log`、`rag_retrieval_hit`；
7. 为 `tool_call_log` 增加 task/step 复合一致性外键与索引；
8. 最后实现公开 Task API/SSE，不在 schema 前让代码产生无法持久化的状态。

具体 Flyway 版本号由实际提交顺序决定，但依赖顺序不得颠倒。

---

## 16. 不变量清单

数据库和测试必须证明：

1. document owner 与 knowledge base owner 一致；
2. chunk scope 与 document scope 一致；
3. Agent binding 中两端 owner 一致；
4. task owner 与 Agent owner 一致；
5. task 的 status/phase/terminationReason 组合合法；
6. completed task 必须有 final answer；
7. failed task 必须有安全错误；
8. task 内 step index 唯一；
9. 专项日志的 step 属于同一 task；
10. task.last_event_sequence 与已提交 event sequence 一致；
11. task event sequence 唯一且递增；
12. RAG hit snapshot 不因源文档删除消失；
13. vector state 字段互相一致；
14. tool call lifecycle 字段互相一致；
15. execution snapshot/citations/event payload JSON 类型正确；
16. 所有预算、计数和 latency 非负。

---

## 17. 数据保留与脱敏

V0.1 可以使用固定保留策略，但必须写清：

- task/Trace 默认保留，不提供用户删除；
- provider request/response、tool result、event payload 有最大长度；
- API key、Authorization、Cookie、内部 URL 和显式敏感字段不进入数据库；
- 后续开放真实业务数据前必须增加字段级脱敏和访问审计；
- 删除用户或执行合规删除的策略在 V1.x 单独设计，不能依赖无约束 `ON DELETE CASCADE`。
