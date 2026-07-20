# 阶段 5 / 节点 17：外部触发与脱敏报告

## 完成内容

- 将接口自动化执行权限从“项目写入”拆分为专用的 `run:execute` 范围；PAT 仍受项目作用域和账号项目角色约束。
- 增加项目级签名 Webhook：密钥仅创建时返回一次，数据库保存 AES-GCM 密文；入站请求采用 HMAC-SHA256、5 分钟时效、严格请求体和 Redis 一次性 nonce 防重放。
- 增加固定结构的执行报告端点：`GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}/report`。报告只包含执行元数据、计数、受限 HTTP 结果和安全步骤结果。
- 自动化页面增加 CLI/PAT、CI、Webhook 集成面板，支持创建、启用/停用和撤销 Webhook；执行抽屉展示报告摘要。
- 补充 CLI、GitHub Actions、Jenkins 和签名 Webhook 文档：[`automation-cli-ci-examples.md`](automation-cli-ci-examples.md)。

## 安全边界

- 控制面不执行用户脚本。外部触发仅创建现有场景的 Outbox 任务，Runner 的可信校验、显式开关和网络边界不变。
- Webhook 不接受任意参数、脚本或场景定义，只允许可选的 `timeoutSeconds`。
- 报告和 Webhook 列表不输出密钥、变量取值、请求地址、请求/响应内容、Cookie、头或日志文本。
- Webhook 审计记录配置创建、启停、撤销和触发事件，但不会记录密钥或签名。

## 验证

- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`
- `./mvnw -q -pl services/platform-api -Dtest=ApiAutomationExternalTriggerFlowTests test`
- 全量 Spring Boot 测试和浏览器预览在本节点交付前执行。
