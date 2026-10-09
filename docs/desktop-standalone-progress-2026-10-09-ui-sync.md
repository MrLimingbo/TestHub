# TestHub 安装即用：Web UI 资料离线双向同步

日期：2026-10-09。上一模块“性能计划离线双向同步”已获用户审核通过。本模块实现与验证已完成，并于 2026-10-09 获用户审核通过；后续移动端同步另行记录。

## 使用与范围

桌面 Web UI 页面和应用菜单新增“Web UI 资料同步”。连接账号服务并登录后，选择本地项目和账号项目，预览上传、下载、冲突及排除的执行历史数量，通过默认取消的原生确认建立绑定。默认不上传；六种同步绑定独立共存，每种绑定一组项目。应用运行时每 30 秒检查新建和更新，断网仍可编辑本机资料，联网且账号会话有效后自动重试。提供立即同步、暂停、恢复、解除绑定和冲突选择；退出应用后不后台运行。

按类型及名称匹配四类定义：浏览器类型、基础 URL 和视口；页面对象定位器及说明；场景说明、失败策略、变量和全部七种有序声明式步骤；定时计划的场景引用、功能测试计划编号、Cron、时区、超时与重试规则。Map 键顺序不构成内容冲突，步骤顺序有意义。引用通过名称映射为目标端自己的 ID，页面对象引用不携带派生定位器，避免修改对象时误判场景也被修改。

已有资料保留本端标识及创建信息，已有浏览器配置和定时计划保留各端启停；更新递增本端浏览器及场景版本。新浏览器配置和定时计划均停用，场景从版本 1 开始。关联功能测试计划只按编号寻找目标端唯一匹配项，不复制功能计划；缺少或重复时暂停该定时计划，其他资料可以继续同步。父浏览器、页面对象或场景存在冲突/缺失时，依赖它的场景或定时计划也暂停，不能绕过父冲突单独确认。

首次同名不同内容或双端同时修改均保留双方，等待用户选择；确认期间再修改则拒绝过期决定。名称大小写不同需手动统一。删除不传播、不自动恢复；改名作为新名称处理，原名称缺失要求核对。冲突定义完整显示在可用键盘滚动的区域，轮询保留冲突按钮焦点。

绑定确认披露 URL（包括 localhost）、变量和步骤内容将发送到所选账号服务。同步不提交执行、不访问目标，不复制运行器/目标授权、执行历史、报告、截图、视频或 Trace。已有启用定时计划以后正常触发时会使用更新后的定义；已提交任务保留冻结配置。目标环境和执行许可仍需单独设置。

每项目每类最多 100 条资料，场景最多 300 步；完整快照及合并定义最多 3 MiB，POST 和原生 HTTP 响应最多 4 MiB。合并数量也包含基线中待核对的缺失资料。

## 一致性与保护

- 新增 GET/POST `/api/v1/projects/{projectId}/ui-automation/sync`，沿用现有 Web UI 写入角色。校验准确账号、项目权限、CSRF、版本、字段规范化、重复名称/操作及数量/字节界限；拒绝未知字段、尾随 JSON 和脚本步骤。
- 复用原 Web UI 定义服务及 Cron 校验，无新增依赖或用户脚本执行入口。停用配置允许格式合法的 URL 定义流转；更新已有启用配置仍须具备目标授权。
- 快照采用一致读事务，摘要包含本端 ID、版本、启停、更新时间、完整定义及功能计划编号集合；Map 编码稳定排序。读取前先检查 SQL 数量和大字段字节数。
- 原 CRUD、迁移、同步和执行读取冻结定义共用项目锁；MySQL 写入使用 READ_COMMITTED，等待锁后重新读取当前定义。Runner 终态、定时触发和超时重试使用相同锁，跨项目超时扫描按项目排序获取锁。过期摘要、引用缺失、最终大小超限、审计失败或数据库名称冲突整批回滚。
- 定时绑定在数据库提交后刷新，并再次读取最新定义；删除/停用也在提交后移除 Quartz 任务。回滚不改变原 Quartz 计划。同步回执重放会恢复当前定时绑定，不覆盖之后的资料修改。
- POST 前持久保存准确请求和 UUID。项目/账号/requestKey 唯一回执使成功提交但响应丢失的重试返回原结果；应用和账号 JVM 重启后不重复创建或递增版本。
- `ui-automation-sync.bin` 保存于工作区之外，使用系统安全存储加密、原子落盘、0600 权限，保存绑定/基线/冲突/待确认请求，不保存密码或令牌；损坏时保留文件并报错。
- 复用已审核的 IPC/HTTP 和加密存储，仅信任本模块原生页面及主框架，业务页面仅有打开入口。六种同步及备份/迁移共用互斥保护，恢复备份前持久暂停全部六种绑定并要求重新预览。原有恢复策略仍会停用定时计划，避免自动重放。

