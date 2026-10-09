# 跨模块真实执行统一列表

状态：2026-10-05 插件/运维获用户审核通过。用户明确选择本轮同时增加各测试模块真实执行统一列表。本轮实现、隔离回归、最终源码与内部包真实流程和旧工作区升级复验已完成，停在本模块审核点。

## 交付范围

- 新增项目只读接口 `GET /api/v1/projects/{projectId}/tasks/executions`，通过各模块的应用服务汇集功能测试批次、接口自动化、性能、Web UI、移动端执行。每个模块最多最近 100 条，合计最多 500 条；按创建时间倒序，同时间以模块及执行标识稳定排序。功能测试原有全部批次接口保持，新查询独立在数据库内限定 100 条。
- 仅投影执行标识、项目、模块、快照名称、原始状态和时间；功能批次另外显示总数、通过、失败、阻塞数。无清单、用户凭据、状态详细错误或伪造的百分比进度。功能批次 `COMPLETED` 表示已结束，保留失败数量，不改为成功。
- 任务中心新增真实记录区域，名称、模块、原始状态独立组合筛选，10 条一页，模块与标识组合行键避免不同执行表同标识碰撞。筛选在每模块最近 100 条内进行，不声明完整历史总数。可前往原模块查看报告、下载工件和取消执行，本轮没有在汇总区域增加第二套执行/取消协议。
- 前台每 5 秒读取，隐藏页面暂停，不重叠请求；项目切换清空旧行与筛选，读取序号和卸载保护丢弃迟到响应。失败清空旧执行记录并显示可重试错误，暂停轮询直到显式重试。后台刷新等待期间保留当前已取得记录。
- 保留原手工登记、任务版本、迁入来源摘要、编辑和取消；明确单独显示。手工修改登记不会创建真实执行，也不计入统一记录或质量统计。接口调试历史继续由原接口调试入口管理，本轮未将一次性调试请求包装为 Runner 任务。
- 复用原项目读取权限，普通读者可读自己可访问的项目，其他组织无权读取。接口只读，读取不写入任务、审计或执行队列。验证发现不支持的 HTTP 方法曾被共享异常处理误报为 500，新增明确 405 共享错误及 Allow 响应头；CSRF 和原写权限保持。
- 不新增数据库迁移，H2 V19、MySQL V76 保持。质量统计继续原功能用例/API/性能执行口径，没有因为新增列表自动纳入 Web UI、移动端或手工登记。

## 实现位置

| 文件 | 内容 |
| --- | --- |
| `execution/UnifiedExecution.java`、`UnifiedExecutionService.java`、`UnifiedExecutionController.java` | 项目只读投影和应用服务汇集 |
| `functional/FunctionalTestExecutionService.java`、`FunctionalTestExecutionRepository.java` | 独立最近 100 条批次查询及稳定同时间排序 |
| `shared/api/ApiExceptionHandler.java` | 不支持方法的 405 错误及 Allow |
| `src/components/UnifiedExecutionsPanel.vue`、`src/pages/TasksPage.vue` | 筛选、状态、时间、结果、轮询、保护及独立手工登记区域 |
| `UnifiedExecutionFlowTests.java`、`src/test/unifiedExecutionsPanel.test.ts` | 后端 6 项、前端 9 项新增专项 |

## 验证

