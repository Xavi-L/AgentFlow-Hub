# AgentFlow Hub User 包：注册、登录与 JWT 鉴权接口说明

本文件描述当前可运行的 `user` 纵切片：用户注册、登录、JWT access token 与当前用户查询。它建立在已执行的 Flyway `V1__create_app_user.sql` 之上；不需要也不能修改 V1。

## 1. 这次新增的分层

```text
user
  controller/AuthController.java        接收 HTTP 请求并返回 201 JSON
  dto/RegisterRequest.java              公开请求字段与校验规则
  dto/RegisteredUserResponse.java       安全的响应字段
  dto/LoginRequest.java                 登录请求字段与校验规则
  dto/LoginResponse.java                access token 与安全用户摘要
  dto/CurrentUserResponse.java          /users/me 的安全用户摘要
  model/AppUser.java                    app_user 表映射
  repository/AppUserMapper.java         MyBatis-Plus 数据访问入口
  security/PasswordEncoderConfig.java   BCrypt PasswordEncoder Bean
  security/JwtService.java              JWT 的签发和解析
  security/JwtAuthenticationFilter.java Bearer token -> SecurityContext
  security/SecurityConfig.java          无状态 Spring Security 规则
  security/AuthenticatedUser.java       安全上下文中的当前用户
  service/UserRegistrationService.java  查重、哈希、插入、事务边界
  service/UserLoginService.java         比对、状态检查、登录审计、签发 token
  service/CurrentUserService.java       每次认证请求确认账号仍为 ACTIVE
```

调用路径：

```text
HTTP JSON
  -> AuthController
  -> @Valid 校验 RegisterRequest
  -> UserRegistrationService
  -> AppUserMapper
  -> app_user
```

带 JWT 的请求多了一层安全链：

```text
HTTP Authorization: Bearer <token>
  -> TraceIdFilter
  -> JwtAuthenticationFilter
  -> JwtService（签名、issuer、过期时间）
  -> CurrentUserService（数据库仍为 ACTIVE 且未软删除）
  -> SecurityContext
  -> UserController
```

## 2. 注册接口

```http
POST /api/v1/auth/register
Content-Type: application/json
```

请求体：

```json
{
  "username": "xavier_01",
  "password": "a-long-enough-password",
  "email": "xavier@example.com",
  "displayName": "Xavier"
}
```

字段规则：

| 字段 | 规则 | 为什么 |
| --- | --- | --- |
| `username` | 3–64 位英文、数字或下划线 | 与 `VARCHAR(64)` 对齐，避免空白和难以查询的名称 |
| `password` | 8–72 个字符 | BCrypt 输入边界的保守限制 |
| `email` | 可省略；提供时必须合法且不超过 128 位 | 与数据库的可空唯一邮箱字段对齐 |
| `displayName` | 非空，最多 64 位 | 数据库字段为 `NOT NULL` |

服务端控制且客户端不得传入：`id`、`role`、`status`、`passwordHash`、时间戳和 `deletedAt`。

## 3. 成功与失败响应

成功时状态码为 `201 Created`，但仍使用项目统一外壳：

```json
{
  "code": "OK",
  "message": "User registered",
  "data": {
    "id": "1234567890123456789",
    "username": "xavier_01",
    "email": "xavier@example.com",
    "displayName": "Xavier",
    "role": "USER"
  },
  "traceId": "af-20260729-xxxxxxxx",
  "timestamp": "2026-07-29T22:00:00+08:00"
}
```

`id` 返回字符串，避免 JavaScript `Number` 对 `BIGINT` 失真。绝不返回 `passwordHash`。

| 场景 | HTTP | code |
| --- | --- | --- |
| 字段校验失败 | 400 | `COMMON_PARAM_INVALID` |
| JSON 格式错误 | 400 | `COMMON_REQUEST_BODY_INVALID` |
| 用户名已存在 | 409 | `USER_USERNAME_ALREADY_EXISTS` |
| 邮箱已存在 | 409 | `USER_EMAIL_ALREADY_EXISTS` |
| 并发插入触发数据库唯一约束 | 409 | `USER_ACCOUNT_ALREADY_EXISTS` |

## 4. 密码与并发边界

- 明文密码只在请求处理过程中存在；写入数据库前必经 BCrypt。
- BCrypt 对同一密码每次产生不同哈希是正常行为，因为它使用随机 salt。
- Service 的查重用于给用户明确错误；数据库唯一约束才是并发下最终正确性保证。
- 当前 V1 的唯一约束覆盖软删除行，所以删除用户后用户名和邮箱仍不能复用。若未来要改变，必须新增 V2 migration，不能编辑 V1。

