# TestHub 当前项目详细分析报告

- 分析日期：2026-10-26
- 项目目录：`/Users/limingbo/Documents/codeXProject/testHub`
- Git 基线：`33c4906`，本报告主要针对**当前工作区**，不是仅针对该提交。
- 方法：目录与配置盘点、核心链路代码抽查、自动化测试、生产构建、纯内存取消探针。
- 配套证据：[`project-analysis-2026-10-26-evidence.json`](project-analysis-2026-10-26-evidence.json)。

> 范围声明：这是有实际运行验证的架构与风险分析，不是对约 13 万行内容逐行审计，也不是完整渗透测试。报告区分已验证事实、静态高可信风险和待验证事项。分析期间存在其他工作区写入及另一组 Maven 测试进程，因此不能将所有文件和测试结果描述为同一不可变快照；本轮发现变化后已复验前端及 lint，后端结论以本轮独立日志为准。

## 一、执行摘要

TestHub 已不是前端演示工程，而是一个同时提供 Web 团队服务和 Electron 本地工作区的综合测试平台。主要业务覆盖功能测试、接口设计与调试、API 自动化、Mock、JMeter 性能测试、Playwright UI 自动化、Appium 移动端自动化，以及执行汇总、报告和跨端同步。

**总体判断：架构方向合理，功能覆盖广，已有较强的测试与交付基础；当前更应优先补齐执行可靠性和部署一致性，而非继续横向扩展功能。**

本轮主要验证结果：

| 检查 | 实际结果 | 边界 |
| --- | --- | --- |
| ESLint | 最终通过，0 错误、2 警告 | 首轮 3 个 Buffer 声明错误在分析期间由其他工作区修改消除；本分析未改业务代码 |
| Vue/TypeScript 前端测试 | 最终 64 文件、543 项通过 | 首轮为 63 文件、508 项，随后新增测试进入工作区 |
| 前端构建 | `vue-tsc --noEmit && vite build` 通过 | 仍有大分包警告，不代表真实首屏性能已达标 |
| Spring Boot 后端 | 临时 H2 模式 901 项通过、JAR 构建成功 | 不等于 MySQL/Redis/MinIO 模式验证 |
| 工作区子项目测试 | 846 项中 844 通过、2 跳过、0 失败 | 包含桌面与各 Runner，不包含根前端和 runner-transport |
| 启动生命周期测试 | 15 项通过 | 测试使用隔离替身，不是完整生产 Compose 联调 |
| runner-transport | 6 项通过 | 单独执行，补足 pnpm 工作区扫描遗漏 |
| 多 Runner 取消探针 | 复现非执行 Runner 确认取消、执行 Runner 未感知 | 纯内存行为复现，未进行真实 Redis 集群注入 |

最值得优先处理的问题是：**多 Runner 取消路由、Redis pending 消息恢复、生产上传限制不一致、CI 对 Runner/传输测试覆盖不全。**

## 二、项目规模与技术构成

### 2.1 规模统计

以下统计选择业务代码、测试、SQL、脚本和部分配置后缀，排除依赖、构建产物、release、运行数据；并非纯业务代码量。工作区变化会带来小幅差异。

| 目录 | 选中文件数 | 约行数 | 职责 |
| --- | ---: | ---: | --- |
| `src/` | 144 | 22,778 | Vue 页面、组件、状态、工具及测试 |
| `apps/desktop/` | 206 | 23,076 | Electron 主进程、本地运行时、同步、备份、打包测试 |
| `services/platform-api/src/` | 751 | 73,713 | Java 控制面、数据库迁移、后端测试 |
| `services/api-runner/` | 11 | 2,225 | API 任务与受限脚本执行 |
| `services/jmeter-runner/` | 12 | 1,579 | JMeter 进程、采样指标、执行恢复 |
| `services/playwright-runner/` | 9 | 622 | 浏览器场景、截图/视频/Trace |
| `services/appium-runner/` | 5 | 202 | Appium/WebDriver 移动端执行 |
| `services/runner-transport/` | 2 | 189 | Redis/桌面 Broker 传输适配 |
| `services/testhub-cli/` | 4 | 684 | CI/命令行接入 |
| `scripts/` | 52 | 5,591 | 启动、隔离验收、安全、发布与运维 |

