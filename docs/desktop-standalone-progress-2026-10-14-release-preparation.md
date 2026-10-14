# 桌面发行构建准备与真实 Mac 包首启（已审核）

本模块已于 2026-10-14 获用户审核通过，继续下一模块“原生 Intel Mac／Windows CI 构建验收”。下述结果是本模块实际完成范围，后续远程验证单独记录。

上一模块“跨模块同步、重启与备份恢复联合验收”已于 2026-10-14 获用户审核通过。本模块补齐安装包构建链，并验证真正的 Mac arm64 程序及 DMG 复制后的首启，保留现有业务行为。未提交／推送 Git、触发远程 CI、发布 Release 或替换用户已安装程序。

发行工作流此前只安装 pnpm 并构建 Electron 壳，没有构建最新前端、后端 JAR，也没有准备各目标架构本地运行时。现分为共享应用构建和原生矩阵：共享任务执行前端 lint／测试／构建、桌面回归、隔离 H2 后端 JAR 构建；三个原生任务分别准备 Mac arm64、Mac x64 和 Windows x64 的 Java 21／Node 24、所有 Runner、前端及后端，检查架构、文件清单和许可，创建真正的 unpacked 程序并后台检查首启／持久化。手动工作流产物明确命名为 preview；tag 打包须先通过原有正式分发门槛，再核对签名配置，全部任务成功后才可进入发布任务。本轮没有将 `distributionReady` 改为 TRUE，内部 smoke 成功也不会自动解除门槛。

Mac 两种原生 runner 按 [GitHub 官方 runner 列表](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)选择 `macos-15` 与 `macos-15-intel`；Windows 使用 x64 runner。保留现有默认 universal 打包命令，CI 分架构产出避免用单一架构环境假装完成双架构运行。Windows 的隔离后端包装器使用 PowerShell 调用自身 mvnw.cmd，路径通过环境变量传递，避免把工作目录插入 shell 代码；这一分支尚未在 Windows 执行。

本地运行时现在记录完整 626 个文件的大小与 SHA-256、后端摘要、最新 H2 迁移版本和 Electron 版本。准备后实际启动内置 Java／Node 检查平台／架构；Java 模块、Node、Playwright／core、Electron／Chromium 的许可文本均在清单中。打包前和复制完成后分别检查全树，拒绝遗漏或新增文件、内容改变、包外／循环链接、错误架构、漏带 Appium 和陈旧摘要。只改分发标记也不能让缺少完整性清单的旧运行时通过正式检查；内部旧测试清单保留原兼容方式。

新增的真实包验收不解开 app.asar 再交给开发 Electron 执行，而是直接启动包内 TestHub 原生可执行文件。[Electron 官方调试机制](https://www.electronjs.org/docs/latest/tutorial/debugging-main-process)允许在 main 入口执行前暂停；本轮启动器在未修改的 app.asar 入口临时应用离屏、不可聚焦、禁止激活及独立测试目录，再继续原代码。`app.isPackaged=true`，资源和原生可执行路径实际来自当前包，默认安装目录及用户工作区未触碰。源码和 ASAR 未被替换或重写；临时应用名称用于独立 QA Keychain 项，退出后只删除自有项。这个调试环境证明包内启动路径，不等同未附加调试器的 Finder／Gatekeeper 或默认目录安装通过。

在移除开发工具目录的 PATH 下，真正的 Mac arm64 `.app` 自动进入 V42 本地工作区，新建需求后退出并重新启动，原身份、业务 ID 与完整记录保留。两次启动均离屏、不可见、未聚焦，意外原生对话框为 0。另生成 Mac arm64 preview DMG 并由 hdiutil 校验，以只读／noautoopen／nobrowse 方式挂载，复制到阶段内带中文和空格的路径后卸载镜像，再用复制程序执行同样的首启和持久化检查；未打开 Finder 或写入真正的 Applications 目录。镜像、复制后运行时和原 ASAR 内容一致。

验证完成：桌面全量 545 项通过（新增完整性与包资源 10 项），ESLint、git diff --check、经官方 SHA-256 校验的 actionlint 1.7.12 工作流静态检查通过；内置 Java／Node 平台与架构、许可和 626 个文件校验通过。真正的 `.app` 与 DMG 复制后的程序各 3 项检查通过，共四次真实程序启动；ASAR 未修改，窗口违规与意外弹窗为 0。165 个包内原生源文件和全部运行时与当前构建一致。后端仍为已审核 H2 V42／MySQL V99，摘要 `c7601a86f05f7fcf96fedcdc8e8d3a8e6ff37ec70bffc38848379710d556413f`，本轮未重复执行无业务改动的后端／前端全量回归。

本轮仅修改发行工作流、构建／验证脚本、打包 hooks 与相关测试／说明，未改业务接口、页面或数据库。许可文本存在和完整性检查不代表全部第三方分发条件已完成法律审查。实际原生 Intel Mac／Windows CI、干净系统真实安装／默认目录、Mac Developer ID／公证／Gatekeeper、Windows 签名及正式发行仍待后续模块，本轮不能据此宣称跨平台发行完成。

阶段自有测试工作区／数据库／身份、QA Keychain、镜像挂载、日志、临时 DMG／包／复制目录、下载的验证工具及无进程使用的旧运行时已清理，保留脱敏摘要和复用脚本，见 `.testhub/release-preparation-module-20261014/final-verification.json`。当前运行时、用户数据、共享服务和此前阶段材料保留。本模块已审核，后续远程验收单独记录。
