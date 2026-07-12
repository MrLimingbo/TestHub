# 阶段 2 · 节点 2.2 验收报告：本地登录与 Redis 会话

完成日期：2026-07-11

## 本节点范围

- 本地账号初始化、JSON 登录、退出与当前用户查询。
- Redis 服务端会话与 `HttpOnly` Cookie（`TESTHUB_SESSION`）。
- CSRF Token 获取与校验。
- 统一认证失败、校验失败和未认证响应，均遵守 `code`、`message`、`requestId`、`details` 错误结构。
- Vue 登录页切换至真实 `/api/v1/auth` 接口，前端路由按会话状态保护。

初始化账号仅用于本地开发预览：`admin@testhub.local / TestHub2026!`。

## 实现要点

- `BootstrapAccountConfiguration` 仅在 `user_account` 为空时创建管理员，密码以 BCrypt 哈希保存。
- `@EnableRedisHttpSession` 将会话存入 Redis，固定 8 小时过期；会话中的用户主体实现 `Serializable`。
- 仅 `GET /api/v1/auth/csrf` 与 `POST /api/v1/auth/login` 允许匿名访问；`/me`、`/logout` 受会话保护。
- 安全错误在 Spring Security 链内直接返回 JSON；请求 ID 过滤器排在安全链之前。
- 本机已有其他 MySQL 占用 3307，TestHub Compose 的 MySQL 主机端口调整为 `13307`，避免干扰已有服务。

## 验证结果

- `./mvnw -q -pl services/platform-api test`：通过（覆盖 CSRF、有效登录、读取用户、退出、退出后 401、错误密码）。
- `npx --yes pnpm@10.13.1 lint`：通过。
- `npx --yes pnpm@10.13.1 test`：3 个前端测试通过。
- `npx --yes pnpm@10.13.1 build`：通过。
- 真实 HTTP：登录响应下发 `TESTHUB_SESSION; HttpOnly; SameSite=Lax`；登录后 Redis 中出现 `testhub:session:*` 会话键；退出后同一 Cookie 访问 `/me` 返回带 `requestId` 的 `401 AUTH_UNAUTHENTICATED`。
- 浏览器预览：完成“登录页 → 工作台 → 账号菜单退出 → 登录页”真实接口链路。

## 用户预览

- Web：<http://localhost:5173/login>
- 初始化账号：`admin@testhub.local`
- 初始化密码：`TestHub2026!`

开发依赖服务与 API 保持运行，便于验收。下一节点（2.3）将在得到确认后才开始，内容为系统/组织/项目成员与 RBAC、个人访问令牌。
