# AgentFlow Hub RAG 知识库流程设计

本文档用于沉淀 AgentFlow Hub 的 RAG 知识库设计，包括文档入库 pipeline、解析策略、chunk 策略、embedding、向量存储、在线检索、rerank、引用溯源、Trace 记录、评测口径和 V0.1/V1.0 实现边界。

核心结论：

> RAG 模块不是简单的“上传文档后向量检索”，而是一条可追踪、可调试、可评测的知识处理链路。PostgreSQL 保存权威文本和元数据，Qdrant 保存向量和检索 payload，Agent 执行时通过 RagService 获取带引用的上下文。

正式分块结论：

> 正式版本采用“结构感知语义分块”，而不是单纯按标题切分，也不是完全不受约束的纯语义切分。标题、段落、页码、代码块和表格提供可追踪的结构约束；相邻原子单元的语义相似度决定是否继续合并；estimated-token 上限负责保护上下文预算和运行时资源。

本文的正式策略版本为 `semantic-v1`。该版本将结构解析、语义边界检测、token 约束、版本化和可追踪 metadata 作为统一的分块契约。

---

## 1. 设计目标

RAG 模块需要支撑：

- 用户创建知识库。
- 上传业务文档。
- 文档解析、清洗、切分。
- chunk metadata 保存。
- embedding 批量生成。
- 向量写入 Qdrant。
- 在线 query 检索。
- metadata filter。
- 引用溯源。
- RAG 召回记录。
- RAG 调试和评测。

面试表达目标：

> 我实现的不只是向量检索，而是完整的知识库 pipeline：文档从上传到解析、chunk、embedding、入库、召回、引用、trace 和评测，每一步都能观察和优化。

---

## 2. RAG 模块边界

### 2.1 RAG 模块负责

- query embedding。
- 向量检索。
- metadata filter。
- 召回结果补全 chunk 文本。
- 相似度阈值过滤。
- rerank，可选。
- RAG context 构造。
- citation 构造。
- 检索日志记录。
- RAG 调试接口。

### 2.2 RAG 模块不负责

- 用户权限认证。
- 原始文件上传。
- MinIO 文件管理。
- Agent 状态机。
- 工具调用。
- 最终答案生成。

相关职责归属：

| 能力 | 所属模块 |
| --- | --- |
| 文档上传 | `knowledge` |
| 文档解析和切分 | `knowledge.parser` / `knowledge.chunk` |
| embedding 调用 | `infra.embedding` / `LlmGateway` |
| 向量读写 | `infra.vector` / `VectorStoreGateway` |
| 在线检索编排 | `rag` |
| Agent 中使用 RAG | `agent` 调用 `rag` |
| RAG trace | `trace` |

---

## 3. 总体流程

RAG 分为两条链路：

1. **离线入库链路**
   - 文档上传后，异步解析、切分、embedding、写入向量库。

2. **在线检索链路**
   - 用户提问时，生成 query embedding，检索 Qdrant，补全文本，构造上下文和引用。

```mermaid
flowchart TD
    subgraph Ingestion["离线入库链路"]
        A["上传文档"] --> B["保存原始文件到 MinIO"]
        B --> C["创建 document 记录"]
        C --> D["解析文本"]
        D --> E["文本清洗"]
        E --> F["提取原子单元"]
        F --> G["语义边界检测与分组"]
        G --> H["保存最终 chunk 到 PostgreSQL"]
        H --> I["生成 chunk embedding"]
        I --> J["写入 Qdrant"]
        J --> K["更新文档状态 COMPLETED"]
    end

    subgraph Retrieval["在线检索链路"]
        Q["用户问题"] --> R1["生成 query embedding"]
        R1 --> R2["Qdrant 向量召回"]
        R2 --> R3["按 chunkId 查询 PostgreSQL 文本"]
        R3 --> R4["阈值过滤 / rerank"]
        R4 --> R5["构造 RAG Context"]
        R5 --> R6["生成 citations"]
        R6 --> R7["写入 RAG trace"]
    end
```

---

## 4. 核心组件

### 4.1 DocumentIngestionService

职责：

- 接收文档解析任务。
- 更新 document 状态。
- 调用 parser、cleaner、chunker。
- 批量保存 chunks。
- 调用 embedding。
- 写入 Qdrant。
- 处理失败和重试。

### 4.2 DocumentParser

接口：

