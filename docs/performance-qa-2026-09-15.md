# 性能测试节点 QA（2026-09-15）

本节点验收完成。只测试和修复性能模块；保留此前模块修改和测试数据，不提交、不推送，完成后暂停。

## 结果

| 验证层 | 通过 / 执行 | 正向 / 反向 | 说明 |
| --- | --- | --- | --- |
| 管理接口及真实执行链路 | 54 / 54 | 20 / 34 | 成功率 100%，无阻塞项；按独立检查统计，非压测样本数 |
| 真实浏览器 | 8 / 8 | 7 / 1 | 创建、编辑、执行、报告及失败保留表单 |
| 后端全量回归 | 132 / 132 | — | 包含性能模块 11 项，部分通过模拟 Runner 事件验证状态机 |
| JMeter Runner | 19 / 19 | — | 包含真实 JMeter 双线程组 TPS/CSV/断言，以及真实进程组强制终止测试；未跳过 |
| 前端全量回归 | 88 / 88 | — | 包含性能页面 6 项、图表 2 项 |

自动化回归合计 239 项。类型检查、构建、源码 ESLint 和 `git diff --check` 通过。ESLint 显式排除 `.testhub/**`、`.playwright-cli/**` 临时证据；未调整源码 lint 规则。构建保留已有 Monaco sourcemap / 大包提示。

## 本轮修复

| 编号 | 复现问题与影响 | 修复及证据 |
| --- | --- | --- |
| PERF-01 | 配置 TPS 后，JMeter 因 `IntegerProperty` XML 转换异常退出，无样本 | 修正 `calcMode` 属性格式；`failed-jmeter.log` 保存原始错误，真实 JMeter 测试及平台执行完成 |
| PERF-02 | 阶梯线程组使用全局线程数分摊各阶段 TPS，低于配置目标 | 使用当前线程组限速模式；同一 2 线程、4 TPS、两阶段场景由 23 样本改善为 42 样本，峰值并发 2 |
| PERF-03 | 取消只杀启动 shell，Java 子进程成为孤儿并继续发请求；`child.killed` 又阻止强杀升级 | POSIX 下建立独立进程组，发送整组 SIGTERM，未完成则升级 SIGKILL；取消终态为 CANCELLED，后续请求增量 0 |
| PERF-04 | CPU/RSS 仅采集 shell，未反映真实 JVM 资源 | 汇总独立进程组资源，排除其他进程；浏览器报告显示真实 JVM 百 MB 级内存。Alpine 默认 BusyBox ps 不支持所需参数，Dockerfile 增加 procps-ng；已在一次性 Alpine 容器安装并执行相同 ps 命令通过 |
| PERF-05 | JTL 缓冲导致运行中指标延迟，短测试可能漏掉峰值并发 | 开启结果自动刷新，实时采集；阶梯最终报告峰值并发与 JTL 一致为 2 |
| PERF-06 | 超时先被控制平面置为 TIMED_OUT，随后原始 JTL/日志上传被拒绝 403 | 原 Runner 身份校验保持不变，只为最近两分钟内的超时任务开放归档；真实超时双工件归档下载通过，超窗请求测试仍返回 403 |
| PERF-07 | 保存失败自动关闭计划表单 | 使用 `on-before-ok` 返回失败，保留用户输入；真实页面与前端回归通过 |
| PERF-08 | 线程组名称属于组件 key，输入时组件被重建 | 使用稳定的列表位置 key，回归确认输入组件实例保持不变 |
| PERF-09 | 慢详情、旧项目列表或已关闭报告的异步响应覆盖当前选择；报告轮询可在关闭后重新启动 | 请求序号与项目/计划上下文校验；项目切换关闭表单/报告，忽略旧结果，报告关闭清理轮询；轮询失败显示可恢复提示 |
| PERF-10 | 抽屉展开后趋势图沿用过窄的初始化尺寸，图例与坐标轴重叠 | 两个图表监听容器尺寸并调用 resize，明确图例位置；截图 `resources-fixed.png` 已目视复核 |

