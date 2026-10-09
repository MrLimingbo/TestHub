# 桌面浏览器按需组件与本地 Web UI 执行

日期：2026-09-20。Mac arm64 本阶段验证完成；整体安装即用改造仍在进行，尚未达到正式分发门槛。

## 本阶段改动

- 本地工作区首次执行 Web UI 场景时准备 Chromium、Firefox 或 WebKit，包含录屏所需的 FFmpeg；Windows 清单另含 winldd。沿用 Playwright 1.61.0，未升级业务运行器依赖。
- 组件版本、官方 HTTPS 下载地址、长度及 SHA-256 固定在生成清单中，页面只能传入浏览器枚举及已授权的目标 Origin。校验值由维护工具从官方 HTTPS 下载文件计算，**不是声称厂商发布了这些校验值**。支持备用官方地址、下载时限、取消及失败重试。
- 安装使用临时目录和原子改名，逐文件记录校验信息，拒绝额外文件、被修改的可执行文件和越界链接。保留 macOS 浏览器框架的相对链接；只忽略 Playwright 首次启动生成的空 `DEPENDENCIES_VALIDATED` 标记。安装后实际启动浏览器、截图并录制视频，成功后才标记就绪。
- 组件支持离线复用。浏览器完整、录屏组件下载失败时，下次重试复用已完成的浏览器文件。损坏缓存拒绝执行并保留原数据，目前没有专用的自动修复入口。
- 手动执行、重试、立即运行计划及启用计划均接入桌面准备流程。取消准备、关闭执行对话框、切换项目或期间场景/浏览器配置版本变化，不会继续提交旧请求。
- 本地 Playwright 运行器通过专用父子进程 IPC 请求组件；只在拿到并验证不可变运行清单后请求一个固定浏览器枚举。API 提交和 Quartz 触发也能首次准备浏览器，不依赖页面按钮。企业及自行管理的运行器继续使用既有浏览器安装方式。
- 原生“本地执行设置”中的精确 Origin 同时传给本地后台及运行器。修复了原生已授权、后台仍要求企业系统配置而拒绝创建浏览器项目的问题；本地允许已明确授权的 IP 地址和域名，并与原生 URL 解析器统一默认端口。企业配置来源及禁止 IP 字面量的原规则保留。
- 关闭运行器时取消准备/运行中的任务，等待终态事件和附件归档后关闭连接；浏览器启动过程中发生关闭，也不能留下稍后才启动的浏览器。
- 真实验收还发现必填请求正文缺失被归类为 HTTP 500。共享错误处理现在将必填正文缺失、畸形 JSON、字段类型无法转换统一返回 HTTP 400 `VALIDATION_FAILED`，不把解析细节或输入值暴露给客户端；有效请求路径保持原行为。

实现主要位于 `apps/desktop/app/src/local-browsers.cjs`、`local-runtime.cjs`、`main.cjs`、`src/pages/UiAutomationPage.vue`、`services/playwright-runner/src/`、`UiAutomationTargetPolicy.java` 和 `ApiExceptionHandler.java`。打包脚本纳入独立 Playwright Node 运行器、固定版本的 Playwright/core 模块和组件校验清单。维护工具为 `scripts/desktop/refresh-browser-catalog.mjs`，使用 `--download` 重新生成清单；不得手工更新其中的校验值。

## 验证及证据

证据目录：`.testhub/browser-desktop-20260920/`。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 桌面与 Playwright Node 测试 | 44/44，0 跳过 | `tests-final.log` |
| JMeter 回归测试 | 24/24，0 跳过，包含真实旧启动器 | `jmeter-regression.log` |
| 前端完整测试 | 114/114，19 文件 | `frontend-tests.log` |
| 后端定向测试与打包 | 14/14，含目标授权和请求正文负向检查 | `backend-tests.log` |
| H2 下原有 Web UI 业务流程 | 2/2，独立数据库，无 Redis/MinIO 服务 | `h2-ui-flow.log` |
| 后端 H2 完整回归 | 181/181，0 失败、0 错误、0 跳过；包含上述后端定向用例，不重复累加 | `backend-h2-all.log` |
| 组件解压、真实启动、截图、录屏及离线缓存 | 三种浏览器通过 | `components-result.json` |
| 真实 Electron 主流程 | 11 个检查通过 | `electron-result.json`、`electron-symlinks-retry.log` |
| 应用正常/异常退出与 JMeter 实际回归 | 4 个检查通过 | `electron-crash-result.json`、`electron-app-crash-retry.log` |
| 内部包代码一致性及实际运行 | ASAR、运行器及 JAR 校验；内置运行时的三种浏览器任务和工件校验通过 | `package-checksums.json`、`packaged-runtime-result.json`、`package-smoke-final.log` |
| ESLint、类型检查、生产构建与差异检查 | 通过；保留既有大分块提示 | `lint-final.log`、`types.log`、`build.log` |

Electron 主流程实际完成：取消首次网络下载且无任务入队；重新下载 Chromium 和 FFmpeg 并由页面提交；Firefox 的 API 首次准备；WebKit 的 Quartz 首次准备；三种浏览器均完成导航、断言、截图，TRACE/VIDEO/SCREENSHOT 下载内容的大小及 SHA-256 一致；重定向到未授权目标被阻止，接收端收到 0 次请求；WebKit 取消后子进程退出；分别 SIGKILL 三种浏览器所属 Node 后子进程退出、同一工作区恢复；重启后阻断组件下载地址仍能执行。

Firefox/WebKit 在该 Electron 主流程中复用了维护工具先前从官方地址下载并真实解压验证过的组件文件，未复制就绪标记；父进程重新验证并实际启动后才能运行。不能将它们计为本次 Electron 又各下载了一遍。独立组件测试和打包运行测试也明确使用缓存。

额外退出流程验证了：正常退出应用会将 Firefox 任务置为取消并保存追踪/录像；SIGKILL Electron 后活动 WebKit、所属 Java/Node/浏览器进程均退出；重启保留本地身份并成功执行 Chromium；同一运行包在阻断组件下载地址后仍完成真实 JMeter 压测，48 个样本全部成功。

过程中除上述两个产品问题外，也修正了验收脚本中的 UTF-8 响应头、Arco 对话框定位、复制框架链接、请求正文及 CSRF Cookie 收集等测试前置问题。早期失败日志保留，表格只引用最终通过的证据；没有放宽组件、CSRF 或业务参数校验来使脚本通过。本轮重新执行了 H2 完整回归；MySQL 本轮未重跑，没有把以往结果计入本阶段。

## 内部包与剩余工作

内部未签名 Mac arm64 包：`.testhub/browser-desktop-20260920/package/mac-arm64/TestHub.app`，版本仍为内部 0.2.0，运行时 `distributionReady: false`。打包检查提取本包 ASAR 中的启动器，实际启动本包 Java/Node/Playwright 依赖执行三种浏览器任务；这不是干净系统 GUI 安装测试，也不是正式 DMG。未替换已安装应用，未提交/推送代码或发布 Release。

剩余工作包括备份/恢复及升级快照、用户选择后的本地资产迁移与账号离线同步/冲突处理、各业务模块的完整功能验收、移动设备依赖、Mac Intel/Windows 实机和签名分发。清单包含 macOS Intel/Apple Silicon 与 Windows x64 官方组件，不代表其他平台已通过安装或执行验收。