- 隔离 H2 后端全量 **365/365**，JAR 构建成功；最终新专项 **6/6** 再次通过。使用仓库隔离脚本，不对默认用户数据库运行全量验收。
- 专用 MySQL **8.4.10** / Redis **7.4.0** 新数据目录、随机端口和凭据专项 **6/6**，完整 MySQL V1–V76，filesystem 对象存储，SERVER profile；测试后停止自有两进程。桌面不打包 Redis，该证据不代表外部 MinIO/企业执行 Runner 部署验收。
- 后端覆盖五源和相同标识、原始全状态、快照名称保持、更新后新状态、功能失败/阻塞、不含手工登记和秘密字段、最近 100 条及同时间排序、匿名/跨组织/读者权限、无队列或审计副作用、只读方法边界。
- 前端全量 **210/210**；最终统一列表与手工登记专项 **17/17**，桌面原回归 **146/146**。lint、类型检查和生产构建通过。保留原 Monaco source map 和大分包提示。
- 最终真实跨模块流程源码 **20/20**，最终内部 Mac arm64 包同一流程 **20/20**。截图后修复较窄桌面时表格撑宽页面及执行编号断行，最终完整流程加入表格横向滚动不溢出面板的检查，源码与包均重新通过。包含实际本地 Mock、API Runner、JMeter、Chromium，成功/失败/取消，实际报告及工件 SHA-256、名单筛选和原模块导航、质量汇总、停用通知、项目切换、重启、独立账号双端及跨组织权限。
- 旧插件/运维 V19 内部包在其 QA 工作区克隆中记录一通过一失败的功能批次与独立手工登记；最终源码和包升级各 **4/4**。新列表读取原执行标识、状态和数量，保留原拥有者、资产、登记、环境/参数、通知、签名信任、脱敏适配器和质量统计。仍为 V19，构建升级前自动快照 SHA-256 等于旧数据库，原 QA 数据库与旧夹具种子均未改动。

真实执行只访问自有本机目标；网关将三个 Runner 的 HTTP 请求转发给同一工作区实际 Mock 响应。JMeter/浏览器使用此前已校验组件缓存，本轮不新增首用下载成功声明。移动端五源投影由 H2/MySQL 专项的结构化数据库夹具覆盖，未使用真机/模拟器执行。

账号端使用另一全新 H2 desktop profile 服务及实际注册账号，独立 Chromium 与桌面读取同一项目；MySQL SERVER 行为证据来自专项。未把本地身份当作在线身份，未将本地执行历史自动上传，未向用户既有开发服务或已安装程序写入 QA 数据。停用通知的报告生成没有投递，本轮未重新验收 Webhook 网络传输。

## 证据及过程

证据 `.testhub/unified-executions-module-20261005/`；截图 `output/playwright/unified-executions-module-20261005/`。

- 后端 `backend-full.log`、`backend-targeted-final-isolated.log`；MySQL `mysql-harness.log`、`unified-mysql-tests.log`、`native-mysql-result.json`。
- 前端 `frontend-full-final.log`、`frontend-layout-final.log`、`lint-layout-final.log`、`build-layout-final.log`；桌面 `desktop.log`。
- 源码 `flow-source-final.log` / `flow-source-result.json`、`upgrade-source-verified.log` / `upgrade-source-result.json`。
- 内部包 `package-build.log`、`package-verification.log`、`package-checksums.json`、`flow-packaged-result.json`、`upgrade-packaged-result.json`。

最初测试夹具的 WAIT 配置键、必填计划说明/用例目录、权限异常类型已依据实际契约修正；MySQL 初轮缺少企业浏览器目标授权，显式配置自有 QA 数据库后通过。只读 POST 原 500 是产品错误，修复 405 后回归通过。早期真实脚本使用 Mock 不支持的请求体匹配值、API 步骤中不支持的结构化 headers、运维投影字段路径及版本显示前缀，修正脚本后重新验证，没有放宽业务校验。失败日志保留，不计为通过。

## 后续

最终包内主进程/桥/备份实现、后端 JAR、index 及全部生产静态资源共 **83** 个文件 SHA-256 与当前源码/构建一致。提取 app.asar，使用同版本开发 Electron 启动包内 Java、后端和页面；已核对源码与包的本机、账号、列表底部和旧数据升级截图。最终后端 SHA-256：`f169bb3313083e907dbea95fedf4119bf7a9ccb4bbeb8ec72388d69ab414e877`。该证据不代表未签名原生 app 的系统安装与 Gatekeeper 验收。

本模块已获用户审核通过，后续开始任务登记离线双向同步模块。移动端真机/模拟器、其他资产的离线自动双向同步、干净机器首启、Mac Intel/Windows、签名公证与第三方组件分发核对、正式安装包仍待办。内部 Mac arm64 包为 `distributionReady=false`，不代表 Gatekeeper 或干净机器安装验收；本轮未安装、发布、提交或推送。
