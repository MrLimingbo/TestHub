# 阶段 6 · 节点 9：性能报告错误归因

完成日期：2026-07-19

## 本节点交付

- JMeter Runner 从 JTL 的 `responseCode` 聚合失败样本，按“线程组 + 状态码”生成最多 50 条错误摘要。
- 为避免日志、请求或响应中的敏感信息进入控制面，只接收三位 HTTP 状态码；非 HTTP 失败统一归类为 `NON_HTTP_ERROR`。
- Runner 在 JMeter 进程结束时、发送终态事件前，将错误摘要通过独立 Redis Stream 上报；控制面使用执行 ID、Runner、线程组和状态码幂等合并。
- MySQL 新增 `performance_test_execution_error`，报告接口新增 `errors` 字段，并按失败次数和最近汇总时间排序。
- 性能报告抽屉新增“错误归因”区域，展示线程组、失败状态码、失败次数和最近汇总时间；无失败时提供明确空状态。

## 安全与可靠性边界

- 不保存响应正文、响应消息、请求正文或异常详情；不会将可能包含密钥和个人数据的内容写入错误摘要。
- 错误摘要消费组从保留流起点初始化，首次部署不会因消费组创建时机遗漏已经上报的终态摘要。
- 重复投递同一摘要以最大累计失败次数合并，避免 Redis 至少一次投递造成重复计数。

## 验证

- `npx pnpm@10.13.1 --dir services/jmeter-runner lint`
- `npx pnpm@10.13.1 --dir services/jmeter-runner test`（15 项通过）
- `./mvnw -q -pl services/platform-api -Dtest=PerformanceTestExecutionFlowTests,PerformanceTestPlanFlowTests test`
- `./mvnw -q -pl services/platform-api test`
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`
- `git diff --check`

## 浏览器验收范围

- 打开任一压测执行的报告抽屉，确认“错误归因”区域在质量阈值区域之后出现。
- 有失败摘要时应显示线程组、HTTP 状态码、失败次数和最近汇总时间；无失败数据时应显示“暂无失败样本摘要”。
