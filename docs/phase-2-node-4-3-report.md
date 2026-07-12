# 阶段 2 · 节点 2.4.3 验收报告：系统配置与审计日志

完成日期：2026-07-12

## 本节点范围

- 系统管理员维护非敏感系统配置：新增、更新、查询、删除。
- 系统管理员查询审计日志，支持项目和动作过滤、最多 200 条。
- 将成员、访问令牌、项目参数、环境密钥和系统配置的写操作接入审计记录。
- 将“系统设置”“权限与审计”从 Mock 页面切换为真实接口页面。

## 权限与安全边界

- `/api/v1/system-config` 和 `/api/v1/audit-logs` 仅 `SYSTEM_ADMIN` 会话可访问；项目范围 PAT 不可访问。
- 系统配置仅保存 `secret = false` 的非敏感值；密钥和凭据不通过此模块存储。
- 审计详情记录资源标识和必要元数据，不记录环境密钥原文、访问令牌原文或系统配置值。
- 审计行携带操作人、组织/项目作用域、请求 ID、客户端 IP、动作、资源和结果。

## 接口

- `GET /api/v1/system-config`
- `POST /api/v1/system-config`
- `DELETE /api/v1/system-config/{key}`
- `GET /api/v1/audit-logs?projectId={id}&action={action}&limit={1..200}`

## 验证结果

- `./mvnw -q -pl services/platform-api test`：通过。覆盖系统管理员配置增查删、审计查询，以及测试工程师被拒绝读取系统配置。
- 真实 HTTP：创建 `platform.preview-mode=enabled` 返回 `204`，读取值正确；审计记录为 `SYSTEM_CONFIG_UPSERTED`，审计详情不含 `value`；删除返回 `204`。
- `npx --yes pnpm@10.13.1 lint`、`test`、`build`：通过。
- 浏览器：系统配置页面、配置编辑表单与审计页面均连接真实接口；审计预览保留系统配置创建/删除记录。

## 用户预览

- 系统配置：<http://localhost:5173/system-settings>
- 权限与审计：<http://localhost:5173/audit>

下一步等待确认：节点 2.5（通知模板、任务中心与 MinIO 文件基础能力）。