新增 H2 V25 / MySQL V82 `ui_automation_sync_receipt`，旧同步文件格式保持。原编辑、一次性迁移、执行与报告契约保持。

## 验证

| 检查 | 最终结果 |
| --- | --- |
| 隔离 H2 全量及 JAR 构建 | 426/426 通过，无跳过 |
| 全新 UTC MySQL 8.4.10 / Redis 7.4.0 | 102/102 通过，无跳过，自有进程已停止 |
| 前端全量 | 227/227 通过 |
| 桌面全量 | 238/238 通过，含新同步专项 19 项 |
| ESLint、vue-tsc、Vite | 通过；保留既有大包提示 |
| 源码业务流程 | 九组共 113 项全部通过 |
| 内部 Mac arm64 包业务流程 | 同组 113 项全部通过；153 个文件 SHA-256 一致 |
| 后台桌面验收 | 启动探测及 Web UI 16、配置 15、任务 12、升级 4 项通过，窗口不可见且不获焦点 |

九组业务流程分别为 Web UI 同步 16、三浏览器执行 11、性能同步 14、真实性能执行 13、Mock 同步 14、通知同步 14、配置同步 15、任务同步 12、V24→V25 升级 4 项。包级流程前六组先通过；切换后台时的一项配置页截图超时保留为失败，随后后台重跑 Web UI 同步及剩余三组全部通过。各组最终结果均对应同一份当前包及已核对的文件哈希，不将中断/失败轮次计为通过。

真实同步验收使用独立本地工作区、独立账号数据库及 Chromium 网页，未向既有 5173 服务或已安装程序写入 QA 数据。源码 16 项通过，覆盖 GUI 离线及网页编辑、默认取消、ID/启停/七种步骤保留、引用重映射、父冲突阻断、缺少功能计划时单独暂停定时计划、实际 Electron 断网与自动重连、焦点与过期确认、提交后取消响应和应用/账号 JVM 双重重启、加密与权限、删除保护、账号切换及真实 V25 加密备份恢复。目标监听与执行列表证明同步全过程目标请求和新执行均为零。

原有执行回归另用真实 Chromium、Firefox、WebKit 和 JMeter，验证 GUI 提交、Quartz 触发、七种步骤、截图/视频/Trace、三种负载、CSV/集合点、质量断言、工件下载校验、拒绝未授权目标、取消与进程清理、重启。使用既有且经固定清单校验的组件缓存，不能代替全新机器首次下载验收。

升级从已审核 V24 内部包及旧 QA 工作区独立克隆开始，在旧版中增加四类 Web UI 定义并完成真实 Chromium 和 JMeter 执行。V25 后账号、任务、配置、通知、API、Mock 密钥/规则/分享、插件信任、质量统计及浏览器/性能资料与历史报告保持；截图、视频、Trace、JTL 和日志的实际字节、长度、SHA-256 保持。五份旧同步文件逐字节不变，未自动建立 Web UI 绑定，升级保护快照匹配旧数据库，原工作区及种子未被改写。

用户要求桌面测试不占用屏幕后，暂停前台流程并新增独立 QA 后台启动器。探测确认主页面和原生同步页正常加载、可截图，全部窗口不可见、未获焦点；测试进程使用 prohibited 激活策略，窗口从创建时采用 show=false、focusable=false、offscreen=true，并禁止 show/focus/restore。未预期的原生对话框会使 QA 报错，避免直接显示到用户屏幕；明确的测试确认由夹具拦截并检查标题/默认取消/内容。只调整 QA 启动和渲染选项，产品代码与包文件不变，今后这组 QA 默认后台运行。普通隐藏窗口期间一项截图超时，保留失败日志；受影响流程在离屏渲染模式中重新执行，原业务断言不变。

