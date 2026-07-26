# 阶段 5 · 节点 35：TestHub CLI 与 CI 报告工件校验

## 交付内容

- 新增独立工作区包 `services/testhub-cli`（Node.js 22+、零运行时依赖），提供：
  - `execution submit`：使用项目访问令牌创建接口自动化执行，输出最小化的执行、任务和状态标识。
  - `execution report`：读取控制面已脱敏的固定结构报告。
  - `execution export`：先获取报告完整性元数据，再下载工件，核对 ETag、`X-TestHub-Report-SHA256` 与本地 SHA-256；校验通过后使用临时文件加原子重命名写入目标路径。
- 所有命令支持显式参数和 `TESTHUB_*` 环境变量；令牌只写入 `Authorization` 头，任何成功输出和客户端错误都不回显令牌。
- CLI 拒绝带账号、查询参数或片段的服务地址，校验超时范围、未知参数和服务端响应信封；`304 Not Modified` 不会覆盖既有归档。
- 自动化页面的“CLI / PAT”入口已改为实际 CLI 命令，并同时展示读取报告与下载校验报告的命令。
- 更新根 README 与 CLI/CI/Webhook 文档，包含 GitHub Actions、Jenkins 和低层 HTTP 兼容示例。

## 验收与验证

- `npx pnpm@10.13.1 --filter @testhub/cli lint`：通过。
- `npx pnpm@10.13.1 --filter @testhub/cli test`：5 项通过，覆盖 PAT 不回显、提交、读取报告、工件校验写入、304 缓存路径和无效输入。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过（前端 4 项测试通过）。构建仍有既有 Monaco source map 缺失提示和大 chunk 提示，不影响产物生成。
- `npx pnpm@10.13.1 --filter @testhub/api-runner lint && npx pnpm@10.13.1 --filter @testhub/api-runner test`：通过，37 项测试通过。
- `./mvnw -q -pl services/platform-api test`：通过；Flyway 在 MySQL 8.4 上仍提示当前库版本高于该 Flyway 版本的已验证范围。
- `curl -fsS http://localhost:8080/actuator/health`：返回 `UP`；本地 API Runner 已恢复运行。
- `git diff --check`：通过。

## 使用边界

CLI 仅向 `/api/v1` 控制面提交或读取数据；它不会执行任何用户脚本，也不会绕过 Runner 的可信校验、显式启用和网络边界。下载的报告仍是既有脱敏固定结构，不含 URL、请求/响应正文、变量、密钥或 Runner 日志文本。
