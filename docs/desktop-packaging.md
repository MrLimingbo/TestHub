# TestHub 桌面安装包与发布

## 定位与共存边界

`apps/desktop` 是 TestHub 的桌面客户端，不是另一套控制面。`apps/desktop/app` 是只含运行时文件的封装目录，`apps/desktop` 根目录只存构建工具和测试。它将用户连接到用户本人或所属组织部署的 TestHub Web 服务，因此不会连接维护者的本机后台；浏览器访问、Vite 开发服务器、Docker Compose 私有化部署和 Kubernetes Helm 部署均保持不变。

桌面运行时只使用 Electron 和 Node 内置 API，不含业务 Node 依赖。构建钩子会显式禁止 Electron Builder 将单仓库根目录的 Vue、图表或测试依赖带入安装包。

客户端不打包 MySQL、Redis、MinIO、Platform API 或任何 Runner；这些企业服务仍由用户或组织按既有方式部署。这样桌面端和浏览器端共享同一登录、数据、权限、审计和 API 行为，避免复制业务逻辑或产生本地数据孤岛。这里的“独立运行”是指每个操作系统用户拥有独立的 Electron `userData`、Cookie 会话、服务地址配置和本地网络授权，安装用户之间不会共享本地状态；如果多人登录同一个 TestHub 账号，服务端数据仍按该账号的组织和项目权限共享。

首次打开桌面端时填写自己的 TestHub Web 根地址：生产环境必须使用 HTTPS，例如 `https://testhub.example.com`。本机开发可使用 `http://127.0.0.1:5173`。安装包不预置任何人的本机服务地址；地址只保存到当前系统用户的应用偏好目录，账号、密码和访问令牌都由 TestHub 服务端处理，不会被桌面设置保存。

## 内网接口调试

- 桌面端的 HTTP、GraphQL 和 SOAP 调试通过受限 IPC 交给 Electron 主进程，从安装用户的电脑直接发出。接口中直接填写的地址和当前项目环境的非密钥变量都会在本地解析，因此可访问用户自己的 RFC1918/ULA、回环或内网 DNS 地址，也不受浏览器 CORS / Private Network Access 限制。
- 每个目标 Origin 在当前应用运行期间第一次访问时都要求用户确认；授权不会跨应用重启持久化。桌面网络请求只支持 HTTP(S)，限制方法、请求头、请求体、超时和响应大小，并使用 `redirect: manual`，不会被重定向到另一个内网目标。
- 项目环境密钥不会暴露给远端页面或桌面预加载脚本。如果 URL 或请求内容引用项目密钥，桌面端会明确拒绝本地解析；请把地址放在普通项目环境变量中，并在认证区域填写本次调试值。执行结果和脱敏请求地址会回写所连接 TestHub 项目的调试历史。
- 在线浏览器仍由 Platform API 发出 HTTP(S) 请求。系统管理员可在“系统设置 → 系统配置”启用 `api-debug.allow-configured-private-targets=true`，使接口地址或环境变量可使用任意 RFC1918/ULA 私网 IP 和端口；默认保持关闭。回环、链路本地、云元数据、组播和保留地址始终禁止；为防 DNS 重绑定，在线调试不放行解析到私网的域名，请在环境中配置解析后的私网 IP。需要更小授权面时，可改用 `api-debug.allowed-private-origins` 精确放行单个私网 IP Origin。

## 安全设计

- 远端 TestHub 页面没有 Node.js 集成或原始 Electron IPC 权限，并启用 context isolation、sandbox、web security 与权限请求拒绝策略。预加载脚本只暴露一个结构化 HTTP 调试调用，不暴露文件系统、Shell、任意 URL 导航或通用网络能力；主进程再次校验发送页面 Origin、请求大小与请求头。
- 设置窗口使用 `testhub-desktop://` 自定义安全协议而非 `file://`；仅暴露“读取设置”和“保存已校验服务地址”两个受限 IPC 操作，并在主进程验证发送方。
- 非本机 HTTP、带凭据、路径、查询参数或片段的服务地址均被拒绝；远端页面只能在所配置的同一 Origin 内导航或打开窗口。
- 桌面端不会更改服务端 CORS 或 Cookie 配置，因为加载的是既有的同源 Web 控制台；本地接口请求不经过页面 `fetch`。

## 本地开发与构建

先照现有流程启动服务：

```bash
npx pnpm@10.13.1 dev
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./mvnw -pl services/platform-api spring-boot:run
```

然后另开终端启动桌面客户端：

```bash
TESTHUB_DESKTOP_SERVER_URL=http://127.0.0.1:5173 \
npx pnpm@10.13.1 desktop:dev
```

构建命令：

```bash
npx pnpm@10.13.1 desktop:test
npx pnpm@10.13.1 desktop:package:mac
npx pnpm@10.13.1 desktop:package:win
```

macOS 构建生成通用（Apple Silicon 和 Intel）`release/desktop/TestHub.dmg`。Windows 构建必须在原生 Windows 环境执行，生成 x64 NSIS 安装器 `release/desktop/TestHub.exe`。构建产物被 `.gitignore` 排除。

安装器图标由 `apps/desktop/build/icon.svg` 生成。修改 SVG 后，在 macOS 执行 `scripts/desktop/generate-icons.sh`，并提交生成的 `icon.icns` 和 `icon.ico`，确保 Windows 原生构建不依赖 macOS 工具。

## GitHub 发布

`.github/workflows/desktop-release.yml` 在 `desktop-v*` 标签上使用 macOS 和 Windows 原生 Runner 分别构建，随后将 `TestHub.dmg` 与 `TestHub.exe` 发布到同名 GitHub Release。手动触发工作流时只上传可下载的构建产物，不创建 Release。

正式标签发布必须配置以下 GitHub Actions Secrets；缺失时工作流会在构建前失败，不会发布未签名安装器：

| 平台 | Secrets |
| --- | --- |
| macOS | `MACOS_CSC_LINK`、`MACOS_CSC_KEY_PASSWORD`、`APPLE_ID`、`APPLE_APP_SPECIFIC_PASSWORD`、`APPLE_TEAM_ID` |
| Windows | `WINDOWS_CSC_LINK`、`WINDOWS_CSC_KEY_PASSWORD` |

macOS 标签构建启用 Electron Builder 公证；Windows 标签构建使用 Authenticode 证书。证书、私钥、Apple 凭据和签名文件只存在于 GitHub Secrets，不能写入仓库、`.env`、安装包日志或发布说明。

发布前请在隔离环境安装 DMG 和 EXE，连接已批准的 HTTPS TestHub 服务，验证登录、项目切换、直接内网 URL、环境 `BASE_URL`、目标授权提示、调试历史、附件下载和退出登录；随后归档安装器 SHA-512、签名/公证状态以及对应服务端版本。
