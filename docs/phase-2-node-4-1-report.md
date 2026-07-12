# 阶段 2 · 节点 2.4.1 验收报告：项目参数

完成日期：2026-07-12

## 本节点范围

- 项目参数的新增、更新、删除与按环境筛选。
- 参数按组织、项目、环境三级作用域隔离。
- 项目管理员、测试负责人可写；项目成员可读；Bearer 令牌写操作要求 `project:write` 范围。
- 项目设置新增“项目参数”页面与“成员与访问令牌”页面间导航。

## 边界

- 本节点仅处理普通非敏感参数，写入时固定 `secret = false`。
- 环境密钥的加密、脱敏、轮换和访问控制尚未开始，等待下一次明确确认。
- 系统配置和审计日志尚未开始。

## 接口

- `GET /api/v1/projects/{projectId}/parameters?environment={code}`
- `POST /api/v1/projects/{projectId}/parameters`
- `DELETE /api/v1/projects/{projectId}/parameters/{parameterId}`

环境标识限制为小写字母、数字、下划线、连字符；参数名称支持字母、数字、下划线、点、连字符。单个值上限 20,000 字符。

## 验证结果

- `./mvnw -q -pl services/platform-api test`：通过，覆盖管理员参数增查删与测试工程师写入拒绝。
- 测试参数会在测试结束后删除，不污染预览数据。
- 真实 HTTP：管理员完成 `test/E2E_PARAMETER_URL` 的新增、查询和删除，三步响应分别为 `204`、返回预期值、`204`。
- `npx --yes pnpm@10.13.1 lint`、`test`、`build`：通过。
- 浏览器：项目参数页面的真实接口加载、空状态、新建参数表单和页面导航已验证。

## 用户预览

- Web：<http://localhost:5173/project-settings/parameters>
- 使用“新建参数”录入环境、名称和值；创建后可按环境筛选、编辑值或删除。

下一步等待确认：节点 2.4.2 环境密钥，届时会加入加密存储、读取脱敏、访问控制与轮换基础能力。
