# TestHub 安装即用：性能计划离线双向同步

日期：2026-10-08。上一模块“Mock 服务与规则离线双向同步”已获用户审核通过。本模块实现与验证已完成，并于 2026-10-09 获用户审核通过；其他模块继续保留待办。

## 使用与同步范围

桌面性能页和应用菜单新增“性能计划同步”。连接账号服务并登录后，选择本地项目和账号项目，先预览上传、下载、冲突及排除的执行历史数量，再通过默认取消的原生确认建立绑定。默认不启用，每种同步只绑定一组项目；性能、Mock、通知、配置和任务五种绑定独立共存。

按计划名称匹配，同步说明、线程组顺序及完整定义，包括三种负载模型、并发/时间/TPS、请求方法/URL/头/参数/正文、响应断言、质量阈值、内联 CSV 与集合点。整个计划作为冲突单位：首次同名不同内容、同一计划两端同时修改时保留双方，要求明确选择。确认期间再次编辑则停止；名称大小写不同需在两端手动统一。Map 键顺序不同不构成内容冲突，线程组顺序有意义。

目标 URL（包括 localhost）、请求内容及 CSV 原样发送到用户选择的账号服务，预览和绑定确认中说明这一点。同步保留各端已有计划的标识、创建信息和状态，更新递增本端版本；新计划为 DRAFT。现有 READY 计划以后提交时会使用更新后的定义，已经提交的任务保留冻结配置。同步本身不提交压测，不访问目标，不复制执行历史、指标、报告、JTL/日志、目标授权或 Runner 配置。目标环境和执行许可需在相应端单独设置。

应用运行时每 30 秒检查双向新建和更新；断网仍可在本机编辑，联网并恢复绑定账号会话后自动重试。支持立即同步、暂停、恢复、解除绑定和冲突选择。删除不传播、不自动重新创建；改名按新名称处理，原名称缺失需要核对。退出应用后不后台运行。每项目最多 100 个计划、每计划最多 10 个线程组，合并范围包含基线中缺失的计划；快照及合并定义最多 3 MiB，请求最多 4 MiB，原生 HTTP 层的响应限制仍为 4 MiB。

## 一致性与保护

- 新增 `/api/v1/projects/{projectId}/performance/sync` GET 快照和 POST 新建/更新批次，沿用性能写入角色 SYSTEM_ADMIN、ORG_ADMIN、PROJECT_ADMIN、TEST_LEAD、TESTER。校验准确账号、项目权限、CSRF、协议、字段规范化、重复操作/名称及数量/字节边界，拒绝未知字段和尾随 JSON。
- 后端复用原计划服务的完整校验；原生侧校验负载、URL、请求头、CSV 格式与变量引用。保留原有禁止脚本函数和受限请求头等约束，不新增执行入口。
- 快照在一致读事务中生成，摘要包含计划标识、版本、状态、更新时间与完整定义；Map 按键稳定排序。读取前检查项目数量和大字段存储字节数，再检查完整快照大小。
- 原 CRUD、一次性迁移、同步以及执行提交读取冻结配置共用项目锁。过期快照、最终快照超限、审计失败和数据库名称唯一约束冲突整批回滚。MySQL 的名称排序冲突返回 409，需要统一名称。
- POST 前持久保存准确请求和 UUID。项目/账号/requestKey 唯一回执使提交成功后丢失响应的重试返回原结果；应用及账号 JVM 重启后不重复创建、不重复递增版本，也不覆盖提交后的新编辑。
- `performance-sync.bin` 保存在工作区外，使用系统安全存储加密、原子落盘，权限 0600。只保存绑定、定义基线、冲突和待发送请求，不保存密码、令牌或浏览器凭据。损坏时保留文件并报错。
- 复用已审核的原生 IPC/HTTP 层，仅信任对应原生页面及主框架；业务页面只有打开入口。保留会话隔离、重定向禁止、超时、响应有界及错误隐藏内部细节。
- 五种同步、迁移、执行设置及备份恢复共用互斥保护。恢复备份在数据库切换前持久暂停五个绑定并要求重新预览；新模块不修改四种旧同步文件格式。

新增 H2 V24 / MySQL V81 `performance_sync_receipt`，无新增依赖。原性能编辑、一次性迁移、执行和报告协议保持。

## 验证

| 检查 | 最终结果 |
| --- | --- |
| 隔离 H2 全量及 JAR 构建 | 414/414 通过，无跳过 |
| 全新 MySQL 8.4.10 / Redis 7.4.0 | 82/82 通过，无跳过；MySQL 显式 UTC，停止自有进程 |
| 前端全量 | 224/224 通过 |
| 桌面全量 | 219/219 通过，包含新性能同步专项 15 项 |
| JMeter Runner | 24/24 通过，无跳过，含真实 JMeter 执行 |
| ESLint、vue-tsc、Vite | 通过，保留既有大包提示 |
| 源码真实流程 | 七组共 86 项全部通过：性能同步 14、原性能执行 13、Mock 14、通知 14、配置 15、任务 12、V23→V24 升级 4 |
| 内部 Mac arm64 包 | 同组 86 项全部通过；148 个原生/后端/生产页面文件 SHA-256 一致 |

