# 阶段 6 · 节点 8：集合点同步起压

完成日期：2026-07-19

## 本节点交付

- 线程组可选配置集合点等待线程数，范围为 2 到当前线程组并发数；超过并发数的配置会被控制面拒绝。
- 集合点与计划、不可变执行快照、Runner v4 运行时工件一并冻结；旧 v1/v2/v3 工件仍可读取。
- 隔离 JMeter Runner 生成 `SyncTimer`，以指定线程数同步放行，并固定 30 秒超时，避免集合点无法满足时无限等待。
- 性能测试页可配置并回显“集合点 N 线程”，并说明该能力作用于单个隔离 JMeter 进程。

## 浏览器验收

已在性能测试页创建并保留草稿计划 **“浏览器验收 集合点突发压测”**：

- 线程组：`集合点下单突发`
- 并发线程：20
- 集合点等待线程：10
- 加压时长：0 秒；持续时间：10 秒

计划详情页已回显 `集合点 10 线程`。该计划保持草稿，审核前不会自动提交压测。

## 验证

- `./mvnw -q -pl services/platform-api -Dtest=PerformanceTestPlanFlowTests,PerformanceTestExecutionFlowTests test`
- `npx pnpm@10.13.1 --dir services/jmeter-runner lint`
- `npx pnpm@10.13.1 --dir services/jmeter-runner test`（14 项通过）
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`
- `./mvnw -q -pl services/platform-api test`

## 边界

- 集合点只在同一隔离 JMeter JVM 内同步；跨 Runner 的全局同步需要协调服务，暂不承诺。
