# 本机 TestHub 更新安装记录

2026-10-16，已按用户授权替换为最新 Mac arm64 构建：`92bc93a861d2ca41ddc89a9ca9edbac111e14035`，预览包 r2。当前安装位置为 `/Applications/TestHub.app`，应用版本字段仍为 0.2.0，以构建提交和包哈希区分本次更新。/Applications 与用户 Applications 中仅发现这一份 TestHub 安装。

## 数据与回退保护

- 应用身份及原数据目录 `~/Library/Application Support/testhub-desktop-app` 保持一致，原有连接配置、缓存及登录资料文件原样保留；376 个原文件的字节和所有目录／链接均与备份清单一致。
- 私有备份保留于 `~/Library/Application Support/TestHub Upgrade Backups/<安装前备份目录>/`，父目录权限 0700，清单与安装记录为 0600；具体目录仅记录在本机私有安装记录中，未导出、修改系统钥匙串中的现有用户密钥。
- 原客户端配置使用既有网页服务连接。此次未访问或修改服务端业务数据，未打开或迁移真实用户工作区。真实用户首次启动后的服务连接及会话状态未代替用户操作验收。
- 新程序完整性及后台启动通过后才删除旧程序；临时回退应用、挂载目录和 QA 数据已清理，数据备份保留。

## 安装与验证

原预览包在替换前未通过 `codesign --verify --deep --strict`，提示资源封装缺失；原程序当时保持不变。r2 使用显式 ad-hoc 本地封装（identity=-、hardenedRuntime=false、notarize=false），排除对已校验本地运行时字节的重新签名；未使用 Developer ID 或 Apple 公证。

最终 DMG 校验、包内应用严格完整性、626 个运行时文件和安装后的程序均通过验证；应用 ASAR 与此前功能验收一致。修复后安装前和安装后各执行 3 项启动／持久化检查、各两次真实后台启动，窗口全部隐藏、离屏、不可聚焦，意外原生对话框为 0。没有关闭或修改系统安全设置，也没有把 ad-hoc 封装当作 Gatekeeper／公证认可。

安装包 SHA-256：`a220a7972776636ecade824b6280760a423baf198faaa3cb1e4b673282d48ccd`。安装应用 ASAR SHA-256：`c249b1da1d4ed96d875764834b67988fcb193f409a965fb5cfb774f22fe84f03`。

本地 r2 安装包位于 `release/desktop/preview-92bc93a-20261016-r2/TestHub-macOS-arm64-preview.dmg`，同目录的 `ACCEPTANCE.json` 保存脱敏验收摘要；这些构建产物不随源码文档提交到 GitHub。原下载路径同步为同一修复版，旧的不完整安装包已移除。既有 20 项功能和 5 项完整安装升级生命周期结果对应相同 ASAR／后端的前一包，r2 未重跑全部生命周期。原生 CI 预览配置仍需在后续恢复时同步并复验，Windows 仍按用户安排暂缓。
