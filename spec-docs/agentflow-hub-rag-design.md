# AgentFlow Hub RAG 与知识库设计

> 文档状态：**NORMATIVE**  
> 权威范围：文档入库、分块策略、embedding profile、向量身份、检索就绪、在线检索、citation 和重建语义  
> 最近审查基线：`main@f276549`（V36）

---

## 1. 核心结论

V0.1 的 RAG 采用：

```text
PostgreSQL 权威正文
+ structured-token-v1 确定性分块
+ 固定 Embedding Profile
+ 与 profile 一一绑定的 Qdrant collection
+ owner/KB/document/generation 过滤
+ PostgreSQL 回查
+ 引用白名单
```

V0.1 **不实现语义分块**。`semantic-v1` 只有在存在固定评测集并证明优于确定性基线后，才进入 V1.5 候选。

文档解析完成与向量可检索是两个独立事实：

```text
DocumentParseStatus
!=
ChunkVectorizationStatus
!=
RetrievalReadiness
```

AgentTask 只能使用创建快照时已经 `READY` 的文档 generation。

---

## 2. 模块边界

### 2.1 Knowledge 负责

- 知识库 CRUD；
- 文档上传和原文件定位；
- 文档解析；
- 分块；
- chunk 正文和 metadata；
- chunk 向量化状态；
- 文档删除与重处理；
- 在线检索编排；
- citation 构造；
- retrieval trace 的领域数据生成。

V0.1 不再为在线检索单独建设一个大型顶层 `rag` 平台模块。可以使用 `knowledge.retrieval` 包隔离在线检索，但它仍属于 Knowledge 领域。

### 2.2 Infra 负责

- Embedding provider 适配；
- Qdrant 适配；
- 本地/对象文件存储适配；
- HTTP timeout、序列化和外部错误归类。

业务层不能直接依赖 provider SDK 或 Qdrant SDK。

### 2.3 Agent 负责

- 从 task execution snapshot 读取允许检索的知识库和 document generation；
- 在执行开始时调用 RetrievalService；
- 将 evidence 放入 Prompt；
- 使用返回的 citation ID；
- 决定空检索后是否继续工具流程。

Agent 不负责解析文件、写向量或自行拼接 Qdrant filter。

---

## 3. 核心数据对象

### 3.1 KnowledgeBase

知识库的逻辑视图包含：

```text
id
userId
name
description
embeddingProfileCode
chunkStrategyVersion
status
metadata
createdAt
updatedAt
deletedAt
```

当前数据库仍保存 `embedding_provider`、`embedding_model`、`chunk_size` 和 `chunk_overlap`。V0.1 的 `embeddingProfileCode` 与 `chunkStrategyVersion` 由固定配置和当前数据库字段解析，不声称已经存在对应数据库列；客户端不能自由组合不可兼容配置。

### 3.2 KnowledgeDocument

文档保存：

```text
id
userId
knowledgeBaseId
fileName
fileType
mimeType
fileSize
storageBucket
storageObjectKey
parseStatus
parseError
vectorGeneration
createdAt
updatedAt
deletedAt
```

V0.1 支持：

```text
TXT
MD
```

PDF、OCR、Office 和网页抓取不进入 V0.1。

### 3.3 KnowledgeChunk

chunk 保存：

```text
id
userId
knowledgeBaseId
documentId
chunkIndex
content
titlePath
charCount
tokenCount
contentHash
vectorId
vectorGeneration
vectorizationStatus
vectorizationError
chunkStrategyVersion
createdAt
updatedAt
```

PostgreSQL 的 `content` 是权威正文。Qdrant payload 只用于过滤和回查，不作为正文真相源。

---

## 4. 状态模型

### 4.1 DocumentParseStatus

```text
PENDING
PROCESSING
COMPLETED
FAILED
REPROCESSING
```

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 原文件已接受，等待解析 |
| `PROCESSING` | parser 已领取 |
| `COMPLETED` | 当前 generation 的 chunk 正文已完整提交，不代表向量已经就绪 |
| `FAILED` | 解析或 chunk 提交失败 |
| `REPROCESSING` | 旧派生数据正在清理，尚未重新进入 parser |

`DELETED` 不作为 parseStatus。软删除由 `deleted_at` 表示，避免同一事实存在两种来源。

### 4.2 ChunkVectorizationStatus

```text
PENDING
PROCESSING
COMPLETED
FAILED
```

