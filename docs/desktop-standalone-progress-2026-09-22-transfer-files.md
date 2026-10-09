# 项目文件迁入账号：2026-09-22

本阶段增加独立勾选的项目文件复制。在原生迁移页选择“同时复制全部项目文件（含未引用文件）”后，预览文件数量和体积；确认后才上传。默认关闭，变更范围使旧预览失效。原有 V1–V4 资料迁移保持兼容。

## 实现与范围

- V5 复制来源项目文件库，包括未被本次资产引用的文件；保留文件名、类型、内容及大小，核验 SHA-256，在目标项目生成新 ID。同名文件不会覆盖；仅有文件的项目也能迁入。
- 需求说明、用例前置条件/步骤/预期、所选计划说明、所选缺陷正文中的标准同项目相对下载路径会映射至目标文件。外部、绝对、跨项目链接保持原文；不访问任意 URL，不宣称这些链接对应内容已迁移。
- 测试计划、标签/字段定义、缺陷三个选项在 V5 下仍可独立组合。未选择的范围不上传。执行历史、其他模块资产、密钥及原人员归属不在本阶段范围内。
- 单次最多 100 个文件，原始文件内容合计 20 MiB；V5 快照 JSON 最大 32 MiB，HTTP 请求/导出响应保留封装余量为 33 MiB。旧版本快照仍限 8 MiB，原生读取仍限 9 MiB。超过限额整次拒绝，不静默截断或丢弃文件。
- 幂等重试使用原始快照摘要，文件 ID/链接改写不改变重试身份。账号绑定、CSRF、原生最终确认及十分钟预览有效期继续生效。

## 文件写入与恢复

新增桌面 Flyway V6、企业 MySQL V63 的 `file_transfer_intent` 表。对象写入前用独立事务持久保存清理记录；业务事务锁定该记录，保护正在写入的文件。业务数据及文件元数据在同一事务提交，回调再清理记录或回收未提交对象。清理失败保留记录，定时任务重试超过一小时的记录；已提交文件不会被回收。

本地存储的临时文件名包含对象键的散列，使恢复任务能够清理该对象写入中断留下的临时文件，同时保留其他文件。已实际启动独立 Java 写入进程，在产生部分文件后强制终止，再用新存储实例清理并校验另一文件未受影响。完整迁移事务的未完成/已提交持久记录恢复、清理失败重试和并发锁由集成测试验证；没有宣称对整个迁移 JVM 的每个崩溃时间点做了穷举。

旧 V5 工作区升级至 V6 另用已有测试工作区的副本运行：先在旧运行时读取业务快照，关闭后使用新运行时自动升级，再比对目录、需求、用例、计划、治理、缺陷和执行批次。升级前数据库及加密密钥文件的 SHA-256 与自动备份一致，原始测试工作区未修改。

## 本轮发现并修复的问题

1. 中文文件下载响应头未编码，Tomcat 删除了非法 `Content-Disposition`，浏览器实际保存名变成 `download`。改用 UTF-8 文件名编码；HTTP 测试和独立 Chromium 的实际下载都验证中文文件名与二进制内容。
2. 全量回归暴露性能执行日志在同一毫秒内顺序不稳定：`QUEUED` 偶发排在 `MANIFEST_FROZEN` 前。日志 ID 改用仓库 API 自动化日志已有的单调 ULID 方式，并保留/扩展原顺序断言。
3. 文件页固定显示“MinIO 对象存储”，与本地运行模式不符；改为显示实际文件归属项目，不影响文件操作接口。

## 验证结果

证据根目录 `.testhub/transfer-files-20260922/`；用例设计为 `CASES.md`。所有真实流程使用独立用户目录、账号数据库和随机回环端口，没有改动已有 localhost:5173 服务和真实用户数据。原生确认框的按钮选择由测试控制，其余页面、IPC、HTTP、会话、CSRF、数据库和文件存储实际运行。

