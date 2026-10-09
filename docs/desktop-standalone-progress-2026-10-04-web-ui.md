# Web UI 自动化：本地执行与账号迁移

状态：Web UI 本轮范围已获用户审核通过，已进入移动端模块；以下保留本轮交付范围及验收证据。

## 本轮交付范围

- 沿用本地 Chromium、Firefox、WebKit 三种浏览器及隔离的 Playwright Runner，保留浏览器配置、页面对象、有序场景步骤、变量、失败策略、定时执行、失败重试、取消、报告和截图/录屏/Trace 下载。大组件仍按原有首次使用流程下载；本轮复用此前已校验缓存。
- 原生“本地资料迁入账号”增加 Web UI 入口，复制浏览器配置当前版本、页面对象、场景当前版本及变量/步骤、定时计划。迁入资产生成新标识，浏览器、页面对象和场景引用重新映射；步骤标识和顺序保留。页面对象引用继续沿用既有保存及执行时解析规则。
- 迁入浏览器配置与定时计划一律停用，不提交执行、不创建 Quartz 任务。基准 URL 原样保留，不复制本机授权。只有迁移创建停用配置的专用路径允许保存尚未授权的 HTTP(S) 地址；普通创建、编辑、启用和运行器校验沿用原有策略。账号服务与其他电脑上的 localhost 指向其自身服务，须核对地址、配置目标授权及运行器后手动启用。
- 定时计划保留 Cron、时区、超时和失败重试/退避策略，不复制最近执行状态。关联功能测试计划按目标项目的计划编号匹配，缺失或不唯一时整批停止，不按名称猜测，也不静默丢弃关联。
- 预览不写入目标，冻结快照并显示数量、账号、项目和服务地址；提交须原生确认，默认取消。预览有效期 10 分钟，账号或服务变化后重新预览。UUID 请求与快照哈希回执支持结果不明时重试而不重复创建。名称冲突、无效引用、任一业务约束错误整批回滚，不覆盖目标资料。
- 迁移最大 32 MiB，每类资料最多 1000 条、每场景 1–300 步；拒绝未知 JSON 字段和尾随内容，HTTP 请求另有 33 MiB 流式上限。沿用项目写权限、CSRF 和共享错误结构。
- 按本轮说明的默认范围迁移可编辑配置；执行历史、截图、录屏及 Trace 留在本地。现有模型只维护配置当前版本，迁移保留版本号，不新增历史版本档案。账号网页和桌面登录同一服务后读取共享服务端资料；本地迁移为一次性复制，后续本地/账号编辑不会自动双向同步。

## 实现位置

- 后端：`uiautomation/UiAutomationTransferService.java`、`UiAutomationTransferController.java`、`UiAutomationService.java`、`UiAutomationTargetPolicy.java`。新建 H2 V14 / MySQL V71 回执表；请求大小过滤器增加 Web UI 路由。
- 协议：`GET /api/v1/projects/{projectId}/ui-automation/transfer/capabilities`、快照 GET 和确认 POST；协议 V1，范围 `UI_AUTOMATION`。
- 桌面：`apps/desktop/app/src/ui-automation-transfer.cjs`、`main.cjs`、预加载桥、`ui/ui-automation-transfer.html` 和 `.js`，以及原生迁移入口。打开只接受可信主框架，预览/提交只接受对应原生页面主框架；与现有迁移和备份共用操作互斥。
- 测试：`UiAutomationTransferFlowTests.java`、`TransferPayloadLimitFilterTests.java`、`DesktopBackupToolTests.java`、`apps/desktop/test/ui-automation-transfer.test.cjs`。既有 Vue 页面及 Playwright 执行协议未改变。

## 已完成验证

- 隔离 H2 后端全套 **306/306**，无失败或跳过，JAR 构建成功。新增迁移 6 项覆盖版本/字段/步骤/引用、停用及启用授权、功能计划编号关联、幂等与名称冲突、后段错误回滚、严格字段和账号/项目权限。备份测试适配 V14。
- 全新 MySQL 8.4 / Redis 7.4 QA 容器专项 **6/6**，完成后停止 QA 容器并保留证据。本轮验收未连接默认业务数据库。Quartz 停用断言已按实际任务标识核对；MySQL 专项包含修正后的断言。
- 前端全套 **127/127**、lint、类型检查和生产构建通过；桌面 **118/118**，Playwright Runner **15/15**，无失败或跳过。构建仍有已有的分包提示，前端测试中的 Monaco 缺失 source map 提示不影响成功结果。
- 真实 Electron 本地执行基线 **11/11**：界面原生提交、三浏览器各 7 步成功、WebKit Quartz 定时执行、全部截图/录屏/Trace 大小与 SHA-256、阻止跨 Origin 重定向、失败步骤归因/重试关联、取消后浏览器子进程退出、报告界面及重启身份/配置/报告保持。专用未授权接收服务的请求次数为 0。该基线使用此前 V13 运行时，保留为升级前证据。
- 真实 Electron 原生迁移 **8/8**：三浏览器/场景当前第二版及已启用定时计划重启持久化、原生窗口保留账号页、只读预览、取消不写入、完整配置和新引用导入且全部停用、未授权地址无法启用/执行、独立网页通过界面编辑场景第三版及页面对象、返回本地核对原件未变。账号服务器使用另一全新隔离 H2 工作区。

- 最终内部 Mac arm64 包提取后的原生迁移 **8/8**、三浏览器真实本地执行 **11/11**、旧工作区升级 **4/4** 全部通过。旧 V13 工作区克隆升级保留原项目及 **6 个浏览器配置、6 个页面对象、6 个场景、7 条执行历史和 1 个停用定时计划**；三浏览器的报告摘要及截图/录屏/Trace 实际下载大小与 SHA-256 一致。升级前自动数据库快照哈希与旧数据库一致，原证据工作区未改动，新迁移接口可读取。
- 包内主进程、预加载桥、原生迁移文件、后端 JAR、前端入口及 Web UI 页面构建资产与当前源码/构建输出哈希一致。最终后端 SHA-256 为 `d494f2ad8b7ce14147cc8803f7c8ba9bd0f6c4763d6ab430d079835ecd8a351d`。已检查原生迁移完成页与真实报告截图。

## 证据与验证边界

证据目录 `.testhub/web-ui-module-20261004/`；截图 `output/playwright/web-ui-module-20261004/`。

- 后端：`backend-tests.log`；MySQL：`web-ui-mysql-tests.log`；桌面：`desktop-tests.log`；Runner：`runner-tests.log`；前端构建：`frontend-build.log`。
- 升级前本地基线：`local-flow-baseline-result.json`；源码原生迁移：`transfer-electron-result.json`。
- 内部包：`package-final/mac-arm64/TestHub.app`；包内校验：`package-checksums.json`；复验日志：`package-verification.log`。
- 最终真实流程：`transfer-electron-packaged-result.json`、`local-flow-packaged-result.json`、`upgrade-electron-packaged-result.json`。

内部 Mac arm64 包仍为 `distributionReady=false`，未签名、公证、安装或发布；使用开发 Electron 启动提取的包内程序与包内运行时验证，不代表 Gatekeeper 或干净机器安装验收。本轮未验证 Windows 或 Mac x64。首次浏览器下载、取消与失败恢复见 2026-09-20 浏览器模块既有记录，本轮不重复计为新增通过。离线双向同步、公网账号服务和正式跨平台发行尚未完成。
