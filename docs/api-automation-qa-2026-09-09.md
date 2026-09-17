# 接口自动化节点测试与修复报告（2026-09-09）

本轮在现有工作区继续完成 R3 接口自动化的测试、修复和回归。未提交或推送代码；R1/R2 及用户原有修改保留。下一节点为 Mock 服务，按约定暂停，等待继续指令。

## 结果与统计

- 最终运行时检查 **21/21 通过**：正向 14、反向/边界 7，通过率 100%。其中包括真实 HTTP 执行及报告检查，不将任务提交成功视为执行成功。
- 浏览器复验：空场景必填校验后弹窗保持打开；编辑后直接运行自动生成 v2；该 v2 最终成功；任务详情显示 HTTP 状态、统计、清单和报告校验值。
- 自动化代码检查：前端 72/72、Runner 39/39、后端接口自动化与 Runner 集成测试 33/33，共 **144 项通过**。
- ESLint、vue-tsc、Vite 构建、git diff --check 通过。构建保留现有大包体提示；前端测试过程中有依赖 sourcemap 提示，不影响测试结果。
- 本轮修复 8 类问题。最终检查无阻塞项；未实测范围单列于下文。

## 缺陷与修复

| 编号 | 问题与复现 | 修复及证据 |
|---|---|---|
| R3-001 | 新建场景不填名称，点确定后提示错误但弹窗关闭；同模块 9 个提交弹窗共用错误事件模式 | 改用 on-before-ok 和 ok-loading，只在操作成功后主动关闭。组件测试及浏览器复验通过 |
| R3-002 | 修改场景后直接运行，执行入口只提交旧的已保存版本 | 普通/高级/当前场景数据驱动执行前检查改动并保存，保存失败阻止提交；无改动不增加版本。组件测试和真实 v2 成功报告通过 |
| R3-003 | cURL 的 -I/-G、重复 -d、--data-urlencode、紧凑 -XPOST/-d 被忽略或解析错误 | 保留方法及数据语义；文件引用明确拒绝。解析回归通过 |
| R3-004 | curl 使用 {{BASE_URL}} 且带 token=literal 时，URL 构造失败导致明文查询密钥未替换 | 查询脱敏独立于绝对 URL 解析，保留已有变量引用。解析回归通过 |
| R3-005 | 从接口导入仅拼接路径，遗漏版本的路径/查询示例及 Content-Type | 复用接口版本快照与 URL 构造，保留参数、正文和内容类型。组件测试验证 /orders/42?q=a%20b 和 XML |
| R3-006 | Runner 比控制面先启动，首次能力发现失败后永久处于 DISABLED，后续任务 REJECTED | 未启用状态收到任务时重新发现能力；离线仍安全拒绝，恢复后可执行。新增恢复测试及运行日志验证 |
| R3-007 | Node 24 的 lookup(all=true) 调用收到单地址回调，真实 HTTP 抛 ERR_INVALID_IP_ADDRESS | 按 options.all 返回已校验地址数组或单地址，保持固定目标地址边界。新增回调契约测试；真实 GET 返回 200 |
| R3-008 | POST 创建计划指定 enabled=false，数据库默认值却使计划启用，且可立即执行 | insert 显式保存 enabled；新增集成测试，重启后实测创建返回 false、立即执行返回 409 |

## 运行时验证明细

在默认关闭 HTTP 的 Compose Runner 上首先验证 REJECTED/EXECUTION_DISABLED。随后暂时使用带随机签名密钥的本地 Runner，只允许 https://postman-echo.com。默认环境无 BASE_URL，因此测试步骤使用明确的公开测试地址。

