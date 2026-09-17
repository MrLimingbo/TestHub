# Mock 服务节点测试与修复报告

测试开始：2026-09-09；中断后续验并完成：2026-09-14（Asia/Shanghai）。

本轮完成 R4 Mock 服务的测试、缺陷修复与回归。按约定在本节点结束暂停，下一节点为性能测试。本报告保留既有测试计划、修复前失败记录和最终检查口径；未提交或推送代码。

## 最终结果

- 真实 HTTP 检查 **51/51 通过**：正向 23、反向/边界 28，通过率 100%。9 月 14 日已重新执行。
- 浏览器验收 **9/9 通过**：表单校验、创建、公开调用、复制反馈、分享状态及只读页面。
- 代码回归 **212/212 通过**：前端 80、后端全量 132；Mock 专项 8 项包含在后端总数内，不重复计数。
- ESLint、vue-tsc、Vite 构建和 git diff --check 通过。
- 修复 **10 类缺陷**；最终已执行检查无失败、无阻塞。

## 测试计划

| 编号 | 范围 | 正向验证 | 反向或边界验证 |
|---|---|---|---|
| R4-SRV | 服务管理 | 创建、列表、公开地址、停用及重新启用 | 空名称、无权限管理、跨项目访问、缺失 enabled |
| R4-RULE | 规则配置 | 方法/路径/状态码/响应头/正文、根路径、优先级、同优先级创建顺序 | 无匹配、错误方法、非法路径/状态/头、无效 JSON 子集 |
| R4-MATCH | 条件匹配 | 查询参数、重复查询值、头名称不区分大小写、EXACT、嵌套 JSON 子集、数组完全相等 | 条件缺失、数组不等、非法 JSON、表单正文不可当作查询参数 |
| R4-BODY | HTTP 真实边界 | text/plain 与表单正文原样匹配、512KB 内请求 | 超 512KB 的 ASCII/UTF-8 正文、HEAD/OPTIONS 语义 |
| R4-SMART | 智能示例 | 发布接口回退、路径参数、手动规则优先、当前版本 | 未发布接口不回退、关闭开关及服务后 404 |
| R4-SHARE | 只读分享 | 有效期、永不过期、匿名读取、安全字段摘要、复制地址 | 无效有效期、格式错误、撤销、过期、项目隔离 |
| R4-UI | 页面交互 | 新建服务/规则/分享、状态反馈、真实分享页 | 必填/格式错误保留弹窗、切换服务竞态、空服务清理、过期链接数量与状态 |


## 缺陷闭环

| 编号 | 复现与影响 | 修复及回归 |
|---|---|---|
| R4-001 | 表单正文 mode=preview 不能命中 EXACT，反而能满足 URL 查询条件；PUT/PATCH/DELETE 还被前置表单过滤器消耗正文 | 只解析原始 URL query；MockAwareFormContentFilter 对 Mock 运行时跳过表单解析。真实 Tomcat 验证 POST/PUT/PATCH/DELETE 原始正文、参数隔离和重复查询值 |
| R4-002 | 配置 /订单，访问对应百分号编码地址返回 404 | 对运行时路径进行 UTF-8 解码，路径中的 + 仍保留。真实请求与集成测试通过 |
| R4-003 | /Orders 误命中 /orders；数据库不区分大小写的排序规则破坏精确匹配 | 对数据库候选规则再执行 Java 字符串精确路径比较。大小写反例返回 404 |
| R4-004 | 超过 512KB 的中文正文被截断并按字符数判断，ANY 规则仍返回成功 | 在解码前按实际读取字节数拒绝超限；ASCII 恰好上限通过，ASCII/UTF-8 超限均 404 |
| R4-005 | POST 启停接口传 {} 返回成功并意外停用服务 | enabled 改为必须提供的 Boolean，两个开关均验证 @NotNull；缺失/null 返回 400 且原状态保留 |
| R4-006 | 服务、规则、分享弹窗在必填或 JSON 错误后被默认关闭 | 使用 on-before-ok 与 ok-loading，只在成功后关闭。组件与真实浏览器验证保留输入 |
| R4-007 | 快速切换服务/分享链接后，旧异步结果可能覆盖新选中内容 | 管理页读取绑定项目和服务，切换时清空旧详情；分享页使用请求序号忽略迟到响应。组件回归覆盖延迟返回 |
| R4-008 | 已过期未撤销分享仍显示“有效”，并计入有效链接数量 | 统一计算有效/过期/撤销状态，定时刷新时钟，过期链接不显示复制操作。真实过期数据、列表截图与公开 404 验证一致 |
| R4-009 | 从 EXACT/JSON 模式切回 ANY 后隐藏的旧正文仍提交，后端拒绝创建 | ANY 模式提交空正文条件，保留其他模式输入。组件回归验证请求载荷 |
| R4-010 | 新建服务后智能示例列表强制为空，直到再次选择服务才更新 | 新建后调用统一详情加载，立即读取项目已发布示例。组件回归与浏览器显示 3 个示例验证通过 |