合计约 **130,659 行**。Java 主源码约 465 个文件，测试源码约 124 个文件。数据库有 **108 个服务端迁移文件**和 **52 个桌面迁移文件**。迁移数量不等于数据表数量。

### 2.2 技术栈及选择评价

- 前端：Vue 3、TypeScript strict、Vite 7、Pinia、Vue Router、Arco Design、ECharts、Monaco。
- 控制面：Java 21、Spring Boot 4.0.8、Spring Security、JDBC、Flyway、Quartz、Actuator；虽然引入 MyBatis Plus，抽查的主要仓储直接使用 `JdbcClient`。
- 团队部署：MySQL、Redis 会话与 Streams、MinIO。
- 本地桌面：Electron 43、H2 文件数据库、本地对象存储、独立 Runner 子进程。
- 执行：Node.js 22+，API/JMeter/Playwright/Appium 分离；运行时协议有版本和字段校验。
- 交付：pnpm 10.13.1 workspace、Maven wrapper、Docker Compose、Helm、GitHub Actions。

这些技术能支撑现有目标。风险主要来自**同一业务同时维护远程模式、本地模式、同步协议和多种执行引擎**，而不是某个框架选错。暂不建议为了“架构升级”拆成大量微服务。

## 三、总体架构与关键链路

### 3.1 逻辑架构

```text
Web 浏览器 / Electron Renderer
             │
         Vue + Pinia + Router
             │ /api/v1
      Spring Boot 模块化单体
       ├─ 账号、组织、项目、权限、审计
       ├─ 功能资产、接口、自动化、质量报告
       ├─ 数据快照、Outbox、执行状态、产物元数据
       └─ RuntimeStreams / ObjectStorage 等边界接口
             │
       ┌─────┴────────────────────┐
  团队模式                        桌面模式
  MySQL / Redis / MinIO           H2 / 本地 Broker / 本地文件
       └──────────┬───────────────┘
            独立 Runner 进程
       API / JMeter / Playwright / Appium
```

关键优点是用 `RuntimeStreams`、`RunnerRegistry`、`ExpiringStateStore`、`ObjectStorage` 等接口隔离基础设施。`@Profile("desktop")` 与非桌面实现复用业务层，避免另建一套完全不同的本地产品。

但这不是免费的复用：H2 的 MySQL 兼容模式、桌面队列语义和 Redis Streams 不完全相同。所有关键状态机都应有双模式契约测试，而不是只让其中一种通过。

### 3.2 登录与项目访问链路

`src/router/index.ts` 在导航前初始化 session，区分公共页面、登录页和必须选项目的页面；`src/stores/session.ts` 读取 `/auth/me` 和 `/access/context`，持久化当前项目 ID。本地会话过期时通过桌面桥续建会话。

服务端使用 Spring Security 会话认证、BCrypt 密码校验及 CSRF；PAT 使用独立 filter、token hash、项目范围和 scope 检查。当前 PAT filter 明确采用 transient context，并清理旧版本可能写入 session 的 PAT 身份，防止令牌认证遗留到后续无令牌请求。

`AccessService` 区分项目读取、参数写入、项目管理和 `run:execute`。业务服务如 `FunctionalAssetService` 在读写入口调用权限服务，仓储按 `projectId` 限定查询。**抽查链路有实际服务端权限，而不是只依赖前端菜单。**

注意：导航守卫不是授权边界；本次未逐个证明所有控制器和仓储均无越权，仍应把跨项目 ID 替换、已撤权会话和 PAT scope 组合列入持续测试矩阵。

### 3.3 自动化执行链路

`ApiAutomationExecutionService` 在事务内固化场景版本、运行变量来源、manifest、执行记录及 Outbox。Outbox dispatcher 抢占待发记录、写流、标记已发，失败重排并清理陈旧锁。Runner 再获取受签名保护的运行时配置，执行并回传事件和结果。

这个设计明显好于“收到请求立即启动进程”：具备审计、不可变执行输入、重试与超时治理基础。运行时敏感配置不直接混入任务流，也是正确方向。

