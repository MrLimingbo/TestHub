# 本地桌面生命周期与访客工作区

日期：2026-09-18。基线 `5d93330`，改动尚未提交。此阶段接续 [本地持久队列](desktop-standalone-progress-2026-09-18-broker.md)，不代表整个独立安装包已经交付。

## 实现

- Electron 新安装默认启动个人本地工作区；已有服务地址继续按原连接方式打开。可以在本地与账号/企业服务间切换，保留原服务地址，使用独立持久 Cookie 分区。本地数据不会因为切换服务而上传。
- `local-runtime.cjs` 负责启动内置 Java、同源生产前端及 API Runner。后端只监听 `127.0.0.1`，由系统分配端口；只有启动实例握手、健康检查和当前 Runner 的有效签名心跳均成功，才打开工作区。
- 工作区口令、数据加密及心跳密钥通过 Electron 43.2 的异步系统安全存储加密保存；不存在明文降级。缺失/损坏密钥时保留数据库并报错，不重新生成身份。目录权限为 0700，密钥文件为 0600（支持 POSIX 的系统）。临时会话令牌和 broker 根凭据每次启动更换，不暴露给页面。
- Flyway V4 单独保存本地拥有者。原生 `/internal/desktop/v1/session` 仅在 desktop 模式存在，校验回环地址、Host、无 Origin 和随机凭据；事务锁保证拥有者单例。该用户管理本机工作区，因此拥有本地系统设置权限；在线注册仍不授予系统管理员。
- 生产前端由本地后端托管，首页和 SPA 深层路径可正常刷新。页面静态资源可以在会话过期后加载，由可信主窗口请求原生层续期；业务 API 保持鉴权。桌面 CSP 允许现有组件所需样式、图片及 Worker，企业 CSP 不变。
- 退出先关闭 Runner，再关闭 Java。父进程管道关闭时，两类子进程自行退出；异常退出有次数限制的自动恢复，保留数据库和身份。
- 本地 Node 模式不加载 Redis 包；企业模式仍动态加载 Redis。四种 Runner 接入统一父进程退出信号，但当前桌面启动器只自动管理 API Runner。

## 运行时准备与安装包边界

`pnpm desktop:prepare:local` 要求先完成前端 build 和后端 package，再从 `JAVA_HOME` 的 Java 21 与当前 Node 22+ 生成本机架构的独立资源目录 `.testhub/desktop-runtime/<platform>-<arch>`。目录包含 JRE、Node 及其许可证、后端 JAR、生产前端、API Runner 和共享传输模块。保留先前运行时，不覆盖正在使用的文件。

macOS 准备过程检查 Java/Node 及 JRE 动态库的绝对依赖，拒绝系统库以外的外部路径。本轮发现 Homebrew Java 的字体库依赖 `/opt/homebrew`，已换用官方 Temurin 21.0.12.1+1；下载按官方 API 返回的 SHA-256 校验。来源与校验流程参见 [Adoptium 官方文档](https://adoptium.net/installation/ci-scripts)。仅改变当前项目测试运行时，没有更改系统 Java 或用户环境配置。

准备脚本产出的清单仍为 `distributionReady: false`。electron-builder 会检查架构及资源，并默认拒绝未经分发验收的运行时；内部测试可显式使用 `TESTHUB_DESKTOP_EXPERIMENTAL_PACKAGE=1`。Mac universal 需要 arm64 和 x64 资源，Windows 需要 win32-x64 资源。当前旧 Release CI 尚未纳入多架构运行时准备，因此会被门禁拦截，不会继续生成一个默认无法启动的空壳。

实际 Temurin 打包产物位于 `.testhub/lifecycle-20260918/package-temurin-final/mac-arm64/TestHub.app`；`packaged-runtime.log` 和 `packaged-runtime-result.json` 证明其包内运行组件真实启动并正常退出。构建使用已安装的官方 Electron 43.2.0，见 `package-temurin-final.log`。本轮只生成隔离目录内的未签名 Mac arm64 `.app` 验证产物；不是正式 DMG、不是已安装的应用，也不是 Windows 安装包。正式发行还需要完整模块验收、签名、公证、第三方组件分发清单及干净机器验证。

## 已执行验证

证据目录 `.testhub/lifecycle-20260918/`；以下测试有重叠，不相加为总通过率。

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| 前端全量 | 107/107，无失败/跳过 | `frontend-tests.log` |
| 后端 H2 desktop 全量 | 171/171，无失败/跳过 | `full-h2.log` |
| 后端原 MySQL/Redis 全量 | 171/171，无失败/跳过，使用独立 QA 容器 | `full-mysql.log` |
| 访客/鉴权/静态页面专项 | 4/4 | `guest-tests.log` |
| 桌面配置、密钥和打包门禁 | 13/13 | `desktop-tests.log` |
| 四类 Runner | 80/80，无跳过；包含已有真实 JMeter 测试 | `runner-tests.log` |
| Node 本地传输 | 6/6 | `transport-tests.log` |
| 前端生产构建、后端打包、相关 ESLint | 通过 | `build.log`、`package.log`、`lint.log` |

首次真实启动暴露首页空路径不能由资源解析器处理的问题（返回 500）。已通过明确的首页转发修复，并增加首页、深层路径和匿名 API 保护回归。会话过期刷新也已加入真实桌面测试。

真实进程测试涵盖：首次访客入口、生产首页及深层路径、正常退出所有自有进程、重启保留拥有者/项目、SIGKILL Java 后恢复、SIGKILL Runner 后恢复，以及 SIGKILL 父进程后 Java/Node 均退出。真实 Electron 测试使用新的私有用户目录和系统安全存储，不复用开发登录或旧安装数据。Temurin 运行时复测的进程闭环为 6/6（`runtime-temurin.log`、`runtime-result.json`）；真实 Electron 为 7/7（`electron-temurin.log`、`electron-result.json`），额外验证本地→在线→本地切换、Cookie 隔离以及在线页面无法续期本地会话，无页面脚本错误。在线侧使用临时回环服务夹具，仅证明模式切换，不替代注册同步验收。截图在 `output/playwright/testhub-local-workspace.png`。

负向运行时准备验证：Homebrew JDK 被明确拒绝，先前 Temurin 资源清单未被覆盖（`prepare-homebrew-rejected.log`）。

## 尚未完成

- 本地模块逐项完整验收。API Runner 目前默认不开启 HTTP 执行，需要接入用户可配置的目标授权；不能把本轮入口通过等同于全部业务功能已迁移。
- JMeter/浏览器按需下载、版本校验与进程管理；Appium 真机/模拟器验收。
- 本地备份、恢复、升级前备份，以及本地数据迁移到账号和冲突处理。此前在线同账号共享服务端数据的证据不代表离线同步完成。
- Mac Intel/Windows 原生组件准备、正式安装包签名及干净系统安装验证。未替换 `/Applications/TestHub.app`、未发布安装包、未提交或推送 Git。