```java
public interface DocumentParser {
    boolean supports(DocumentType type);

    ParsedDocument parse(DocumentParseCommand command);
}
```

V1.0 实现：

| Parser | 文件类型 | 说明 |
| --- | --- | --- |
| `TextDocumentParser` | `.txt` | 按纯文本读取 |
| `MarkdownDocumentParser` | `.md` | 识别标题层级 |
| `PdfDocumentParser` | `.pdf` | 使用 PDFBox 按页提取文本 |

### 4.3 TextCleaner

职责：

- 统一换行。
- 去除过多空行。
- 去除首尾空白。
- 合并异常空格。
- 过滤过短噪声段落。
- 保留标题结构。

V1.0 不做复杂页眉页脚识别，只做轻量清洗。

### 4.4 DocumentChunker

职责：

- 将解析结果拆成可追踪的原子单元。
- 以标题、页码、代码块和表格作为结构约束，而不是机械的唯一切分依据。
- 基于相邻原子单元的语义相似度识别主题边界并聚合最终 chunk。
- 控制目标大小、最小大小、最大大小和语义单元级 overlap。
- 生成 `titlePath`、`chunkIndex`、来源 block 范围和策略版本等 metadata。
- 使用 token 估算作为上下文预算和运行时安全边界。

### 4.5 EmbeddingService

职责：

- 批量调用 embedding API，为原子单元边界检测和最终 chunk 向量化提供向量。
- 控制 batch size。
- 处理模型调用失败。
- 返回语义边界计算所需的相似度输入，以及最终 `chunkId` 到 vector 的映射。
- 记录 embedding model 和策略版本，避免不同模型生成的 chunk 边界或向量被混用。

### 4.6 VectorStoreGateway

职责：

- upsert chunks 到 Qdrant。
- 按 query vector 搜索。
- 删除指定 document/chunk 的向量。
- 屏蔽 Qdrant SDK 细节。

### 4.7 RagService

职责：

- 接收检索请求。
- 调用 query embedding。
- 调用 VectorStoreGateway。
- 根据命中的 chunkId 查询 PostgreSQL。
- 阈值过滤和 rerank。
- 构造 context 和 citations。
- 写入 rag retrieval trace。

---

## 5. 文档入库流程

### 5.1 上传阶段

用户上传文档时：

1. 校验用户是否有知识库权限。
2. 校验文件类型。
3. 计算文件 hash。
4. 上传原始文件到 MinIO。
5. 创建 `knowledge_document`，状态为 `PENDING`。
6. 投递文档解析任务。

V0.1 可同步处理，V1.0 使用 RabbitMQ 异步处理。

### 5.2 解析阶段

Worker 消费任务后：

1. 将文档状态改为 `PROCESSING`。
2. 从 MinIO 读取原始文件。
3. 根据 `file_type` 选择 parser。
4. 输出 `ParsedDocument`。

`ParsedDocument` 建议结构：

```json
{
  "title": "payment-error-guide.md",
  "blocks": [
    {
      "type": "HEADING",
      "level": 1,
      "text": "支付失败处理"
    },
    {
      "type": "PARAGRAPH",
      "text": "E_PAY_TIMEOUT 表示支付网关响应超时...",
      "pageNo": null
    }
  ],
  "metadata": {
    "fileName": "payment-error-guide.md",
    "fileType": "MD"
  }
}
```

### 5.3 清洗阶段

清洗规则：

- `\r\n` 统一为 `\n`。
- 连续 3 个以上空行压缩为 2 个。
- 段落首尾 trim。
- 去除纯页码行，例如 `1`、`Page 1`。
- 保留 Markdown 标题。
- 保留 PDF 页码信息。

不做：

- OCR。
- 表格结构化恢复。
- 复杂页眉页脚检测。
- 语义纠错。

### 5.4 正式切分阶段：结构感知语义分块

正式版本不采用“标题一到就切一个 chunk，超长段落再硬切”的单一策略，也不允许语义模型无视文档结构自由拼接。分块器按照“原子单元 -> 结构约束 -> 语义聚合 -> token 安全边界”的顺序工作。

#### 5.4.1 原子单元

解析器先将文档转换为可独立追踪的原子单元，每个单元至少包含正文、类型、起止位置和来源 metadata：