## 5. 手动验收

在 IDEA 的 Spring Boot Run Configuration 中设置 `POSTGRES_PASSWORD` 为 `agentflow` 数据库账号密码后启动应用。然后在 IDEA HTTP Client、Postman 或 curl 发送上面的 JSON。

IDEA 用户可直接打开 `backend/http/user-registration.http`，替换其中的 `username`、`password` 和邮箱占位值，再点击请求行左侧绿色运行图标。这个文件不含真实密码；不要把替换后的真实密码提交到 Git。

注册成功后，在 Query Console 中确认：

```sql
SELECT id, username, email, password_hash, display_name, role, status
FROM app_user;
```

`password_hash` 应以 BCrypt 格式保存，而不是请求中的明文密码。

## 6. 登录接口

```http
POST /api/v1/auth/login
Content-Type: application/json
```

请求体：

```json
{
  "username": "xavier_01",
  "password": "a-long-enough-password"
}
```

成功时为 `200 OK`：

```json
{
  "code": "OK",
  "message": "Login successful",
  "data": {
    "accessToken": "eyJ...",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "user": {
      "id": "1234567890123456789",
      "username": "xavier_01",
      "displayName": "Xavier",
      "role": "USER"
    }
  },
  "traceId": "af-20260730-xxxxxxxx",
  "timestamp": "2026-07-30T22:00:00+08:00"
}
```

`expiresIn` 的单位固定是秒。登录成功时服务端会更新 `last_login_at` 与 `updated_at`，但不会返回密码哈希、邮箱或内部状态字段。

| 场景 | HTTP | code | 说明 |
| --- | --- | --- | --- |
| 用户不存在或密码错误 | 401 | `AUTH_INVALID_CREDENTIALS` | 两者故意使用同一响应，避免枚举用户名 |
| 密码正确但账号禁用 | 403 | `AUTH_ACCOUNT_DISABLED` | 数据库 `status` 不是 `ACTIVE` |
| 请求字段为空或超长 | 400 | `COMMON_PARAM_INVALID` | 在进入 Service 前由 `@Valid` 拒绝 |

## 7. 当前用户接口与 JWT 规则

```http
GET /api/v1/users/me
Authorization: Bearer <accessToken>
```

成功时返回与登录响应中相同的安全用户摘要。JWT 内仅保存用户 ID、签发者、签发时间、过期时间和 token 类型；不保存明文密码、密码哈希或邮箱。

| 场景 | HTTP | code |
| --- | --- | --- |
| 没有 `Authorization` 头 | 401 | `AUTH_UNAUTHENTICATED` |
| token 格式错误、被篡改、过期，或用户已禁用/软删除 | 401 | `AUTH_TOKEN_INVALID` |
| 已登录但未来某角色接口权限不足 | 403 | `AUTH_ACCESS_DENIED` |

所有 API 默认需要登录；当前明确公开的只有：

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `GET /api/v1/health`
- Actuator 的 health/info 端点

## 8. JWT 密钥：IDEA 中要设置的第三种密码

JWT 签名密钥与 PostgreSQL 的 `agentflow` 数据库密码、以及用户登录时输入的应用密码，三者完全不同：

| 名称 | 使用位置 | 能否写进项目文件 |
| --- | --- | --- |
| `POSTGRES_PASSWORD` | Spring Boot 连接 PostgreSQL | 不能 |
| 用户的应用密码 | `POST /auth/login` 请求体；数据库只存 BCrypt hash | 不能 |
| `JWT_SECRET_BASE64` | 服务端签发和验证 JWT | 不能 |

在 IDEA 的 Terminal 中生成一次本地开发密钥：

```bash
openssl rand -base64 32
```

复制输出内容，在 **Run → Edit Configurations → AgentFlowApplication → Environment variables** 中新增：

```text
JWT_SECRET_BASE64=刚才生成的内容
```

保留你已有的 `POSTGRES_PASSWORD=...`，不要把这两个值填成同一个，也不要把生成出来的值发到聊天或提交到 Git。未设置 `JWT_SECRET_BASE64` 时应用会在启动阶段明确失败，这是故意的安全保护。

默认有效期配置在 `backend/src/main/resources/application.yml`：`JWT_ACCESS_TOKEN_TTL` 未设置时为 `PT2H`（2 小时）。本轮只实现 access token；refresh token、注销和 Redis token 黑名单会在独立后续切片中设计。

## 9. 手动验收：用 IDEA HTTP Client 跑完整链路