仍需明确投递语义：Outbox 的“写流成功、数据库标记前崩溃”可能重投，属于至少一次投递。消费者需要业务幂等、执行租约/所有权和完成确认；不能把有 Outbox 等同于端到端 exactly-once。

### 3.4 桌面与账号同步链路

`apps/desktop/app/src/api-design-sync.cjs` 展示了较完整的同步策略：

1. 显式绑定服务、账号、本地项目与远端项目。
2. 预览合并，重新确认两端 revision 后才建立绑定。
3. 使用 bases 和两端内容判断单端变化或冲突。
4. pending 操作带 requestKey/operationId，支持中断后的安全续传。
5. 检查引用、身份变化、名称变化与同名重建。
6. 单端删除或引用缺失时暂停核对，不直接扩散删除。
7. 恢复备份后停用绑定并要求重新确认。

`EncryptedSyncStore` 限制明密文大小，采用临时文件、fsync、rename 持久化。本地业务状态不直接明文落成同步 JSON，是明显优点。

局限也很明确：接口设计同步存在 300 条和 8 MiB 等业务容量边界；这些是可验证的产品限制，不是无限数据量的团队同步能力。模块之间大量相似同步状态机会增加长期维护成本。

## 四、业务模块分析

| 模块 | 当前代码组织与能力 | 评价及重点 |
| --- | --- | --- |
| 账号、权限、管理 | `account/access/security/administration`，账号身份、成员、PAT、参数配置 | 已有角色和 scope 分层；优先保持账号唯一性、撤权和 session/PAT 隔离回归 |
| 工作台 | `dashboard` 聚合真实业务表、执行状态和审计 | SQL 按项目限定；时区显式为 Asia/Shanghai，适合当前环境，但未来国际化须可配置 |
| 功能测试 | `functional` 覆盖需求、用例、目录、计划、批次、治理、交付与缺陷 | 服务层有状态枚举、目录范围、唯一性与事务处理；与其他执行模块关联多，变更影响面较大 |
| 接口设计 | `apidesign` 的定义、版本、用例、导入导出、同步 | 功能深；`ApiInterchangeService` 约 1,198 行，应按格式拆适配器，固定兼容样本 |
| 接口调试 | `apidebug`，HTTP/GraphQL/SOAP/WebSocket/gRPC | 有目标策略、请求头限制、历史与脱敏；本地直接调试与服务端目标边界不能混用 |
| API 自动化 | `apiautomation` 约 88 个 Java 文件，场景、计划、Webhook、数据集、Outbox、运行租约 | 业务与协议最复杂；应先加强消息恢复、取消与执行幂等 |
| 性能测试 | `performance` + JMeter Runner，指标、资源、阈值、产物 | Runner 已有 pending 与 XAUTOCLAIM 恢复，可作为统一恢复实现参考；本轮真实 JMeter 测试跳过 |
| Web UI 自动化 | `uiautomation` + Playwright，声明式步骤和浏览器产物 | 有独立上下文、目标代理、禁用 service worker；需要强化任务 deadline、产物失败和取消投递测试 |
| 移动自动化 | `mobileautomation` + Appium，设备能力、应用、元素、场景 | 有能力白名单与 WebDriver 接口，不等于所有设备/驱动实测通过；本轮未连接真实设备 |
| Mock | `mock`，公共匹配与分享入口 | 公共路由是产品设计，不可仅因 permitAll 判为漏洞；须维持 share key、响应安全与限流验证 |
| 质量/任务/通知 | `reporting/execution/notification` | 提供跨模块汇总与执行历史；应建立统一状态语义、统计口径与失败追踪 |
| 存储/桌面/运维 | `storage/desktop/operations`、备份与部署脚本 | 本地文件和恢复保护较细；跨数据库兼容、生产配置、发行签名需独立验收 |

模块“存在”与“所有使用环境已验收”是两种结论。此表结合目录、配置、已抽查链路和测试结构，不将 README 声明视为本轮实机结果。

## 五、安全设计评估

### 5.1 已确认的积极设计

