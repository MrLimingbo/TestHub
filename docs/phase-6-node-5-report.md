# 阶段 6 · 节点 5：多 Runner 调度、失联保护与任务恢复

完成日期：2026-07-19

## 交付范围

- JMeter Runner 心跳新增已签名的 `maxConcurrentTasks` 与 `activeTaskCount`。控制面只信任完整签名的容量声明，并兼容节点升级前的单槽位心跳。
- Runner 使用本地并发槽位限制执行任务；槽位占满时先回传 `DEFERRED / RUNNER_CAPACITY_EXHAUSTED`，不领取运行时工件、不启动 JMeter，随后由控制面按退避时间重新投递给其他 Runner。
- 每条压测执行记录新增 `routingAttempts`。容量转投最多 8 次；超过上限后以 `RUNNER_CAPACITY_EXHAUSTED` 明确失败，而不是无限循环。
- Runner 启动和运行结束时立即刷新容量心跳；Redis Streams 使用 `XAUTOCLAIM` 认领超过最小空闲时间的未确认任务，避免 Runner 进程在确认前退出而遗留消息。
- 控制面定期检查已分配 Runner 的已验证心跳。若任务处于接收、运行或取消中且 Runner 已失联，则标记为 `RUNNER_OFFLINE` 并停止重放，避免双重压测流量。
- 性能测试页面新增 Runner 调度池、总槽位/空闲槽位、每台 Runner 已用槽位和心跳展示；任务列表展示已发生的调度尝试次数。

## 浏览器验收

- 启动两台真实 Runner：`jmeter-runner-capacity-a` 与 `jmeter-runner-capacity-b`，每台声明 1 个槽位；页面显示“2 / 2 空闲槽位”。
- 从页面连续提交两条“JMeter 实跑验收 · 本机健康检查”任务后，页面显示“0 / 2 空闲槽位”，两台 Runner 均为“1 / 1 已用”。
- 两条任务分别由 A 与 B 执行并成功结束：
  - `01KXWR1MN4W4819HPZ3RXNRR6S` → `jmeter-runner-capacity-a`，4,569 样本，错误率 0.00%。
  - `01KXWR19VP7CHT26JNEP2XJHBS` → `jmeter-runner-capacity-b`，4,190 样本，错误率 0.00%。
- 两台 Runner 均已将原始 JTL 与 JMeter 日志归档到 MinIO；验收流量仅访问本机 `/actuator/health`。

## 验证

- `./mvnw -q -pl services/platform-api test`：通过；34 版 Flyway 迁移已验证，后端 Surefire 结果无失败或错误。
- `npx pnpm@10.13.1 --dir services/jmeter-runner lint && npx pnpm@10.13.1 --dir services/jmeter-runner test`：通过，13 项测试覆盖容量延迟、遗留任务认领、签名心跳、归档与执行生命周期。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过；前端测试 4 项通过。
- `git diff --check`：通过。

## 已知边界

- 多 Runner 当前以同一执行队列的独立任务分配为单位，不会把一条已经开始的 JMeter 执行拆分后跨 Runner 重放；这保证 Runner 失联时不会因重复流量导致结果失真。
- 分布式分片压测、跨节点聚合和长期 Runner 容量策略属于后续企业级扩展；本节点已提供它们需要的容量心跳、槽位状态和安全失联语义。
