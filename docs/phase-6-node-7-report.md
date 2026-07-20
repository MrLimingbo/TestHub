# 阶段 6 · 节点 7：CSV 数据参数化

完成日期：2026-07-19

## 本节点交付

- 线程组新增受限的内联 CSV 数据集，首行定义变量名，最多 20 列、500 行和 64 KiB。
- 目标路径、非敏感请求头、请求正文和响应包含断言可使用 `${变量}`；每个引用都必须存在于同一线程组 CSV 首行，拒绝 JMeter 函数和未声明变量。
- CSV 数据集随计划、不可变执行快照和 Runner v3 运行时工件传递；Redis 队列仍仅携带任务引用，不携带请求数据。
- 隔离 JMeter Runner 把 CSV 写入执行目录的受限文件，生成 `CSVDataSet`，并让 JMeter 以原生变量语法替换路径、头、正文和断言。
- Runner 仍兼容 v1/v2 工件；无数据集的既有压测计划保持原有行为。

## 浏览器验收

已在性能测试页创建并保留草稿计划 **“浏览器验收 CSV 参数化请求”**：

- `POST http://host.docker.internal:18081/orders/${orderId}`
- 请求头 `X-TestHub-Trace: ${traceId}`
- 请求正文使用 `${orderId}` 与 `${quantity}`
- 响应包含断言 `order-${orderId}`
- CSV：`orderId,quantity,traceId`，包含 2 行数据

计划详情已回显 `CSV 2 行 · 3 字段`、请求模板和已有断言。计划保持草稿，不会在审核前自动发送请求。

## 验证

- `./mvnw -q -pl services/platform-api -Dtest=PerformanceTestPlanFlowTests,PerformanceTestExecutionFlowTests test`（5 项通过）
- `npx pnpm@10.13.1 --dir services/jmeter-runner lint`
- `npx pnpm@10.13.1 --dir services/jmeter-runner test`（14 项通过）
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`
- `./mvnw -q -pl services/platform-api test`

## 后续边界

- 当前只支持线程组内联 CSV；文件上传、数据库数据源、变量作用域、随机函数和更复杂的数据驱动策略留待后续节点。