- 标题：记录标题文本、层级和当前 `titlePath`。
- 段落：作为 TXT、Markdown 正文和 PDF 文本的主要语义单元。
- 列表、引用和相邻短段落：在语义完整时作为一个候选单元。
- fenced code、代码片段和表格：默认保持整体，不与普通说明文字跨类型合并。
- PDF 页码、文本块和版面位置：保留为来源 metadata；页码默认不是强制 chunk 边界。

原子单元只做规范化和结构解析，不改写原文，不通过 LLM 做语义纠错。扫描 PDF 必须先经过 OCR 或其他文本提取流程，不能把空文本直接交给语义分块器。

#### 5.4.2 结构约束是软边界

- Markdown 标题用于提供上下文和候选边界，`titlePath` 必须继承到其后的正文 chunk。
- 标题层级跳跃、缺失或使用不一致时，不把层级当作绝对真相；标题仍保留为 provenance metadata，由语义边界决定是否真的切分。
- PDF 页码用于引用和回溯。一个主题跨页时允许跨页合并；页码变化必须进入 `pageStart` / `pageEnd`。
- 代码块、表格、列表等具有自身结构的单元默认不被普通段落的相似度计算拆散；当它们超过最大 chunk 大小时，使用类型专属的安全切分规则。

#### 5.4.3 语义边界检测与聚合

1. 对段落或句子级原子单元生成用于边界检测的 embedding；该 embedding 只用于判断相邻内容是否仍属于同一主题。
2. 按文档顺序计算相邻单元或滑动窗口之间的语义相似度，识别主题突变点。
3. 相似度足够高且未超过目标大小时继续合并；出现明显主题突变，或继续合并会超过目标大小时，在当前边界结束 chunk。
4. 语义边界阈值必须跟 embedding 模型和评测集绑定，通过检索评测标定，不写死一个跨模型通用的常数。
5. 如果单个段落或结构单元本身超过最大大小，先在句子边界切分；只有没有可用句子边界时才使用 token 边界硬切。
6. 如果一个候选 chunk 小于最小大小，优先与语义最接近且结构允许的相邻单元合并，避免产生只有标题、半句话或单个列表项的碎片。

#### 5.4.4 Overlap 规则

Overlap 优先复制完整的上一个语义单元或句子，而不是从任意字符中间截取。默认目标为最后一个或两个语义单元，且不超过 `chunkOverlap` 的 estimated-token 上限；若单个单元已经超过上限，则按照句子边界或 token 安全边界处理。

这样既能保留跨 chunk 的上下文连续性，又不会因为机械 token overlap 把标题、表格行或代码语句截断。

#### 5.4.5 各格式策略

| 格式 | 正式策略 |
| --- | --- |
| `.txt` | 以段落为主要原子单元，按相邻主题相似度合并；日志、配置和记录型 TXT 使用行/记录边界，不使用普通 prose 语义规则 |
| `.md` | 标题作为软边界和 `titlePath` 来源；标题层级松散时由语义变化决定边界；代码块、表格和列表保持结构完整 |
| `.pdf` | 先按页面和版面提取段落/文本块，再按语义聚合；保留页码和位置用于 citation，不把每页机械作为一个 chunk |

#### 5.4.6 正式默认参数

```text
chunkStrategyVersion = semantic-v1
targetChunkSize = 800 estimated tokens
chunkOverlap = 120 estimated tokens
minChunkSize = 80 estimated tokens
maxChunkSize = 1000 estimated tokens
semanticBoundaryThreshold = per embedding model and evaluation set
```

`targetChunkSize` 是聚合目标，不是必须达到的硬长度；`maxChunkSize` 是硬上限；`minChunkSize` 是防止碎片化的下限。无论语义模型如何判断，所有 chunk 都必须遵守最大源文档大小、最大 chunk 数和 token 安全限制。

### 5.5 token 估算策略

token 估算只负责预算控制、最大长度保护和 overlap 上限，不负责替代语义边界判断。正式版本的语义边界由原子单元 embedding 和评测标定的边界策略决定。

正式版本可以使用轻量估算：

```text
中文字符：约 1 token
英文单词：约 1 token
数字和符号：按简单规则估算
```

示例接口：

```java
public interface TokenEstimator {
    int estimate(String text);
}
```

后续如果需要更精确，可以替换为模型 tokenizer；替换 tokenizer 或语义边界模型时必须提升 `chunkStrategyVersion`，并重新生成 chunk embedding，不能静默改变已有 chunk 的含义。

### 5.6 chunk metadata

每个 chunk 保存：