- **凭据安全**：BCrypt；PAT 随机生成且保存摘要；`SecretCipher` 使用 AES-GCM、随机 nonce、版本化 key ID 与旧密钥回读。
- **Web 边界**：CSRF、明确 CORS origins、HttpOnly 会话 cookie、错误 envelope 和 request ID。
- **桌面 Renderer 边界**：`contextIsolation: true`、`sandbox: true`、`nodeIntegration: false`、`webSecurity: true`；关键 IPC 校验发送方和主 frame。
- **本地 Broker 边界**：仅 desktop profile；检查 loopback、拒绝 Origin、按引擎与 Runner 绑定凭据，限制命令、流和字段大小。内部路由在 SecurityConfiguration 中 permitAll，不等于无需内部凭据。
- **文件边界**：`FilesystemObjectStorage` 对 object key 做 SHA-256 映射，检查符号链接，校验实际长度并使用原子替换。
- **API 执行网络边界**：Origin 白名单、私网地址判断、DNS 解析后固定连接地址，减少 DNS rebinding 风险；不自动跟随 HTTP 重定向。
- **富文本边界**：`src/utils/richText.ts` 对标签、属性及 URL 协议做允许列表清洗。
- **生产容器**：控制面无宿主端口暴露、只读根文件系统、cap drop、no-new-privileges、资源限制；Prometheus 不从 Nginx 对外开放。

### 5.2 不能由这些设计直接推出的结论

`node:vm` 的受限上下文和关键词拒绝规则**不应被描述为不可信代码的完整安全沙箱**。API Runner 已限制脚本长度、同步执行时间和能力，并避免向 context 传宿主回调；但内存分配、进程资源、运行时漏洞仍依赖外层隔离。桌面 Runner 是本机子进程，不能自动获得生产容器的资源边界。建议每个用户脚本独立进程/worker，设置内存与生命周期限制，并确保恶意内存分配不能拖垮长期运行的 Runner。

`application.yml` 包含开发默认数据库密码、加密 key 和心跳 key。生产 Compose 强制注入关键存储/加密值是正确的，但开发默认值仍不应出现在直接运行 JAR 的生产环境。建议增加明确生产 profile 的拒绝默认密钥启动检查；这属于防误配置，不等于确认当前生产泄漏。

本轮没有运行最新漏洞库扫描、完整镜像扫描、签名验证或渗透测试，因此不作“无漏洞”“已满足生产安全要求”的结论。

## 六、问题清单与优先级

分级：P1 为可能造成执行语义错误/重要功能不可用的问题；P2 为工程质量、可维护性和持续交付缺口；P3 为治理完善。没有证据支持将本轮问题直接定为已遭利用的 P0 安全事件。

### F01 · P1：取消流共享消费组不能保证命中执行 Runner

**证据**：`services/api-runner/src/runner.js` 的 `consumeCancellationLoop` 使用固定 `CANCEL_CONSUMER_GROUP`，取消记录保存到该实例 `cancelledTasks`；`handleCancellationEntry` 即使不是任务执行者，也发布 `CANCELLED` 并 XACK。Playwright/Appium 也使用共享取消组与本地 running/cancelled Map。

**本轮验证**：创建 A/B 两个 API Runner 实例，将同一合法取消命令交给 B，B 确认并发布取消事件，但 A 的 `isCancelled()` 仍为 false。未连接真实 Redis。探针结果保存在证据 JSON 及 `.testhub/project-analysis-20261026/cancellation-probe.json`。

**影响**：多实例下取消命令可能被非所有者消费，真正执行者继续发出外部请求或占用设备。控制面是否接受非所有者事件还取决于事件状态校验，不能只靠“界面显示取消”证明执行停止。

**建议与验收**：按 owner 路由到专属取消流，或采用 durable cancellation registry 并由执行者检查；只有执行者确认资源停止后才报告完成。新增双 Runner 集成测试，强制取消先落到非执行者，验证真实任务/浏览器/进程停止。单纯增加消费者数量不能解决问题。

### F02 · P1：Redis 消费失败后缺少待处理消息恢复闭环

