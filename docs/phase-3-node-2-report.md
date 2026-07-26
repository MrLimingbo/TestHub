# 阶段 3 · 节点 3.2 验收报告：测试计划、执行记录与可追溯报告

完成日期：2026-07-12

## 本节点范围

- 建立测试计划、计划用例、执行批次、执行用例和步骤级结果数据模型。
- 支持测试计划创建、编辑、状态和用例范围快照。
- 支持从计划创建执行批次，并复制计划用例及其步骤。
- 支持执行用例标记为通过、失败、阻塞，记录执行备注。
- 支持步骤级状态和实际结果记录。
- 支持按执行批次汇总总数、通过、失败、阻塞、未执行及完成状态。
- 本节点暂不接入缺陷系统、Excel 导入导出、定时计划和外部 Runner。

## 数据与接口

- Flyway V3：`functional_test_plan`、`functional_test_plan_case`、`functional_test_run`、`functional_test_run_case`、`functional_test_run_step`。
- `GET/POST/PUT /api/v1/projects/{projectId}/functional/plans`
- `GET /api/v1/projects/{projectId}/functional/plans/{planId}/cases`
- `POST /api/v1/projects/{projectId}/functional/plans/{planId}/runs`
- `GET /api/v1/projects/{projectId}/functional/runs`
- `GET /api/v1/projects/{projectId}/functional/runs/{runId}/cases`
- `PUT /api/v1/projects/{projectId}/functional/run-cases/{runCaseId}`
- `GET/PUT /api/v1/projects/{projectId}/functional/run-cases/{runCaseId}/steps`
- 所有计划和执行记录携带组织、项目作用域；写操作复用阶段 2 项目写权限和审计记录。

## 浏览器验收

在测试计划页面完成真实链路：

1. 创建计划 `PLAN_ORDER_001`，纳入订单正常提交用例。
2. 创建执行批次，系统复制计划用例并生成 3 个步骤结果。
3. 将用例标记为通过，执行报告刷新为“已完成、1/1 通过、0 未执行”。
4. 查看计划用例、执行批次、报告指标和步骤级结果。

用户预览：<http://127.0.0.1:5173/functional/plans>

## 验证结果

- Java 21 Maven 全量测试通过：17 个测试，0 failures、0 errors、0 skipped；新增 `FunctionalExecutionFlowTests` 覆盖计划—批次—步骤—报告链路。
- `npx --yes pnpm@10.13.1 lint`：通过。
- `npx --yes pnpm@10.13.1 typecheck`：通过。
- `npx --yes pnpm@10.13.1 test`：通过，2 个测试文件、3 个测试通过；路由测试超时上限调整为 10 秒以覆盖新增页面动态加载。
- `npx --yes pnpm@10.13.1 build`：通过，包含 `FunctionalPlansPage` 生产分包。
- `git diff --check`：通过。
- 浏览器页面控制台错误为 `[]`。

下一步等待确认：阶段 3 后续的缺陷关联、标签/自定义字段与 Excel 导入导出，或按计划进入阶段 4 接口设计与调试。
