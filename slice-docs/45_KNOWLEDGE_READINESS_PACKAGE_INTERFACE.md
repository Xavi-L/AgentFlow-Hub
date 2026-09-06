# V44 知识库公开就绪读模型接口包说明

> 路线位置：M4G-B1，接续 V43/M4G-A。完成本片不代表知识库管理页面、完整 M4G 或真实 E2E 完成。
> 开工基线：`main@f047cb9`，已获取并核对 `origin/main`（2026-09-06）。
> 依据：RAG Design §4.3/§7、Backend API Design §6.1/§6.2、V37 执行绑定与 snapshot 准入代码。

## 目标与范围

为知识库管理页面提供可直接消费的数据库事实：可解析的知识库 profile/strategy、文档当前
generation 的向量化状态计数和派生 `retrievalReadiness`。保留现有字段、路由、分页、鉴权与
删除可见性，前端只在 `retrievalReadiness=READY` 时显示“可用于 Agent”。

本片只补公开读模型；`parseStatus=COMPLETED` 仅表示解析完成，不等于 Agent 检索就绪。
Readiness 不写入数据库，也不增加可由客户端更新的状态。

## 公开接口

| 现有方法与路径 | 本片补充 |
| --- | --- |
| `GET /api/v1/knowledge-bases` | 每项增加 `embeddingProfileCode`、`chunkStrategyVersion` |
| `GET /api/v1/knowledge-bases/{knowledgeBaseId}` | 同上 |
| `GET /api/v1/knowledge-bases/{knowledgeBaseId}/documents` | 每项增加 generation、计数、Readiness |
| `GET /api/v1/documents/{documentId}` | 同上 |

成功仍为 `200 + ApiResponse`。ID 继续以字符串返回；分页仍使用 `page/pageSize`，最大 100，
排序为 `createdAt DESC, id DESC`，保留 `items/page/pageSize/total/hasNext`。

知识库共享 `KnowledgeBaseResponse`，因此既有 POST/PATCH 的输出也附带两个可解析字段，
其输入、校验、写入逻辑均未改。文档上传/重处理的写响应仍保留原八字段，三个新增字段只由
GET 的数据库聚合填充；不能用写入确认响应猜测后台处理后的就绪度，应刷新 GET。

## 知识库只读配置契约

保留 `id/name/description/embeddingProvider/embeddingModel/chunkSize/chunkOverlap/status/createdAt/updatedAt`。

| 新字段 | 解析条件 | 不兼容或缺失 |
| --- | --- | --- |
| `embeddingProfileCode` | provider 严格等于 `dashscope` 且 model 严格等于 `text-embedding-v4` | 明确 JSON `null` |
| `chunkStrategyVersion` | size 严格等于 800 且 overlap 严格等于 120 | 明确 JSON `null` |

兼容时分别返回 `dashscope-te-v4-1024-cosine` 与 `structured-token-v1`。二者独立解析：例如旧模型
但标准分块参数，返回 profile=null、strategy=`structured-token-v1`。不做大小写归一、trim、回填、
配置修复或迁移。`KnowledgeReadConfiguration` 同时供 V37 resolver 使用，V37 仍要求二者都兼容。

这里的 strategy 是知识库**配置**的解析结果，不能代替当前文档 chunk 上持久化的实际版本。
Profile code 表示服务端支持的配置映射，不证明模型服务、1024 维 collection 或远端 Qdrant 当前可用。
本片不读取或探测外部服务配置。

## 文档读模型契约

保留 `id/knowledgeBaseId/fileName/fileType/fileSize/parseStatus/createdAt/updatedAt`，GET 增加：

```json
{
  "vectorGeneration": 2,
  "vectorization": {
    "pending": 0,
    "processing": 0,
    "completed": 4,
    "failed": 0
  },
  "retrievalReadiness": "READY"
}
```

- `vectorGeneration` 为数据库文档当前的非负 BIGINT，JSON 数字，与既有 generation 契约一致。
- 四个计数均为非负 `long`，只统计同一 owner/KB/document 且 `kc.vector_generation=kd.vector_generation`
  的持久化 chunk；四者之和就是当前 generation 的 chunk 总数。零 chunk 必须返回四个 0。
- 旧 generation 的成功、失败、处理中和策略差异全部不计，也不能让当前 generation 变为 READY。
- 不公开 chunk 内容、原始文件路径、owner ID、parse/vectorization 错误、storage key 或内部策略聚合列。

## 五种 Readiness 与优先级

按下表从上向下匹配，首个命中即为结果。计数始终保留实际值，不因状态不可用而清零。

