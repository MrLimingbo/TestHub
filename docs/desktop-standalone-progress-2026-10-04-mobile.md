# 移动端自动化：配置迁移与本地 Appium Runner

状态：移动端配置迁移、编辑、本地 Appium Runner 对接及内部 Mac arm64 包复验已获用户审核通过。真机/模拟器执行及环境准备仍待办，移动端真实执行尚未闭环。用户再次要求继续后，已说明暂推进报表统计模块并保留设备验收待办；尚未收到不同范围的反馈。

## 本轮实现

- 原生“本地资料迁入账号”增加移动端入口，复制 Android/iOS 设备能力参数、应用标识及版本名称、元素定位器、场景变量/失败策略/有序步骤和配置当前版本号。迁入生成新标识，重映射设备、应用及步骤元素引用；设备和应用全部停用，不创建执行任务。
- 本轮暂按配置迁移范围处理，等待用户反馈是否还需要复制 APK/IPA。应用包文件 ID 不跨服务复制，预览和结果明确显示解除的关联数量；包内容、执行历史及截图留在本地。原件保持不变，一次性复制不代表后续自动双向同步。同一账号网页和桌面读取同一账号服务的数据。
- 预览只读、快照冻结、10 分钟有效期，原生确认默认取消；账号或服务变化使预览失效。UUID 与 SHA-256 回执保证结果不明时重试不重复建档；同名/UDID 冲突及无效引用整批回滚，不覆盖目标资产。最大 32 MiB，每类最多 1000 条、每场景 1–300 步，拒绝未知字段和尾随 JSON；HTTP 流式上限 33 MiB。项目权限、账号身份及 CSRF 校验沿用现有规则。
- 增加设备、应用、元素、场景编辑。设备/应用/场景使用当前版本号防止覆盖，元素使用单调递增的毫秒更新时间令牌；过期编辑返回冲突。平台不能通过编辑改变，应用文件必须属于当前项目。既有执行清单冻结，不随后续配置编辑改变；停用资产可编辑但不能执行。
- 桌面本地执行设置增加独立 Appium 地址，可保留 `/wd/hub` 等路径；允许无凭据 HTTPS 或本机 HTTP，不借用 API 执行目标授权。旧版设置文件兼容，原 API 目标设置保存保留 Appium 地址。设置原子写入后重启本地工作区，使用独立互斥状态，修复设置保存被迁移锁挡住重启的问题。
- 包内增加隔离 Appium Runner，使用现有本地任务流、签名心跳、运行时清单与截图归档接口。使用前探测 Appium `/status`，再启动并验证 Runner 心跳；未配置/未就绪不会由桌面准备流程提交任务，也不影响本地其他模块启动。取消、失败、报告和重启持久化沿用原有协议。
- Runner 将已保存字符串能力参数转换为 Appium 要求的布尔值/整数，拒绝非法类型及同时开启 noReset/fullReset；长 WAIT 检查取消和截止时间。禁止 WebDriver、运行时和截图请求的自动重定向。IPv6 本机地址校验补齐。
- 修复启动后的账号设置窗口竞态：等待本地启动完成后再打开窗口，并重新校验请求窗口。迁移与备份沿用已有互斥和可信主框架限制。

## 实现位置

- 后端：`mobileautomation/MobileAutomationTransferService.java`、`MobileAutomationTransferController.java`、`MobileAutomationService.java`、`MobileAutomationController.java`、`MobileAutomationRunnerStatusService.java`、`desktop/DesktopWorkspaceController.java`；新增 H2 V15 / MySQL V72 迁移回执表。
- 迁移 HTTP 接口：`GET /api/v1/projects/{projectId}/mobile-automation/transfer/capabilities`、快照 GET、确认 POST；协议 V1，范围 `MOBILE_AUTOMATION`。资产更新为原集合下的 PUT ID 路由。
- 桌面：`mobile-automation-transfer.cjs`、原生 HTML/JS、入口和预加载桥、`local-execution.cjs`、`local-runtime.cjs`、本地执行设置、`main.cjs`。
- 前端：`src/pages/MobileAutomationPage.vue`、`src/utils/desktopWorkspace.ts`；Runner：`services/appium-runner/src/config.js`、`runner.js`；打包：`scripts/desktop/prepare-local-runtime.mjs`。

## 已完成验证