真实验收使用独立本地工作区、独立账号数据库及 Chromium 网页，未向既有 5173 服务或已安装程序写入 QA 数据。覆盖 GUI 编辑三种负载及 CSV、原生预览/取消、状态和标识保留、新计划草稿、大小写保护、Electron 实际阻断账号网络、30 秒自动重连、轮询焦点保留、过期冲突拒绝、提交成功后取消响应、双进程重启的原请求重放、系统加密/0600、独立网页编辑、账号切换/其他组织隔离和真实 V24 加密备份恢复后五种绑定重新核对。

内部包流程从 `app.asar` 提取原生代码，使用包内 Java、后端和生产页面，在同版本 Electron 下启动隔离 QA 工作区。此方式验证包内业务内容，未覆盖 `app.isPackaged` 下的正式用户目录、Finder 首启、Gatekeeper 或 DMG 安装路径；这些属于正式发行验收，不能用本轮结果替代。

性能同步验收监听目标请求并检查两端执行列表：同步全过程目标请求和新执行均为零。另行执行原性能回归，使用真实 JMeter 验证三种负载、CSV、集合点、质量断言、报告、JTL/日志下载与 SHA-256、取消及重启；未授权目标未被访问。该执行回归使用已有且经固定清单校验的引擎缓存，不作为全新机器首次下载的验收证据。

升级从已审核 V23 内部包和旧 QA 工作区的独立克隆开始，在旧版本中增加实际 Mock 规则/分享，并完成一条真实 JMeter 执行。V24 升级后，账号、任务、真实执行、配置、通知、API、Mock 密钥/规则/分享、插件信任和质量投影保持；性能历史、报告及 JTL/日志的实际字节、长度和 SHA-256 保持。四份旧同步文件字节不变，未自动创建性能绑定，升级保护快照与旧数据库一致，原工作区及种子未被改写。

初次 H2 失败为新增夹具错误（超时低于既有最小值、直接引用 outbox 不存在的 project_id），修正为符合原契约的值和执行表 JOIN，最终全量通过。扩展 MySQL 初次两项时间断言失败来自 QA 数据库主机时区与 JDBC UTC 不一致，改为全新实例显式 UTC 后 82 项通过，未改变业务或断言。JMeter 单独测试的缓存路径/入口执行权限错误改用自有 QA 启动器和现有产品同样的 Java JAR 入口，最终 24 项通过；首轮取消清理异常亦在最终完整执行中通过，原测试未弱化。失败日志保留在证据目录。

## 重要文件与证据

- 后端：`performance/PerformanceSyncService.java`、`PerformanceSyncController.java`、原计划 CRUD/执行提交的项目锁、H2 V24/MySQL V81、`PerformanceSyncFlowTests.java` 和同步请求大小过滤测试。
- 原生：`apps/desktop/app/src/performance-sync.cjs`、`performance-sync-ipc.cjs`、`ui/performance-sync.html` / `.js`、主进程/预加载入口及 `apps/desktop/test/performance-sync.test.cjs`。
- 前端：`src/pages/PerformancePage.vue`、`src/utils/desktopWorkspace.ts`、`src/test/performancePage.test.ts`。
- 证据目录：`.testhub/performance-sync-module-20261008/`；截图：`output/playwright/performance-sync-module-20261008/`。
- 最终日志：`backend-canonical-final.log`、`sync-mysql-tests.log`、`frontend-final.log`、`desktop-final.log`、`jmeter-tests-canonical.log`、`lint.log`、`frontend-build.log`、`flow-source.log`、`source-verification.log`、`package-verification.log`。
- QA 入口：`flow.mjs`、`execution-regression.mjs`、四个原同步回归脚本、`upgrade.mjs`、`source-verification.mjs`、`package-verification.mjs`、`native-mysql-qa.mjs`；隔离后端统一使用 `scripts/desktop/test-local-backend.mjs`。
- 内部包：`.testhub/performance-sync-module-20261008/package-final/mac-arm64/TestHub.app`；包内校验记录 `package-checksums.json`，各组运行结果按 `source` / `packaged` 分别保存。
- `final-evidence-check.mjs` 复核所有结果数量、包验收结果的新鲜度、零同步目标请求、未授权访问为零及当前文件哈希，全部通过；汇总记录 `final-verification.json`。
- 最终后端 JAR SHA-256：`ef55128e0ff7481e06d0cf09a8f1200a8a831c59938a096d43d5846ffbf85510`。

## 尚未完成

本轮只交付性能计划离线双向同步。功能用例、API 设计/用例、接口自动化、Web UI、移动端、报告与历史/附件等其他资产的离线持续同步仍待逐模块实现和审核；已完成的一次性迁移不等同于持续同步。跨端删除策略也仍待设计。

本轮内部 Mac arm64 `.app` 仅用于开发验收，`distributionReady=false`；没有替换已安装 TestHub。Mac Intel / Windows 干净环境、真机与模拟器、全新机器的引擎首次下载、正式签名/公证、第三方分发审核和正式安装包仍是后续门槛，不能宣称公开发行已经完成。公网账号服务需后续部署。