TPS 模式依据 [Apache JMeter 官方组件文档](https://jmeter.apache.org/usermanual/component_reference#Constant_Throughput_Timer)，并用本地 JMeter 5.6.3 的真实采样验证。

## 真实执行证据

目标仅为本机专用服务 `127.0.0.1:18085`。正常场景 2 线程、4 TPS、10 秒；阶梯两阶段间隔 1 秒。取消场景配置 120 秒后提前取消，超时场景配置 120 秒并设置 60 秒任务截止时间。未向公共网站发送压测流量。

- 恒定负载：41 样本、0 错误、P95 26 ms、质量阈值通过。
- CSV/POST/请求头/查询参数/正文/响应断言/集合点：38 样本、0 错误。
- 阶梯负载：42 样本、0 错误、峰值并发 2。
- 响应断言失败：40 错误样本，质量阈值 FAILED；JMeter 进程正常退出，因此执行状态 SUCCEEDED，不能将它等同于质量通过。
- P95 失败：约 2035 ms，超过配置的 1 ms；无 HTTP 错误但质量 FAILED。
- 未授权目标 Origin：任务 FAILED，错误来自 Runner 运行时配置校验，未启动该目标压测。
- 取消：CANCELLED，随后请求增量 0；超时：TIMED_OUT，随后请求增量 0。
- 超时工件：JTL 16834 字符、日志 7521 字符，均可下载；原始内容及大小见对应报告与文件。
- 浏览器提交的任务也真实完成，报告显示 41 样本、阈值通过、可见历史对比和归档入口。

## 修复前失败与测试工具修正

保留 `runtime-before-fixes.json`、`runtime-intermediate.json`、`failed-jmeter.log`、`artifact-before.log`、`ui-baseline-tests.log`，不抹去失败记录。

Runner 重启时旧进程尚未退出就启动同名新进程，曾产生一次队列超时；最终回归等待旧进程退出、确认新 Runner 已可信且空闲后执行。该中间结果不作为最终通过证据，不据此声称验证了所有重启/故障转移场景。

测试脚本最初将草稿提交预期写成 400（实际契约为 409），未授权目标终态预期写成 REJECTED（实际实现为 FAILED），已按代码核对后修正。工件下载字段由 `fileName` 修正为 `originalName`。Python 导入后的结果列表重新绑定导致早期汇总遗漏，最终由分别保存的管理/执行/补充检查 JSON 合并成 `all-results.json`，54 项均有实际日志。

曾使用 `vue-tsc -b` 意外生成 JS 输出；全部移至证据目录 `generated-check-output/`，恢复源目录后使用 `vue-tsc --noEmit` 重新检查。首次 `eslint .` 扫描了下载的 JMeter 文档和临时生成文件；最终 ESLint 仅排除临时证据目录，源码检查通过。

## 验证命令

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -pl services/platform-api test
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home JMETER_BIN="$PWD/.testhub/qa-r5-20260915/apache-jmeter-5.6.3/bin/jmeter" node --test services/jmeter-runner/test/*.test.js
./node_modules/.bin/eslint . --ignore-pattern '.testhub/**' --ignore-pattern '.playwright-cli/**'
./node_modules/.bin/vue-tsc --noEmit
./node_modules/.bin/vitest run
npm run build
git diff --check
```

真实 JMeter 测试需要 `JMETER_BIN`；不提供时只有该可选集成用例跳过，本轮提供了路径并实际执行。

## 边界与未执行项

本轮是性能测试功能节点验收，不是 TestHub 的生产容量结论。未重建并完整运行 JMeter Docker 镜像（容器中已验证 procps-ng 安装和资源命令）；未覆盖多机大并发、长时间稳定性、Windows Runner、全角色权限组合及真实多 Runner 故障转移；相关已有模拟测试不计作真实多机执行。原有测试数据和本轮合成计划、执行、工件均保留。后端回归中的测试专用记录按既有测试清理逻辑处理。

所有临时脚本、快照、截图和日志在 `.testhub/qa-r5-20260915/`。本轮 API、Vite、目标服务、Runner 与浏览器均已停止；8080、5174、18085 无监听，未残留本轮 JMeter 进程。最终进程清理结果见 `cleanup.json`；不停止既有 Docker 基础服务和其他项目进程。

## HTTP / 执行检查明细

| 检查 | 类型 | 结果 |
| --- | --- | --- |
| threads-zero | 反向 | 通过 |
| threads-over | 反向 | 通过 |
| duration-short | 反向 | 通过 |
| duration-over | 反向 | 通过 |
| invalid-url | 反向 | 通过 |
| url-query | 反向 | 通过 |
| url-credentials | 反向 | 通过 |
| method-invalid | 反向 | 通过 |
| restricted-header | 反向 | 通过 |
| crlf-header | 反向 | 通过 |
| invalid-csv | 反向 | 通过 |
| undefined-variable | 反向 | 通过 |
| rendezvous-over | 反向 | 通过 |
| error-threshold-over | 反向 | 通过 |
| steps-missing | 反向 | 通过 |
| get-body | 反向 | 通过 |
| empty-name | 反向 | 通过 |
| empty-groups | 反向 | 通过 |
| duplicate-groups | 反向 | 通过 |
| plan-create-detail | 正向 | 通过 |
| duplicate-plan | 反向 | 通过 |
| optimistic-update | 正向 | 通过 |
| stale-version | 反向 | 通过 |
| anonymous-management | 反向 | 通过 |
| cross-project-plan | 反向 | 通过 |
| draft-execution | 反向 | 通过 |
| short-timeout | 反向 | 通过 |
| real-latency-threshold-terminal | 正向 | 通过 |
| latency-threshold-fails | 反向 | 通过 |
| real-timeout-archive-terminal | 反向 | 通过 |
| timeout-artifacts-retained | 正向 | 通过 |
| timeout-download-JMETER_JTL | 正向 | 通过 |
| timeout-download-JMETER_LOG | 正向 | 通过 |
| timeout-final-no-http | 反向 | 通过 |
| real-baseline-terminal | 正向 | 通过 |
| real-metrics-quality | 正向 | 通过 |
| real-resources | 正向 | 通过 |
| real-archives | 正向 | 通过 |
| download-JMETER_JTL | 正向 | 通过 |
| download-JMETER_LOG | 正向 | 通过 |
| report-csv | 正向 | 通过 |
| real-comparison-terminal | 正向 | 通过 |
| history-comparison | 正向 | 通过 |
| real-request-terminal | 正向 | 通过 |
| request-data-assertions | 正向 | 通过 |
| real-stepped-terminal | 正向 | 通过 |
| stepped-metrics | 正向 | 通过 |
| real-assertion-failure-terminal | 正向 | 通过 |
| assertion-errors-and-threshold | 反向 | 通过 |
| real-rejected-terminal | 反向 | 通过 |
| real-cancelled-terminal | 反向 | 通过 |
| cancel-stops-http | 反向 | 通过 |
| real-timeout-terminal | 反向 | 通过 |
| timeout-stops-http | 反向 | 通过 |

## 浏览器检查明细

| 编号 | 检查 | 证据 |
| --- | --- | --- |
| UI-01 | 模块列表与计划详情 | `ui-snapshot.log` |
| UI-02 | 必填校验失败保留表单 | `ui-invalid-fixed-snapshot.log` |
| UI-03 | 线程组名称输入与保存 | `ui-group-fixed.log` |
| UI-04 | 浏览器创建计划 | `ui-created.log` |
| UI-05 | 浏览器编辑计划版本递增 | `ui-edited.log` |
| UI-06 | 浏览器提交任务并完成 | `ui-submitted.log / ui-live-report.log` |
| UI-07 | 报告阈值、历史对比与归档入口 | `ui-report.log` |
| UI-08 | 图表展开尺寸与资源监控 | `resources-fixed.png` |
