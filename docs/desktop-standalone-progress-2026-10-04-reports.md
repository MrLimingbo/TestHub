# 报表统计：本地运行、跨项目计划迁移与后台调度

状态：本轮实现和验收已获用户审核通过，已进入任务中心模块。本轮只处理报表统计，等待本模块审核后再推进任务/设置/集成。移动端真机及环境准备仍待办。

## 本轮交付范围

- 保留质量概览、趋势、项目健康度、CSV 导出及定时报告。统计仍沿用既有功能测试、接口自动化和性能执行记录口径；Web UI/移动端执行未新增纳入汇总。统计从当前工作区业务数据计算，不复制源摘要数值来填充目标报表。
- 新增原生“质量报告资料迁移”：复制包含所选本地来源项目的定时计划名称、Cron、时区、统计周期和有序项目引用。来源项目映射到所选目标项目，其他项目按目标组织中唯一的项目编码匹配，要求所有项目具备报告写权限；缺少、无权限或多对一映射整批拒绝，不缩小项目范围。
- 生成新的计划标识及目标创建人，全部停用，不创建 Quartz 任务、执行、通知投递或历史记录。报告运行历史、通知模板和投递记录留在来源工作区。启用前核对目标业务数据、项目范围与通知配置。迁移是一次性复制，不是离线自动双向同步；账号网页与桌面读取同一账号服务。
- 冻结只读预览，10 分钟有效期，原生确认默认取消，账号/服务变化使预览失效；UUID 加快照 SHA-256 回执支持不重复的请求重试。组织锁保护跨来源项目导入，同名计划不覆盖，业务错误整批回滚。协议 V1、范围 `QUALITY_REPORT`、最大 32 MiB，每类最多 1000 条、每计划最多 50 个项目；拒绝未知字段、尾随 JSON 与无效引用，HTTP 流式上限 33 MiB，保留权限及 CSRF。
- 修复报表筛选迟到响应覆盖新结果、失败保留旧指标、无效日期仍显示旧结果、关闭记录窗口后迟到响应重新显示、定时计划保存失败提前关闭表单、CSV 下载文件名与请求范围不一致。默认日期按既有服务端 Asia/Shanghai 口径生成；停用计划不能从页面立即运行。
- 真实 Quartz 验收发现手动报告成功、后台触发失败：审计服务取得请求作用域代理，在没有 HTTP 请求的线程读取属性时抛出 IllegalStateException，已生成报告随后被标为失败。改为读取实际绑定的 servlet 请求属性，后台审计保留执行账号并使用 system 请求标识；正常 HTTP 审计保留请求信息。新增真实 Quartz 工作线程回归。

## 实现位置

- 后端：`reporting/QualityReportTransferService.java`、`QualityReportTransferController.java`、`audit/AuditService.java`，H2 V16 / MySQL V73 回执表；迁移接口位于 `/api/v1/projects/{projectId}/quality-report/transfer`，能力 GET、快照 GET、确认 POST。
- 桌面：`quality-report-transfer.cjs`、原生 HTML/JS、`main.cjs`、预加载桥和迁移主入口。
- 前端：`src/pages/ReportsPage.vue`；测试：`QualityReportTransferFlowTests.java`、`QualityReportScheduleFlowTests.java`、`apps/desktop/test/quality-report-transfer.test.cjs`、`src/test/reportsPage.test.ts`，既有传输边界及备份测试适配新路由/版本。

## 验证及边界

- 最新隔离 H2 后端全量 **319/319**，JAR 构建成功；报告迁移专项 6 项覆盖有序跨项目引用、生成新标识/创建人、停用且无任务/历史/通知、幂等与冲突、严格 JSON、权限及整批回滚；新增实际 Quartz 工作线程测试核对成功状态和审计账号。
- 最新全新专用 MySQL 8.4 / Redis 7.4 迁移专项 **6/6**；容器停止并保留测试数据，未连接默认业务数据库。
- 前端全量 **139/139**，报表专项 **8/8**，lint、类型检查及生产构建通过；桌面 **131/131**，新增原生迁移专项 5 项。既有前端分包体积和 Monaco source map 提示仍存在。
- V15 真实 Electron 升级前基线 **4/4**：真实功能批次两项执行一通过一失败、50% 通过率及严重风险、跨项目摘要和 CSV 公式转义、手动定时报告运行以及重启身份/计划/报告历史保持。该旧基线未通过自动 Cron，不能计为自动调度成功；旧运行时的实际 Cron 失败作为修复前复现保留。

- 最新源码真实 Electron + 独立 Chromium 流程 **11/11**：真实业务统计与 CSV、报表界面、实际 Cron 后台触发、停用和手动运行、重启持久化、只读迁移预览、取消不写入、缺少关联编码原子失败、关联项目有序映射及迁入停用/无历史/目标统计为空、网页编辑后显式启用并运行、返回本地核对原件和统计未改动。账号服务使用另一全新隔离 H2 工作区，没有向用户现有网页数据库写入验收夹具。

- 最终内部 Mac arm64 包提取后的完整流程 **11/11** 与 V15→V16 本地工作区升级 **4/4** 通过。升级仅操作旧基线克隆，保留本地身份、两项目、一条跨项目周报及其运行记录、两项功能用例、50% 通过率和严重风险；新迁移接口可读。升级前自动备份数据库 SHA-256 与旧数据库一致，原证据数据库未改动。
- 包内主进程、运行时管理、预加载桥、原生报告迁移及主入口、执行设置、后端 JAR、前端入口及 ReportsPage 资产共 **15 个文件**与当前源码/构建 SHA-256 一致。最终后端 SHA-256：`38285503f2b650834d86192998d05a77484bde0fad9de395f410cb26c0bb92c6`。已检查本地报表、账号网页与原生迁移完成截图。另用包内程序启动最终本地工作区克隆，核对最近一天趋势为 2 项已判定、1 项通过、1 项失败，等待图表的 500 ms 动画完成后补充稳定截图；这项检查没有改动原证据工作区。
- 真实 CSV 验收调用现有导出 HTTP 接口核对内容和公式转义；筛选期间文件名保持与请求一致由前端专项覆盖，没有把 HTTP 下载字节验证表述为原生系统保存对话框验收。

证据目录 `.testhub/reports-module-20261004/`，截图目录 `output/playwright/reports-module-20261004/`。

Mac arm64 内部包仍为 distributionReady=false，不代表签名、公证、Gatekeeper/干净机器或 Windows/Mac Intel 安装验收。未安装、发布、提交或推送本轮改动。

## 最终证据索引

- 后端全量：`backend-tests-quartz-final.log`；MySQL：`report-mysql-tests.log`、`mysql-quartz-final.log`。
- 前端：`frontend-tests.log`、`frontend-focused.log`、`lint.log`、`frontend-build.log`；桌面：`desktop-tests.log`。
- V15 原基线：`baseline-result.json`；源码：`flow-result.json`、`flow-final.log`。
- 内部包：`package-final/mac-arm64/TestHub.app`；构建：`package-build-quartz-final.log`；校验：`package-checksums.json`；复验：`package-verification-final.log`、`flow-packaged-result.json`、`upgrade-electron-packaged-result.json`。
- 早期自动 Quartz 失败见 `baseline-final.log`、`flow.log`；修复前只能声明手动报告成功。早期完整流程脚本误用窗口桥接方法、固定旧端口，已改用产品现有工作区菜单并识别新端口；流程失败不计入最终成功数。
- 稳定趋势补验：`chart-result.json`、`chart-evidence.log`；稳定截图：`output/playwright/reports-module-20261004/local-packaged-report-stable.png`。
