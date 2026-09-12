# AgentFlow Hub Knowledge：V21 当前 owner 的单文档元数据详情

V21 在 V20 的知识库软删除之后补齐单文档详情：当前认证用户可读取自己、尚未软删除，且父知识库也尚未软删除的一条文档元数据。它是只读切片，复用既有安全输出 `KnowledgeDocumentResponse`，不把重解析、删除或原始文件读取混入该接口。

文件编号为 22，是因为上一份 V20 契约文件编号为 21；本文实现切片版本为 V21。

## 1. HTTP 契约

~~~http
GET /api/v1/documents/{documentId}
Authorization: Bearer <access-token>
~~~

路径中的 `documentId` 只来自 URL，`AuthenticatedUser` 只来自 JWT principal；请求没有 body，因此客户端不能提交或控制 `userId`、知识库 ID、存储定位信息、解析状态或可见性条件。

成功时返回 HTTP 200、`ApiResponse<KnowledgeDocumentResponse>` 和固定消息 `Document retrieved`：

~~~json
{
  "code": "OK",
  "message": "Document retrieved",
  "data": {
    "id": "301",
    "knowledgeBaseId": "201",
    "fileName": "refund-rules.md",
    "fileType": "MD",
    "fileSize": 7,
    "parseStatus": "PENDING",
    "createdAt": "2026-08-29T12:00:00+08:00",
    "updatedAt": "2026-08-29T12:00:00+08:00"
  },
  "traceId": "...",
  "timestamp": "..."
}
~~~

不存在、其他 owner 的文档、已软删除文档，以及父知识库已软删除的文档，统一返回 `COMMON_NOT_FOUND` / HTTP 404 和 `Document not found`：

