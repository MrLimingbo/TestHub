# 签名校验与正式发行阻断准备（已审核通过）

本模块于 2026-10-16 获用户审核通过，并授权提交 GitHub main、继续后续验收及配置仓库签名凭据。本文保留下列本地验收阶段的实际结果，后续提交／CI 结果单独记录。

2026-10-16，用户审核通过上一“三架构默认目录安装与升级”模块并要求继续。本模块完成原生签名检查的实现、拒绝边界验收和阶段清理；真实签名／公证尚未完成。当前 GitHub main 代码基线为 `893ca188b3ded1b0c96237565e8769e135ca52aa`；下述新增代码仍在本地，未提交、未触发 CI、未创建 tag 或正式 Release。上一模块的 CI 结果不作为本轮新代码已通过远程 CI 的证据。

## 完成的变更

- [正式发行工作流](../.github/workflows/desktop-release.yml)及 Mac 签名构建命令显式设置 `forceCodeSigning=true`，Mac 同时明确要求 Hardened Runtime；保持预览流程和原 `distributionReady` 分发门槛。
- [原生产物校验器](../scripts/desktop/verify-signed-artifacts.mjs)核对 Mac 的完整签名、应用标识、预期团队、Developer ID Application、Hardened Runtime、安全时间戳、架构、公证票据及应用 Gatekeeper 结果，再只读且隐藏地挂载最终 DMG，对映像内应用重新校验并核对主程序／ASAR；不会启动应用。挂载卸载失败时不递归删除挂载内容。
- [签名策略](../apps/desktop/build/signature-policy.cjs)与 [Windows 检查脚本](../scripts/desktop/inspect-authenticode.ps1)同时要求应用和 NSIS EXE 为系统判断 Valid 的嵌入式 Authenticode 签名、代码签名用途、存在时间戳且签名证书 SHA-256 一致；拒绝 Catalog 替代、非可信状态和不同证书。文件路径通过环境变量和 LiteralPath 传入固定脚本。
- 上传正式安装器前，签名检查与签名后的隐藏启动／持久化检查必须全部成功；检查失败会使 native 任务失败，发布任务也不能继续。摘要独占创建，旧摘要不会被覆盖或复用；保留文件摘要与源码提交，不写入 Team ID、证书主体、私钥或原始系统工具输出。
- 补齐 [16 项定向回归](../apps/desktop/test/signature-verification.test.cjs)，更新 [打包说明](desktop-packaging.md)并将上一模块审核结果写入迁移记录；业务页面、桌面业务源码、后端、数据格式和依赖版本均未改动。

## 实际验证

| 检查 | 本轮实际结果 |
| --- | --- |
| 定向回归 | 16 项通过，涵盖 Mac 元数据／团队不符、ad hoc／开发证书、运行时／时间戳、Windows 状态／证书用途／匹配、映像内容替换、文件中途变化、清理失败、旧摘要保护和日志脱敏；系统命令成功分支在这里使用替代函数，不能计为真实签名通过 |
| 桌面全量 | `npx pnpm@10.13.1 desktop:test`：572 项通过，失败／跳过均为 0 |
| lint | `npx pnpm@10.13.1 lint` 通过；Node 语法和 git diff 检查通过 |
| 工作流静态检查 | 官方 actionlint 1.7.12 通过；归档 SHA-256 与官方清单匹配，工具、归档、清单已删除 |
| Mac 原生拒绝检查 | 7 项通过；真实自有 Mach-O 夹具验证未签名拒绝、有效本地 ad hoc 签名仍被发行策略拒绝、系统 Gatekeeper 拒绝 ad hoc、签名后资源篡改拒绝，以及 Mac／Windows 两种凭据缺失预检；应用启动次数 0 |
| 原生拒绝检查边界 | 使用的 DMG 是占位输入，按预期在应用签名阶段即被拒绝，未将其作为映像成功验证；未运行真证书成功路径、stapler 成功路径或新 PowerShell 脚本的 Windows 原生执行 |
| 本机边界 | 原有 TestHub ASAR 摘要不变；当前本地运行时完整性通过并保留，旧运行时副本和自有残留进程为 0 |

实现依据 [electron-builder 26 强制签名配置](https://www.electron.build/v26/docs/configuration/)、[Apple 公证排查](https://developer.apple.com/documentation/security/resolving-common-notarization-issues)、[Microsoft Authenticode API](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.security/get-authenticodesignature)和 [X509 EKU API](https://learn.microsoft.com/en-us/dotnet/api/system.security.cryptography.x509certificates.x509enhancedkeyusageextension.enhancedkeyusages)，并核对项目锁定 26.15.3 的本地 schema 与签名工具代码。

## 清理和仍需外部配置的事项

本阶段自有应用夹具、失败摘要、临时工具和所有新测试临时目录已清除，仅保留脱敏结果与复用脚本；没有生成新的 TestHub 安装包、访问真实业务数据库、创建 Keychain 凭据或修改用户安装。`output/` 原有内容保留。详情见 `.testhub/signing-verification-module-20261016/final-verification.json`。

仓库 Secrets 仅按名称检查，所需七项仍全部缺失：`MACOS_CSC_LINK`、`MACOS_CSC_KEY_PASSWORD`、`APPLE_ID`、`APPLE_APP_SPECIFIC_PASSWORD`、`APPLE_TEAM_ID`、`WINDOWS_CSC_LINK`、`WINDOWS_CSC_KEY_PASSWORD`。证书与账号身份不能通过默认推荐选项产生，也不要把凭据发到聊天或写入仓库；需在仓库 Actions Secrets 安全配置后才能运行真正的签名与公证。

Mac 当前检查的是 DMG 内应用，DMG 本身的 Gatekeeper 打开评估尚未完成；Windows 当前检查两个文件签名，没有提取并安装签名 NSIS 的内容。真实签名后启动、签名安装器原生安装、交互式 Finder／Gatekeeper／SmartScreen 和正式发行均须另行完成，不能把本模块的实现／拒绝测试当作这些结果。`distributionReady=false` 保持不变。
