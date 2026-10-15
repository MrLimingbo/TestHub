# 安全升级后的 main 提交与三架构 CI（已审核）

2026-10-15 用户审核通过 Spring Boot 4／Spring 7 本地安全升级，并授权提交 main、复验 verify 和三种架构的桌面 CI。本阶段已完成；以下证据均对应提交 `3332861f5487d98d512a8fa13288ff05bfd756ee`，与 GitHub main 一致。108 个已审核代码、测试和文档文件已提交；历史 output 和测试输出未纳入提交。

| 验收范围 | 实际结果 |
| --- | --- |
| [verify](https://github.com/MrLimingbo/TestHub/actions/runs/38668428599) | 前端 lint／测试／构建、隔离 MySQL／Redis／MinIO、发行安全检查三个 job 全部成功 |
| [desktop-release 预览](https://github.com/MrLimingbo/TestHub/actions/runs/38668443766) | 共享构建与 Mac arm64、Intel Mac x64、Windows x64 原生 job 全部成功，正式发布 job 跳过 |
| Windows 重试 | 首轮托管 Runner 在安装器生成期间失联，未保存该步骤日志；保留 GitHub 失败注解，同一提交仅重跑 Windows，最终结果以成功重试为准 |
| 流程测试 | 前端 305、桌面 545、隔离 H2 616、隔离 MySQL／Redis／S3 616 项通过，无失败／跳过 |
| 包内运行时 | darwin-arm64：624 文件；darwin-x64：624 文件；win32-x64：684 文件，Java／Node 原生校验、Electron 43.2.0、H2 V42 通过 |
| 实际应用包 | 三平台合计 9 项检查／6 次首启与重启通过，ASAR 未修改，身份与完整业务记录持久 |
| 后台与数据隔离 | 窗口始终隐藏、离屏且未聚焦；可见窗口违规和意外对话框均为 0；自有测试数据及进程已清理 |
| 发行镜像安全 | 两份镜像均通过原有可修复 HIGH／CRITICAL 检查；阈值、ignore-unfixed 规则与豁免配置未放宽 |
| 依赖证据 | CI SBOM 核对 Boot 4.0.8、Spring 7.0.9、Security 7.0.7、Jackson 2.22.3／3.1.7、Tomcat 11.0.27、H2 2.3.232；仅保留摘要与组件数 |

三平台包内后端 SHA-256 一致：`0a2fbdbe198ffa978e962c67345e815fb126dc1a8ac4860d184dc47462a68a53`。它对应本次 CI 构建，不与上一版本或本地独立构建的 JAR 摘要混同。

本阶段验收后删除 5 个自有临时构建／安装器／原始 SBOM artifact（1040097419 字节），保留 3 份脱敏平台摘要和本地复用脚本。未下载或安装本轮预览安装器，未启动本机可见窗口，未触碰用户安装程序、工作区和共享服务。详细脱敏证据见 `.testhub/security-native-ci-module-20261015/final-verification.json`。

本轮为内部预览验证；`distributionReady=false`，未完成正式签名／公证，未创建 tag 或正式 Release。默认目录与干净系统实际安装／升级、正式签名与公证、真实移动设备以及未纳入的同步范围继续分模块验收。本阶段已于 2026-10-15 获用户审核通过，继续下一模块“默认目录安装与升级验收”。
