# 阶段 5 · 节点 5.5：任务实时日志、详情与失败上下文

## 交付范围

- `V16__api_automation_execution_logs.sql` 新增按组织、项目和执行任务作用域隔离的结构化预检日志表。
- 新增 `GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}/logs`，仅项目可读角色可访问，单次最多返回 200 条按时间排序的日志。
- 控制面记录安全 Outbox 入队、取消请求、投递前取消、超时和投递上限等固定上下文；Runner 日志通过独立 Redis Stream 消费并以 Stream 记录 ID 去重。
- API Runner 发布 `testhub.api-runner.log` 安全预检日志，涵盖接收任务、等待预检、接收取消、取消确认和执行引擎禁用。
- 日志协议只允许固定的代码、级别和英文消息组合。任何字段多余、代码未知、级别/消息不匹配的 Runner 日志均被确认并丢弃，绝不写入数据库。
- 接口自动化页面的任务行新增“详情”。抽屉中展示状态、失败原因、Runner、任务 ID、截止时间及每 1.5 秒刷新的预检日志时间线。

## 安全边界

- 日志流和 MySQL 预检日志均不包含请求 URL、请求头、Cookie、环境密钥、请求体、脚本或任意 Runner 自由文本。
- 当前日志只覆盖小型结构化预检信息；未来真实执行的大体积日志、附件和报告仍按架构写入 MinIO。
- Runner 仍处于预检模式，不发送业务 HTTP 请求、不访问数据库、不执行用户脚本。

## 验证

已通过：

- `./mvnw -q -pl services/platform-api -Dtest=ApiAutomationExecutionFlowTests test`，覆盖合法日志回写、非法含敏感文本日志丢弃、详情日志 API、取消、超时和重试上限。
- `./mvnw -q -pl services/platform-api test`，Flyway 验证并运行 16 个迁移。
- `npx pnpm@10.13.1 --filter @testhub/api-runner lint && npx pnpm@10.13.1 --filter @testhub/api-runner test`，8 项 Runner 协议与日志测试通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`。

前端工具链仍会报告既有 Monaco source-map 缺失与大包体提示；不影响测试或构建结果。

## 手工预览

1. 打开 `http://localhost:5173/api-automation`，选择场景并点击“提交当前场景”。
2. 立刻点击该任务的“详情”。
3. 日志时间线会先显示控制面的 `QUEUED`，随后出现 Runner 的 `TASK_RECEIVED`、`PRECHECK_WAITING` 与 `EXECUTION_DISABLED`；任务最终为“未启用”。
4. 再提交一个任务并在安全窗口内点击“取消”，打开详情可观察 `CANCEL_REQUESTED`、`CANCELLATION_ACCEPTED` 与 `TASK_CANCELLED`（实际顺序取决于投递时序）。
5. 验证过程中不会产生任何真实业务网络请求。
