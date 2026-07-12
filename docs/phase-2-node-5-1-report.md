# 阶段 2 · 节点 2.5.1 验收报告：通知模板

完成日期：2026-07-12

## 本节点范围

- 按项目维护通知模板，支持邮件、Webhook、企业微信和钉钉渠道。
- 支持模板新增、查询、编辑、启停和删除。
- 事件编码统一为小写字母开头，支持字母、数字、点、下划线和连字符。
- 写操作接入审计日志，模板正文不执行、不发送，仅作为后续任务中心和集成适配器的配置资产。

## 权限与安全边界

- 项目成员可读取模板；系统管理员、组织管理员、项目管理员和测试负责人可写入模板。
- 所有数据按组织与项目隔离，控制面只保存模板文本，不执行用户脚本或向外部渠道发送消息。
- 删除、更新和启停均记录 `NOTIFICATION_TEMPLATE_*` 审计动作。

## 接口

- `GET /api/v1/projects/{projectId}/notification-templates`
- `POST /api/v1/projects/{projectId}/notification-templates`
- `PUT /api/v1/projects/{projectId}/notification-templates/{templateId}`
- `DELETE /api/v1/projects/{projectId}/notification-templates/{templateId}`

## 验证结果

- `./mvnw -q -pl services/platform-api test`：通过，覆盖项目管理员新增、查询、更新停用和删除模板。
- 真实 HTTP：完成 Webhook 模板新增、列表查询、切换为 EMAIL 并停用、删除，最终列表为空。
- `npx --yes pnpm@10.13.1 lint`、`test`、`build`：通过；构建仅保留既有 Monaco source map 与大分包提示。
- 浏览器：通知模板页面可访问，项目摘要、空状态和“新建通知模板”表单可预览。

## 用户预览

- 通知与集成：<http://localhost:5173/integrations>

下一步等待确认：节点 2.5.2 任务中心基础能力。