- `PENDING`：等待 embedding/Qdrant；
- `PROCESSING`：已被 vectorizer 条件领取；
- `COMPLETED`：Qdrant 写入成功且 PostgreSQL 已保存 vectorId；
- `FAILED`：确认未成功完成，保存安全错误摘要。

外部结果不确定时不得伪装成普通 `FAILED` 并立即允许破坏性重处理。应保留 `PROCESSING` 或通过后续 reconciliation 状态处理，防止迟到 upsert。

### 4.3 RetrievalReadiness

RetrievalReadiness 是查询时派生的读模型，不在 V0.1 复制为第二个可写状态：

```text
NOT_READY
INDEXING
READY
DEGRADED
FAILED
```

派生规则：

| 条件 | Readiness |
| --- | --- |
| document 为 `PENDING/PROCESSING/REPROCESSING` | `NOT_READY` |
| document 为 `FAILED` | `FAILED` |
| document 为 `COMPLETED` 且有 PENDING/PROCESSING chunk | `INDEXING` |
| document 为 `COMPLETED` 且当前 generation 所有 chunk 均 COMPLETED | `READY` |
| document 为 `COMPLETED` 且同时存在 COMPLETED 和 FAILED chunk | `DEGRADED` |
| document 为 `COMPLETED` 且没有任何 COMPLETED chunk | `FAILED` |

V0.1 Agent 只使用 `READY` 文档。`DEGRADED` 文档可以在管理页显示，但不进入 Agent task snapshot。

---

## 5. V0.1 分块策略：structured-token-v1

### 5.1 目标

V0.1 需要一个可复现、可测试、无需额外模型调用的分块基线。当前 `DocumentChunker` 的确定性结构/token 设计继续作为正式 V0.1 策略，并命名为：

```text
structured-token-v1
```

### 5.2 原子单元

- TXT：段落和行记录；
- Markdown：标题、段落、列表、引用、代码块和表格；
- 标题用于形成 `titlePath`；
- 代码块和表格优先保持完整；
- 原文不由 LLM 改写；
- 解析器必须保持稳定顺序和来源位置。

### 5.3 聚合规则

- 按文档顺序聚合；
- 优先在自然结构边界结束；
- 目标大小由 estimated token 控制；
- 超长单元在安全 token/句子边界切分；
- overlap 从前一 chunk 尾部按完整 token span 复制；
- 不在 Unicode code point 中间截断；
- 相同输入、相同策略参数必须产生相同 chunk 顺序和正文。

V0.1 固定默认值：

```text
targetChunkSize = 800 estimated tokens
chunkOverlap = 120 estimated tokens
maxDocumentCodePoints = 500000
maxChunkCount = 10000
```

数据库现有 `chunk_size/chunk_overlap` 在 V0.1 只能使用支持范围内的固定默认值。配置 UI 不允许任意调参。

### 5.4 策略版本

每个 chunk、task snapshot、retrieval trace 和后续 reindex 记录必须保存 `chunkStrategyVersion`。修改 tokenizer、结构规则、overlap 或 hard split 行为时提升版本，不能静默改变已有 chunk 的含义。

---

## 6. semantic-v1 的后移条件

`semantic-v1` 不是 V0.1 的“正式默认策略”。它进入 V1.5 前必须满足：

1. 固定且版本化的评测文档；
2. 固定 query/expected evidence；
3. 与 `structured-token-v1` 比较 Hit@K、MRR、citation accuracy、成本和延迟；
4. 边界 embedding profile 固定；
5. 阈值通过评测标定，而不是写死通用常数；
6. 原子单元 embedding 与最终 chunk embedding 的成本被记录；
7. reindex 和 generation 切换可回滚。

没有这些证据时，不因“语义分块更高级”而替换稳定基线。

---

## 7. Embedding Profile 与向量空间

### 7.1 V0.1 固定 Profile

```text
profileCode = dashscope-te-v4-1024-cosine
provider = dashscope
model = text-embedding-v4
dimension = 1024
distance = COSINE
collection = agentflow_chunks_te_v4_1024
```

上述字段构成同一个不可拆分的向量空间契约。

V0.1：

- 新知识库只使用该 profile；
- API 不接受自由 provider/model；
- 旧的 `openai-compatible/text-embedding-v3` 知识库在 remote mode 下不视为 V0.1 可用知识库；
- 不在同一 collection 混写不同模型、维度或距离度量；
- profile 变化必须新建 collection 并显式 reindex。

### 7.2 后续模型切换

后续增加：

```text
embedding_profile
knowledge_base.embedding_profile_id
```

