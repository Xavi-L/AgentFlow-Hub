# AgentFlow Hub Knowledge：V18 当前 owner 的知识库元数据详情只读查询

V18 补齐既有知识库 API 设计中的详情入口：当前认证用户可以读取自己、且尚未软删除的一条知识库
元数据。它复用现有 `KnowledgeBaseResponse`，不新增详情 DTO，也不改变其中任何字段的含义。

本切片把路径中的知识库 ID、JWT current owner 与 `deleted_at IS NULL` 固定在同一条查询中。不存在、
跨 owner 和已软删除资源都得到同一个 `COMMON_NOT_FOUND` / HTTP 404，不能通过详情端点区分资源是否存在。
这是一条元数据只读查询；它不读取或改写文档、chunk、向量、RAG 上下文、回答审计或模型配置。

## 1. HTTP 契约

~~~http
GET /api/v1/knowledge-bases/{knowledgeBaseId}
Authorization: Bearer <access-token>
~~~

`knowledgeBaseId` 只能来自路径，`AuthenticatedUser` 只能来自 JWT principal。接口没有 request body，
不接受 `userId`、名称、描述、状态、embedding、chunk 配置、`deletedAt`、分页或任何 RAG 参数。

成功时返回 HTTP `200`、外层 `ApiResponse` 和既有 `KnowledgeBaseResponse`：

~~~json
{
  "code": "OK",
  "message": "Knowledge base retrieved",
  "data": {
    "id": "201",
    "name": "支付业务知识库",
    "description": "支付失败、错误码、退款规则相关文档",
    "embeddingProvider": "dashscope",
    "embeddingModel": "text-embedding-v4",
    "chunkSize": 800,
    "chunkOverlap": 120,
    "status": "ACTIVE",
    "createdAt": "2026-08-29T10:00:00+08:00",
    "updatedAt": "2026-08-29T10:00:00+08:00"
  },
  "traceId": "...",
  "timestamp": "..."
}
~~~

`KnowledgeBaseResponse` 故意不含 `userId`、内部 `metadata` 或 `deletedAt`。本接口可以读取既有的
`embeddingProvider`、`embeddingModel`、`chunkSize` 与 `chunkOverlap` 字段，但绝不接受请求来修改它们。

若同一 scope 没有匹配行，响应统一为 HTTP `404`、`COMMON_NOT_FOUND` 和 `Knowledge base not found`。
该结果同时覆盖不存在的 ID、其他 owner 的 ID 与已软删除 ID；不提供不同错误码或文案。

## 2. Scope 与可见性边界

V18 的唯一数据库读取同时包含三个条件：

~~~sql
SELECT ...
FROM knowledge_base
WHERE id = #{knowledgeBaseId}
  AND user_id = #{currentUser.id}
  AND deleted_at IS NULL
~~~

Service 不会先 `selectById` 再在 Java 中比较 owner，也不会先查询软删除状态。三个条件缺一不可：

- `id` 选择请求的详情资源；
- `user_id` 让 JWT current owner 成为资源可见性的组成部分；
- `deleted_at IS NULL` 排除已经软删除的行。

因此，数据库的“无匹配行”是三类不可见情形共享的唯一输入，Service 只把它映射为同一个 404。`DISABLED`
不是软删除：它仍是当前 owner 可管理的既有元数据，所以详情接口正常返回其 `status="DISABLED"`，不执行
状态切换或额外的 ACTIVE 校验。

## 3. 包接口与只读边界

~~~text
KnowledgeBaseController
  GET /api/v1/knowledge-bases/{knowledgeBaseId}
    -> KnowledgeBaseService.getOwnedById(currentUser, knowledgeBaseId)
         -> KnowledgeBaseMapper.selectOne(
              id + current owner user_id + deleted_at IS NULL
            )
              -> KnowledgeBaseResponse
~~~

1. `KnowledgeBaseController` 只传递 JWT principal 和路径 ID，并以统一 `ApiResponse` 包装既有响应 DTO；
   它不从 request body 读取 owner 或任何可修改字段。
2. `getOwnedById(...)` 使用 `@Transactional(readOnly = true)`，只调用现有 `KnowledgeBaseMapper` 的一次
   `selectOne`。它没有调用 `insert`、`updateById`、`deleteById` 或任何状态更新方法。
3. `KnowledgeBaseMapper` 继续复用 MyBatis-Plus `BaseMapper`，不新增 XML、专用写入方法或内存缓存。
4. Service 不依赖 `KnowledgeDocumentService`、chunk 处理、embedding/vector gateway、Qdrant、V7 retrieval、
   V8 context、V9 chat 或回答/feedback 服务。详情 GET 只读取 `knowledge_base` 元数据行。

## 4. 数据与查询契约

V18 只读取 V2 的既有 `knowledge_base` 表及其已存在的列：`id`、`user_id`、名称/描述、embedding/chunk
元数据、`status`、创建/更新时间与 `deleted_at`。不新增 Flyway migration、索引、表、列、触发器或约束。

`id` 是既有主键，单个详情读取至多需要检查一条主键候选行，再由同一 SQL 中的 owner 和软删除条件决定
是否可见；本切片没有引入排序、分页或批量访问需求，因而不增加索引。响应通过既有
`KnowledgeBaseResponse.from(...)` 完成 `BIGINT` ID 到字符串的转换，避免 JavaScript number 精度问题。

