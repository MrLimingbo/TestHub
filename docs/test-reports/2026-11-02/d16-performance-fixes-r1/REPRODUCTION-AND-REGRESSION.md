# OBS-10-E011：详细复现与最终回归

优先级P2，D16五项A已批准。状态：**修复完成，等待性能模块结果审核**。以下为同一观察的多个触发入口，不登记为多个新增产品Bug。

## 公共准备

1. 在全新自有隔离工作区登录，有目标项目写权限。所有projectId/planId/UserId必须来自该账号的测试资产。
2. 写请求携带登录会话，并先GET `/api/v1/auth/csrf`获取当次CSRF头名和token。
3. 使用`POST /api/v1/projects/{projectId}/performance/plans`及下面基准JSON；读取列表GET同地址，读取详情GET `.../plans/{planId}`。
4. 请求中的小数、大指数、NaN/Infinity必须用原始JSON文本发送。不要先经过JavaScript Number或JSON.stringify，避免微小小数被舍入、非有限值变null后误算为实测。

```json
{
  "name": "QA Plan",
  "description": "",
  "status": "READY",
  "threadGroups": [{
    "name": "QA Group",
    "targetUrl": "https://qa.example.test/qa",
    "loadModel": "RAMP_UP",
    "threadCount": 10,
    "rampUpSeconds": 0,
    "durationSeconds": 10,
    "targetTps": 10,
    "p95ThresholdMillis": 500,
    "errorThresholdPercent": 2.50,
    "requestMethod": "POST",
    "requestHeaders": {},
    "queryParameters": {},
    "requestBody": "{\"n\":1}",
    "expectedStatusCode": 200,
    "responseContains": "001",
    "dataSetCsv": null,
    "rendezvousUsers": null
  }]
}
```

为避免同名约束干扰，每次正向创建用独立名称；每个反例只改目标字段，其余配置保持合法。不向真实目标发负载。

## 一、整数小数保存后被截断

1. 基准请求只改threadGroups[0].threadCount为1.5，发送POST。
2. GET详情核对threadCount；再对其他整数逐字段使用合法范围附近的小数，例如durationSeconds=10.5、expectedStatusCode=200.5。
3. 旧行为：Jackson绑定Integer时丢弃小数部分，非法输入可保存为整数。
4. 最终结果：原始小数和1.0000000000000001等微小小数400，不新增计划、线程组或成功审计。精确等值的10、10.0、1e1仍保存为10。
5. 九类字段均有等值与边界案例：threadCount 1..100000、rampUpSeconds 0..86400、durationSeconds 10..86400、stepCount 2..min(threadCount,20)、stepIntervalSeconds 1..86400且总时长受限、TPS 1..1000000、P95 1..600000、状态100..599、集合点2..threadCount。
6. 阶梯字段的测试先设loadModel=STEPPED_RAMP、rampUpSeconds=0、合法阶段/间隔、集合点null；不能把非阶梯不允许阶段字段的拒绝误算为类型拒绝。

证据：01–22。修复前fractions循环首失败证明截断入口；首失败后的各字段/微小变体不冒称已逐个跑完，最终所有循环变体已执行。

## 二、数字字符串和错误率数字字符串被接受

1. 将threadCount改为JSON字符串"10"或" 10 "，发送POST并读取。
2. 将errorThresholdPercent改为JSON字符串"2.50"，重复。
3. 旧行为：数字字符串可隐式解析为Integer或BigDecimal并保存。
4. 最终结果：均400，无业务写入；九整数与错误率都拒绝字符串/布尔/数组/对象。真实null、缺失则按各字段必填或可选语义处理。
5. 错误率正向发送数值2.50，仍精确保存2.5，不取整；2.501及2.500按既有scale>2规则400，不做四舍五入。0/100可用，-0.01/100.01拒绝；null/省略仍无阈值。

证据：20–24、30–34；真实JAR/HTTP覆盖原始类型和百分比精度。

## 三、条件、正文和断言值自动字符串化

1. 对requestHeaders或queryParameters分别改为`{"X-Code":1}`、`{"X-Code":true}`；对requestBody、responseContains分别提交JSON数值或布尔。
2. 旧行为：其中数值/布尔可被转成"1"、"true"文本，改变条件含义。
3. 最终结果：列明文本字段及Map值要求JSON字符串，数字/布尔/容器400；dataSetCsv同样在类型边界拒绝，原CSV规则继续检查内容。
4. 正向对照：query的`{"id":"001","flag":"false","literal":"null","blank":""}`、显式正文字符串`"{\"n\":1}"`、合法CSV和模板引用仍可保存。正文字符串内部的数字不当作字段类型错误。
5. 原空值规则保持：Header内null归空串且trim，Query内null归空串并保持其他空白；可选正文、断言、CSV的null/空白仍为null。GET/HEAD非空正文、禁用认证Header、无定义模板仍按旧业务规则拒绝。

证据：35–42。原已拒绝的容器/坏CSV/超范围值不是新缺陷，不把本次新增的验证次数当作新增Bug。

## 四、非法编辑和后续线程组留下变更

1. 先创建合法计划并记录id、version=1及完整线程组；需要冻结验证时，在自有隔离服务中创建一条排队执行，不启动Runner负载。
2. `PUT .../plans/{planId}`提交`{"expectedVersion":1,"plan":{基准计划，threadCount改为1.5}}`。
3. 旧行为：被转换为整数的非法值可更新计划和版本。
4. 最终结果：400，原版本、线程组内容、审计和旧manifest不变；合法整数编辑版本+1，旧manifest仍不改变。缺失/null版本400，过期版本409，未扩大expectedVersion类型政策。
5. 创建两个不同名称的线程组，前组合法、后组threadCount=1.5，发送POST。最终400，计划和所有线程组均未写入；错误details可定位`threadGroups[1].threadCount`或对应字段。