Profile 一旦被使用即不可原地修改。创建新 profile、构建新向量语料、验证完成后再切换 active corpus。

---

## 8. Vector ID 精确契约

### 8.1 Content Hash

```text
contentHash = lowercase hex SHA-256(exact UTF-8 chunk content)
```

不 trim、不规范化换行、不改写正文。解析和清洗产生的最终 chunk content 是 hash 输入。

### 8.2 Material

使用换行符 `\n` 连接以下 UTF-8 字符串：

```text
agentflow-knowledge-vector-v1
{userId}
{knowledgeBaseId}
{documentId}
{chunkIndex}
{contentHash}
```

即：

```java
String material = String.join(
    "\n",
    "agentflow-knowledge-vector-v1",
    userId.toString(),
    knowledgeBaseId.toString(),
    documentId.toString(),
    chunkIndex.toString(),
    contentHash
);
```

### 8.3 UUIDv8

1. 对 material 的 UTF-8 bytes 计算 SHA-256；
2. 取 digest 前 16 bytes；
3. 设置 RFC 9562 version 8 bits；
4. 设置 RFC variant bits；
5. 输出标准 UUID 字符串。

`vectorGeneration` **不进入 V0.1 vector ID v1 material**。它是 Qdrant payload 和当前破坏性删除/reprocess 的 lifecycle fence。相同文档位置和相同正文可以幂等覆盖同一点；正文变化会产生新 point ID。

该算法必须有跨语言测试向量，任何分隔符、字段顺序或编码变化都要求新的 namespace/version。

### 8.4 与未来 copy-on-write 的兼容边界

当前 v1 ID 不能让相同 document/chunk/content 的两个 generation 在同一个 collection 中并存：新 generation 会覆盖同一 point ID。因此未来零停机 copy-on-write 不能直接复用 v1 identity。

实现 copy-on-write 时必须二选一，并通过显式 migration/reindex：

1. 使用新 namespace `agentflow-knowledge-vector-v2`，将 `vectorGeneration` 纳入 ID material；或
2. 每个 corpus generation 使用独立 physical collection，并通过 alias 原子切换。

在选择并实现其中一种方案前，不能宣称当前 v1 ID 已支持双 generation 并存。

---

## 9. Qdrant Payload

V0.1 payload 使用当前实现字段：

```json
{
  "chunkId": 401,
  "documentId": 301,
  "knowledgeBaseId": 201,
  "userId": 101,
  "chunkIndex": 0,
  "vectorGeneration": 0,
  "contentHash": "...",
  "embeddingProvider": "dashscope",
  "embeddingModel": "text-embedding-v4",
  "titlePath": "支付失败/错误码"
}
```

原则：

- 不把完整 chunk 正文作为权威 payload；
- ID 型字段的 JSON 类型在写入和过滤中保持一致；
- `embeddingProvider/model` 用于诊断，不替代 collection profile；
- `vectorGeneration` 用于精确删除和 task corpus snapshot；
- hit 必须回查 PostgreSQL 并验证 owner、live document、current generation、contentHash 和 COMPLETED 状态。

---

## 10. 文档入库流程

### 10.1 上传

1. owner-scoped 校验 knowledge base；
2. 校验知识库 ACTIVE 和 profile 支持；
3. 校验扩展名、MIME、文件大小；
4. 生成 server-owned storage key；
5. 保存文件；
6. 插入 `knowledge_document(PENDING)`。

失败时不得留下数据库认为存在但文件不存在的成功记录。V0.1 可使用本地补偿删除或先存文件后事务建记录。

### 10.2 解析与 chunk 提交

1. 条件领取 `PENDING -> PROCESSING`；
2. 读取原文件；
3. parser 输出稳定结构；
4. `structured-token-v1` 生成 chunk drafts；
5. 同一短事务批量插入当前 generation chunks；
6. 更新 document `COMPLETED`；
7. chunk 初始 `vectorizationStatus=PENDING`。

只有全部 chunk 成功提交后，document 才能 `COMPLETED`。

### 10.3 向量化

每个 chunk：

1. 条件领取 `PENDING -> PROCESSING`；
2. 事务外调用 EmbeddingGateway；
3. 生成稳定 vector ID 和 payload；
4. 事务外 Qdrant upsert，使用同一 ID 幂等覆盖；
5. 成功后短事务写 `COMPLETED/vectorId`；
6. 明确失败写安全 `FAILED/vectorizationError`；
7. 结果不确定时保留不可安全重处理的状态并交给 reconciliation。

不在外部 HTTP I/O 期间持有数据库事务或行锁。

