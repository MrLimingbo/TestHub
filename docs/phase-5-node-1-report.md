# 阶段 5.1：接口自动化场景资产与编排基础报告

## 交付范围

- 将静态“接口自动化”演示页替换为项目级真实场景库与可保存的步骤编排器。
- 场景支持名称、说明、环境标识、失败策略、场景变量及步骤顺序；当前支持请求、数据提取、断言、条件、循环和等待步骤。
- 每次保存递增场景版本并写入修订快照；保存使用版本号乐观锁，避免多人覆盖彼此修改。
- 新增场景列表、详情、创建、更新和修订记录接口，以及对应审计事件。
- 页面提供场景选择、步骤添加/删除、请求与等待配置、变量编辑、修订查看和保存操作。

## 数据与安全边界

- 迁移 `V13__api_automation_scenarios.sql` 新增场景和修订表，所有记录携带组织与项目作用域。
- 场景变量为可见配置，不得用于保存密钥；密钥继续由项目环境密钥模块管理。
- 控制面仅验证和持久化编排 JSON，不发送 HTTP 请求、不访问数据库、不执行脚本。
- 本节点明确拒绝 `SCRIPT`、数据库等尚未实现 Runner 支持的步骤类型；实际执行按钮保持禁用。
- 场景最多 300 步、100 个变量，步骤配置总大小最多 512 KB。

## 接口

- `GET /api/v1/projects/{projectId}/api-automation/scenarios`
- `POST /api/v1/projects/{projectId}/api-automation/scenarios`
- `GET /api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}`
- `PUT /api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}`
- `GET /api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/revisions`

## 验证记录

- `./mvnw -q -pl services/platform-api -Dtest=ApiAutomationScenarioFlowTests test`：通过，覆盖创建、变量与步骤持久化、修订记录、陈旧版本冲突及禁止脚本步骤。
- `./mvnw -q -pl services/platform-api test`：通过，Flyway 验证 13 个迁移并完成完整后端回归。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过。
- 控制面重启后已验证 V13 结构处于最新状态；前端开发服务和控制面均保持运行。

## 用户预览步骤

1. 打开 `http://localhost:5173/api-automation`，点击“新建场景”。
2. 创建场景后，在画布添加提取、断言、条件、循环或等待步骤；选择步骤并编辑右侧属性。
3. 编辑场景变量、失败策略和环境标识后保存，确认版本从 `v1` 递增。
4. 打开“修订记录”，确认保存版本可追溯；“等待 Runner”按钮应保持禁用。
