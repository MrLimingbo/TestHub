# 阶段 8 · 节点 1：Web UI 自动化基础闭环

## 交付范围

- 新增项目隔离的数据模型与 Flyway `V43`：浏览器项目、页面对象、声明式场景、不可变执行清单、步骤结果、执行工件与可靠 Outbox。
- 提供 `/api/v1/projects/{projectId}/ui-automation` 的资产、执行、报告、工件下载和 Runner 状态接口。
- 新增独立 `services/playwright-runner`：Redis Streams 任务消费、签名心跳、受控运行时配置、声明式 Playwright 步骤、截图/Trace/视频归档；当前节点固定单并发槽位，后续节点再扩展浏览器并发调度。
- Runner 默认保持禁用；启用时必须同时配置控制面地址与目标 Origin 白名单。Docker Compose 以只读文件系统、`tmpfs`、能力删除、进程/内存上限运行。
- 新增 `/ui-automation` 真实业务页面，包含浏览器项目、页面对象、场景编排、Runner 边界提示、执行记录与报告抽屉。

## 安全边界

- 控制面不执行用户脚本；场景仅支持导航、填写、点击、可见/文本断言、等待和截图。
- 浏览器基准 URL 在保存和运行时均校验 `ui-automation.allowed-origins`；Runner 再次校验相同 Origin 白名单。
- Runner 内部请求由签名心跳、HMAC、时间窗口和一次性 nonce 共同认证；工件上传限制类型、大小、校验和、任务归属和项目作用域。
- 运行时清单在排队时冻结，执行记录、步骤、工件和审计日志均带组织/项目作用域。

## 验证记录

- `npx pnpm@10.13.1 --filter @testhub/playwright-runner lint`
- `npx pnpm@10.13.1 --filter @testhub/playwright-runner test`
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`
- `./mvnw -q -pl services/platform-api test`
- `docker compose config`
- 浏览器验收：登录后打开 `/ui-automation`，确认页面路由、空态、Runner 安全提示、浏览器项目弹窗与场景声明式编排表单可用。

## 部署前配置

1. 在系统设置添加 `ui-automation.allowed-origins`，值为逗号分隔的 HTTP(S) Origin，例如 `https://web.example.test`。
2. 为 `playwright-runner` 注入同一 Origin 白名单、控制面 URL 与独立 `UI_AUTOMATION_RUNNER_HEARTBEAT_KEY`。
3. 将 `RUNNER_PLAYWRIGHT_EXECUTION_ENABLED` 显式改为 `true` 后，才会对已允许的目标启动浏览器执行。