---

## 11. Agent Task 的检索语料快照

Task 创建时解析每个绑定知识库当前 `READY` 文档，并冻结：

```json
{
  "knowledgeBaseId": "201",
  "embeddingProfileCode": "dashscope-te-v4-1024-cosine",
  "documents": [
    {"documentId": "301", "vectorGeneration": 0}
  ],
  "topK": 5,
  "similarityThreshold": 0.2,
  "chunkStrategyVersion": "structured-token-v1"
}
```

规则：

- 只有 READY 文档进入 snapshot；
- task 运行期间新上传的文档、新 generation 或 binding 修改不进入本次语料；
- 所有绑定知识库均没有 READY 文档时，task 创建失败为 `RAG_KNOWLEDGE_NOT_READY`；
- 在线检索必须按 snapshot document/generation 过滤，而不是只按 knowledgeBaseId 搜索当前全部点；
- Trace 保存 hit snapshot，历史解释不依赖未来文档是否仍存在。

Snapshot 冻结语料选择，但不会绕过平台级撤销：若知识库或文档随后被禁用、软删除，或 snapshot generation 已被清理，在线检索不得回退到新 generation，也不得继续暴露已撤销内容。它记录该 evidence 不再可用；只要检索调用本身仍能受控完成，就可以返回空/部分有效结果并让 Agent 基于工具继续。若整个 corpus 校验或向量空间契约失效，则以稳定 RAG 错误结束。

V0.1 演示 Agent 只绑定一个支付知识库，但数据模型允许多个知识库。

---

## 12. 在线检索

建议接口：

```java
public interface RetrievalService {
    RetrievalResult retrieve(RetrievalQuery query);
}
```

```text
RetrievalQuery:
  taskId
  userId
  query
  corpusSnapshot
  topK
  similarityThreshold
```

流程：

1. 校验 query 非空且长度受限；
2. 使用 corpus snapshot 的 embedding profile；
3. 生成 query vector；
4. Qdrant 搜索并使用 owner + document/generation filter；
5. 取大于 topK 的有限候选，补偿 PostgreSQL 二次过滤；
6. 回查 chunk、document、knowledge base；
7. 验证 owner scope、knowledge base/document live+ACTIVE、snapshot generation、contentHash、chunk strategy 和 vectorization COMPLETED；
8. similarity threshold；
9. 排序并截取 topK；
10. 分配 citation ID；
11. 构造 bounded context；
12. 写 retrieval log 和 hit snapshots。

V0.1 不做 query rewrite、multi-query、rerank 或 Hybrid Search。

### 12.1 空结果

空结果是合法的 RetrievalResult：

```json
{
  "hits": [],
  "citations": [],
  "contextText": "",
  "empty": true
}
```

Agent 可以继续调用业务工具；不得把“没有有效 hit”自动映射为系统失败。Embedding provider、Qdrant 或 corpus contract 本身失败仍是 `RAG_RETRIEVAL_FAILED`。

### 12.2 过期或已撤销 point

Qdrant 命中但 PostgreSQL 校验失败的 point：

- 不进入 Prompt；
- 记录 `staleHitCount` 或 revoked count；
- 不向用户暴露；
- 后续 reconciliation 清理；
- 若有效候选不足，可从更大的受限候选集补足，但必须设置最大候选数。

---

## 13. Context 与 Citation

### 13.1 Evidence 结构

```json
{
  "citationId": "C1",
  "chunkId": "401",
  "documentId": "301",
  "knowledgeBaseId": "201",
  "fileName": "refund-rules.md",
  "titlePath": "支付失败/错误码",
  "score": 0.8421,
  "content": "E_PAY_TIMEOUT 表示支付网关响应超时……"
}
```

### 13.2 Context 限制

- 单个 chunk content 上限；
- 总 evidence token 上限；
- 超限时按 score 和稳定 rank 截断；
- 不在字符中间截断 citation 对应正文；
- knowledge content 明确标记为 untrusted data；
- 文档中的指令不得覆盖 Runtime Rules。

### 13.3 Citation 验证

Final Generation 只能使用本次 RetrievalResult 分配的 `[C1]...[Cn]`。

后端必须：

1. 提取最终回答中的 citation marker；
2. 验证每个 marker 属于本次白名单；
3. 生成结构化 citation 列表；
4. 未知 marker 以 `RAG_INVALID_CITATION` 使最终生成失败，不静默伪造来源；
5. 没有 evidence 时，不要求引用知识库。

