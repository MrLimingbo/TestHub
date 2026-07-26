# 阶段 5 · 节点 37：一键 CLI CI 执行闭环

## 交付内容

- 新增 `testhub execution run`，将提交场景、等待异步 Runner 终态和可选报告工件归档合并为一条 CI 命令。
- 支持 `--timeout`、`--wait-timeout`、`--poll-interval` 与 `--report-output`；不需要 CI 自行解析、保存或传递执行 ID。
- 成功时仅输出一份 JSON：提交摘要、终态脱敏摘要以及可选报告工件的路径、字节数和 SHA-256。
- 终态业务失败时，若提供 `--report-output`，会先核验并原子写入已脱敏报告，然后以既有退出码 `2` 终止流水线；客户端等待超时或协议错误不会尝试下载可能不完整的报告。
- 复用既有 `execution submit`、`execution wait` 与工件校验实现，保持拆分命令的输出与兼容行为不变。
- 自动化页面、README、GitHub Actions 和 Jenkins 示例已改用一键闭环，保留拆分命令文档供跨阶段流水线使用。

## 验收与验证

- `npx pnpm@10.13.1 --filter @testhub/cli lint`：通过。
- `npx pnpm@10.13.1 --filter @testhub/cli test`：10 项通过，新增覆盖一键成功归档和失败后归档再返回退出码 `2` 的路径。
- 使用临时本地 HTTP 服务直接运行 CLI 入口，确认 `execution run` 在失败终态时返回进程退出码 `2`，并写入已校验的报告工件。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过（前端 4 项测试通过）。构建仍有既有 Monaco source map 缺失提示和大 chunk 提示，不影响产物生成。
- `docker compose ps api-runner`：Runner 正常运行；`curl -fsS http://localhost:8080/actuator/health`：返回 `UP`。
- `git diff --check`：通过。

## 使用边界

`execution run` 只调用已有项目范围内的提交、执行状态和脱敏报告接口。它不读取 Runner 日志、运行时变量或用户脚本，不会将令牌、状态消息、请求/响应内容或密钥写入标准输出、报告元数据或临时文件名。
