# AgentFlow Hub：PostgreSQL 与首个 Flyway 迁移

这一阶段的目标是让应用自动创建并管理第一张业务表 `app_user`。以后表结构变更都通过新的 Flyway migration 提交到 Git，不能只在 pgAdmin 中手工修改。

## 已落地的代码

- 数据源配置：`backend/src/main/resources/application-dev.yml`
- 首个迁移：`backend/src/main/resources/db/migration/V1__create_app_user.sql`
- 首个业务表：`app_user`

`app_user` 只覆盖下一阶段的用户注册、登录和基础角色：用户名、可选邮箱、BCrypt 密码哈希、显示名、角色、状态和审计时间。知识库、Agent、Trace 等表暂不创建。

## 1. 启动并确认 PostgreSQL

本机安装的 PostgreSQL 18 命令在：

```text
/Library/PostgreSQL/18/bin
```

确认服务可用：

```bash
/Library/PostgreSQL/18/bin/pg_isready -h 127.0.0.1 -p 5432
```

期望看到：

```text
127.0.0.1:5432 - accepting connections
```

若未启动，可先打开 PostgreSQL 18 自带的 pgAdmin 或 SQL Shell；不要在应用尚未连上数据库前启动后端迁移。

## 2. 创建项目专用角色和数据库

先以安装时设置的 PostgreSQL 管理员账号登录（通常是 `postgres`）：

```bash
/Library/PostgreSQL/18/bin/psql -h 127.0.0.1 -U postgres -d postgres
```

在 `psql` 中执行一次：

```sql
CREATE ROLE agentflow LOGIN PASSWORD '请替换为你自己的本地开发密码';
CREATE DATABASE agentflow_hub OWNER agentflow;
```

退出：

```text
\q
```

密码不要提交到 Git。运行后端前，在当前终端设置它：

```bash
export POSTGRES_PASSWORD='你的本地开发密码'
```

默认连接参数见 `application-dev.yml`：主机 `localhost`、端口 `5432`、数据库 `agentflow_hub`、用户 `agentflow`。

## 3. 让 Flyway 自动执行 V1

在项目根目录运行：

```bash
cd backend
mvn test
mvn spring-boot:run
```

首次启动时 Flyway 会创建 `flyway_schema_history`，然后执行 `V1__create_app_user.sql`。

用项目账号验证：

```bash
/Library/PostgreSQL/18/bin/psql -h 127.0.0.1 -U agentflow -d agentflow_hub
```

```sql
\dt
SELECT installed_rank, version, description, success
FROM flyway_schema_history;
\d app_user
```

你应当看到 `app_user`、`flyway_schema_history`，并且 V1 的 `success` 为 `true`。

## 4. 接下来的编码边界

数据库验证通过后，才创建 `com.agentflow.user`：

```text
user
  model       AppUser 实体
  repository  AppUserMapper
  service     注册和登录流程
  dto         请求与响应对象
  controller  /api/v1/auth/*
  security    BCrypt、JWT、当前用户
```

此时不要提前创建知识库、Agent、Trace 等表。它们应在对应功能开始前，各自通过新的 migration 添加。
