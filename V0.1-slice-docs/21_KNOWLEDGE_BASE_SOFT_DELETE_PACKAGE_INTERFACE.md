# AgentFlow Hub Knowledge：V20 当前 owner 的知识库软删除

V20 在 V19 的知识库名称/描述 PATCH 之后补齐同一路径的 DELETE：当前认证用户只能软删除自己、
尚未软删除的知识库。它只更新 `knowledge_base.deleted_at` 和 `knowledge_base.updated_at`，不物理删除，
不新增 migration 或索引，也不级联修改文档、chunk、向量、Qdrant point、回答审计或本地文件。

文件编号为 21，是因为上一份 V19 契约文件编号为 20；本文实现切片版本为 V20。

## 1. HTTP 契约

~~~http
DELETE /api/v1/knowledge-bases/{knowledgeBaseId}
Authorization: Bearer <access-token>
~~~

请求没有 body。路径 ID 只来自 URL，`AuthenticatedUser` 只来自 JWT principal；客户端不能提交或控制
`userId`、owner、状态或删除时间。

成功时返回 HTTP 200、`ApiResponse<Void>` 和固定消息 `Knowledge base deleted`：

~~~json
{
  "code": "OK",
  "message": "Knowledge base deleted",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
~~~

不存在、其他 owner 的资源和已经软删除的资源都返回同一种 `COMMON_NOT_FOUND` / HTTP 404：

~~~json
{
  "code": "COMMON_NOT_FOUND",
  "message": "Knowledge base not found",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
~~~

V20 不因知识库是 `ACTIVE` 还是 `DISABLED` 改变该契约；两者都允许删除，也不会发生状态切换。

## 2. 包接口

~~~text
KnowledgeBaseController
  DELETE /api/v1/knowledge-bases/{knowledgeBaseId}
    -> KnowledgeBaseService.softDeleteOwned(currentUser, knowledgeBaseId)
         -> KnowledgeBaseMapper.softDeleteOwned(
              id + current owner user_id + deleted_at IS NULL + server timestamp
            )
         -> ApiResponse<Void>
~~~

1. `KnowledgeBaseController.softDelete(...)` 只传递路径 ID 与 JWT principal；成功消息固定为
   `Knowledge base deleted`，HTTP 状态保持项目统一的 200 响应外壳。
2. `KnowledgeBaseService.softDeleteOwned(...)` 是写事务，但不先读取资源；它生成一次服务端
   `OffsetDateTime.now()`，将其原样交给 Mapper。
3. `KnowledgeBaseMapper.softDeleteOwned(...)` 是专用注解 SQL，不调用 `deleteById`，也不使用
   MyBatis-Plus 的泛型更新 API。零影响行统一映射为 `COMMON_NOT_FOUND` / `Knowledge base not found`；
   非零且不为一行属于意外数据状态并抛出内部错误。
4. 服务没有 `KnowledgeDocumentService`、chunk、embedding/vector gateway、Qdrant、检索、context、chat、
   回答审计或 feedback 依赖，因此 DELETE 不会在本切片内触发下游删除、清理或外部调用。

## 3. 写入、owner scope 与时间契约

V20 的授权和可见性条件直接存在实际 UPDATE 中，而不是依赖先前的按 ID 查询：

~~~sql
UPDATE knowledge_base
SET deleted_at = #{deletedAt},
    updated_at = #{deletedAt}
WHERE id = #{knowledgeBaseId}
  AND user_id = #{userId}
  AND deleted_at IS NULL
~~~

两个 SET 位置复用同一个 `deletedAt` 参数，因此两个字段来自同一个服务端时间。WHERE 没有 `status`
条件，故 `ACTIVE` 与 `DISABLED` 行等价地可删除；WHERE 也没有 `DELETE FROM`、`name`、`description`、
embedding、chunk、metadata、`created_at` 或子资源字段。

这条 UPDATE 同时处理并发窗口：如果资源在请求期间被其他操作先软删除，第二个请求命中零行并获得统一 404。
同样，缺失、跨 owner 和重复删除不会因预读取或不同 SQL 分支泄露存在性。

## 4. 可见性与数据边界

`KnowledgeBaseService.listOwnedBy(...)` 和 `getOwnedById(...)` 既有的 owner-scoped 查询都要求
`deleted_at IS NULL`，所以 DELETE 成功后列表不包含该 ID、详情读取为 404。V19 的 PATCH 也已使用同一
可见性范围；已删除知识库的 PATCH 因 scoped 读取或 scoped UPDATE 不能成功。

文档、处理、向量化、检索、context、chat 与回答审计等现有知识库入口各自已经以“当前 owner +
未删除 knowledge base”作为父资源门槛。V20 不改这些实现，只让父资源的既有门槛在软删除后生效。
本切片的自动化测试直接覆盖列表和详情不可见；并未把每一个下游 endpoint 的所有组合重测冒充成新的
外部服务端到端证明。

没有新增或修改 Flyway migration、表、列、触发器、索引、缓存、队列或任务。`knowledge_document`、
`knowledge_chunk`、Qdrant point、本地文件和回答审计记录保持原状；它们的清理、恢复或保留策略属于
独立切片，不能由这个父资源软删除接口隐式完成。

## 5. 实现与验收

本地 JDK 21 / Mockito javaagent 的 focused 自动化验证命令为：

~~~text
cd backend
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  mvn -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar \
  -Dtest=KnowledgeBaseServiceTest,KnowledgeBaseControllerTest,KnowledgeBaseMapperTest test
~~~

该 focused run 已通过 `26/26`，其中 V20 覆盖：

1. 当前 owner 的成功软删除仅调用专用 scoped Mapper 写入，并传入服务端时间；
2. Service 不读取或改变 status，Mapper SQL 又没有 status 条件，因此 `DISABLED` 可删除；
3. Mapper 零影响行针对缺失、跨 owner 与已删除三种语义统一返回 `COMMON_NOT_FOUND`；
4. 成功软删除之后，既有 detail 读取得到 404、列表为空，且两条既有查询都仍包含 `deleted_at` scope；
5. Mapper SQL test 检查 `id`、`user_id`、`deleted_at IS NULL` 三个条件、两个相同的 `deletedAt`
   参数，以及没有物理 `DELETE FROM` 或无关字段写入；
6. Controller test 检查 DELETE 路由把 JWT principal 和路径 ID 传给 Service，并返回 200、`OK`、
   `Knowledge base deleted` 与空 payload。

`backend/http/knowledge-base.http` 的手工运行顺序为：先在 `backend/http/user-auth.http` 执行 `Login`，
再执行 `Create a knowledge base`，完成所需的详情/PATCH 检查后，依次执行 `Soft-delete the current owner's
knowledge base`、`Deleted knowledge-base detail is invisible` 和 `Deleted knowledge base is absent from the
current owner's list`。后三项分别断言 200、GET 404 / `COMMON_NOT_FOUND`，以及列表中没有刚删除的 ID。
这份 HTTP 文件是可执行的本地运行时手工验收脚本；本次 focused unit/mock 与 SQL-mapping 测试并不等于
生产认证、多租户部署、PostgreSQL 并发、Qdrant 清理、外部模型服务或 RAG 质量证明。

随后执行的完整 Maven suite 也已通过 `206/206`。它同样只能证明本地测试覆盖，不能替代真实 PostgreSQL
并发或外部服务验收。

## 6. 明确不做

- `deleteById`、物理删除、恢复/undelete、批量删除、管理员跨 owner 删除，或删除原因与审计日志 API；
- `ACTIVE` / `DISABLED` 状态切换，或以状态作为删除资格；
- 文档、chunk、本地文件、向量、Qdrant point、检索缓存、回答审计或 feedback 的级联删除、异步清理或
  垃圾回收；
- migration、索引、表/列、触发器、缓存、队列、后台任务、版本冲突协议或软删除保留期限；
- 对缺失、跨 owner、已删除资源返回不同错误，或在 DELETE 前使用全局 `selectById` 进行存在性探测；
- 生产认证、真实 PostgreSQL 并发负载、外部向量/模型服务可用性、数据保留合规或 RAG 效果结论。

## 面试问题与回答

### 问题 1：为什么 V20 不先 `selectById` 再删除？

**回答：** DELETE 的授权条件必须在实际写 SQL 中，而不应由一次早于写入的全局读取替代。
`softDeleteOwned(...)` 的 UPDATE 同时限定 `id`、`user_id` 与 `deleted_at IS NULL`，所以资源缺失、属于其他
owner、已删除或在并发窗口内被删除都会只表现为零影响行和同一个 404；这避免了存在性探测，也避免了范围外写入。

### 问题 2：为什么 `deleted_at` 与 `updated_at` 要复用一个参数？

**回答：** V20 的删除是一次状态变化，应有一个一致的服务端时刻。Service 只生成一次 `OffsetDateTime.now()`，
Mapper 在两个 SET 位置都使用 `#{deletedAt}`。Mapper SQL test 还检查参数映射中该参数出现两次，而不是由两个
独立的 now 值产生细微差异。

### 问题 3：为什么 DISABLED 知识库仍可删除？

**回答：** `DISABLED` 是可管理的运行状态，不是删除标记。V20 的写入可见性只要求“当前 owner 且
`deleted_at IS NULL`”，没有 `status` 条件，因此 ACTIVE 与 DISABLED 都能删除；该切片不会反向改变 status。

### 问题 4：为什么删除知识库时不立即删除 Qdrant point 和本地文件？

**回答：** 本切片只定义父资源软删除与后续入口不可见性。现有路径已通过未删除的父知识库门槛阻断访问，
但外部 point、子资源和文件的清理涉及失败补偿、保留期限和恢复策略，必须由具有独立可审计契约的后续切片完成；
把它们塞进 DELETE 会扩大事务和外部副作用范围。

### 问题 5：26 个测试和 HTTP 脚本分别证明什么，不能证明什么？

**回答：** 26 个测试证明当前 Service、Controller 和 Mapper 的本地/mock 编排与 SQL 形状，包括 scope、
统一 404、状态无关性和可见性谓词；HTTP 脚本定义本地运行时的创建→删除→GET 404/列表隐藏检查。二者都不证明
生产多租户部署、真实 PostgreSQL 高并发、Qdrant/文件清理、外部模型服务 SLA 或 RAG 质量；这些未纳入 V20。
