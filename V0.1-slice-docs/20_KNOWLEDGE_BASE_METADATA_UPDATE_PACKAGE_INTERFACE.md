# AgentFlow Hub Knowledge：V19 当前 owner 的知识库名称与描述部分更新

V19 在 V18 的 owner-scoped 详情读取之后补齐知识库元数据的窄写入口：当前认证用户可以部分修改自己、
尚未软删除知识库的 name 与 description。它继续返回既有 KnowledgeBaseResponse，不新增 migration、索引
或响应 DTO，也不修改文档、chunk、向量、RAG、回答审计或模型配置。

文件编号为 20，是因为上一份 V18 契约文件编号为 19；本文实现切片版本仍为 V19。后续软删除会单独作为
V20 实现，并使用 21_KNOWLEDGE_BASE_SOFT_DELETE_PACKAGE_INTERFACE.md。

## 1. HTTP 契约

~~~http
PATCH /api/v1/knowledge-bases/{knowledgeBaseId}
Authorization: Bearer <access-token>
Content-Type: application/json
~~~

请求体只能是 JSON object，且只能出现 name、description 中的至少一个：

~~~json
{
  "name": "支付业务知识库（2026）"
}
~~~

或：

~~~json
{
  "description": null
}
~~~

成功时返回 HTTP 200、ApiResponse 和既有 KnowledgeBaseResponse：

~~~json
{
  "code": "OK",
  "message": "Knowledge base updated",
  "data": {
    "id": "201",
    "name": "支付业务知识库（2026）",
    "description": null,
    "embeddingProvider": "dashscope",
    "embeddingModel": "text-embedding-v4",
    "chunkSize": 800,
    "chunkOverlap": 120,
    "status": "ACTIVE",
    "createdAt": "2026-08-29T10:00:00+08:00",
    "updatedAt": "2026-08-29T10:05:00+08:00"
  },
  "traceId": "...",
  "timestamp": "..."
}
~~~

KnowledgeBaseResponse 仍不公开 userId、内部 metadata 或 deletedAt。路径 ID 来自 URL，AuthenticatedUser
只来自 JWT principal；请求体没有也不能有 owner 字段。

## 2. 请求体与字段语义

V19 对这个 endpoint 使用 UpdateKnowledgeBaseMetadataRequestDeserializer 的局部 allowlist，而不改变
全局 Jackson 配置。它记录字段是否出现，因此不会把“字段缺失”和“字段值为 null”折叠成同一种 Java
null。

| JSON 情形 | 处理 |
| --- | --- |
| name 缺失 | 保留当前名称 |
| name 出现 | 必须为字符串；trim() 后不得为空，且最多 128 字符 |
| description 缺失 | 保留当前描述 |
| description: null | 将描述清空为数据库 NULL |
| description: "   " | trim() 后为空，将描述清空为数据库 NULL |
| 有内容的 description | trim() 后最多 4000 字符 |
| 空 object {} | COMMON_PARAM_INVALID / HTTP 400 |
| 已识别字段的空名称或长度超限 | COMMON_PARAM_INVALID / HTTP 400 |
| 非 object、语法错误、字段类型错误或任何未知字段 | COMMON_REQUEST_BODY_INVALID / HTTP 400 |

因此，status、embeddingProvider、embeddingModel、chunkSize、chunkOverlap、metadata、owner、userId、
createdAt、updatedAt、deletedAt 和所有其他未知字段都会在反序列化阶段被拒绝；不会传到 Service，更不会进入
SQL。name: null 是已识别的名称字段，但无法满足非空名称业务规则，返回 COMMON_PARAM_INVALID。

规范化后的请求值若恰好等于当前 name 与 description，接口仍返回 200 和当前响应，但不执行 UPDATE，
所以不会刷新 updatedAt。这让相同 PATCH 的安全重试保持幂等。

## 3. 包接口