## HTTP 检查明细

请求直接发送到本地真实 Spring Boot/MySQL 服务；公开运行时与分享请求使用不带登录 Cookie 的客户端。反向用例按预期 400/401/404 等判定通过，不将非 2xx 一律视为失败。

| 检查 | 类型 | 结果 | 实际证据 |
|---|---|---|---|
| empty-server-name | 反向/边界 | 通过 | HTTP 400 |
| server-create-list | 正向 | 通过 | {"serverId":"01M22FFS42NWWW30ER4ZGKDYJW","enabled":true} |
| manual-default | 正向 | 通过 | HTTP 201 |
| nested-subset-and-header-case | 正向 | 通过 | HTTP 202 |
| repeat-query-values | 正向 | 通过 | HTTP 202 |
| missing-query-fallback | 反向/边界 | 通过 | HTTP 201 |
| array-not-equal-fallback | 反向/边界 | 通过 | HTTP 201 |
| malformed-json-fallback | 反向/边界 | 通过 | HTTP 201 |
| exact-text-body | 正向 | 通过 | HTTP 200 |
| exact-form-body | 正向 | 通过 | HTTP 200 |
| form-is-not-query | 反向/边界 | 通过 | HTTP 404 |
| query-and-form-body | 正向 | 通过 | HTTP 200 |
| wrong-method | 反向/边界 | 通过 | HTTP 404 |
| case-sensitive-path | 反向/边界 | 通过 | HTTP 404 |
| no-rule | 反向/边界 | 通过 | HTTP 404 |
| root-route | 正向 | 通过 | HTTP 200 |
| unicode-route | 正向 | 通过 | HTTP 200 |
| same-priority-order | 正向 | 通过 | HTTP 200 |
| head-empty-body | 正向 | 通过 | HTTP 200 |
| head-header-preserved | 正向 | 通过 | {"emptyBody":true,"header":"head"} |
| options-rule | 正向 | 通过 | HTTP 204 |
| body-limit-exact-ascii | 正向 | 通过 | HTTP 200 |
| body-limit-over-ascii | 反向/边界 | 通过 | HTTP 404 |
| body-limit-over-utf8 | 反向/边界 | 通过 | HTTP 404 |
| invalid-path | 反向/边界 | 通过 | HTTP 400 |
| invalid-status | 反向/边界 | 通过 | HTTP 400 |
| protected-response-header | 反向/边界 | 通过 | HTTP 400 |
| crlf-header | 反向/边界 | 通过 | HTTP 400 |
| duplicate-header-condition | 反向/边界 | 通过 | HTTP 400 |
| invalid-json-subset | 反向/边界 | 通过 | HTTP 400 |
| anonymous-management | 反向/边界 | 通过 | HTTP 401 |
| cross-project-server | 反向/边界 | 通过 | HTTP 400 |
| missing-enabled-rejected | 反向/边界 | 通过 | HTTP 400 |
| server-disabled | 反向/边界 | 通过 | HTTP 404 |
| server-reenabled | 正向 | 通过 | HTTP 201 |
| share-expiry-config | 正向 | 通过 | {"hasExpiry":true,"neverExpires":true} |
| share-anonymous | 正向 | 通过 | HTTP 200 |
| share-safe-fields | 正向 | 通过 | {"fields":["shareKey","shareName","serviceName","publicKey","serviceEnabled","smartExampleEnabled","expiresAt","rules"],"ruleCount":11} |
| share-invalid-expiry | 反向/边界 | 通过 | HTTP 400 |
| share-invalid-key | 反向/边界 | 通过 | HTTP 404 |
| share-revoked | 反向/边界 | 通过 | HTTP 404 |
| draft-not-smart-source | 反向/边界 | 通过 | HTTP 404 |
| smart-published-fallback | 正向 | 通过 | HTTP 207 |
| smart-source-header | 正向 | 通过 | {"headerPresent":true} |
| manual-before-smart | 正向 | 通过 | HTTP 202 |
| current-draft-not-served | 反向/边界 | 通过 | HTTP 404 |
| current-published-version | 正向 | 通过 | HTTP 208 |
| smart-switch-off | 反向/边界 | 通过 | HTTP 404 |
| smart-server-disabled | 反向/边界 | 通过 | HTTP 404 |
| share-expired | 反向/边界 | 通过 | HTTP 404 |
| share-expiry-keeps-runtime | 正向 | 通过 | HTTP 201 |

