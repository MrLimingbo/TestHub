# TestHub 最终整体测试报告 R4

更新于 2026-11-20（北京时间）。本版补录接口调试历史同步的测试夹具修复及修复后的两轮本地全量后端回归。各模块逐项结果、缺陷台账和已审核的 CI 记录见 [整体测试报告 R3](../../2026-11-09/final-overall-report-r3/OVERALL-TEST-REPORT.md)。

## 整体验收结论

16 个主模块、19 个验收范围已完成约定测试并审核。首轮 3090 项检查中，3031 项通过、0 项未关闭失败、47 项观察、12 项环境阻塞。47 项观察中，45 项结果已审核，D26 两项基线继续跳过。109 项已登记产品缺陷均已修复验证。 [各模块和缺陷明细](../../2026-11-09/final-overall-report-r3/OVERALL-TEST-REPORT.md#各模块最终结果)

## 接口调试历史同步夹具修复

`ApiDebugHistorySyncFlowTests` 使用 2026-10-20 的固定创建日期，重放草稿在 11 月 20 日运行时已超过 30 天保留期。修复前，隔离 H2 全量有 4 项报错。相同的 4 项报错在当前工作区和主分支基线 `3eee267` 的定向复跑中均能复现。

修复在测试上下文注入固定 UTC 时钟，把草稿创建时间设为该时钟的前一天，时间戳断言使用同一个夹具值。修复后该测试类 **13/13 通过**，包括此前报错的 4 项。 [修改后的测试夹具](../../../../services/platform-api/src/test/java/io/testhub/platform/ApiDebugHistorySyncFlowTests.java) · [重放保留策略](../../../../services/platform-api/src/main/java/io/testhub/platform/apidebug/ApiDebugReplayPolicy.java)

## 修复后全量后端回归

两轮测试均在 2026-11-20 使用 JDK 21 执行。各轮 Maven 汇总均与 161 份 JUnit XML 报告一致。

| 环境 | 测试总数 | 通过 | 断言失败 | 报错 | 跳过 | 构建 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 隔离 H2 | 1380 | **1377** | 0 | 0 | 3 | Maven 成功，JAR 打包成功 |
| 隔离 MySQL／Redis／S3（MinIO） | 1380 | **1380** | 0 | 0 | **0** | Maven 测试阶段成功 |

H2 跳过的 3 项均在隔离 MySQL／Redis／S3 全量回归中实际执行并通过，已包含在该轮 1380 项通过的结果中。

| 测试方法 | 验证内容 | H2 | MySQL／Redis／S3 |
| --- | --- | --- | --- |
| `AuditResourceMysqlMigrationTests.upgradesOwnedMysql108To109WithoutChangingOldAuditValues` | MySQL 从 V108 升级到 V109，保留旧审计值。 [测试源码](../../../../services/platform-api/src/test/java/io/testhub/platform/AuditResourceMysqlMigrationTests.java) | 跳过 | **通过** |
| `P1CompatibilityTests.mysqlUpgradePreservesLegacyCiphertextAndDefaultsSecurityVersion` | MySQL 迁移保留旧密文，并设置正确的默认安全版本。 [测试源码](../../../../services/platform-api/src/test/java/io/testhub/platform/P1CompatibilityTests.java) | 跳过 | **通过** |
| `P1RedisHttpFlowTests.realRedisRefusalStallRecoveryAndSessionInvalidationOverHttp` | 通过 HTTP 验证真实 Redis 拒绝连接、阻塞、恢复和会话失效。 [测试源码](../../../../services/platform-api/src/test/java/io/testhub/platform/P1RedisHttpFlowTests.java) | 跳过 | **通过** |

H2 使用新建的临时数据库。MySQL、Redis 和按固定源码版本构建的 MinIO 在专用 Docker 环境中运行，使用新建容器、数据卷和回环端口。测试结束后已清理临时工作区、容器、网络、卷和专用 Docker 环境。 [隔离 H2 脚本](../../../../scripts/desktop/test-local-backend.mjs) · [隔离 MySQL／Redis／S3 脚本](../../../../scripts/desktop/test-mysql-backend.mjs)

## 历史待验收事项

本次夹具修复和两轮执行计入本地工程验证记录。D26 两项基线的有效完整窗口仍为 0/2，原 12 项移动真机、跨平台及正式发行等环境补验仍为本轮跳过。升级关闭超时继续保留为独立的未确认风险。 [D26 及环境补验明细](../../2026-11-09/final-overall-report-r3/OVERALL-TEST-REPORT.md#d26跳过两个完整有效窗口未取得) · [升级关闭风险记录](../../2026-11-05/final-overall-report-r1/UNCONFIRMED-RISK.json)