~~~text
KnowledgeBaseController
  PATCH /api/v1/knowledge-bases/{knowledgeBaseId}
    -> UpdateKnowledgeBaseMetadataRequestDeserializer
         -> UpdateKnowledgeBaseMetadataRequest
    -> KnowledgeBaseService.updateMetadata(currentUser, knowledgeBaseId, request)
         -> selectOne(id + current owner user_id + deleted_at IS NULL)
         -> KnowledgeBaseMapper.updateMetadataOwned(
              id + current owner user_id + deleted_at IS NULL
            )
         -> KnowledgeBaseResponse
~~~

1. KnowledgeBaseController.updateMetadata(...) 只传递路径 ID、JWT principal 与经过局部严格反序列化的
   request；成功消息固定为 Knowledge base updated。
2. UpdateKnowledgeBaseMetadataRequest 的 namePresent / descriptionPresent 是反序列化器创建的内部
   presence 标记，不是额外开放给 JSON 客户端的字段。它们让 Service 能正确决定“保持”还是“清空”。
3. KnowledgeBaseService.updateMetadata(...) 先校验至少一个字段及已识别字段的业务约束，再在 owner +
   未删除 scope 中读取当前行、合成目标名称/描述并检查同值。不同值才生成新的 updatedAt 并调用 Mapper。
4. KnowledgeBaseMapper.updateMetadataOwned(...) 只写 name、description、updated_at。它的 UPDATE
   本身同时限定 ID、当前 user_id 和 deleted_at IS NULL；更新影响行数为 0 时 Service 统一映射为
   COMMON_NOT_FOUND / Knowledge base not found。

DISABLED 但未删除的知识库没有被排除：它可以修改名称和描述，且响应保留原有 status=DISABLED。V19
不实现状态切换。

## 4. 数据与可见性契约

读取当前元数据与实际写入都固定在同一个可见性规则中：

~~~sql
UPDATE knowledge_base
SET name = #{name},
    description = #{description},
    updated_at = #{updatedAt}
WHERE id = #{knowledgeBaseId}
  AND user_id = #{currentUser.id}
  AND deleted_at IS NULL
~~~

预读取用于保留缺失字段和识别同值 PATCH，不是写入授权的替代品；UPDATE 重新带上这三个条件。若资源在预读取后
变为不可见，UPDATE 的零影响行仍统一成为 404。不存在、跨 owner 与已软删除资源对调用者没有不同的成功或错误
通道；V19 不先进行一个全局 selectById 来区分它们。

该 UPDATE 不改 embedding_provider、embedding_model、chunk_size、chunk_overlap、status、metadata、
created_at、deleted_at 或 user_id。不新增 Flyway migration、表、列、触发器或索引，也不触及
knowledge_document、chunk、Qdrant、检索、context、chat、回答审计与 feedback 表。

## 5. 实现与验收

本地 JDK 21 / Mockito mock 验收使用：

~~~text
cd backend
mvn test -Dtest=KnowledgeBaseServiceTest,KnowledgeBaseControllerTest,KnowledgeBaseMapperTest
~~~

该 focused run 已通过 20/20，测试范围覆盖如下行为：

1. Service 验证只更新出现的字段、保留缺失 description、允许 DISABLED，且保持 embedding/chunk/status/
   createdAt 等其余元数据；
2. 显式 null 与空白 description 都写为 NULL；
3. 同值 PATCH 不调用 UPDATE，保留旧的 updatedAt；
4. 空 body object、空/超长 name、超长 description 都返回 COMMON_PARAM_INVALID，且不会查询数据库；
5. scoped UPDATE 的 0 影响行返回同一个 COMMON_NOT_FOUND；
6. Controller 验证 PATCH 绑定、Knowledge base updated 响应，以及禁止字段或错误 JSON 类型在到达 Service
   前返回 COMMON_REQUEST_BODY_INVALID；
7. Mapper unit test 检查注册的 UPDATE SQL 同时含 id、user_id 和 deleted_at IS NULL，且没有写入
   status、embedding、chunk、metadata 或 created_at。

