# 项目版本可选迁入账号

日期：2026-09-23 开始，2026-09-24 完成验证。状态：本阶段代码、自动化、源码及包内真实流程全部通过；整体独立安装与离线账号同步仍未完成。

## 行为与兼容范围

- 桌面新增默认关闭的“项目版本”选项，单独选择即可，不依赖测试计划、历史、标签、缺陷或文件。目标服务需声明 V7 能力，不支持时明确拒绝。
- 复制版本号、有序构建列表、四种发布状态、计划/实际发布日期和描述。使用目标项目的新 ID 和展示序号，由迁入账号创建；不冒用原创建人。
- 已发布版本必须包含实际发布日期，避免现有创建接口自动填入当天日期而改变历史。拒绝状态与日期矛盾、超出受支持范围的日期、重复版本号/ID、重复或含换行符的构建号及超量定义。单次版本最多 5000 条，也计入原有总资产 5000 条上限。
- 目标同名版本号（忽略大小写及两端空白）使整批停止；已有版本不覆盖。同选项目文件时重写版本描述中的标准同项目相对下载链接，缺失文件拒绝。未选文件时描述原样复制。
- 版本、其他资产、文件、审计和幂等回执沿用同一事务；后续缺陷创建失败会回滚。V1–V6 JSON 字段顺序和空值省略、旧回执重试保持兼容。本轮无数据库 schema 变更；V7 指迁移协议。
- 迁移仍为预览后主动确认的一次性复制，未实现后续编辑的自动双向同步。

日期范围采用 MySQL `DATE` 文档保证支持的 `1000-01-01` 至 `9999-12-31`，不依赖更早日期可能碰巧写入的行为。依据：[MySQL 8.4 DATE 类型说明](https://dev.mysql.com/doc/refman/8.4/en/datetime.html)。

## 本轮验证

证据目录：`.testhub/transfer-versions-20260923/`，先行用例设计 `CASES.md`。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 隔离 H2 迁移/执行生命周期专项 | 52/52；新增版本测试 6 项 | `targeted.log` |
| 隔离 H2 全量及 JAR | 241/241 | `backend-all.log` |
| 桌面 Node | 75/75，迁移协议 25 项 | `desktop-tests.log`、`desktop-targeted.log` |
| 前端回归 | 114/114 | `frontend-tests.log` |
| 类型检查/前端构建、ESLint | 通过 | `frontend-build.log`、`lint-final.log` |
| 新建隔离 MySQL/Redis/MinIO | 52/52；本轮三个容器已停止、数据保留 | `mysql-transfer-tests.log` |
| 原生桌面到独立网页 | 33/33；含 7 MiB 附件 | `electron-final.log` |
| 内部 Mac arm64 包内流程与 SHA-256 | 33/33；17 项资源一致 | `package-transfer.log` |

33 条真实流程按主要目的分为 21 条正向/完整性检查、12 条范围/拒绝/取消检查；源码和包内是同组复测，不相加作为独立覆盖数。最终结果分别见 `electron-result.json`、`electron-packaged-result.json`，资源哈希见 `package-checksums.json`，汇总见 `verification-summary.json`。截图位于 `output/playwright/transfer-versions-20260923/`，已检查迁入完成页和包内网页版本编辑结果。源码网页截图捕获到弹窗退出动画，包内复测已等待弹窗完全关闭再截图。

内部工件：`.testhub/transfer-versions-20260923/package/mac-arm64/TestHub.app`。仍为未签名内部 0.2.0、`distributionReady:false`；实际复测使用开发 Electron、解出的 ASAR、包内运行时和新建用户目录，不是干净系统安装验收。测试完成后本轮运行时进程剩余 0，三个隔离容器均停止且保留数据。没有替换 `/Applications/TestHub.app`，没有提交、推送或公开分发。

ESLint 首次扫描进入 `.testhub` 中的运行时副本及构建工件，主动终止了本轮该检查进程；现已按仓库生成目录边界排除这些内容，保留全部源码与测试检查，标准 `pnpm lint` 通过。

首次真实网页运行在“编辑版本”的构建输入框定位处超时：Arco 在已有构建标签时不显示占位文字。根据实际页面快照改用该输入组件内的输入框定位，保留原有编辑与 API 持久化断言；该次失败日志保存在 `electron.log`，不计为通过。

全量后端使用 `scripts/desktop/test-local-backend.mjs`，新建 H2 并清除继承的 Spring/TestHub 配置。企业模式专项脚本只创建本轮独立容器。本轮不使用原默认 MySQL/Redis/MinIO；此前误连事件及未清理数据继续以 [执行历史阶段记录](desktop-standalone-progress-2026-09-23-transfer-history.md) 为准。

## 功能模块资产盘点

本轮按当前路由、控制器与持久层重新核对：

| 当前功能 | 迁移覆盖及边界 |
| --- | --- |
| 需求、用例及其目录/关联 | V1；保留内容及目录层级 |
| 测试计划/有序关联 | V2；执行快照由 V6 另选 |
| 项目标签、资产标签、自定义字段 | V3 复制字典、关联、字段定义；当前没有已实现的自定义字段填写值表和填写值读写入口，不应虚构这项待迁移功能 |
| 缺陷、目录及关联 | V4；原人员归属按已说明的迁入边界处理，V6 可带执行关联 |
| 项目文件 | V5；包含未引用文件，受数量/体积上限约束 |
| 执行批次、用例/步骤历史 | V6；新 ID、原时间及快照，支持继续执行 |
| 项目版本 | 本轮 V7；当前功能资产没有绑定 `project_version` 的外键或版本关联 API |
| 治理页面目录 | 当前 `GOVERNANCE` 范围目录可单独存在，不绑定标签/字段；原迁移未包含该范围，仍需明确补齐和真实回归 |
| 汇总、导入导出/交换 | 不是新增持久化资产；仍需完成本地真实页面及交换闭环验收，不能仅由迁移测试推定通过 |

功能模块整体尚未验收结束。后续继续治理目录和剩余页面闭环，再推进其他模块；账号离线同步、分批迁移、Windows/Intel/干净系统首启、签名分发也未完成。

## 主要实现

- `FunctionalTransferVersions.java`：版本导出、校验、冲突检查、事务内创建与文件链接改写。
- `FunctionalTransferService.java`、`FunctionalTransferController.java`、`FunctionalTransferFiles.java`：V7 范围、快照/回执兼容及文件联动。
- 桌面 `functional-transfer.cjs`、`main.cjs`、`ui/transfer.html`、`ui/transfer-renderer.js`：范围协商、预览、确认、范围变化失效及文件响应上限。
- `FunctionalTransferFlowTests.java`、桌面 `functional-transfer.test.cjs`：新增 6 项后端及 4 项桌面回归。
- `eslint.config.js`：仅排除运行时缓存和生成工件，避免重复检查已打包的第三方代码。