1. 打开 `backend/http/user-auth.http`。
2. 只在本地把 `username` 和 `password` 两个占位符改成刚才已注册用户的值；不要保存真实密码到 Git。
3. 先运行“Login”，预期 `200 / OK`。请求文件会在 IDEA 本地会话中自动保存 token。
4. 运行“Current user with saved token”，预期 `200 / OK`，并看到你自己的 `id`、`username`、`displayName` 和 `role`。
5. 运行“No token”，预期 `401 / AUTH_UNAUTHENTICATED`。
6. 运行“Invalid token”，预期 `401 / AUTH_TOKEN_INVALID`。

也可以在 Query Console 中确认登录审计字段已经更新：

```sql
SELECT username, last_login_at, updated_at
FROM app_user
WHERE username = '你的用户名';
```

## 面试问题与回答

### 问题 1：注册时为什么既做用户名/邮箱查重，又保留数据库唯一约束？密码怎样避免落库为明文？

**回答：** `UserRegistrationService` 先查重是为了返回精确的 `USER_USERNAME_ALREADY_EXISTS` 或 `USER_EMAIL_ALREADY_EXISTS`，并在已知冲突时避免无意义的 BCrypt 计算；但两个并发请求仍可能都在预检查后才插入，所以 V1 的唯一约束才是最终正确性防线，竞争产生的 `DuplicateKeyException` 会统一转为 409。请求 DTO 不暴露 `role`、`status`、`passwordHash` 等服务端字段，Service 在插入前只写 `PasswordEncoder.encode(...)` 的结果，响应也不返回哈希；BIGINT id 对外转字符串以避免 JavaScript 精度丢失。`UserRegistrationServiceTest` 是无真实 PostgreSQL 的 Mockito 单元测试，证明编排和持久化对象，不应把它表述为并发数据库联调结果；文档中的 HTTP/Query Console 步骤才是本地手工验收路径。

### 问题 2：登录接口为什么把“用户不存在”和“密码错误”都返回同一个 401，却在密码正确后返回账号禁用？

**回答：** `UserLoginService` 对查不到用户和 BCrypt 比对失败统一返回 `AUTH_INVALID_CREDENTIALS`，避免接口成为枚举用户名的信号源；只有凭据已被正确证明后，才报告 `AUTH_ACCOUNT_DISABLED`。活跃账号登录时，代码先以只含 `lastLoginAt`、`updatedAt` 的 patch entity 更新审计字段，再签发 token，避免把刚查出的整行数据回写覆盖并发更新。`UserLoginServiceTest` 覆盖这三种分支及“失败不签发 token”；这是 mock Mapper、mock JWT 的单元证据，不是对真实数据库、密码泄露防护或生产登录流量的外部验收。

### 问题 3：JWT 已验证签名后，为什么还要在每个已认证请求中查询当前用户状态？

**回答：** JWT 只证明 token 的签名、issuer、时间边界和 `token_type=access`，subject 仅保存用户 ID，不保存密码、邮箱或可过期的角色信息。`JwtAuthenticationFilter` 验签后调用 `CurrentUserService`，要求数据库中的账号仍为 `ACTIVE` 且未软删除，并用刚读取的角色建立 `SecurityContext`；因此禁用或软删除会立即拒绝旧 token，而不必等其过期。取舍是每次受保护请求多一次数据库读取，后续如需缓存必须不削弱这一失效语义。本切片只有 access token；refresh token、注销和 Redis 黑名单明确是后续规划。`JwtServiceTest` 使用固定时钟验证过期、issuer 和密钥长度等规则，`JwtAuthenticationFilterTest` 使用 mock 验证过滤器行为，均不是线上 token 服务验收。

### 问题 4：为什么 401/403 的统一 JSON 不能只依赖 `GlobalExceptionHandler`，本切片如何验收认证链路？

**回答：** Spring Security 过滤器链中的认证失败不会进入 MVC 的 `GlobalExceptionHandler`，所以 `RestAuthenticationEntryPoint` 和 `RestAccessDeniedHandler` 直接用 `ObjectMapper` 输出同一份 `ApiResponse`，分别对应未认证/无效 token 的 401 与已认证但无权限的 403；traceId 仍由更前面的 `TraceIdFilter` 写入响应。当前 `AuthControllerTest`、`JwtAuthenticationFilterTest` 等测试覆盖统一外壳、校验失败和无效 token 的本地行为。`backend/http/user-auth.http` 定义的 Login、带 token 的 `/users/me`、无 token、无效 token 是本地手工验收顺序；它们不构成真实外部身份提供商、refresh-token 或线上部署的验证。
