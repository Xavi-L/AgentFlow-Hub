# AgentFlow Hub Knowledge：V6 真实向量化入库

V6 将 V5 已验收的同步状态机接入真实服务：DashScope `text-embedding-v4` 生成 dense embedding，
Qdrant REST 保存稳定 UUID point。业务层仍只依赖 `EmbeddingGateway` 与 `VectorStoreGateway`，不 import
DashScope 或 Qdrant SDK。

V6 的目标是“真实向量成功写入 Qdrant，PostgreSQL chunk 可观察为 `COMPLETED`”。它不实现语义查询、
query embedding、rerank、Hybrid Search、Agent、自动重试或异步队列。

## 1. 固定模型与 collection 契约

初始 V6 只允许一个模型/collection 组合：

```text
embeddingProvider = dashscope
embeddingModel    = text-embedding-v4
embedding dimension = 1024
Qdrant distance     = Cosine
Qdrant collection   = agentflow_chunks_te_v4_1024
```

`DashScopeEmbeddingGateway` 会拒绝 provider/model 与当前配置不一致的知识库；
`QdrantVectorStoreGateway` 会拒绝维度不等于 `vectorSize` 的向量，并在首次写入时验证既有 collection 的
维度与距离度量。remote mode 启动时还会检查：

```text
DashScope dimensions == Qdrant vectorSize
```

这不是限制未来切换模型，而是防止不同模型或维度混写进同一向量空间。更换模型时必须新建 collection、
更新运行配置、重新向量化对应 chunks；不能复用旧 collection。

`V6__align_knowledge_base_embedding_defaults.sql` 只把**新建**知识库的数据库默认值改为
`dashscope/text-embedding-v4`，不会修改既有知识库。既有 `openai-compatible/text-embedding-v3` 知识库在
remote mode 下会因模型契约不匹配而失败；请新建知识库进行 V6 验收，旧知识库的迁移与重向量化另行设计。

## 2. 本地与部署配置

`backend/src/main/resources/application-dev.yml` 只保留环境变量引用，绝不保存真实密钥：

```yaml
agentflow:
  knowledge:
    vectorization:
      mode: remote
      embedding:
        dashscope:
          base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
          api-key: ${DASHSCOPE_API_KEY:}
          model: ${DASHSCOPE_EMBEDDING_MODEL:text-embedding-v4}
          dimensions: ${DASHSCOPE_EMBEDDING_DIMENSIONS:1024}
  qdrant:
    base-url: ${QDRANT_BASE_URL:http://127.0.0.1:6333}
    api-key: ${QDRANT_API_KEY:}
    collection: ${QDRANT_COLLECTION:agentflow_chunks_te_v4_1024}
    vector-size: ${QDRANT_VECTOR_SIZE:1024}
```

对北京公共云 Workspace，`DASHSCOPE_BASE_URL` 可覆写为：

```text
https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
```

API Key 必须来自本机 IDEA Run Configuration、shell 环境或部署平台的密钥管理，不要发给他人、写进 YAML
或提交 Git。没有 `DASHSCOPE_API_KEY` 时应用可以启动，但实际 `vectorize-pending` 会使被认领的 chunk
受控失败，不会标记为 `COMPLETED`。

## 3. Gateway 实现

```text
ChunkVectorizationService
  -> DashScopeEmbeddingGateway
       POST {baseUrl}/embeddings
       Authorization: Bearer ${DASHSCOPE_API_KEY}
       model/text/dimensions -> exactly one finite 1024-d vector
  -> QdrantVectorStoreGateway
       GET /collections/{collection}
       404 -> PUT /collections/{collection} (size=1024, distance=Cosine)
       PUT /collections/{collection}/points?wait=true
```

Qdrant point ID 继续使用 V5 的 scope + content hash 派生 UUID。`wait=true` 使一次请求在 Qdrant 确认后
再返回；重复写入同一 ID 是覆盖而非新增。payload 仍只携带回查/过滤 metadata，chunk 正文仍以 PostgreSQL
为权威来源。

## 4. Qdrant 本地部署

根目录 `compose.yml` 启动单节点 Qdrant：

```bash
docker compose up -d
curl --noproxy '*' http://127.0.0.1:6333/
```

REST 与 Dashboard 使用 `6333`，gRPC 使用 `6334`。Compose 只绑定 `127.0.0.1`，数据保存至 Docker 命名
卷 `agentflow-qdrant-storage`；本地开发不需 `QDRANT_API_KEY`。若将端口暴露给网络，必须启用 Qdrant
API-key 认证与 TLS，再设置 `QDRANT_API_KEY`。

## 5. 验收路径

1. 确认 Qdrant 正在运行，`curl --noproxy '*' http://127.0.0.1:6333/` 返回版本 JSON。
2. 在启动 AgentFlow 的相同环境设置 `DASHSCOPE_API_KEY`。
3. 通过 `backend/http/knowledge-base.http` 新建一个 `dashscope/text-embedding-v4` 知识库。
4. 上传并处理 TXT/Markdown，使 document `parseStatus=COMPLETED`。
5. 调用 `POST /api/v1/knowledge-bases/{kbId}/chunks/vectorize-pending`。
6. 查看 chunk：`vectorizationStatus=COMPLETED`，同时有 `contentHash` 和 `vectorId`。
7. 调用 Qdrant `GET /collections/agentflow_chunks_te_v4_1024`，确认 collection 为 1024 维 Cosine；按
   `vectorId` 查询 point，确认 payload 的 `chunkId`、`documentId`、`knowledgeBaseId` 与 PostgreSQL 一致。
8. 重复第 5 步，已完成 chunk 必须为 `skipped`，Qdrant point 数不会增加。

## 6. 失败边界

- 缺失/失效 DashScope Key、模型服务非 2xx、响应格式错误或维度不符：chunk 标为 `FAILED`，保存既有受控
  错误类别 `Embedding generation failed`。
- Qdrant 不可达、collection 契约不符或 upsert 失败：chunk 标为 `FAILED`，保存 `Vector store upsert failed`。
- V5 保证 PostgreSQL 与 Qdrant 的部分失败重放使用同一个 `vectorId`；V6 不自动重试 `FAILED` 或回收陈旧
  `PROCESSING`，这些仍是后续独立的可靠性切片。
