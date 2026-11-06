# TestHub最终整体测试报告 R2（含已审核CI修复结果）

更新日期：2026-11-06。原最终整体报告和本次CI修复、复验结果均已获用户审核；本R2按授权汇总并提交主分支。**本次仅更新报告及证据导航，不新增产品测试执行，不恢复D26或12项环境补验，不修改产品代码。** R1原报告、统计、台账和哈希清单保持原字节，当前报告继承其各模块结果并单独补充CI结果。

## 最终结论与统计口径

**原16个主模块、19个验收范围均已完成约定范围测试并审核；原3090项检查统计保持：正例1643、反例1447，最终3031通过、0未关闭失败、47观察、12环境阻塞。47项观察现为45项结果已审、D26两项跳过并保留SPEC；12项环境补验本轮跳过。升级关闭超时独立保留未确认。**

已登记产品Bug原108项全部修复验证，后续新增BUG-D23-001也已修复并审核；按两份登记台账去重共109项，已确认未修复Bug为0。观察处置不自动当作新增Bug。正式资产原1项和后续收尾1类引用问题均已修复，分别保留登记。

3090为首轮模块检查项次，不是全系统互斥场景或覆盖率；不把后续用例、测试方法、工程套件、D26请求次数或16项文档核验相加。45项结果审核也不回填成首轮新增通过。03B/03C原11条历史失败由后续定向证据关闭，原整批快照保留，不声称整批重跑。

## 各模块最终结果

下表“首轮处置”依次为通过/观察/环境阻塞；各行首轮未关闭失败均为0。“后续观察”依次为已审/跳过，针对原47项的当前处置。范围03A—03D属于一个主模块。

|范围|模块及首轮报告|正/反|首轮处置|原Bug已修复|后续观察已审/跳过|模块审核|
|---|---|---:|---:|---:|---:|---|
|01|[登录、注册与会话](../../../../output/qa-20261023/module-01-auth/TEST-REPORT.md)|46/59|105/0/0|4|0/0|已审核|
|02|[工作台](../../../../output/qa-20261023/module-02-dashboard/TEST-REPORT.md)|74/29|103/0/0|4|0/0|已审核|
|03A|[项目管理与成员权限](../../../../output/qa-20261023/module-03a-projects/TEST-REPORT.md)|65/55|120/0/0|4|0/0|已审核|
|03B|[项目环境、参数与密钥](../../../../output/qa-20261023/module-03b-environments/TEST-REPORT.md)|62/70|129/3/0|3|3/0|已审核|
|03C|[文件管理与操作日志](../../../../output/qa-20261024/module-03c-files-audit/TEST-REPORT.md)|57/52|107/2/0|5|2/0|已审核|
|03D|[账号管理与系统参数](../../../../output/qa-20261024/module-03d-accounts-config/regression-r1/TEST-REPORT.md)|75/64|137/2/0|4|2/0|已审核|
|04|[功能测试资产与治理](../../../../output/qa-20261024/module-04-functional-assets/regression-r1/TEST-REPORT.md)|91/68|159/0/0|6|0/0|已审核|
|05|[测试计划与交付](../../../../output/qa-20261024/module-05-plans-delivery/regression-r1/TEST-REPORT.md)|104/77|179/2/0|11|2/0|已审核|
|06|[接口设计与导入](../../../../output/qa-20261024/module-06-api-design/regression-r1/TEST-REPORT.md)|112/78|188/2/0|9|2/0|已审核|
|07|[接口调试](../../../../output/qa-20261024/module-07-api-debug/regression-r1/TEST-REPORT.md)|122/95|211/6/0|10|6/0|已审核|
|08|[接口自动化](../../../../output/qa-20261026/module-08-api-automation/regression-r1/TEST-REPORT.md)|117/97|211/3/0|12|3/0|已审核|
|09|[Mock](../../../../output/qa-20261026/module-09-mock-regression/TEST-REPORT.md)|90/72|160/2/0|8|2/0|已审核|
|10|[性能测试](../../../../output/qa-20261026/module-10-performance-regression/TEST-REPORT.md)|125/89|212/2/0|4|2/0|已审核|
|11|[Web UI自动化](../../../../output/qa-20261027/module-11-web-ui-regression/TEST-REPORT.md)|116/104|217/3/0|6|3/0|已审核|
|12|[移动端自动化](../../../../output/qa-20261027/module-12-mobile-regression/TEST-REPORT.md)|77/102|173/2/4|8|2/0|已审核|
|13|[质量报告与任务](../../../../output/qa-20261027/module-13-quality-tasks-regression/TEST-REPORT.md)|90/68|151/7/0|5|7/0|已审核|
|14|[通知、集成、插件与运维](../../../../output/qa-20261027/module-14-notifications-extensions-operations-regression/TEST-REPORT.md)|86/101|180/7/0|5|7/0|已审核|
|15|[桌面工作区与跨端](../../../../output/qa-20261028/module-15-desktop-workspace-cross-end/TEST-REPORT.md)|82/136|213/1/4|0|1/0|已审核|
|16|[横向回归](../../../../output/qa-20261028/module-16-cross-regression-fixed/TEST-REPORT.md)|52/31|76/3/4|0|1/2|已审核|
|合计|16主模块 / 19范围|1643/1447|3031/47/12|108|45/2|约定范围已审|

