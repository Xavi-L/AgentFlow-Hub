# AgentFlow Hub Common 包接口文档

本文档用于指导你先独立完成 `com.agentflow.common` 包。

当前建议：先做 `common`，暂时不要做 `user`。

原因：

- `common` 不依赖 PostgreSQL，适合在数据库还没配置好时练 Spring Boot 基础。
- `user` 会涉及数据库表、密码加密、JWT、Spring Security 鉴权链路，前置依赖更多。
- 后续所有业务模块都会复用 `common` 里的统一响应、异常、分页和 traceId。

---

## 1. 本阶段目标

完成一个最小但像真实项目的后端基础包：

```text
com.agentflow.common
  api
    ApiResponse.java
    PageRequest.java
    PageResult.java
  error
    ErrorCode.java
    BusinessException.java
    GlobalExceptionHandler.java
  web
    TraceIdFilter.java
    TraceIdHolder.java
    HealthController.java
```

本阶段不做：

- 数据库连接。
- 用户登录。
- JWT。
- Redis。
- 业务 CRUD。

---

## 2. REST 接口

### 2.1 健康检查

```http
GET /api/v1/health
```

用途：

- 确认后端服务已启动。
- 返回当前应用名、运行环境和时间。
- 后续前端也可以用它检测后端是否可用。

请求参数：无。

当前最小版代码的成功响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "status": "UP",
    "application": "agentflow-hub",
    "profile": "dev",
    "timestamp": "2026-06-20T17:30:00Z"
  }
}
```

完成本阶段 `ApiResponse`、`TraceIdHolder`、`TraceIdFilter` 后，目标成功响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "status": "UP",
    "application": "agentflow-hub",
    "profile": "dev",
    "timestamp": "2026-06-20T17:30:00+08:00"
  },
  "traceId": "af-20260620-abc123",
  "timestamp": "2026-06-20T17:30:00+08:00"
}
```

说明：

- `code` 固定为 `OK`。
- `data.status` 固定为 `UP`。
- `data.application` 来自 `spring.application.name`。
- `data.profile` 来自当前 active profile，没有时返回 `default`。
- `traceId` 来自 `TraceIdFilter`，当前最小版没有，完成 `TraceIdHolder` 和 `TraceIdFilter` 后再出现。
- 外层 `timestamp` 来自增强后的 `ApiResponse`，当前最小版没有。

---

## 3. common.api

### 3.1 ApiResponse<T>

位置：

```text
backend/src/main/java/com/agentflow/common/api/ApiResponse.java
```

职责：

- 统一所有 Controller 的 JSON 响应格式。
- 成功和失败都用这一种外壳返回。

字段：

```java
private String code;
private String message;
private T data;
private String traceId;
private OffsetDateTime timestamp;
```

推荐静态方法：

```java
public static <T> ApiResponse<T> success(T data)

public static <T> ApiResponse<T> success(String message, T data)

public static <T> ApiResponse<T> fail(String code, String message)

public static <T> ApiResponse<T> fail(ErrorCode errorCode)

public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message)
```

实现要求：

- 成功响应的 `code` 为 `OK`。
- 成功响应的默认 `message` 为 `success`。
- `timestamp` 使用 `OffsetDateTime.now()`。
- `traceId` 从 `TraceIdHolder.getTraceId()` 获取。

---

### 3.2 PageRequest

位置：

```text
backend/src/main/java/com/agentflow/common/api/PageRequest.java
```

职责：

- 接收分页请求参数。
- 统一限制页码和页大小。

字段：

```java
private int page = 1;
private int pageSize = 20;
```

方法：

```java
public int offset()
```

规则：

- `page` 最小为 1。
- `pageSize` 最小为 1。
- `pageSize` 最大为 100。
- `offset = (page - 1) * pageSize`。

---

### 3.3 PageResult<T>

位置：

```text
backend/src/main/java/com/agentflow/common/api/PageResult.java
```

职责：

- 统一分页响应结构。

字段：

```java
private List<T> items;
private int page;
private int pageSize;
private long total;
private boolean hasNext;
```

推荐静态方法：

```java
public static <T> PageResult<T> of(List<T> items, int page, int pageSize, long total)
```

`hasNext` 计算规则：

```text
page * pageSize < total
```

---

## 4. common.error

### 4.1 ErrorCode

位置：

```text
backend/src/main/java/com/agentflow/common/error/ErrorCode.java
```

职责：

- 管理项目通用错误码。
- 每个错误码包含业务 code、默认 message、HTTP 状态码。

推荐先实现这些：

```java
OK("OK", "success", 200)
COMMON_PARAM_INVALID("COMMON_PARAM_INVALID", "Request parameter is invalid", 400)
COMMON_REQUEST_BODY_INVALID("COMMON_REQUEST_BODY_INVALID", "Request body is invalid", 400)
COMMON_NOT_FOUND("COMMON_NOT_FOUND", "Resource not found", 404)
SYS_INTERNAL_ERROR("SYS_INTERNAL_ERROR", "Internal server error", 500)
```

