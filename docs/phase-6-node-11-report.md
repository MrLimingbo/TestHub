# 阶段 6 · 节点 11：性能阶梯加压模型

完成日期：2026-07-19

## 本节点交付

- 线程组新增 `STEPPED_RAMP` 阶梯加压模型，支持 2 至 20 个阶段和每阶段启动间隔。
- Runner 不依赖 JMeter Plugins：将一个业务线程组展开为多个 JMeter 核心线程组，使用 Scheduler 的启动延迟和持续时长实现逐阶增加并发；HTTP 采样器名称保持不变，因此指标、错误归因和质量阈值仍按原业务线程组聚合。
- 每一阶段在同一结束时刻停止；界面中的“持续时长”表示最后一阶启动后的保持时间，“总运行时长”包含前置阶梯间隔。
- 阶梯模型配置随执行清单 v4、Runner 运行时工件 v6 冻结；Runner 保持读取旧 v1 至 v5 工件的兼容性。

## 安全与边界

- 阶段数不得超过线程数或 20；持续时长加上全部阶梯间隔不得超过 24 小时。
- 阶梯加压不允许同时配置集合点，因为核心 JMeter 线程组之间不存在可证明的全局同步语义。
- 配置目标 TPS 时必须不小于阶段数，Runner 按各阶段线程占比拆分 TPS，防止某个阶段失去限速。

## 验证

- `npx pnpm@10.13.1 --dir services/jmeter-runner lint && npx pnpm@10.13.1 --dir services/jmeter-runner test`（16 项通过）
- `./mvnw -q -pl services/platform-api -Dtest=PerformanceTestPlanFlowTests,PerformanceTestExecutionFlowTests test`（9 项通过）
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`
- `./mvnw -q -pl services/platform-api test`（92 项通过）
- `git diff --check`

## 浏览器验收范围

- 新建草稿压测计划，在线程组“负载模型”选择“阶梯加压”。
- 设置并发线程、阶段数、阶梯间隔、持续时长并保存；确认计划详情显示“阶梯加压”“N 阶段 · 每 N 秒启动一阶”以及正确的总运行时长。
- 再次编辑，确认阶段数和阶梯间隔能够回显；该草稿不提交压测任务。
