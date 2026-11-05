# D08/D07 隔离 MySQL 补充回归计划

承接已审核的D08/D07首轮报告，只补跑原计划因审批服务403未执行的隔离MySQL影响面。首轮报告及哈希保持冻结，审批另存；本轮不重新定义业务规则、不进入下一模块。

1. 核对已审核报告、实现、测试及回归脚本哈希，确认复用首轮用例与代码。
2. 使用项目JDK21及 `scripts/desktop/test-mysql-backend.mjs`，创建随机独立Compose项目、凭证、卷及loopback端口；每测试类使用独立schema。不接触共享服务或用户工作区。
3. 执行P2D08D07PlanTests的B01至B10，并回归FunctionalExecution*Tests、FunctionalHistorySyncFlowTests、FunctionalTransferFlowTests、FunctionalDefectExecutionSyncFlowTests。重点复验MySQL V113迁移、标题快照与旧缺失状态、编号变更原子拒绝、并发项目行锁、历史交换及原权限边界。
4. 根据实际结果记录各套件/方法通过、失败、错误及跳过；执行次数不新增为正式用例数。不把其他模块或H2结果当作本次MySQL通过证据。
5. 保留脱敏摘要和来源哈希，清理脚本自有容器、网络、卷、测试目录及本轮原始日志/报告。更新补充报告与最新进度，等待用户审核。

12项环境补齐继续暂缓；此临时审批阻塞与它们分列。升级关闭超时仍为UNCONFIRMED_RETAINED、rootCauseConfirmed=false，本轮不调查或复验。
