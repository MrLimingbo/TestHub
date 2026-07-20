# 阶段 4 · 节点 4.3.1.1：HTTP 调试器基础闭环

**状态：待产品验收**

## 本节点交付

- 用真实项目级 HTTP 调试器替换原有静态 Mock 页面，支持 `GET`、`POST`、`PUT`、`PATCH`、`DELETE`、`HEAD`、`OPTIONS` 请求。
- 支持编辑 URL、请求体和逐行请求头；响应面板显示真实响应状态、耗时、响应体、响应头和 512 KB 截断状态。
- 新增项目级执行历史，保存方法、脱敏 URL、状态、耗时、响应大小和错误摘要；请求体、响应体及请求头均不落库。
- 新增 Flyway 迁移 `V7__api_debug_history.sql`，所有历史记录携带组织和项目作用域。
- 新增接口：
  - `POST /api/v1/projects/{projectId}/api-debug/execute`
  - `GET /api/v1/projects/{projectId}/api-debug/history?limit={limit}`

## 安全边界

- 仅允许 HTTP/HTTPS 绝对 URL，禁止 URL user-info、私网、回环、链路本地、组播和保留地址；主机 DNS 解析到任一受限地址时直接拒绝。
- 不跟随重定向，连接超时和请求超时均受限在 10 秒以内，响应体最多读取 512 KB。
- 禁止覆盖 `Host`、`Content-Length`、`Connection`、`Expect`、`Upgrade` 等传输层请求头。
- 查询参数名包含 token、secret、password、api-key、authorization 或 signature 时，会在历史记录中替换为 `***`。
- 本节点不执行任何用户脚本；脚本、认证、Cookie、环境变量、动态值与高级协议将留在后续小节点实现。

## 验证记录

- 后端：`./mvnw -q -pl services/platform-api test` 通过，共 24 个测试。新增 `ApiDebugFlowTests` 覆盖真实执行服务契约、脱敏历史写入和 `127.0.0.1` 回环地址拒绝。
- 前端：`npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build` 通过（3 个前端测试）。构建仅保留既有 Monaco source map 缺失提示和大体积分包提示。
- 浏览器：在“接口调试”页面向 `https://postman-echo.com/post` 发送真实 POST 请求，收到 `200 OK`，显示 829 ms、响应体和 13 个响应头，并产生一条项目执行历史。
- `git diff --check` 通过。

## 下一节点

在本节点验收通过后，进入 **4.3.1.2：调试环境、认证与动态值**。
