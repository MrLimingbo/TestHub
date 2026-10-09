# 任务中心：手工任务登记、并发保护与账号迁移

状态：报表统计模块已获用户审核通过。本轮只推进现有任务登记与账号迁移，实现、MySQL 与最终内部包验收已完成，2026-10-05 获用户审核通过，已进入项目设置模块；跨模块真实执行汇总暂未新增。已提前询问范围，尚未收到不同选择，按已说明的默认范围继续。任务中心范围已停在审核点并获得继续授权；跨模块执行汇总仍未新增。

## 现有行为与本轮范围

- 任务中心保存独立的 `execution_task` 手工登记，支持六类任务、五种状态、名称、进度和取消；API/性能/Web UI/移动端真实执行存于各自执行表，并使用各自消息流。此前 [基础能力报告](phase-2-node-5-2-report.md)已明确本页不消费执行队列。保留该契约和各测试模块的真实执行功能，页面改为明确“登记”，按钮使用“标记执行中”，不将改状态表述为启动测试。
- 修复顶部或页面项目选择后不刷新、迟到列表覆盖新项目、失败继续显示旧行、保存过程中取到另一个项目、失败表单提前关闭及重复取消等问题。操作捕获原项目/任务/输入；项目切换关闭旧表单，销毁页面丢弃迟到响应，列表错误可见且可刷新，单条操作在等待中阻止重复提交。
- 新增单调任务 `version`，新 UI 保存和取消提交 `expectedVersion`；旧版本返回 409，表单内容保留，不能覆盖另一窗口的新登记。服务对更新和取消加行锁，取消与完成竞争时只有一个版本能够成功；终态不可取消。兼容旧客户端无版本请求及既有手工状态编辑，包括终态再改为排队、成功自动 100%、排队自动 0%。备份恢复取消活动登记时同步递增版本。
- 原生“本地资料迁入账号”新增任务登记入口：复制当前项目类型、名称、进度及状态；成功/失败/取消保持，QUEUED/RUNNING 一律迁为 CANCELLED。来源任务标识、原状态、版本与原创建/更新时间保留为只读摘要，目标生成新标识、目标账号作为拥有者、目标新建时间，当前版本从 1 开始。来源人员及账号标识不跨服务复制。
- 允许与目标同名登记并新增，保留既有记录；没有将历史登记名称当作唯一资产名。不会复制执行清单、报告、工件、真实执行表或队列消息。目标编辑只改当前字段，来源摘要与本地原件保持；后续编辑不会自动双向同步。
- 只读冻结预览、10 分钟有效期、原生确认默认取消；账号/服务变化须重新预览。UUID 与 SHA-256 回执及目标项目锁支持并发重试不重复创建，任一数据库/审计错误整批回滚。协议 V1，范围 TASK_REGISTRATION，最大 32 MiB / 1000 条；HTTP 流式 33 MiB 上限。拒绝未知字段、尾随 JSON、无效类型/状态/进度/版本/时间/重复标识，沿用项目写权限和 CSRF。

## 实现位置

- 后端：`execution/ExecutionTask.java`、`ExecutionTaskRepository.java`、`ExecutionTaskService.java`、`ExecutionTaskController.java`、`TaskTransferService.java`、`TaskTransferController.java`。新增 H2 V17 / MySQL V74，任务版本与只读来源字段、迁移回执。
- 接口：既有任务 PUT 与取消 POST 增加可选 expectedVersion；迁移能力/快照 GET 与确认 POST 为 `/api/v1/projects/{projectId}/tasks/transfer`。
- 桌面：`task-transfer.cjs`、原生 HTML/JS、主入口、主进程与预加载桥；前端：`src/pages/TasksPage.vue`。既有备份工具和传输边界同步适配。
- 测试：`TaskRegistrationFlowTests.java`、`src/test/tasksPage.test.ts`、`apps/desktop/test/task-transfer.test.cjs`。

## 已完成验证

- 最新隔离 H2 后端全量 **326/326**、JAR 构建成功。新增 7 项覆盖六类/五状态/进度/来源、活动转取消及零真实执行/消息、同名不覆盖、并发幂等、严格输入/身份、事务与审计失败回滚、版本冲突和旧接口、取消/完成竞争及 HTTP 权限/CSRF。
- 前端全量 **147/147**，任务页面专项 **8/8**，lint、类型检查和生产构建通过；桌面 **136/136**，新增原生迁移 5 项。保留既有分包体积和 Monaco source map 提示。
- V16 旧版真实 Electron 基线 **4/4**：六类任务、五状态与同名登记、进度归一化、项目隔离、原页面显示及重启身份/数据保持。
- 最新源码真实 Electron + 独立 Chromium **10/10**：真实登记与界面、重启、只读预览、取消不写入、导入新标识/拥有者/来源摘要与活动转取消、同名目标不变、四个真实执行表为空且质量执行数为 0、网页编辑、桌面过期编辑冲突且保留表单、取消后重载、返回本地原件不变。账号服务为另一个全新隔离 H2 工作区，没有向用户现有数据库写入验收夹具。

