# 桌面 JMeter 按需安装与本地压测进度

日期：2026-09-20。整体改造仍未达到正式分发门槛；本阶段完成 Mac arm64 的 JMeter 按需安装与真实执行验证。

## 已实现

- 在本地工作区首次提交压测时准备 JMeter；页面展示下载进度，支持取消和网络失败后重试。准备完成、运行器签名心跳通过后才创建业务执行任务。取消、下载失败或期间切换计划不会提交旧任务。在线服务模式保留原流程。
- 原生主进程只接受当前本地工作区主页面的组件请求。下载地址、版本、校验值不能由页面传入。压测计划的目标必须已经在原生“本地执行设置”中授权，运行器收到任务后仍重新验证目标。
- 使用 Apache JMeter 5.6.3 官方 ZIP，固定 SHA-512，下载上限 120 MiB；不依赖用户安装解压软件或 Java。官方校验文件：[Apache JMeter SHA-512](https://downloads.apache.org/jmeter/binaries/apache-jmeter-5.6.3.zip.sha512)。本阶段验证了 SHA-512，没有宣称验证 PGP 签名。
- 由内置 Java 在独立进程中解压。拒绝路径越界、不同根目录、反斜杠、盘符、重复条目、CRC 不符、截断 ZIP、已有目标目录，限制条目数和展开大小。下载、解压、版本探测全部成功后才将临时目录原子改名为已安装组件。
- 组件缓存按文件校验，包含额外文件检查；有效缓存可离线复用。文件损坏时拒绝执行并保留原文件，避免覆盖工作区数据。当前尚无自动修复损坏组件的专用入口。
- JMeter 使用内置 Java 直接启动，路径支持空格和中文，工作目录使用系统路径分隔符，避免依赖 shell 或 Windows 批处理启动器。企业运行器仍支持原有 `JMETER_BIN` 方式。
- 本地 JMeter 不跟随 HTTP 重定向，防止已授权地址把流量转向未授权地址。企业模式原有重定向行为不变。
- 新增本地 JMeter 进程管理及经签名心跳确认的就绪检测，缓存存在时随本地工作区启动。工作区退出时先停止 JMeter，再停止 API 运行器和后台；等待报告归档后再关闭连接。退出发生在配置下载阶段时，也不能延迟启动新的 JVM。
- 给桌面 JMeter 加入轻量 Java 退出监护。即使 Node 运行器被 SIGKILL，独立 JVM 也会在父进程管道关闭后退出，避免后台持续压测。它只运行在隔离的 JMeter JVM，不加载到业务后台。

## 关键文件

| 文件 | 作用 |
| --- | --- |
| `apps/desktop/app/src/local-engines.cjs` | 官方固定版本下载、校验、安装与缓存 |
| `apps/desktop/app/src/local-runtime.cjs`、`main.cjs` | 原生调用边界、本地运行器启动、退出及恢复 |
| `src/pages/PerformancePage.vue`、`src/utils/desktopWorkspace.ts` | 首次提交、进度、取消及任务提交顺序 |
| `services/platform-api/src/main/java/io/testhub/platform/desktop/DesktopEngineArchiveTool.java` | 独立 Java 解压工具 |
| `services/platform-api/src/main/java/io/testhub/platform/desktop/DesktopEngineWatchdog.java` | JMeter 父进程退出监护 |
| `services/jmeter-runner/src/` | 内置 Java 启动、路径适配、重定向限制和退出等待 |
| `scripts/desktop/prepare-local-runtime.mjs` | 纳入 JMeter Node 运行器与退出监护 JAR |

## 已执行验证

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| 桌面和 JMeter Node 测试 | 44/44，0 跳过，包含真实 JMeter 原有启动器测试 | `node-tests.log` |
| 前端回归 | 110/110，19 个测试文件 | `frontend-tests.log` |
| 后端定向检查与打包 | 8/8，含解压边界与本地工作区鉴权 | `backend-build.log` |
| 前端生产构建、类型检查、相关 ESLint、差异检查 | 通过；保留原有大分块提示 | `frontend-build.log`、`typecheck.log`、`lint.log` |
| 真实 Electron 基础流程 | 7/7 | `result.json`、`electron-jmeter.log` |
| 真实异常退出流程 | 4/4 | `crash-result.json`、`electron-crash-retry.log` |

上述证据均位于 `.testhub/jmeter-desktop-20260920/`。后端本阶段只重跑定向 8 条，不能把此前 H2/MySQL 各 171 条记为本阶段重新执行。

基础流程验证了：未授权目标拒绝、首次真实下载取消无残留任务、重新下载并在原生页面提交压测、报告与工件下载校验、重定向不越过授权目标、任务取消后的 JVM 清理、执行中关闭应用、重启后在下载网络被阻断的情况下复用组件。主流程首次压测 44 个样本全部成功，12 个资源样本，JTL/LOG 的大小与 SHA-256 均通过校验；未授权接收端收到 0 个请求。

异常退出流程验证了：加入监护后正常压测仍成功、强制结束本应用的 Node 运行器后 JVM 退出且工作区恢复、强制结束本应用 Electron 后 JMeter 与所属 Node 退出、再次启动保留原用户和项目并完成压测。首次异常退出脚本因重复测试计划名被业务接口正确拒绝，改为唯一测试名称后 4 条全部执行通过；没有修改业务唯一性约束。

测试使用独立的中文及空格用户数据目录和本机临时 HTTP 服务，未对外部站点施加压测流量。原安装程序、开发服务、历史用户数据和未提交修改均保留。

## 交付边界与下一步

内部 Mac arm64 未签名 `.app` 位于 `.testhub/jmeter-desktop-20260920/package/mac-arm64/TestHub.app`，用于后续内部验证，不是正式 DMG/Windows 发布版本，未替换已安装应用。运行时继续标记 `distributionReady: false`。

JMeter 本地执行基础链路已完成；完整性能模块的所有负载模型、权限及跨页面业务验收仍需按总计划展开。后续还包括 Playwright 浏览器组件按需下载与生命周期、备份/恢复及升级快照、本地资产迁移到账号和冲突处理、各模块完整回归、Mac Intel/Windows 实机与签名分发。Windows 路径已做代码适配，但本阶段没有 Windows 实机结果。当前不能将整体“安装即用改造”或账号离线同步标记为完成。
