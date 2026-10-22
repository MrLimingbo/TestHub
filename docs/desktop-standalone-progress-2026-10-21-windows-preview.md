# Windows x64 未签名预览包验收与发布

## 范围

在 Windows 原生 GitHub 托管 Runner 构建 NSIS 安装器，检查实际安装后的本地工作区、跨模块执行和旧版升级；通过后发布 GitHub Pre-release 并在 README 增加下载入口。全程使用隐藏／离屏窗口和隔离测试数据，不操作用户桌面或已有工作区。

本轮不办理发行者签名，不宣称通过 SmartScreen、Smart App Control 或企业应用策略。Intel Mac 原生验收、移动端真实设备执行继续暂缓。Windows arm64 原生包不在本轮范围。

## 构建与验收链路

- 手动 `desktop-release` 的 `target=windows` 只调度 Windows x64；正式 `desktop-v*` 标签仍要求三平台和签名门槛。
- Ubuntu 执行前端、桌面、隔离 H2 后端回归，构建当前应用及固定旧版 `3932ef1` 升级基线。
- Windows 准备原生 Electron、Node、Java 与各 Runner，校验完整性和许可后生成真实安装器。
- 预览检查核对未签名状态、PE x64、运行时清单及安装器摘要；随后在默认路径静默安装，检查快捷方式、注册项和本地首启。
- 已安装程序运行跨模块真实流程，Chromium 与 JMeter 使用本轮新下载的隔离缓存。此回归不替代三种浏览器首次下载、中断和离线的专项验收。
- 从旧版安装器升级，检查业务记录、身份和加密数据保留，再检查卸载保留及重装恢复。安装后的 ASAR、后端和安装器摘要必须与预览清单一致。
- 脚本清理自有应用、工作区、数据库、组件缓存、截图和进程；共享升级夹具在 CI 的 `always()` 步骤删除。最终只保留安装器、校验文件与脱敏验收摘要。

## 本轮修复

Windows PowerShell 5.1 从 PowerShell 7 → Node 继承模块目录后，签名检查无法加载 `Microsoft.PowerShell.Security`。验收脚本现在只给该子进程设置系统 PowerShell 5.1 自带模块目录，保留父进程及系统环境，并增加真实 Windows 模块加载回归。依据 [Microsoft PSModulePath 文档](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_psmodulepath)。Mac 旧入口保留，通过两次实际离屏启动复验。

安全扫描恢复后发现服务端 Debian 基础镜像的 Expat 已有修复版本。Java 21 distroless 基础镜像固定为 `sha256:27ea9d33a1329dfa96a787e447f5caaf62ff2e21e496ca501fcb56ba3cedd5af`，其 amd64 包含 `libexpat1 2.9.0-1~deb13u1`。依据 [Debian 安全跟踪](https://security-tracker.debian.org/tracker/CVE-2026-66046)。未添加漏洞豁免，HIGH／CRITICAL 门禁与 `--ignore-unfixed` 规则保持不变；漏洞库使用 Trivy 官方支持的镜像顺序回退解决 ECR 限流。

## 验收结果

构建源码为 `3f2d041d7b1196b4d3fd5b9d56ebc1b60e42ae5c`，原生环境为 GitHub 托管 Windows Server 2025 x64（`windows-2025-vs2026`）。2026-10-21 完成构建及验收，2026-10-22 发布预览版。

| 项目 | 结果 |
| --- | --- |
| [Windows 构建与原生验收](https://github.com/MrLimingbo/TestHub/actions/runs/39579841914) | application、native 全部通过；正式签名 publish 按预览规则跳过 |
| [verify](https://github.com/MrLimingbo/TestHub/actions/runs/39579839352) | 前端、MySQL／Redis／MinIO、供应链门禁全部通过 |
| 前端 / H2 后端 / MySQL 后端 | 376 / 733 / 733 项通过；旧版 H2 基线另有 616 项通过 |
| Ubuntu 桌面回归 | 733 项通过、3 项平台专属跳过（2 项 Mac、1 项 Windows） |
| Windows 文件系统与 PowerShell 回归 | 22 项通过，1 项依赖 `/etc/hosts` 的符号链接用例按已有平台规则跳过 |
| 构建目录包启动 | 3 项检查、2 次真实启动通过；未修改 ASAR |
| 已安装程序跨模块执行 | 20 项检查通过；9 条真实执行记录，未授权目标收到 0 次请求 |
| 默认目录安装生命周期 | 5 项检查、8 次启动通过；覆盖新装、固定旧版升级、卸载保留及重装恢复 |
| 发布包一致性 | 最终 EXE 与验收候选摘要一致；新装、升级和重装后的 ASAR／后端与预览清单一致 |
| 后台与清理 | 可见窗口违规、意外原生弹窗均为 0；所有被观察窗口隐藏、不聚焦、离屏；自有数据与进程清理通过 |
| 供应链 | 两份镜像未检出本次 HIGH／CRITICAL 且有修复版本的漏洞；没有新增豁免 |

跨模块流程实际覆盖功能批次、Mock、接口执行、JMeter、Chromium、失败与取消、JTL／日志和截图／视频／Trace 产物、任务筛选和跳转、质量报告、重启保留、账号网页访问及组织权限隔离。Chromium 和 JMeter 使用本轮空缓存下载并完成完整性检查；Windows 三浏览器的完整首次下载故障矩阵、通知外发、移动真机不在此结果内。

本地签名编排与 PowerShell 环境回归另有 17 项通过、1 项 Windows 原生检查跳过；Windows CI 已实际执行后者。Mac 两次真实离屏启动的兼容性复验通过，不替代新的 Mac 全量验收。

## 发布文件

[GitHub Release：Windows x64 未签名预览版](https://github.com/MrLimingbo/TestHub/releases/tag/windows-v0.2.0-preview.20261022)

- `TestHub-Windows-x64-preview.exe`：271,627,807 字节，SHA-256 为 `24e9f502675036874566099e7c53bca0371bd26d17b111e681ae027c1cca7726`。
- `BUILD-INFO.json`：源码、原生环境、应用和运行时摘要，数据库版本为 V50。
- `VERIFICATION.json`：上述 CI、真实安装、功能、后台约束和清理的脱敏结果。
- `SHA256SUMS.txt`：三个交付文件的完整性摘要。

上传后核对四个附件的 GitHub 服务端 SHA-256 与大小，均与本地一致；再以无登录、无授权头的请求完整下载 271,627,807 字节的 EXE，SHA-256 与已验收文件一致，确认公众可下载。README 提供独立 Windows 下载入口并保留 Mac 入口；预览保留 `publisherSigned=false`、`smartScreenAssessed=false`、`distributionReady=false`。本结果不是 Windows 10／11 全部实机、Windows arm64、SmartScreen、Smart App Control 或企业策略通过的声明。

本轮测试使用独立 CI 环境；完成后移除测试安装、默认 QA 数据、升级基线、数据库、缓存、截图、临时下载和原始日志，仅保留最终发布文件、可复用脚本及脱敏摘要。用户 Mac 安装、原有工作区与未跟踪的 `output/` 均未改变。
