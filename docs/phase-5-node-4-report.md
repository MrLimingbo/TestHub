# 阶段 5 · 节点 5.4：取消、超时与有界重试

## 交付范围

- `V15__api_automation_execution_controls.sql` 为 Runner Outbox 增加消息类别与每条消息最多 5 次的投递预算。
- 控制面新增 `DELETE /api/v1/projects/{projectId}/api-automation/executions/{executionId}`：
  - 仍在 Outbox 待投递的任务直接取消，任务消息不会进入 Runner；
  - 已投递或投递中的任务进入 `CANCELLING`，并以同一数据库事务写入固定字段的取消指令；
  - `CANCELLED`、`TIMED_OUT` 和投递预算耗尽均为明确终态，晚到事件不能覆盖终态。
- 每秒扫描已超过 `deadlineAt` 的活动任务：标记 `TIMED_OUT / EXECUTION_TIMEOUT`，撤销尚未投递的任务，必要时向 Runner 发送超时取消指令。
- 任务与取消指令均由 Outbox 投递。失败等待 5 秒重试，达到 5 次后收敛为：
  - 任务投递：`FAILED / DELIVERY_RETRY_EXHAUSTED`；
  - 取消指令：`FAILED / CANCELLATION_DELIVERY_FAILED`。
- API Runner 新增独立取消指令流与消费者组。取消命令只允许固定原因码 `USER_REQUESTED` 或 `EXECUTION_TIMEOUT`；Runner 保留取消标记 90 分钟，并在安全预检窗口内只确认任务，不会执行任何用户内容。
- 接口自动化页面新增“取消”操作，以及“取消中”“已超时”等状态展示。

## 安全边界

- 取消消息只包含 ULID、时间和固定原因码，不包含环境密钥、认证头、Cookie、请求体或脚本。
- 控制面仍不执行用户代码；Runner 仍是预检模式，取消与拒绝均不会发出 HTTP 请求、访问数据库或执行脚本。
- Outbox 错误记录仅保留异常类型，不写入底层连接异常文本。

## 验证

已通过：

- `./mvnw -q -pl services/platform-api -Dtest=ApiAutomationExecutionFlowTests test`；覆盖投递、取消确认、超时收敛与投递上限。
- `./mvnw -q -pl services/platform-api test`；Flyway 验证并运行 15 个迁移。
- `npx pnpm@10.13.1 --filter @testhub/api-runner lint && npx pnpm@10.13.1 --filter @testhub/api-runner test`；8 项 Runner 协议/取消测试通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`。

前端测试与构建会显示既有 Monaco source-map 缺失和大包体提示；不影响测试或构建结果。

## 手工预览

1. 打开 `http://localhost:5173/api-automation` 并选择一个场景。
2. 点击“提交当前场景”，立刻点击新任务行的“取消”。
3. 观察状态从“待投递”或“取消中”最终变为“已取消”，原因码为 `USER_REQUESTED`。
4. 再提交但不取消，约 3 秒后会保持原有预检结果“未启用 / EXECUTION_DISABLED”。
5. 上述操作均不发送任何真实业务请求。