D26基线及真实设备/跨平台/发行补验未完成，不因模块报告审核而转为通过。各模块的当前观察处置见[47项台账](../../2026-11-05/final-overall-report-r1/OBSERVATION-STATUS.json)；结构化逐模块结果见[模块明细](../../2026-11-05/final-overall-report-r1/MODULE-RESULTS.json)。

## 后续修复与回归结果

|批次/模块|观察结果已审|最终用例或方法结果|报告/审批|
|---|---:|---|---|
|10项可先修复|10|正式用例56：正16/反40，56通过|[报告](../../2026-10-29/direct-fixes-r1/REPORT.md) / [审批](../../2026-10-29/direct-fixes-r1/REVIEW-APPROVAL.json)|
|6项P1观察|6|正式用例72：正29/反43，72通过|[报告](../../2026-10-29/p1-fixes-r1/REPORT.md) / [审批](../../2026-10-29/p1-fixes-r1/REVIEW-APPROVAL.json)|
|首批P2|8|正式用例63：正30/反33，63通过|[报告](../../2026-10-30/p2-fixes-r1/FINAL-REPORT.md) / [审批](../../2026-10-30/p2-fixes-r1/REVIEW-APPROVAL.json)|
|第二批P2|8|正式用例45：正25/反20，45通过|[报告](../../2026-10-30/p2-round2-fixes-r1/FINAL-REPORT.md) / [审批](../../2026-10-30/p2-round2-fixes-r1/REVIEW-APPROVAL.json)|
|D16六模块|6|189正式用例（正83/反106）及48测试方法（纯正11/纯反18/混合19），分别全通过|[报告](../../2026-11-03/d16-overall-summary-r1/OVERALL-TEST-REPORT.md) / [审批](../../2026-11-03/d16-overall-summary-r1/REVIEW-APPROVAL.json)|
|D19移动定位器|1|18测试方法：纯正8/纯反8/混合2，18通过；未连真机|[报告](../../2026-11-03/d19-mobile-locator-fixes-r1/FINAL-REPORT.md) / [审批](../../2026-11-03/d19-mobile-locator-fixes-r1/REVIEW-APPROVAL.json)|
|D08/D07测试计划|2|原16测试方法通过；隔离MySQL复跑10方法通过（不新增分母），影响面98/98|[报告](../../2026-11-03/d08-d07-plan-mysql-r2/FINAL-REPORT.md) / [审批](../../2026-11-03/d08-d07-plan-mysql-r2/REVIEW-APPROVAL.json)|
|D24桌面预览|1|24场景：纯正13/纯反8/混合3，24通过；离屏4/4|[报告](../../2026-11-03/d24-preview-fixes-r1/FINAL-REPORT.md) / [审批](../../2026-11-03/d24-preview-fixes-r1/REVIEW-APPROVAL.json)|
|D06账号搜索|1|16测试方法：纯正7/纯反7/混合2，16通过|[报告](../../2026-11-04/d06-account-search-fixes-r1/FINAL-REPORT.md) / [审批](../../2026-11-04/d06-account-search-fixes-r1/REVIEW-APPROVAL.json)|
|D21报告参数|1|正式用例17：正8/反9，17通过|[报告](../../2026-11-04/d21-report-filter-fixes-r1/FINAL-REPORT.md) / [审批](../../2026-11-04/d21-report-filter-fixes-r1/REVIEW-APPROVAL.json)|
|D23通知集成插件|1|正式用例32：正13/反19，32通过（原20＋补充12）|[报告](../../2026-11-04/d23-json-policy-fixes-r2/FINAL-REPORT.md) / [审批](../../2026-11-04/d23-json-policy-fixes-r2/REVIEW-APPROVAL.json)|

