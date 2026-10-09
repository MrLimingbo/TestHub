# 功能测试：治理目录迁移与 Excel 交换回归

日期：2026-09-24。状态：功能测试模块本阶段已识别缺口已修复，源码桌面与内部 Mac arm64 包各 40 项真实流程通过。整体独立分发与账号离线同步仍未完成。

## 修复与功能补齐

### 治理目录显式迁入账号

新增默认关闭的“同时复制资产治理目录”，与标签、字段、计划、历史、文件和项目版本独立选择。目标需支持 V8，否则明确提示更新或取消该范围，不静默遗漏目录。

V8 保留治理目录的层级、顺序、名称及空目录，迁入目标的新“本地迁入”根目录，使用新 ID 和迁入账号归属；目标已有目录不覆盖。仅有目录的项目也可以迁移。沿用 5000 目录和 64 层上限，重复 ID、循环、丢失父目录、跨范围父子和越界引用拒绝。目录与其他资产、文件、幂等回执同事务，后段失败整批回滚。

V1–V7 仍排除治理目录，旧回执不新增空字段，原有范围与重试语义不变。V8 是传输协议版本，无数据库 schema 变更。

### 修复 Excel 往返丢失用例类型

专项测试真实复现：创建 API 类型用例，导出 Excel 并导入另一项目后，类型变成 `FUNCTIONAL`。原因是导出缺少类型列，导入固定写入功能用例；证据 `exchange-before.log`，原断言 `expected API but was FUNCTIONAL`。

现在在原九列之后增加“用例类型”，保留全部八种已支持类型；导入在预检中验证类型，非法类型不产生部分记录。旧九列模板继续按功能用例导入；新列留空也沿用该默认值，合法小写类型会标准化。需求关联依据目标需求编号重新绑定。

页面已说明：目录需预先创建且名称唯一，关联需求需先导入。Excel 交换保留资产正文，但不搬运附件文件；包含目录结构、文件及历史的账号迁移应使用显式迁移流程。旧版程序没有识别新类型列的能力，类型保留需要本次修复后的服务。

### 修复目录删除确认提前消失

真实页面验收发现，选择“删除目录”时，下拉菜单先关闭，嵌套确认框随之卸载；用户无法点击确认，也没有发出删除请求。独立复现记录在 `catalog-delete-repro.log`。最终改用项目已有的独立确认对话框模式：菜单正常关闭，确认界面独立存在。

初步保留菜单的方案虽通过单次删除检查（`catalog-delete-fixed.log`），连续操作发现菜单遮挡相邻目录按钮（`electron-verified.log`），因此未采用。最终独立对话框方案由完整桌面流程验证新建、重命名、非空保护及叶目录删除。

## 验证证据

先行用例设计及日志目录：`.testhub/functional-closure-20260924/`。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 修复前 Excel 类型往返 | 1 项预期失败，缺陷已复现 | `exchange-before.log` |
| 隔离 H2 专项 | 63/63：迁移 53、执行生命周期 4、交换 6 | `targeted.log` |
| 隔离 H2 全量及 JAR | 252/252 | `backend-all.log` |
| 新建 MySQL/Redis/MinIO 专项 | 63/63，容器已停止且保留数据 | `mysql-transfer-tests.log`、`mysql-run.log` |
| 桌面 Node 测试 | 79/79 | `desktop-tests.log` |
| Vue 前端 | 114/114 | `frontend-tests-final.log` |
| 类型检查/生产构建/ESLint | 通过 | `frontend-build-final.log`、`lint-final.log` |
| 真实源码桌面及独立网页 | 40/40 | `electron-verified-final.log`、`electron-result.json` |
| 内部 Mac arm64 包内流程 | 40/40，17 项关键文件哈希一致 | `package-transfer.log`、`electron-packaged-result.json`、`package-checksums.json` |
| 退出与隔离环境清理 | 本轮进程残留 0，3 个隔离容器停止，数据保留 | `closure-audit.json` |

本轮新增后端测试 11 项（治理目录 5、Excel 交换 6），桌面协议测试 4 项；同组测试在不同数据库/包内复跑不累加独立覆盖数。所有全量后端测试使用 `scripts/desktop/test-local-backend.mjs`；企业回归只使用本轮新建独立容器，未连接已有业务数据库。

首次真实页面运行通过既有迁移及编辑检查后，测试脚本因“治理子目录”同时命中左侧目录和 Arco 下拉选项而停止。已按实际结构限定到左侧目录树，未降低业务断言。失败日志保留在 `electron.log`，不算通过。后续 `electron-verified-modal.log` 在目录操作及指标已通过后，因直接点击 Arco 样式隐藏的 radio 输入框超时；脚本改为点击用户可见标签并断言选中状态。独立交换复测 `exchange-ui-repro.log` 与最终完整源码流程均通过，未使用强制点击或跳过断言。

包内验证使用解包后的真实应用代码与包内 Java、后端和前端，由开发 Electron 启动独立测试用户目录；没有直接启动已安装程序。源码及包内均验证回到本地后原始资产与文件字节不变，以及初始化过程中退出能关闭后台。检查了目录、汇总与交换页面截图，图表截图等待动画完成。收尾审计发现早先失败脚本遗留的独立账号测试服务 PID 29284，核对本轮日志和启动命令后发送 SIGTERM 停止；未删测试数据库或用户数据。

## 当前功能验收范围

本阶段已核对所有功能测试页：需求/用例资产与关联、测试计划/执行历史与结果、标签和字段定义、缺陷与追溯、项目版本；补充治理目录真实操作、汇总指标与 API 对照、页面按钮导出与选择文件导入、重复导入拒绝。上述流程均通过源码与包内复测，本阶段已识别缺口闭环，下一模块为接口测试。此结论限于列明的用例及环境，不能扩展为所有平台和所有业务路径均无缺陷。

已有自定义字段仅实现定义，没有填写值持久化功能，不能虚构填写值迁移。Excel 目录同名歧义仍按已有规则明确拒绝，不猜测目标目录。

## 主要文件

- `FunctionalTransferService.java`、`FunctionalTransferController.java`：V8 治理目录范围、事务和兼容回执。
- 桌面 `functional-transfer.cjs`、`main.cjs`、`ui/transfer.html`、`ui/transfer-renderer.js`：范围选择、协商与预览/确认。
- `FunctionalAssetExchangeService.java`、`FunctionalAssetService.java`：Excel 用例类型保留与共享校验；`FunctionalDeliveryPage.vue`：交换边界提示。
- `FunctionalTestingLayout.vue`：目录删除确认不再随菜单选择提前卸载。
- `FunctionalTransferFlowTests.java`、`FunctionalExchangeRoundTripTests.java`、`functional-transfer.test.cjs`：回归覆盖。

内部包位置 `.testhub/functional-closure-20260924/package/mac-arm64/TestHub.app`。仍为未签名 0.2.0 内部验证包；不代表 Windows/Intel、干净系统安装或签名分发通过。没有替换已安装 TestHub，没有提交、推送或公开发布。其他模块迁移、完整离线账号同步和跨平台发行仍需继续。
