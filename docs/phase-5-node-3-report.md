# 阶段 5.3：自动化任务创建、可靠投递与状态闭环报告

## 交付范围

- 新增 Flyway `V14__api_automation_execution_queue.sql`：接口自动化执行记录和 Runner Outbox。
- 场景可创建 10 到 3600 秒的预检任务；执行记录固定保存组织、项目、场景版本、任务 ID、时限和无密钥清单引用。
- 任务与 Outbox 在同一 MySQL 事务中创建；调度器定时认领并投递 Redis Streams，成功后才标记为已发送，异常会回到待重试状态。
- 新增 Runner 事件消费者组，将 `ACCEPTED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` 和 `REJECTED` 事件回写执行状态。
- 接口自动化页新增“场景预检任务”区域、当前场景提交入口和 1.5 秒状态轮询。

## 安全与阶段边界

- Redis 任务仍是严格白名单字段；不包含密钥、认证令牌、Cookie、请求体或脚本内容。
- Outbox 重试不写入底层 Redis 异常文本，只保存异常类型，避免连接凭据进入数据库。
- 控制面只负责创建、投递和状态归档。当前 API Runner 收到任务仍返回 `REJECTED / EXECUTION_DISABLED`；不会发起 HTTP 请求、执行脚本或访问数据库。
- 取消、超时回收、任务重试策略、清单读取和实际执行将由后续节点实现。

## 接口

- `GET /api/v1/projects/{projectId}/api-automation/executions`
- `GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}`
- `POST /api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/executions`

## 验证记录

- `./mvnw -q -pl services/platform-api -Dtest=ApiAutomationExecutionFlowTests test`：通过，覆盖场景预检任务创建、Outbox 载荷不含密码/认证字段、Runner 拒绝事件回写。
- `./mvnw -q -pl services/platform-api test`：通过，Flyway 验证 14 个迁移并完成完整控制面回归。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过。
- 本地控制面已重启，`/actuator/health` 为 `UP`，新执行接口已出现在 OpenAPI 文档；独立 Runner 持续上报心跳。

## 用户预览步骤

1. 打开并刷新 `http://localhost:5173/api-automation`，选择或创建一个场景。
2. 在“场景预检任务”区域点击“提交当前场景”。
3. 确认任务先显示“待投递”，随后显示“未启用”，原因代码为 `EXECUTION_DISABLED`。
4. 确认预检过程中没有真实接口访问、数据库访问或脚本执行；场景编辑与保存仍正常。
