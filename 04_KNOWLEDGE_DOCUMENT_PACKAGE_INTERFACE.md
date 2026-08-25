# AgentFlow Hub Knowledge：V3 文档接入切片

本文件描述当前已实现的 V3 `knowledge_document` 纵切片：**当前登录用户向自己的 ACTIVE
知识库上传 `.txt` / `.md` 原始文件，并分页查看已接收的文档。**

它建立在不可修改的 Flyway V1 `app_user` 与 V2 `knowledge_base` 之上，新增 V3
`knowledge_document`。本轮的目标是建立可靠的“原始资料入口”，不是直接完成 RAG 问答。

## 1. 这次到底完成了什么

```text
用户的 JWT
  -> POST /knowledge-bases/{kbId}/documents
  -> 确认 kbId 属于当前用户且状态为 ACTIVE
  -> 校验 .txt/.md、非空、最大 20 MB
  -> 本地文件存储（服务端 UUID 对象键）
  -> knowledge_document 记录（parseStatus = PENDING）
```

上传成功后，知识库的状态可以理解为：

```text
支付知识库
└── 退款规则.md       PENDING
```

`PENDING` 的意思是“原文件已经被安全接收并有元数据记录，但尚未进入解析流水线”。它不是
“已经切成 chunk”，更不是“已经可向量检索”。

## 2. 明确不属于本轮的内容

- 不解析 Markdown / TXT 正文；
- 不清洗文本、不估算 token、不创建 `knowledge_chunk`；
- 不生成 embedding，不连接 Qdrant；
- 不做召回、RAG 提问、引用溯源或 Agent；
- 不支持 PDF、Word、Excel、HTML；
- 不做异步队列或文档删除接口。

这些内容会继续拆成独立的后续切片，避免一次改动同时混入上传、解析、向量库和 Agent。

## 3. V3 数据库迁移

位置：

```text
backend/src/main/resources/db/migration/V3__create_knowledge_document.sql
```

`knowledge_document` 的关键字段：

| 字段 | 现在的含义 |
| --- | --- |
| `id` | MyBatis-Plus 生成的 BIGINT 主键，对外作为字符串返回 |
| `user_id` | 当前 JWT 用户；不能由 multipart 请求伪造 |
| `knowledge_base_id` | 文档所属知识库 |
| `file_name` | 供界面显示的清理后原始文件名，不用于物理路径 |
| `file_type` / `mime_type` | 受控的 `TXT` / `MD` 及服务端派生 MIME type |
| `file_size` | 上传时记录的字节数 |
| `storage_bucket` / `storage_object_key` | 内部存储定位信息，不出现在 API 响应中 |
| `parse_status` | 初始为 `PENDING`，预留后续 `PROCESSING`、`COMPLETED`、`FAILED` |
| `parse_error` | 预留给后续解析失败记录；当前上传不会写它 |
| `deleted_at` | 预留软删除；当前列表已主动过滤它 |

V3 还用 `(knowledge_base_id, user_id)` 复合外键确保文档的 `user_id` 与知识库 owner 一致。
这不是客户端校验，而是数据库最后一道关联约束。

和 V1/V2 一样，V3 一旦被 Flyway 应用就不能修改；以后的字段或状态变化必须新增 V4+。

## 4. 上传接口

```http
POST /api/v1/knowledge-bases/{knowledgeBaseId}/documents
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data
```

请求只接受一个名为 `file` 的 part。例如：

```text
file = 退款规则.md
```

服务端控制的字段有：`userId`、`knowledgeBaseId`、`fileType`、`mimeType`、存储对象键、
`parseStatus`、审计时间。客户端不能通过额外 JSON 或 multipart 字段覆盖它们。

成功响应为 `201 Created`：

```json
{
  "code": "OK",
  "message": "Document uploaded",
  "data": {
    "id": "2080000000000000001",
    "knowledgeBaseId": "2080000000000000000",
    "fileName": "退款规则.md",
    "fileType": "MD",
    "fileSize": 245,
    "parseStatus": "PENDING",
    "createdAt": "2026-08-14T12:00:00+08:00",
    "updatedAt": "2026-08-14T12:00:00+08:00"
  }
}
```

响应不会暴露 `userId`、绝对磁盘路径、`storageObjectKey` 或解析错误。

## 5. 文件规则与错误语义

| 场景 | HTTP | code |
| --- | --- | --- |
| 缺少 `file` | 400 | `KNOWLEDGE_DOCUMENT_FILE_REQUIRED` |
| 空文件 | 400 | `KNOWLEDGE_DOCUMENT_FILE_EMPTY` |
| 无效文件名 | 400 | `KNOWLEDGE_DOCUMENT_FILE_NAME_INVALID` |
| 不是 `.txt` / `.md` | 400 | `KNOWLEDGE_DOCUMENT_FILE_TYPE_UNSUPPORTED` |
| 超过 20 MB | 413 | `KNOWLEDGE_DOCUMENT_FILE_TOO_LARGE` |
| 无效 `knowledgeBaseId` | 400 | `COMMON_PARAM_INVALID` |
| 知识库不存在、软删除或属于其他用户 | 404 | `COMMON_NOT_FOUND` |
| 自己的知识库已禁用 | 409 | `KNOWLEDGE_BASE_NOT_ACTIVE` |
| 没有或错误 token | 401 | 现有认证错误码 |

对“别人的知识库”和“不存在的知识库”统一返回 404，而不是 403 或空列表；这样调用者不能据此枚举其他用户有哪些资源。