| 优先级 | 条件 | 结果 |
| --- | --- | --- |
| 1 | 可见知识库不是 `ACTIVE`（例如 `DISABLED`） | `NOT_READY` |
| 2 | parse=`FAILED` | `FAILED` |
| 3 | parse=`PENDING/PROCESSING/REPROCESSING`，或其他非 `COMPLETED` 状态 | `NOT_READY` |
| 4 | parse=`COMPLETED`，知识库任一配置解析为 null | `FAILED` |
| 5 | parse=`COMPLETED` 且当前 generation 非空，实际 chunk strategy 混合或不是 `structured-token-v1` | `FAILED` |
| 6 | parse=`COMPLETED`，且有 PENDING 或 PROCESSING chunk | `INDEXING` |
| 7 | parse=`COMPLETED`，且同时有 COMPLETED 和 FAILED chunk | `DEGRADED` |
| 8 | parse=`COMPLETED`，当前 generation 非空、所有 chunk 均 COMPLETED | `READY` |
| 9 | parse=`COMPLETED`，零当前 chunk 或全部 FAILED | `FAILED` |

禁用父资源、配置不兼容、实际策略不一致的映射是 V44 对原五态规则的补充，不新增第六种状态。
配置或策略失败不能被正常 indexing 掩盖。禁用优先表示当前不可用于 Agent，解析错误仍可由
独立的 `parseStatus=FAILED` 观察。兼容策略下，COMPLETED+FAILED+PENDING 优先返回 INDEXING；
所有 pending/processing 结束后，完成与失败并存为 DEGRADED，全完成才为 READY。

空集合不满足“全完成”：解析完成但零 chunk（包括只有旧 generation chunk）返回 FAILED。
DEGRADED 仅表达部分向量化失败，不表示可以用于 Agent，也不表示检索质量或召回率。

## 与 V37 准入对齐

`AgentKnowledgeBindingMapper.selectReadyDocumentGenerations` 已在 SQL 中要求：owner 一致、父资源
live+ACTIVE、文档 live+COMPLETED、当前 generation 非空且全 COMPLETED、实际 strategy 唯一。
`AgentTaskSnapshotResolver` 再拒绝不支持的单一策略以及不兼容的知识库配置。

V44 的 READY 满足这组文档级要求；仍须另外满足 Agent ACTIVE、绑定、模型、工具和预算等
task 创建条件。一个 READY 文档不承诺整个 Agent 创建请求一定成功。

本片保留 V37 现有错误语义：混合策略不会成为 SQL 候选；单一不支持策略或不兼容知识库配置
仍可能触发 `AGENT_BINDING_INVALID`；没有就绪语料仍为 `RAG_KNOWLEDGE_NOT_READY`。
公开 Readiness 不修改这些异常，也不会将不兼容候选悄悄改为默认配置。

## 查询、一致性与删除边界

四个 GET service 入口均使用独立 `REQUIRES_NEW + REPEATABLE_READ + readOnly` 事务，避免加入
外层较弱隔离级别。service 方法返回时事务结束，早于 HTTP 响应序列化/发送；不锁住源文档，
也不调用模型、向量服务或存储。

文档详情先通过现有 JOIN 查询确定 owner/live 文档及父 KB，再做一次批量聚合。文档列表先在
同一快照中确认 owner/live 父 KB，再做 count/page，最后对**本页 ID** 做一次聚合。所有读取
共享首次 SELECT 建立的数据库快照，不存在“旧 generation 元数据 + 新 generation 计数”的响应。

聚合使用 `knowledge_document + knowledge_base + LEFT JOIN knowledge_chunk`，再次限定
owner、父子一致性、删除标记及 current generation。LEFT JOIN 保留零 chunk 文档。列表不是
逐文档查询：非空页最多 4 条 SELECT（父校验、count、page、aggregate），详情 2 条；空页不执行
aggregate。现有分页拦截器在 total=0 或越界时可能省略 page SELECT。

知识库列表只显示当前 owner 未删除项；详情不可见统一 404。文档列表的父 KB 缺失、跨 owner
或已删除仍为既有 404；文档详情缺失、跨 owner、文档已删除、父 KB 已删除统一 404。
DISABLED 是管理可见状态，不等于软删除。

并发提交发生在本次快照建立之后时，本次可以完整返回删除或重处理前的视图；下一次 GET
看到新状态（包括 404）。这属于快照一致性，不承诺响应发送时仍是最新状态，也不阻塞删除。
不会把删除后的新计数与删除前的旧分页拼成一个响应。

## 实现与验收

- `KnowledgeReadConfiguration`：公开读模型与 V37 共用固定配置解析。
- `DocumentReadinessRow` / `RetrievalReadiness`：派生五态，不写第二套状态机。
- `KnowledgeDocumentReadMapper`：owner/live/current-generation 范围内按本页批量聚合。
- `KnowledgeDocumentService` / `KnowledgeBaseService`：现有 GET 的独立一致快照。
- `KnowledgeReadinessPostgresIntegrationTest`：真实 JWT HTTP 与 PostgreSQL 状态、分页、隔离和并发验收。
- `backend/http/knowledge-base.http` / `knowledge-document.http`：更新现有手工读取断言。

