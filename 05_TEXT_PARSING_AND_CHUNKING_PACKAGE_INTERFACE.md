# AgentFlow Hub Knowledge：V4 文本解析与分块切片

本文件描述当前实现的 V4：**读取已安全接收、状态为 `PENDING` 的 `.txt` / `.md` 原始文件，按其
知识库的 `chunkSize` / `chunkOverlap` 生成稳定的 `knowledge_chunk`，并把文档更新为
`COMPLETED` 或 `FAILED`。**

它建立在不可修改的 Flyway V1 `app_user`、V2 `knowledge_base`、V3 `knowledge_document`
之上。V4 的完成标准是“文本块可直接查看、顺序可验证、失败可追踪”；它**不**生成 embedding、
不连接 Qdrant、不做向量检索、不引入异步队列或自动重试。

## 1. 完成后的最小闭环

```text
POST /knowledge-bases/{kbId}/documents      -> 原始 TXT/MD + PENDING
POST /knowledge-bases/{kbId}/documents/process-pending
                                             -> 读取、解析、切分、落库
GET  /knowledge-bases/{kbId}/documents/{docId}/chunks
                                             -> 审阅稳定文本块
```

状态机如下：

```text
PENDING
  -- 条件更新（仅仍为 PENDING 时） --> PROCESSING
  -- 全部 knowledge_chunk 插入成功 --> COMPLETED
  -- 读取 / UTF-8 解码 / 解析 / 分块 / 落库任一步失败 --> FAILED
```

`PROCESSING` 是并发保护：两个请求同时处理同一文档时，只有一个请求可以完成条件更新；另一个
请求会把该文档计入 `skipped`，并且不会读取文件、不会重复插入 chunk。

“全部 chunks 插入”和“改为 `COMPLETED`”在同一个独立数据库事务中完成。若中途任何一次插入
失败，事务回滚，文档随后会在另一个短事务中标为 `FAILED`，并记录受控的简短 `parse_error`。
单个失败不会阻断同一批剩余的 PENDING 文档。

## 2. Flyway V4 数据契约

位置：

```text
backend/src/main/resources/db/migration/V4__create_knowledge_chunk.sql
```

V4 新建 `knowledge_chunk`：

| 字段 | 当前含义 |
| --- | --- |
| `id` | MyBatis-Plus 生成的 BIGINT 主键 |
| `user_id` / `knowledge_base_id` / `document_id` | 便于未来 owner、知识库和文档范围查询的冗余范围字段 |
| `chunk_index` | 文档内稳定的 **0 起始** 顺序 |
| `content` | 当前权威的 chunk 文本，保存在 PostgreSQL |
| `title_path` | 可选 Markdown ATX 标题路径，例如 `支付 / 退款` |
| `char_count` | Unicode code point 数，不是 Java UTF-16 `String.length()` |
| `token_count` | 当前轻量估算 token 数 |
| `created_at` / `updated_at` | 创建与落库时间 |

`knowledge_document` 也新增 `(id, knowledge_base_id, user_id)` 唯一约束；`knowledge_chunk`
使用对应的三列复合外键，因而 chunk 中冗余的 owner / KB 范围不可能与源文档不一致。`UNIQUE(document_id,
chunk_index)` 则保证一个文档内不会出现两个相同顺序号的块。

这里没有 `vector_id`、embedding、Qdrant 配置或内容 hash。它们尚无 V4 行为价值，等向量化切片
再通过新的 Flyway migration 加入，不能回改 V4。

## 3. 文本解析与规范化规则

V4 只接受 V3 已接收的 `TXT` / `MD`，并将它们视为 **UTF-8** 文本：

- 可选 UTF-8 BOM 会移除；非法 UTF-8 不会静默替换为乱码，而是把文档标记为 `FAILED`；
- `CRLF` 与 `CR` 统一为 `LF`；连续超过两行的空行压缩为两行；
- Markdown fenced code 内的空行保持不变，避免轻量清洗意外改写代码块；
- TXT 只产生规范化正文；Markdown 保留原始 Markdown 正文，并轻量识别 fenced code 外的 ATX
  标题（`#` 到 `######`）形成 `titlePath`；本轮不引入完整 Markdown AST、HTML 渲染或 PDF 解析。

解析层只通过 `DocumentStorage.open(StoredDocument)` 打开数据库保存的受控 `bucket/object key`。它
不会拼接用户文件名或绝对磁盘路径；本地实现继续校验 bucket 和路径不能逃出已配置的存储根目录。

## 4. 分块规则

V2 中已有的 `chunkSize` / `chunkOverlap` 沿用 RAG 设计，单位是 **estimated tokens**，不是字符数。
当前轻量估算器的固定规则是：

