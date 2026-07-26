# 阶段 5 · 节点 36：CLI 执行等待与 CI 门禁

## 交付内容

- 在 `@testhub/cli` 新增 `execution wait` 命令，使用已有的执行详情接口轮询异步 Runner 任务，不引入新的控制面接口、数据表或 Runner 运行时能力。
- 支持 `--wait-timeout`（默认 900 秒，范围 10–7200 秒）和 `--poll-interval`（默认 5 秒，范围 1–60 秒），也沿用 `TESTHUB_URL`、`TESTHUB_PROJECT_ID`、`TESTHUB_EXECUTION_ID`、`TESTHUB_TOKEN` 配置。
- 轮询输出只包含执行 ID、任务 ID、状态、固定状态码、轮询次数和等待时长；不会输出场景名称、状态消息、请求/响应、变量、密钥或日志。
- CI 退出码契约：`0` 为 `SUCCEEDED`，`2` 为服务端终态失败（`FAILED`、`CANCELLED`、`REJECTED`、`TIMED_OUT`），`3` 为 CLI 本地等待超时，`1` 为协议、输入、连接或未知状态错误。
- 对未知执行状态和执行 ID 不匹配采取失败关闭策略，防止 CI 在协议漂移时误判为成功。
- 自动化页面的 CLI 区域、README 和 GitHub Actions/Jenkins 示例均补充等待门禁与退出码说明。

## 验收与验证

- `npx pnpm@10.13.1 --filter @testhub/cli lint`：通过。
- `npx pnpm@10.13.1 --filter @testhub/cli test`：8 项通过，覆盖轮询成功、终态失败、客户端超时、安全摘要、工件校验和无效输入。
- 通过临时本地 HTTP 服务直接运行 CLI 入口，确认终态 `FAILED` 返回进程退出码 `2`，并输出 `COMPLETED` 脱敏摘要。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过（前端 4 项测试通过）。构建仍有既有 Monaco source map 缺失提示和大 chunk 提示，不影响产物生成。
- `docker compose ps api-runner`：Runner 正常运行；`curl -fsS http://localhost:8080/actuator/health`：返回 `UP`。
- `git diff --check`：通过。

## 使用边界

等待命令仅读取控制面中项目范围内的执行状态。它不会调用报告明细、Runner 日志、运行时变量或用户脚本，也不会绕过现有的项目读取权限、Runner 可信校验和网络边界。
