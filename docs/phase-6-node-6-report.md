# 阶段 6 · 节点 6：HTTP 请求配置与响应断言

完成日期：2026-07-19

## 本节点交付

- 线程组新增 HTTP 方法、请求头、原始请求正文、预期状态码和响应包含文本配置，并通过 Flyway `V35` 持久化。
- 控制面会校验 HTTP 方法、请求头数量与格式、正文长度、状态码范围和断言长度；`Authorization`、`Cookie`、`Host`、`Content-Length`、代理及连接类请求头被拒绝，避免在压测计划中明文保存认证信息。
- 压测计划快照与 Runner 运行时工件升级为 v2；已冻结的执行工件带有完整请求与断言配置，仍可读取 v1 旧工件。
- JMeter Runner 会生成 HTTP 请求方法、请求头管理器、原始请求正文、状态码断言和响应正文包含断言。
- 性能测试页可配置并回显请求信息；GET/HEAD 不允许填写请求正文，敏感认证字段在页面说明与服务端校验中均受限制。

## 浏览器验收

已在性能测试页创建并保留计划 **“浏览器验收 POST 请求与断言”**：

- `POST http://host.docker.internal:18081/orders`
- 两个请求头：`Content-Type`、`X-TestHub-Trace`
- JSON 请求正文
- 预期状态码 `201`
- 响应包含 `orderId`
- 最小验收负载：1 线程、0 秒加压、10 秒持续

计划详情页已回显 `POST`、请求头数量、正文长度、状态码断言和响应包含断言。该演示计划暂保持“草稿”，不会自动向本机发起压测；你可在页面中切换为“待执行”后手动提交。

## 验证

- `./mvnw -q -pl services/platform-api -Dtest=PerformanceTestPlanFlowTests,PerformanceTestExecutionFlowTests test`
- `npx pnpm@10.13.1 --dir services/jmeter-runner lint`
- `npx pnpm@10.13.1 --dir services/jmeter-runner test`（13 项通过）
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`
- `./mvnw -q -pl services/platform-api test`

## 后续边界

- 项目密钥、认证注入、请求查询参数、CSV 数据驱动与更复杂协议仍由后续节点扩展；本节点不在计划中保存认证信息。
