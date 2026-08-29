# AgentFlow Hub Knowledge：V22 当前 owner 的失败文档重试请求

V22 在 V21 的当前 owner 单文档安全详情之后，只补齐一个窄的状态重试入口：当前认证用户可以把自己可见、且当前处于 `FAILED` 的文档重新排回 `PENDING`。它不在请求中解析文件，也不把“重试请求已接受”表述为“本次解析已成功”。后续仍由既有 `process-pending` 流程认领并处理这条 `PENDING` 文档。

文件编号为 23，是因为上一份 V21 契约文件编号为 22；本文实现切片版本为 V22。

## 1. 状态机与切片范围

V22 只允许这一条用户触发的状态迁移：

```text
FAILED ── POST reprocess ──> PENDING ── process-pending ──> PROCESSING ──> COMPLETED / FAILED

PENDING / PROCESSING / COMPLETED ── POST reprocess ──> 409
```

`POST reprocess` 是一次重新排队请求，而不是解析执行器。它只把 `parse_status` 从 `FAILED` 改为 `PENDING`、清空服务器内部的 `parse_error`，并以一次服务端时间更新 `updated_at`；文件名、类型、大小、存储定位、owner、知识库归属、创建时间、软删除标记、chunk 与向量都不变。

既有 `POST /api/v1/knowledge-bases/{knowledgeBaseId}/documents/process-pending` 仍是唯一消费 `PENDING` 文档并进入 `PROCESSING` 的入口。V22 的 Service 不调用 `DocumentProcessingService`、`DocumentStorage`、parser、chunk Mapper、embedding/vector gateway 或 Qdrant，因此一个 `200` 只表示安全地请求重试，绝不表示源文件已经可解析。

V4 保证“全部 chunk 插入”和 `PROCESSING → COMPLETED` 在同一短事务中提交；解析失败则回滚该批 chunk 后单独写入 `FAILED/parse_error`。因此符合该状态机的 `FAILED` 文档没有半套已持久化 chunk，也就不需要 V22 清理旧 chunk/vector。相反，`COMPLETED` 文档重解析按设计必须先清理旧 chunk 和 Qdrant vectors；现有向量网关没有删除契约，故 V22 刻意不允许把 `COMPLETED` 重置为 `PENDING`。

## 2. HTTP 契约

~~~http
POST /api/v1/documents/{documentId}/reprocess
Authorization: Bearer <access-token>
~~~

接口没有请求体，也不读取任何客户端提交的 `userId`、知识库 ID、解析状态、`parseError`、存储键或时间字段。`documentId` 只来自 URL，`AuthenticatedUser` 只来自 JWT principal。

当当前 owner 对文档和父知识库都可见、且文档正处于 `FAILED` 时，成功返回 HTTP `200`、`ApiResponse<KnowledgeDocumentResponse>` 和固定消息 `Document reprocessing requested`：

~~~json
{
  "code": "OK",
  "message": "Document reprocessing requested",
  "data": {
    "id": "301",
    "knowledgeBaseId": "201",
    "fileName": "refund-rules.md",
    "fileType": "MD",
    "fileSize": 7,
    "parseStatus": "PENDING",
    "createdAt": "2026-08-29T12:00:00+08:00",
    "updatedAt": "2026-08-29T12:10:00+08:00"
  },
  "traceId": "...",
  "timestamp": "..."
}
~~~

以下四种不可见情形统一返回 `COMMON_NOT_FOUND` / HTTP `404` 和 `Document not found`：不存在的文档、其他 owner 的文档、已软删除的文档、以及父知识库已软删除的文档。调用者不能根据错误码、文案或成功/冲突分支区分它们：

