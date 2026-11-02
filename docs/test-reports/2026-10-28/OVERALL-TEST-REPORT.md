> 后续跟进（2026-10-29）：已批准的10项观察候选完成修复与后台回归，结果待审核，详见[后续报告](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-29/direct-fixes-r1/REPORT.md)。原批次统计保持；另37项观察、12项阻塞及升级关闭超时未确认风险继续保留。

> 审核更新（2026-10-29）：用户已审核通过本整体测试报告。观察、环境阻塞及未确认风险未关闭；后续处理计划另行提交审核。

# TestHub 整体测试报告

汇总时间：2026-10-28T19:03:47+08:00；测试证据期间：2026-10-23至2026-10-28。状态：各模块已获用户审核通过，整体汇总报告提交审核。

**16个主模块、19个验收范围，共登记3090项模块检查：正例1643、反例1447；最终处置3031通过、0未关闭失败、47项观察、12项环境阻塞。108个独立产品Bug及1个正式登记的测试资产问题均已修复验证。另单列1项升级关闭超时未确认风险，继续保留。**

## 统计口径与证据边界

- 03“项目与系统管理”分为03A—03D四批，因此16个主模块按19个范围列示。每个范围只取最终审核版本，初测、重复回归和Bug独立复验不重复加入合计。Mock为原155项及预先锁定的7项补充，共162项。
- 3090是各模块登记检查项次；跨模块可能测试同一规则，不是全系统互斥独立场景数，也不是产品覆盖率。前后端、桌面等工程套件另列，不叠加进3090。
- 03B、03C原整批快照合计仍有11条历史失败（6+5），对应8个Bug。后续模块16及H2/MySQL专项已验证修复；汇总按明确用例映射登记11条后续通过。原批次数据不改写，也不声称重新完整执行132项或109项。
- 原选定批次合计为3020通过、11历史失败、47观察、12阻塞；关联后续证据后的最终处置为3031通过、0未关闭失败、47观察、12阻塞。
- 本报告汇总跨日期已审核证据；本次没有新增执行，不宣称全部3090项均在当前V53上同批重新运行。历史报告中的“等待审核”是当时生成状态，审核状态以用户后续明确确认及本次模块16审核通过为准。

[3090项原状态与最终处置台账](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/CASE-RESULTS.csv) · [11条后续修复映射](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/followup-case-mapping.json)

## 各模块最终结果

|范围|模块|项次|正例|反例|最终通过|未关闭失败|观察|阻塞|产品Bug已修复|
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
|01|[登录、注册与会话](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-01-auth/TEST-REPORT.md)|105|46|59|105|0|0|0|4|
|02|[工作台](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-02-dashboard/TEST-REPORT.md)|103|74|29|103|0|0|0|4|
|03A|[项目管理与成员权限](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03a-projects/TEST-REPORT.md)|120|65|55|120|0|0|0|4|
|03B|[项目环境、参数与密钥](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03b-environments/TEST-REPORT.md)†|132|62|70|129|0|3|0|3|
|03C|[文件管理与操作日志](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-03c-files-audit/TEST-REPORT.md)†|109|57|52|107|0|2|0|5|
|03D|[账号管理与系统参数](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-03d-accounts-config/regression-r1/TEST-REPORT.md)|139|75|64|137|0|2|0|4|
|04|[功能测试资产与治理](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-04-functional-assets/regression-r1/TEST-REPORT.md)|159|91|68|159|0|0|0|6|
|05|[测试计划与交付](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-05-plans-delivery/regression-r1/TEST-REPORT.md)|181|104|77|179|0|2|0|11|
|06|[接口设计与导入](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-06-api-design/regression-r1/TEST-REPORT.md)|190|112|78|188|0|2|0|9|
|07|[接口调试](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/TEST-REPORT.md)|217|122|95|211|0|6|0|10|
|08|[接口自动化](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-08-api-automation/regression-r1/TEST-REPORT.md)|214|117|97|211|0|3|0|12|
|09|[Mock](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-09-mock-regression/TEST-REPORT.md)|162|90|72|160|0|2|0|8|
|10|[性能测试](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-10-performance-regression/TEST-REPORT.md)|214|125|89|212|0|2|0|4|
|11|[Web UI自动化](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-11-web-ui-regression/TEST-REPORT.md)|220|116|104|217|0|3|0|6|
|12|[移动端自动化](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-12-mobile-regression/TEST-REPORT.md)|179|77|102|173|0|2|4|8|
|13|[质量报告与任务](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/TEST-REPORT.md)|158|90|68|151|0|7|0|5|
|14|[通知、集成、插件与运维](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/TEST-REPORT.md)|187|86|101|180|0|7|0|5|
|15|[桌面工作区与跨端](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-15-desktop-workspace-cross-end/TEST-REPORT.md)|218|82|136|213|0|1|4|0|
|16|[横向回归](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/TEST-REPORT.md)|83|52|31|76|0|3|4|0|
|合计|模块登记项次|3090|1643|1447|3031|0|47|12|108|