文件后缀是当前接入切片的准入依据，客户端 MIME type 只作输入信息而不被信任。服务端会根据
`.txt` / `.md` 写入固定的 `text/plain` / `text/markdown`。真正的正文解析与内容质量校验将在
下一切片完成。

20 MB 来自既有的 `spring.servlet.multipart.max-file-size`，Servlet 与 Service 使用同一份配置；
若部署环境要调整限制，只改这一项即可。

## 6. 列表接口

```http
GET /api/v1/knowledge-bases/{knowledgeBaseId}/documents?page=1&pageSize=20
Authorization: Bearer <accessToken>
```

返回统一分页外壳：

```json
{
  "code": "OK",
  "data": {
    "items": [],
    "page": 1,
    "pageSize": 20,
    "total": 0,
    "hasNext": false
  }
}
```

列表同样先验证知识库属于当前用户并排除软删除记录。为了保持 V2 的“owner 可管理 DISABLED
知识库”语义，禁用知识库的 owner 仍可查看历史文档，但不能继续上传新文件。

## 7. 本地文件存储

默认根目录为：

```text
${user.home}/.agentflow-hub/documents
```

可通过启动环境变量覆盖：

```text
AGENTFLOW_DOCUMENT_STORAGE_ROOT=/your/local/documents
```

物理对象键由服务端生成，形如：

```text
users/101/knowledge-bases/201/documents/<uuid>.md
```

原始文件名只用于展示，永远不会拼接到本地路径。文件先写入同目录临时文件，再以原子移动（系统
不支持时退化为普通移动）放到最终位置。因为文件系统不参与 PostgreSQL 事务，若元数据插入失败，
或外层数据库事务在方法返回后回滚，Service 都会尽力删除刚刚保存的文件。

## 8. IDEA HTTP 手工验收

1. 启动 `AgentFlowApplication`，它会通过 Flyway 应用 V3。
2. 在 `backend/http/user-auth.http` 运行 Login。
3. 在 `backend/http/knowledge-base.http` 运行一次 Create；成功后会把知识库 ID 临时保存为
   `agentflowKnowledgeBaseId`。
4. 在 `backend/http/knowledge-document.http` 依次运行 Upload、List、Unsupported suffix、No token。

上传文件使用项目内的演示 fixture：

```text
backend/http/fixtures/refund-rules.md
```

该 fixture 仅用于 API 验收，不是运行时用户数据。

## 面试问题与回答

### 问题 1：为什么 V3 上传成功后只将文档置为 `PENDING`，而不在同一请求中完成解析和检索？

**回答：** V3 的职责是可靠接收原始资料：`KnowledgeDocumentService.upload` 完成 owner 范围校验、文件准入、本地受控存储和元数据插入后，只写入 `parseStatus = PENDING`。这样“文件已接收”与“文本可解析、可分块、可检索”是可观察的不同事实，上传链路也不会因后续耗时或失败而失去明确语义。TXT/Markdown 解析和 `knowledge_chunk` 是 V4，embedding、向量库和检索不属于 V3；因此不能把 `201 Created` 或 `PENDING` 表述为 RAG 已可用。

### 问题 2：上传接口如何避免客户端伪造归属或利用文件名越权访问路径？

**回答：** 当前用户只从 JWT 建立的 `AuthenticatedUser` 取得，`userId`、知识库归属、存储对象键和 `parseStatus` 都不是 multipart 可写字段。Service 以 `knowledgeBaseId + currentUser.id()` 查询，缺失、非 owner 和软删除统一返回 `404 COMMON_NOT_FOUND`，避免资源枚举；V3 的 `(knowledge_base_id, user_id)` 复合外键再提供数据库侧的一致性约束。原始文件名会被规范化且仅用于展示，物理对象键由服务端 UUID 生成并限制在配置的存储根目录内，响应也不会返回路径或对象键。当前仅用受控后缀决定 `TXT`/`MD` 和服务端 MIME，客户端 `Content-Type` 不被信任；这不是正文安全扫描，非法 UTF-8 或内容质量问题留给 V4 的解析阶段处理。

### 问题 3：数据库事务不能回滚本地文件系统时，V3 如何降低文件与元数据不一致的风险？

**回答：** `LocalDocumentStorage` 先写同目录临时文件，再原子移动到服务端对象键；之后 V3 才插入 `knowledge_document`。若元数据插入失败，`KnowledgeDocumentService` 会立即尽力删除刚保存的对象；若外层数据库事务在方法返回后回滚，则通过 `afterCompletion` 注册同样的清理。这里刻意不宣称文件系统与 PostgreSQL 是分布式强事务：清理本身失败只记录服务端日志，仍可能留下需运维处理的孤儿文件，但不会把内部存储细节暴露给客户端。

### 问题 4：V3 如何验收，哪些结论不能由这些验收推出？

**回答：** `KnowledgeDocumentServiceTest`、`KnowledgeDocumentControllerTest` 和 `LocalDocumentStorageTest` 覆盖了 PENDING 写入、空文件/错误后缀、owner 范围、存储或元数据失败清理等本地行为。手工 HTTP 验收按 Login → Create knowledge base → Upload → List → Unsupported suffix → No token 的 V3 子序列执行，可观察 `201`、`PENDING`、分页和受控错误码。这些是单元测试与本地应用 HTTP 验收；它们不证明 V4 解析、V5/V6 向量化、语义检索、生产对象存储或病毒扫描已经完成。
