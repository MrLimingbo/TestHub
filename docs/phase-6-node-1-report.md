# 阶段 6 · 节点 1：性能测试持久化控制面

完成日期：2026-07-19

## 交付范围

- 新增项目级压测计划与线程组的 MySQL 迁移：计划名称、说明、状态、乐观锁版本，以及线程组目标 URL、负载模型、并发、加压时长、持续时长、TPS/P95/错误率阈值。
- 提供 `/api/v1/projects/{projectId}/performance/plans` 的列表、详情、创建、更新和删除接口；写操作要求项目写权限，读操作保持项目作用域隔离。
- 所有变更写入审计日志；审计详情不记录目标 URL，避免将可能敏感的地址内容扩散到审计记录。
- 替换性能测试演示页为真实数据 UI：项目切换、计划列表、线程组展示、新建、编辑、添加/删除线程组以及删除计划。
- 本节点不发送任何压测流量。JMeter Runner、任务调度、停止和实时指标留给下一节点。

## 验证

- `./mvnw -q -pl services/platform-api -Dtest=PerformanceTestPlanFlowTests test`：通过。
- `./mvnw -q -pl services/platform-api test`：32 份 Surefire 报告均无 `failure` 或 `error`。
- `npx pnpm@10.13.1 lint`：通过。
- `npx pnpm@10.13.1 test`：4 项前端测试通过。
- `npx pnpm@10.13.1 build`：通过。
- `git diff --check`：通过。
- 浏览器：已验证真实链路“新建单线程组计划 → 编辑名称并新增第二线程组 → 保存后版本递增、汇总并发与线程组数同步更新”。

## 审核入口

打开 `http://localhost:5173/performance`。当前本机保留一条“浏览器验收 订单容量基线（更新）”计划，包含两个线程组，便于继续手动检查编辑和删除操作。