† 03B最终129通过/3观察，其中6项由后续定向证据关闭；03C最终107通过/2观察，其中5项由后续定向证据关闭。它们的原整批结果分别123通过/6失败/3观察、102通过/5失败/2观察。模块16另复核8个既有Bug，不重复记作新增Bug。

[各模块CSV明细](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/MODULE-RESULTS.csv)

## Bug修复与测试资产

独立产品Bug按原编号去重，共108项，均为FIXED_VERIFIED；已确认未关闭产品Bug为0。01—14各范围的Bug在本范围回归关闭，03B/03C的8项在模块16关闭；15没有确认产品Bug，16没有新增确认产品Bug。
正式登记的测试资产QA-XREG-TEST-001已修复：Mock重名断言改为公开409及原快照保持；MockSyncFlowTests 9/9通过，原先被提前失败阻断的重音名称和同名规则断言已执行。独立HTTP复核409且原记录唯一。
V53相关备份版本夹具维护和首次定向存储Spy配置修正单列为执行/资产维护，不登记成新增产品Bug。完整后端首轮949通过/2处版本夹具失败，修正后重新完整执行951/951通过，首轮证据仍保留。

[108项Bug及1项测试资产逐项清单](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/BUG-FIX-REGISTER.md) · [修复台账CSV](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/BUG-FIX-REGISTER.csv)

## 遗留观察项

**47条模块观察记录继续保留，不计通过，也不直接认定为已修复Bug。** 跨模块存在同一策略主题，47不表示47个互斥的产品问题。优先确认以下策略：

- 会话与敏感回放：账号停用/删除/改密后旧会话是否立即失效；PAT历史读取权限；加密回放实际保存认证/正文与页面说明的一致性；WS旧会话回收。
- 环境与配置：自由环境代码、普通参数和密钥同名覆盖、停用环境跨入口执行规则。
- 输入和数据快照：小数/整数/字符串转换、trim后键冲突、未知字段、历史执行标题及页面对象冻结时点、非200响应码导出。
- 操作与恢复：取消/离页后的行操作是否撤销、刷新锁定新表单、复制中断的幂等和清理、并发编辑冲突策略。
- 稳定性与健康：业务SLA、资源阈值和更长稳定性窗口；Redis故障时readiness依赖策略。最新实测为总health503、readiness200、auth/me500，恢复成功。

[47条观察事实与待确认规则](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/OBSERVATIONS.md) · [观察项CSV](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/OBSERVATIONS.csv)

## 环境阻塞

**12条阻塞用例，归为7组环境条件；重复环境需求不重复当成新的平台。** 移动端4条、桌面4条、横向4条均未算通过。

