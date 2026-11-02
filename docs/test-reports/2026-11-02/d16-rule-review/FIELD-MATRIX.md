# D16 字段矩阵（待审核）

仅下表字段在本提案范围内。字段类型、上下限及默认值来自当前代码；拟议类型政策来自D16五张规则卡。当前未执行动态测试。

|模块|字段|业务类型|范围与关联条件|缺失/null处理|来源|
|---|---|---|---|---|---|
|接口自动化|steps[type=WAIT].config.delayMillis|INTEGER|0..600000毫秒|必填，缺失/null拒绝|C01,C02,C03|
|Mock|priority|INTEGER|0..1000|省略/null为0|C04,C05|
|Mock|expiresInDays|INTEGER|1..365天|省略/null为永久有效|C04,C05|
|Mock|responseStatus|INTEGER|200..599|省略/null为200|C04,C05|
|Mock|requestQuery.* / requestHeaders.* / responseHeaders.* / requestBody / responseBody|STRING|保留原长度、Header和正文匹配规则|保持各字段原空值语义；不把null字符串化|C04,C05|
|性能测试|threadGroups[].threadCount|INTEGER|1..100000|必填|C06,C07|
|性能测试|threadGroups[].rampUpSeconds|INTEGER|0..86400秒|必填；STEPPED_RAMP要求0|C06,C07|
|性能测试|threadGroups[].durationSeconds|INTEGER|10..86400秒|必填|C06,C07|
|性能测试|threadGroups[].stepCount / stepIntervalSeconds|INTEGER|阶段2..min(threadCount,20)，间隔1..86400；总时长<=86400|仅STEPPED_RAMP必填；其他模型不允许配置|C06,C07|
|性能测试|threadGroups[].targetTps / p95ThresholdMillis / expectedStatusCode / rendezvousUsers|INTEGER|TPS 1..1000000且阶梯TPS>=阶段；P95 1..600000；状态100..599；集合点2..threadCount且阶梯不支持|省略/null无对应设置，既有关联约束保持|C06,C07|
|性能测试|threadGroups[].errorThresholdPercent|DECIMAL|0..100，最多两位小数；不得改为整数、不得静默四舍五入|省略/null无阈值|C06,C07|
|性能测试|threadGroups[].requestHeaders.* / queryParameters.* / requestBody / responseContains / dataSetCsv|STRING|保留原大小/模板/CSV及方法正文限制|保持原空值语义|C06,C07|
|Web UI自动化|viewportWidth / viewportHeight|INTEGER|宽320..3840，高320..2160|省略/null分别1440/900|C08,C09|
|Web UI自动化|steps[].timeoutMillis|INTEGER|WAIT 1..60000；其他100..60000毫秒|WAIT必填；其他省略/null沿用Runner默认30000毫秒|C08,C09,C18|
|Web UI自动化|execute.timeoutSeconds|INTEGER|10..3600秒|省略/null为300|C08,C09|
|Web UI自动化|variables.* / steps[].value / steps[].expectedText|STRING|保持变量名、大小、换行和步骤所需值规则|沿用既有：变量null可归空串；步骤必填按类型判断|C08,C09|
|移动端自动化|steps[].timeoutMillis|INTEGER|1..60000毫秒|省略/null为30000|C10,C11|
|移动端自动化|execute.timeoutSeconds|INTEGER|10..3600秒|省略/null为300|C10,C11|
|移动端自动化|variables.* / steps[].value / steps[].expectedText|STRING|保持变量名、大小及步骤语义|变量null拒绝；步骤值沿用原规则|C10,C11|
|质量报告与任务|taskUpdate.progress|INTEGER|0..100；合法输入后QUEUED归0、SUCCEEDED归100|推荐改为必填；需确认D16-NULL|C12,C13|
|质量报告与任务|qualitySchedule.periodDays|INTEGER|1..90天|必填|C14,C15|

所有数值先检查原始类型、精确值及范围；不得用parseInt、intValue/longValue或double舍入后再判合法。按I-A保留精确等值数字写法；N-A拒绝数字字符串。STRING仅表示禁止未声明的自动字符串化，null/空串、trim、模板和长度仍按原字段业务规则。

## 范围限制

- 清单是21组路径，并非21条执行用例；同组多个字段均需展开正式用例。
- 错误率是DECIMAL，不应用整数字段策略；小数精度和范围仍沿用原规定。
- expectedVersion及原428/409、通用API步骤config、Appium设备capabilities和D19跨平台定位不扩大到本次。
- 管理API、导入/同步的同一路径必须在转换前判断类型；不可只在DTO已成为Integer/String后检查，从而失去原输入信息。
- 非有限JSON要用原始字节；JSON.stringify(NaN)变成null不能算NaN实测。

## 来源锚点

以下为静态代码/历史报告锚点，文件SHA及摘录见[SOURCE-INDEX.json](SOURCE-INDEX.json)。
- H01：[RULE-DECISIONS.json](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-29/RULE-DECISIONS.json:230)。
- H02：[OBSERVATION-DETAILS.md](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-29/OBSERVATION-DETAILS.md:229)。
- C01：[ApiAutomationScenarioController.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/apiautomation/ApiAutomationScenarioController.java:88)。
- C02：[ApiAutomationScenarioService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/apiautomation/ApiAutomationScenarioService.java:261)。
- C03：[runner.js](/Users/limingbo/Documents/codeXProject/testHub/services/api-runner/src/runner.js:399)。
- C04：[MockManagementController.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/mock/MockManagementController.java:113)。
- C05：[MockService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/mock/MockService.java:135)。
- C06：[PerformanceTestPlanController.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/performance/PerformanceTestPlanController.java:85)。
- C07：[PerformanceTestPlanService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/performance/PerformanceTestPlanService.java:150)。
- C08：[UiAutomationController.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/uiautomation/UiAutomationController.java:134)。
- C09：[UiAutomationService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/uiautomation/UiAutomationService.java:395)。
- C10：[MobileAutomationController.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/mobileautomation/MobileAutomationController.java:81)。
- C11：[MobileAutomationService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/mobileautomation/MobileAutomationService.java:356)。
- C12：[ExecutionTaskController.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/execution/ExecutionTaskController.java:59)。
- C13：[ExecutionTaskService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/execution/ExecutionTaskService.java:58)。
- C14：[QualityReportScheduleController.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/reporting/QualityReportScheduleController.java:76)。
- C15：[QualityReportScheduleService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/reporting/QualityReportScheduleService.java:191)。
- C16：[ApiExceptionHandler.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/shared/api/ApiExceptionHandler.java:49)。
- C17：[runner.js](/Users/limingbo/Documents/codeXProject/testHub/services/appium-runner/src/runner.js:60)。
- C18：[runner.js](/Users/limingbo/Documents/codeXProject/testHub/services/playwright-runner/src/runner.js:125)。
