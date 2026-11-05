# OBS-11-E021 详细复现与最终回归

优先级P2，D16五项A已批准。本轮1项观察的多个触发面，最终状态：修复完成、待Web结果审核。

## 准备和基准请求

1. 使用全新自有隔离工作区、测试账号和有写权限的项目。以下ID都来自该账号创建的资产，禁止对用户工作区或共享数据库注入数据。
2. 浏览器来源需要在该隔离环境获准；本批使用https://ui.example.test。写请求携带登录会话，并先GET `/api/v1/auth/csrf`取当次CSRF头。
3. 创建浏览器配置：`POST /api/v1/projects/{projectId}/ui-automation/browser-profiles`，使用下方JSON，记录返回profileId。GET同地址读取配置列表。

```json
{"name":"QA Profile","browser":"chromium","baseUrl":"https://ui.example.test","viewportWidth":800,"viewportHeight":600,"enabled":true}
```

4. 创建场景：`POST /api/v1/projects/{projectId}/ui-automation/scenarios`，使用下方JSON，名称保持独立，记录scenarioId。GET同地址读取。

```json
{
  "browserProfileId":"{profileId}","name":"QA Scene","description":"",
  "failureStrategy":"STOP","variables":{"id":"001"},
  "steps":[
    {"id":"wait","type":"WAIT","name":"Wait","timeoutMillis":1000},
    {"id":"fill","type":"FILL","name":"Fill","selector":"#input","value":"001","timeoutMillis":1000},
    {"id":"text","type":"ASSERT_TEXT","name":"Assert","selector":"#input","expectedText":"001","timeoutMillis":1000}
  ]
}
```

小数、微小小数、溢出和NaN/Infinity必须发原始JSON文本。不要先经Number/JSON.stringify；微小小数会提前舍入，非有限值会变成null，不能冒充原始字节验证。

## 1. 视口小数及数字字符串被转换

1. 只改viewportWidth=800.5（再单独试viewportHeight=600.5），POST创建，GET读取。
2. 修复前：可接受并截断为800/600；数字字符串"800"也可被解析为整数。
3. 最终：小数、800.0000000000000001、数字字符串、布尔/容器均400，配置/成功审计不增加。800/800.0/8e2精确保存800。
4. 宽320/3840、高320/2160可用，相邻越界拒绝；缺失/null仍默认1440/900。
5. 编辑使用`PUT .../browser-profiles/{profileId}`同一规则；非法输入后版本、旧值和审计不变。

证据01–07、28–29；页面46–47及真实HTTP。

## 2. WAIT及其他步骤的超时截断

1. 基准场景的WAIT timeoutMillis改为1000.5发送；FILL单独改为1000.5重复。
2. 修复前：Integer绑定丢弃小数部分，场景可保存1000。
3. 最终：普通/微小小数、数字字符串或错误类型400，不写场景/步骤/成功审计。1000/1000.0/1e3精确保存1000。
4. WAIT为1..60000，缺失/null拒绝；非WAIT有值时100..60000，缺失/null保留null，Runner仍默认30000。不能把WAIT下界套给FILL。
5. 后步骤非法也整批拒绝；编辑`PUT .../scenarios/{scenarioId}`非法后原版本、步骤及既有manifest不变。

证据08–16、28、30–31；页面48、50及真实HTTP。

## 3. 执行超时与定时入口

1. `POST .../scenarios/{scenarioId}/executions`提交`{"timeoutSeconds":300.5}`，再提交数字字符串"300"。
2. 修复前：可能按300秒入队，生成execution、manifest及Outbox。
3. 最终：非法值400，上述三类资产和成功审计不增加；300/300.0/3e2均300秒，10/3600边界可用，缺失正文、{}、真实null默认300。
4. 定时创建地址`POST .../ui-automation/schedules`，提交如下自有停用计划，再逐次修改timeoutSeconds类型。每次新名称避免同名约束遮盖数值问题。

```json
{"scenarioId":"{scenarioId}","name":"QA Schedule","cronExpression":"0 0 0 1 1 ? 2099","timezone":"UTC","timeoutSeconds":300.5,"maxRetryAttempts":1,"retryBackoffSeconds":30,"enabled":false}
```

5. 定时同值超时也400；合法精确等值数字正常。重试次数、退避等未列字段不施加本轮新类型政策。
6. 页面非法执行必须在ensureLocalBrowser/prepareBrowser前结束，业务POST为0；避免先准备浏览器后才报数值错误。

证据17–21、44、49及Runner56。

## 4. 变量和步骤文本自动字符串化

1. variables.id依次改为数值1、布尔true；FILL.value或ASSERT_TEXT.expectedText分别改为同类值。
2. 修复前：其中数字/布尔可被转换成"1"/"true"后保存。
3. 最终：列明字段只接受JSON字符串，数值/布尔/容器400，不新增或改写场景。
4. 显式"001"、"false"、"null"、模板引用仍按原文本规则保留。真实null变量值归空串；变量集合缺失/null为空Map。
5. FILL值、ASSERT_TEXT期望仍必填且按原trim/换行规则检查；其他类型的可选空值语义保持。错误响应仅定位固定字段或steps索引，不回显合成标记、变量键或正文值。

证据22–27、43、51、54。页面变量编辑器原已有字符串防线，本轮保留，修复重点包括绕过页面的写入口。

## 5. 完整迁移和同步

迁移步骤：

