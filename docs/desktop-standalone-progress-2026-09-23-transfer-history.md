# 功能测试执行历史迁入账号

日期：2026-09-23。状态：V6 可选执行历史迁移已通过本阶段源码与包内复测。整体独立安装与账号离线同步尚未完成。

## 功能与兼容边界

- 桌面新增默认关闭的“执行历史”选项，需同时勾选测试计划；不强制选择标签、缺陷或文件。旧服务未声明 V6 能力时明确拒绝，不降级丢弃历史。
- 复制批次、执行用例、历史步骤、结果、备注和原时间。历史用例可以已不属于当前计划，历史步骤可以与当前用例定义不同，迁移不会重新生成执行内容。
- 所有记录使用新 ID、目标组织和项目；批次创建归迁入账号，原执行人和步骤更新人清空。导入后仍可继续执行。此操作是一次性复制，不是持续同步。
- 同选缺陷时保留并重映射 `RUN_CASE` 关联，网页显示“执行用例”；未选历史时沿用原有排除计数。同选文件时也重写历史备注、步骤文本和实际结果中的标准同项目相对下载路径。
- 默认单次资料限制 8 MiB，同选文件为 32 MiB；最多 1000 批次、10000 执行用例、50000 步骤。文件沿用 100 个、合计 20 MiB 限制。
- 拒绝重复 ID、范围外关联、矛盾状态、通过用例中的非通过步骤、不合法时间及超限资料。历史与资产、缺陷、文件、幂等回执在原有事务流程中提交；后段失败回滚。
- V1–V5 的 JSON 字段顺序和空值省略保持兼容，旧回执可重试。本次无数据库 schema 迁移；V6 是传输协议版本。

历史时间按现有数据库毫秒精度处理，超出企业 MySQL TIMESTAMP 支持范围或带更细精度时明确拒绝。范围依据 [MySQL 8.4 时间类型说明](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/date-and-time-type-syntax.html)，不截断伪造时间。

## 测试证据

目录 `.testhub/transfer-history-20260923/`；先行设计见 `CASES.md`。

| 项目 | 结果 | 日志 |
| --- | --- | --- |
| H2 历史、迁移、执行生命周期专项 | 46/46，其中新增历史测试 8 项 | `targeted-verified.log` |
| 隔离 H2 全量与 JAR 构建 | 235/235；故意注入错误数据源仍保持隔离 | `backend-guard-verified.log` |
| 真实隔离 MySQL 8.4 / Redis 7.4 / MinIO 专项 | 46/46 | `mysql-transfer-tests.log` |
| 桌面 Node 测试 | 71/71，其中迁移协议 21 项 | `desktop-tests.log` |
| 前端回归 | 114/114 | `frontend-tests.log` |
| 最终构建与源码 ESLint | 通过 | `frontend-build-final.log`、`lint-complete.log` |
| 源码真实桌面到网页流程 | 30/30，含 7 MiB 附件 | `electron-final.log`、`electron-result.json` |
| 包内真实流程及资源一致性 | 30/30；17 项源码、JAR、前端入口 SHA-256 一致 | `package-transfer-final.log`、`electron-packaged-result.json`、`package-checksums.json` |

30 条真实流程按主要目的分为 19 条正向/完整性检查、11 条范围/拒绝/取消检查；包内重复同组流程不增加独立覆盖数。截图位于 `output/playwright/transfer-history-20260923/`，已检查原生完成页、历史执行页和缺陷详情页。

MySQL 专项实际运行 63 个数据库迁移和真实存储操作。早期隔离 H2 全量运行有 2 项 Web UI 用例因未授权固定测试地址而失败，随后仅在测试入口显式授权 `https://ui.example.test`，原权限断言保持不变，最终全量通过。重复运行和不同后端上的同组用例不能相加作为独立测试覆盖数。最初一个反例按随机 ID 顺序定位“已通过”用例，改为按真实状态定位后，原约束断言全部通过。首次真实页面流程沿用了“缺陷只有两条关联”的旧预期；V6 实际有需求、用例、执行用例三条，已调整数量并补充执行关联目标校验。

## 测试环境误连事件

本轮首次全量 Maven 命令漏掉隔离配置，2026-09-23 10:42:14–10:43:17 连接了已有本机 MySQL `13307/testhub`，并使用已有 Redis/MinIO。运行了验收写入和清理；235 项中有 3 项失败，该次不计入通过证据。

现有数据库确实发生测试操作。随后只读提取了该时段完整行格式的 MySQL 二进制日志，并按表和主键归并 1324 次行事件：302 条本轮临时记录已清理，584 条本轮新增记录仍在；未发现原有行被删除，2 条原有系统配置仅 `updated_at` 改变，配置值等业务字段一致。摘要在 `existing-db-binlog-summary.json`。这是所选时段的 MySQL 行日志核对，不是对 Redis/MinIO 的完整变更审计，也不等于现有环境完全未变。

该进程已结束，没有自动删除 584 条新增记录或回滚现有数据。已保存运行后数据库私密快照（0600）、原始二进制日志副本、只读审计汇总及测试清理语句清单。详细记录：`.testhub/transfer-history-20260923/ENVIRONMENT-INCIDENT.md`。私密快照和日志不应提交或上传。

防止重现：新增 `scripts/desktop/test-local-backend.mjs`，每次创建唯一 H2 目录、清除继承的 Spring/TestHub 配置、固定桌面 profile 和测试目标地址。`AGENTS.md` 已规定全量后端隔离入口；企业验证脚本只新建独立容器。后续隔离测试通过不代表此次现有环境影响已消除。

## 主要文件与后续

- `FunctionalTransferHistory.java`：导出历史、范围/状态/时间校验、直接复制执行快照。
- `FunctionalTransferService.java`、`FunctionalTransferController.java`：V6 选项、事务、回执兼容。
- `FunctionalTransferDefects.java`、`FunctionalTransferFiles.java`：执行关联及历史正文下载链接映射。
- 桌面 `functional-transfer.cjs`、`main.cjs`、`ui/transfer.html`、`ui/transfer-renderer.js`：范围选择、能力协商、预览和确认；`FunctionalDeliveryPage.vue`：执行关联中文标签。
- `FunctionalTransferFlowTests.java`、`functional-transfer.test.cjs`：新增历史、容量、权限、HTTP、旧协议及回滚回归。

内部 Mac arm64 工件位于 `.testhub/transfer-history-20260923/package/mac-arm64/TestHub.app`。仍为未签名内部 0.2.0、`distributionReady:false`；包内资源复测使用开发 Electron、解出的 ASAR 和独立测试目录，不是干净系统安装验收。源码及包内流程退出后，本轮专用运行时进程剩余数为 0，3 个隔离 MySQL/Redis/MinIO 容器均已停止并保留数据。未替换 `/Applications/TestHub.app`，未提交、推送或公开分发。

后续仍需功能模块剩余资产盘点（例如版本与自定义字段填写值）、其他模块资产迁移、完整离线账号同步、较大资料分批迁移、Windows/Intel/干净系统及签名分发验收。不能将执行历史迁移完成等同于整体改造完成。
