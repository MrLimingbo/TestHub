# TestHub 桌面安装包与发布

## 定位与共存边界

`apps/desktop` 封装 Vue 控制台和本地 Platform API，默认进入当前系统用户的本地工作区。安装即用改造已完成多个模块验收，原生多平台发行正在验证；当前预览包仍不是正式签名发行。完整进度及已验收范围见 [迁移计划](desktop-standalone-migration-plan.md)。浏览器、Vite、Docker Compose 和 Kubernetes Helm 的既有使用方式保留。

`apps/desktop/app` 的 Electron 主进程仅依赖内置 API，构建钩子禁止将根目录的前端和测试依赖错误地装入 app.asar。本地运行组件独立打包，包含 Java 21、Node、Platform API、静态前端、API／JMeter／Playwright／Appium Runner、必要依赖及许可。桌面本地模式使用 H2 和本地文件，不依赖用户预装 MySQL、Redis、MinIO、Java、Node 或 Docker。浏览器引擎、JMeter、Appium 与移动设备驱动等外部执行组件按各模块设置准备，不能据此承诺所有设备环境都已安装或验证。

本地数据目录、身份及加密设置按系统用户隔离，首启无需注册。用户可选连接自己的网页服务并注册、登录，将支持的资料迁移或建立持续同步绑定；两端项目和账号权限仍需满足要求。账号网页服务是可选在线能力，不是本地使用的前提。生产服务地址使用 HTTPS，本机开发允许 HTTP 回环地址；安装包不预置维护者的服务地址或凭据。

## 内网接口调试

- 桌面端的 HTTP、GraphQL 和 SOAP 调试通过受限 IPC 交给 Electron 主进程，从安装用户的电脑直接发出。接口中直接填写的地址和当前项目环境的非密钥变量都会在本地解析，因此可访问用户自己的 RFC1918/ULA、回环或内网 DNS 地址，也不受浏览器 CORS / Private Network Access 限制。
- 每个目标 Origin 在当前应用运行期间第一次访问时都要求用户确认；授权不会跨应用重启持久化。桌面网络请求只支持 HTTP(S)，限制方法、请求头、请求体、超时和响应大小，并使用 `redirect: manual`，不会被重定向到另一个内网目标。
- 项目环境密钥不会暴露给远端页面或桌面预加载脚本。如果 URL 或请求内容引用项目密钥，桌面端会明确拒绝本地解析；请把地址放在普通项目环境变量中，并在认证区域填写本次调试值。执行结果和脱敏请求地址会回写所连接 TestHub 项目的调试历史。
- 在线浏览器仍由 Platform API 发出 HTTP(S) 请求。系统管理员可在“系统设置 → 系统配置”启用 `api-debug.allow-configured-private-targets=true`，使接口地址或环境变量可使用任意 RFC1918/ULA 私网 IP 和端口；默认保持关闭。回环、链路本地、云元数据、组播和保留地址始终禁止；为防 DNS 重绑定，在线调试不放行解析到私网的域名，请在环境中配置解析后的私网 IP。需要更小授权面时，可改用 `api-debug.allowed-private-origins` 精确放行单个私网 IP Origin。

## 安全设计

- 远端 TestHub 页面没有 Node.js 集成或原始 Electron IPC 权限，并启用 context isolation、sandbox、web security 与权限请求拒绝策略。预加载脚本仅暴露结构化调试、工作区和模块操作，不暴露文件系统、Shell、任意 URL 导航或通用网络能力；主进程验证页面身份、发送方和各操作参数。
- 设置窗口使用 `testhub-desktop://` 自定义安全协议而非 `file://`；提供本地设置、备份、迁移及同步管理等受限 IPC 操作，每个原生工具在主进程验证发送方和工作区状态。
- 非本机 HTTP、带凭据、路径、查询参数或片段的服务地址均被拒绝；远端页面只能在所配置的同一 Origin 内导航或打开窗口。
- 桌面端不会更改服务端 CORS 或 Cookie 配置，因为加载的是既有的同源 Web 控制台；本地接口请求不经过页面 `fetch`。

## 本地开发与构建

安装依赖，构建前端和隔离后端，再准备当前平台架构的运行时：

```bash
npx pnpm@10.13.1 install --frozen-lockfile
npx pnpm@10.13.1 lint
npx pnpm@10.13.1 test
npx pnpm@10.13.1 build
npx pnpm@10.13.1 desktop:test
# JAVA_HOME 指向当前系统架构的独立 Java 21 JDK。
# 不要用正式数据源执行后端全量测试。
node scripts/desktop/test-local-backend.mjs
npx pnpm@10.13.1 --filter @testhub/desktop exec install-electron
npx pnpm@10.13.1 desktop:prepare:local
npx pnpm@10.13.1 desktop:verify:runtime
```

