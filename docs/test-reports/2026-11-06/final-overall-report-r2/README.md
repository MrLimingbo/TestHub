# TestHub最终整体测试报告 R2

更新：2026-11-06。原整体测试结果及本次CI修复、通过结果已审核，按用户授权更新报告并提交主分支；R1冻结快照保留。

- [最终整体测试报告](OVERALL-TEST-REPORT.md)
- [CI结构化结果及运行链接](CI-RESULTS.json)
- [CI结果审核及提交推送授权](CI-RESULT-APPROVAL.json)
- [整体统计与当前状态](summary.json)
- [来源索引](SOURCE-INDEX.json) / [文档核验](VALIDATION.json) / [本版文件摘要](ARTIFACT-HASHES.json)
- [原各模块与台账](../../2026-11-05/final-overall-report-r1/README.md)

已验证候选cec6df1：Web950项、启动15项、后端1374项全部通过，安全门通过。上述为工程套件复验，不加入原3090项、109项产品Bug或2项历史资产统计。本次4项CVE修复、1项CI测试资产维护单列。

D26两项SPEC继续跳过（有效窗口0/2）；12项环境补验继续跳过；升级关闭超时风险UNCONFIRMED_RETAINED、rootCauseConfirmed=false，不恢复执行或进入下一模块。