上述11个批次合计对应45个原观察结果。D16六模块的189条正式用例与48个测试方法分列，不能称为237条正式用例；数据库复跑不重复登记。冻结执行文件中的“待审”是生成时状态，当前状态按其独立结果审批文件判定。D08/D07先前临时审批服务阻塞已由隔离MySQL成功执行解除，不再列为当前未完成项。

## Bug修复情况

|登记层次|数量|最终结果|
|---|---:|---|
|原首轮产品Bug|108|均FIXED_VERIFIED；原编号、分模块修复数及回归证据保留|
|后续新增BUG-D23-001|1|同类型同名企业适配器重复创建/改名从500改为受控409，结果已审|
|去重已登记产品Bug|109|全部修复验证或审核，未修复已确认Bug为0|

BUG-D23-001最终32条正式用例全部通过；隔离H2共1374项：1371通过、3项MySQL专用跳过；MySQL影响面65/65，前端影响面67/67。两数据库并发创建/改名各3轮均一个200、一个409，原数据及密文/审计边界保持。详见[复现及回归](../../2026-11-04/d23-json-policy-fixes-r2/REPRODUCTION-AND-REGRESSION.md)和[109项登记台账](../../2026-11-05/final-overall-report-r1/PRODUCT-BUG-REGISTER.json)。这些工程套件和迭代验证不加入原3090项。

## 测试资产修复情况

|资产|最终修复与验证|统计口径|
|---|---|---|
|QA-XREG-TEST-001|Mock重名断言改为公开409及原快照保持；MockSyncFlowTests 9/9通过，并有独立HTTP复核|原正式资产1项，冻结保留|
|QA-CLOSEOUT-001|跨日期复制台账的9处审批相对引用改为明确仓库路径，涉及2个目标；72处当前证据引用可打开，收尾16项核验全通过|后续收尾资产1类；已审核|
|其他执行维护|D26复制保留相对链接、无正文DELETE携带CSRF、版本/夹具更新、清理Promise API及报告结果登记时序均按来源完成维护|不重复登记为上述2项或产品Bug|

当前两类历史已登记资产均已修复。本次CI还修复1项时区兼容断言资产，单独列示，不回写历史2项资产台账。旧冻结快照不重写，当前台账提供修正后的引用。详细复现、9处变更和收尾验证见[资产清单](../../2026-11-05/final-overall-report-r1/TEST-ASSET-REGISTER.json)及[已审核收尾报告](../../2026-11-05/nonblocking-closeout-r1/FINAL-REPORT.md)。

## 已审核CI修复及最终通过结果