正式包验收边界仍保留：从 `app.asar` 提取代码，使用包内 Java、后端及生产页面，在同版本 Electron 中运行隔离工作区。此方式验证包内业务内容，未覆盖 `app.isPackaged` 下的正式用户目录、Finder 首启、Gatekeeper 或 DMG 安装路径，也未验证真实前台窗口激活、显示及 OS 文件选择器。

初次后端测试暴露了新同步字段比较错误：序列化的 record 字段顺序与 Map 顺序不同；修复为 JSON 结构比较后全量通过。桌面专项首轮为夹具仍使用单名称键，修正为类型/名称键后通过。真实流程初轮为 QA 定位器相对作用域错误；下一轮备份比较未考虑既有“恢复后停用定时计划”规则，已改为明确验证停用及更新时间、其余标识/定义逐字段保持，未改变恢复产品行为。运行时准备首次误用缺少 jmods 的精简 JRE，随后使用缓存完整 Temurin JDK 成功生成；失败日志保留。

## 文件与证据

- 后端：`uiautomation/UiAutomationSyncService.java` / `UiAutomationSyncController.java`、原 CRUD/调度/Runner 事件的锁与提交后定时刷新、H2 V25 / MySQL V82、`UiAutomationSyncFlowTests.java`、请求大小过滤及备份版本测试。
- 原生：`apps/desktop/app/src/ui-automation-sync.cjs` / `ui-automation-sync-ipc.cjs`、`ui/ui-automation-sync.html` / `.js` / `.css`、主进程与预加载入口、`apps/desktop/test/ui-automation-sync.test.cjs`。
- 前端：`src/pages/UiAutomationPage.vue`、`src/utils/desktopWorkspace.ts`、`src/test/uiAutomationPage.test.ts`。
- 证据：`.testhub/ui-sync-module-20261009/`；截图：`output/playwright/ui-sync-module-20261009/`。
- 自动检查日志：`backend-canonical.log`、`sync-mysql-tests.log`、`frontend.log`、`desktop-final.log`、`lint-final.log`、`frontend-build.log`、`prepare-final.log`。
- 真实入口：`flow.mjs`、`browser-regression.mjs`、`performance-regression.mjs`、`execution-regression.mjs`、四种旧同步回归、`upgrade.mjs`、`source-verification.mjs`、`package-verification.mjs`、`package-background-verification.mjs`、`background-bootstrap.cjs`、`background-probe.mjs`、`native-mysql-qa.mjs`。
- 最终真实日志：`flow-source-verified.log`、`source-verification.log`、`package-verification.log`（含切换后台时的失败）、`package-background-verification.log`、`background-probe-final.log`。
- `final-evidence-check.mjs` 复核九组结果数量、包验收证据新鲜度、零同步目标请求、未授权目标访问为零和当前文件哈希；汇总为 `final-verification.json`。后台启动窗口状态另见 `background-probe-result.json`。
- 最终后端 JAR SHA-256：`e508ef064b89fee3ec2261041797c660f9a9c7b2df74d31231a811ba4835c84f`。
- 内部 Mac arm64 包：`.testhub/ui-sync-module-20261009/package-final/mac-arm64/TestHub.app`，仅用于内部验证，`distributionReady=false`。

## 尚未完成

本轮只处理 Web UI 资料持续同步。功能用例、API 设计/用例、接口自动化、移动端、报告与执行历史/附件等其他资产持续同步仍需逐模块完成与审核；一次性迁移不等于持续同步，跨端删除策略仍待设计。

内部 Mac arm64 `.app` 没有替换已安装 TestHub，也没有提交 Git 或发布 Release。Mac Intel / Windows 干净环境、移动真机/模拟器、全新机器首次下载组件、正式签名/公证与第三方分发审核、正式安装包及未来公网账号服务部署仍待完成。
