# 阶段 6 · 节点 12：性能报告 CSV 导出

完成日期：2026-07-19

## 本节点交付

- 新增项目范围内的 `GET /api/v1/projects/{projectId}/performance/executions/{executionId}/report/export` 下载接口。
- 导出 UTF-8 BOM CSV，包含摘要、实时线程组指标、质量阈值、错误归因和历史对比；便于 Excel 直接打开。
- 性能报告抽屉新增“导出 CSV”入口，复用当前已登录的 Cookie 会话和项目权限。
- 每次导出记录 `PERFORMANCE_TEST_EXECUTION_REPORT_EXPORTED` 审计事件，保留执行、任务、格式和文件大小元数据。

## 安全边界

- 导出只使用聚合统计、阈值和失败状态码；不导出请求头、查询参数、请求体、响应体、Runner 日志正文或原始 JTL 内容。
- 每个文本单元格进行标准 CSV 转义，并对以 `=`, `+`, `-`, `@` 开头的值加前导单引号，防止办公软件公式注入。
- 文件名只使用执行 ULID，不接受用户输入，避免响应头注入和跨平台文件名问题。

## 验证

- `./mvnw -q -pl services/platform-api -Dtest=PerformanceTestExecutionFlowTests test`（3 项通过）
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`
- `./mvnw -q -pl services/platform-api test`（92 项通过）
- `git diff --check`

## 浏览器验收范围

- 打开任一已有压测任务的“报告”抽屉，确认标题右侧出现“导出 CSV”。
- 点击导出后应下载 `testhub-performance-report-<执行 ID>.csv`，不应改变任务状态或提交新的压测任务。
