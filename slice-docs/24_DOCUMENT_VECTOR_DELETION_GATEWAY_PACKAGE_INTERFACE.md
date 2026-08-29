# AgentFlow Hub Knowledge：V23 文档范围向量删除网关

V23 只补齐一个 provider-neutral 的**文档范围向量删除 Gateway**。它为后续“当前 owner 删除文档”与
`COMPLETED` 文档重解析提供必要的外部索引清理原语，但本身不开放文档删除 API，也不改变任何文档、chunk
或文件生命周期。

API 规格中，文档详情与重解析之后的下一个业务接口确实是 `DELETE /api/v1/documents/{documentId}`；路线图也
要求该删除同步清理 chunks 与 vectors。可是现有 `PROCESSING` worker 仍以条件更新完成或失败，不能把这套跨
PostgreSQL、Qdrant 与文件存储的编排压缩成一条软删除 SQL。V22 因而继续把 `COMPLETED` 重解析固定为 409；
V23 先单独建立向量删除能力，尚不实施上述 API。

文件编号为 24，是因为上一份 V22 契约文件编号为 23；本文实现切片版本为 V23。

## 1. 最小领域契约

新增的 scope 位于 `com.agentflow.knowledge.vector`，参数顺序固定为 current owner、knowledge base、
document：

