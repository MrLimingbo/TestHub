# 系统设置：本地管理员、账号环境与表单保护

状态：项目设置模块于 2026-10-05 获用户审核通过。本轮完成系统设置实现与隔离回归、源码和最终内部 Mac arm64 包验证，本模块已于 2026-10-05 获用户审核通过，通知/集成见下一模块记录。

## 范围与行为

- 本轮处理实际路由中的账号管理、环境管理和系统参数。新增 `GET /api/v1/administration/capabilities`，经认证返回 `{systemAdministration: boolean}`，复用既有 SYSTEM_ADMIN 判定，不授予新权限。项目令牌不具备系统管理能力。
- 本地工作区拥有者保留账号、环境及系统参数管理能力。注册账号默认从系统设置进入项目环境，侧栏只显示环境入口；直接访问账号或参数页面显示权限说明，不挂载管理子页面、不调用全局账号/参数接口。后端原接口仍返回 403，前端隐藏菜单不能替代后端鉴权。能力查询失败显示重试，不猜测权限。
- 环境页监听当前项目变化，清空旧数据和筛选、关闭旧环境/变量/删除表单与详情抽屉。请求序号、原项目和视图检查防止迟到结果覆盖当前项目；读取失败有可见错误及重试。创建、编辑、变量/密钥保存和行操作捕获原目标，提前序列化请求，再等待 CSRF；重复提交去重。旧保存回调不能关闭新表单，失败保留输入。
- 删除环境增加等待式确认弹窗，取消不写入，失败仍可重试；默认环境及已被执行资产引用环境的后端保护保持。变量/密钥删除继续使用原确认交互。环境定义及普通参数迁入账号复用上一模块入口，不新增迁移协议。
- 账号查询以最后发出的查询为准，失败清空旧列表并显示重试。保存捕获原账号标识和字段，改密捕获原目标和密码；取消、成功及卸载清空密码输入，旧回调不能关闭新密码表单。创建初始密码、账号停用/软删除及本人停用/删除保护沿用原契约。
- 系统参数保存改为等待式弹窗钩子，失败保留编辑值；保存、删除、私网开关去重，删除有确认。普通参数仍支持空值。既有 `secret=true` 的系统配置不会被普通参数 upsert 降为非敏感记录或覆盖，返回 409；列表不回显、普通删除不删除该记录。自由文本输入不能自动判定是否为凭据，页面明确提示不要填写敏感值；项目环境密钥仍使用既有加密存储。
- 本机页面解释 HTTP 私网 IP 开关及精确目标授权的区别，并提供既有“本地执行设置”入口。localhost/回环地址须在原生设置逐目标授权；私网 IP 开关仍对当前本机后端生效。在线页面保留原网络限制及 DNS 重绑定说明。账号密码、全局系统参数和本机网络授权留在各自工作区，不自动迁入账号。

同一账号桌面和网页读取共享服务的数据，网页环境/普通变量编辑可在账号桌面重新读取。此证据不代表离线本地工作区与账号自动双向同步已完成。

## 主要文件

| 文件 | 变更 |
| --- | --- |
| `src/layouts/SystemSettingsLayout.vue` | 能力读取、普通账号默认环境入口与管理页面挂载保护 |
| `src/pages/AccountManagementPage.vue` | 查询竞态、表单捕获、密码清理及去重 |
| `src/pages/EnvironmentManagementPage.vue` | 项目切换、迟到响应、表单保护及环境删除确认 |
| `src/pages/SystemSettingsPage.vue` | 参数保存与确认、本地/在线网络说明及原生入口 |
| `services/platform-api/.../administration/AdministrationCapabilitiesController.java` 与 `access/AccessService.java` | 管理能力接口与既有权限判断复用 |
| `services/platform-api/.../config/SystemConfigRepository.java` 与 `SystemConfigService.java` | 敏感行不被非敏感参数接口覆盖或降级 |
| `SystemSettingsFlowTests.java`、`src/test/systemSettings.test.ts` | 权限、CSRF、保密边界、生命周期与异步表单回归 |

本轮没有新增数据库迁移，H2 保持 V18、MySQL 保持 V75。应用后端构建变化仍触发原有工作区升级前快照机制。

## 已完成验证

