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