~~~java
public record VectorDocumentScope(
        long userId,
        long knowledgeBaseId,
        long documentId
) {
    public VectorDocumentScope {
        requirePositive(userId, "userId");
        requirePositive(knowledgeBaseId, "knowledgeBaseId");
        requirePositive(documentId, "documentId");
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
~~~

三个 ID 都必须在构造时为正数。`VectorDocumentScope` 是业务层用 canonical owner/知识库/文档记录构造的
内部值对象；它不是 HTTP DTO，也不能由客户端提交的 `userId`、任意 payload filter 或裸 `documentId` 替代。

`VectorStoreGateway` 在保留 V5 的写入和 V7 的检索能力的同时，只新增这一项删除原语：

~~~java
public interface VectorStoreGateway {
    void upsert(VectorStoreRecord record);

    List<VectorSearchHit> search(VectorSearchRequest request);

    void deleteByDocumentScope(VectorDocumentScope scope);
}
~~~

不提供 `deleteByDocumentId(long documentId)`、`deleteByChunkIds(...)`、任意 payload filter、Qdrant SDK 类型或
批量跨文档删除接口。现有独立 chunk 删除调用方不存在；在没有调用者和失败语义前，不能为“也许以后需要”提前扩大
Gateway 表面。

V5 已经把每个向量 point 的 `userId`、`knowledgeBaseId` 与 `documentId` 作为 payload 写入；V23 删除只依赖
这三个服务端生成的字段。`chunkId`、`vectorId`、`contentHash`、模型字段与正文均不是本删除 filter 的条件。

## 2. Adapter 行为契约

### 2.1 `InMemoryVectorStoreGateway`

开发用 adapter 在其现有 `vectorId -> VectorStoreRecord` 内存映射中，只移除 payload 的 `userId`、
`knowledgeBaseId` 与 `documentId` **三个字段都与** `VectorDocumentScope` 相等的 point。三者任一不同的
point（包括同一 owner/知识库下的其他文档）必须保留。

删除不按 `vectorId`、`chunkId` 或相似度排序执行，也不修改保留 point。没有命中时、或对同一 scope 重复调用时，
方法正常返回且不产生副作用，即幂等 no-op。该 adapter 只模拟本地 scope 隔离和幂等删除语义，不持久化、不访问
网络，也不是 Qdrant 行为的线上证明。

### 2.2 `QdrantVectorStoreGateway`

remote adapter 必须使用不创建 collection 的确认路径；若该 Gateway 实例尚未确认 collection，就先只读检查。
已经由同一实例成功确认的 collection 可以直接进入删除，但**删除路径绝不能调用会创建 collection 的
`ensureCollection()`**：

```text
GET /collections/{collection}
  404 -> 正常返回（已确认 collection 不存在，幂等 no-op）
  其他失败 -> 向调用方抛出异常
  存在 -> POST /collections/{collection}/points/delete?wait=true
```

存在 collection 时，删除请求沿用已配置的 Qdrant API key，且 body 只能由 `VectorDocumentScope` 构造：

~~~json
{
  "filter": {
    "must": [
      {"key": "userId", "match": {"value": 101}},
      {"key": "knowledgeBaseId", "match": {"value": 201}},
      {"key": "documentId", "match": {"value": 301}}
    ]
  }
}
~~~

三个 `must` 条件必须同时存在，值必须是 scope 内的服务端 `long` 值；不得只按 `documentId` 删除，也不得将客户端
传入的 filter 透传给 Qdrant。`wait=true` 要求 Qdrant 完成该 point 删除请求后才返回调用方。

collection lookup 的 404 是“已确认不存在”，可安全视为 no-op；其余 collection lookup 或 point 删除的 REST
失败都必须包装为带原始 `RestClientException` cause 的 `IllegalStateException` 并向上传播。已有 collection 的
配置契约错误也必须直接失败，不能降级为 no-op。V23 不在 Gateway 内吞掉错误、自动重试、创建 collection 或伪造
成功；后续业务编排才能据此设计持久补偿。

## 3. 包接口与调用边界

~~~text
ChunkVectorizationService
  -> VectorStoreGateway.upsert(VectorStoreRecord)

KnowledgeRetrievalService
  -> VectorStoreGateway.search(VectorSearchRequest)

V23
  -> VectorStoreGateway.deleteByDocumentScope(VectorDocumentScope)
     -> InMemoryVectorStoreGateway: 仅三字段完全匹配才移除本地 point
     -> QdrantVectorStoreGateway: GET collection（不创建）
          -> POST points/delete?wait=true（userId + knowledgeBaseId + documentId filter）
~~~

V23 不把该方法接入 `KnowledgeDocumentService`、`DocumentProcessingService`、Controller、Mapper 或 HTTP 脚本；
它没有当前业务调用方。未来需要清理文档向量的编排必须先完成 current owner、父知识库、文档可见性和
`PROCESSING` 协议的设计，再从服务器读取的三元范围构造 `VectorDocumentScope`。Gateway 本身不做授权查询，也不
替代 PostgreSQL 的权威生命周期判断。

## 4. 本地 / Mock 验收契约

实现后的聚焦自动化验收至少覆盖：

1. `VectorDocumentScope` 对 `userId`、`knowledgeBaseId`、`documentId` 的零值和负值分别拒绝；三个正数可
   构造稳定 scope。
2. `InMemoryVectorStoreGateway` 预置同 owner/知识库不同 document、不同 owner、不同知识库及完整相同范围的
   point 后，只删除完整三元匹配的项；第二次删除保持正常 no-op。
3. `QdrantVectorStoreGatewayTest.shouldDeleteOnlyTheCurrentOwnerKnowledgeBaseAndDocumentAndWaitForCompletion`
   用 `MockRestServiceServer` 验证 collection 的 GET、
   `POST /collections/{collection}/points/delete?wait=true`、三个 `must` filter 与 `wait=true`。测试中没有
   collection-create request，额外的 `PUT /collections/{collection}` 必须使 mock 验收失败。
4. `shouldTreatAConfirmedAbsentCollectionAsAnIdempotentDeleteNoOp` 验证 collection GET 404 后正常返回，且既不
   发送 point delete，也不创建 collection；`shouldPropagateRemoteDeletionFailuresForLaterCompensation` 验证其余
   远端删除失败仍向调用方传播。
5. 既有 `local` 与显式 `remote` 两套 Spring Gateway 配置测试继续能启动并各自选中
   `InMemoryVectorStoreGateway`、`QdrantVectorStoreGateway`；该启动测试不需要真实 Qdrant 网络连接。

可聚焦运行向量 adapter 相关测试；命令沿用仓库的 JDK 21 与 Mockito javaagent 约定：

~~~text
cd backend
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  mvn -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar \
  -Dtest=InMemoryVectorStoreGatewayTest,QdrantVectorStoreGatewayTest,VectorizationGatewayConfigurationTest,RemoteVectorizationGatewayConfigurationTest test
~~~

以上是 unit/mock 与 Spring 配置验收：它们能证明 scope 参数校验、内存隔离、请求形状、`wait=true`、不建
collection 和异常传播；**不能**证明真实 Qdrant 已执行删除、远端认证/网络可用、生产并发、跨
PostgreSQL/Qdrant 一致性或持久补偿。V23 没有 HTTP 路由，所以不新增 `backend/http/*.http` 的文档删除手工
验收，也不能把 Mock Qdrant 称为真实远端删除验收。

## 5. 明确不做

- `DELETE /api/v1/documents/{documentId}`，任何文档软删除、物理删除、批量删除或管理员跨 owner 删除；
- Flyway migration、表/列/索引/触发器、文档或 chunk 状态写入，以及 PostgreSQL `knowledge_chunk` 删除；
- 原文件、本地/对象存储定位、缓存、Qdrant collection 或 payload index 的创建、删除、迁移或回收；
- `PENDING`、`PROCESSING`、`FAILED` 或 `COMPLETED` 文档的重解析、重新上传、文件替换或自动调用
  `process-pending`；
- `deleteByChunkIds`、delete-by-vector-ID、任意客户端 filter、Gateway 内重试/队列/outbox/补偿；
- Qdrant SDK、rerank、sparse/hybrid vector、模型切换、检索、context、chat、答案审计或 feedback。

## 6. 后续切片的前置依赖

“当前 owner 删除文档”必须另行设计，而不能由 V23 推断为已经具备。该切片至少要明确：

1. 对正在 `PROCESSING` 的文档是返回 409，还是引入可证明的取消/lease 协议；不能让既有 worker 在删除后继续
   条件完成或写失败状态。
2. 文档、父知识库与 owner 的条件可见性，PostgreSQL chunk 清理、文档状态/软删除与原文件删除的顺序和失败边界。
3. PostgreSQL、Qdrant 和文件存储之间的持久补偿记录、重试责任、恢复策略与可审计证据；它们没有分布式事务。

只有该文档删除编排明确使用 V23 的 `deleteByDocumentScope(...)`，并处理 PostgreSQL chunk 与文件侧清理后，才适合
单独设计 `COMPLETED` 文档重解析。V23 不把“具备一个向量删除 Gateway”表述为文档删除、完整重解析或跨存储一致性
已经完成。

## 面试问题与回答

### 问题 1：为什么 V23 用 `userId + knowledgeBaseId + documentId`，而不只传 `documentId`？

**回答：** V5 写入的 Qdrant payload 已有这三个服务端范围字段，V23 必须同时过滤它们，才能把当前 owner、当前知识库
和单一文档一起锁定。裸 `documentId` 即使当前数据库 ID 全局唯一，也会把 Gateway 约束退化为无法表达 owner/知识库
边界的接口；三元 `VectorDocumentScope` 还在构造时拒绝非正数，且不接受客户端自定义 filter。

### 问题 2：为什么 V23 先做 Gateway，却不直接开放文档 DELETE 或 `COMPLETED` 重解析？

**回答：** 向量删除只是完整文档生命周期编排中的一个外部副作用。现有 `DocumentProcessingTransactionService` 会用
`PENDING -> PROCESSING` 与 `PROCESSING -> COMPLETED/FAILED` 的条件更新维护 worker 所有权；直接加一条软删除 SQL
不能解决 worker 竞争、PostgreSQL chunk、原文件和 Qdrant 的顺序与补偿。因此 V23 只建立可复用的删除原语，V22 对
`COMPLETED` 重解析仍保持 409，HTTP 删除和重解析均留给后续独立切片。

### 问题 3：为什么 Qdrant 删除要求 `wait=true`、不得创建 collection，并且 collection 404 可以 no-op？

**回答：** 删除调用要在 Qdrant 确认 point 删除请求完成后再返回，故固定使用 `wait=true`。清理操作不应因资源缺失
反向创建一个空 collection；GET 已确认 404 时，不可能有该 collection 内待删 point，因此安全地作为幂等 no-op。
但其他 inspection 或 delete 失败不能伪装为成功，Gateway 会向调用方暴露原始远端 cause，供后续持久补偿决定是否重试。

### 问题 4：为什么现在不增加 `deleteByChunkIds`？

**回答：** 当前没有独立 chunk 删除业务调用方，V23 的已知需求是整个文档范围的清理。加入 chunk-ID 批量删除会同时引入
输入上限、跨 document scope 校验、部分失败和调用方授权等未被需求驱动的语义。保持单一 `deleteByDocumentScope` 让
当前接口与已持久化 payload 对齐，未来真的出现独立调用方时再以独立契约定义。

### 问题 5：V23 的 Mock 和 Spring 测试能证明什么，不能证明什么？

**回答：** 本地单元测试可证明正数 scope 校验、In-memory 三字段隔离删除与重复 no-op；
`MockRestServiceServer` 可证明 Qdrant 的 GET/DELETE 请求、三项 payload filter、`wait=true`、不创建 collection 和
失败传播；两套 Spring 配置测试可证明 bean 装配。这些都不是线上 Qdrant 删除证明，更不能证明生产认证、网络故障恢复、
PostgreSQL/Qdrant/文件三方原子性或后续文档删除协议；那些能力未纳入 V23。
