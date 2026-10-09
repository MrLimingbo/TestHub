# 缺陷定义与需求/用例关联迁入账号：2026-09-22

本阶段在已有目录、需求、用例、测试计划、标签与字段定义迁移之上，增加独立勾选的缺陷迁移。默认不勾选；变更范围使旧预览失效。预览列出缺陷数、将复制的需求/用例关联数、不会迁入的执行记录关联数，确认后才上传。

## 实现与边界

- 保留缺陷编号、标题、DELIVERY 目录层级和排序、截止日期、类型、关键词、浏览器、操作系统、严重程度、优先级、当前状态、确认标记、解决方案、来源、外部链接及重现步骤。
- 所有目标缺陷采用新 ID；需求/用例关联只映射至本次迁入的资产。不复制执行记录关联，也不把它们转换成普通用例关联；通过预览明确显示排除数量。无目录、无关联、仅含缺陷的项目也可迁入。
- 不复制原作者、指派人、抄送人、解决/关闭人员和时间。新记录创建者是导入账号。已解决/已关闭状态保留，但不会将原历史处理行为归给导入者。
- `FunctionalDefectService.createTransferred` 复用现有业务校验和插入逻辑；已有创建入口保持原语义。编辑迁入缺陷且状态/解决方案未变时保留空历史归属，重新打开并实际解决后按现有生命周期记录当前操作者和时间。
- 缺陷、关联、其余所选资产及迁移回执共用事务。相同缺陷编号、错误目录、跨范围或重复引用、后置字段校验失败整批停止或回滚，不覆盖已有记录。
- 附件、正文链接指向的本地文件、执行历史仍未迁移。原始正文和外部链接保留，不能据此认为所引用文件已上传。没有增量合并和修改/删除同步。

## 协议兼容

- 新增 V4：`GET /api/v1/projects/{projectId}/functional/transfer?version=4&includePlans=false&includeGovernance=false` 可独立选择计划、标签/字段；V4 本身代表明确选择缺陷。
- 能力接口保留 `version:1`，支持版本为 `[1,2,3,4]`。目标不支持 V4 时，原生客户端在读取本地资产前提示更新或取消缺陷勾选。
- 默认 GET 仍为 V1，V2/V3 保持原范围，均不带 `defects` 字段或 DELIVERY 目录；V1-V3 快照字节及回执形状兼容，原幂等重试继续工作。V4 新增缺陷及关联计数。
- 继续要求项目权限、登录账号绑定、CSRF、原生确认、十分钟预览有效期和原请求重试。非法 RUN_CASE 引用不能混入可迁移关系。
- 缺陷计入总资产 5000 条上限；来源目录 5000、深度 64、快照 8 MiB。缺陷需求/用例关联总量和未迁入执行关联计数分别最多 50000。没有宣称极限规模性能验证。
- 本阶段复用现有表；桌面 schema V5、企业 MySQL V62 不变，无新增数据库迁移。

## 修改文件

- `services/platform-api/src/main/java/io/testhub/platform/functional/FunctionalTransferDefects.java`：便携数据结构、范围校验、冲突检测和关联映射。
- 同目录 `FunctionalTransferService.java`、`FunctionalTransferController.java`：V4 导出/导入、事务及回执。
- 同目录 `FunctionalDefectService.java`：迁入记录的生命周期归属保护。
- `apps/desktop/app/src/functional-transfer.cjs`、`main.cjs`、`ui/transfer.html`、`ui/transfer-renderer.js`：范围勾选、能力协商、预览计数及原生确认。
- `FunctionalTransferFlowTests.java`、`apps/desktop/test/functional-transfer.test.cjs`：新增针对性回归。

## 验证记录

证据根目录 `.testhub/transfer-defects-20260922/`，用例设计 `CASES.md`。测试使用独立本地和账号 H2 数据库、随机回环端口及测试账号；没有改动现有 localhost:5173 服务或真实用户数据。页面、IPC、HTTP、会话、CSRF 和业务服务实际运行，仅原生确认框按钮选择由测试控制。

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| 迁移后端针对性测试 | 26/26，新增 7 项并扩展 HTTP/只读校验 | `backend-targeted-final.log` |
| H2 后端全量回归及构建 | 211/211，0 失败、错误、跳过 | `backend-all.log` |
| 桌面 Node 测试 | 64/64，其中迁移协议 14 项 | `desktop-tests.log` |
| 前端测试 | 114/114，19 个文件 | `frontend-tests.log` |
| Vue 类型检查与生产构建 | 通过，保留已有 chunk 大小提示 | `frontend-build.log` |
| 修改路径 ESLint 与 diff 检查 | 通过 | `lint.log`、`git diff --check` |
| 原生桌面 → 独立账号服务 → Chromium 最终完整流程 | 20/20 | `electron-verified.log`、`electron-result.json` |
| 最终包内资源重复同一流程 | 20/20；17 项源码/JAR/前端入口 SHA-256 一致 | `package-transfer-verified.log`、`electron-packaged-result.json`、`package-checksums.json` |

20 项真实检查按主要目的分为 12 项正向/完整性检查、8 项范围/拒绝/取消检查，最终均通过。包内复测重复同一组检查，不累计为额外独立覆盖数；其他平台及未执行的事项不计为通过。

新增真实验收包括：缺陷默认排除、改变范围使预览失效、V4 独立范围及执行关联排除计数、完整内容和目录保留、重建需求/用例关联、人员与历史时间清除、网页编辑已关闭缺陷且不伪造历史处理记录、网页详情读取两条真实关联，以及源缺陷/所有原关联/原执行批次不变。同时保留此前的冷启动迁移、取消/换账号拒绝、计划执行、治理布局和退出初始化竞态回归。

截图在 `output/playwright/transfer-defects-20260922/`，已目视检查原生迁移完成页和网页缺陷详情。测试进程结束后完成了进程检查。

第一次新增后端测试在准备数据时选中了空计划，创建执行批次被正确拒绝；改为选择含用例的计划后全部通过，未修改产品逻辑或削弱断言。第一次真实 UI 流程在缺陷详情截图前定位到了两个抽屉（详情、隐藏的导入交换抽屉）；按实际“关联测试资产”内容限定详情抽屉后重跑完整流程。随后补齐详情列表的异步等待：先确认关联接口 HTTP 200，再等待两条关联渲染后检查数量。原始失败日志保留，不计作通过；没有删除或降低业务断言。

## 内部工件与待办

内部未签名 Mac arm64 工件：`.testhub/transfer-defects-20260922/package/mac-arm64/TestHub.app`，仍为内部 0.2.0，`distributionReady:false`。包内源码、JAR 和前端入口比对 SHA-256，解出 ASAR 后使用开发 Electron 与包内运行时在独立测试目录执行；这是包内资源验证，不是直接安装包/干净系统验收。未替换 `/Applications/TestHub.app`，未提交、推送或发布代码。

仍待完成：附件与执行历史迁移、其他模块资产迁移、完整账号离线同步；真实 MySQL、Windows x64、Mac Intel、干净系统安装、跨系统恢复、签名分发，以及移动端真机验收。
