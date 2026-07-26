# 接口自动化 Quartz 定时计划

接口自动化计划属于项目和场景。计划配置存储在 MySQL，服务启动时由 Quartz 自动重建运行时触发器，因此不依赖 Quartz 内部 JobStore 表。

## 创建计划

接口自动化页面的“新建计划”可配置：

- 目标场景和名称。
- Quartz Cron（六或七段），例如 `0 0 9 * * ?` 表示每天 `09:00:00`。
- IANA 时区，例如 `Asia/Shanghai`。
- 每次执行超时（10–3600 秒）。
- 连续失败自动停用阈值（1–10 次）。

计划可随时编辑、停用、删除或“立即执行”。立即执行和 Cron 触发都只会创建既有的安全 Outbox 任务；Runner 的可信校验、显式启用和网络白名单限制保持不变。

## 失败保护

计划的最近一次执行达到 `FAILED`、`REJECTED` 或 `TIMED_OUT` 时，连续失败数加一；达到阈值后会停用计划并从 Quartz 移除。成功执行会重置连续失败计数。手动编辑计划也会重置该计数，便于修正配置后重新启用。

`CANCELLED` 不计为连续失败。自动停用、手动触发、创建、更新和删除都会写入审计日志，且不包含变量或密钥取值。

## HTTP API

所有接口均在项目作用域下：

```text
GET    /api/v1/projects/{projectId}/api-automation/schedules
POST   /api/v1/projects/{projectId}/api-automation/schedules
PUT    /api/v1/projects/{projectId}/api-automation/schedules/{scheduleId}
DELETE /api/v1/projects/{projectId}/api-automation/schedules/{scheduleId}
POST   /api/v1/projects/{projectId}/api-automation/schedules/{scheduleId}/run
```

创建或更新请求示例：

```json
{
  "name": "每日订单回归",
  "scenarioId": "01...",
  "cronExpression": "0 0 9 * * ?",
  "timezone": "Asia/Shanghai",
  "timeoutSeconds": 300,
  "maxConsecutiveFailures": 3,
  "enabled": true
}
```