**证据**：`ApiRunnerEventConsumer.consume()` 只读 `ReadOffset.lastConsumed()`；持久化抛异常时不 ack，但下一轮继续只读新消息。`RedisRuntimeStreams` 仅提供 read/ack，不含 pending recovery；UI 事件消费者有相同模式。API Runner 任务循环也只读 `>`，未像 JMeter Runner 那样显式恢复本实例 pending 和 XAUTOCLAIM。

**影响**：一次瞬时数据库故障可能让已读取事件长期留在 PEL，任务状态/报告缺失；Runner 崩溃后的旧任务可能等待超时，而不是可靠恢复。首次创建组使用 `$`/latest 还需要确认是否允许跳过已有消息。

**证据强度**：高可信静态结论，未在本轮向 Redis/数据库注入故障。桌面客户端有 pending 重读适配，不能将桌面测试成功当成 Redis 逻辑已修复。

**建议与验收**：统一恢复接口，先恢复本消费者 pending，再按 idle 时间认领遗留记录；配合幂等、退避、最大尝试和死信。测试“数据库第一次写入失败，第二次恢复”和“Runner 读消息后退出，新实例恢复”。JMeter 的实现可作参考，但需防止长任务被错误重复认领。

### F03 · P1：生产网关默认上传限制与后端不一致

**证据**：`application.yml` 允许单文件 20MB、multipart 总体 21MB；`infra/nginx/production.conf` 与 Helm 的 `templates/web-configmap.yaml` 均未显式设置 `client_max_body_size`。

**影响**：按该 Nginx server 配置运行时会继承默认限制，通常为 1 MiB；超过该阈值的项目文件、较大导入/同步 JSON 可能先被网关以 413 拒绝，根本到不了后端校验。返回的也可能不是标准 API JSON 错误。

**证据强度**：配置级高可信风险，本轮未启动生产镜像上传复现；需检查镜像主配置/外围 Ingress 是否另有显式覆盖。

**建议与验收**：按各接口类型明确统一的上限，对同步 JSON 与 multipart 分别设计；同步更新 Compose 网关、Helm、Ingress 和错误展示。验证低于 1 MiB、2 MiB、接近业务上限及越界请求，不能只改后端。

### F04 · P2：CI 未完整运行 Runner 与传输层单元测试

**证据**：`.github/workflows/verify.yml` 的 Web job 执行根 `pnpm test`，该命令仅匹配 `src/**/*.test.ts`；后端 job 跑隔离服务端验收；release workflow 有 `desktop:test`，但未看到统一运行全部 Runner 单元测试的步骤。`services/runner-transport/` 无 package.json，不被 `pnpm -r --if-present test` 自动覆盖。

**影响**：协议、取消、消息恢复或 HTTP executor 回归可能在默认 PR 门禁之外。后端包含流测试不等于替代各 Node Runner 本身测试。

**建议与验收**：添加统一 `test:all` 或独立 CI job，显式运行工作区测试及 transport；用故意失败的临时测试确认每条门禁会失败，不要只检查 CI YAML 中出现 test 字样。

### F05 · P2：前端分包大，静态引入范围偏广

构建输出的 Monaco、charts 和 Arco 主 chunk 约为 2.28MB、1.13MB、1.08MB，均为压缩前构建体积，不是 gzip 传输或真实首屏耗时。`src/main.ts` 全局注册 Arco 组件及图标，`CodeEditor.vue` 静态加载 Monaco API/worker；`vite.config.ts` 已做路由懒加载和大库 manualChunks，但仍存在大块依赖。

建议按需导入组件/图标和 ECharts 图表，进入编辑器场景再加载 Monaco；基于真实登录、工作台、接口页进行网络与交互基线测试，记录 gzip、解析执行时间和长任务。不要用调高 warning 阈值掩盖问题，也不要把大 Monaco 异步块误称为必然全量首屏加载。

### F06 · P2：大组件及重复同步协议提高变更成本

- `AutomationPage.vue` 约 1,950 行，`ApiDesignPage.vue` 1,732 行，`ApiDebugPage.vue` 1,550 行，`ApiInterfaceWorkbench.vue` 1,258 行。
- Electron `main.cjs` 约 1,505 行，集中管理 IPC、工作区、迁移、执行组件和备份。
- `GrpcDebugService.java` 约 1,470 行、`ApiInterchangeService.java` 约 1,198 行。
- 前端约 7 个、Java 主源码约 10 个文件超过 500 行；桌面各模块有大量相似同步/IPC 实现。