证据：43–45、53、56。

## 五、迁移和同步绕过普通保存校验

迁移：

1. 源隔离项目准备合法非阶梯和阶梯计划；GET `/api/v1/projects/{sourceProjectId}/performance/transfer`导出完整snapshot。
2. 保留version/plans/threadGroups等完整协议，仅在原始文本中改一个目标数值为1.5、数字字符串，或条件值/正文为非字符串。
3. 用新UUID requestKey、目标expectedUserId和该snapshot组成`{"requestKey":"{UUID}","expectedUserId":"{targetUserId}","snapshot":{...}}`，POST目标项目同类transfer地址。
4. 旧行为：解码允许转换后再进入规范化/业务检查；部分坏资料可通过。
5. 最终结果：400，无计划、线程组、成功回执或审计。合法精确等值整数和真小数正常导入，新计划仍DRAFT，执行历史不复制；相同请求重放保持幂等。

同步：

1. GET源项目`.../performance/sync?expectedUserId={sourceUserId}`取得完整规范化items；GET目标同类地址取得revision。
2. 按原协议组成requestKey、目标expectedUserId、expectedRevision、operations；每项含新UUID operationId和完整item。只改目标字段原始JSON类型。
3. POST目标`.../performance/sync`。最终错误资料400、整批未写入；合法等值整数及原scale百分比可同步，旧目标id/status保持且版本+1，新目标DRAFT。
4. 旧revision仍409，无成功写入；重放返回原回执，不重复改版本或资产。

证据：46–49、54–55。后端对迁移和同步各执行54组非法资料变体：九整数各4类、错误率数字字符串1类、两类Map各4类、三类文本各3类；合法整数原写法/.0/e0三组覆盖完整资料。所有变体仍属于原正式用例，不重复计数。

## 六、旧非法计划仍可新入队

本项只对自有测试数据库做兼容夹具注入，不能在用户工作区或共享数据库操作。

1. 创建一条合法READY计划和一条自有排队执行，记录旧manifest和表计数。
2. 在该临时数据库将该计划线程组thread_count设为0，再调用新执行入口。
3. 旧行为：仅检查READY/线程组非空，非法旧定义仍能生成新执行、manifest和Outbox。
4. 最终结果：新入队400，三类执行资产与审计计数不增，旧manifest和排队状态不变。
5. 恢复线程数10，将该临时记录query_parameters原始JSON设为`{"id":123}`。普通详情仍沿旧读取契约返回"123"，不批量重写原记录；再次新入队时400。
6. 原始Map检查发生在新入队专用路径，避免先被Repository读取为Map<String,String>后丢失类型信息。它不改变原详情/历史读取，也不修改旧记录或取消既有运行。

证据：50–51。原始数值输入如果早已被保存成整数/字符串，无法反推恢复；本轮不尝试批量还原。

## 七、页面保留原始文本并定位错误

1. 打开性能测试→新建压测计划，在并发线程输入1.5、1.0000000000000001或100001，点击保存。
2. 旧页面未阻止非法整数提交；最终保留原文本并显示线程组/字段错误，业务POST为0。响应状态等其他整数同样验证。
3. 错误率输入2.501点击保存，最终不四舍五入、不POST；改为2.50可以发送数值2.5。
4. 线程数1e1、持续时长10.0、状态2e2正常发送number 10/10/200；可选输入清空后为无设置，不转成0。
5. 第一组填非法原文，添加第二组填20.0，然后删除第一组保存；最终第二组保留20，不串用被删除组的原文。切换负载模型时沿原规则清理不适用字段。
6. 对HTTP线程组字段提交合成私有标记，400错误只暴露固定字段/组索引，不回显合成值、条件键或正文。

证据：56–61；真实Chrome5组、页面及解析测试、真实HTTP按逐条结果映射。没有可见窗口、截图或焦点抢占。

## Runner与复跑边界

62–63为纯协议预检。Runner已有安全整数/字符串保护，本轮生产实现未改；P95/错误率由控制面质量判断消费，不新增Runner未约定阈值字段。原Runner1秒起的内部短时兼容保留，控制面计划持续仍10秒起。26项Runner检查25通过、1项真实JMeter集成跳过，不把跳过、预检或普通JVM重启当作真实负载/升级退出验收。

```sh
JAVA_HOME="$PWD/.testhub/desktop-runtime/darwin-arm64/jre" node scripts/qa/test-p2-backend.mjs P2D16PerformanceFlowTests
JAVA_HOME="$PWD/.testhub/desktop-runtime/darwin-arm64/jre" node scripts/desktop/test-mysql-backend.mjs '--tests=P2D16PerformanceFlowTests,Performance*Tests'
npx pnpm@10.13.1 exec vitest run src/test/d16PerformancePage.test.ts src/test/d16PerformanceNumbers.test.ts --maxWorkers=1
node --test services/jmeter-runner/test/*.test.js
npx pnpm@10.13.1 build
node scripts/qa/d16-performance-ui.mjs
JAVA_HOME="$PWD/.testhub/desktop-runtime/darwin-arm64/jre" node scripts/qa/d16-performance-http.mjs
```

真实HTTP复跑前以隔离H2构建命令生成当前JAR。复用QA只使用自有临时数据库/进程并清理，禁止向默认数据源运行破坏性验收。升级关闭超时仍为UNCONFIRMED_RETAINED，根因未确认。