该读取不更新 `updated_at`，不设置 `deleted_at`，不改变 `status`，也不更新 embedding provider/model 或
chunk 参数。文档、chunk、向量和 RAG 数据表不参与查询。

## 5. 实现与验收

自动化本地 unit/mock 验收覆盖：

1. Service 对正常 current-owner 详情调用一次 `selectOne`，查询 wrapper 同时包含 `id`、`user_id` 和
   `deleted_at` 条件，并将已有行映射为 `KnowledgeBaseResponse`；
2. Service 对 scoped query 的空结果返回 `COMMON_NOT_FOUND` / `Knowledge base not found`。由于不存在、跨 owner
   与软删除在同一条 SQL 中都会成为零匹配行，它们不会走不同的错误路径；
3. `DISABLED` 但未软删除的行仍返回，证明可见性只由 current owner 与 `deleted_at` 决定，而不是把状态误当作
   删除；
4. Controller 仅把 current principal 和路径 ID 传给 Service，并返回 `Knowledge base retrieved` 与既有 DTO；
5. `backend/http/knowledge-base.http` 的 `Read one current-user, non-deleted knowledge base` 请求在创建知识库后
   验证 HTTP `200`、ID、名称，以及不暴露 `userId`/`deletedAt`。

已在 JDK 21 与 Mockito javaagent 下完成本地验证：V18 的 `KnowledgeBaseServiceTest` 与
`KnowledgeBaseControllerTest` 共 `9/9` 通过，完整 `mvn test` 共 `189/189` 通过。

手动 HTTP 验收顺序：先运行 `backend/http/user-auth.http` 的 `Login`，再运行
`backend/http/knowledge-base.http` 的 `Create a knowledge base`，最后运行新增的详情 GET。该手工路径验证正常
运行时响应形状；跨 owner 与软删除统一 404 的自动化证据来自上述 scoped-query unit/mock 测试。它们不构成生产
多租户合规、外部模型服务、向量服务或 RAG 质量证明。

## 6. 明确不做

- PATCH、PUT、DELETE、名称/描述修改、软删除、恢复、状态切换或任何管理写操作；
- embedding provider/model、chunk size/overlap、`metadata` 或其他知识库配置变更；
- 文档上传/列表、解析、chunk、向量化、Qdrant、检索、context、chat、回答审计或 feedback；
- migration、索引、表/列/触发器变更、缓存、异步队列、后台任务、批量接口或分页；
- 管理员越权读取、跨 owner 查询、`userId` 客户端传参、存在性探测或详情差异化错误；
- 生产环境认证、外部服务 SLA、模型效果、RAG 准确率或多租户安全认证结论。

## 面试问题与回答

### 问题 1：为什么不先按 `knowledgeBaseId` 查询，再在 Service 中判断 owner？

**回答：** 若先按 ID 查询，Service 已经知道了一个范围外资源是否存在，后续实现很容易产生不同的错误码、日志
或时间路径。V18 把 `id`、JWT `user_id` 和 `deleted_at IS NULL` 放进同一个 `selectOne`，所有不可见情形都只有
“没有匹配行”这一结果，并统一映射为 `COMMON_NOT_FOUND`。这也是测试检查 wrapper 三个条件的原因。

### 问题 2：为什么 `DISABLED` 的知识库仍能读取详情？

**回答：** V18 的可见性规则是“当前 owner 且未软删除”，没有把 `status` 加入查询条件。`DISABLED` 表示既有资源
当前不可用于某些后续操作，但它不是删除；当前 owner 仍需要看到自己的元数据。V18 只返回该状态，不实现启用、
禁用或状态切换，这些属于后续独立写切片。

### 问题 3：为什么复用 `KnowledgeBaseResponse`，而不新建一个详情 DTO？

**回答：** V18 的需求就是返回现有公开元数据，并没有详情专属字段。复用 DTO 可以保持创建、列表和详情对
`id` 字符串化、时间字段及内部字段隐藏的一致性；`userId`、`metadata` 与 `deletedAt` 仍不会泄露。名称/描述
编辑与软删除需要独立 request/response 契约，未纳入本切片。

### 问题 4：为什么没有为详情查询新增 migration 或索引？

**回答：** V18 只在 V2 的 `knowledge_base` 上按既有主键 ID 读取单条候选行，并在同一查询过滤 owner 和软删除。
它不引入新表、排序、分页或批量扫描需求，所以不修改已经应用的 Flyway migration，也不为了一个单行读取新增
索引。真实性能调优需要独立的实际负载与查询计划证据，未纳入本切片。

### 问题 5：如何证明这个详情接口不会触碰 RAG 或写入数据？

**回答：** Controller 只依赖 `KnowledgeBaseService`，该 Service 方法标注为只读事务且只调用一次
`KnowledgeBaseMapper.selectOne`；其实现没有 document/chunk/vector/chat 服务依赖，也没有 insert/update/delete。
本地 unit/mock 测试同时断言只有这次 Mapper 读取交互。这证明当前代码的编排边界，不等于真实外部服务或生产
环境的端到端证明。