当前锁定 Electron 43 使用延迟下载，安装 npm 包或只读取 `package.json` 不会生成 `electron/dist`。准备运行时前须显式执行其锁定的 `install-electron` 命令，下载当前原生架构的二进制和 Chromium 许可；该安装器使用包内版本与校验摘要，不启动应用。见 [Electron 43 包入口](https://github.com/electron/electron/blob/v43.2.0/npm/index.js) 和 [安装器](https://github.com/electron/electron/blob/v43.2.0/npm/install.js)。

Mac 的 JDK／Node 不得依赖 Homebrew 等包外运行库。准备脚本保持 `distributionReady=false`；仅内部预览打包可通过 `TESTHUB_DESKTOP_EXPERIMENTAL_PACKAGE=1` 执行 `package:dir` 或对应安装器命令。前置检查验证平台、架构、组件与全树完整性；`afterPack` 再检查实际复制进包内的资源。正式构建不能靠修改该布尔值代替发行验收。

`desktop:dev` 默认启动本地工作区。连接既有开发网页时仍可设置 `TESTHUB_DESKTOP_SERVER_URL=http://127.0.0.1:5173`；这是可选服务模式。人工开发会打开应用窗口；自动桌面验收必须使用 [离屏 QA 引导](desktop-standalone-progress-2026-10-09-mobile-sync.md)。真正包验收用 `scripts/desktop/smoke-packaged.mjs --app <临时应用路径> --output <脱敏摘要路径>`，独立数据目录、隐藏窗口，退出后删除自有 QA 数据，不替换已安装应用。

默认 `desktop:package:mac` 仍生成 universal `release/desktop/TestHub.dmg`，必须预先准备 arm64 与 x64 两份验证后的运行时。原生 CI 分架构构建和验收，避免用单一架构的测试替代另一架构。Windows 安装器必须在原生 Windows x64 环境执行 `desktop:package:win`。构建产物和运行时不提交 Git。

安装器图标由 `apps/desktop/build/icon.svg` 生成。修改 SVG 后，在 macOS 执行 `scripts/desktop/generate-icons.sh`，并提交生成的 `icon.icns` 和 `icon.ico`，确保 Windows 原生构建不依赖 macOS 工具。

## GitHub 发布

`.github/workflows/desktop-release.yml` 先在 Ubuntu 执行前端／桌面回归和隔离 H2 后端构建，再传递共享应用文件到三个原生任务：Mac arm64、Intel Mac x64、Windows x64。每个任务准备自己的原生 Java／Node 运行时，检查许可与完整性，打包后实际后台启动程序，验证首启和重启持久化。

`workflow_dispatch` 上传 `TestHub-macOS-arm64-preview.dmg`、`TestHub-macOS-x64-preview.dmg`、`TestHub-Windows-x64-preview.exe` 和脱敏验收摘要，不创建 GitHub Release。预览文件不等同已签名／公证的发行包。`desktop-v*` 标签必须先通过正式分发门槛和签名配置检查，全部原生任务成功后才发布 `TestHub-macOS-arm64.dmg`、`TestHub-macOS-x64.dmg`、`TestHub-Windows-x64.exe`。当前运行时准备脚本不会自动解除正式门槛。

无 Developer ID 的 Mac 预览包仍须具有完整的本地资源封装。Electron Builder 26 未找到身份时会跳过签名；读取到 `Signature=adhoc` 元数据不代表整个应用的资源签名有效。`apps/desktop/build/mac-preview.cjs` 继承完整基础构建配置，显式设置 `mac.identity="-"`、`mac.hardenedRuntime=false`、`mac.notarize=false`，并通过 `mac.signIgnore=["/Contents/Resources/local-runtime/"]` 保留已校验的运行时原始字节；外层应用资源封装仍覆盖这些文件。该 ad-hoc 封装不验证发行者身份，不代替 Developer ID 或公证；正式标签签名流程保持原有要求。

预览统一使用以下入口；`--arm64` 可在原生 Intel Mac 改为 `--x64`，不得将跨架构构建当作原生验收：

```bash
TESTHUB_DESKTOP_EXPERIMENTAL_PACKAGE=1 CSC_IDENTITY_AUTO_DISCOVERY=false \
  npx pnpm@10.13.1 desktop:package:mac:preview --arm64
node scripts/desktop/verify-mac-preview.mjs --arch arm64 \
  --app release/desktop/mac-arm64/TestHub.app \
  --installer release/desktop/TestHub-macOS-arm64-preview.dmg \
  --output artifacts/preview-mac-arm64.json --revision <full-commit>
```

验证器先检查构建应用的 `codesign --verify --deep --strict`、应用标识、ad-hoc 类型、原生架构和全部运行组件字节清单，再以只读、隐藏方式挂载最终 DMG，对映像内应用重复检查，并核对主程序、ASAR 和运行时清单与构建应用一致。成功摘要只表示这些预览检查通过，不记录 Developer ID、公证或 Gatekeeper 通过。摘要文件必须不存在；失败会留下 `passed=false` 的脱敏结果，卸载映像失败时保留挂载点，禁止递归删除其内容。CI 在上传 Mac 预览安装器前执行此门槛；内部 Mac 目录包和旧版安装夹具也复用预览封装配置。最终包的隐藏启动、功能及安装生命周期仍须另外执行。2026-10-16 的本地 r2 修复已固化到此流程，本轮验收范围见 [Mac 标准打包与完整回归](desktop-standalone-progress-2026-10-19-mac-preview.md)。

正式标签发布必须配置以下 GitHub Actions Secrets；缺失时正式安装器构建会失败，不会发布未签名安装器：

| 平台 | Secrets |
| --- | --- |
| macOS | `MACOS_CSC_LINK`、`MACOS_CSC_KEY_PASSWORD`、`APPLE_ID`、`APPLE_APP_SPECIFIC_PASSWORD`、`APPLE_TEAM_ID` |
| Windows | `WINDOWS_CSC_LINK`、`WINDOWS_CSC_KEY_PASSWORD` |

macOS 标签构建启用 Electron Builder 公证；Windows 标签构建使用 Authenticode 证书。证书、私钥、Apple 凭据和签名文件只存在于 GitHub Secrets，不能写入仓库、`.env`、安装包日志或发布说明。

正式构建显式设置 `forceCodeSigning=true`，Mac 同时启用 Hardened Runtime。按 [electron-builder 26 配置说明](https://www.electron.build/v26/docs/configuration/)，默认不强制签名时，签名配置缺失可能只产生警告；凭据预检不能代替产物检查。

正式标签任务在上传发行安装器前执行 `scripts/desktop/verify-signed-artifacts.mjs`，摘要保留源码提交、安装器 SHA-256 和已执行的原生校验结果：

- Mac：`codesign --verify --deep --strict`、预期应用标识及 Apple Team ID、Developer ID Application、Hardened Runtime、安全时间戳、`stapler validate` 和应用 `spctl --assess` 均须通过；随后仅以只读、隐藏方式挂载最终 DMG，对映像内 TestHub.app 重做这些检查，并核对其主程序和 ASAR 与构建应用一致。命令依据 [Apple 公证故障排查](https://developer.apple.com/documentation/security/resolving-common-notarization-issues)。此检查覆盖 DMG 内的应用，不把 DMG 本身的 Gatekeeper 打开检查记为通过。
- Windows：通过固定 PowerShell 脚本和 `-LiteralPath` 检查构建的 TestHub.exe 及最终 NSIS EXE；两者都必须是 `Valid` 的嵌入式 Authenticode 签名，具备代码签名用途与时间戳，且签名证书 SHA-256 一致。依据 [Microsoft Get-AuthenticodeSignature](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.security/get-authenticodesignature)；Catalog 签名不能替代嵌入式签名。此步骤不提取 Windows 安装器内容，签名安装器的真实安装仍需另外验收。

签名检查通过后，还须使用原有隐藏／离屏引导重新启动**签名后的程序**，验证首启和持久性。任一检查失败均阻断该任务的发行安装器上传，发布任务也无法继续；不因设置了凭据、完成内部 smoke 或修改布尔值就自动解除分发门槛。摘要路径须为新文件，旧报告不会被覆盖或复用；仅输出脱敏的证书摘要和检查结论，不记录证书主体、私钥或原始系统工具输出。成功校验和真实签名／公证仍需实际证书及原生 CI 验收，单元测试中的替代系统命令不构成该证据。


发布前需在干净隔离系统安装 DMG 和 EXE，验证无需开发工具的本地首启、默认数据目录、重启、退出及升级保留数据；另外连接已批准的网页服务，验证登录、项目切换、内网接口授权、历史、附件和同步。真实安装、签名／公证、Gatekeeper／Authenticode 和移动设备执行均须单独留存结果；后台包 smoke 不能代替这些检查。归档安装器摘要、签名状态、源码提交和运行时清单，并完成发布安全扫描。

## 安装与升级自动验收

预览 CI 增加真实安装生命周期检查。共享任务从固定的上一已验收提交 `3932ef1` 构建隔离后端基线，同时归档该提交实际桌面壳，分别记录后端与壳归档的 SHA-256。各原生任务核对来源与摘要后构建旧版安装器，候选直接保留实际预览安装器，在独占安装位置执行首次安装、覆盖升级、卸载保留数据和重装恢复。旧版壳不替换为当前壳，因此可覆盖桌面壳自身的升级修复。两份后端摘要必须不同，升级后核对完整业务记录、身份、密文字节、运行版本标记和升级前快照。

各原生任务还运行备份、恢复和升级的 21 项文件系统回归：两种 Mac 各 21 项通过；Windows 按已有平台规则跳过依赖 `/etc/hosts` 的符号链接用例，其余 20 项通过，不能将该跳过项计入通过。升级快照和加密备份副本使用 `r+` 句柄执行 `sync()`，保持已有内容且保留刷新持久化；Windows 的 [FlushFileBuffers](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-flushfilebuffers) 要求文件句柄具备写权限，使用只读句柄会使升级或导出失败。

安装生命周期先检查当前候选在全新默认工作区的首次安装与两次启动；移除这份自有夹具后，再用固定旧壳创建升级数据，检查升级、卸载保留和重装。每个平台共 5 项检查、8 次成功启动，当前候选不自动重试。旧基线保持原样，失败不会替换成新壳或补记为成功。启动诊断只记录已知 loopback 状态／会话端点、传输类型、耗时和异常名，不记录请求头、密钥、查询参数或业务响应。

API 与可选 Runner 的就绪状态轮询保持原有 15 秒总预算及单次最多 3 秒请求限制；暂时的连接／请求超时可在总预算内继续检查。HTTP 拒绝、损坏响应与已停止进程立即失败，持续超时在原期限内停止。独立 HTTP 服务回归覆盖这些边界，不修改业务接口或密钥格式。

本机 Mac 验收使用 `/Applications` 下随机命名的独立子目录与独立 QA 应用名，数据目录由 Electron 的系统默认规则生成，不调用 `setPath('userData')` 或 `setPath('sessionData')`。它能验证系统目录中的安装与数据保留，不能替代正式应用名下的精确默认路径验收。`--clean-runner-defaults` 仅允许全新的 GitHub 托管原生 Runner 使用；它保留实际应用名及默认数据路径，遇到已有应用或数据、快捷方式、卸载注册项时拒绝继续。Windows 使用 assisted NSIS `/S`，核对默认安装目录、快捷方式目标和卸载注册项，拒绝静默安装自行启动未受离屏保护的应用。

准备好当前原生运行时后，可运行本机 Mac 隔离验收：

```bash
node scripts/desktop/build-install-baseline.mjs --output artifacts/install-baseline
node scripts/desktop/build-install-fixtures.mjs --baseline-backend artifacts/install-baseline/platform-api.jar --output .testhub/install-fixtures
node scripts/desktop/accept-installed.mjs --fixtures .testhub/install-fixtures/fixtures.json --output artifacts/installed-mac.json
```

输出目录必须不存在。验收创建的身份、业务数据、Keychain 项和应用副本在结束后清理；升级中间状态只在带唯一所有权标记的自有数据目录中保留。阶段结束还需移除基线 JAR、源码、测试安装器和原始日志，只保留脱敏摘要。现有用户安装程序和工作区不被接管。这些脚本使用未修改的 ASAR 和调试器离屏引导，仍不代表 Finder／Gatekeeper、正式签名或公证通过。

## 首次组件下载验收

原生 Mac 可对从最终 DMG 提取的独占应用副本执行 `node scripts/desktop/accept-first-downloads.mjs --app <owned-TestHub.app> --output <new-summary.json>`。脚本在空组件缓存下通过隐藏离屏引导运行，实际下载三种浏览器、FFmpeg 和 JMeter，验证只读目录、模拟离线／断流、界面取消和重试、报告附件、缓存完整性及拦截组件下载后的重启复用。成功下载保留原有 HTTPS、大小及摘要校验，不复制历史缓存，不修改 ASAR。

测试需要官方组件网络并仅使用自有回环执行目标。脚本删除自己创建的工作区、缓存、业务工件和测试 Keychain 项；调用方在结束后删除输入的临时应用副本，不得使用用户已安装应用或工作区。每次摘要路径必须为新文件，初始化即写入 `passed=false`；阶段结束只保留脱敏摘要与脚本。2026-10-19 Mac arm64 结果见 [首次下载验收](desktop-standalone-progress-2026-10-19-first-downloads.md)，不能用已有缓存的 smoke 替代该项验收。
