# AgentFlow Hub Knowledge：V25 当前 owner 的已完成文档安全重解析

> 当前状态：V25 已按本契约完成 Flyway、Java、自动化测试与 `.http` 验收脚本实现。JDK 21 下的
> 285 个 unit/mock、Controller、Mapper SQL-shape 测试已全部通过；V1–V11 已在隔离的本地 PostgreSQL 18
> 临时集群顺序执行并验证关键约束。尚未执行本文件的 15 步 HTTP 手工闭环，也未把 local/mock
> adapter 结果当作真实 Qdrant、生产并发或 RAG 效果证据。

V25 在 V24 当前 owner 文档删除与持久补偿闭环之后，复用既有
`POST /api/v1/documents/{documentId}/reprocess`，为当前 owner 的 `COMPLETED` 文档补齐“先清旧
vectors/chunks，再安全回到 `PENDING`”的重解析入口。它不新增上传接口、不替换源文件，也不在该
POST 内自动调用 parser、embedding 或向量化流程。

文件编号为 26，功能切片版本为 V25。V25 已在 V24 验收的 `V10` 之后新增
`V11__create_knowledge_document_reprocess_task.sql`，不得修改 `V10__create_knowledge_document_deletion_task.sql`
或更早的已应用迁移。V22 的 `FAILED -> PENDING` 条件更新原语及其响应语义保持不变；V25 只新增
`COMPLETED` 清理分支。

