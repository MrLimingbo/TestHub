# OBS-09-E013：详细复现与最终回归

优先级P2；D16五项A已批准。本观察包含数值截断、数字字符串/文本自动转换及跨入口绕过。以下拆分触发方式便于复核，不登记为多个新增Bug。最终状态：**修复完成，待结果审核**。

## 公共准备

1. 在自有隔离测试工作区注册并登录，使用有项目写权限的账号。本文`{projectId}`、`{serverId}`、`{ruleId}`均为该测试账号自己创建的资产。
2. `POST /api/v1/projects/{projectId}/mock/servers`提交`{"name":"QA Mock"}`，确认200，记录serverId。
3. 写请求携带登录会话，并先GET `/api/v1/auth/csrf`获取本次CSRF头名和token。不要复用真实用户数据、密钥或共享服务。
4. 规则基准请求为下面JSON。除指定变体外，字段保持不变；创建地址为`POST /api/v1/projects/{projectId}/mock/servers/{serverId}/rules`，读取使用同地址GET，编辑使用`PUT .../rules/{ruleId}`。

```json
{
  "name": "QA Rule",
  "httpMethod": "GET",
  "path": "/qa",
  "priority": 10,
  "requestQuery": {},
  "requestHeaders": {},
  "requestBodyMatch": "ANY",
  "requestBody": "",
  "responseStatus": 200,
  "responseHeaders": {},
  "responseBody": "{\"ok\":true}"
}
```

测试小数及超大数时必须直接发送原始JSON文本。不要先经过JavaScript Number或JSON.stringify转换；例如1.0000000000000001会先被Number舍入，NaN序列化会变成null，这不能证明服务端处理了原始变体。

## 触发一：优先级小数被截断

1. 基准JSON只改`priority`为`1.5`，发送创建请求。
2. 读取规则列表，检查保存值；再对既有合法规则PUT同一非法值。
3. 旧行为：请求被接受，优先级被转换为整数1，非法更新也能覆盖原规则。
4. 最终预期与结果：返回400，新增规则数不变；非法更新后原记录内容、启停状态及审计计数不变。`1.0000000000000001`也400；10、10.0、1e1正常保存为10；0/1000接受，-1/1001拒绝。

证据：正式01–07、23–24、35、37；H2/MySQL以及真实HTTP/后台浏览器按逐条结果映射。

## 触发二：有效期小数生成实际分享

1. 向`POST .../servers/{serverId}/shares`发送原始JSON `{"name":"QA Share","expiresInDays":1.5}`。
2. GET同地址，检查是否生成分享、其expiresAt，以及分享/审计行数。
3. 旧行为：1.5被转换为1天并生成分享。
4. 最终结果：400，无新增分享、成功审计或shareKey响应。微小小数、0、-1、366、数字字符串也拒绝；1、1.0、1e0及365可用。缺失或真实null仍创建永久分享。
5. 页面点击“创建只读分享”，输入1.5后保存：保留文本并提示expiresInDays，不发业务POST；勾选“永不过期”后同一失效输入不再参与校验，提交expiresInDays:null。

证据：正式08–11、26、36、40。分享真实密钥仅存在于已删除的自有临时工作区，不保留在交付中。

## 触发三：响应状态小数被转为整数

1. 基准请求只改responseStatus为200.5，创建并读取。
2. 旧行为：接受并保存为200。
3. 最终结果：200.5及200.0000000000000001均400且无新增记录；200、200.0、2e2保存为200；599可用，199/600拒绝。缺失或null仍默认200。
4. 页面输入非法状态后提示responseStatus；精确等值状态200.0正常提交JSON数值200。

证据：正式12–16、35、37及真实HTTP补验。合法整数越界仍由原业务层返回400/REQUEST_REJECTED；新类型/精度错误在管理入口返回400/VALIDATION_FAILED。

## 触发四：数字字符串和文本值自动转换

1. 对三种数值字段分别提交`"10"`（状态用`"200"`），以及`" 10 "`、`"null"`。
2. 旧行为：合法数字字符串可被绑定为整数；当前全部400。真实null按上述原默认规则处理，字符串"null"不是null。
3. 对requestQuery/requestHeaders/responseHeaders逐个提交`{"X-Code":1}`或`{"X-Code":true}`；对requestBody/responseBody逐个提交JSON数值1或布尔true。
4. 旧行为：数值和布尔可被转换为文本，例如1→"1"、true→"true"。最终全部400，不新增或修改规则。容器错误类型同样拒绝。
5. 正向对照：query中的`{"id":"001","flag":"false","literal":"null","blank":""}`按原值保存；正文字符串`"{\"n\":1}"`内的JSON数字不被拒绝。请求头仍按原规则归一键名；真实null仍归一为空文本。

