# AgentFlow Hub Knowledge：V9 单次可追溯 RAG 回答与 ChatGateway

V9 只在 V8 的稳定 RAG context 之后增加一次受控生成：它调用 V8，原样接收
`KnowledgeContextResponse`，把其中的规范化 `query` 与 **未修改的** `context` 交给一个窄的
`ChatGateway`，再用 V8 已分配的 source 表核对模型回答里的 `[S#]`。V9 不重做检索、预算装配或
citation 编号。

## 1. HTTP 契约

```http
POST /api/v1/knowledge-bases/{knowledgeBaseId}/chat-test
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "query": "退款失败时应该如何排查？",
  "topK": 5,
  "maxContextTokens": 3000,
  "maxAnswerTokens": 512
}
```

- `query`、`topK` 与 V8 相同：query 去首尾空白后必须非空、最多 1000 个 Java 字符；`topK` 可省略，
  由 V8/V7 采用默认 `5`，范围 `1..10`。
- `maxContextTokens` 必填、范围 `1..8000`，完全交给 V8 的既有预算装配。
- `maxAnswerTokens` 必填、范围 `1..4096`。这是 V9 对单次上游输出的明确服务端上限；它不改变 V8
  context 的 token 统计，也不是客户端可选的模型参数。
- 请求 JSON 采用局部严格字段白名单，只接受上述四个字段。`model`、`modelName`、`prompt`、
  `chunkId`、`citationId` 或任何其他字段都会在进入 Service 前以 `400 COMMON_REQUEST_BODY_INVALID`
  拒绝。客户端不能选择模型、覆盖内部指令、指定 chunk，或伪造引用。
- `{knowledgeBaseId}` 与 JWT principal 原样传给 V8。因此 V7 已有的 owner scope、KB 不存在与非
  `ACTIVE` 状态语义保持不变，V9 不复制这些校验。

成功响应的 `data` 示例：

```json
{
  "answer": "先核对支付渠道返回的错误码，再按错误码处理。[S1]",
  "query": "退款失败时应该如何排查？",
  "topK": 5,
  "maxContextTokens": 3000,
  "usedContextTokens": 83,
  "skippedChunkCount": 1,
  "maxAnswerTokens": 512,
  "sources": [
    {
      "citationId": "S1",
      "chunkId": "401",
      "documentId": "301",
      "fileName": "refund-rules.md",
      "titlePath": "支付 / 退款",
      "score": 0.91
    }
  ],
  "citationIds": ["S1"]
}
```

`maxContextTokens`、`usedContextTokens`、`skippedChunkCount`、`query`、`topK` 和 `sources` 都直接来自
同一份 V8 response；V9 不重新估算、不重排 sources，也不把生成文本中的文件名转换为新来源。
`citationIds` 是服务端从 `answer` 解析出的、按第一次出现顺序去重的有效 ID，因此可直接映射到
`sources[].citationId`。

## 2. 依赖链与包接口

```text
KnowledgeChatController
  -> KnowledgeChatService
       -> KnowledgeContextService.retrieveContextTest(...)     (V8)
            -> KnowledgeContextResponse
       -> ChatGateway.generate(ChatRequest)
            -> answer
       -> CitationReferenceExtractor
            -> validated citationIds
```

`KnowledgeChatService` 只依赖 `KnowledgeContextService` 和 `ChatGateway`。调用 V8 后，它仅消费
`KnowledgeContextResponse.query/context/sources` 及其既有统计字段：不 import、不调用 V7、
`EmbeddingGateway`、`VectorStoreGateway`、Qdrant，也不从 `sources` 重建 context。

`ChatRequest` 只有三个输入：V8 已规范化的 `query`、V8 原样 `context`、服务端验证后的
`maxAnswerTokens`。它没有 model、Prompt、chunk 或 citation 字段。`ChatGateway` 只返回原始 answer；
引用解析和来源回传仍在服务层，避免 provider adapter 成为数据权威。

## 3. OpenAI-compatible Gateway

`OpenAiCompatibleChatGateway` 使用 `POST {OPENAI_BASE_URL}/chat/completions`，并绑定既有开发配置：

- `OPENAI_BASE_URL` -> `agentflow.llm.base-url`
- `OPENAI_API_KEY` -> `agentflow.llm.api-key`
- `OPENAI_CHAT_MODEL` -> `agentflow.llm.chat-model`

模型名只从服务端配置读取。Gateway 固定发送一段内部 system instruction，要求模型只依据提供的
context 作答、每个答案至少使用一个 context 中已有的 `[S#]`，并禁止编造来源；再发送包含 query 和
原样 context 的 user message。请求固定 `stream: false` 和 `max_tokens: maxAnswerTokens`，不存在 Prompt
模板管理、客户端温度/模型设置或其他生成调度功能。

若 API key 非空，Gateway 发送 Bearer header；为空时不伪造 header，以兼容不鉴权的本地
OpenAI-compatible 服务。连接、超时、非成功响应、缺失 `choices[0].message.content` 或空回答统一封装为
Gateway failure，绝不把 provider URL、响应体或密钥回传给客户端。

## 4. Context 与 citation 失败边界

1. V8 返回 `sources: []`（等价于没有任何完整 context block）时，V9 立即返回
   `409 KNOWLEDGE_CONTEXT_EMPTY`，**绝不调用 ChatGateway/LLM**。
2. V9 将 V8 `context` 字符串逐字传入 `ChatRequest`，不 trim、摘要、追加 source 表、rerank 或重新编号。
3. 只识别严格的 `[S数字]` 标记。所有解析到的 ID 必须存在于 V8 `sources[].citationId`，并且回答至少要有
   一个有效标记。
4. 无 citation、未知 ID（如 `[S99]`）、或畸形 S 型 citation（如 `[S1, S2]`、`[[S1]]`）返回
   `502 KNOWLEDGE_CHAT_CITATION_INVALID`。失败响应不会把模型编造的 citation/source 作为真实来源回传。
5. Gateway 不可用或返回无效 Chat-completions body 时返回
   `503 KNOWLEDGE_CHAT_GATEWAY_UNAVAILABLE`。V9 不重试生成。

## 5. IDEA HTTP 与 mock 验收

在 `backend/http/knowledge-document.http` 中，先完成 Login -> Create KB -> Upload -> Process -> Vectorize
-> V7 -> V8。只有本地 OpenAI-compatible 服务和三个 `OPENAI_*` 环境变量已配置时，才运行 V9 happy-path
请求；它必须返回 `200`、原始 V8 预算/sources，且每个 `citationIds` 元素都能映射到 source。

不依赖真实模型的自动化 mock 验收覆盖：

1. V8 的 exact `context` 传给 Gateway，V8 预算和 sources 原样回包；
2. 合法且重复的 `[S#]` 按首次出现顺序去重；
3. 空 context 不调用 Gateway；
4. 缺失/伪造/畸形 citation 与 Gateway 不可用均返回稳定受控错误，且无重试；
5. OpenAI-compatible adapter 的 HTTP 路径、服务端模型、固定 instruction、原样 context 与非流式
   `max_tokens`；
6. 禁止字段和越界 `maxAnswerTokens` 在 Controller 层拒绝。

## 6. 明确不做

- 流式/SSE、多轮历史、会话、回答落库、检索日志、异步队列或 Flyway migration；
- Agent/tool calling、Prompt 管理、rerank/hybrid retrieval/query rewrite、重试生成或回答评测；
- V7/V8、Embedding、Qdrant、向量写入或既有 retrieval 的任何改动；
- 将模型回答中的自由文本、文件名或未知 citation 升格为来源事实。
