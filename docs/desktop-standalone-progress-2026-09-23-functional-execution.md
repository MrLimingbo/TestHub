# 功能测试：计划执行生命周期修复与回归

日期：2026-09-23。范围：独立桌面改造中的功能测试模块；本轮未进入下一个模块。整体安装即用改造仍未完成。

## 已复现并修复的问题

1. **执行时间被后续保存覆盖**：第二个用例更新会重写批次首次开始时间，完成批次再次保存也会改写结束时间。现在保留首次开始时间；完成后的重复保存保留结束时间；重新打开执行时清除结束时间，再次完成记录新的结束时间。全部重置为未执行仍保留真实首次开始时间。
2. **并发结果导致批次汇总错误**：两个用例同时完成后，计数已经全部终态而批次仍为执行中。现在所有结果写入先锁定同项目的父批次，再更新并重算汇总。
3. **并发步骤与用例更新绕过状态约束**：同时将用例标记通过、将步骤标记失败，原先两者均可成功。现在步骤和用例共用批次锁，约束校验发生在锁内。事务采用 READ_COMMITTED，避免 MySQL 的锁前权限查询固定旧快照。
4. **保存后跳转到其他用例或最新批次**：重新加载列表无条件选中首项。现在只在原选择不再存在时选择首项。
5. **长 ID、下载路径及窄窗口布局溢出**：批次计数与状态重叠，计划操作按钮及步骤输入框超出内容区，计划状态标签被压缩裁切。修复文本收缩、网格最小宽度和操作区换行，状态与选择控件保留空间。

后端原始复现为 4 项测试中的 3 项失败、1 项通过，记录于 `reproduction.log`。页面选择丢失与布局溢出的失败记录分别为 `electron-ui-reproduction.log`、`electron-layout-final.log`；没有把修复前失败算作通过。

## 验证结果

证据目录：`.testhub/functional-execution-20260923/`。测试设计在 `CASES.md`，所有真实流程使用独立测试账号、数据库和桌面目录。

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| H2 后端全量测试及 JAR 构建 | 227/227，失败、错误、跳过均为 0 | `backend-all.log` |
| 最终共享配置下 H2 生命周期与迁移专项 | 38/38 | `h2-targeted-final.log` |
| 真实 MySQL 8.4、Redis 7.4、MinIO 专项 | 38/38，包括 4 项生命周期、34 项迁移 | `mysql-transfer-tests.log` |
| 前端测试 | 114/114，19 个测试文件 | `frontend-tests.log` |
| 桌面 Node 测试 | 67/67 | `desktop-tests.log` |
| 最终 Vue 类型检查、生产构建 | 通过，保留已有 chunk 大小提示 | `frontend-build-complete.log` |
| 修改页面 ESLint、diff 空白检查 | 通过 | `lint-final.log`、`git diff --check` |
| 最终源码桌面 → 账号服务 → 独立网页 | 27/27 | `electron-final-verified.log`、`electron-result.json` |
| 最终包内资源完整流程 | 27/27；17 项源码、JAR、前端入口 SHA-256 一致 | `package-transfer-complete.log`、`electron-packaged-result.json`、`package-checksums.json` |

同组用例在不同数据库或包内复测属于兼容性重复验证，不能将表中数字相加作为独立测试总数。MySQL 专项确实执行了全部 63 个数据库迁移，并通过真实 MinIO 写入、读取、故障回收和旧迁移协议验证；它不代表企业部署全部模块验收完成。

新增后端回归通过数据库实际锁等待状态协调并发事务，覆盖两个用例同时更新、步骤与用例相互竞争、重开及重复保存的时间语义，以及跨项目和只读用户拒绝。原生桌面和独立 Chromium 页面回归同时覆盖此前文件、计划、治理、缺陷迁移，以及本次选择保留、步骤状态拒绝、三档窗口宽度。

截图位于 `output/playwright/functional-execution-20260923/`；已目视检查最终 `web-lifecycle.png`。本轮仅对修改的 Vue 页面运行 ESLint，没有声称全目录 `pnpm lint` 通过。源码与包内流程结束后，本轮专用后端、Node Runner、Electron 进程剩余数为 0。

## 主要代码

- `FunctionalTestExecutionRepository.java`：保留生命周期时间、同项目父批次行锁。
- `FunctionalTestExecutionService.java`：结果与步骤写入的事务隔离和串行校验。
- `FunctionalPlansPage.vue`：保留有效选择、长内容及窗口布局修复。
- `FunctionalExecutionLifecycleTests.java`：4 项生命周期、并发与权限回归。
- `FunctionalDatabaseProfile.java`、`FunctionalTransferFlowTests.java`：默认独立 H2，显式配置才使用隔离 MySQL；MySQL 模式使用真实 MinIO。

## 工件和剩余工作

内部 Mac arm64 工件为 `.testhub/functional-execution-20260923/package/mac-arm64/TestHub.app`，内部版本 0.2.0、未签名、`distributionReady:false`。验证方式是解出 ASAR，以开发 Electron 加载包内运行时并使用新测试目录；这不等于干净系统安装或正式分发验收。

本轮保留已有工作树和测试数据，没有提交、推送、替换 `/Applications/TestHub.app` 或公开发布。两次隔离数据库验证的 6 个专用容器均已停止并保留数据，既有 4 个 TestHub 容器持续运行。

下一阶段仍在功能测试模块内：执行历史迁移及其回归。之后还需其他模块资产迁移、完整账号离线同步、较大文件分批迁移方案，以及 Windows x64、Mac Intel、干净系统安装、跨系统恢复、签名分发和移动端真机验收。不能以本轮计划执行修复宣称整个功能测试模块或独立安装目标已完成。
