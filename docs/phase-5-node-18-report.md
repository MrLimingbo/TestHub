# 阶段 5 / 节点 18：接口自动化定时计划

## 完成内容

- 引入 Quartz，并新增项目级、场景级的持久化定时计划。
- 支持 Quartz Cron、IANA 时区、单次超时、启停、编辑、删除和立即执行。
- 服务启动时从 MySQL 配置重建已启用的 Quartz Job；JobDataMap 只保存计划 ID，不携带场景内容、变量或密钥。
- 新增连续失败保护：`FAILED`、`REJECTED`、`TIMED_OUT` 达到阈值后自动停用并移除 Quartz Job；成功会重置计数。
- 接口自动化页面新增定时计划清单和管理弹窗；保留 Runner 默认安全边界。
- 详细使用方式见 [`api-automation-schedules.md`](api-automation-schedules.md)。

## 验证

- `ApiAutomationScheduleFlowTests`：范围隔离、Cron 校验、Quartz 触发链路、失败自动停用和重新启用。
- 前端 lint、测试和生产构建。
- 全量 Spring Boot 测试、Runner 测试、健康检查和浏览器预览在交付前执行。