- `chunkIndex`
- `content`
- `contentHash`
- `titlePath`
- `charCount`
- `tokenCount`
- `chunkStrategyVersion`
- `metadata`

metadata 示例：

```json
{
  "fileName": "payment-error-guide.md",
  "fileType": "MD",
  "headingLevel": 2,
  "pageNo": null,
  "startBlockIndex": 3,
  "endBlockIndex": 8,
  "pageStart": null,
  "pageEnd": null,
  "boundaryStrategy": "semantic-v1"
}
```

### 5.7 embedding 和向量写入

推荐流程：

1. parser 生成带来源 metadata 的原子单元。
2. 批量生成原子单元 embedding，执行语义边界检测和 chunk 聚合。
3. 批量插入最终 `knowledge_chunk`，初始 `vectorization_status = PENDING`，并保存精确 UTF-8 正文的 `content_hash`。
4. 用 `userId + knowledgeBaseId + documentId + chunkIndex + contentHash` 派生稳定、Qdrant 可接受的 UUID `vector_id`。
5. 批量生成最终 chunk embedding。
6. 按 `vector_id` 批量 upsert 到 Qdrant。
7. 将该 chunk 的 `vectorization_status` 更新为 `COMPLETED` 并保存 `vector_id`；document 的解析状态保持为自己的独立事实。

原因：

- PostgreSQL chunk 与 Qdrant point 一一对应，且相同正文出现在不同文档/位置时不会错误争用一个 point。
- 删除和回溯简单。
- trace 中可以通过 chunkId 回到原文。
- 原子单元 embedding 与最终 chunk embedding 的职责分离，边界检测和在线检索不会混用两类向量。

### 5.8 入库一致性策略

PostgreSQL 和 Qdrant 不在同一个事务中，需要做补偿。

策略：

- 文档解析状态与 chunk 向量化状态分离：已完成解析的 chunk 依次经过 `PENDING → PROCESSING → COMPLETED / FAILED`。
- 向量化失败写入受控的 `vectorization_error`，不会回写或伪造 document 的 `parse_status`。
- 重新解析时，先删除旧 chunks 和旧 vectors，再重新入库。
- Qdrant upsert 使用由 scope + content hash 派生的稳定 `vector_id`，因此重放可以安全覆盖同一点；已完成状态可直接跳过。
- 如果 Qdrant upsert 成功但 PostgreSQL 状态更新失败，后续补偿仍使用相同 `vector_id`，不会制造重复 point。

---

## 6. Qdrant 设计

### 6.1 Collection

使用一个 collection：

```text
kb_chunks
```

### 6.2 Vector ID

```text
content_hash = SHA-256(exact UTF-8 chunk content)
vector_id = UUIDv8(SHA-256(
  "agentflow-knowledge-vector-v1" + user_id + knowledge_base_id +
  document_id + chunk_index + content_hash
))
```

`content_hash` 让正文变化产生新的 point identity；owner/知识库/文档/顺序范围避免两个不同
chunk 恰好正文相同而互相覆盖。UUID 只作为 Qdrant point ID，`chunk_id` 仍保存在 payload 中用于
回查 PostgreSQL 权威正文。

### 6.3 Payload

```json
{
  "user_id": "123",
  "knowledge_base_id": "456",
  "document_id": "789",
  "chunk_id": "10001",
  "chunk_index": 3,
  "file_name": "payment-error-guide.md",
  "file_type": "MD",
  "title_path": "支付失败/错误码",
  "content_hash": "..."
}
```

设计原则：

- payload 用于过滤和轻量展示。
- chunk 正文以 PostgreSQL 为准。
- 不把长文本正文作为 Qdrant 的权威数据源。

### 6.4 Filter

Agent 检索时必须过滤：

```text
user_id = currentUserId
knowledge_base_id IN agent.boundKnowledgeBaseIds
```

可选过滤：

```text
document_id IN selectedDocumentIds
file_type IN selectedFileTypes
```

---

## 7. 在线检索流程

### 7.1 RagQuery

建议结构：

```json
{
  "userId": "123",
  "taskId": "30001",
  "stepId": "70001",
  "query": "order_1024 支付失败的原因是什么？",
  "knowledgeBaseIds": ["456"],
  "topK": 5,
  "similarityThreshold": 0.2,
  "useRerank": false
}
```

### 7.2 检索步骤

