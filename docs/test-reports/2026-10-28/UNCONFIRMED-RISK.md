# 升级关闭超时未确认风险

编号：OBS-XREG-SHUTDOWN；状态：**UNCONFIRMED_RETAINED，继续保留**。

原始现象：P013离屏应用关闭超过30秒。首轮具体关闭阶段与产品根因没有确认，不能将风险声明为已修复。

两次后续独立复验在完整后端重跑结束后执行，候选四类组件摘要一致，均完成V50→V53、身份和任务保持、文件SHA一致、冷快照生成及正常退出。

|复验|历史包关闭毫秒|候选包关闭毫秒|结果|
|---|---:|---:|---|
|1|686.0|769.0|PASS，exitCode=0|
|2|970.0|492.0|PASS，exitCode=0|

最终P013通过依据后续复验；原始FAIL及未确认风险均保留。该风险不记为已修复产品Bug、不增加业务用例分母。后续再现时应采集关闭阶段、负载、版本和退出证据，再判断是否登记产品Bug。

[原始关闭超时FAIL](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-package.json) · [两次复验及明确归并记录](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-package-final.json) · [原风险记录](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/shutdown-observation.json)
