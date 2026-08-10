# AgentFlow Hub Knowledge 包：知识库元数据首个切片

本文件描述当前已实现的 `knowledge` 纵切片：**当前登录用户创建知识库元数据，并分页查看自己的知识库**。

它建立在不可修改的 Flyway `V1__create_app_user.sql` 之上，并新增 `V2__create_knowledge_base.sql`。本轮刻意不做文件上传、文档解析、chunk、embedding、Qdrant 或 RAG 检索；这些都是后续独立切片。

## 1. 这次新增的分层

```text
knowledge
  controller/KnowledgeBaseController.java  HTTP 入口、读取当前登录用户
  dto/CreateKnowledgeBaseRequest.java      允许客户端提交的字段与校验
  dto/KnowledgeBaseResponse.java           安全的 API 输出
  model/KnowledgeBase.java                 knowledge_base 表映射
  repository/KnowledgeBaseMapper.java      MyBatis-Plus 数据访问入口
  service/KnowledgeBaseService.java        owner 绑定、默认值、分页与事务

config
  MybatisPlusConfig.java                   PostgreSQL 分页拦截器
```

调用路径：

```text
POST /api/v1/knowledge-bases
  -> JwtAuthenticationFilter 已验证 Bearer token
  -> SecurityContext 中的 AuthenticatedUser
  -> KnowledgeBaseController
  -> @Valid 校验 CreateKnowledgeBaseRequest
  -> KnowledgeBaseService（固定 userId = currentUser.id）
  -> KnowledgeBaseMapper
  -> knowledge_base
```

这里的重点是：请求 JSON **没有** `userId`。资源归属只来自已经验证的 JWT，而不是客户端声称“我是谁”。

## 2. V2 数据库迁移

位置：

```text
backend/src/main/resources/db/migration/V2__create_knowledge_base.sql
```

表 `knowledge_base` 的关键字段：

| 字段 | 含义 | 本轮如何使用 |
| --- | --- | --- |
| `id` | BIGINT 主键 | MyBatis-Plus `ASSIGN_ID`，对外转字符串 |
| `user_id` | 所属用户 | 创建时由 `currentUser.id` 固定；列表时必须过滤 |
| `name`、`description` | 显示元数据 | 创建与列表都返回 |
| `embedding_provider`、`embedding_model` | 后续 embedding 配置 | 现在保存，后续入库流水线使用 |
| `chunk_size`、`chunk_overlap` | 后续切分配置 | 现在保存，并执行 RAG 设计给出的范围校验 |
| `status` | `ACTIVE` / `DISABLED` | 本轮创建为 `ACTIVE`，列表仍显示自己的 `DISABLED` 项 |
| `metadata` | 预留扩展 JSONB | 数据库保留，当前 API 不公开 |
| `deleted_at` | 软删除时间 | 当前没有删除接口；列表已提前过滤它 |

数据库约束是最后一道保护：名称不能全空白，`chunk_size` 必须在 `80` 到 `1000`，且 `0 <= chunk_overlap < chunk_size`。V2 应用后也不能编辑；变更必须另建 V3+ migration。

没有给 `(user_id, name)` 加唯一约束：当前产品设计没有要求“同一用户知识库名称唯一”，因此同名知识库是允许的。若以后产品明确要禁止同名，应同时新增专属错误码和新的 migration，而不是复用用户注册的冲突错误。

## 3. 创建接口

```http
POST /api/v1/knowledge-bases
Authorization: Bearer <accessToken>
Content-Type: application/json
```

完整请求示例：

```json
{
  "name": "支付业务知识库",
  "description": "支付失败、错误码、退款规则相关文档",
  "embeddingProvider": "openai-compatible",
  "embeddingModel": "text-embedding-v3",
  "chunkSize": 800,
  "chunkOverlap": 120
}
```

字段规则：

| 字段 | 规则 | 缺省行为 |
| --- | --- | --- |
| `name` | 必填、trim 后不能空白、最多 128 字符 | 无 |
| `description` | 可选、最多 4000 字符 | 空白会存为 `null` |
| `embeddingProvider` | 可选、最多 64 字符 | `openai-compatible` |
| `embeddingModel` | 可选、最多 128 字符 | `text-embedding-v3` |
| `chunkSize` | 可选，80–1000 | `800` |
| `chunkOverlap` | 可选，必须小于最终 `chunkSize` | `120` |

客户端不得传入并且服务端不会接受为可控字段的有：`id`、`userId`、`status`、`metadata`、`createdAt`、`updatedAt`、`deletedAt`。

成功返回 `201 Created`：

