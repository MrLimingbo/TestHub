# 通知与集成：本地 Webhook、停用模板迁入及投递恢复

状态：系统设置模块于 2026-10-05 获用户审核通过。本轮只处理通知与集成；代码及隔离回归、源码和最终内部 Mac arm64 包验收已完成，本模块已于 2026-10-05 获用户审核通过，继续插件/运维模块。

## 范围与行为

- 实际路由 `/integrations` 保留 EMAIL、WEBHOOK、WECHAT、DINGTALK 模板定义；实际发送仍为质量报告的 `quality.report.ready` / `quality.report.risk` Webhook。页面与表单明确说明其他渠道尚未接入发送。已询问是否本轮扩展其他渠道，等待期间按现有能力与模板迁移范围实施。
- 通知页复用当前会话项目，顶部与页内切换都清空旧列表并重新读取；关闭旧表单、清空签名输入。请求序号和视图检查阻止迟到结果覆盖新项目；读取失败显示错误与重试。保存提前捕获项目、模板标识、字段并序列化，再请求 CSRF；失败保留输入、重复提交去重，取消或新表单不受旧回调影响。
- 新建默认为质量报告 WEBHOOK。编辑密钥留空保留原加密密钥；成功、取消、离开页面和切换非 WEBHOOK 渠道清空明文输入。删除和重新投递都有确认；删除定义不会撤回已有待发记录或删除历史，重投沿用原接收地址、载荷和投递标识，可能产生重复通知。中断错误完整显示，避免隐藏核对接收端的说明。
- 本地拥有者可在既有系统参数中配置允许域名；注册账号项目管理员仍无全局系统配置权限。Webhook 仍受 HTTPS、域名白名单、标准 443 端口及既有目标校验限制，不复制或复用本地执行网络授权。发送由当前后端完成，本地模式要求接收端能被当前电脑访问；本地安装不会自动获得公网入站地址。
- 本地模板迁入账号使用独立原生页面、冻结十分钟预览和默认取消的原生确认。只复制名称、事件、渠道、主题和正文，创建新标识及目标账号归属，**全部停用**；接收地址、签名密钥仅统计排除数量，目标独立重新填写。网络授权、历史、待发记录及定时计划不复制，不触发发送。自由文本正文可能含用户自行填写的敏感内容，页面提示检查来源。
- 新接口 `/api/v1/projects/{projectId}/notification-templates/transfer` 提供能力、导出、导入；沿用系统/组织/项目管理员和测试负责人写权限及 CSRF。上限 1000 个模板、32 MiB，严格校验字段与数量；项目锁串行化导入和模板 CRUD。来源同名模板需先改名，目标同名整批回滚不覆盖；相同账号、项目、UUID 请求与快照重试返回原回执，改变快照返回冲突，审计只记录数量。
- 质量报告 Webhook 载荷新增渲染后的 `subject`、`message`，保留已有事件、投递标识、状态、计划、范围、摘要、项目和报告链接字段。全部渲染主题在入队前校验，超过 255 字符显示报告运行失败，不出现部分入队或静默忽略。数据库写异常仅在确认同一去重键已入队时忽略。
- 手动重投按项目和标识读取记录，修复超过最近 100 条后找不到记录的问题；重试时间按毫秒存储。桌面单后端启动在调度器运行前把遗留 `DELIVERING` 标为结果不明的 `FAILED`，保留标识、尝试数及原快照，要求用户核对接收端后决定是否重投。`PENDING` / `RETRYING` 保留既有队列行为；在线多实例配置不运行此桌面恢复钩子。

同一注册账号桌面和网页使用共享服务，网页更新模板后桌面可重新读取。此能力不代表离线本地与账号自动双向同步已完成。

## 主要文件

| 文件 | 作用 |
| --- | --- |
| `src/pages/NotificationTemplatesPage.vue` | 项目隔离、异步表单保护、密钥清理及确认、渠道与网络说明 |
| `src/test/notificationsPage.test.ts` | 12 项页面生命周期与交互回归 |
| `services/platform-api/.../notification/NotificationTransferController.java`、`NotificationTransferService.java` | 严格迁移协议、权限、回执与事务回滚 |
| `NotificationTemplateRepository.java`、`NotificationTemplateService.java` | 导入与 CRUD 项目锁 |
| `NotificationDeliveryRepository.java`、`NotificationDeliveryService.java` | 渲染内容、错误处理、标识读取、重投和中断状态 |
| `services/platform-api/.../desktop/DesktopNotificationRecovery.java` | 桌面启动恢复钩子 |
| `db/desktop/V19__notification_transfer_receipt.sql`、`db/migration/V76__notification_transfer_receipt.sql` | 本地及 MySQL 迁移回执表 |
| `apps/desktop/app/src/notification-transfer.cjs`、`ui/notification-transfer.html`、`ui/notification-transfer.js` | 冻结预览、身份绑定、原生迁移页面 |
| `main.cjs`、`setup-preload.cjs`、`remote-preload.cjs`、`ui/transfer.html`、`ui/transfer-renderer.js` | 受信主框架 IPC 与现有迁移入口 |
| `NotificationTransferFlowTests.java`、`apps/desktop/test/notification-transfer.test.cjs` | 后端 10 项与桌面 5 项专项 |

数据库版本升级至 H2 V19 / MySQL V76，保留原业务记录及加密签名。

