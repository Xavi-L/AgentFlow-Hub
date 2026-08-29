# AgentFlow Hub Knowledge：V24 当前 owner 文档删除编排与持久清理任务

V24 首次实现 `DELETE /api/v1/documents/{documentId}`，但它不是一条孤立的软删除 SQL。一次文档删除
同时涉及 PostgreSQL 权威元数据、已派生的向量 point、受控原始文件和 `knowledge_chunk`；这些资源没有
分布式事务。V24 因而先以一个受锁的短 PostgreSQL 准入事务软删除文档并创建持久清理任务，再在数据库
事务外按固定顺序完成外部副作用，最后以一个短事务物理删除 chunks 并完成任务。

文件编号为 25，功能切片版本为 V24。源码已有 Flyway migration 最高版本是 `V9`，因此本切片新增的
`V10__create_knowledge_document_deletion_task.sql` 是新的、不可修改的第一个后续迁移。V23 提供的
`VectorStoreGateway.deleteByDocumentScope(...)` 是本切片唯一使用的向量删除原语；`COMPLETED` 文档重解析
仍不在本切片，留给删除和补偿闭环稳定后的 V25。

## 1. 范围、状态与可见性

V24 的资源资格如下：

```text
PENDING   ─┐
FAILED    ─┼─ DELETE -> document.deleted_at + durable deletion task -> cleanup -> 200
COMPLETED ─┘

PROCESSING document                         -> 409
any scoped chunk vectorization PROCESSING   -> 409
```

`PENDING`、`FAILED` 和 `COMPLETED` 都可进入删除；V24 不重新解析、重新上传或修改 `parse_status`。
文档自身为 `PROCESSING` 时，既有 parser worker 正在使用该文件并将来可能回写终态，故 DELETE 返回 409。
即使文档已经 `COMPLETED`，其任一 chunk 若仍是 `vectorization_status = PROCESSING`，同样必须返回 409：该
worker 可能已经在事务外完成 embedding/Qdrant upsert，但还没写回 chunk 终态。

下列情形统一是 `COMMON_NOT_FOUND` / HTTP 404 / `Document not found`：文档缺失、其他 owner、文档已经完成
软删除、父知识库已经软删除。这里不通过错误码、消息或成功分支透露哪一种原因。`DISABLED` 不是软删除，
所以未删除的 `ACTIVE` 和 `DISABLED` 父知识库下的当前 owner 文档都可删除。

首次删除后，文档不再通过普通可见性查询出现。若存在同一 owner 的**未完成**删除任务，重复 DELETE 会恢复
该任务；若任务已完成，或软删除文档没有未完成任务，重复 DELETE 仍为统一 404，不能静默伪造成功。

## 2. HTTP 契约

~~~http
DELETE /api/v1/documents/{documentId}
Authorization: Bearer <access-token>
~~~

接口没有 request body。`documentId` 只能来自路径，`AuthenticatedUser` 只能来自 JWT principal；客户端不能
提交 `userId`、知识库 ID、storage bucket/object key、步骤完成标记、失败摘要、重试次数或向量 filter。

仅在 vectors、源文件和 chunks 三项都完成后，接口返回 HTTP 200、`ApiResponse<Void>` 和固定消息
`Document deleted`：

