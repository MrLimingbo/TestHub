# 桌面安装包节点报告

## 已交付

- 新增 `apps/desktop` Electron 客户端。它连接既有 TestHub Web 服务，保留 Vite、Docker Compose 与 Helm 的浏览器访问方式，不复制控制面、数据库或 Runner。
- 首次启动显示受限的服务器连接页；生产环境只接受 HTTPS 根地址，本机开发可使用 `http://127.0.0.1:5173`。
- 远端 TestHub 页面未启用 Node.js 集成或通用 IPC，启用了 context isolation、sandbox、web security、权限拒绝、同源导航限制与窗口限制。设置页使用 `testhub-desktop://` 协议；远端页面仅获得经过发送方、协议、方法、请求头、大小与超时校验的 HTTP 调试桥。
- HTTP、GraphQL 和 SOAP 调试请求从安装用户的电脑发出，支持接口直填地址与项目环境普通变量中的内网地址。每个目标 Origin 在当前运行期间首次访问均需用户确认，不自动跟随重定向，且项目密钥不会暴露给桌面页面。
- 安装包不预置维护者后台地址。每个操作系统用户拥有独立的服务地址、Cookie 会话、Electron `userData` 和本地目标授权；客户端连接用户本人或所属组织部署的 TestHub 服务，不打包也不依赖维护者本机的 Platform API。
- 新增 TestHub SVG、ICNS 与 ICO 图标；安装包不再使用 Electron 默认图标。
- 使用双 `package.json` 结构和构建钩子，阻止单仓库根目录的 Vue、图表和测试依赖进入安装包。最终应用 `app.asar` 仅包含桌面运行时代码，大小约 13 KB。
- 新增 GitHub Actions 原生构建与发布流程：macOS 产出通用 `TestHub.dmg`，Windows 产出 x64 NSIS `TestHub.exe`；`desktop-v*` 标签必须通过签名变量检查后才会创建 GitHub Release。

## 本机 macOS 候选包

- 位置：`release/desktop/TestHub.dmg`
- 架构：macOS universal（Apple Silicon 与 Intel）
- 版本：0.2.0
- 大小：218,559,188 bytes（约 208 MB）
- DMG 完整性：`hdiutil verify` 通过
- SHA-512：`e9a68bcb903329d0b1ad790e84d8a2a44738f89735b7d80743f4312097488849e8fc7a6058c78a096ef92b664147fedbca5064a7300369ea6134e8124c714da2`
- `TestHub.app` 主程序包含 `x86_64` 与 `arm64`，已实际启动封装后的应用并在验证后退出。
- `app.asar` 仅包含桌面运行时代码和设置 UI，已确认包含受限的 `remote-preload.cjs` 且不包含 `node_modules`。

当前候选包没有本机 Developer ID 证书，因而未签名、未公证；不能作为正式对外发布件。标签发布工作流会要求 macOS 签名与公证 Secrets，缺失时失败，不会发布未签名 DMG。

## 验证记录

- `npx pnpm@10.13.1 desktop:test`：7 项桌面地址校验、任意私网/回环地址、本地请求边界、同源限制和偏好持久化测试通过。
- `src/test/desktopApiDebug.test.ts`：3 项接口直填地址、环境变量解析和项目密钥拒绝测试通过。
- `npx pnpm@10.13.1 lint`：通过。
- `npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过，共 12 个前端测试文件、58 项测试。Vitest 的 Monaco 上游 source map 警告和 Vite 大分包提示为既有警告，未影响结果。
- Platform API 全量测试：126 项通过；内网目标策略与传输层定向单元测试 9 项通过，接口调试流程测试 7 项通过。
- 使用与 GitHub CI 相同排序规则的全新 MySQL 数据库执行 60 个 Flyway 迁移并运行 Platform API 全量测试：126 项通过；验证首次启动会幂等创建项目默认 `test` 环境。
- 本地完整安全发布门禁通过：Platform API Debian 运行层、应用 JAR 和 Web Alpine 镜像的 HIGH/CRITICAL 漏洞均为 0，两个 CycloneDX SBOM 已成功生成。
- 当前在线环境已启用 `api-debug.allow-configured-private-targets=true`；TestHub Platform API 对 `http://172.27.15.153:8888/api/eventserver/authenticate` 的真实 POST 调试返回 HTTP 200，验证任意已配置私网 IP 链路可用。
- `npx pnpm@10.13.1 --filter @testhub/desktop package:dir`：通过；已检查 ASAR 不包含 `node_modules`。
- `npx pnpm@10.13.1 desktop:package:mac`：通过；已检查 DMG 包含 `TestHub.app`、`Applications` 链接和 TestHub 图标。
- `.github/workflows/desktop-release.yml`：YAML 解析与 `git diff --check` 通过。

## 发布前待办

1. 在 GitHub Actions 配置 `docs/desktop-packaging.md` 所列 macOS 与 Windows 签名 Secrets。
2. 在原生 Windows Runner 执行工作流，以获得并人工安装验证 0.2.0 `TestHub.exe`。
3. 在隔离环境完成 DMG/EXE 安装、HTTPS 登录、项目切换、附件下载及退出登录测试。
4. 经审核并配置签名凭据后创建并推送 `desktop-v0.2.0` 标签，工作流会发布两个签名后的安装包。