| 验证项 | 最终结果 | 证据 |
| --- | --- | --- |
| 迁移后端流程 | 34/34，含 8 项新增文件流程 | `backend-all-storage.log` |
| HTTP 请求字节限制 | 3/3，含无长度流、边界及重复读取拒绝 | 同上 |
| 本地对象存储 | 5/5，含独立写入进程强制终止 | 同上 |
| H2 后端全量回归及 JAR 构建 | 223/223，0 失败、错误、跳过 | `backend-all-storage.log` |
| 桌面 Node 测试 | 67/67，其中迁移协议 17 项 | `desktop-tests-final.log` |
| Vue 前端测试 | 114/114，19 个文件 | `frontend-tests-final.log` |
| Vue 类型检查与生产构建 | 通过；已有 chunk 大小提示仍在 | `frontend-build-final.log` |
| 源码范围 ESLint、diff 检查 | 通过 | `lint-final.log`、`git diff --check` |
| 桌面 → 账号服务 → 独立网页完整流程 | 23/23 | `electron-final.log`、`electron-result.json` |
| 最终包内资源重复完整流程 | 23/23；17 项源码/JAR/前端入口 SHA-256 一致 | `package-transfer.log`、`electron-packaged-result.json`、`package-checksums.json` |
| V5 → V6 真实工作区副本升级 | 通过；业务数据与升级前备份均核验 | `upgrade.log`、`upgrade-result.json` |

23 项真实流程按主要目的分为 14 项正向/完整性检查、9 项范围/拒绝/取消检查，最终全部通过。包内复测重复同组检查，不计作额外独立覆盖数。两次流程都验证正文下载路径、新文件 ID、二进制摘要、未引用文件、网页下载及源数据不变，并回归此前计划执行、缺陷编辑、治理布局、冷启动和退出初始化竞态。

截图在 `output/playwright/transfer-files-20260922/`；已目视检查原生迁移完成页与网页文件管理页。源码全目录 `pnpm lint` 会扫描大量本地打包/测试工件，本轮主动终止该次扫描后，对 `src`、桌面源码/测试、桌面脚本和相关 Runner 源码运行 ESLint，最终通过；未宣称全目录命令通过。

首次后端新增用例因 Mockito 无法直接代理 final 存储类而未启动，改用调用真实文件存储的测试委托；新增 schema 后两处旧版本预期从 5 更新至 6。真实网页首次运行复现中文下载名问题，全量回归另暴露上述日志排序问题。原始失败记录保留，最终结果仅以上表列出的最终日志为准。

## 主要修改

- `FunctionalTransferFiles.java`、`FunctionalTransferService.java`、`FunctionalTransferController.java`：V5 导出导入、摘要、范围及链接映射。
- `FileTransferStorage.java`、`FilesystemObjectStorage.java`、Flyway V6/V63：持久清理记录、事务保护及中断文件回收。
- `TransferPayloadLimitFilter.java`、`ApiExceptionHandler.java`：读取过程中限制请求字节并返回共享错误格式。
- `FileObjectController.java`：中文下载文件名编码；`ProjectSettingsPage.vue`：文件归属展示。
- 桌面 `functional-transfer.cjs`、`main.cjs`、`ui/transfer.html`、`ui/transfer-renderer.js`：可选文件范围、限额、预览和确认。
- `PerformanceTestExecutionRepository.java`：同毫秒执行日志排序修复；相应后端、存储、备份和桌面测试已更新。

## 工件与后续边界

内部 Mac arm64 工件：`.testhub/transfer-files-20260922/package/mac-arm64/TestHub.app`，版本仍为内部 0.2.0，未签名，`distributionReady:false`。解出 ASAR 后使用开发 Electron 加载包内运行时，在独立测试目录执行；这是包内资源验证，不等于签名安装包或干净系统安装验收。没有替换 `/Applications/TestHub.app`，没有提交、推送或公开发布。

后续仍需：执行历史与其他模块资产迁移、完整账号离线同步、更多/更大文件的分批迁移方案；真实 MySQL/MinIO、Windows x64、Mac Intel、干净系统安装、跨系统恢复、签名分发及移动端真机验收。本轮 H2 本地存储测试不能替代这些验证。
