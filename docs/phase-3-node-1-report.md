# 阶段 3 · 节点 3.1 验收报告：功能测试基础模型

完成日期：2026-07-12

## 本节点范围

- 建立功能测试目录、需求库和用例库基础数据模型。
- 支持目录树的一级/子级目录、排序、重命名和删除保护。
- 支持需求创建、编辑、删除、优先级和草稿/评审中/已通过/已归档状态。
- 支持用例创建、编辑、删除、优先级、草稿/待执行/阻塞/已归档状态。
- 支持用例关联需求、前置条件、测试步骤和预期结果。
- 功能测试页面从阶段 1 Mock 页面切换为真实 API 数据；本节点暂不实现评审、测试计划、执行记录、缺陷关联、标签、自定义字段和 Excel 导入导出。

## 数据与接口

- Flyway V2：`functional_catalog`、`functional_requirement`、`functional_case`。
- `GET/POST/PUT/DELETE /api/v1/projects/{projectId}/functional/catalogs`
- `GET/POST/PUT/DELETE /api/v1/projects/{projectId}/functional/requirements`
- `GET/POST/PUT/DELETE /api/v1/projects/{projectId}/functional/cases`
- 所有数据携带组织和项目作用域；读操作要求项目成员权限，写操作限制在阶段 2 已定义的项目写角色。
- 需求编号和用例编号在项目内唯一，目录/需求/用例写操作记录审计事件。

## 浏览器验收

在功能测试页面完成真实链路：

1. 创建目录“订单核心流程”。
2. 创建需求 `REQ_ORDER_001`：订单提交后生成交易记录并进入待支付状态。
3. 创建并关联用例 `CASE_ORDER_001`：正常提交订单。
4. 切换需求库/用例库，确认计数、目录归属、优先级、状态和操作按钮刷新。

用户预览：<http://localhost:5173/functional>

## 验证结果

- Java 21 Maven 全量测试通过：16 个测试，0 failures、0 errors、0 skipped；新增 `FunctionalAssetFlowTests` 通过完整目录—需求—用例 CRUD 链路。
- `npx --yes pnpm@10.13.1 lint`：通过。
- `npx --yes pnpm@10.13.1 test`：通过，2 个测试文件、3 个测试通过。
- `npx --yes pnpm@10.13.1 build`：通过，包含 `FunctionalTestingPage` 生产分包。
- `git diff --check`：通过。
- 浏览器页面控制台错误为 `[]`。

下一步等待确认：节点 3.2 功能测试计划、执行记录与可追溯报告。
