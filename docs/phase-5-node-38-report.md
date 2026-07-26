# 阶段 5 · 节点 38：GitLab CI 一键执行模板

## 交付内容

- 新增独立的 [GitLab CI 模板](../ci/gitlab/testhub-api-automation.yml)，不会自动改变仓库根流水线；项目可复制或以 `include:local` 引入。
- 模板使用 Node.js 22，检查所有必需的 `TESTHUB_*` 变量后执行 `execution run`，归档到 `artifacts/testhub-execution-report.json`。
- GitLab `artifacts.when: always` 确保 TestHub 业务失败（CLI 退出码 `2`）后仍能下载已完成、已校验且脱敏的报告工件。
- 模板只引用受保护、掩码 CI/CD 变量，不含任何令牌、示例令牌或敏感默认值；执行、等待和轮询时长均可通过非敏感变量覆盖。
- CLI 页面增加 GitLab 模板位置提示；README 与 CI 文档补充复制/引入方法、阶段要求和 GitLab 变量配置说明。
- 新增 CLI 包测试，防止模板遗漏必需变量、`execution run`、工件路径或失败工件保留策略。

## 验收与验证

- `npx pnpm@10.13.1 --filter @testhub/cli lint`：通过。
- `npx pnpm@10.13.1 --filter @testhub/cli test`：11 项通过，包含 GitLab 模板契约测试。
- Ruby YAML 解析：`ci/gitlab/testhub-api-automation.yml` 语法有效。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过（前端 4 项测试通过）。构建仍有既有 Monaco source map 缺失提示和大 chunk 提示，不影响产物生成。
- `docker compose ps api-runner`：Runner 正常运行；`curl -fsS http://localhost:8080/actuator/health`：返回 `UP`。
- `git diff --check`：通过。

## 使用边界

模板假定 GitLab Runner 可以访问企业私有化的 TestHub 地址。`TESTHUB_TOKEN` 必须配置为 protected + masked 变量，且至少具备 `run:execute` 与读取执行状态/报告所需的项目权限；模板本身不会生成、输出或持久化令牌。
