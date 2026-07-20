# 阶段 6 · 节点 10：性能请求查询参数

完成日期：2026-07-19

## 本节点交付

- 线程组新增结构化查询参数配置，以 JSON 对象保存；目标 URL 本身禁止携带查询串或片段，避免两处配置产生歧义。
- 每个线程组最多配置 16 个参数；参数名仅允许 RFC 3986 非保留字符，值限制为 1,024 个字符且拒绝换行，降低 JMeter 配置注入和日志污染风险。
- 查询参数支持 CSV 数据集变量（如 `${orderId}`）；控制面会校验变量来源，Runner 仅保留合法变量引用，并对其他字符进行 URL 编码。
- 查询参数随不可变执行清单 v3 和 Runner 运行时工件 v5 一同冻结；旧 v1 至 v4 工件继续可读。
- 性能测试页支持新建、编辑和回显查询参数，并在计划详情摘要展示参数数量。

## 验证

- `npx pnpm@10.13.1 --dir services/jmeter-runner lint`
- `npx pnpm@10.13.1 --dir services/jmeter-runner test`（15 项通过）
- `./mvnw -q -pl services/platform-api -Dtest=PerformanceTestPlanFlowTests,PerformanceTestExecutionFlowTests test`
- `./mvnw -q -pl services/platform-api test`（91 项通过）
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`
- `git diff --check`

## 浏览器验收范围

- 新建或编辑草稿压测计划，在“查询参数（JSON 对象）”填写例如 `{"page":"1","source":"web-preview"}` 并保存。
- 确认计划详情显示“查询参数 2 项”，重新编辑后可回显原始 JSON。
- 尝试在目标 URL 直接填写 `?page=1`，应得到提示并要求改用结构化查询参数配置。
