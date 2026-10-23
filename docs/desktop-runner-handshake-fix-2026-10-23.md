# Mac Runner 启动握手修复与验收（2026-10-23）

## 根因与修复

已确定并复现此前的启动 401 路径：

1. 本地就绪轮询读取 Runner 心跳，得到“不存在”或“已过期”。
2. Runner 在读取之后写入新的有效签名心跳。
3. 轮询根据先前的读取结果清理成员。桌面端把成员和心跳保存在同一行，原来的无条件清理会删除这条新心跳。
4. 紧接着的能力发现请求虽有正确签名，也因心跳不再可信而返回 401；同样的竞争会影响启动就绪判定。

在独立 H2 数据库中控制上述交错顺序，修复前测试稳定得到 `401 UNAUTHORIZED "Runner 内部请求认证失败"`；修复后同一测试通过，覆盖从未注册和旧心跳已过期两种情况。

新增内部注册表操作 `removeIfMissing`：桌面端在一条带过期条件的 SQL 中清理；Redis 端在同一个 Lua 操作中检查心跳是否存在并清理成员。API、JMeter、Web UI、移动端四类状态读取统一使用此操作。原有显式移除／停止操作保留。

签名、nonce、防重放、权限及 Runner 执行开关的校验规则均保留；启动仍使用原有 15 秒就绪总预算。没有通过忽略 401、自动重试候选启动或延长超时掩盖问题。没有数据库结构或数据格式变更。

## 验收

| 检查 | 结果 |
| --- | --- |
| 修复前确定性复现 | 1 项稳定复现同一路径 401 |
| 重点回归：注册表、桌面队列、签名与篡改／重放 | 13 项通过，零跳过 |
| 完整隔离 H2 后端回归及 JAR 构建 | 738 项通过，零失败、零跳过 |
| 完整隔离 MySQL／Redis／MinIO 回归 | 738 项通过，零失败、零跳过；包含真实 Redis 条件清理契约 |
| 桌面运行时与就绪检查回归 | 13 项通过，零跳过 |
| 前端类型检查与生产构建 | 通过；保留现有大体积 chunk 提示 |
| 原生运行时校验 | Java 21、Node 与 arm64 匹配，626 个文件完整性通过；`distributionReady=false` |
| 最终 DMG 解包后封装校验 | `codesign --verify --deep --strict` 通过 |
| 真实 Mac 包首次启动／重启 | 12 个全新工作区、6 次重启，18 次全部通过；无候选启动重试 |
| 每次启动的实际 Runner | 签名心跳 `VERIFIED`、签名能力发现成功、无 401；执行保持停用 |
| 重启数据保留 | 项目身份及测试参数保持，实际运行修复后的包内后端 |
| Web 注册、Mac 登录与四类资料双向同步 | 15 项通过，包括账号／邮箱登录、自动同步、冲突确认和重新登录后恢复 |
| ESLint、Node 语法、差异空白检查 | 通过 |

Mac 验收直接运行从最终 DMG 解出的应用，使用独立应用名、数据目录、会话目录和 QA 钥匙串；所有窗口在应用入口执行前改为隐藏、离屏且不可聚焦。12 个工作区的参数、数据库和日志仅属于此次测试。

MySQL 隔离基础设施的首次准备因 Go 依赖下载缓慢而取消，尚未执行测试；该次独占资源已清理。随后使用脚本已有的 `TESTHUB_QA_GOPROXY` 配置完成固定源码的 MinIO 构建和全部回归，源码摘要与 Go 模块校验保持启用。

## 文件与复验入口

- `services/platform-api/src/main/java/io/testhub/platform/shared/runtime/RunnerRegistry.java`：条件清理契约。
- `RedisRunnerRegistry`、`DesktopRuntimeBroker`：对应存储的原子条件清理。
- 四类 `RunnerStatusService`：使用条件清理。
- `DesktopRuntimeBrokerTests`：真实 H2 队列与签名握手交错复现。
- `RunnerRegistryFlowTests`：两种运行配置下的过期、续期、新写入保护和显式停止契约。
- `scripts/desktop/accept-runner-handshake.mjs`：固定 12 次首启、6 次重启的真实打包程序验收。
- 复用 `scripts/desktop/accept-web-mac-account-sync.mjs` 验证账号和同步。

原生包启动复验示例，输入必须是自有临时应用，输出必须为新文件：

```bash
node scripts/desktop/accept-runner-handshake.mjs \
  --app /absolute/path/to/owned/TestHub.app \
  --output .testhub/runner-handshake-native-new.json
```

## 数据保留、清理与交付

后端测试使用脚本创建的独立 H2 或新 Compose 项目的 MySQL／Redis／MinIO，原有业务数据库不作为 Maven 夹具的数据源。网页联调只使用新注册的私人账号和项目，依次清理文件对象、登录会话及账号关联记录。失败的基础设施准备也清理其独占 Compose 项目。

独占数据库、容器、网络、数据卷、测试账号、文件与遗留会话、QA 钥匙串、临时应用、原始日志及本轮生成的旧运行时副本均已清理。网页联调结束后，原 MySQL 全表摘要、Redis 会话主体与配置、MinIO 文件库存、真实 Mac 用户目录均保持一致；活动会话可正常刷新访问时间。原 Web 服务和注册功能继续可用，原安装程序的摘要及完整封装校验通过。原有工作区、启动前备份与 `output/` 保留。

仅保留可复用脚本、文档、脱敏摘要和用于审核的最终 Mac 预览安装包。摘要位于：

- `.testhub/runner-handshake-repair-20261023.json`：完整修复、回归与清理结果。
- `.testhub/runner-handshake-native-20261023.json`：18 次真实打包程序的启动记录。
- `.testhub/runner-fix-web-mac-sync-20261023.json`：15 项账号与同步复验、原数据保留及清理。

候选包位于 `release/desktop/runner-handshake-fix-20261023/TestHub-macOS-arm64-preview.dmg`，同目录包含 `SHA256SUMS.txt`。该包没有 Apple Developer ID 签名或公证，仅使用预览构建的临时封装签名。送审时保留现有安装和用户工作区，尚未自动替换或提交 GitHub。

## 审核后的安装与发布

用户审核通过后，修复代码、验收脚本及文档已提交至 `d091fcd285808237427c4fd00ae4fa7454b1c968`。该提交的 [GitHub CI](https://github.com/MrLimingbo/TestHub/actions/runs/39890442096) 中 Web、后端和发布门禁全部通过；后端 738 项测试零失败、零错误、零跳过，并确认隔离容器、网络、数据卷与临时文件已清理。

本机 `/Applications/TestHub.app` 已替换为同一修复包。替换前完成私有用户目录备份，替换后逐文件、目录核对原有数据保持一致；没有打开或迁移真实用户工作区。安装后的两次真实启动与持久化检查，以及另外 12 次全新工作区启动、6 次重启握手检查全部通过，所有窗口隐藏、离屏且不可聚焦。成功后移除了旧程序临时副本、测试目录和 QA 钥匙串，原有数据备份保留。

该已验收的原始 DMG 以 [Mac arm64 2026-10-23 预览版](https://github.com/MrLimingbo/TestHub/releases/tag/mac-v0.2.0-preview.20261023) 发布，未重新打包或修改二进制。应用版本字段仍为 `0.2.0`，使用标签、产品提交号和 SHA-256 区分构建。Release 提供安装包、`SHA256SUMS.txt`、`BUILD-INFO.json`、`VERIFICATION.json` 与脱敏 `ACCEPTANCE.json`；未做 Developer ID 签名、Apple 公证或干净系统 Gatekeeper 首开验收，`distributionReady=false`。