证据：正式05–06、17–22、38–39。页面的JSON条件编辑器原已要求值为字符串，本轮保留并回归该防线，新增保护位于绕过页面的写入口。

## 触发五：迁移绕过保存入口限制

1. 用源测试项目创建一条合法规则并GET `/api/v1/projects/{sourceProject}/mock/transfer`导出完整snapshot。
2. 保留version、servers、rules、shares等全部结构，原始文本中把一条规则的priority改为1.5（或把responseStatus改为200.5、条件Map值改为1、正文改为true）。
3. 使用新的UUID requestKey、目标账号expectedUserId、修改后的snapshot组成`{"requestKey":"{UUID}","expectedUserId":"{targetUserId}","snapshot":{...}}`，POST目标项目`/api/v1/projects/{targetProject}/mock/transfer`。
4. 旧行为：解码允许部分数值/文本强制转换，错误资料可在转换后导入。
5. 最终结果：400，无新增服务、规则、分享、迁移成功回执或审计。合法10/10.0/1e1和状态210/210.0/2.1e2仍可迁移。导入服务保持原停用策略，随快照复制的分享保持原撤销策略。

证据：正式27–28、34；最终H2/MySQL每入口26个错误资料变体，涵盖priority/status各6类、三类条件Map各4类、两类正文各1类；3组配对精确整数正向变体。仍属原正式用例，不加分母。

## 触发六：同步解码绕过

1. 源项目准备合法规则，GET `/api/v1/projects/{sourceProject}/mock/sync?expectedUserId={sourceUserId}`取得完整规范化items。
2. 目标项目GET同类地址取得当前revision。为每个item生成不同UUID operationId，组成新的requestKey、目标expectedUserId、expectedRevision和operations；保留serverName、ruleName、完整rule及服务item等原有语义。
3. 仅在rule中替换上述priority/responseStatus/条件值/正文原始JSON类型，再POST目标项目`/api/v1/projects/{targetProject}/mock/sync`。
4. 旧行为：解码阶段可先转换字段，再作为规范化内容进入应用。
5. 最终结果：错误资料400，资产和回执/审计不变；精确等值整数可以规范化后同步。旧revision仍409且无写入，相同请求重放返回原回执且不新增数据。

证据：正式29–30、33–34；与迁移相同26个拒绝资料和3组精确整数配对变体在H2/MySQL补验。完整快照的缺失/null字段校验没有放宽为管理接口默认值。

## 触发七：字段定位与页面错误处理

1. 管理接口在priority中放入合成字符串标记，或requestBody中放入含合成私有值的对象，触发绑定错误。
2. 旧错误缺少可用的具体字段定位。最终message/details只暴露固定字段名，合成提交值和条件键不回显；requestId及标准错误信封保留。迁移/同步错误message同样使用固定字段名。
3. 后台浏览器打开新建规则，依次输入1.5、微小小数、1001并点击保存；确认原始文本保留、错误提示可见、业务写请求次数为0。有效期做相同检查。
4. 输入priority=1e1及responseStatus=200.0，保存的真实浏览器请求携带number 10和200。

证据：正式35–40，浏览器4组通过；真实JAR/HTTP另外覆盖原始请求和隐私标记。

## 回归及兼容边界

所有正式40条最终通过，含权限隔离、运行匹配、拒绝原子性、永久分享、旧资产可读不改写与回执重放。修复前11个后端失败和2个页面失败是同一观察的断言切面；初次循环中后续小数等变体未必执行，最终变体已全部执行。既有溢出、错误容器及合法整数越界检查并非全是旧缺陷，不把原已通过的检查列为新Bug。

真实JVM重启验证的是本次自有合法记录持久化，不能恢复历史已经丢失的原始精度，也不能当作升级关闭超时复验。升级风险仍为UNCONFIRMED_RETAINED。

复跑可用的复用入口：

```sh
JAVA_HOME="$PWD/.testhub/desktop-runtime/darwin-arm64/jre" node scripts/qa/test-p2-backend.mjs P2D16MockFlowTests
JAVA_HOME="$PWD/.testhub/desktop-runtime/darwin-arm64/jre" node scripts/desktop/test-mysql-backend.mjs '--tests=P2D16MockFlowTests,Mock*Tests'
npx pnpm@10.13.1 exec vitest run src/test/d16MockPage.test.ts src/test/d16MockIntegerInput.test.ts --maxWorkers=1
npx pnpm@10.13.1 build
node scripts/qa/d16-mock-ui.mjs
JAVA_HOME="$PWD/.testhub/desktop-runtime/darwin-arm64/jre" node scripts/qa/d16-mock-http.mjs
```

复跑真实HTTP前应以隔离H2构建命令生成当前JAR。上述QA脚本使用自有临时数据并清理，不对默认数据源跑验收，也不留下可见浏览器窗口。