行数不是缺陷本身，但这些文件承担多类异步状态和边界校验，容易出现旧请求覆盖新草稿、切换项目后写错上下文、重复修复等问题。当前已有不少针对 stale response 和 spinner 的测试，这是实际优点。

建议先抽离请求生命周期、编辑草稿、场景保存、产物面板和通用同步状态机；保留各业务自己的字段、引用和删除语义。不建议一次性全量重构或把所有差异压成巨型通用配置。

### F07 · P2：全局未知异常返回安全，但缺少对应服务端日志

`shared/api/ApiExceptionHandler.handleUnexpected` 对所有 Exception 返回固定 INTERNAL_ERROR，未记录异常或 requestId。避免向客户端泄漏堆栈是正确的，但不记录服务端原因会显著增加定位难度；并非每个异常都一定在下层另有日志。

建议在统一入口记录 requestId、异常类型和堆栈，敏感参数脱敏；建立 5xx 指标和日志关联测试。不要通过把异常原文返回客户端来“改善可观测性”。

### F08 · P2：API 脚本隔离的资源防护仍依赖外层环境

`script-executor.js` 使用 `vm.Script(...).runInContext({timeout:150})`、禁止动态代码生成及受限 pm API；没有每个脚本独立的进程级内存边界。词法允许列表/拒绝列表也不是稳定的权限模型。

建议增加独立 worker/进程、可终止生命周期和内存配额，保留无宿主对象跨界设计；测试合法脚本、大量分配、同步死循环和任务取消。此项是架构风险评估，本轮没有尝试沙箱逃逸或触发 OOM。

### F09 · P3：运行数据与构建版本治理需要收敛

本地 `.testhub/` 盘点约 44GB，release 约 3.4GB；其中包含历史验收目录和运行时。这些被 `.gitignore` 排除，不等于 Git 仓库体积为几十 GB，也不能据此认定全部可删。当前根包 0.1.0、桌面 0.2.0、后端 0.1.0-SNAPSHOT，预览构建仍主要依靠日期、tag、commit 和摘要区分。

建议建立明确的 owned-workspace 保留策略和磁盘配额，先确认无进程使用再清理。为所有包注入 commit、构建号、协议版本和迁移版本摘要；本轮没有清理用户历史目录或发布包。

## 七、测试、构建与环境说明

### 7.1 实际执行

```bash
pnpm lint
pnpm test
pnpm build
pnpm -r --if-present test
pnpm test:startup
node --test services/runner-transport/client.test.mjs
# 仅本条命令的环境，未修改系统默认 Java：
JAVA_HOME="$PWD/.testhub/lifecycle-20260918/jdk-21.0.12.1+1/Contents/Home" \
PATH="$PWD/.testhub/lifecycle-20260918/jdk-21.0.12.1+1/Contents/Home/bin:$PATH" \
node scripts/desktop/test-local-backend.mjs
```

Node 为 v24.14.1、pnpm 为 10.13.1。默认 `java -version` 是 Java 8，且 JAVA_HOME 未配置；项目需要 JDK 21。因此本轮仅选择项目已有的 Temurin JDK 21.0.12.1 执行隔离后端测试，没有安装依赖或更改系统配置。

后端外层命令超过工具单次等待时间后子进程仍继续运行。本轮后续读取该次独立日志，确认 **901 tests / 0 failures / 0 errors / BUILD SUCCESS**，并确认脚本输出已删除本轮临时数据目录。未伪造不存在的 shell exit code。

### 7.2 子项目测试明细

| 子项目 | 通过 | 跳过 | 失败 |
| --- | ---: | ---: | ---: |
| API Runner | 48 | 0 | 0 |
| JMeter Runner | 23 | 1 | 0 |
| Playwright Runner | 15 | 0 | 0 |
| Appium Runner | 12 | 0 | 0 |
| CLI | 11 | 0 | 0 |
| Electron 桌面 | 735 | 1 | 0 |