字段：

```java
private final String code;
private final String message;
private final int httpStatus;
```

---

### 4.2 BusinessException

位置：

```text
backend/src/main/java/com/agentflow/common/error/BusinessException.java
```

职责：

- 业务代码主动抛出的异常。
- 例如参数不合法、资源不存在、状态冲突。

字段：

```java
private final ErrorCode errorCode;
```

构造方法：

```java
public BusinessException(ErrorCode errorCode)

public BusinessException(ErrorCode errorCode, String message)
```

使用示例：

```java
throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Knowledge base not found");
```

---

### 4.3 GlobalExceptionHandler

位置：

```text
backend/src/main/java/com/agentflow/common/error/GlobalExceptionHandler.java
```

职责：

- 捕获 Controller 层抛出的异常。
- 转成统一 `ApiResponse`。

注解：

```java
@RestControllerAdvice
```

需要处理的异常：

```java
@ExceptionHandler(BusinessException.class)
public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex)

@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex)

@ExceptionHandler(Exception.class)
public ResponseEntity<ApiResponse<Void>> handleException(Exception ex)
```

规则：

- `BusinessException` 使用异常里的 `ErrorCode` 和 message。
- 参数校验异常返回 `COMMON_PARAM_INVALID`。
- 未知异常返回 `SYS_INTERNAL_ERROR`。
- HTTP 状态码来自 `ErrorCode.httpStatus`。

---

## 5. common.web

### 5.1 TraceIdHolder

位置：

```text
backend/src/main/java/com/agentflow/common/web/TraceIdHolder.java
```

职责：

- 使用 `ThreadLocal` 保存当前请求的 traceId。
- 供 `ApiResponse` 和日志使用。

推荐方法：

```java
public static void setTraceId(String traceId)

public static String getTraceId()

public static void clear()
```

---

### 5.2 TraceIdFilter

位置：

```text
backend/src/main/java/com/agentflow/common/web/TraceIdFilter.java
```

职责：

- 每个 HTTP 请求进来时生成或读取 traceId。
- 响应头里写回 `X-Trace-Id`。
- 请求结束后清理 `ThreadLocal`。

建议继承：

```java
OncePerRequestFilter
```

请求头：

```http
X-Trace-Id: af-client-test-001
```

规则：

- 如果请求头已经有 `X-Trace-Id`，沿用它。
- 如果没有，就生成一个。
- 生成格式建议：

```text
af-yyyyMMdd-随机短字符串
```

响应头也要带：

```http
X-Trace-Id: af-20260620-abc123
```

---

### 5.3 HealthController

位置：

```text
backend/src/main/java/com/agentflow/common/web/HealthController.java
```

职责：

- 提供 `GET /api/v1/health`。
- 返回统一 `ApiResponse<HealthResponse>`。

响应 DTO 可以先写成内部 record：

```java
public record HealthResponse(
        String status,
        String application,
        String profile,
        OffsetDateTime timestamp
) {
}
```

---

## 6. 推荐实现顺序

按照这个顺序写，最不容易乱：

1. `TraceIdHolder`
2. `TraceIdFilter`
3. `ApiResponse`
4. `ErrorCode`
5. `BusinessException`
6. `GlobalExceptionHandler`
7. `PageRequest`
8. `PageResult`
9. 调整 `HealthController`，让它返回新版 `ApiResponse`

---

## 7. 验收方式

### 7.1 编译

在 `backend` 目录运行：

```bash
mvn -q -DskipTests compile
```

要求：编译成功。

### 7.2 健康检查

启动后访问：

```bash
curl -i http://localhost:8080/api/v1/health
```

要求：

- HTTP 状态码是 `200`。
- 响应 JSON 里有 `code`、`message`、`data`、`traceId`、`timestamp`。
- 响应头里有 `X-Trace-Id`。

### 7.3 自定义 traceId

运行：

```bash
curl -i -H "X-Trace-Id: af-manual-test-001" http://localhost:8080/api/v1/health
```

要求：

- 响应头 `X-Trace-Id` 是 `af-manual-test-001`。
- 响应体里的 `traceId` 也是 `af-manual-test-001`。

---

## 8. 学习重点

完成这个包后，你应该能讲清楚：

- 为什么 Controller 不直接返回裸对象，而是返回统一 `ApiResponse`。
- 为什么异常不在每个 Controller 里 try/catch，而是用 `GlobalExceptionHandler`。
- 为什么每个请求要有 `traceId`。
- 为什么分页请求和分页响应要统一。
- 为什么 `common` 包不能依赖 `user`、`knowledge`、`agent` 等业务模块。

---

## 9. 完成后再进入 user

当 `common` 包完成后，再开始 `user` 包：

```text
user
  controller
  service
  repository
  model
  dto
  security
```

那时需要先配置 PostgreSQL，并创建第一张表 `app_user`。