| 检查 | 类型 | 结果 | 终态/关键证据 |
|---|---|---|---|
| trusted-runner | 正向 | 通过 | Runner 状态符合预期 |
| request-extract-assert | 正向 | 通过 | SUCCEEDED · 01M21YAAQ21YAV4Y6Z5WKRDESF |
| report-sha256 | 正向 | 通过 | 校验一致 |
| assertion-failure | 反向/边界 | 通过 | FAILED · 01M21YACVMHH6C2Q3YBJ2MF3Y4 |
| condition-skip-loop-wait | 正向 | 通过 | SUCCEEDED · 01M21YAF28R38H2B9XYB91550H |
| parallel | 正向 | 通过 | SUCCEEDED · 01M21YAH95T5JZERQH83ZPWWPA |
| scripts | 正向 | 通过 | SUCCEEDED · 01M21YAKE3DKZRE82XC24WRK2E |
| dataset-row-1 | 正向 | 通过 | SUCCEEDED · 01M21YANN8TB0VHBSV26HWR2PW |
| dataset-row-2 | 正向 | 通过 | SUCCEEDED · 01M21YANPC5ZTN9PRDHSWKNWF0 |
| profile-overrides | 正向 | 通过 | SUCCEEDED · 01M21YAQVQXYDEVBK77CBA2JFF |
| allowlist-block | 反向/边界 | 通过 | FAILED · 01M21YAT0BYY948CYW0452MQFX |
| cancel | 反向/边界 | 通过 | CANCELLED · 01M21YAV6HX6088RX9GXXQ8CBR |
| wait-budget-rejection | 反向/边界 | 通过 | FAILED · 01M21YDWAH9YGCF21WH2XD6DXX |
| retry-and-continue | 反向/边界 | 通过 | FAILED · 01M21YDXFKZ7GZD3CQFGACWSES |
| retry-result-count | 正向 | 通过 | 校验一致 |
| versioned-references | 正向 | 通过 | SUCCEEDED · 01M21YE1SX9VVSQM7H7K28XQ2A |
| schedule-manual-run | 正向 | 通过 | SUCCEEDED · 01M21YE51QQVQD51XC7N6QHJ98 |
| offline-deadline-timeout | 反向/边界 | 通过 | TIMED_OUT · 01M22CD2MTPHBWS92R54KAF6JN |
| schedule-created-disabled | 正向 | 通过 | 校验一致 |
| disabled-schedule-run-rejected | 反向/边界 | 通过 | HTTP 409 |
| default-runner-restored | 正向 | 通过 | Runner 状态符合预期 |

## 失败记录与统计口径

原始运行结果保留全部失败记录，没有覆盖或删除：启动顺序缺陷导致 REJECTED、DNS 回调缺陷导致 FAILED；修复后重跑已成功。Runner 切换期间另有一条任务未获处理而超时，保留为环境切换记录。
最初名为 timeout 的测试将 WAIT=15000ms 与 timeout=10s 组合，预期 TIMED_OUT，实际 FAILED。检查实现确认 WAIT 会提前拒绝不可能满足的预算，故该条原预期作废，不纳入最终 21 项；重新执行 wait-budget-rejection 验证提前失败，并通过停止临时 Runner 后运行 offline-deadline-timeout 验证真实到期收敛。原始不匹配结果仍在 runtime-results.json 和最终文件 superseded 中。
数据集两行分别等待独立任务终态；重试继续测试验证 3 条 HTTP 结果（2 次 503 失败、后续 1 次成功），场景最终仍为 FAILED。正向统计不意味着每条任务都是 SUCCEEDED：取消、拒绝、失败和超时均按预期行为判定。

## 范围与限制

- 实测覆盖：场景保存执行、请求/提取/断言、条件/循环/等待、并行、脚本、数据行、配置覆盖、失败继续与重试、版本固定的公共步骤/子场景、计划手动执行、禁用状态、取消、离线到期、脱敏报告校验。
- 未进行真实外部 CI Webhook 签名调用、Quartz 到点触发和连续失败自动停用的现场长时间观测；这些不能算浏览器端到端通过，相关后端回归包含现有调度测试。
- CSV/JSON 文件上传、所有角色权限组合未在本轮逐项跑浏览器；不宣称穷举所有交互组合。
- 当前 Runner 的可执行请求字段限于方法、URL、正文、Content-Type；自定义认证头和任意响应体提取不属于本轮新增能力。

## 验证命令

```sh
./node_modules/.bin/eslint .
./node_modules/.bin/vitest run
./node_modules/.bin/vue-tsc --noEmit
./node_modules/.bin/vite build
node --test services/api-runner/test/*.test.js
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -pl services/platform-api '-Dtest=ApiAutomation*,ApiRunner*' test
git diff --check
```

## 证据与环境恢复

- 本地证据目录：`.testhub/qa-r3-20260909/`（Git 忽略），包含原始/最终运行时 JSON、复验脚本、检查日志和 Playwright 快照截图。
- 浏览器成功报告截图：`playwright/page-2026-09-09T06-08-28-012Z.png`。
- 临时签名 Runner 已停止；原 Compose API Runner 已启动并验证 executionEnabled=false。临时控制面已切回默认配置；测试结束后关闭本轮启动的 API 与 5174 页面服务。
- 保留 QA-R3 场景、执行、数据集与配置集供追溯；本轮创建的定时计划已全部停用。未修改仓库持久安全配置或用户代理。