~~~json
{
  "code": "COMMON_NOT_FOUND",
  "message": "Document not found",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
~~~

这里没有知识库 `status` 条件。`ACTIVE` 与 `DISABLED` 都是未删除 owner 可管理的知识库状态，因此两者下的历史文档都可读取；只有上传新文件仍要求 `ACTIVE`。

## 2. 包接口

~~~text
KnowledgeDocumentDetailController
  GET /api/v1/documents/{documentId}
    -> KnowledgeDocumentService.getOwnedById(currentUser, documentId)
         -> KnowledgeDocumentMapper.selectVisibleOwnedById(documentId, currentUser.id)
              -> one joined document/knowledge-base visibility query
         -> KnowledgeDocumentResponse.from(document)
    -> ApiResponse<KnowledgeDocumentResponse>
~~~

1. `KnowledgeDocumentDetailController.get(...)` 只传递 JWT principal 与路径 ID，成功消息固定为 `Document retrieved`；它不接收请求体，也不打开文件。
2. `KnowledgeDocumentService.getOwnedById(...)` 是只读事务。它不调用 `KnowledgeBaseMapper` 做先行查询，避免把父资源可见性拆成可漂移的多次读取；JOIN 无匹配行统一转换为 `COMMON_NOT_FOUND` / `Document not found`。
3. `KnowledgeDocumentMapper.selectVisibleOwnedById(...)` 是专用 `@Select`，把文档 ID、当前 owner、文档未删除和父知识库未删除放入一条 SQL。它不按 `status`、`parse_status` 或 chunk 状态过滤。
4. `KnowledgeDocumentResponse.from(...)` 是唯一对外映射。Mapper 可以读取持久化实体以支撑服务器内部处理，但 Controller 不会把实体直接序列化给客户端。

## 3. 数据可见性与 SQL 契约

实际详情查询保持以下条件：

~~~sql
SELECT kd.*
FROM knowledge_document kd
INNER JOIN knowledge_base kb
    ON kb.id = kd.knowledge_base_id
    AND kb.user_id = kd.user_id
WHERE kd.id = #{documentId}
  AND kd.user_id = #{currentUser.id}
  AND kd.deleted_at IS NULL
  AND kb.deleted_at IS NULL
~~~

`knowledge_document` 的复合外键已经要求写入时父知识库和文档 owner 一致；这里仍显式把 `kb.user_id = kd.user_id` 放在 JOIN 中，并以 `kd.user_id = currentUser.id` 收口读取范围。这样即使后续数据修复、手工查询或实现演进出现异常父子关系，也不会把不一致行作为当前用户的详情返回。

`INNER JOIN` 与两条 `deleted_at IS NULL` 条件使四类不可见原因都没有结果行。Service 只观察“有无匹配行”，所以不会把缺失、跨 owner、子资源软删除和父资源软删除暴露为不同响应。查询没有 `kb.status`，也没有 `kd.parse_status`：`PENDING`、`PROCESSING`、`COMPLETED`、`FAILED` 都只是安全元数据中的当前状态，不是读取资格。

本切片没有 Flyway migration、表/列、索引、触发器、缓存或后台任务变更。

## 4. 安全输出与读取边界

V21 只复用现有 `KnowledgeDocumentResponse` 的八个字段：

| 对外字段 | 含义 |
| --- | --- |
| `id` | 文档 ID 字符串 |
| `knowledgeBaseId` | 所属知识库 ID 字符串 |
| `fileName` / `fileType` / `fileSize` | 已接收文件的展示元数据 |
| `parseStatus` | 当前处理状态，不等于重新处理能力 |
| `createdAt` / `updatedAt` | 元数据创建与最后更新时间 |

以下字段或能力明确不在响应中：`storageBucket`、`storageObjectKey`、`userId`、`parseError`、`deletedAt`、`mimeType`、`chunkCount`、chunk 正文、原始文件内容和下载地址。特别是 `parseError` 是服务器内部诊断信息；`chunkCount` 需要额外聚合，并会把详情读取与 chunk 数据生命周期耦合，因此两者均不因本切片而公开。

## 5. 实现与验收

聚焦的本地 JDK 21 / Mockito javaagent 自动化验证命令为：

~~~text
cd backend
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  mvn -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar \
  -Dtest=KnowledgeDocumentServiceTest,KnowledgeDocumentDetailControllerTest,KnowledgeDocumentMapperTest test
~~~

该聚焦命令已在当前 checkout 通过 `20/20`：`KnowledgeDocumentServiceTest` 16 项、`KnowledgeDocumentDetailControllerTest` 3 项、`KnowledgeDocumentMapperTest` 1 项。

自动化测试应覆盖：

1. 当前 owner 成功读取安全 DTO，并且 Service 只调用专用 JOIN Mapper；
2. `DISABLED` 父知识库不构成读取门槛，SQL 不含 `kb.status` 条件；
3. 缺失、跨 owner、已软删除文档和已软删除父知识库都由同一个无结果行分支映射为 404 / `Document not found`；
4. Mapper SQL 同时验证文档 ID、owner、文档软删除和父知识库软删除；
5. Controller JSON 只保留八个安全字段，不出现存储桶、对象键、`userId`、`parseError` 或 `chunkCount`。

`backend/http/knowledge-document.http` 增加了可执行的 `V21 read the uploaded document's safe metadata detail` 请求。手工验收的命名顺序是：先在 `user-auth.http` 运行 `Login`，再在 `knowledge-base.http` 运行 `Create a knowledge base`，随后运行 `Upload the tracked Markdown fixture`，最后运行上述 V21 详情请求。它断言创建后详情为 200、消息为 `Document retrieved`、状态仍是 `PENDING`，且 `data` 恰好包含八个安全字段。

上述 unit/mock 与 SQL-mapping 测试只证明本地代码、DTO 映射和注册 SQL 的边界；HTTP 文件是需要运行中的本地应用、认证与数据库的手工验收脚本。它们不证明生产多租户部署、真实 PostgreSQL 并发、文件存储可用性、Qdrant 清理、外部模型服务或 RAG 效果。

随后执行的完整 backend Maven suite 也已在当前 checkout 通过 `216/216`。它包含本切片的本地 unit/mock 与 SQL-mapping 覆盖，但同样不替代上述真实运行时或外部服务验收。

## 6. 明确不做

- 文档重解析、状态重置、旧 chunk/vector 清理、重试、队列或任何解析状态写入；
- 文档软删除、物理文件删除、Qdrant point 删除、恢复或保留期策略；
- 文件下载、读取原始内容、暴露存储路径/桶/对象键，或暴露 `userId`、`parseError`、`deletedAt` 与 `mimeType`；
- `chunkCount` 聚合、chunk 详情、向量化、检索、context、chat、回答审计或 feedback；
- migration、索引、表/列、触发器、缓存、后台任务、状态切换、批量读取或管理员跨 owner 读取；
- 对缺失、跨 owner、文档已删除或父知识库已删除返回不同错误，或以 `ACTIVE` 作为元数据读取资格；
- 生产认证、真实 PostgreSQL 并发压力、对象存储/Qdrant 清理、外部模型 SLA 或 RAG 质量结论。

## 面试问题与回答

### 问题 1：为什么 V21 要用文档与知识库的 JOIN，而不是先按 documentId 查询再单独查知识库？

**回答：** 详情可见性依赖文档本身和父知识库都未软删除，并且两者属于同一 current owner。`selectVisibleOwnedById(...)` 在同一条 SQL 中包含 `kd.id`、`kd.user_id`、`kd.deleted_at IS NULL`、`kb.deleted_at IS NULL` 以及父子 owner 对齐条件；没有匹配行就只有统一 404。拆成全局文档预读再查父资源既会扩大存在性暴露面，也会让两次读取之间的父资源状态变化变成额外行为边界。

### 问题 2：为什么 DISABLED 知识库下的文档仍能查看？

**回答：** `DISABLED` 是运行状态，不是软删除；它只阻止 V3 上传新文件，并不抹去 owner 对已接收历史元数据的管理权。V21 SQL 故意没有 `kb.status` 条件，因此 ACTIVE 与 DISABLED 都能读取；父知识库一旦 `deleted_at` 非空才统一不可见。本切片不实现状态切换或重新启用。

### 问题 3：详情查询从 `knowledge_document` 读取了实体，为什么不直接把实体返回？

**回答：** 实体包含 `storageBucket`、`storageObjectKey`、`userId` 和 `parseError` 等服务器内部字段。V21 在 Service 中明确经过既有 `KnowledgeDocumentResponse.from(...)`，Controller 只返回该 record 的八个字段；Controller JSON 测试还断言上述内部字段以及 `chunkCount` 不存在。读取实体是服务器实现细节，不是 API 暴露契约。

### 问题 4：为什么不顺带返回 chunkCount 或 parseError？

**回答：** `parseError` 是内部诊断，公开会破坏已有安全输出边界；`chunkCount` 则要求聚合 `knowledge_chunk`，会把一个只读元数据查询与 chunk 生命周期、删除和一致性策略耦合。V21 的成功响应只描述文档元数据和当前 `parseStatus`，两项均未纳入本切片，后续若需要应单独定义安全字段、查询成本和失败边界。

### 问题 5：为什么不在 V21 同时做重解析或删除？

**回答：** 重解析需要定义状态重置、旧 chunk/vector 清理、外部存储或 Qdrant 失败补偿；删除同样涉及物理文件、向量 point、保留期与恢复策略。这些都是有副作用的跨存储工作，而 V21 的详情只执行一条只读 JOIN 并输出安全 DTO。自动化测试和 HTTP 脚本只证明本地读取边界，不把未来清理策略表述为已完成能力。