- 最新隔离 H2 后端全量 **312/312**，JAR 构建成功；新移动端迁移/编辑专项 6 项包含 Android/iOS 字段/版本/步骤/引用、文件关联解除、停用、权限、幂等、事务回滚及编辑冲突/冻结清单。元素专项覆盖时钟倒退时旧令牌仍失效。备份测试适配 V15。
- 全新专用 MySQL 8.4 / Redis 7.4 专项 **6/6**，容器测试后停止并保留数据；未连接默认业务数据库。
- 最新前端 **131/131**、lint、类型检查及生产构建通过；桌面 **126/126**、Appium Runner **12/12**。前端仍有既有分包体积及 Monaco source map 提示。
- V14 本地移动端升级基线 **3/3**：两平台各类资产创建和重启持久化、无 Runner 时执行正常超时并记录报告、未配置本地 Appium 准备被拒绝且没有额外任务。该基线没有设备命令执行。

- 源码真实 Electron 原生迁移 **8/8**：两平台当前第二版配置和文件关联重启持久化、原生入口保留账号页、只读预览、取消不写入、新标识及引用重映射、停用资产阻止排队、独立网页编辑场景第三版和元素并手动启用设备/应用、返回本地核对原件未变。账号服务为另一全新隔离 H2 工作区；网页使用独立 Chromium 会话。
- 源码本地执行协议集成 **8/8**：原生设置保存/重启、Android/iOS 各 7 步任务流与 Runner 指令、有效 PNG 归档/下载字节核对、字符串能力参数转换、阻止重定向且未授权接收服务请求数为 0、失败步骤归因、长 WAIT 取消及会话释放、报告界面和重启身份/资产/历史保持。使用专用 W3C HTTP 测试服务，没有真实 Appium 服务、设备或模拟器执行；截图为协议夹具生成的 1×1 PNG。
- 最终内部包提取后的原生迁移 **8/8**、W3C 协议集成 **8/8**、V14→V15 工作区升级 **4/4** 全部通过。升级仅操作旧基线克隆，保留原身份/项目、两平台各 2 个设备/应用/元素/场景及 1 条既有超时执行和报告；新迁移接口可读，未配置 Appium 不新增任务。升级自动快照与升级前数据库 SHA-256 一致，原证据数据库没有改动。
- 包内主进程、预加载桥、迁移窗口、本地执行设置和运行时、后端 JAR、前端入口及移动端页面资产、Appium Runner 全部源码及清单与当前源码/构建输出 SHA-256 一致。最终后端 SHA-256 为 `2fa7b98b907b3f5078e831817679d04185d2854cd6289efd8aaf9b29f9339307`。已检查原生迁移完成页和协议报告界面截图。

## 未完成及验证边界

当前 Mac 未发现 adb、Appium 或可用 simctl，尚未获得用户的设备/模拟器、Appium 服务和测试应用信息。真实 Android/iOS 设备执行 **0 项**，不能将 W3C HTTP 协议测试服务计为真机或模拟器通过。

包内目前只包含 Appium Runner 客户端，不包含 Appium 服务端、Android SDK、Xcode 或平台驱动；被测应用需已安装，现有执行清单使用应用标识，不自动下载或安装 APK/IPA。因此移动端在任意干净电脑上的依赖准备和真实执行闭环仍未完成。Appium 服务与平台驱动需单独安装，详见 [Appium 官方安装说明](https://appium.io/docs/en/latest/quickstart/install/)；能力参数类型依据 [官方会话能力说明](https://appium.io/docs/en/latest/reference/session/caps/)。

内部 Mac arm64 包为 `distributionReady=false`，未签名、公证、安装或发布。使用开发 Electron 启动提取的包内程序及包内运行时验证，不代表实际 .app/Gatekeeper 或干净机器安装验收；Windows x64、Mac x64、公网注册服务和离线双向同步未在本轮验收。

证据目录 `.testhub/mobile-module-20261004/`，截图 `output/playwright/mobile-module-20261004/`。早期验证遇到旧备份版本断言、设置互斥导致的重启失败、启动窗口竞态、独立结果流的持久化时差及脚本错误定位。最终成功记录按各 `*-final.log` 和最终结果 JSON 核对；过程失败不计为通过。

- 后端：`backend-tests-final.log`；MySQL：`mobile-mysql-tests.log`、`mysql-qa.log`；前端：`frontend-tests-final.log`、`frontend-focused.log`、`lint-final.log`、`frontend-build-final.log`；桌面：`desktop-tests-final.log`；Runner：`runner-tests.log`。
- 升级前基线：`baseline-electron-result.json`；源码流程：`transfer-electron-result.json`、`local-protocol-result.json`。
- 内部包：`package-final/mac-arm64/TestHub.app`；构建：`package-build.log`；包内校验：`package-checksums.json`；复验：`package-verification.log`。
- 最终结果：`transfer-electron-packaged-result.json`、`local-protocol-packaged-result.json`、`upgrade-electron-packaged-result.json`。