~~~json
{
  "code": "COMMON_NOT_FOUND",
  "message": "Document not found",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
~~~

若文档对当前 owner 可见，但当前 `parseStatus` 是 `PENDING`、`PROCESSING` 或 `COMPLETED`，统一返回 `KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT` / HTTP `409` 和 `Document is not eligible for reprocessing`：

~~~json
{
  "code": "KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT",
  "message": "Document is not eligible for reprocessing",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
~~~

`PENDING` 的重复请求不能被视为幂等成功，因为那会掩盖已排队状态并弱化重复排队的边界；`PROCESSING` 不能被抢占；`COMPLETED` 不能在未清理旧 chunk/vector 的情况下重置。父知识库的 `DISABLED` 状态不是可见性或重试资格门槛：只要父知识库尚未软删除，`ACTIVE` 与 `DISABLED` 下的 `FAILED` 文档都可重试。

## 3. 包接口

~~~text
KnowledgeDocumentDetailController
  POST /api/v1/documents/{documentId}/reprocess
    -> KnowledgeDocumentService.reprocessOwnedFailed(currentUser, documentId)
         -> KnowledgeDocumentMapper.reprocessFailedVisibleOwned(
              document ID + current owner + document/parent non-deleted + FAILED + server timestamp
            ) -> UPDATE ... RETURNING updated document
         -> returned document: KnowledgeDocumentResponse.from(updated document)
         -> zero-row mutation only: KnowledgeDocumentMapper.selectVisibleOwnedById(...)
              -> 404 when now invisible; 409 when still visible
    -> ApiResponse<KnowledgeDocumentResponse>
~~~

1. `KnowledgeDocumentDetailController.reprocess(...)` 只传递 JWT principal 和路径 ID；成功消息固定为 `Document reprocessing requested`。它不绑定 request body，也不调用既有 `process-pending`。
2. `KnowledgeDocumentService.reprocessOwnedFailed(...)` 是短写事务，并先执行条件 `UPDATE ... RETURNING`；它不在成功路径预读文档。Mapper 返回刚写入的行时，Service 直接映射为 `KnowledgeDocumentResponse`。若 Mapper 没有返回行，Service 才调用 V21 的 joined visibility query：无结果统一抛出 `COMMON_NOT_FOUND` / `Document not found`；仍可见则抛出 `KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT`。
3. `reprocessFailedVisibleOwned(...)` 的实际 UPDATE 本身就是状态资格与写入授权：它同时限定文档 ID、current owner、文档未软删除、父知识库未软删除和 `FAILED` 状态。它必须只写 `parse_status`、`parse_error` 与 `updated_at`，并以一次 Service 生成的 `OffsetDateTime` 作为时间参数；零行后的读取只负责区分 404 与 409，不能放宽该写入条件。
4. `reprocessFailedVisibleOwned(...)` 以 PostgreSQL `UPDATE ... RETURNING kd.*` 返回刚写入的行；成功路径不需要第二次读取。并发、软删除或其他状态变化使条件 UPDATE 没有返回行时，Service 才用同一 joined visibility read 归类为 404（已经不可见）或 409（仍可见）；绝不把零行静默当成成功。
5. Controller 和 Service 不直接序列化 `KnowledgeDocument` 实体。`parseError` 清空是内部状态变化，响应仍只使用既有安全 DTO。

这一路径可以保留在 V21 的顶层 `KnowledgeDocumentDetailController` 中；它不改变 V3/V4 的知识库嵌套上传与 `process-pending` 路由，也不要求客户端传递或推断父知识库 ID。

## 4. 可见性、写入与并发 SQL 契约

V22 先执行条件写入；只有该写入没有返回行时，才复用 V21 的安全读取来区分不可见的 404 与仍可见的 409。该 joined read 让文档、父知识库和 current owner 在同一条查询中相交；它没有 `kb.status` 或 `kd.parse_status` 条件，因为它只判断当前可见性，不决定写入状态资格：

~~~sql
SELECT kd.*
FROM knowledge_document kd
INNER JOIN knowledge_base kb
    ON kb.id = kd.knowledge_base_id
    AND kb.user_id = kd.user_id
WHERE kd.id = #{documentId}
  AND kd.user_id = #{userId}
  AND kd.deleted_at IS NULL
  AND kb.deleted_at IS NULL
~~~

实际重试写入在先，且必须在**同一条 SQL**中同时包含文档和父知识库的可见性，不能由后续零行分类查询替代。PostgreSQL 形状如下：

~~~sql
UPDATE knowledge_document kd
SET parse_status = 'PENDING',
    parse_error = NULL,
    updated_at = #{updatedAt}
FROM knowledge_base kb
WHERE kd.id = #{documentId}
  AND kd.user_id = #{userId}
  AND kd.deleted_at IS NULL
  AND kd.parse_status = 'FAILED'
  AND kb.id = kd.knowledge_base_id
  AND kb.user_id = kd.user_id
  AND kb.deleted_at IS NULL
RETURNING kd.*
~~~

这条条件 UPDATE 以 `RETURNING kd.*` 把刚刚变为 `PENDING` 的行直接交给 Service，避免成功写入后再做一次可能被并发状态变化影响的读取。两个并发请求同时命中 `FAILED` 时，也只有一个能得到返回行；另一个零行后读取到仍可见文档即得到 `409`，不会重复排队。父知识库在写入竞争期间被软删除时，UPDATE 不会返回行，后续 joined read 统一得到 `404`。无论 Mapper 的具体注解或结果映射如何组织，写语句自身都必须保留上述父子 JOIN、owner、两处软删除和 `FAILED` 条件。

没有 `kb.status = 'ACTIVE'` 条件，也没有 `DELETE FROM`、chunk/vector 清理、存储读写或文件替换。V22 不新增 Flyway migration、表、列、索引、触发器、队列、缓存或后台任务；复用 V3 的 `parse_status` / `parse_error` / `updated_at` 与 V21 的 owner/父资源可见性模型。

## 5. 安全输出与验收契约

成功响应只复用 `KnowledgeDocumentResponse` 的八个字段：

| 对外字段 | 含义 |
| --- | --- |
| `id` | 文档 ID 字符串 |
| `knowledgeBaseId` | 所属知识库 ID 字符串 |
| `fileName` / `fileType` / `fileSize` | 已接收源文件的展示元数据 |
| `parseStatus` | V22 成功后为 `PENDING`，不等于解析成功 |
| `createdAt` / `updatedAt` | 原创建时间与本次重新排队时间 |

响应不得出现 `storageBucket`、`storageObjectKey`、`userId`、`parseError`、`deletedAt`、`mimeType`、`chunkCount`、chunk 正文、原始文件内容或下载地址。尤其不能因为 `parseError` 被清空而把它作为“已修复”的对外证据。

实现后，聚焦的本地 JDK 21 / Mockito javaagent 验收可在既有文档 test 类中运行：

~~~text
cd backend
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  mvn -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar \
  -Dtest=KnowledgeDocumentServiceTest,KnowledgeDocumentDetailControllerTest,KnowledgeDocumentMapperTest test
~~~

自动化测试与 SQL-mapping 测试至少覆盖：

1. 当前 owner 的 `FAILED` 文档只写 `PENDING`、`parseError = null` 和同一个服务端 `updatedAt`，并返回恰有八个安全字段的 DTO；
2. `DISABLED` 但未软删除的父知识库也允许成功重试，写 SQL 不含 `kb.status` 条件；
3. 缺失、跨 owner、已软删除文档、父知识库已软删除四类情况都走 `COMMON_NOT_FOUND` / `Document not found`；
4. 当前 owner 可见的 `PENDING`、`PROCESSING`、`COMPLETED` 分别都走 `KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT` / HTTP 409，重复请求在第一次成功后也属于 `PENDING` 冲突；
5. Mapper SQL 同时检查 `kd.id`、current owner、`kd.deleted_at IS NULL`、`kb.id = kd.knowledge_base_id`、`kb.user_id = kd.user_id`、`kb.deleted_at IS NULL` 与 `kd.parse_status = 'FAILED'`，且没有 `kb.status`、物理删除或无关字段写入；
6. Controller 只转交 JWT principal 与路径 ID，返回固定成功消息；非数字路径 ID 仍由全局参数绑定处理为 400，而不是 500。

`backend/http/knowledge-document.http` 的 V22 手工验收应使用一个受控的、能由 V4 处理流程稳定变为 `FAILED` 的同存储源 fixture。可执行顺序是：先在 `user-auth.http` 运行 `Login`，在 `knowledge-base.http` 创建知识库，上传该 fixture，运行 `Process every currently PENDING document in this knowledge base` 以确认其进入 `FAILED`，再运行 `POST /api/v1/documents/{documentId}/reprocess`。最后一个请求断言 200、`Document reprocessing requested`、`parseStatus = PENDING`，且 `data` 仍恰好只有八个安全字段。

该 HTTP 路径能证明“上传 → 本地处理失败 → 重新排队为 PENDING”的运行时闭环；它不能把同一个永久损坏文件的下一次解析也会成功表述为已验证能力。unit/mock、SQL-mapping 和本地 HTTP 验收同样不证明生产多租户部署、真实 PostgreSQL 并发负载、对象存储可靠性、Qdrant 删除/补偿、外部模型服务或 RAG 质量。

## 6. 明确不做

- `COMPLETED` 文档重解析，或对 `PENDING` / `PROCESSING` 文档重复排队；
- 旧 chunk、vector、Qdrant point、缓存或本地/对象存储文件的删除、清理、恢复、补偿或保留策略；
- 文档软删除、物理删除、文件替换、重新上传、下载、原文读取或暴露存储定位信息；
- 直接解析、自动执行 `process-pending`、队列投递、后台重试、陈旧 `PROCESSING` claim 回收或批量重试；
- 暴露或以 API 返回 `parseError`、`userId`、存储桶/对象键、`deletedAt`、`mimeType`、`chunkCount`、chunk 正文、向量 ID 或 embedding；
- migration、表/列/索引/触发器、向量网关删除契约、模型配置、检索、context、chat、回答审计或 feedback；
- 管理员跨 owner 操作、客户端 owner/状态/时间控制，或为四类不可见原因提供不同错误；
- 对永久损坏源文件的内容修复承诺，或生产认证、真实 PostgreSQL 并发、外部存储/Qdrant/模型 SLA 与 RAG 效果结论。

## 面试问题与回答

### 问题 1：为什么 V22 只允许 `FAILED → PENDING`，而不让 `COMPLETED` 文档也重解析？

**回答：** V4 的失败路径会回滚未完成的 chunk 插入，因此符合状态机的 `FAILED` 文档没有半套 chunk/vector；重试只需重新排队即可。`COMPLETED` 文档则已经有 PostgreSQL chunk，可能还已有 Qdrant vector；若不先清理旧数据就重置状态，会造成旧新内容、向量与检索身份混杂。现有向量网关没有删除契约，所以 V22 将 `COMPLETED` 明确固定为 409，完整重解析留给后续独立切片。

### 问题 2：为什么重试请求不直接调用解析器，而要交给已有 `process-pending`？

**回答：** V22 的职责只是安全的 owner-scoped 状态迁移；把解析、文件 I/O、chunk 落库和失败隔离继续留在 V4 的 `process-pending` 状态机中，才能保持 `PENDING → PROCESSING → COMPLETED/FAILED` 的条件认领与短事务边界。因此 200 的含义严格是 `PENDING` 已写入，不能承诺同一损坏源文件已经恢复或下一次会解析成功。

### 问题 3：为什么 V22 先执行条件 UPDATE，而不是先读出文档状态再决定是否写入？

**回答：** `reprocessFailedVisibleOwned(...)` 的一条 SQL 同时包含文档 ID、current owner、文档与父知识库均未软删除、父子 owner 对齐及 `parse_status = 'FAILED'`，并以 `RETURNING` 直接返回成功结果。这样状态资格和写入授权在同一个原子操作内完成；两个并发重试最多一个成功。只有 UPDATE 零行时，V22 才用 V21 joined visibility query 把“已经不可见”归为 404、把仍可见的请求归为 409，不会先做可被并发变化推翻的授权预读。

### 问题 4：为什么 `DISABLED` 父知识库下仍允许失败文档重试？

**回答：** `DISABLED` 是运行状态，不是删除。它阻止 V3 接收新上传，但不会取消 current owner 对已经接收资料的收尾处理权；V4 也允许处理禁用后留下的 `PENDING` 文档。V22 因而只要求父知识库未软删除，不添加 `kb.status = 'ACTIVE'`，让重试后的 `PENDING` 能由既有处理流程消费；本切片不实现状态切换或新文件上传。

### 问题 5：V22 的测试和 HTTP 验收能证明什么，不能证明什么？

**回答：** Service、Controller 与 Mapper 的本地 unit/mock 和 SQL-mapping 测试可以证明精确状态资格、统一 404/409、父子可见性和安全 DTO 边界；HTTP 可演示“受控坏文件上传后处理失败，再重试回到 PENDING”。它们不能证明永久损坏文件会在下一次成功解析，也不证明生产并发、真实对象存储、Qdrant 删除/补偿、外部模型服务或 RAG 质量。这些要么未纳入 V22，要么需要独立的真实运行时证据。