跳过的是依赖真实 JMeter 运行环境的测试和 Windows PowerShell 原生测试；不能将跳过项目计作本机成功验收。桌面测试通过也不等于最终 DMG/EXE 重新安装成功。

### 7.3 测试覆盖的强项与缺项

强项：现有用例对项目切换、CSRF 时序、旧异步响应、同步冲突、恢复中断、文件持久化和安装脚本有较细验证；测试并非只有渲染快照。

缺项：未生成 Istanbul/JaCoCo 覆盖率，因此不能给出覆盖率百分比；本轮未跑独立 MySQL/Redis/MinIO 全链路验收、真正多 Runner 故障注入、真实设备自动化、最终包签名与安装、压测基准和最新依赖漏洞扫描。

另观察到其他 Maven 测试进程同时使用 `services/platform-api/target`。本轮没有读取共享 surefire XML 后声称全部归本次运行，也未终止其他进程。建议未来每次验收隔离构建输出或使用独立 worktree，避免测试报告/JAR 归属竞争。

## 八、工程与交付判断

### 8.1 应保留的设计

- 模块化控制面与独立执行进程，不把用户脚本搬回 Spring Boot。
- 组织/项目范围、PAT scope、审计事件与明确的服务调用边界。
- 不可变运行快照、Outbox 与幂等同步操作。
- 本地备份、原子写入、恢复日志、恢复后暂停同步等安全默认行为。
- 隔离数据验收脚本和已存在的原生安装 CI，不对默认业务数据库跑破坏性测试。
- SBOM 与镜像漏洞门禁。现有 release-gate 主要处理 API/Web 镜像，仍应把执行引擎与桌面内置依赖纳入完整供应链清单。

### 8.2 不宜立即做的事

不建议现在把后端拆成十几个微服务、重写前端框架、删除全部本地数据、批量合并同步实现，或仅通过隐藏警告提高“健康分”。这些动作不直接解决当前最关键的取消、恢复与部署边界问题。

公开 README 明确将 Mac/Windows 定位为未发行者签名的预览版，移动端真实设备验收也有限定。这种表述应继续保留；本次测试不能把预览版升级为“生产全面验收”。

## 九、建议推进顺序与验收条件

| 阶段 | 目标 | 完成判据 |
| --- | --- | --- |
| 第一阶段：可靠性与部署边界 | 修正多 Runner 取消、pending 恢复及生产上传配置；补齐 Runner/transport CI | 双 Runner 取消确实停止执行；故障后事件最终落库；2MiB 与边界文件通过统一网关验证；所有子项目测试纳入 PR |
| 第二阶段：统一协议与可观测性 | 统一执行 owner/lease/attempt 与重试约定；补充错误日志和指标 | 同一 execution 重投不产生未授权重复副作用；requestId 能定位 5xx；可观测 PEL、Outbox 重试和失联 Runner |
| 第三阶段：渐进式维护优化 | 拆分最复杂页面/服务与主进程；按需依赖加载；契约样本库 | 原有回归保持通过；关键页面不再相互污染状态；真实加载与长任务指标改善 |
| 发布收口：环境矩阵与证据 | 独立 MySQL/Redis/MinIO、设备、原生安装与签名、恢复演练 | 每个发布物有唯一 build/commit/SHA256、验收环境、未覆盖项和恢复方案 |

每阶段应先补能稳定失败的测试，再改实现、复验、记录证据。涉及执行和同步的数据结构变更需同时评估 H2 与 MySQL 两套迁移及旧工作区恢复。

## 十、交付与最终结论

本次交付为本报告、结构化证据 JSON，以及项目内 `.testhub/project-analysis-20261026/` 的原始验证日志与探针结果。测试重建了忽略目录中的前端 dist 和后端 target；没有覆盖业务源码、修改系统配置、清理用户工作区或执行部署。

**最终结论：TestHub 已具备功能丰富、可构建且有大量回归测试支撑的综合测试平台基础；下一步最有价值的投入是把“多节点执行、故障恢复、生产网关与持续验证”形成闭环，再做大规模功能扩展与正式发布。**