```json
{
  "code": "OK",
  "message": "Knowledge base created",
  "data": {
    "id": "2080000000000000001",
    "name": "支付业务知识库",
    "description": "支付失败、错误码、退款规则相关文档",
    "embeddingProvider": "openai-compatible",
    "embeddingModel": "text-embedding-v3",
    "chunkSize": 800,
    "chunkOverlap": 120,
    "status": "ACTIVE",
    "createdAt": "2026-08-06T12:00:00+08:00",
    "updatedAt": "2026-08-06T12:00:00+08:00"
  },
  "traceId": "af-kb-create-001",
  "timestamp": "2026-08-06T12:00:00+08:00"
}
```

`createdAt` 和 `updatedAt` 在 Service 中同时写入。原因是数据库 `DEFAULT CURRENT_TIMESTAMP` 虽然能保护直接 SQL，但 MyBatis 插入后不会自动把数据库默认值读回 Java 对象；不写入的话，刚创建的响应会缺少时间字段。

## 4. 分页列表接口与资源归属

```http
GET /api/v1/knowledge-bases?page=1&pageSize=20
Authorization: Bearer <accessToken>
```

返回类型固定为：

```text
ApiResponse<PageResult<KnowledgeBaseResponse>>
```

实际查询的安全边界是：

```text
user_id = currentUser.id
AND deleted_at IS NULL
ORDER BY created_at DESC, id DESC
```

即使用户已经登录，也只能看到自己的记录。列表不额外过滤 `status = ACTIVE`，因为拥有者仍应能看到并管理未来可能被禁用的知识库；在真正上传文档或检索时，才需要拒绝 `DISABLED`。

分页参数继续复用 `common.api.PageRequest`：默认 `page=1`、`pageSize=20`、最大 `pageSize=100`。`config/MybatisPlusConfig.java` 注册了 PostgreSQL 的 `PaginationInnerInterceptor`，这使 `selectPage` 同时执行正确的 count 和 LIMIT/OFFSET 分页。

## 5. 鉴权和失败语义

新路由没有额外修改 `SecurityConfig`，因为已有规则是“注册、登录、健康检查公开；其余所有接口都要求认证”。

| 场景 | HTTP | code |
| --- | --- | --- |
| 没有 token | 401 | `AUTH_UNAUTHENTICATED` |
| token 无效、过期，或用户已禁用/软删除 | 401 | `AUTH_TOKEN_INVALID` |
| 普通字段校验失败 | 400 | `COMMON_PARAM_INVALID` |
| `chunkOverlap >= chunkSize` | 400 | `COMMON_PARAM_INVALID` |
| 创建成功 | 201 | `OK` |
| 自己的列表查询成功 | 200 | `OK` |

本轮还没有 `{kbId}` 路由。未来新增详情、修改和删除时，每个按 ID 的查询都必须附带 `user_id = currentUser.id`；未找到与“不是资源所有者”应统一返回 404，避免泄露其他用户资源是否存在。

## 6. IDEA 手工验收

1. 保持已有 `POSTGRES_PASSWORD` 和 `JWT_SECRET_BASE64` 的 IDEA Run Configuration，启动 `AgentFlowApplication`。
2. 启动日志中应看到 Flyway 成功应用 `V2__create_knowledge_base.sql`。不要在 IDEA Query Console 手动建这张表。
3. 先在 `backend/http/user-auth.http` 运行 Login，使 IDEA 的 HTTP Client 本地会话保存一个短期 `agentflowAccessToken`。本地账号密码和 token 都不要保存或提交到 Git。
4. 打开 `backend/http/knowledge-base.http`，依次运行 Create、List、Invalid chunk settings 和 No token。
5. 在 IDEA 的 `agentflow_hub` 数据源 Query Console 执行：

```sql
SELECT id, user_id, name, status, chunk_size, chunk_overlap, created_at, updated_at
FROM knowledge_base
ORDER BY created_at DESC, id DESC;

SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

第一条应看到创建的知识库和当前登录用户的 `user_id`；第二条应看到版本 `1` 和 `2` 都成功。

## 7. 明确留到下一步的内容

- `knowledge_document`、`knowledge_chunk` 的 V3+ migration；
- `.txt` / `.md` 上传、对象存储和文档状态机；
- 文本清洗、token 估算和 chunk 切分；
- embedding 与 Qdrant 写入；
- retrieve-test、引用溯源和 RAG 服务；
- 知识库详情、修改、删除与 `updatedAt` patch 更新。

这样下一步会在一个已经有明确 owner、状态、分页和数据库边界的知识库根资源上继续，而不是直接从文件上传和向量库开始堆功能。