~~~json
{
  "code": "OK",
  "message": "Document deleted",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
~~~

冲突统一使用 `KNOWLEDGE_DOCUMENT_DELETION_CONFLICT` / HTTP 409 / `Document is not eligible for deletion`。它
覆盖 document `PROCESSING` 和任一 scoped chunk `PROCESSING`，但不引入取消、抢占、lease 或后台等待协议。

V23 向量删除或 `DocumentStorage.delete(...)` 的外部调用失败时，任务会保留为未完成、写入受控的失败摘要、
递增 `retry_count`，并返回 `KNOWLEDGE_DOCUMENT_DELETION_UNAVAILABLE` / HTTP 503 /
`Document deletion is temporarily unavailable`。原始 Qdrant、存储路径或异常文本不会进入 API 响应。下次同一
owner DELETE 将从第一个未完成步骤继续。

## 3. 持久任务与准入事务

`V10__create_knowledge_document_deletion_task.sql` 新建 `knowledge_document_deletion_task`。它不是后台队列，
也不由定时器消费；它是同步请求失败后可由同一 DELETE 重入的持久补偿证据。

| 字段组 | 持久化内容与用途 |
| --- | --- |
| scope | `id`、`user_id`、`knowledge_base_id`、`document_id`；复合外键指向 `knowledge_document`，`document_id` 全局唯一，避免 scope 漂移或重复任务。 |
| source snapshot | `storage_bucket`、`storage_object_key`；首次准入从已验证的 document 行复制，重试不读客户端数据。 |
| step evidence | `vectors_deleted_at`、`source_deleted_at`、`chunks_deleted_at`、`completed_at`；非空时间是每一项已完成的可审计标记。 |
| retry evidence | `failure_summary`、非负 `retry_count`、`last_attempted_at`、`last_failed_at`、`created_at`、`updated_at`。 |

首次准入由 `KnowledgeDocumentDeletionTransactionService.admitOrResumeOwned(...)` 在一个
`REQUIRES_NEW` 短事务内完成：

1. `KnowledgeDocumentMapper.selectOwnedWithLiveParentForDeletionForUpdate(...)` 以 document ID、current
   owner 和未删除父知识库做 JOIN，并对同一个 `knowledge_document` 行使用 `FOR UPDATE OF kd`；普通可见的
   `deleted_at` 条件在本方法外分支处理，才能让已软删除但未完成的 task 恢复。
2. 若文档尚未软删除，事务在锁内拒绝 document `PROCESSING`，再用完整三元 scope 查任一 chunk 是否
   `vectorization_status = PROCESSING`。任一冲突均不写 document/task。
3. 同一事务以一个服务端 `OffsetDateTime` 更新 `knowledge_document.deleted_at` 和 `updated_at`，并插入一条
   带 source locator snapshot 的 task。任一步失败都会回滚两项数据库写入，故不会出现“文档已删除但没有任务”的
   正常提交路径。
4. 若文档已软删除，只锁定完全匹配 scope 的未完成 task，更新 `last_attempted_at` 后返回它；完成任务或不存在
   task 统一为 404。

外部 I/O 不属于该事务，也不会借 JDBC 事务持锁：

```text
admit / resume short transaction
  -> VectorStoreGateway.deleteByDocumentScope(VectorDocumentScope)
  -> mark vectors_deleted_at short transaction
  -> DocumentStorage.delete(StoredDocument snapshot)
  -> mark source_deleted_at short transaction
  -> DELETE scoped knowledge_chunk + mark chunks_deleted_at/completed_at short transaction
  -> HTTP 200
```

最后一步由 `deleteChunksAndMarkCompleted(...)` 把物理 `knowledge_chunk` 删除和 task 的
`chunks_deleted_at/completed_at` 放在同一短事务中。document 元数据本身不会硬删除，仍保留为软删除的审计行；
task 也不会在成功后被删除。

## 4. 包接口与并发门槛

~~~text
KnowledgeDocumentDetailController
  DELETE /api/v1/documents/{documentId}
    -> KnowledgeDocumentDeletionService.deleteOwned(currentUser, documentId)
         -> KnowledgeDocumentDeletionTransactionService.admitOrResumeOwned(...)
              -> lock current-owner / live-parent knowledge_document
              -> reject document PROCESSING or scoped chunk PROCESSING
              -> soft-delete document + insert or resume durable task
         -> VectorStoreGateway.deleteByDocumentScope(server-owned VectorDocumentScope)
         -> DocumentStorage.delete(server-owned StoredDocument snapshot)
         -> transaction: delete scoped chunks + complete task
    -> ApiResponse<Void>
~~~

V24 同步修改了 `ChunkVectorizationTransactionService.claimPendingChunk(...)`。以前它只对 chunk 做
`PENDING -> PROCESSING` 条件 UPDATE；候选查询与实际 claim 之间若删除先提交，旧 worker 仍能将 chunk
改成 `PROCESSING`，随后在事务外把已删 Qdrant point 再次 upsert。

现在 claim 所在的 `REQUIRES_NEW` 短事务会先调用
`KnowledgeDocumentMapper.selectVectorizableOwnedForChunkClaimForUpdate(...)`。这条查询以 document、knowledge
base、current owner、document `COMPLETED`、document/parent 未删除为门槛，并锁定同一个 `kd` 行。只有拿到该锁
且仍可向量化时，才执行已有完整三元 scope 的 chunk `PENDING -> PROCESSING` UPDATE；否则返回 `false`，既有
vectorization service 将其计入 skipped 且不会调用 embedding 或 upsert。

因此两个并发顺序都安全：

```text
vector claim first:
  lock document -> chunk becomes PROCESSING -> commit
  DELETE later sees PROCESSING chunk -> 409, no vector/source/chunk cleanup

DELETE admission first:
  lock document -> deleted_at + task commit
  vector claim later fails live-document gate -> no embedding/upsert
```

`markCompleted` / `markFailed` 不另加 document 锁：已被成功认领的 worker 在外部调用期间保持 chunk
`PROCESSING`，V24 DELETE 已以 409 阻止清理；worker 进入终态后，后续 DELETE 才可重新准入。既有 parser claim
已经带 `knowledge_document.deleted_at IS NULL`，所以 V24 软删除后新的解析 claim 同样失败。

## 5. 实现与验收

本切片新增/修改的主要边界为：

1. 新 Flyway `V10__create_knowledge_document_deletion_task.sql`、`KnowledgeDocumentDeletionTask` 和
   `KnowledgeDocumentDeletionTaskMapper`，并为未完成 task、步骤完成和受控失败记录提供完整三元 scope SQL；
2. `KnowledgeDocumentDeletionTransactionService` 与 `KnowledgeDocumentDeletionService`，区分短数据库事务和
   事务外 V23/storage 调用；
3. `KnowledgeDocumentDetailController` 的顶层 DELETE、两个稳定错误码；
4. `KnowledgeDocumentMapper` 的删除锁/向量 claim 锁查询，以及 `KnowledgeChunkMapper` 的 PROCESSING 探测和
   scoped physical delete；
5. `ChunkVectorizationTransactionService` 的父 document 锁与可见性复核；
6. unit/mock、MyBatis SQL-shape、Controller 和 HTTP 手工验收覆盖。

聚焦自动化命令：

~~~text
cd backend
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  mvn -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar \
  -Dtest=KnowledgeDocumentDeletionServiceTest,KnowledgeDocumentDeletionTransactionServiceTest,KnowledgeDocumentDeletionTaskMapperTest,KnowledgeDocumentMapperTest,KnowledgeChunkMapperTest,ChunkVectorizationTransactionServiceTest,KnowledgeDocumentDetailControllerTest test
~~~

自动化覆盖的本地/mock 证据包括：三种允许 document 状态；document/chunk 两类 `PROCESSING` 冲突；统一 404；
server-owned task snapshot；vectors → source → chunks 的严格顺序；vector/source 外部失败的受控 503 与失败记录；
未完成 task 恢复、完成后重复 DELETE 的 404；最后物理 chunk 删除与 task completion 同一短事务；以及 vector
claim 在父 document 不再可见时跳过而不调用 upsert。

本次实现已运行上述聚焦 Maven 验收（36 项）以及完整 backend Maven suite（260 项），均为零失败/零错误。
另在隔离的空 PostgreSQL 18.4 临时实例上由 Spring Boot/Flyway 实际验证并顺序应用 V1–V10；`flyway_schema_history`
到 v10，且 `knowledge_document_deletion_task` 的 16 个预期列均已创建。该迁移验证是本地数据库证据，不替代
生产 PostgreSQL 版本兼容性、生产数据升级演练或真实外部服务验收。

`backend/http/knowledge-document.http` 将 V24 请求放在所有需要原始 Markdown fixture 的 V5–V17 验收之后。
可执行顺序为：`user-auth.http` 的 `Login` → `knowledge-base.http` 的创建知识库 → 本文件上传/处理/向量化及其前置
检查 → 最后运行 `V24 synchronously delete...`、deleted detail 404、列表不再出现该 document、chunks 404 和完成后
重复 DELETE 404。默认 local vector adapter 的 HTTP 成功只能证明本地编排；remote 模式也只是在当前运行配置下实际
调用 Qdrant。unit/mock、SQL-mapping 和手工 HTTP 都**不能**证明真实对象存储、真实 Qdrant 网络/认证、生产并发负载
或三方原子性。

## 6. 明确不做

- 后台队列、定时扫尾、自动重试、outbox、异步任务管理或任务查询 API；
- 文档元数据硬删除、恢复/撤销删除、保留期策略或 task 回收；
- 管理员跨 owner 删除、批量删除、跨知识库删除、单 chunk 删除，或客户端传入 vector/storage filter；
- 取消 parser/vector worker、lease 协议、强行抢占 `PROCESSING`，或把外部 I/O 放进 PostgreSQL 事务；
- `COMPLETED` 文档重解析、文件替换、重新上传、旧/新 chunk 的重新建立或向量再生成；这些属于删除与补偿闭环稳定后的
  V25；
- rerank、检索、context、chat、回答审计、feedback、Qdrant collection 创建/回收、模型切换或 provider 配置；
- 把 unit/mock、SQL-shape 或本地 HTTP 结果表述成真实外部服务或生产并发证明。

## 面试问题与回答

### 问题 1：为什么 V24 不能只在 `knowledge_document` 上加一条软删除 SQL？

**回答：** `deleted_at` 只能隐藏 PostgreSQL 元数据，不能删除已派生的 Qdrant point、受控源文件或 chunks；而它们又没有
和 PostgreSQL 共同提交的分布式事务。V24 先把软删除和 source locator snapshot 写入同一个持久 task，再以
`vectors_deleted_at`、`source_deleted_at`、`chunks_deleted_at` 记录每一步。外部失败时 task 仍可由同一 owner 的
DELETE 恢复，HTTP 200 才有“所有三项已完成”的含义。

### 问题 2：为什么 document `PROCESSING` 和 chunk `PROCESSING` 都必须返回 409？

**回答：** document `PROCESSING` 代表 parser worker 仍可读取文件并回写状态；chunk `PROCESSING` 代表向量 worker
已获得写入资格，可能正处于事务外 embedding/upsert 与终态回写之间。只检查 document 会漏掉后者：若先删掉旧 point，
旧 worker 仍可能把它再次 upsert。V24 选择明确 409 而不是取消/lease，因为后两者没有在本切片实现或验证。

### 问题 3：向量 claim 为什么必须与删除准入共享父 document 锁？

**回答：** 候选列表只是普通读取，无法阻止它在删除之后使用旧行。V24 让
`selectVectorizableOwnedForChunkClaimForUpdate(...)` 和删除准入都锁同一 `knowledge_document` 行：claim 先提交时，
DELETE 会看到 chunk `PROCESSING` 并拒绝；DELETE 先提交时，claim 看到 `deleted_at` 后返回 false。外部 upsert 始终在
锁释放之后，但不存在“删除提交后才获得 PROCESSING claim”的窗口。

### 问题 4：为什么 task 要保存 locator snapshot 和逐步完成时间，而不是下一次重试时重新读 document？

**回答：** 正常首次准入已经让 document 对普通读取不可见，重试不能依赖客户端重传 locator，也不应把已删除资源重新
暴露。snapshot 把已授权的 storage bucket/object key 固定在 task 中；逐步时间标记使重试只补做尚未完成的步骤，并能
区分 vector 已成功但 source 失败等情况。时间戳比单一布尔值还保留了基本审计证据。

### 问题 5：为什么未完成删除的重复请求可恢复，而成功后的重复 DELETE 是 404？

**回答：** 未完成 task 是 V24 唯一可信、且同一 owner 已被授权的补偿证据；恢复它避免把外部失败伪装为成功。完成后
document 已按正常可见性规则软删除，若再返回 200 就会把“此前任务已完成”和“本请求做完了三项清理”混淆，也会削弱
缺失/跨 owner/已删除统一 404 的边界。因此只有未完成 task 享有重入语义。