“失败后允许下一次 POST 重试”与“安全重新入队”还要求一个服务端 generation fence：当前 Qdrant 调用即使使用
`wait=true`，客户端 read timeout 仍不能证明远端操作没有继续执行，而默认 weak write ordering 也不能隔离迟到
删除。V25 因此在 V11 中给 document/chunk 增加内部 `vector_generation`，并让同一个
`deleteByDocumentScope(...)` 在 V25 路径只匹配 task 固定的旧 generation；该字段不进入客户端 DTO，也不形成新
接口或自动 worker。这里的判断依据是 Qdrant 官方对
[`wait`/`ordering` 参数](https://api.qdrant.tech/api-reference/points/delete-points)和
[weak/medium/strong write ordering](https://qdrant.tech/documentation/scaling/consistency-guarantees/)的定义；V25 不把
`wait=true` 误写成跨网络 exactly-once。

## 1. 状态机、范围与可见性

V25 完成后的状态机固定为：

```text
FAILED
  -> 既有 V22 条件 UPDATE
  -> PENDING                                      # 不创建 V25 task，不清理 chunks/vectors

COMPLETED
  -> 锁定 current-owner / live-parent document
  -> 拒绝任一 scoped chunk 的 vectorization PROCESSING
  -> REPROCESSING + durable reprocess task         # 同一准入短事务
  -> V23 deleteByDocumentScope(old generation)     # PostgreSQL 事务外，server-owned fence
  -> 标记 vectors_deleted_at                       # 独立短事务
  -> 删除旧 chunks + document PENDING + task 完成  # 同一最终短事务
  -> 既有 process-pending
  -> PROCESSING -> COMPLETED / FAILED

PENDING / parser PROCESSING
  -> POST reprocess -> 409

REPROCESSING + 同一 owner、同一三元 scope 的未完成 task
  -> VECTOR_DELETE_RETRYABLE：一个重复 POST 原子认领后重试 vector 删除
  -> READY_TO_FINALIZE：重复 POST 只恢复最终 PostgreSQL 事务
  -> VECTOR_DELETING：已有请求仍在执行，重复 POST -> 409

REPROCESSING + 无匹配未完成 task
  -> POST reprocess -> 409
```

`REPROCESSING` 必须作为新的显式 `DocumentParseStatus`。它表示“旧派生 vectors/chunks 正在被清理，尚未
重新进入 parser 队列”，不能复用 `PROCESSING`：后者已经具有明确的 parser worker claim 语义。既有
`process-pending` 仍只认领 `PENDING`；V25 只有在旧 vectors 与 chunks 都已清理后才写回 `PENDING`，因此
parser 不会与清理流程交错。

以下四种不可见情形继续统一返回 `COMMON_NOT_FOUND` / HTTP `404` / `Document not found`：文档不存在、
文档属于其他 owner、文档已软删除、父知识库已软删除。父知识库 `DISABLED` 不是删除；只要父知识库和
文档均未软删除，当前 owner 仍可对 `FAILED` 或 `COMPLETED` 文档请求重解析。

`COMPLETED` 首次准入必须在与 V24 删除、V5 chunk vector claim 相同的父 `knowledge_document` 行锁内完成。
锁内若发现任一完整三元 scope 的 chunk 为 `vectorization_status = 'PROCESSING'`，统一返回既有
`KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT` / HTTP `409`，且不得写 `REPROCESSING` 或创建 task。该 worker
可能已经完成事务外 embedding/Qdrant upsert，只是尚未回写终态；此时清理会产生“删完又被旧 worker
upsert”的窗口。

该门槛要成立，V25 实现必须同步收紧 V5 的远端结果分类：只有 adapter 能证明 upsert 未被接受/执行的失败才可把
chunk 从 `PROCESSING` 写成 `FAILED`；read timeout、连接中断或响应丢失等“远端可能仍会迟到完成”的结果必须保留
`PROCESSING`，只写受控的 `Vector store outcome unknown` 摘要。这样 V25/V24 都继续返回 409，直到后续受控
运维修复；不能先把 outcome-unknown chunk 标成 `FAILED` 再允许清理，因为 generation fence 只能防止旧删除误伤
新 vectors，不能阻止一个旧 upsert 在清理成功后重新写回旧 point。

## 2. HTTP 契约

V25 复用既有顶层入口，不增加 request body：

~~~http
POST /api/v1/documents/{documentId}/reprocess
Authorization: Bearer <access-token>
~~~

`documentId` 只能来自路径，current owner 只能来自 JWT principal。客户端不能提交 `userId`、知识库 ID、
目标状态、task ID、重试次数、步骤时间、Qdrant filter、storage locator 或源文件。

V22 `FAILED` 分支与 V25 `COMPLETED` 分支成功后都返回 HTTP `200`、固定消息
`Document reprocessing requested` 和既有八字段 `KnowledgeDocumentResponse`。V25 的成功响应必须已经是
`PENDING`：

~~~json
{
  "code": "OK",
  "message": "Document reprocessing requested",
  "data": {
    "id": "301",
    "knowledgeBaseId": "201",
    "fileName": "refund-rules.md",
    "fileType": "MD",
    "fileSize": 193,
    "parseStatus": "PENDING",
    "createdAt": "2026-08-30T12:00:00+08:00",
    "updatedAt": "2026-08-30T12:10:00+08:00"
  },
  "traceId": "...",
  "timestamp": "..."
}
~~~

按 V25 服务契约，该 `200` 表示当前配置的
`VectorStoreGateway.deleteByDocumentScope(...)` 已成功返回、旧 PostgreSQL chunks 已物理删除，且文档已安全
回到 `PENDING`。若当前配置是 local/mock adapter，这只能证明对应 adapter 与编排成功，不能据此声称真实远端
Qdrant point 已删除。它也不表示源文件已被重新解析，或新 chunks 已生成/重新向量化完成；后两步仍必须分别显式
调用既有 `process-pending` 与 `vectorize-pending` 接口。

可见但不满足状态/worker 门槛时继续使用既有冲突响应：

~~~json
{
  "code": "KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT",
  "message": "Document is not eligible for reprocessing",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
~~~

它覆盖 `PENDING`、parser `PROCESSING`、`COMPLETED` 但存在 PROCESSING chunk、`REPROCESSING` 却没有
同 owner 未完成 task，以及 active task 仍为 `VECTOR_DELETING` 的在途重复请求。一次成功后的立即重复 POST
会看到 `PENDING`，因此仍是 409，不能伪装成幂等成功。

V23 向量删除失败时，文档保持 `REPROCESSING`，task 保持未完成，旧 chunks 暂不物理删除；服务端记录受控
失败摘要、递增 `retry_count`，并返回新增错误：

~~~json
{
  "code": "KNOWLEDGE_DOCUMENT_REPROCESS_UNAVAILABLE",
  "message": "Document reprocessing is temporarily unavailable",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
~~~

HTTP 状态固定为 `503`。响应不得暴露 Qdrant URL、collection、API key、filter、原始异常、task ID 或内部
时间戳。Gateway 异常发生后，服务端先尽力把 task 从 `VECTOR_DELETING` 条件更新为
`VECTOR_DELETE_RETRYABLE`，再返回 503；若该失败证据写入本身也失败，仍沿用 V24 的优先级返回原 Gateway
503，并只在服务端日志保留两项异常，此时不能声称 failure/retry 字段已经持久化。

下一次同 owner POST 只有在文档与父知识库仍可见、存在完全匹配的未完成 task，且 task 为
`VECTOR_DELETE_RETRYABLE` 或 `READY_TO_FINALIZE` 时才恢复。`VECTOR_DELETING` 表示已有请求仍拥有外部步骤，
重复 POST 返回 409；已经完成的 task 也不赋予 `PENDING` 文档重复成功语义。

未完成 task 存续期间，既有安全文档详情/列表可以把 `parseStatus = REPROCESSING` 作为可观察中间态返回，
但不得附带 failure summary、retry count 或任何 task 字段；chunks 读取则按第 6 节返回空页。

## 3. Flyway 与持久任务数据契约

`V11__create_knowledge_document_reprocess_task.sql` 同时完成三项 schema 变化：

1. 删除并重建既有 `ck_document_parse_status`，允许
   `PENDING / PROCESSING / COMPLETED / FAILED / REPROCESSING`；Java 的 `DocumentParseStatus` 同步新增
   `REPROCESSING`。V3 migration 保持不可变。
2. 在 `knowledge_document` 与 `knowledge_chunk` 上分别增加非负 `vector_generation BIGINT NOT NULL DEFAULT 0`。
   现有 PostgreSQL 行因此属于 generation 0；既有 Qdrant point 没有该 payload 字段，V25 第一次 generation 0
   清理必须把“字段缺失”作为 legacy generation 0 一并匹配。
3. 新建独立的 `knowledge_document_reprocess_task`。它是同步 POST 失败后可重入的持久补偿证据，不是后台
   队列，也不由 scheduler 自动消费。

建议的数据库形状为：

~~~sql
ALTER TABLE knowledge_document
    DROP CONSTRAINT ck_document_parse_status;

ALTER TABLE knowledge_document
    ADD CONSTRAINT ck_document_parse_status
    CHECK (parse_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REPROCESSING'));

ALTER TABLE knowledge_document
    ADD COLUMN vector_generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_document_vector_generation_nonnegative
        CHECK (vector_generation >= 0);

ALTER TABLE knowledge_chunk
    ADD COLUMN vector_generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_chunk_vector_generation_nonnegative
        CHECK (vector_generation >= 0);

CREATE TABLE knowledge_document_reprocess_task (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    source_vector_generation BIGINT NOT NULL,

    vectors_deleted_at TIMESTAMPTZ,
    chunks_deleted_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,

    cleanup_status VARCHAR(32) NOT NULL,
    failure_summary VARCHAR(500),
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ NOT NULL,
    last_failed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_document_reprocess_task_document_scope
        FOREIGN KEY (document_id, knowledge_base_id, user_id)
        REFERENCES knowledge_document (id, knowledge_base_id, user_id),
    CONSTRAINT ck_document_reprocess_task_retry_count_nonnegative
        CHECK (retry_count >= 0),
    CONSTRAINT ck_document_reprocess_task_source_generation_range
        CHECK (
            source_vector_generation >= 0
            AND source_vector_generation < 9223372036854775807
        ),
    CONSTRAINT ck_document_reprocess_task_cleanup_status
        CHECK (cleanup_status IN (
            'VECTOR_DELETING',
            'VECTOR_DELETE_RETRYABLE',
            'READY_TO_FINALIZE',
            'COMPLETED'
        )),
    CONSTRAINT ck_document_reprocess_task_lifecycle
        CHECK (
            (
                cleanup_status IN ('VECTOR_DELETING', 'VECTOR_DELETE_RETRYABLE')
                AND vectors_deleted_at IS NULL
                AND chunks_deleted_at IS NULL
                AND completed_at IS NULL
            )
            OR (
                cleanup_status = 'READY_TO_FINALIZE'
                AND vectors_deleted_at IS NOT NULL
                AND chunks_deleted_at IS NULL
                AND completed_at IS NULL
            )
            OR (
                cleanup_status = 'COMPLETED'
                AND vectors_deleted_at IS NOT NULL
                AND chunks_deleted_at IS NOT NULL
                AND completed_at IS NOT NULL
            )
        ),
    CONSTRAINT ck_document_reprocess_task_retryable_failure
        CHECK (
            cleanup_status <> 'VECTOR_DELETE_RETRYABLE'
            OR (
                failure_summary IS NOT NULL
                AND char_length(btrim(failure_summary)) > 0
                AND last_failed_at IS NOT NULL
            )
        )
);

CREATE UNIQUE INDEX uk_document_reprocess_task_active_document
    ON knowledge_document_reprocess_task (document_id)
    WHERE completed_at IS NULL;
~~~

字段责任固定为：

| 字段组 | 持久化内容与用途 |
| --- | --- |
| scope | `id`、`user_id`、`knowledge_base_id`、`document_id`；复合外键证明 task 与 canonical document scope 一致。 |
| vector fence | immutable `source_vector_generation`；固定本轮只能删除的旧代 vectors，不随重试或后续入库改变。 |
| coordination | `cleanup_status`；只允许 `VECTOR_DELETING / VECTOR_DELETE_RETRYABLE / READY_TO_FINALIZE / COMPLETED`，提供无 lease 的持久单飞门槛。 |
| step evidence | `vectors_deleted_at`、`chunks_deleted_at`、`completed_at`；最终 chunk/document/task 三项数据库写入必须在同一事务完成。 |
| retry evidence | `failure_summary`、非负 `retry_count`、`last_attempted_at`、`last_failed_at`、`created_at`、`updated_at`。 |

`cleanup_status` 的唯一合法迁移为：

```text
new admission                         -> VECTOR_DELETING
Gateway failure recorded             -> VECTOR_DELETE_RETRYABLE
one retry POST claims by CAS          -> VECTOR_DELETING
vectors_deleted_at durably recorded  -> READY_TO_FINALIZE
chunks + document PENDING + task      -> COMPLETED
```

这是无超时的持久单飞门槛，不是 lease：`VECTOR_DELETING` 不会按时间自动失效。若进程在既未记录 Gateway
失败、也未写入 `vectors_deleted_at` 时崩溃，V25 无法区分“仍在执行”和“执行者已消失”，后续 POST 必须继续
返回 409；自动回收这种卡住尝试需要后续 attempt lease/ownership fencing 或受控运维修复设计，未纳入本切片。

`vector_generation` 是删除匹配 fence，不是执行 lease。每次 `COMPLETED` 首次准入在同一事务中把 task 的
`source_vector_generation` 固定为 document 当前值，并把 document generation 精确递增 1；下一轮 parser 创建的
每个 chunk 必须复制递增后的 document generation，vectorizer 再把它写入 Qdrant payload 的
`vectorGeneration`。V25 对 source generation 大于 0 时只匹配该精确值；source generation 为 0 时匹配
`vectorGeneration = 0` **或字段缺失**，兼容升级前的 legacy points。迟到的旧删除因此永远不能匹配后续
generation。generation 达到 `BIGINT` 上限时不再准入并按内部不变量失败处理，不允许溢出回绕。

部分唯一索引只禁止同一 document 同时存在两条未完成 task；它必须允许同一 document 保留多条已完成历史，
从而支持将来再次从新的 `COMPLETED` 状态发起重解析。因此 task Mapper 的所有步骤写入、并发完成 reread 和
失败记录都必须使用 `task_id + user_id + knowledge_base_id + document_id` 精确定位，不能照搬 V24
deletion task“按 document scope 可读取唯一历史行”的假设。

V25 task 不保存 `storage_bucket`、`storage_object_key` 或 `source_deleted_at`。源文件正是下一轮 parser 的输入，
所以 V25 不调用 `DocumentStorage.delete(...)`，也不需要复制 V24 删除任务的 source locator snapshot。删除任务
包含源文件删除和文档永久软删除语义，重解析任务则保留源文件与可见文档并最终回到 `PENDING`；两者不得复用
同一表或同一实体。

## 4. 包接口与事务边界

V25 的目标调用边界为：

~~~text
KnowledgeDocumentDetailController
  POST /api/v1/documents/{documentId}/reprocess
    -> KnowledgeDocumentReprocessService.reprocessOwned(currentUser, documentId)
         -> 既有 KnowledgeDocumentService.reprocessOwnedFailed(...)
              -> FAILED 命中：原 V22 条件 UPDATE -> PENDING -> 直接返回
              -> 404：原样向上传播
              -> 仅 KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT：进入 V25 分支
         -> KnowledgeDocumentReprocessTransactionService.admitOrResumeCompletedOwned(...)
              -> lock current-owner / live-parent knowledge_document
              -> COMPLETED：拒绝 scoped PROCESSING chunk
              -> snapshot source generation
              -> document REPROCESSING + generation + 1
              -> insert VECTOR_DELETING task with source generation（同一短事务）
              -> REPROCESSING + VECTOR_DELETE_RETRYABLE：CAS 认领 vector retry
              -> REPROCESSING + READY_TO_FINALIZE：只恢复最终数据库步骤
              -> REPROCESSING + VECTOR_DELETING：409
         -> VectorStoreGateway.deleteByDocumentScope(server-owned generation-fenced VectorDocumentScope)
         -> transaction: mark vectors_deleted_at + READY_TO_FINALIZE
         -> transaction: delete scoped chunks
                         + document REPROCESSING -> PENDING
                         + clear parse_error / update updated_at
                         + mark chunks_deleted_at / completed_at / COMPLETED
    -> ApiResponse<KnowledgeDocumentResponse>
~~~

1. `KnowledgeDocumentReprocessService` 是非事务编排层。它首先调用既有 V22 service，保留
   `reprocessFailedVisibleOwned(...)` 的 SQL、`FAILED -> PENDING`、统一 404/409、八字段 DTO 和“不碰
   chunks/vectors”的全部语义。只有精确的 V22 reprocess conflict 才继续分类；404 和其他异常不得被吞掉。
2. `admitOrResumeCompletedOwned(...)` 使用新的 owner/live-parent joined query，并以 `FOR UPDATE OF kd` 锁定
   与 V24/V5 相同的 `knowledge_document` 行。查询必须包含 current owner、`kd.deleted_at IS NULL`、
   `kb.deleted_at IS NULL` 和父子 owner 对齐，但不能增加 `kb.status = 'ACTIVE'`。
3. 锁内状态为 `COMPLETED` 时，先用现有完整三元 scope 的
   `hasProcessingChunkByDocumentScope(...)` 检查向量 worker；无冲突后，才以同一个服务端时间写
   `REPROCESSING`、把 `vector_generation` 从旧值精确递增 1、清空内部 `parse_error`、更新 `updated_at`，并插入
   `VECTOR_DELETING` task；task 的 `source_vector_generation` 固定为递增前旧值，其他初始值固定为
   `retry_count = 0`、`failure_summary = NULL`，且
   `last_attempted_at = created_at = updated_at = admissionTime`。document 状态 UPDATE 自身必须再次限定
   document ID、current owner、完整父子 scope、两处未软删除、父子 owner 对齐、旧状态 `COMPLETED`、
   `vector_generation = source_vector_generation` 且未达到 `BIGINT` 上限，影响行数必须恰好为 1；零行按最新
   可见性归为 404/409，generation 上限按内部不变量失败。状态与 task 任一写入失败必须整体回滚，不允许提交
   “REPROCESSING 但没有 task”。
4. 锁内状态为 `REPROCESSING` 时，只能按完整 scope 锁定 `completed_at IS NULL` 的 task。进入任一恢复分支前，
   必须验证 `document.vector_generation = task.source_vector_generation + 1`；不匹配属于内部持久化不变量失败，
   禁止调用 Gateway 或删除 chunks。task 为
   `VECTOR_DELETE_RETRYABLE` 时，一个 POST 以条件 UPDATE 原子改为 `VECTOR_DELETING`、清空当前
   `failure_summary` 并更新 `last_attempted_at/updated_at`；竞争失败的重复请求返回 409。task 为
   `READY_TO_FINALIZE` 时只恢复最终 PostgreSQL 事务，不再次调用 Gateway；task 为 `VECTOR_DELETING`、没有
   匹配 task，或 document 为 `PENDING / PROCESSING / FAILED` 时统一 409。
5. 外部 `deleteByDocumentScope(...)` 不在 JDBC 事务内，且只有持有当前 `VECTOR_DELETING` 单飞资格的本地请求才可
   调用。V25 继续复用 V23 方法名，但把 provider-neutral `VectorDocumentScope` 扩展为仅服务端可构造的两种模式：
   保留现有三参数构造/工厂作为“不限 generation”的完整 document scope 供 V24 永久删除使用；V25 通过新增服务端
   factory 传 task 的 `source_vector_generation`。Qdrant 与 in-memory adapter 都只能删除该旧 generation，且
   generation 0 额外匹配缺失 `vectorGeneration` 的 legacy point。
   客户端不得提交或覆盖该 fence。成功后以
   `task_id + 三元 scope + cleanup_status = VECTOR_DELETING + completed_at IS NULL` 条件写
   `vectors_deleted_at`、清空 `failure_summary`、更新 `updated_at` 并转为 `READY_TO_FINALIZE`。V23 的幂等性只用于
   document 仍保持 `REPROCESSING` 时，对已记录失败/超时进行重放；generation fence 必须保证任何迟到旧请求仍只
   命中旧代 points。
6. `deleteChunksRequeueAndComplete(...)` 是最终 `REQUIRES_NEW` 短事务。所有同时访问 document/task 的事务统一
   使用 `document -> task` 锁顺序：先按完整三元 scope 锁定未软删除 document，再按 `task_id + 三元 scope`
   锁定 task。只有 document 仍为 `REPROCESSING`、document generation 等于 task source generation 加 1、task 为
   `READY_TO_FINALIZE` 且
   `vectors_deleted_at IS NOT NULL / completed_at IS NULL` 才物理删除 chunks、把 document 改为 `PENDING`、清空
   `parse_error`，并以同一服务端时间写 document `updated_at` 与 task
   `chunks_deleted_at/completed_at/updated_at/COMPLETED`。
   document UPDATE 必须限定 `document_id + knowledge_base_id + user_id`、`deleted_at IS NULL`、
   `parse_status = 'REPROCESSING'` 和 `vector_generation = task.source_vector_generation + 1`，且影响恰好一行；
   否则抛内部异常，让三项写入共同回滚。该事务必须把同一提交点的八字段 `PENDING` document snapshot 返回给
   编排层，200 DTO 直接映射该 snapshot，不在提交后重新读取一个可能已被显式 parser 推进的当前行。
7. 最终事务在任何 chunk DELETE 之前若发现 task 已完成，或其状态不再是 `READY_TO_FINALIZE`，必须停止且不触碰
   当前 chunks。两个 finalizer 竞争时最多一个返回本次 200；败者返回既有 409，不承诺复读一个可能已被显式
   parser 推进到 `PROCESSING/COMPLETED` 的“当前 PENDING”响应。该单飞/条件事务协议不引入 lease、取消或后台
   worker。
8. 既有 parser 的 chunk 落库事务必须从被 claim 的 document 复制 `vector_generation` 到每个新 chunk；V5 的
   candidate/claim SQL 必须要求 chunk generation 与当前 `COMPLETED` document generation 一致，V5 upsert payload
   必须携带同一 `vectorGeneration`。该字段仅用于内部删除 fence，不进入 chunk/document HTTP DTO。V24 的永久删除
   继续调用“不限 generation”的同一 Gateway 方法，仍清理该 document scope 的全部 points。Qdrant adapter 还要用
   provider-neutral 的“definitive failure / outcome unknown”异常边界区分 upsert 结果；V5 对后者不得调用既有
   `markFailed(...)`，而要以条件 UPDATE 保持 `PROCESSING` 并仅写受控摘要，使 document cleanup gate 持续生效。

首次准入和每次由新 POST 发起的恢复都必须重新验证父知识库未软删除。该可见性检查与 document 行锁不能序列化
并发的知识库软删除：一个已经完成准入的在途请求可以继续依靠 task 中的服务端三元 scope 完成 Gateway 步骤和
最终数据库事务，即使父知识库随后被软删除；但软删除提交后的新 POST 必须返回统一 404，不得借 active task 绕过
父资源可见性。V25 不把这一边界表述为与知识库删除共享了完整锁协议，也不新增知识库删除补偿。

向量删除失败只记录固定、受控的摘要（例如 `Vector deletion failed`）；原始 cause 仅进入服务端日志。失败写入
必须以 `task_id + 三元 scope + cleanup_status = VECTOR_DELETING + vectors_deleted_at IS NULL + completed_at IS NULL`
为条件，将 task 改为 `VECTOR_DELETE_RETRYABLE`、执行 `retry_count = retry_count + 1`，并更新
`last_failed_at/updated_at`。条件更新零行视为次生持久化异常：不得覆盖已经推进的 task，记录服务端日志后仍按
原 Gateway 失败返回 503。`retry_count` 表示已成功持久化的外部失败次数，不是 POST 次数，也不是 task 执行
lease。

`KNOWLEDGE_DOCUMENT_REPROCESS_UNAVAILABLE` 只映射已识别的向量 Gateway 外部失败。数据库写入、约束或内部
不变量失败必须依靠短事务回滚，并由既有全局内部错误边界处理，不能把所有服务端错误伪装成 provider 503。

## 5. 与删除、向量 claim 和 parser 的并发协议

V25 清理准入不新增进程内 mutex，而是复用同一父 document 行形成确定顺序；chunks 列表只增加与这些写锁冲突的
数据库共享行锁：

```text
vector claim first:
  lock document -> chunk PENDING -> PROCESSING -> commit
  V25 later locks document -> sees PROCESSING chunk -> 409
  no vector cleanup / no task

V25 admission first:
  lock document -> REPROCESSING + active task -> commit
  vector claim later fails existing document COMPLETED gate
  no embedding / no Qdrant upsert

V24 DELETE first:
  lock document -> soft delete + deletion task -> commit
  V25 later sees invisible document -> 404

V25 admission first:
  lock document -> REPROCESSING + active task -> commit
  V24 DELETE later sees REPROCESSING -> deletion conflict 409

parser:
  process-pending only sees PENDING
  REPROCESSING cannot be claimed
  final cleanup transaction commits PENDING only after old chunks are gone

chunk list:
  lock live knowledge_base FOR SHARE -> visible document FOR SHARE
  hold both locks through count/data
  V25/V24/V5 document FOR UPDATE waits, so page items and total share one state boundary

active reprocess task:
  admission/retry CAS first commits VECTOR_DELETING
  concurrent duplicate sees VECTOR_DELETING -> 409; it cannot call Gateway
  recorded Gateway failure -> VECTOR_DELETE_RETRYABLE
  exactly one later POST wins VECTOR_DELETE_RETRYABLE -> VECTOR_DELETING CAS
  Gateway success -> READY_TO_FINALIZE
  READY_TO_FINALIZE resume skips Gateway and only runs the final database transaction
```

因此 V25 实现阶段必须同步修改 V24 删除准入：document `PROCESSING` **或** `REPROCESSING` 都使用既有
`KNOWLEDGE_DOCUMENT_DELETION_CONFLICT` / 409。V24 契约文档中当前只写 `PROCESSING` 的状态图、实现说明和
面试回答也必须同步更新，避免保留过时边界；本轮先行契约阶段不提前修改 V24 已验收实现或文档。

V5 的 `selectVectorizableOwnedForChunkClaimForUpdate(...)` 已要求 document `COMPLETED` 并锁定同一行，V25
不放宽该 SQL。V25 需要回归验证上述两种顺序，但不能把 SQL-shape/unit 测试表述为真实生产并发证明。

`cleanup_status` 只保证正常请求与已记录失败重试的**本地准入单飞**：同一时刻最多一个当前服务请求会新发起 vector
删除。read timeout/连接丢失后，远端旧操作仍可能继续，因此不能声称全局只有一个 Qdrant 操作；真正保护新代
vectors 的是 task 固定的 generation filter。它不是 exactly-once，也不能自行恢复进程崩溃后遗留的
`VECTOR_DELETING`；该失败边界必须与第 3 节保持一致。

## 6. chunks 列表可见性收口

当前
`GET /api/v1/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/chunks` 由
`DocumentProcessingService.listOwnedDocumentChunks(...)` 在验证 owner、知识库和文档后直接按 document scope
查询 chunks，没有检查 document `parse_status`。V25 必须将该读取收口为：

```text
short read transaction
  -> lock current-owner live knowledge_base FOR SHARE
  -> lock current-owner visible document FOR SHARE

visible COMPLETED document
  -> 使用带 document/knowledge-base 可见性和 COMPLETED 谓词的专用分页 SQL
  -> 按 chunk_index ASC 查询并返回分页 chunks

visible non-COMPLETED document
  -> HTTP 200 + empty page
  -> items = [], total = 0, hasNext = false
  -> 保留请求中的 page / pageSize
  -> 不执行 chunk 分页查询

invisible document or soft-deleted parent
  -> 仍是统一 404，不伪装成空页
```

`listOwnedDocumentChunks(...)` 必须成为只执行查询的短事务，并固定按
`knowledge_base -> knowledge_document` 顺序取得 `FOR SHARE` 行锁，持锁直至 count/data 查询都结束。该事务不能
启用会禁止 PostgreSQL locking read 的数据库级 read-only 模式。父锁稳定“未软删除”可见性，document share lock 与
V25/V24/V5 的 document `FOR UPDATE` 冲突：列表先获得锁时，它在线性化于准入之前读取；V25 先提交
`REPROCESSING` 时，列表随后看到非 `COMPLETED` 并返回 canonical 空页。不能只依赖默认 `READ COMMITTED` 下两个
独立分页 statement 使用相同 WHERE，因为 count 与 data 之间仍可能发生状态切换并产生
`items=[] / total=N`。

Service 的锁内状态判断可以作为非 `COMPLETED` 的快速空页分支，但不能成为唯一门槛。`COMPLETED` 分支必须新增
专用 Mapper 分页查询；其实际 count/data SQL 都要从 `knowledge_chunk` 关联 `knowledge_document` 与
`knowledge_base`，同时限定完整三元 scope、current owner、父子 owner 对齐、两处 `deleted_at IS NULL`、
`kc.vector_generation = kd.vector_generation` 和 `kd.parse_status = 'COMPLETED'`，不得继续调用只按 chunk scope 的
通用 `BaseMapper.selectPage(...)`。count 与 data 必须复用相同可见性谓词，并在上述共享锁事务中执行。

因此 `REPROCESSING` 准入一提交，旧 chunks 即使尚未物理删除也不再通过后续列表 SQL 可见；最终成功后它们才在
数据库中实际消失。`PENDING`、`PROCESSING` 和 `FAILED` 同样只代表当前没有可发布的完整 chunk 集，不能返回
历史或异常遗留数据。任何未知/异常状态也按“非 `COMPLETED`”fail closed 为空页。现有 V7 canonical retrieval
SQL 已要求 parent document `COMPLETED`，V25 不新增检索接口，也不把 stale Qdrant hit 直接返回给客户端。

## 7. 实现目标与验收契约

本次实现变更限定为：

1. 新 Flyway V11、`DocumentParseStatus.REPROCESSING`、document/chunk `vector_generation`、
   `KnowledgeDocumentReprocessTask` 与 `KnowledgeDocumentReprocessTaskMapper`；
2. `KnowledgeDocumentReprocessService`、`KnowledgeDocumentReprocessTransactionService` 与既有 Controller
   路由的委托切换，同时保留 V22 service/Mapper 原语；
3. `KnowledgeDocumentMapper` 的 V25 owner/live-parent document lock 与 generation 条件状态写入；复用
   `KnowledgeChunkMapper.hasProcessingChunkByDocumentScope(...)`、`deleteByDocumentScope(...)`；
4. parser 落库复制 generation、V5 claim 复核 generation、vector payload 写 `vectorGeneration`，以及
   `VectorDocumentScope`/Qdrant/in-memory adapter 的 V24 全代删除与 V25 旧代删除两种服务端模式；Qdrant upsert
   的 outcome-unknown 分类与 V5 保持 `PROCESSING` 的安全收口；
5. `ErrorCode.KNOWLEDGE_DOCUMENT_REPROCESS_UNAVAILABLE`；
6. V24 删除准入对 `REPROCESSING` 的 409 收口；
7. `listOwnedDocumentChunks(...)` 的父/文档共享锁、非 `COMPLETED` 快速空页，以及带
   owner/live-parent/`COMPLETED`/generation 谓词的专用 chunk 分页 Mapper；
8. unit/mock、MyBatis SQL-shape、Controller、并发与事务隔离 PostgreSQL/Flyway、`.http` 手工验收。

本地聚焦回归命令为：

~~~text
cd backend
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  mvn -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar \
  -Dtest=KnowledgeDocumentReprocessServiceTest,KnowledgeDocumentReprocessTransactionServiceTest,KnowledgeDocumentReprocessTaskMapperTest,KnowledgeDocumentServiceTest,KnowledgeDocumentMapperTest,KnowledgeChunkMapperTest,DocumentProcessingServiceTest,DocumentProcessingTransactionServiceTest,KnowledgeDocumentDeletionTransactionServiceTest,ChunkVectorizationServiceTest,ChunkVectorizationTransactionServiceTest,QdrantVectorStoreGatewayTest,InMemoryVectorStoreGatewayTest,KnowledgeDocumentDetailControllerTest test
~~~

自动化与本地数据库验收边界如下；其中已落地的 unit/mock、Controller、Mapper SQL-shape、adapter 与
V1–V11 migration 验证均已执行，生产级并发/真实事务故障注入仍不得从这些证据外推：

1. V11 从空 PostgreSQL 顺序应用 V1–V11；新 CHECK 接受 `REPROCESSING`，document/chunk generation 默认 0 且
   拒绝负数，task 复合外键、source generation、步骤约束和部分唯一索引生效；第二条 active task 被拒绝，而已有
   completed history 后可创建下一条 active task。
2. V22 `FAILED -> PENDING` 原回归全部通过，且断言不创建 V25 task、不调用 vector Gateway、不删除 chunks。
3. `COMPLETED` 首次准入的完整锁/owner/父资源/状态/PROCESSING chunk 条件，以及
   `REPROCESSING + active task` 恢复；断言 task 固定旧 generation、document 精确加 1，四类不可见统一 404，其他
   状态统一 409。
4. parser 新 chunks 复制当前 document generation；V5 candidate/claim 拒绝 generation 不一致的 chunk，upsert
   payload 携带服务端 `vectorGeneration`；definitive upsert failure 仍进入 `FAILED`，outcome-unknown upsert 保持
   `PROCESSING` 和受控摘要，V24/V25 对其均为 409。
5. Qdrant request-body 与 in-memory adapter 测试证明 V25 generation 0 同时匹配 0/legacy missing、后续只匹配精确
   旧代，而 V24 仍不限 generation；用可控 fake 模拟“旧删除超时、重试完成、新代 upsert、旧删除迟到”，断言新代
   point 保留。该测试证明 filter/编排，不冒充真实网络时序证明。
6. vector claim 与 V25、V24 DELETE 与 V25 的两个并发顺序；测试证明代码与 SQL 门槛，不宣称生产负载下的
   线性化证据。
7. vectors -> mark vector step -> chunks/PENDING/task completion 的顺序；并在隔离 PostgreSQL 的事务集成用例中
   注入最终状态写入失败，确认 scoped chunk DELETE、document `PENDING` 与 task completion 共同回滚；
   `DocumentStorage.delete(...)` 从不被调用。
8. Gateway 失败记录受控摘要、retry count 与失败时间并返回新 503；下一次同 owner POST 使用同一 source
   generation 恢复，已完成 vector step 时跳过 Gateway；失败证据写入失败仍优先返回原 Gateway 503，且不声称
   retry 字段已落库。
9. task Mapper 所有步骤按 task ID + 三元 scope 更新/reread，不把多条 completed history 当成唯一行。
10. active task 单飞状态机：在途 `VECTOR_DELETING` 重复 POST 为 409、失败后仅一个请求可 CAS 重试、
   `READY_TO_FINALIZE` 恢复不再调用 Gateway，以及无 lease 时卡住的 `VECTOR_DELETING` 不会被超时抢占。
11. chunks 列表对 `PENDING / PROCESSING / FAILED / REPROCESSING` 参数化返回 canonical 空页且不执行 chunk 分页；
    `COMPLETED` 专用 Mapper 的 count/data SQL 均包含 current-owner、live-parent、document `COMPLETED` 与 generation
    谓词。在隔离 PostgreSQL 中把 V25 准入插入 count/data 之间，验证父/文档共享锁使列表或完整旧页先提交、或在
    准入后得到 `items=[] / total=0`，不能得到混合页。
12. 首次准入与最终事务分别断言 `parse_error` 被清空、document `updated_at` 使用服务端时间；最终事务还要断言
    task 的 `chunks_deleted_at/completed_at/updated_at/cleanup_status` 同时落库。
13. Controller 继续使用原 POST、固定成功消息和八字段 DTO；成功映射最终事务返回的 `PENDING` snapshot，不做
    post-commit current-row reread，并覆盖统一 404、既有 409 与新增 503。

隔离 PostgreSQL 的 V1–V11 验证只能称为本地 migration 证据；unit/mock 与 MyBatis SQL-shape 只能证明分支、
调用顺序和 SQL 约束。它们不证明真实 Qdrant 网络/认证、生产 PostgreSQL 并发、进程崩溃恢复、跨系统原子性
或 RAG 效果。

`backend/http/knowledge-document.http` 的 V25 手工验收应使用独立的 V25 knowledge base 与正常 Markdown
fixture，避免覆盖现有 `agentflowKnowledgeBaseId/agentflowDocumentId`，也避免第二次 `process-pending` 消费 V22
已经重新排入 `PENDING` 的 whitespace-only 坏 fixture。该 setup 只复用既有知识库创建和文档上传接口，不新增
上传能力。可执行 named-request 顺序固定为：

1. `user-auth.http` 的 `Login`；
2. `V25 create a dedicated knowledge base`，将 ID 只保存为本地 `agentflowV25KnowledgeBaseId`；
3. `V25 upload the tracked Markdown fixture`，将 ID 保存为 `agentflowV25DocumentId`，断言 201 / `PENDING`；
4. `V25 process the dedicated pending document`，只断言聚合响应有一次正常 claim/completion；
5. `V25 read the completed document before cleanup`，通过详情断言该文档实际为 `COMPLETED`；
6. `V25 vectorize the dedicated document chunks`，断言聚合响应有成功 vectorization；
7. `V25 read vectorized chunks before cleanup`，断言 PostgreSQL chunk 为 `COMPLETED` 且有 `vectorId`。该读取
   不能单独证明真实 Qdrant point 当前存在；
8. `V25 reprocess the completed document`，断言 200、固定消息、八字段 DTO 与 `parseStatus = PENDING`；
9. `V25 repeat reprocess after successful cleanup`，断言 `KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT` / 409；
10. `V25 read chunks after cleanup`，断言 HTTP 200 空页而不是 404；
11. `V25 explicitly process the requeued document`，只断言聚合响应有一次正常 claim/completion，期间没有上传或替换；
12. `V25 read the reprocessed document`，通过详情断言该文档实际重新进入 `COMPLETED`；
13. `V25 read recreated chunks before vectorization`，断言至少一项且 vectorization status 为 `PENDING`；
14. `V25 explicitly vectorize recreated chunks`，断言聚合响应有成功 vectorization；
15. `V25 read recreated chunks after vectorization`，断言 PostgreSQL chunk 为 `COMPLETED` 且有 `vectorId`。

V25 block 放在现有 V7–V17 验收之后、最终破坏性 V24 DELETE block 之前；V22、V7–V17 与 V24 named requests
继续使用原有本地变量和顺序，不依赖 V25 专用 fixture。

默认 local vector adapter 的 HTTP 成功只证明本地编排。受控 Gateway 失败、503 与恢复应由 mock 自动化验证；
不能为了展示 503 把本地成功路径伪装成真实 Qdrant 故障。remote 模式也只能说明当前配置下实际发起调用，不能
证明生产外部服务 SLA。

## 8. 明确不做

- 新上传接口、文件替换、重新上传、multipart request body，或修改源文件内容/定位；
- 调用 `DocumentStorage.delete(...)`、删除源文件、文档永久软删除、元数据硬删除、恢复已删除文档；
- 修改 V22 `FAILED -> PENDING` 原语，或为 `FAILED` 文档创建清理 task、删除 chunks/vectors；
- 在 reprocess POST 内自动调用 parser、`process-pending`、embedding、`vectorize-pending` 或检索；
- 后台队列、scheduler、自动重试、outbox、任务查询/管理 API、取消、lease、超时回收或强制抢占；
- `VECTOR_DELETING` task 或 outcome-unknown `PROCESSING` chunk 的自动恢复、attempt lease/ownership fencing，或公开
  generation/task 管理 API；
- 复用 `knowledge_document_deletion_task`，或把重解析 task 扩展成源文件删除/文档删除任务；
- 单 chunk、批量、跨知识库、管理员跨 owner 重解析，或客户端提交 task/scope/filter；
- 文档恢复、保留期、task 清理、历史 task 列表或统计；
- rerank、检索策略、context、chat、回答审计、feedback、模型/provider 配置或 Qdrant collection 管理；
- 把 HTTP 200 表述为重新解析、重新向量化或 RAG 可用已经完成；
- 把 unit/mock、SQL-shape、本地 PostgreSQL、local adapter 或一次 remote HTTP 表述为真实生产并发、真实外部
  服务可靠性、跨 PostgreSQL/Qdrant 原子性或 RAG 质量证明。

## 面试问题与回答

### 问题 1：为什么要新增 `REPROCESSING`，不能复用 parser 的 `PROCESSING`？

**回答：** 两个状态拥有不同的 worker 和写入权。`PROCESSING` 表示 parser 已从 `PENDING` 获得 claim，正在读取
源文件并准备原子写入新 chunks；`REPROCESSING` 表示旧 vectors/chunks 正在清理，parser 尚未获得资格。若复用同一
状态，DELETE、chunks 列表、失败恢复和 parser 终态写入都无法区分当前是谁拥有流程。V25 只有在清理完成的最终短
事务中才写 `PENDING`，之后仍由既有 parser 显式认领。

### 问题 2：为什么重解析 task 不能复用 V24 deletion task？

**回答：** V24 删除任务快照 source locator，并以 vectors、源文件、chunks 全部删除和 document 永久软删除为成功
语义；V25 必须保留同一源文件和可见文档，成功后回到 `PENDING`。因此 V25 task 只有三元 scope、旧 vector
generation fence、vector/chunk 步骤和重试证据，没有 `source_deleted_at` 或 storage locator。独立表还能用
“仅未完成任务唯一”的部分索引保留同一文档多轮重解析历史，避免把两种相反生命周期揉进一个状态机。

### 问题 3：为什么 `COMPLETED` 准入必须与 vector claim、DELETE 共享父 document 锁？

**回答：** 候选查询本身不能阻止已读取的 chunk 随后获得 vector claim。共享 `knowledge_document` 行锁后，vector
claim 先提交会留下 `PROCESSING` chunk，V25 在锁内看见后返回 409；V25 先提交会把 document 改为
`REPROCESSING`，后来的 claim 因既有 `COMPLETED` 门槛失败。DELETE 同理：先删除则 V25 看到 404，V25 先准入则
DELETE 必须把 `REPROCESSING` 视为 409。远端 outcome-unknown upsert 也必须保留 chunk `PROCESSING`，否则这把
共享锁只能阻止新 claim，不能阻止已发出的旧请求迟到写回。该协议共同避免旧 worker 在清理后重新 upsert。

### 问题 4：重复 POST 为什么可以恢复未完成 task，却不承诺 exactly-once？

**回答：** PostgreSQL 与 Qdrant 没有共同事务；客户端超时后远端删除仍可能继续。V25 在首次准入或重试 CAS 时先
持久化 `VECTOR_DELETING`，所以并发重复 POST 不能新发第二个本地调用；失败落成 `VECTOR_DELETE_RETRYABLE` 后，
一个后续 POST 才能认领重试。网络层仍可能同时存在迟到旧操作，因此 task 固定递增前的
`source_vector_generation`，所有本轮删除只匹配旧代 points，新 parser/vectorizer 写入递增后的 generation。它仍
不是 exactly-once，但重复或迟到删除不再能命中新代 vectors；没有 attempt lease 时，进程崩溃遗留的
`VECTOR_DELETING` 仍需后续运维修复能力。

### 问题 5：为什么成功响应只是 `PENDING`，并且非 `COMPLETED` 文档的 chunks 列表必须为空？

**回答：** V25 的职责止于清旧和安全重新入队；parser 与 vectorizer 仍由两个既有显式接口执行。`REPROCESSING`
期间数据库里可能暂时还存在旧 chunks，若列表只按 scope 查询就会把已经失效的派生物继续暴露；因此短读事务先
共享锁定父知识库与文档，只有专用 SQL 再次确认 current-owner/live-parent 文档仍为 `COMPLETED` 且 chunk/document
generation 一致时才可以返回 chunks，其他可见状态返回
`items=[] / total=0`。HTTP 200 表示当前配置的 vector Gateway 成功返回、旧 PostgreSQL chunks 已删除并回到
`PENDING`，不能被解释为真实远端删除证据、新 chunks/vectors 已生成，或 RAG 已经可用。

### 问题 6：V25 的自动化和 HTTP 验收能证明什么，不能证明什么？

**回答：** 当前已运行的 unit/mock 与 Mapper SQL-shape 可以证明状态分支、generation filter 请求、
owner/父资源范围、共享锁形状、调用顺序、受控 503 和 chunks 列表门槛；隔离 PostgreSQL 的 migration 验证可证明
V1–V11 schema 与本地样例约束。当前没有把 SQL-shape 测试冒充生产并发线性化，也尚未执行真实事务故障注入或
15 步 HTTP 手工闭环；只有后两者实际运行后，才能分别补充最终事务回滚和同一源文件经显式清理、再次
`process-pending` 和再次 `vectorize-pending` 的可观察闭环。它们仍不能证明真实 Qdrant/网络认证的持续可用性、
生产并发线性化、跨系统强事务、进程级自动恢复或 RAG 质量；这些能力未纳入 V25，或需要独立的真实运行时证据。
