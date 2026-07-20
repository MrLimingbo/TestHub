# 阶段 6 · 节点 13：隔离 JMeter 运行资源监控

## 本节点交付

- JMeter Runner 在任务运行期间以一秒间隔读取其自身启动的 JMeter 子进程 CPU 占用和常驻内存（RSS）。采样仅调用固定的 `/bin/ps -o %cpu=,rss= -p <validated-pid>`，不拼接用户输入、不使用 shell。
- 新增 `testhub.jmeter-runner.resource` Redis Streams 协议、`testhub:runner:jmeter:resources:v1` 流及控制面消费者；资源消息严格校验任务、执行、Runner、CPU、内存和时间字段。
- Flyway `V41__performance_execution_resource_metrics.sql` 新增项目与组织作用域的资源时序表；报告和独立资源接口均按项目权限读取，最多返回 2,000 个采样点。
- 性能报告抽屉新增“运行资源监控”：峰值/最新 CPU、峰值/最新 RSS、CPU/内存双轴趋势图，并清晰说明只监控隔离 JMeter 子进程。

## 浏览器验收

- 已在本机 `http://localhost:5173/performance` 打开“JMeter 实跑验收 · 本机健康检查”的已完成报告；资源监控面板可访问且历史执行明确显示“任务启动后将每秒采集…”。
- 历史任务创建于本节点之前，没有伪造资源序列；提交一个新的本机健康检查任务后，报告会自动显示峰值卡片和趋势图。

## 验证

- `npx pnpm@10.13.1 --dir services/jmeter-runner lint`：通过。
- `npx pnpm@10.13.1 --dir services/jmeter-runner test`：17 项通过，覆盖固定 `ps` 参数、PID 校验、资源事件构造和生命周期上报。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -q -pl services/platform-api -Dtest=PerformanceTestExecutionFlowTests test`：通过；Flyway 实际升级到 V41，并覆盖资源写入、报告字段和资源接口。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -q -pl services/platform-api test`：35 个测试类、92 项测试通过，无 failure/error。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过；Vitest 4 项通过。Monaco 上游缺失 source map 和既有大 chunk 仅产生构建警告。

## 已知边界

- CPU 与 RSS 是 Runner 容器/主机视角下的单个 JMeter 子进程指标，不等同于整个 Docker 节点、控制面或数据库的资源使用率。
- 历史执行不会回填资源数据；这是为了保持报告与真实采集时间一致。
