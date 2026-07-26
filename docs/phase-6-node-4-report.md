# 阶段 6 · 节点 4：阈值判定、历史对比与原始工件归档

完成日期：2026-07-19

## 交付范围

- 执行报告新增基于已冻结计划阈值的质量判定。每个线程组展示 TPS、P95、错误率、已配置检查数与通过/失败/未判定结果；只有已成功结束的执行才产生最终质量结论。
- 报告自动选择同项目、同计划的上一条成功执行作为基线，展示样本数、峰值 TPS、P95、错误率与峰值并发的增减值；无历史成功执行时明确显示无可用基线。
- 新增 `performance_test_execution_artifact` 迁移与 MinIO 工件服务，将 `results.jtl` 和 `jmeter.log` 归档为受项目权限保护的执行附件，并提供报告内下载入口。
- Runner 在发送终态事件前读取受 20 MiB 上限约束的本地工件、计算 SHA-256，并向内部端点发起 HMAC 签名上传。签名绑定 Runner、任务、执行、工件类型、长度、摘要、时间戳和 nonce；控制面在写入元数据前校验长度、摘要、租户、运行状态和 Runner 所属关系。
- 原始工件不经过 Redis Streams，控制面只接收受限的 HTTP 流并写入 MinIO；用户下载时重新执行项目读取权限检查。

## 浏览器验收

- 保留性能报告抽屉，执行 `01KXWPCN09DGXGT71P0X49TT90` 真实访问本机健康检查端点，结果为 4,268 样本、峰值 TPS 410.56、P95 4ms、错误率 0.00%。
- 质量判定为通过，共 2 项检查通过且无失败；报告将其与 15:59 的上一条成功执行比较，展示样本数 -355、峰值 TPS -9.37、P95 0ms、错误率 0.00%。
- 报告的“原始执行工件”区域已列出并可下载 `results.jtl`（354.4 KB）与 `jmeter.log`（7.0 KB）；Runner 运行日志确认两份工件均已归档。

## 验证

- `./mvnw -q -pl services/platform-api -Dtest=PerformanceTestExecutionFlowTests,PerformanceTestExecutionArtifactFlowTests test`：通过，覆盖冻结阈值、历史比较、签名工件上传、项目隔离与下载。
- `./mvnw -q -pl services/platform-api test`：通过；34 份 Surefire 测试报告的失败/错误扫描为空，Flyway 已验证第 33 版迁移。
- `npx pnpm@10.13.1 --dir services/jmeter-runner lint && npx pnpm@10.13.1 --dir services/jmeter-runner test`：通过，11 项测试覆盖工件请求签名及终态前归档。
- `npx pnpm@10.13.1 build`：通过。`git diff --check`：通过。

## 已知边界

- 当前归档面向单 Runner 的 JTL 与 JMeter 日志；跨 Runner 汇总和长期留存策略在分布式执行与生产加固节点中继续扩展。
- 前端生产构建仍提示 Monaco 上游包缺失 source map，及较大的 Monaco/ECharts 产物分包警告；均不影响本节点功能或构建结果。
