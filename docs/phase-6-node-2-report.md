# 阶段 6 · 节点 2：JMeter Runner 任务传输与控制面

完成日期：2026-07-19

## 交付范围

- 新增压测执行、不可变清单、执行日志与 Runner Outbox 的 MySQL 迁移；执行记录按组织、项目和计划版本隔离，并使用 ULID 标识。
- 提供项目级压测任务列表、详情、日志、提交和取消 API，以及已签名 JMeter Runner 心跳状态 API。
- 使用 Redis Streams 投递任务和取消指令，控制面通过 Outbox 重试投递，并消费 Runner 的状态事件；超时与投递耗尽会被明确标记为终态。
- 增加独立 `services/jmeter-runner` 服务和 Docker Compose 定义。任务消息只包含任务、范围和清单引用，不包含目标 URL、认证信息或脚本内容。
- 性能测试页面加入 Runner 状态、任务列表、提交与取消入口，并展示任务状态码。
- Runner 当前固定为“执行禁用”：收到任务会回传 `ACCEPTED` 后以 `JMETER_EXECUTION_DISABLED` 受控拒绝。该节点不启动 JMeter、不请求目标地址、不产生压测流量。
- 修正 MySQL 8.4 严格模式下 Outbox 扫尾查询的 `DISTINCT` 排序兼容性，并让已有压测计划测试不会依赖项目数据为空。

## 验证

- `npx pnpm@10.13.1 --filter @testhub/jmeter-runner lint`：通过。
- `npx pnpm@10.13.1 --filter @testhub/jmeter-runner test`：5 项通过，覆盖协议边界、心跳签名、Redis 回复格式、禁用执行和取消事件。
- `./mvnw -q -pl services/platform-api -Dtest=PerformanceTestExecutionFlowTests test`：通过。
- `./mvnw -q -pl services/platform-api test`：33 份 Surefire 报告均无 `failure` 或 `error`。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过；前端单测 4 项通过。测试期间仅有 Monaco 上游缺失 source map 警告，未影响结果。
- 浏览器：已完成“草稿计划切换为待执行 → 提交压测 → Redis Streams 投递 → Runner 心跳 → 状态回传 → 页面显示已拒绝（`JMETER_EXECUTION_DISABLED`）”链路；没有向 `perf.example.test` 发送请求。

## 审核入口

打开 `http://localhost:5173/performance`。当前保留“浏览器验收 订单容量基线（更新）”及一条受控拒绝的任务记录，便于检查任务状态、Runner 信息和安全边界。

## 下一节点

安装和封装真正的 JMeter 执行器，再接入指标流、实时曲线、停止控制和执行报告；执行器上线前将先配置容器网络、资源与目标访问白名单边界。