- 隔离 H2 全量 **340/340**，后端 JAR 构建成功；使用 `JAVA_HOME=<JDK21> node scripts/desktop/test-local-backend.mjs`，不测试用户默认数据库。
- 全新原生 MySQL **8.4.10** / Redis **7.4.0** 专项 **7/7**，执行实际 MySQL V1–V75 迁移。新目录、随机端口及凭据，仅运行适配隔离配置的 `SystemSettingsFlowTests`，额外强制 `SPRING_DATASOURCE_*` 指向 QA 数据库；完成后停止两个自有进程。Redis 是缓存的测试二进制，不加入安装包。
- 前端全量 **175/175**，包含本轮 18 项；桌面 **141/141**；lint、类型检查及生产构建通过。保留既有 Monaco 缺少 source map 和大分包提示。
- 旧项目设置模块 V18 内部包真实 Electron 基线 **5/5**：QA 账号及非敏感参数、环境/普通参数/密钥脱敏、项目隔离、旧页面及重启数据保持。
- V18 工作区后端升级 **4/4**：旧 QA 基线克隆保留本地拥有者、两个项目、账号字段、系统配置、环境版本/默认/普通参数及密钥元数据；新能力接口识别原本地管理员。自动升级快照 SHA-256 与原数据库一致，原 QA 数据库不变。这是后端构建升级，数据库版本保持 V18。
- 源码真实 Electron 本地/独立账号服务与独立 Chromium 网页完整流程 **16/16**：管理员能力；账号失败保留、创建/编辑/搜索、改密及 QA 登录、删除确认取消/成功；参数失败保留与创建/编辑、私网开关及原生设置入口、参数删除确认；环境编辑与普通变量/加密密钥、默认动作、取消草稿和项目刷新、重启；注册账号默认入口/菜单/直接 UI 与 API 拒绝；网页环境/普通变量编辑在账号桌面可读；环境删除取消/成功及关联普通变量清理；返回本地原件不变。
- 最终内部 Mac arm64 包同一完整流程 **16/16**、V18 后端升级 **4/4**。包内原生主进程/桥/执行设置、JAR、index 和全部构建静态资源共 **81** 个文件 SHA-256 与当前构建一致；`distributionReady=false`。提取 app.asar 后由同版本开发 Electron 启动包内 Java、后端和页面进行复验；这不代表未签名原生 app、Gatekeeper 或干净机器安装通过。
- 已检查本地参数、账号环境与原生执行设置的实际截图。源码与包内最终截图均等待提示及抽屉动画结束后采集。

本轮源码和包流程使用全新本地数据目录及独立 H2 账号服务，不向现有用户工作区或已有联调数据库写入测试账号、参数或授权。

## 验证证据

证据目录 `.testhub/system-settings-module-20261005/`，截图 `output/playwright/system-settings-module-20261005/`：

- 后端 `backend-final.log`；专项 `mysql-harness.log`、`system-settings-mysql-tests-final.log`、`native-mysql-result.json`。
- 前端 `frontend-final.log`、`frontend-targeted-final.log`、`lint.log`、`build.log`；桌面 `desktop-final.log`。
- 旧包 `baseline.log` / `baseline-result.json`；源码升级 `upgrade-electron.log` / `upgrade-electron-result.json`。
- 源码流程 `flow-final.log` / `flow-result.json`；内部包 `package.log`、`package-verification.log`、`package-checksums.json`、`flow-packaged-result.json`、`upgrade-electron-packaged-result.json`。
- 最终包目录 `package-final/mac-arm64/TestHub.app`。后端 SHA-256：`e14402548d278df48e47cd0fc4f777c7f6e42288e2d71f25bb7ee418e92fb16f`。

首次新增测试使用 UUID 作为 26 字符 membership 主键、误断言创建环境返回 201，未通过；按既有 ULID 主键及 HTTP 200 契约修正测试夹具/断言后全量通过。前端密码组件的继承属性断言改为验证实际输入 DOM 值，18 项及全量通过。以上失败不计作通过。

## 审核与后续

本模块已于 2026-10-05 获用户审核通过，[通知/集成](desktop-standalone-progress-2026-10-05-notifications.md)已按模块继续。移动端真机/模拟器、跨模块真实执行汇总、离线自动双向同步、干净机器首启、Mac Intel/Windows、签名公证及正式发行仍待后续。内部包未安装到用户电脑或正式发布；本轮未提交、推送代码。