1. 校验知识库归属和状态。
2. 调用 embedding 生成 query vector。
3. 使用 Qdrant 搜索 topK。
4. 按 payload filter 限定用户和知识库。
5. 根据 chunkId 查询 PostgreSQL。
6. 过滤已删除文档或无效 chunk。
7. 按相似度阈值过滤。
8. 可选 rerank。
9. 构造 `RagResult`。
10. 写入 `rag_retrieval_log` 和 `rag_retrieval_hit`。

### 7.3 RagResult

建议结构：

```json
{
  "retrievalId": "40001",
  "query": "order_1024 支付失败的原因是什么？",
  "hits": [
    {
      "chunkId": "10001",
      "documentId": "101",
      "knowledgeBaseId": "456",
      "fileName": "payment-error-guide.md",
      "titlePath": "支付失败/错误码",
      "score": 0.8421,
      "rerankScore": null,
      "content": "E_PAY_TIMEOUT 表示支付网关响应超时..."
    }
  ],
  "contextText": "...",
  "citations": [],
  "latencyMs": 86
}
```

---

## 8. Rerank 策略

### 8.1 V0.1

不做 rerank。

只使用：

- query embedding。
- Qdrant topK。
- similarity threshold。

### 8.2 V1.0

Rerank 作为“应该做”能力。

实现方式：

- 先向量召回较大的候选集，例如 topK=20。
- 调用 rerank API。
- 取前 5 个进入最终 context。

### 8.3 V1.5

Rerank 作为正式增强项：

- 在知识库配置中启用或禁用。
- 保存 rerank score。
- 评测对比 rerank 前后命中率。

### 8.4 为什么不一开始强制 rerank

原因：

- 增加模型调用成本。
- 增加接口依赖。
- V0.1 阶段更重要的是打通完整闭环。
- 可以先通过可观测数据发现是否需要 rerank。

---

## 9. Hybrid Search 策略

### 9.1 V1.0 边界

Hybrid Search 属于“应该做”，不是 V0.1 必须项。

推荐实现：

- PostgreSQL 全文检索或简单关键词匹配。
- Qdrant 向量召回。
- 两路结果融合。

### 9.2 简化融合策略

可以使用 RRF：

```text
score = 1 / (k + vectorRank) + 1 / (k + keywordRank)
```

V1.0 如果时间不足，可以先只做向量检索，把 Hybrid Search 放到 V1.5。

### 9.3 适用场景

Hybrid Search 对这些问题更有帮助：

- 错误码。
- 订单状态枚举。
- API 名称。
- 精确术语。
- 日志关键字。

例如：

```text
E_PAY_TIMEOUT
PAY_FAILED
refund_status
```

纯向量检索可能弱于关键词精确匹配。

---

## 10. Context 构造

### 10.1 目标

RAG context 要让模型：

- 知道每段内容来自哪里。
- 能区分知识库内容和工具结果。
- 能在最终回答中引用来源。
- 不把无关 chunk 塞满上下文。

### 10.2 Context 格式

推荐格式：

```text
[Document 1]
Source: payment-error-guide.md
Title: 支付失败/错误码
ChunkId: 10001
Content:
E_PAY_TIMEOUT 表示支付网关响应超时，通常需要检查支付渠道状态、重试记录和用户扣款状态。

[Document 2]
Source: refund-policy.md
Title: 退款规则/支付超时
ChunkId: 10008
Content:
如果支付状态为 PAY_FAILED 且用户未扣款，应提示用户重新支付；如果扣款成功但订单失败，需要创建退款工单。
```

### 10.3 Context Budget

建议预算：

```text
单次 Agent 任务总 maxTokens: 8000
RAG context 预算: 2500 到 3500 tokens
工具观察预算: 1500 到 2500 tokens
最终回答预算: 1000 到 2000 tokens
```

Context 构造时：

- 按 score/rerankScore 排序。
- 逐个加入 chunk。
- 超过 RAG context 预算则停止。
- 同一文档连续 chunk 可合并展示，但 trace 仍保存各 chunk。

---

## 11. 引用溯源

### 11.1 Citation 数据结构

```json
{
  "citationId": "C1",
  "chunkId": "10001",
  "documentId": "101",
  "fileName": "payment-error-guide.md",
  "titlePath": "支付失败/错误码",
  "score": 0.8421
}
```

### 11.2 最终回答引用格式

建议模型最终回答中使用：

```text
根据支付失败错误码说明，E_PAY_TIMEOUT 通常表示支付网关响应超时 [C1]。
```

