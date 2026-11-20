# 安全复查与修复验证（2026-11-10）

本轮修复了 6 类已确认的代码漏洞，并更新存在已知安全告警的 npm 依赖。本报告记录当轮的检查、修复与隔离验证结果。严重程度按本项目的触发条件和影响判断，不等同于第三方 CVSS 评分。

本文件记录首轮代码修复的结果。后续 MySQL/Redis/S3 验收、镜像扫描发现的 zlib 与 MyBatis 内嵌 OGNL、基础库剩余告警以及 Colima 存储故障处理，以 [追加验收报告](security-infrastructure-validation-2026-11-10.md) 为准；不能将下文 Maven 解析坐标的零告警扩展为实际 JAR 内嵌依赖或 OS 镜像零告警。

## 问题清单

| 编号 | 严重程度 | 已确认问题及触发条件 | 修复与回归证据 |
| --- | --- | --- | --- |
| SEC-01 | 高 | JMeter 将线程组名称作为可求值属性处理。具有性能计划编辑权限的用户可在名称中嵌入函数，导致 Runner JVM 执行 Groovy。修复前的隔离验证确认该字段会求值。 | 控制面、Runner 工件验证、JMX 生成三层拒绝名称中的 `${`。Java 接口回归及 Node 协议/生成器回归通过。 |
| SEC-02 | 高 | 企业模式 JMeter 自动跟随重定向，只校验初始 Origin，已授权目标可将流量转向未授权目标。修复前两个独立本机端口的实验中，重定向接收端收到 1 次请求。 | 所有模式关闭自动重定向；旧调用方显式要求开启也不能绕过。真实 JMeter 回归中，初始目标收到请求，重定向接收端收到 0 次请求。 |
| SEC-03 | 高 | Mock 规则允许 HTML、JavaScript 等活动内容及自定义安全头，公开响应与登录 API 同源。具有 Mock 写权限的用户可构造存储型 XSS，让访问链接的更高权限用户执行脚本。 | 强制 CSP sandbox、nosniff、attachment；保留 JSON/+json MIME，其余内容以纯文本提供。真实 HTTP 覆盖 HTML/JS/SVG/XML 及恶意安全头；独立 H2 + Chromium 验证 JS 加载被拒、HTML 触发下载、管理员会话正常。 |
| SEC-04 | 高 | 账号管理与项目成员自动建号共用公开默认密码，攻击者知道账号后可尝试登录尚未改密的账号。 | 新建账号使用不可获知的随机凭据散列；管理员创建后自动进入已有的密码设置流程，项目成员页面给出设置说明。回归覆盖默认密码登录 401、单独设密后登录成功，以及项目成员自动建号路径。 |
| SEC-05 | 中 | API 脚本的 Promise/async 微任务可绕过原 VM 同步超时，持续占用 Runner；未处理拒绝或堆耗尽也可影响宿主进程。修复前 Promise 死循环超出 150ms，需外部终止。 | 微任务纳入 150ms VM 限时；每段脚本进入独立 Node 子进程，1500ms 生命周期、48 MiB V8 堆限制、无继承环境凭据。覆盖 Promise、async/await、未处理拒绝、堆耗尽及异常后的正常脚本执行。 |
| SEC-06 | 中 | 在线 HTTP 调试使用流式正文，原请求超时不能限制收到响应头后的正文停顿，可长期占用处理线程。 | 以单调时钟维护请求剩余期限，到期关闭响应流并取消已完成任务的计时器。真实本机服务发送头和首字节后停顿 1500ms，200ms 配置下客户端在 1200ms 内退出。 |
| DEP-01 | 最高含 Critical | npm 初次完整审计返回 103 条告警：3 Critical、54 High、36 Moderate、10 Low；其中生产依赖审计为 6 条。告警命中不代表所有条目均可通过本项目直接利用。 | 升级 Vue/Vite/Vitest/happy-dom/Electron，并对有安全修复的传递依赖设置版本下限；由 pnpm 生成锁文件。2026-11-10 完整审计为 0 条告警。 |

## 关键修改位置

- `services/jmeter-runner/src/{protocol,jmeter,config}.js`、`services/platform-api/src/main/java/io/testhub/platform/performance/PerformanceTestPlanService.java`：名称注入与重定向边界。
- `services/platform-api/src/main/java/io/testhub/platform/mock/MockRuntimeController.java`：同源 Mock 内容与响应头约束。
- `services/platform-api/src/main/java/io/testhub/platform/account/AccountManagementService.java`、`src/pages/AccountManagementPage.vue`、`src/pages/ProjectSettingsPage.vue`：账号初始化凭据与设密流程。
- `services/api-runner/src/{script-executor,script-worker}.js`：脚本进程隔离与资源限制；同步更新两份 Runner/pm 兼容协议说明。
- `services/platform-api/src/main/java/io/testhub/platform/apidebug/JdkApiDebugTransport.java`：流式响应正文期限。
- `package.json`、`apps/desktop/package.json`、`pnpm-workspace.yaml`、`pnpm-lock.yaml`、`vite.config.ts`：安全依赖版本与 Vitest 配置类型兼容。
- 对应 Java/Node/Vue 回归测试及 `scripts/security/check-mock-browser.mjs`：可重复的负向验证。

## 验证结果

环境为 macOS arm64、Node 24.14.1、JDK 21.0.12.1、JMeter 5.6.3；浏览器验证使用已安装的 Playwright 1.61.0 Chromium headless shell。后端验收通过仓库脚本创建全新 H2 工作区，没有连接默认或共享数据库。