原提交 [b522598](https://github.com/MrLimingbo/TestHub/commit/b522598a641516bd46eb10ac4e55684d2ed01304) 的 [verify运行41981648534](https://github.com/MrLimingbo/TestHub/actions/runs/41981648534) 中，Web成功；后端1374项中有1项失败（0错误/0跳过），安全门发现3项HIGH依赖漏洞。修复分两次提交，原失败记录保留，不能把原提交标为通过。

|修复类别|问题及最终处理|提交/验证|
|---|---|---|
|CI测试资产：时区断言|UTC下迁移测试把TIMESTAMP转为JVM本地日期，期望2020-01-01却读为2019-12-31；改为精确数据库LocalDateTime字段及迁移前后Instant双重断言，保留旧密文、安全版本、保留策略和幂等检查；不改生产迁移或全局时区|[92385d2](https://github.com/MrLimingbo/TestHub/commit/92385d2116c0b769b6aaf6231a482e577aa51a99)；UTC修复前3项中1失败，修复后UTC和Asia/Shanghai各3/3通过；最终CI兼容类3/3通过|
|Java依赖：3项HIGH|Guava CVE-2026-102554，以及Snappy CVE-2026-108106、CVE-2026-93451；在既有dependencyManagement锁定Guava33.8.0-jre、Snappy1.1.10.12，依赖树及可执行JAR已核实|92385d2；最终JAR扫描在既有门槛下0发现|
|基础镜像：1项CRITICAL|首轮修复后运行41989378859又检出Brotli CVE-2026-111497；更新同系列distroless固定摘要，核实amd64包为Debian修复版1.1.0-2+deb13u1；保留Java21、Debian13及非root运行|[cec6df1](https://github.com/MrLimingbo/TestHub/commit/cec6df1dd68c67fd6279d785c19a8c84d64a5e6f)；最终OS镜像扫描在既有门槛下0发现|

**最终已审核候选：cec6df1dd68c67fd6279d785c19a8c84d64a5e6f。** [verify运行41990603365](https://github.com/MrLimingbo/TestHub/actions/runs/41990603365) 于2026-11-05 16:10:15（北京时间）完成，结论success；2026-11-06更新报告时已再次核对三个作业均成功。

|GitHub作业|测试/构建与安全结果|最终状态|
|---|---|---|
|[web](https://github.com/MrLimingbo/TestHub/actions/runs/41990603365/job/127121658436)|950项前端测试、15项启动测试通过；lint 0错误、2条既有Vue属性警告；构建成功|success|
|[platform-api](https://github.com/MrLimingbo/TestHub/actions/runs/41990603365/job/127121658588)|隔离MySQL/Redis/MinIO全量1374/1374通过，0失败/错误/跳过；原兼容类3/3通过，构建成功；本轮自有容器、网络、卷和临时文件均清理|success|
|[release-gate](https://github.com/MrLimingbo/TestHub/actions/runs/41990603365/job/127121658202)|后端/前端镜像构建、安全扫描通过，SBOM上传；后端OS、JAR及前端OS在HIGH/CRITICAL门槛和既有ignore-unfixed策略下均0发现|success|

没有放宽测试或安全门槛，没有新增漏洞忽略项。4项CVE安全修复及1项CI测试资产修复作为本次工程补充单列，不补登记成原产品Bug或新增功能正反例：原109项已登记产品Bug、2项历史资产及3090项首轮分母保持不变。跨时区、跨数据库的重复执行不重复登记为新设计用例，H2的3项MySQL专用跳过也不属于12项历史环境补验。

[结构化CI结果](CI-RESULTS.json) · [用户CI结果审核及发布授权](CI-RESULT-APPROVAL.json) · [原失败和修复方案](../../2026-11-05/ci-b522598-fixes-r1/BEFORE-CI-RESULTS.json) · [补充基础镜像修复](../../2026-11-05/ci-b522598-fixes-r1/FOLLOWUP-SECURITY-FINDING.json)。

以上成功结果属于cec6df1。当前仅报告与导航更新的文档提交是其后继提交；其新触发CI应按独立运行判断，不把既有成功结果写成新文档提交的执行结果。

## D26：跳过，两个完整有效窗口未取得

**状态：SKIPPED_BY_USER_SPEC_RETAINED；有效完整窗口0/2。** D26-R资源观察与D26-L业务/健康延迟观察分别保留SPEC，不判资源预算、泄漏、SLA、容量或长期稳定性达标。

|批次|实际执行及结论|
|---|---|
|R1|正式尝试1次，采集约27分30秒后因外部CPU污染作废；1645次业务和108条探针无错误，全部样本保留，不能代替有效窗口|
|R2|曾通过调度准入、6类预检成功；正式准入等待30分钟未取得连续120秒低负载，正式尝试0次|
|R3|调度待机保留1183条CPU记录，最长连续低负载约115秒；用户要求跳过后停止，正式尝试0次|

两项基线不计通过。原阈值、100次序列和15+120+15分钟窗口没有放宽；未拼接短窗。自有进程、候选目录、测试数据及精确QA安全存储项已按记录清理，自动跟进已删除，本次不重启。见[D26状态与历史](../../2026-11-05/final-overall-report-r1/D26-SKIPPED.json)、[R3停止记录](../../2026-11-05/d26-baseline-r3/STATUS.json)。

## 12项环境补验：本轮全部跳过

历史环境原因及记录按原字节保留，当前执行处置为SKIPPED_THIS_ROUND_BY_USER，不计通过；共12条、7组环境条件。

|原用例/阻塞ID|模块|尚未执行的真实环境验收|当前处置|
|---|---|---|---|
|12/D001 · BLOCK-12-D001|移动端自动化|Android设备七种步骤及PNG|本轮跳过|
|12/D002 · BLOCK-12-D002|移动端自动化|iOS设备七种步骤及PNG|本轮跳过|
|12/D003 · BLOCK-12-D003|移动端自动化|真实设备断开与授权错误|本轮跳过|
|12/D004 · BLOCK-12-D004|移动端自动化|真实设备超时取消与会话释放|本轮跳过|
|15/F016 · BLOCK-15-F016|桌面工作区与跨端|大小写重名拒绝|本轮跳过|
|15/X001 · BLOCK-15-X001|桌面工作区与跨端|Intel Mac原生运行|本轮跳过|
|15/X002 · BLOCK-15-X002|桌面工作区与跨端|Windows原生运行与恢复|本轮跳过|
|15/X003 · BLOCK-15-X003|桌面工作区与跨端|正式安装及Gatekeeper首启|本轮跳过|
|16/Z001 · BLOCK-16-Z001|横向回归|Intel Mac原生包及运行|本轮跳过|
|16/Z002 · BLOCK-16-Z002|横向回归|Windows原生安装与运行|本轮跳过|
|16/Z003 · BLOCK-16-Z003|横向回归|发行者签名与公证|本轮跳过|
|16/Z004 · BLOCK-16-Z004|横向回归|Finder/Gatekeeper干净机器安装首启|本轮跳过|

环境组为Android、iOS、大小写敏感文件系统、Intel Mac、Windows、发行签名/公证、干净机器Finder/Gatekeeper首启。移动真机执行仍为0；协议模拟、受控Runner或临时MySQL/Redis/S3不解除这些补验。未安装新环境、替换用户应用或宣称发行资格。[完整原因清单](../../2026-11-05/final-overall-report-r1/ENVIRONMENT-BLOCKERS.json) / [当前跳过登记](../../2026-11-05/final-overall-report-r1/SKIPPED-WORK.json)。

## 升级关闭超时：独立未确认风险

**OBS-XREG-SHUTDOWN = UNCONFIRMED_RETAINED，rootCauseConfirmed=false。** 原P013曾发生一次离屏应用关闭超过30秒，具体关闭阶段及根因未确认，不能标为已修复。历史两次同候选复验虽通过（历史/候选关闭0.686/0.769秒、0.970/0.492秒），原始风险仍保留。

本次及收尾没有新增升级关闭调查或复验。普通退出、停止D26待机及清理不构成升级关闭复验。该风险不并入109项已修复Bug、47项观察或12项环境补验，不增加用例分母。见[独立原始记录](../../2026-11-05/final-overall-report-r1/UNCONFIRMED-RISK.json)。

## 工程验证与交付边界

|验证层|最近相关已执行证据|限制|
|---|---|---|
|完整隔离H2/JAR|本次CI修复后UTC全量1374项：1371通过、0失败/错误、3项MySQL专用条件跳过；JAR构建成功，测试工作区已清理|执行在92385d2对应Java/测试修复上；最终cec6df1继续更新基础镜像，未改Java测试|
|完整隔离MySQL|cec6df1 GitHub全量1374/1374，0失败/错误/跳过，构建成功|全量后端套件，不代表所有产品界面或12项真实环境补验|
|前端与启动|cec6df1 GitHub前端950/950，启动15/15，lint及build成功|2条既有Vue属性警告保留；工程套件不并入3090项历史分母|
|供应链安全门|cec6df1后端/前端镜像构建、SBOM及扫描通过|既有HIGH/CRITICAL和ignore-unfixed策略；不声称无全部等级漏洞或无风险|
|桌面|此前D24全量755通过、1项Windows专项跳过；真实离屏4/4|本次未新增桌面执行，不解除Windows、正式安装或发行环境补验|
|交付收尾|此前16项文档/证据检查通过，15份清单涉及387份冻结文件SHA一致，72处当前引用可打开|原收尾已审，不当作本次新增产品测试|

R1曾完成12项汇总文档校验、418个冻结文件条目、92份来源及65处本地链接核对，保留当时证据；本R2只核对新增报告结构、来源哈希、链接、CI对应关系、历史统计及跳过/风险状态，不将原核验写成本次重复执行。详见[本次来源索引](SOURCE-INDEX.json)和[本次文档核验](VALIDATION.json)。

各模块历史功能证据跨日期、跨候选；新增CI证明cec6df1对应工程套件通过，不声称所有历史功能检查均在同一最新候选完整重跑，也不作全平台发行、资源预算/泄漏/SLA达标或无风险结论。

## 当前交付状态

最终整体报告R1及CI修复验证结果已审核，本R2按用户授权更新并提交、推送到GitHub主分支。当前范围无其他未执行的非阻塞功能模块。D26两项SPEC基线及12项环境补验继续跳过、不计通过、不恢复执行；升级关闭超时继续独立保留UNCONFIRMED_RETAINED，rootCauseConfirmed=false，本次未调查或复验。原审核快照及历史分母不回写，不进入下一模块。

[原R1报告](../../2026-11-05/final-overall-report-r1/OVERALL-TEST-REPORT.md) · [原整体审核](../../2026-11-05/final-overall-report-r1/REVIEW-APPROVAL.json) · [本次CI结果审核和发布授权](CI-RESULT-APPROVAL.json)。