前端可以将 `[C1]` 渲染为可点击引用，点击后展示 chunk 内容。

### 11.3 引用原则

- 引用只来自实际召回 chunk。
- 不允许模型编造不存在的引用编号。
- 后端可以在最终答案后附带 citations 列表。
- 如果模型没有引用，但使用了知识库内容，前端仍展示召回来源。

---

## 12. RAG 与 Agent 的关系

### 12.1 V1.0 默认模式

Agent 执行采用：

> 前置 RAG + 工具调用循环。

流程：

1. AgentEngine 接收用户任务。
2. AgentEngine 调用 RagService 进行前置检索。
3. RAG 结果进入 `AgentExecutionContext.retrievedChunks`。
4. Thinking Prompt 中包含 Knowledge Context。
5. 模型决定是否调用业务工具。
6. 最终回答同时基于知识库内容和工具 observations。

### 12.2 knowledge_search 工具

V1.0 内置 `knowledge_search` 工具，但可以先不作为默认路径。

用途：

- 当模型发现前置检索不够时，主动发起二次检索。
- 适合复杂多跳问题。

实现建议：

- V0.1 不启用。
- V1.0 作为可绑定工具保留。
- V1.5 再优化主动检索策略。

---

## 13. Trace 记录

### 13.1 rag_retrieval_log

记录一次检索：

- taskId。
- stepId。
- userId。
- query。
- knowledgeBaseIds。
- topK。
- similarityThreshold。
- useRerank。
- latencyMs。

### 13.2 rag_retrieval_hit

记录每个命中：

- retrievalId。
- chunkId。
- documentId。
- knowledgeBaseId。
- rankNo。
- score。
- rerankScore。
- contentSnapshot。
- metadataSnapshot。

### 13.3 为什么保存 contentSnapshot

原因：

- 文档可能被删除。
- 文档可能被重新解析。
- chunk 内容可能变化。
- 历史 Agent trace 仍然需要可回放。

这是项目工程化亮点之一。

---

## 14. RAG 调试能力

V1.0 应提供知识库检索测试接口：

```text
POST /api/v1/knowledge-bases/{kbId}/retrieve-test
```

调试页面应展示：

- query。
- topK。
- similarityThreshold。
- useRerank。
- 命中 chunk。
- score。
- rerankScore。
- document。
- titlePath。
- chunk 内容。
- latencyMs。

调试目标：

- 判断文档是否成功入库。
- 判断 chunk 是否切得合理。
- 判断 query 是否能召回相关内容。
- 调整 topK、threshold、chunkSize。

---

## 15. 评测口径

### 15.1 RAG 检索评测

评测 case 字段：

- question。
- expectedDocumentIds。
- expectedAnswer，可选。
- expectedToolCodes，可选。

RAG 指标：

| 指标 | 说明 |
| --- | --- |
| Hit@K | topK 中是否命中预期文档 |
| MRR | 预期文档首次出现排名的倒数 |
| Average Score | 命中 chunk 平均相似度 |
| Citation Accuracy | 最终回答引用是否来自预期文档 |

这些指标后续由 Evaluation Harness 聚合，并可回溯到对应 Agent Episode Package。

V1.0 可以先实现：

- Hit@K。
- 是否命中预期文档。
- 人工通过/不通过。

### 15.2 Agent 任务评测中的 RAG

Agent 评测还要看：

- 是否检索了正确知识库。
- 是否命中预期文档。
- 是否调用了预期工具。
- 最终答案是否引用证据。
- 关联 episode 后能否回放完整 RAG、LLM、工具和策略检查过程。

---

## 16. 失败处理

### 16.1 入库失败

可能失败：

- 文件类型不支持。
- PDF 解析失败。
- embedding API 调用失败。
- Qdrant 写入失败。
- 数据库写入失败。

处理：

- document 状态改为 `FAILED`。
- `parse_error` 写入失败原因。
- 支持用户点击重新解析。
- reprocess 前清理旧 chunk 和向量。

### 16.2 检索失败

可能失败：

- embedding API 调用失败。
- Qdrant 查询失败。
- PostgreSQL chunk 查询失败。
- rerank API 失败。

处理：

- 记录错误。
- 如果 RAG 是 Agent 必需环节，任务可失败。
- 如果只是辅助环节，可返回空上下文并让 Agent 说明知识库未检索到信息。

推荐：

