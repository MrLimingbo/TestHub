# 升级关闭超时：独立未确认风险

2026-10-29状态延续：**OBS-XREG-SHUTDOWN / UNCONFIRMED_RETAINED**。不并入47项观察、10项修复候选或12项阻塞；不关闭、不宣称已修复。

原始一次离屏应用关闭超过30秒，具体阶段及根因未确认；两次候选组件摘要一致的独立复验均通过，候选关闭0.769秒和0.492秒，身份、任务、文件SHA和冷快照保持。

## 后续调查建议（尚未执行）

若获准调查，在同一批准候选组件和全新自有工作区中记录：退出请求、before-quit、备份/迁移操作等待、引擎/浏览器停止、本地后端停止、进程exit、stdio close和调试连接关闭的单调时间线；保留正常与超时对照。
将安静环境与明确的并行负载分别留证，不混合归因。只收集阶段、耗时、错误类型和自有进程关系，不输出Cookie、密钥、密码或用户数据。
保持原退出阈值；不通过增加超时、强杀后判成功或重复未再现来关闭风险。OBS-15-E001的预览取消属于业务交互规则，不能预设与本风险有共同根因。
真实再现后再判断产品、历史基线包或QA引导问题，必要时登记新Bug并给复现。只有定位和相应验证满足后，才提交风险状态变更审核。

本轮未复现、未加日志、未执行负载对照、未改变任何退出逻辑。

[已审核报告的独立风险](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/UNCONFIRMED-RISK.md) · [原始FAIL](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-package.json) · [两次复验记录](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-package-final.json)