| 检查 | 执行方式 | 实际结果 |
| --- | --- | --- |
| 前端完整回归 | `npx pnpm@10.13.1 test --maxWorkers=2` | 100 个文件，951 项通过，0 失败 |
| 后端完整验收和 JAR 构建 | `JAVA_HOME=<JDK 21> node scripts/desktop/test-local-backend.mjs` | 1378 项：1375 通过、0 失败、3 条件跳过；BUILD SUCCESS |
| 四类 Runner 与共享传输 | `node --test services/api-runner/test/*.test.js services/jmeter-runner/test/*.test.js services/playwright-runner/test/*.test.js services/appium-runner/test/*.test.js services/runner-transport/*.test.mjs` | 130 项：128 通过、0 失败、2 项真实 JMeter 测试因通用运行未设置 JMETER_BIN 跳过 |
| 真实 JMeter | `JAVA_HOME=<JDK 21> JMETER_BIN=<JMeter 可执行文件> node --test services/jmeter-runner/test/jmeter-runtime.test.js` | 上述 2 项另行执行，2 通过、0 跳过；包括禁止跨 Origin 重定向 |
| 桌面 Node 回归 | `npx pnpm@10.13.1 desktop:test` | 756 项：755 通过、0 失败、1 项 Windows 专属测试在 macOS 跳过 |
| 开发启动流程 | `npx pnpm@10.13.1 test:startup` | 15 项通过，0 失败 |
| Mock 浏览器攻击路径 | `JAVA_HOME=<JDK 21> TESTHUB_QA_BROWSER=<Chromium 可执行文件> node scripts/security/check-mock-browser.mjs` | `mockScriptBlocked=true`、`mockHtmlDownloaded=true`、`victimSessionIntact=true`；使用本轮新构建 JAR |
| 静态检查 | `npx pnpm@10.13.1 lint` | 0 错误；原有 `src/test/apiDesignFixes.test.ts` 2 条 Vue prop 类型警告 |
| 生产构建及类型检查 | `npx pnpm@10.13.1 build` | vue-tsc 与 Vite 构建通过 |
| npm 完整依赖审计 | `npx pnpm@10.13.1 audit --registry=https://registry.npmjs.org --json` | 548 个依赖，所有严重级别均为 0；显式使用官方 registry，避免镜像不支持审计接口 |
| Maven 运行时依赖核对 | `JAVA_HOME=<JDK 21> ./mvnw -pl services/platform-api dependency:list -DincludeScope=runtime -DoutputFile=<临时清单>`，按解析出的坐标和版本查询 OSV | 190 个去重后的 Maven 包版本，0 条返回的漏洞记录；查询日期 2026-11-10 |
| 补丁检查 | `git diff --check` | 通过 |

后端跳过项来自 `AuditResourceMysqlMigrationTests`、`P1RedisHttpFlowTests` 和 `P1CompatibilityTests` 的环境条件，不能计作通过。前端日志还有 Monaco 缺失源码映射提示，生产构建有大分块提示，均未导致检查失败。

## 修复后复查

再次检查了修复入口、共享调用路径、旧工件/调用方绕过方式、响应头覆盖以及异常后的资源回收，并运行上述完整受影响测试。已确认的 6 类漏洞均有修复和回归覆盖；在本轮检查范围内没有新增已确认的代码漏洞。

npm 依赖以官方审计数据为依据。版本下限参考了 [Vue 服务端渲染公告](https://github.com/advisories/GHSA-g2v6-rqmx-r4w6)、[PostCSS 公告](https://github.com/advisories/GHSA-fxqj-rqcc-2cmp)、[source-map-js 公告](https://github.com/advisories/GHSA-68fv-2mgg-jv7q) 和 [nanoid 公告](https://github.com/advisories/GHSA-2v37-7h3g-55p8)。Java 核对使用 [OSV 批量查询 API](https://google.github.io/osv.dev/post-v1-querybatch/)，按 Maven 解析后的实际包版本逐项查询，并检查返回数量与分页标记；这不是运行时可利用性分析，也不覆盖 OS 基础镜像。

## 行为变化与剩余验证边界

1. 新建账号必须由管理员单独设置密码。本轮没有变更历史账号或凭据；已存在且仍使用旧默认密码的账号，需要管理员在自己的环境中重置。
2. JMeter 所有模式均不再跟随重定向；线程组名称不再接受 `${...}`。需要测试重定向后接口时，应显式配置并授权目标。
3. Mock 的 JSON/+json 类型保留，其余正文按纯文本下载；不再提供同源 HTML/JavaScript/SVG 的活动呈现能力。
4. 脚本新增子进程启动开销。48 MiB 是 V8 堆上限，不是整个操作系统进程的 RSS 上限；VM 与子进程不替代容器/操作系统隔离。异步脚本 API 仍不属于支持的兼容契约。
5. Electron 依赖已更新，当前锁定为 43.8.7；本轮未重新制作或安装 DMG/Windows 包，未验证新 Electron 原生二进制、签名、公证或安装升级流程。既有安装包不会随源码修改自动更新。
6. 隔离 MySQL/Redis/S3 验收与 API/Web 镜像 Trivy 门禁已在后续补验中执行，最终状态和未消除条目见追加报告。跨平台安装测试仍未执行。依赖数据库的零告警结果不能证明不存在未知漏洞，也不能替代实际交付镜像扫描。

隔离后端工作区、浏览器验收工作区和本轮测试进程均按所有权清理；保留本报告与可复用的回归脚本，不保留含测试请求细节的原始验收日志。
