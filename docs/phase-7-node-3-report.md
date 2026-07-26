# 阶段 7 · 节点 3：定时报告、可靠通知与 CI 集成

完成日期：2026-07-22

## 交付内容

- 新增质量报告计划与运行记录，使用 Quartz Cron、时区和 1–90 天统计周期；支持创建、编辑、删除、立即运行及查看运行历史。
- 计划运行会持久化质量摘要，并按项目为 `quality.report.ready` / `quality.report.risk` 事件创建通知投递记录。
- 新增 Webhook 出站投递：AES-GCM 加密保存签名密钥，使用 `X-TestHub-*` 头和 HMAC-SHA256 签名。
- Webhook 仅允许 HTTPS 域名，且必须命中系统配置 `notifications.webhook.allowed-hosts`，避免模板配置成为 SSRF 入口。
- 投递记录按“计划运行 + 模板 + 事件”去重；瞬时错误按退避策略最多重试 5 次，失败/完成记录可手动重新入队。
- 通知与集成页增加 Webhook 配置、CI 契约说明和投递记录；报表页增加定时报告管理与运行记录弹窗。

## 验收入口

- 报表统计：`http://127.0.0.1:5173/reports`
- 通知与集成：`http://127.0.0.1:5173/integrations`
- 系统允许域名配置：`http://127.0.0.1:5173/system-settings`

创建实际 Webhook 模板前，请由系统管理员设置非密钥系统配置：

```text
notifications.webhook.allowed-hosts=hooks.example.com,*.internal.example.com
```

## 验证结果

- `./mvnw -q -pl services/platform-api test` 通过（包含新增 `QualityReportScheduleFlowTests`）。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build` 通过。
- 浏览器已验证报表页定时报告弹窗，以及通知与集成页的 Webhook/CI/投递记录渲染。