backend/http/knowledge-base.http 的手工顺序为：先在 backend/http/user-auth.http 运行 Login，再运行
Create a knowledge base，接着运行 Update only the name 与 Clear the description with explicit null。
这两个 HTTP 请求检查运行中 200、成功消息、缺失字段保持不变及 null 清空行为。Immutable metadata is
rejected 可额外检查 400 / COMMON_REQUEST_BODY_INVALID。

上述自动化测试是本地/mock 与 SQL-mapping 证据；HTTP 文件是本地运行时手工验收脚本。它们不证明生产多租户
部署、外部模型/向量服务、RAG 质量或真实外部服务 SLA。

随后执行的完整 backend Maven test suite 也已通过 200/200；其中包含本切片的本地 unit/mock tests，但同样
不替代真实 PostgreSQL、生产认证或外部服务的端到端验收。

## 6. 明确不做

- DELETE、软删除、恢复、物理删除、状态启用/禁用，或下一切片的 DELETE knowledge-base endpoint；
- embedding provider/model、chunk 参数、metadata、owner、审计时间或任何其他知识库配置的修改；
- 文档上传/删除、解析、chunk、向量化、Qdrant、检索、context、chat、回答审计或 feedback；
- migration、索引、表/列、触发器、缓存、异步队列、后台任务、批量 PATCH、PUT 或版本冲突协议；
- 管理员跨 owner 修改、客户端 userId/owner 控制、存在性探测或区别不同不可见原因；
- 生产认证、并发负载、外部服务可用性、模型效果或 RAG 准确率结论。

## 面试问题与回答

### 问题 1：为什么 V19 需要记录字段是否出现，而不能只用 String description？

**回答：** 普通 JSON 绑定会让缺失 description 与 description: null 都变成 Java null，但 V19 的语义不同：
前者必须保持旧值，后者必须清空。局部反序列化器生成 descriptionPresent，Service 据此选择旧值或 NULL；
空白字符串经规范化后也走清空分支。这个 presence 信息只在请求 DTO 内部使用，并未开放成可由客户端伪造的
JSON 字段。

### 问题 2：为什么 UPDATE 之前已经做了 scoped SELECT，SQL 里还要再次限定 owner 和软删除？

**回答：** SELECT 的用途是合成部分更新和识别同值 PATCH；它不能替代写入时授权。V19 的
updateMetadataOwned(...) 在 UPDATE 的 WHERE 中再次同时加入 id、user_id 和 deleted_at IS NULL。因此即使
读取之后资源被软删除或归属发生变化，零影响行也只会得到统一 404，不会因为旧读取结果发生范围外写入。

### 问题 3：为何同值 PATCH 不更新 updatedAt，还要返回 200？

**回答：** 调用者已经表达了一个合法的最终状态，返回 200 使网络重试无需为“资源已是目标值”设计特殊错误；
不执行 UPDATE 则避免仅因重试改变审计时间。比较发生在 name/description 的规范化后，所以前后空白不同但
有效内容相同的请求也属于同值。

### 问题 4：为什么 DISABLED 知识库也能改名称与描述？

**回答：** V19 的可写可见性是“当前 owner 且未软删除”，不是“必须 ACTIVE”。DISABLED 不是删除，owner
仍应能整理它的展示元数据；这个切片不改变 status，也不暗示 DISABLED 资源可用于后续检索或 chat。
状态切换未纳入本切片。

### 问题 5：如何证明客户端不能通过 PATCH 修改 embedding 或 owner？

**回答：** UpdateKnowledgeBaseMetadataRequestDeserializer 的 allowlist 只有 name 与 description。例如
status、provider/model、chunk 参数、metadata、owner、时间字段与未知字段都会在请求体解析阶段变为
COMMON_REQUEST_BODY_INVALID，Controller 不会调用 Service。Mapper SQL 也只 SET 名称、描述和 updated_at；
本地 controller/mock 与 mapper SQL tests 覆盖了这两层边界。这些是代码和本地测试证据，不是生产环境或
外部服务验证。