- 每个 CJK、日文假名或韩文 Unicode code point 估算为 1 token；
- 普通连续英文/数字词估算为 1 token；异常长的无空格英文/数字串（URL、base64、minified
  identifier 等）每 8 个 Unicode code point 继续分段，避免一个超长 token 绕过 chunk 预算；
- 每个非空白符号估算为 1 token；
- 空白不计 token。

分块器按 token 边界切分，优先在足够接近 `chunkSize` 的段落边界结束；遇到超长段落或无空格文本
才硬切。下一块从上一块最后 `chunkOverlap` 个 token 的起点开始，因此相邻块拥有精确的 token
overlap。任何只含空白的文件在规范化后都不能产生空 chunk，而会转为 `FAILED`。

V4 同步处理会先在内存中生成当前文档的 token spans / chunk drafts，再用一个短事务一起落库，因此有
两个明确的运行时上限：每篇源文档最多 `500,000` 个 Unicode code point，每篇最多 `10,000` 个
chunk。两项检查都在分配 token spans 前执行；chunk 数预检按段落优先策略的**最小前进步长**计算，
循环中也保留硬上限。若来源过大，或 `chunkSize` / `chunkOverlap` 的组合预计会超过上限，文档会以
受控错误转为 `FAILED`，而不会让一次 20 MB 上传扩张成百万级内存对象或长期占用数据库事务。可拆分
原始文档，或在下一阶段采用流式/异步处理。

`titlePath` 是辅助 metadata，不应因为一个异常长标题导致正文处理失败。V4 会将它按 Unicode
code point 截断为最多 512 个字符，并以 `…` 结尾，恰好匹配数据库的 `VARCHAR(512)` 契约；chunk
正文本身不会因此被截断。

该估算器刻意不伪装成某个 embedding 模型的 tokenizer。以后若更换为模型级 tokenizer，只需替换
`TokenEstimator` 实现并为新策略建立新的重处理版本，不应悄悄改变已经完成的文本块。

## 5. HTTP 接口

### 5.1 显式处理当前 PENDING 文档

```http
POST /api/v1/knowledge-bases/{knowledgeBaseId}/documents/process-pending
Authorization: Bearer <accessToken>
```

成功响应为 `200 OK`：

```json
{
  "code": "OK",
  "message": "Pending documents processed",
  "data": {
    "discovered": 2,
    "claimed": 2,
    "completed": 1,
    "failed": 1,
    "skipped": 0
  }
}
```

`discovered` 是本次开始时查到的 PENDING 数，`claimed` 是成功由本请求认领的数量，`skipped` 是已被
并发请求抢先认领的数量。正常情况下 `claimed = completed + failed`。

接口先按 JWT owner 查询知识库；不存在、非 owner、软删除知识库均返回已有的 `404
COMMON_NOT_FOUND`。V3 上传仍要求知识库为 `ACTIVE`；V4 允许 owner 完成一个后来变为 `DISABLED`
的知识库中已经接收的 PENDING 文档——它不会接收新资料，只是完成既有资料的收尾处理。

V4 不自动处理 `FAILED`，也不回收遗留 `PROCESSING`。显式重试和 stale-claim 恢复需要单独定义
重处理/向量一致性语义，留给后续切片。

### 5.2 查看自己的 chunk

```http
GET /api/v1/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/chunks?page=1&pageSize=20
Authorization: Bearer <accessToken>
```

结果按 `chunkIndex ASC` 分页。响应只在当前 owner 已通过知识库和文档范围检查后返回正文、标题路径、
字符数与估算 token 数；不会暴露 `userId`、原始文件存储对象键、磁盘路径或 `parse_error`。

## 6. IDEA 手工验收

打开：

```text
backend/http/knowledge-document.http
```

按顺序运行：Login → Create knowledge base → Upload fixture → Process pending → List documents → Inspect
chunks。上传响应会把文档 ID 只保存在 IDEA HTTP Client 的本地 session，随后 chunk 请求验证：

1. 上传文档初始状态是 `PENDING`；
2. `process-pending` 返回处理汇总；
3. 文档列表中该文档状态变为 `COMPLETED`（若文件合法）；
4. chunk 接口至少返回一个 `chunkIndex = 0` 的正文块。

也可以在 PostgreSQL Query Console 使用：

```sql
SELECT id, file_name, parse_status, parse_error, updated_at
FROM knowledge_document
ORDER BY created_at DESC, id DESC;

SELECT document_id, chunk_index, title_path, char_count, token_count, content
FROM knowledge_chunk
WHERE document_id = <document_id>
ORDER BY chunk_index ASC;
```

这两条查询把“原始文档状态”和“确定性 chunk 序列”分开观察，是后续 embedding / Qdrant 接入前的可靠
输入验收点。