- V0.1 中 RAG 失败直接让任务失败。
- V1.0 中 RAG 召回为空不算失败，RAG 服务异常才失败。

### 16.3 召回为空

召回为空时：

- 写入 `rag_retrieval_log`。
- hits 为空。
- SSE 发送 `RAG_FINISHED`，topK=0。
- Agent 继续尝试工具调用或说明知识库未找到依据。

---

## 17. 性能与批处理

### 17.1 文档入库

建议：

- embedding 批量调用，batch size 16 到 64。
- 大文档分批写入 chunk。
- Qdrant 批量 upsert。
- 文档解析异步执行。
- 对同一 document 加锁，避免重复解析。

### 17.2 在线检索

建议：

- query embedding 可以短期缓存。
- 热门知识库检索结果可缓存。
- topK 不宜过大，默认 5。
- rerank 候选数量不宜过大，默认 20。
- context 构造必须有 token budget。

### 17.3 默认参数

```text
chunkSize: 800 estimated tokens
chunkOverlap: 120 estimated tokens
retrievalTopK: 5
candidateTopKForRerank: 20
similarityThreshold: 0.2
ragContextBudget: 3000 estimated tokens
embeddingBatchSize: 32
```

---

## 18. V0.1 实现边界

V0.1 必须实现：

- `.txt` / `.md` 文档上传。
- 简单 PDF 解析可选。
- 文本清洗。
- 结构感知语义 chunk 切分；在语义 embedding 不可用时，才允许使用可观测的确定性基线兜底。
- embedding 生成。
- Qdrant upsert。
- 知识库检索测试。
- Agent 前置 RAG。
- RAG 召回写入 trace。
- 最终回答带来源信息。

V0.1 可以简化：

- 文档解析同步执行。
- 不做 rerank。
- 不做 Hybrid Search。
- 不做复杂 PDF 版面处理。
- 不做 OCR。
- 不做主动二次检索工具。

---

## 19. V1.0 完成标准

V1.0 RAG 应支持：

1. 用户创建多个知识库。
2. 支持 `.txt`、`.md`、`.pdf`。
3. 原始文件保存到 MinIO。
4. 文档解析异步执行。
5. 文档状态可查看。
6. chunk 可查看。
7. chunk 带 `titlePath`、`tokenCount`、来源 block 范围、`chunkStrategyVersion` 和其他 metadata。
8. embedding 写入 Qdrant。
9. 支持按用户和知识库过滤。
10. 支持检索测试。
11. Agent 执行时自动前置 RAG。
12. RAG 结果进入 Prompt。
13. 最终回答返回引用来源。
14. 每次 RAG 召回都可在 Trace 中回放。
15. 评测模块能判断是否命中预期文档。

---

## 20. V1.5 增强项

推荐增强：

- 语义边界阈值和 chunk 策略的评测调参。
- Rerank。
- Hybrid Search。
- Query Rewrite。
- 主动 `knowledge_search` 工具。
- 接入 Evaluation Harness 做 RAG 参数对比评测。
- chunk overlap 可视化调试。
- 文档解析失败自动重试。
- PDF 页码引用。
- 检索结果缓存。
- 更精确 tokenizer。

---

## 21. 面试表达重点

RAG 设计可以这样讲：

1. **数据和向量解耦**
   - PostgreSQL 保存 chunk 正文和元数据，Qdrant 保存向量和 payload，通过 chunkId 关联。

2. **不是黑盒检索**
   - 文档入库经过解析、清洗、原子单元提取、语义边界检测、chunk 聚合、embedding 和向量写入，每一步都有状态和失败处理。

3. **引用可追踪**
   - 最终答案带 citations，Trace 保存每次召回的 chunk 快照。

4. **可调试**
   - 提供 retrieve-test 接口，可以观察 query 命中的 chunk、score、来源和耗时。

5. **可评测**
   - 评测集中保存 expectedDocumentIds，可以计算是否命中预期文档。

6. **为工程化留余地**
   - 后续可以加入 rerank、Hybrid Search、Query Rewrite，而不需要推翻现有结构。

---

## 22. 当前不做的内容

V1.0 暂不做：

- OCR。
- 多模态文档解析。
- Word/Excel/PPT 复杂解析。
- Graph RAG。
- 自动知识图谱。
- 本地 embedding 模型部署。
- 大规模分布式索引。

这些内容可作为 V2.0 扩展，不进入当前核心闭环。