- 全新专用原生 MySQL **8.4.10** / Redis **7.4.0** 专项 **7/7**。使用新数据目录、随机本机端口、测试专用凭据，MySQL 完整 V1–V74 迁移及企业 mysql-qa 配置；没有连接默认数据库。测试后两进程停止，保留隔离 QA 数据。Redis 仅用于验收，没有加入安装包。
- 内部 Mac arm64 包核心完整流程 **10/10**、V16→V17 工作区升级 **4/4** 通过。升级仅操作旧基线克隆，保留原身份、两项目、六条原登记及另一项目一条登记、原任务类型/名称/状态/进度/拥有者/时间；旧登记版本初始化为 1、无来源摘要，新迁移接口读取完整范围。升级自动快照数据库 SHA-256 与旧数据库一致，原证据库未改动。

时间列排版已调整为固定宽度、较小字号及完整时间元素，防止分钟被断到下一行；进度只保留一个百分比文本，避免组件默认文本与页面文本重复；最终包重新构建后的完整流程 **10/10**、V16→V17 升级 **4/4** 通过，已检查最终账号网页及原生迁移截图。证据位于 `.testhub/tasks-module-20261004/`；截图位于 `output/playwright/tasks-module-20261004/`。

## 未完成边界

本轮完成范围为现有手工任务登记，跨模块真实执行汇总原先待范围确认；2026-10-05 用户明确要求增加统一列表，现单独推进 [统一列表模块](desktop-standalone-progress-2026-10-05-unified-executions.md)。各测试模块仍从其原入口发起、取消真实执行，未改变已有协议。移动端设备及环境验收、离线自动双向同步、Mac Intel/Windows 和干净机器安装、签名/公证与正式多平台发行仍待办。内部 Mac arm64 包为 distributionReady=false，未安装或发布。

## 过程说明

- 原 Docker 环境未运行，原默认 VM 启动未完成 SSH 就绪；独立 QA VM 可以启动，但 Docker Hub 下载超时。改用本机已存在的 MySQL 8.4 安装和官方 Redis 7.4.0 源码编译出的测试进程，目录全部为本轮新建。默认 VM 和本轮新建 QA VM 均已停止，Docker 默认上下文仍为 colima，未删除或修改既有数据库。
- Electron 自动下载等待网络，改为使用本机已有的同版本 43.2.0 Electron 分发目录构建；没有更新项目依赖。最终仍由包内源码/静态资源/后端/运行时哈希和真实流程核对。
- 首次前端测试对刷新前已卸载的旧行按钮做断言，已改为观察刷新后的当前按钮；首次真实网页脚本选错 Arco 选项角色，已依据实际结构修正。失败日志不计为通过；产品版本冲突和迁移在修正脚本后完整验收。

## 验收命令和证据

- H2 全量：`JAVA_HOME=<JDK21> node scripts/desktop/test-local-backend.mjs`，日志 `backend-tests-final.log`。
- MySQL/Redis：本轮 `native-mysql-qa.mjs` 启动隔离进程后运行 `-Dtest=TaskRegistrationFlowTests`；日志 `native-mysql-qa.log`、`task-mysql-tests-final.log`，结果 `native-mysql-result.json`。测试专用目录及凭据不进入源代码或安装包。
- 前端：`npx pnpm@10.13.1 test`、`exec vitest run src/test/tasksPage.test.ts`、`lint`、`build`；最终日志 `frontend-tests-final.log`、`frontend-focused-final.log`、`lint-final.log`、`frontend-build-final.log`。末尾时间列和进度文本排版调整重做页面专项、lint、类型检查和构建，保留之前全量行为测试结果。
- 桌面：`npx pnpm@10.13.1 desktop:test`，日志 `desktop-tests.log`。
- V16 基线：`baseline-result.json`、`baseline.log`；源码流程：`flow-result.json`、`flow-final.log`。
- 内部包：`package-final/mac-arm64/TestHub.app`；构建 `package-build-final.log`；校验 `package-checksums.json`；复验 `package-verification-final.log`、`flow-packaged-result.json`、`upgrade-electron-packaged-result.json`。
- 截图：`local-packaged-tasks.png`、`account-packaged-tasks.png`、`transfer-packaged-complete.png`（位于上述截图目录）。

包内主进程、运行时管理、预加载桥、原生任务迁移、主入口、执行设置、后端 JAR、前端入口和 TasksPage 资产共 **15 个文件**与当前源码/构建 SHA-256 一致；distributionReady=false。最终后端 SHA-256：`484140950cd3488a1aa36af67cbcd617b644d8fc92684a66cdbe31108c51b69f`。本轮未提交、推送、安装或发布。