## 浏览器验收

| 编号 | 已执行结果 | 证据 |
|---|---|---|
| UI-01 | 通过：新建服务不填名称，确定后提示错误且弹窗保留 | ui-validation.txt |
| UI-02 | 通过：创建 QA-R4-浏览器验收-0914 后立即显示项目 3 个已发布示例 | Playwright snapshots |
| UI-03 | 通过：规则查询条件为非法 JSON 时，弹窗、名称和路径均保留 | ui-rule-invalid.txt |
| UI-04 | 通过：修正 JSON 后创建 GET /ui-r4，公开地址实测返回 200 和预期 JSON | Playwright snapshots; curl response |
| UI-05 | 通过：创建分享不填名称时，提示错误且弹窗保留 | ui-share-validation.txt |
| UI-06 | 通过：创建浏览器分享后显示有效链接；复制操作反馈成功 | ui-copy-share.txt |
| UI-07 | 通过：列表同时显示有效、已过期、已撤销；两条过期链接不计入有效数量 | ui-expired-list.txt; share-management.png |
| UI-08 | 通过：有效分享页显示只读说明、规则摘要及公开调用地址 | ui-share-valid.txt; share-valid.png |
| UI-09 | 通过：过期分享页显示分享链接不可用，与公开接口 404 一致 | ui-share-expired.txt; share-expired.png |

## 验证命令与日志

```sh
./node_modules/.bin/eslint .
./node_modules/.bin/vitest run
./node_modules/.bin/vue-tsc --noEmit
./node_modules/.bin/vite build
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -pl services/platform-api test
git diff --check
```

9 月 14 日验证日志保存在 `.testhub/qa-r4-20260909/validation-20260914/`，使用工作区持久路径，避免临时目录被清理后丢失。
新增后端 `MockRuntimeHttpFlowTests` 使用真实嵌入式 Tomcat HTTP 请求；仅 MockMvc 无法复现的表单消费问题因此得到覆盖。前端新增 `mockServicePage.test.ts` 和 `mockSharePage.test.ts` 共 8 项。
构建存在已有大包体提示，Monaco 依赖存在缺失 sourcemap 的提示；均不影响 lint、测试、类型检查和构建结果。访问过期链接的浏览器控制台 404 属于预期负向结果。

## 原始失败与复验口径

- 初始运行时 40 项中 34 项通过、6 项不匹配，原始结果在 `runtime-before-fixes.json`。其中 HEAD 响应头检查错误地依赖大小写（X-QA/X-Qa），属于测试脚本错误，已改为不区分大小写，未改产品响应。其他 5 项为实际产品问题。
- 后续独立发现路径大小写误匹配；前置表单过滤器问题在首轮真实 Tomcat 新增测试中表现为 PUT 返回 404；分享页迟到响应问题在组件测试中先失败再修复。
- 9 月 9 日的 51 项成功结果保留为 `runtime-results-20260909.json`；9 月 14 日重跑的 51 项为 `runtime-results.json`。两次结果不叠加计数。
- 过期场景仅回写本轮新建 QA-R4 分享记录的 expires_at 到过去，验证数据库到期条件及真实页面；没有修改既有用户分享记录。

## 范围与限制

- 实测覆盖计划中的主要服务、匹配、字节边界、智能示例及分享链路。异步竞态和 ANY 隐藏条件通过组件测试验证，不冒充手工慢网浏览器操作。
- 未穷举全部项目角色组合；本轮实测匿名管理拒绝和跨项目服务隔离，其他模块权限保持现有全量后端回归覆盖。
- 未进行 20 服务/200 规则/20 分享的容量填充、压力测试或所有浏览器/窄屏组合测试；不宣称这些已通过。
- 本节点没有新增规则编辑/删除、脚本执行或动态模板能力；继续遵循现有模块契约。

## 产物与环境

- 主要修改：`MockServicePage.vue`、`MockSharePage.vue`、`MockRuntimeController.java`、`MockManagementController.java`、`MockService.java`；新增 `MockAwareFormContentFilter.java` 与上述回归测试。
- `.testhub/qa-r4-20260909/` 内保留运行脚本、JSON 结果、浏览器快照和截图；该目录被 Git 忽略。
- 关键截图：`share-management.png`、`share-valid.png`、`share-expired.png`；已人工查看管理列表与失效页面渲染。
- 保留 QA-R4 新建服务、规则、接口示例和分享记录用于追溯。保留 R1/R2/R3 与其他已有未提交修改。
- 收尾关闭本轮浏览器、8080 API 和 5174 Vite；Docker 基础服务及原有 API Runner 保持运行，未修改 Runner 开关、用户代理或持久安全配置。