1. 源隔离项目准备合法配置、场景和停用定时计划；GET `.../ui-automation/transfer`导出完整snapshot。
2. 仅改snapshot目标视口、步骤/执行超时为普通小数、微小小数或数字字符串，或把变量/步骤文本改为非字符串；其他版本、ID和引用结构保留。
3. 用新UUID requestKey、目标账号expectedUserId、完整snapshot组成ImportRequest，POST目标项目同类transfer地址。
4. 修复前部分字段可在解码转换后导入；最终错误资料400，整批资产、回执和成功审计不变。合法整数/.0/e0写法可以导入，配置及定时仍停用，不复制执行历史。

同步步骤：

1. GET源`.../ui-automation/sync?expectedUserId={sourceUserId}`取完整items，GET目标同类地址取revision。
2. 用新UUID requestKey、目标expectedUserId、expectedRevision、operations组请求；各操作含新UUID operationId和完整item。保留原引用/规范化要求。
3. 对目标数字用精确等值.0/指数写法，POST目标sync；最终正常同步，其他字段仍作完整规范化比较。
4. 对definition内原始微小小数或错误类型提交，最终400，不改目标revision、资产/回执/审计。通用Map解析保留原精度后才检查。
5. 原同步完整比较已拒绝不少非法值，同时也拒绝了精确浮点等值。本次新增“只对批准数字等值规范化”能力，不能误报为原非法同步全部能通过；本次保留精度是防止新能力引入舍入绕过。
6. 同一请求重放幂等，旧revision409；合法编辑/同步不改旧执行manifest。

证据32–38：每入口28组非法资料变体（4数值各4类，变量及2文本各4类）和三组合法数字写法；这些是正式用例的参数化数据，不增加分母。

## 6. 旧定义新入队与旧排队运行预检

只在自有临时数据库注入夹具：

1. 创建合法场景和一条排队执行，保存旧manifest及状态。
2. 将该场景原始steps JSON中的超时设为1000.5，并确认数据库仍保存非整数。旧列表读取沿原兼容契约可能显示1000，不批量修改原始记录。
3. 再手动执行：最终400，无新execution/manifest/outbox，旧清单和状态不变。原始JSON必须在旧读取转换前检查，数组/对象变量也应受控400，不能由旧读取器抛500。
4. 分别恢复步骤后注入variables.id=123/true/[]/{}，或将视口宽设319，再执行；均拒绝。能保留为非整数的存量夹具用1000.5；历史已丢失精度无法恢复，微小原始小数在写入前验证。
5. 从定时、手动重试入口触发同一非法定义，也不产生新执行。
6. 对自有旧排队manifest原始JSON注入1000.5，申请运行配置：最终400，不先签发运行工件、不产生成功签发审计、不重写旧manifest。

自动重试补验：

1. 合法定时执行进入ACCEPTED后，使当前场景定义非法。
2. 上报该执行FAILED事件，系统尝试下一次重试。
3. 发现并修正的边界：新预检异常不能使整个事务回滚，导致原FAILED结果丢失。
4. 最终：原执行保持FAILED，无新重试执行/manifest/outbox；记录`UI_AUTOMATION_RETRY_PRECHECK_REJECTED`失败审计，未把失败重试算作成功排队。

证据39–42；自动重试补验前45项中1失败，修正后目标类和后端全量/MySQL重新通过。属于本观察的兼容回归，不另加正式Bug数量。

## 7. 页面和Runner资源前拒绝

1. 真实后台Chrome打开配置/场景/执行弹窗；输入小数、微小小数或越界，点击保存，确认原文保留、错误可定位、业务POST为0。
2. 输入8e2/600.0、1e3/100.0、3e2等合法等值写法，实际请求发送JSON数值。
3. WAIT清空拒绝，非WAIT清空保留默认；FILL切WAIT时重新按WAIT必填判定，切回后保持可选规则。删除旧步骤后使用保留步骤自身原输入。
4. 向Runner运行预检提供非法视口/超时/变量/文本，必须在prepareBrowser、工作目录和browser.launch前失败；实际run调用断言准备次数0。
5. 本机loopback真实Chrome执行实际Runner.step：导航自有表单、填入变量001、断言文本001、WAIT 1毫秒完成。此为受控步骤流程，不等同远端Runner队列部署或跨平台全部浏览器验收。

证据46–56。没有可见窗口或焦点抢占，不保存截图/视频/Trace。

复用脚本：

```sh
JAVA_HOME="$PWD/.testhub/desktop-runtime/darwin-arm64/jre" node scripts/qa/test-p2-backend.mjs P2D16WebFlowTests
JAVA_HOME="$PWD/.testhub/desktop-runtime/darwin-arm64/jre" node scripts/desktop/test-mysql-backend.mjs '--tests=P2D16WebFlowTests,UiAutomation*Tests'
npx pnpm@10.13.1 exec vitest run src/test/d16WebPage.test.ts src/test/d16WebNumbers.test.ts --maxWorkers=1
node --test services/playwright-runner/test/*.test.js
npx pnpm@10.13.1 build
node scripts/qa/d16-web-ui.mjs
JAVA_HOME="$PWD/.testhub/desktop-runtime/darwin-arm64/jre" node scripts/qa/d16-web-http.mjs
```

真实HTTP前用隔离H2构建当前JAR。脚本只使用自有临时数据并清理；不对默认库跑验收。升级关闭超时仍未确认，普通JVM/浏览器退出不能替代升级复验。