citation 验证只证明来源存在于本次 evidence，不自动证明每个自然语言结论都被充分支持；后续 Evaluation 再评估 citation accuracy。

---

## 14. Trace

`rag_retrieval_log` 保存：

```text
taskId
stepId
query
embeddingProfileCode
corpusSnapshot
topK
similarityThreshold
candidateCount
validHitCount
staleHitCount
latencyMs
status
errorCode
createdAt
```

`rag_retrieval_hit` 保存：

```text
retrievalId
rankNo
citationId
chunkIdSnapshot
documentIdSnapshot
knowledgeBaseIdSnapshot
vectorGeneration
score
contentSnapshot
metadataSnapshot
createdAt
```

历史 hit 不因 chunk/document 删除而级联消失。可以保留 nullable 关联 ID，但 snapshot 是历史 Trace 的权威内容。

---

## 15. 删除与重处理

### 15.1 删除

删除文档需要：

- owner/live scope；
- 阻止正在 PROCESSING 的不确定 vector worker；
- generation-fenced Qdrant 删除；
- 持久补偿任务；
- 数据库软删除或最终清理；
- 不破坏历史 retrieval hit snapshot。

当前 V10/V11 的持久补偿和 generation fence 继续保留。

### 15.2 V0.1 Completed 文档重处理

当前实现采用先清理旧 vector/chunk，再回到 PENDING 的破坏性流程。它不是零停机重建：新解析或向量化失败时，旧可检索版本已经下线。

因此 V0.1：

- 该能力属于维护入口，不属于 Agent 闭环完成标准；
- 默认前端可以隐藏 Completed 文档 reprocess；
- 不继续扩展它的产品交互；
- task snapshot 只选择当时 READY 的 generation；
- snapshot generation 在 task 执行前被重处理清理时，本次检索不会切换到新 generation。

### 15.3 长期 copy-on-write

V1.x 推荐：

```text
保留 old active corpus
-> 使用 vector identity v2 或独立 collection 构建 new corpus
-> 全量向量 READY
-> 原子切换 active corpus/generation alias
-> 异步删除 old corpus
```

失败时旧 corpus 保持可检索。该方案必须先落实第 8.4 节的双 generation identity/collection 选择，不能只增加数据库 generation 字段就宣称完成。

---

## 16. 安全与资源限制

V0.1 固定限制：

- 文件类型 allowlist；
- MIME 与扩展名双校验；
- 最大上传字节；
- 最大解码 code points；
- 最大 chunk 数；
- 最大单 chunk tokens；
- 最大 query 长度；
- 最大 Qdrant 候选数；
- 最大 context tokens；
- parser/embedding/Qdrant timeout；
- storage key 由服务端生成；
- 禁止路径穿越；
- 错误不暴露本地绝对路径、provider body、API key 或 Qdrant URL。

Prompt 和 Trace 进入持久化前必须执行密钥、Authorization、Cookie 和显式敏感字段脱敏。

---

## 17. 失败分类

至少包括：

```text
KNOWLEDGE_BASE_NOT_FOUND
KNOWLEDGE_BASE_DISABLED
DOCUMENT_NOT_FOUND
DOCUMENT_TYPE_UNSUPPORTED
DOCUMENT_TOO_LARGE
DOCUMENT_PARSE_FAILED
DOCUMENT_REPROCESS_CONFLICT
EMBEDDING_PROFILE_UNSUPPORTED
EMBEDDING_GENERATION_FAILED
VECTOR_STORE_UNAVAILABLE
VECTOR_STORE_CONTRACT_MISMATCH
VECTOR_OUTCOME_UNKNOWN
RAG_KNOWLEDGE_NOT_READY
RAG_RETRIEVAL_FAILED
RAG_INVALID_CITATION
```

外部异常统一映射为稳定安全消息；原始 cause 仅进入受保护日志。

---

## 18. V0.1 验收

必须证明：

1. 相同文档与参数产生稳定 chunks；
2. vector ID 与精确测试向量一致；
3. 不兼容 embedding profile 被拒绝；
4. parse COMPLETED 但 vector 未完成时 readiness 不是 READY；
5. Agent task snapshot 只包含 READY document generation；
6. Qdrant hit 经 PostgreSQL scope/generation/hash 二次校验；
7. 空结果是合法结果；
8. stale/revoked point 不进入 Prompt；
9. citation 只能引用本次 hit；
10. 当前 vector ID v1 不被误称为支持双 generation 并存；
11. 真实 Qdrant 检索能支持支付诊断 Agent 的完整闭环。