2026-09-06 实际验收：

| 验收层 | 结果与边界 |
| --- | --- |
| focused 回归 | 89/89 通过，含配置解析、知识库/文档 service/controller、V37 resolver 与绑定 mapper |
| 完整常规 Maven 回归 | 661 项执行通过，0 failure/error；当次 700 项中 39 项既有 opt-in PostgreSQL 测试跳过 |
| V44 独立真实 JWT HTTP/PostgreSQL | 11/11 通过、0 跳过；PostgreSQL 18.4 空库应用既有 V1–V20，无新增 migration |
| 脚本与改动检查 | `bash -n`、`git diff --check` 通过 |

V44 集成测试单独显式执行：一个场景表覆盖 11 种 parse/chunk 组合及旧 generation；另有配置
独立解析、混合/不支持策略、owner/删除/禁用可见性、稳定分页与本页批量查询四项测试。六项
参数化并发测试分别在详情和列表聚合前暂停，由另一连接提交 generation、parse、chunk 状态、
KB 配置、文档删除、父 KB 删除变更；验证本次保留完整旧快照、下次看到新状态，且读连接实际
隔离级别为 REPEATABLE_READ。V37 mapper/resolver 同时验证相同语料是否准入。

常规回归先执行，随后新增 V44 opt-in 集成类并单独编译运行；以上 39 跳过项不包含已单独通过的
V44。没有将 skipped 计为通过，也没有重新运行其他不相关 PostgreSQL 集成套件。

复现 V44 真实验收（脚本每次创建全新临时库并自动关闭，默认端口可用 `V44_PG_PORT` 调整）：

```bash
env JAVA_HOME=/Users/xavier/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  bash scripts/v44-readiness-acceptance.sh \
  -DargLine=-javaagent:/Users/xavier/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar
```

当次本地日志：`/tmp/agentflow-v44-focused.log`、`/tmp/agentflow-v44-regression.log`、
`/tmp/agentflow-v44-postgres.log`。临时数据库运行目录由脚本在日志开头打印，产物未加入仓库。
测试只允许使用独立可丢弃 PostgreSQL；外部 Gateway 被隔离，并逐项验证没有调用模型/embedding/
vector store。这是本地真实 HTTP/PostgreSQL 证据，非真实服务 E2E。

## 明确不做

- 知识库页面、Agent 配置页面、前端类型或交互改造；
- 上传、FAILED 重试、COMPLETED 重处理或清理流程改造；
- 新增公开路径、新增请求配置能力或数据库 migration；
- 模型、embedding、Qdrant 调用或可用性探测；
- 动态 profile 表、自动迁移不兼容配置、重新向量化或旧 generation 回退；
- 真实 provider E2E、召回率/质量评测。

后续依次推进知识库页面、Agent 配置页面和真实 E2E，均未纳入本切片。

## 面试问题与回答

**问题 1：为什么解析完成仍可能不可用于 Agent？**

**回答：**解析只产生 chunk。V44 还要求当前 generation 非空、所有 chunk 向量化完成、实际策略
一致且受支持、知识库配置兼容并 ACTIVE。零 chunk、仅有旧代数据、部分失败都不算 READY。

**问题 2：为什么不用数据库字段保存 retrievalReadiness？**

**回答：**它由父 KB、文档和 chunk 的事实派生。再保存一份可写状态会增加同步负担。本片在同一
repeatable-read 快照内计算，保持 parse、向量化与检索准入三个维度独立。

**问题 3：如何避免列表产生 N+1 查询和重处理竞态？**

**回答：**只把本页 ID 交给一次 aggregate，LEFT JOIN 只取相同 owner/KB/document/current generation。
父校验、count、page 和 aggregate 使用同一个独立 RR 事务；并发提交不会让这些 SELECT 混用新旧数据。
验收以可控 latch 暂停读请求、让另一数据库连接提交，再检查本次与下次 HTTP 结果。

**问题 4：为什么不兼容配置返回 null，而不是直接填固定 profile？**

**回答：**固定 code 是对实际配置的解析结果，不是资源的默认标签。provider/model 和 size/overlap
分别解析，无法匹配时明确 null；V37 复用同一解析器，并继续拒绝不兼容 snapshot 依赖。

**问题 5：READY 是否证明 Qdrant 在线或 Agent 一定执行成功？**

**回答：**不能。本片只证明当前数据库快照满足文档级准入；不探测远端，Agent 还有其他准入与
执行条件。真实 HTTP/PostgreSQL 验收不等于真实模型/Qdrant E2E，后者属于后续规划。