|环境组|对应范围用例|补齐条件与后续验证|
|---|---|---|
|Android设备或模拟器|12/D001, 12/D003, 12/D004|可用adb、Appium及已授权Android设备/模拟器，实际七步、PNG、断开、超时、取消与会话释放。|
|iOS真机或模拟器|12/D002, 12/D003, 12/D004|可用Xcode/simctl、Appium和iOS设备条件，实际执行及错误恢复。|
|大小写敏感文件系统|15/F016|在独占、大小写敏感卷复核认证清单重名拒绝；现有归档单元测试不能替代该原生场景。|
|Intel Mac原生环境|15/X001, 16/Z001|在Intel原生系统准备、校验、运行目标架构包并执行工作区流程。|
|Windows原生环境|15/X002, 16/Z002|原生Windows安装、首启、PowerShell、运行时、恢复及跨系统工作区验证。|
|发行者签名与公证|16/Z003|具备发行者证书和公证条件后核验正式分发包，当前ad-hoc不能代替。|
|干净机器Finder/Gatekeeper首启|15/X003, 16/Z004|用最终发行包在干净机器验证安装、隔离标记和Finder/Gatekeeper首启。|

[12条环境阻塞清单](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/ENVIRONMENT-BLOCKERS.md) · [阻塞CSV](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/ENVIRONMENT-BLOCKERS.csv)

移动端真实设备执行次数为0，已通过部分包含资产管理、控制面及Appium协议模拟，不能替代真机七步、PNG、设备断开、取消与释放验证。Intel/Windows、大小写敏感卷及正式安装尚未完成；当前Mac arm64包为ad-hoc预览，distributionReady=false。

## 升级关闭超时：单独保留的未确认风险

**OBS-XREG-SHUTDOWN保持UNCONFIRMED_RETAINED。** 原P013发生一次离屏应用关闭超过30秒；当时未记录具体关闭阶段，产品根因未确认。不能把后续未再现写成“已修复”。
完整后端重跑结束后，两个全新工作区独立复验均通过；可执行文件、ASAR、运行时清单、后端四项摘要与原候选组件一致。两次均完成V50→V53、身份/任务/文件SHA保持、冷快照及正常退出。最终P013按复验通过登记，原FAIL不删除。

|复验|历史包关闭|候选包关闭|退出与数据|
|---|---:|---:|---|
|1|0.686秒|0.769秒|exitCode=0，数据与文件SHA保持|
|2|0.970秒|0.492秒|exitCode=0，数据与文件SHA保持|

该风险不并入108个已修复产品Bug，不增加3090项分母，也不覆盖47条原观察。后续若再出现，应保留版本、并行负载、关闭阶段和退出日志，先定位再决定是否登记产品Bug。

[单独风险说明及证据](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/UNCONFIRMED-RISK.md)

## 最新工程验证与覆盖边界

|验证|最新已执行结果|来源|
|---|---|---|
|后端完整重跑|951通过，0失败/错误/跳过；日志识别MySQL726、H2 17、无数据库日志208|[执行汇总](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/backend-existing-verification.json)|
|前端完整|761/761通过，70个文件|[执行汇总](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/frontend-existing-verification.json)|
|桌面Node|736项：735通过、0失败、1项Windows专项跳过|[执行汇总](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/desktop-existing-verification.json)|
|Lint与构建|退出0；2条既有Vue属性类型警告保留；原生V53/626文件完整性验证通过|[构建与运行时](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/prepare-existing-verification.json)|
|真实包跨模块|20条链路通过；Mac arm64后台/离屏验证|[跨模块证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/packaged-cross-module.json)|
|连续窗口|真实30.004分钟，31轮/124项探针，非预期错误0|[采样证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/observations.json)|

工程套件彼此可能覆盖同一规则，不能把951+761+735加到3090中。连续窗口与其他独立验收存在时间重叠，资源/延迟不作为空闲基线；没有多日稳定性、业务容量或全平台发行认证结论。

## 交付、清理与审核

本次只汇总已有证据并更新审核/关闭说明，没有新增执行、产品代码修改、安装、部署或Git提交。最近横向回归已清理自有进程、容器/卷/网络、测试数据库、临时包、原始日志及previous运行时，保留当前V53；3个共享容器元数据前后不变，用户工作区和无关改动保持。

[最近一次隔离清理证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/cleanup-verification.json) · [本报告来源及SHA-256索引](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-28/SOURCE-INDEX.json)

**整体测试报告提交审核；不开始新模块，也不将观察、阻塞和未确认风险自动关闭。**
