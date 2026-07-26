# 桌面安装包节点报告

## 已交付

- 新增 `apps/desktop` Electron 客户端。它连接既有 TestHub Web 服务，保留 Vite、Docker Compose 与 Helm 的浏览器访问方式，不复制控制面、数据库或 Runner。
- 首次启动显示受限的服务器连接页；生产环境只接受 HTTPS 根地址，本机开发可使用 `http://127.0.0.1:5173`。
- 远端 TestHub 页面未启用 Node.js 集成或 IPC 桥接，启用了 context isolation、sandbox、web security、权限拒绝、同源导航限制与窗口限制。设置页使用 `testhub-desktop://` 协议和发送方校验的最小 IPC。
- 新增 TestHub SVG、ICNS 与 ICO 图标；安装包不再使用 Electron 默认图标。
- 使用双 `package.json` 结构和构建钩子，阻止单仓库根目录的 Vue、图表和测试依赖进入安装包。最终应用 `app.asar` 仅包含桌面运行时代码，大小约 13 KB。
- 新增 GitHub Actions 原生构建与发布流程：macOS 产出通用 `TestHub.dmg`，Windows 产出 x64 NSIS `TestHub.exe`；`desktop-v*` 标签必须通过签名变量检查后才会创建 GitHub Release。

## 本机 macOS 候选包

- 位置：`release/desktop/TestHub.dmg`
- 架构：macOS universal（Apple Silicon 与 Intel）
- 大小：208 MB
- DMG 完整性：`hdiutil verify` 通过
- SHA-512：`4e966c28115f3695d42519163ec8e82dc2ca81c466124226deab541ff98d363bcdb16d4bbbc74ab259aedc2d8e7da7d9a5f84174b633f21d39c451ae33cd6da6`
- 已启动封装后的 `TestHub.app` 并连接本机 `http://127.0.0.1:5173` 供人工预览。

当前候选包没有本机 Developer ID 证书，因而未签名、未公证；不能作为正式对外发布件。标签发布工作流会要求 macOS 签名与公证 Secrets，缺失时失败，不会发布未签名 DMG。

## 验证记录

- `npx pnpm@10.13.1 desktop:test`：5 项桌面地址校验、同源限制和偏好持久化测试通过。
- `npx pnpm@10.13.1 lint`：通过。
- `npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过。Vitest 的 Monaco 上游 source map 警告和 Vite 大分包提示为既有警告，未影响结果。
- `npx pnpm@10.13.1 --filter @testhub/desktop package:dir`：通过；已检查 ASAR 不包含 `node_modules`。
- `npx pnpm@10.13.1 desktop:package:mac`：通过；已检查 DMG 包含 `TestHub.app`、`Applications` 链接和 TestHub 图标。
- `.github/workflows/desktop-release.yml`：YAML 解析与 `git diff --check` 通过。

## 发布前待办

1. 在 GitHub Actions 配置 `docs/desktop-packaging.md` 所列 macOS 与 Windows 签名 Secrets。
2. 在原生 Windows Runner 执行工作流，以获得并人工安装验证 `TestHub.exe`。
3. 在隔离环境完成 DMG/EXE 安装、HTTPS 登录、项目切换、附件下载及退出登录测试。
4. 经审核后创建并推送 `desktop-v0.1.0`（或新的正式版本）标签，工作流会发布两个签名后的安装包。