## 已完成验证

- 隔离 H2 全量 **350/350**，后端 JAR 构建成功。命令 `JAVA_HOME=<JDK21> node scripts/desktop/test-local-backend.mjs` 创建新 H2，不测试默认用户数据库。
- 全新原生 MySQL **8.4.10** / Redis **7.4.0** 专项 **10/10**，实际执行 MySQL V1–V76 迁移。新目录、随机端口及凭据，仅运行适配隔离配置的通知测试，并强制 `SPRING_DATASOURCE_*` 指向 QA；结束后停止自有进程。
- 后端专项包括真实自有 HTTPS 接收端、真实 HTTP 客户端与队列调度、HMAC 头验签、渲染主题/正文、503→204 重试标识和载荷一致。测试专用策略先验证正常 QA HTTPS 域名，再映射至自有 localhost 临时 TLS 端口，测试客户端信任自签证书；生产策略没有放宽，此证据不代表任意公网接收端通过。另有严格协议、权限/CSRF、审计失败回滚、同名冲突、并发回执及超长主题回归。
- 前端全量 **187/187**、桌面 **146/146**，lint、类型检查与生产构建通过；保留既有 Monaco 缺失 source map 和大分包提示。
- 旧系统设置 V18 内部包真实 Electron 基线 **6/6**：本地身份、账号与普通配置、环境及加密元数据、通知地址和签名状态、项目隔离、重启保持。
- 源码 V18→V19 升级 **4/4**：QA 旧工作区克隆保留拥有者、两个项目、账号、配置、环境和通知原字段；新增导出不含地址/密钥。自动升级快照 SHA-256 等于原数据库，原 QA 工作区不变。
- 源码真实 Electron + 独立 H2 账号服务 + 独立 Chromium 网页最终完整流程 **15/15**：失败保留、创建编辑与密钥保留、两处项目选择、重启、注册权限、冻结预览、原生取消、同名整批回滚、停用复制、网页配置/桌面共享、真实质量报告入队、五次重试及确认重投、桌面重启恢复、删除确认和历史保留、本地原件不变。最终页面调整后完整复验仍为 15/15。

- 最终内部 Mac arm64 包同一完整流程 **15/15**、V18→V19 升级 **4/4**；主进程、桥、原生迁移页、JAR、index 和全部前端静态资源共 **84** 个文件 SHA-256 与当前构建一致，`distributionReady=false`。提取 app.asar 后由同版本开发 Electron 启动包内 Java、后端和页面复验；不代表未签名原生 app、Gatekeeper 或干净机器安装通过。
- 已检查最终源码和最终包的本地通知、原生迁移完成及网页投递恢复截图。本地补拍复用 QA 工作区，源码/包各重新核对一次原始模板，共 2 项通过。

双端流程只投向保留的 `hooks.qa.invalid` QA 域名，不向第三方实际接收端发送。重启恢复场景在自有账号服务停机后，仅对 QA 数据库种入一个 `DELIVERING` 行，再验证真实启动钩子；这不代表真实网络发送中途断电实验。所有流程使用新本地数据目录与独立账号数据库，不写入用户已有工作区。

## 证据与失败记录

证据目录 `.testhub/notifications-module-20261005/`，截图目录 `output/playwright/notifications-module-20261005/`。

- 后端 `backend-final.log`；专项 `mysql-harness.log`、`notifications-mysql-tests-final.log`、`native-mysql-result.json`。
- 前端 `frontend-final.log`、`frontend-targeted-final.log`、`lint.log`、`build.log`；桌面 `desktop.log`。
- 旧包 `baseline.log` / `baseline-result.json`；源码升级 `upgrade-electron.log` / `upgrade-electron-result.json`。
- 源码流程 `flow-final.log` / `flow-result.json`；内部包 `package.log`、`package-verification.log`、`package-checksums.json`、`flow-packaged-result.json`、`upgrade-electron-packaged-result.json`。
- 最终内部包目录 `package-final/mac-arm64/TestHub.app`，后端 SHA-256：`11e5d67ae6f89b842a377524a33227c9b93d0d2001390838d04e1f6dc6d2a492`。本地源码与包内页面补拍在 `recapture-local.log`，复用各自独立 QA 工作区，核对原始模板后采集，避免项目下拉菜单关闭动画残影。

初次超长主题回归发现嵌套事务回滚会阻止保存报告失败状态，改为先校验全部主题且该预期异常不标记事务回滚；毫秒时间列导致立即手动重试可能尚未到期，改为截断到毫秒，最终 H2/MySQL 全部通过。页面专项的一处夹具文本误匹配静态说明，按唯一夹具标签修正。真实流程初次使用过期项目选择器未通过，改用实际可访问名称后首轮通过。截图补拍首次在本地会话授权完成前跳转，等待模板超时；改为等待真实本地入口就绪后跳转，源码和包各补拍通过。以上失败不计为通过。

## 审核与后续

本模块已于 2026-10-05 获用户审核通过，已继续插件/运维模块。邮件/企业微信/钉钉实际发送、插件/运维、移动端真机/模拟器、跨模块真实执行汇总、离线双向同步、干净机器首启、Mac Intel/Windows、签名公证及正式发行仍未完成。本轮内部包未安装到用户电脑或正式发布，未提交或推送代码。
