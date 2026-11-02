# 下一批P2规则审核包

日期：2026-10-30。**仅规划，未实施、未执行新测试。**

P1六项修复与回归已经用户审核通过。原10项加P1六项，共16项观察获批；剩余31项为27项P2、4项P3。建议下一批先处理环境边界与数据一致性的**6组规则、8项观察**。

|规则|覆盖观察数量|推荐|
|---|---:|---|
|D01 环境定义|1|写入须引用已定义环境，旧孤立数据保留。|
|D03-CFG 停用环境维护|1|允许配置维护，与P1执行停用分开。|
|D09 响应码导出|1|三出口保留真实单响应码。|
|D10 目录复制|1|原子提交、请求幂等，建议上限200项。|
|D17 归一键冲突|3|三处trim冲突400，不静默覆盖。|
|D22 报告计划并发|1|复用配置版本；缺版本428、过期409。|

- [6组规则、选项与43组验收设计](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-30/p2-rule-review/P2-RULES-AND-ACCEPTANCE.md)
- [具体审核填写单](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-30/p2-rule-review/USER-REVIEW.md)
- [47项最新状态](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-30/p2-rule-review/OBSERVATION-STATUS.md)
- [P1审核通过记录](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-29/p1-fixes-r1/REVIEW-APPROVAL.json)
- [历史证据与当前静态源码定位](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-30/p2-rule-review/EVIDENCE.json)
- [12项环境阻塞，仍暂缓](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-30/p2-rule-review/ENVIRONMENT-BLOCKERS.json)
- [升级关闭超时，未确认风险](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-30/p2-rule-review/UNCONFIRMED-RISK.json)

43组是推荐A分支的验收检查，尚不是执行结果，不能计为通过。上述8项也尚未批准实施；其余23项保持暂缓。原始执行快照和未确认风险不回写、不自动关闭。
