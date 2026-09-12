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

## 面试问题与回答

### 问题 1：为什么 V6 要固定 `dashscope/text-embedding-v4`、1024 维和一个 Cosine collection？以后换模型怎么办？

**回答：** 已实现的 V6 把模型、维度、距离度量和 collection 当作同一份向量空间契约：remote mode 启动时会比较
DashScope dimensions 与 Qdrant `vectorSize`，Gateway 还会校验知识库 provider/model、返回向量维度及既有
collection 的 1024 维 Cosine 配置。这样避免不同语义空间的向量混写后产生不可解释的相似度。`V6` migration
只改新建知识库的默认值，不会迁移旧数据；更换模型或维度必须新建 collection 并显式重新向量化，旧知识库迁移
不属于本切片。

### 问题 2：`vectorize-pending` 如何处理重复调用、并发与 PostgreSQL/Qdrant 的部分失败？

**回答：** 已实现流程先以条件更新在短事务中认领仍为 `PENDING` 的 chunk，再在事务外调用 embedding 与 Qdrant，最后
在独立短事务写回 `COMPLETED` 或受控 `FAILED`，因此不会在外部 HTTP 调用期间持有数据库事务。`vectorId` 由
user、知识库、document、chunkIndex 和正文 hash 稳定派生，Qdrant 用 `wait=true` 对同一 ID upsert；已完成的
chunk 会被跳过。这降低了重复写入风险，但不等于分布式事务：`FAILED` 自动重试和陈旧 `PROCESSING` 回收明确未纳入
本切片。

### 问题 3：为什么 PostgreSQL 仍是正文权威，而不是直接把 Qdrant payload 当作知识内容？

**回答：** 当前实现把 Qdrant 用作向量索引和回查/过滤 metadata 存储，写入 payload 不含 chunk 正文；正文、生命周期状态、
`contentHash` 和当前 `vectorId` 仍以 PostgreSQL 为准。这样 V7 以后可以用 PostgreSQL 重读并验证 Qdrant hit 是否
仍对应当前 chunk，避免过期 point、删除后的数据或错误 payload 被直接暴露。Qdrant 只承担本切片约定的向量写入，
不是第二份内容真相源。

### 问题 4：怎样说明 V6 已验证到什么程度，而不把 mock 或配置当成真实云服务验收？

**回答：** 已有自动化测试验证 remote mode 的 Bean 选择和维度不匹配拒绝，并用 `MockRestServiceServer` 检查
DashScope/Qdrant 请求、collection 契约、稳定 upsert 和失败分支；这些是本地/mock 契约验收，不是 DashScope 或
Qdrant 的真实连通性证明。真实验收仍需在实际运行的 Qdrant、同一应用进程注入的 `DASHSCOPE_API_KEY`、新建的
V6-compatible 知识库及已完成解析的文档条件下调用 `vectorize-pending`，再同时核对 PostgreSQL 状态与 Qdrant point。
