# D23 通知与扩展JSON兼容政策修复回归报告

2026-11-04。**D23-A已批准项实施完成，20条正式用例（正9、反11）全部通过，0失败、0阻塞；另发现1个重复适配器登记500缺陷，尚未修复，单独待审。** 完整隔离H2 1360通过/3跳过，隔离MySQL影响面54项完成，相关前端66项通过。未进入下一模块。

[D21结果已审核](../d21-report-filter-fixes-r1/REVIEW-APPROVAL.json)。本轮仅推进OBS-14-E024的[已批准D23-A](../../2026-11-03/remaining-nine-rule-review/RULES-AND-ACCEPTANCE.md)：普通外层DTO保持兼容忽略，补齐说明及边界回归。原观察不直接证明越权，本轮未将其改写为未知字段必须400的政策。

## 实施结果

实施前冻结[控制器/DTO/入口清单](SCOPE-INVENTORY.md)，范围为2个控制器、4类外层DTO、6个写入口：通知TemplateRequest创建/更新，扩展AdapterRequest创建/更新、SigningKeyRequest登记、PackageRequest登记/升级。仅对这个清单给出普通DTO兼容结论，不外推全产品接口。

在NotificationTemplateController和ExtensionRegistryController补充4类DTO的OpenAPI说明，以及configuration动态Map、manifestJson签名原文的字段说明；同步[独立兼容契约](../../../notification-extension-json-contract.md)。本轮生产变更仅为描述注解，运行兼容行为原已符合A；全局ObjectMapper、业务服务、严格reader和PluginManifest均未修改，无新依赖、schema迁移或产品UI变更。

已验证：

- 合法已知字段附加未知标量、对象或数组后，创建/更新投影与合法基线一致，未知内容不保存、不执行、不进入成功审计。合法配置加密及脱敏保持，元数据更新的null配置继续保留密文。
- 拼错/缺失/null/空白必填字段、错误对象/数组类型、已知渠道/适配器/公钥/清单权限规则继续拒绝。伪造组织、项目、用户、管理员和角色字段不能覆盖路径、登录主体或原授权。
- 普通用户扩展写、项目只读用户通知写、匿名及缺失CSRF仍被拒绝；拒绝时业务、信任、角色、回执和成功审计快照不变。普通用户权限方法没有建立可信密钥。
- 合法configuration自定义键保留，原Map值和必填键校验保持。manifest原始UTF-8字符及首尾空白参与签名和checksum，外层兼容不会重写已知内层内容；篡改原文而沿用旧签名继续拒绝。数据库JSON排版可能规范化，不宣称原始字节归档或新增任意内层未知字段政策。
- 通知迁移/同步顶层和嵌套DTO未知字段继续400，无模板、回执或成功审计写入；其他严格业务入口代码保持，全量回归包含既有D16数值规则。

## 测试结果与口径

|检查|最终结果|统计和证据|
|---|---|---|
|D23正式专项|H2 20/20、MySQL 20/20|正9/反11；[用例](TEST-PLAN.md)、[逐项结果](CASE-RESULTS.json)。|
|独立观察X01|H2及MySQL均采集完成|不含于20条分母；两种重复登记均500，未据此关闭Bug。|
|完整隔离H2/JAR|159套件、1363项：1360通过、3跳过，0失败/错误；构建成功|包含20个正式方法及1个X01；[套件/跳过明细](backend-h2.json)。|
|隔离MySQL影响面|6套件54/54，0失败/错误/跳过|D23 21、扩展登记1、扩展运维9、通知迁移10、同步9、运维修复4；含X01，不是全产品MySQL全量。[方法与诊断](backend-mysql.json)。|
|相关前端|3文件66/66|本轮未新增UI方法、未重跑前端全量；[具体文件](frontend-results.json)。|
|ESLint / 类型 / Vite|通过|lint 0错误、2条既有apiDesignFixes.test.ts prop警告；保留构建大chunk建议。|
|diff及报告校验|通过|[命令/JAR哈希](VERIFICATION-MANIFEST.json)、[最终校验](VALIDATION.json)。|

正式用例对应20个B方法，X01是新增发现的证据采集方法。JUnit统计中X01完成显示PASS，只说明采集和无写入断言完成，不代表500是业务应有结果；54/54及1360通过不能解释为“所有Bug已关闭”。两个数据库、字段/类型矩阵及创建/更新变体不额外增加正式用例数。

H2的MySQL审计迁移、Redis HTTP及兼容分支共3项按环境条件跳过，不计通过，本轮MySQL影响面未补跑它们，不解除专用环境要求。相关前端只运行通知、插件/运维及其表单生命周期的3个已有文件，不借用上轮949项为本轮结果。

有效修复前正式用例19通过、1个OpenAPI说明方法失败，另有1个X01采集完成，共21个方法；B20的4类DTO说明断言均失败。修复后说明及动态Map/原文字段断言均通过。首次B03比较夹具复用唯一键，已恢复“已知字段合法”的前提后再红测，保留了原投影、解密和不保存未知字段的断言；重复键500另案保留。[修复前证据](BEFORE-RESULTS.json)、[执行与维护记录](EXECUTION-PLAN.md)。

## 新增未修复缺陷：BUG-D23-001

**P3，OPEN_PENDING_RULE_REVIEW：同类型同名称适配器重复登记返回500。**

1. 隔离系统管理员用合法OIDC类型、新名称、HTTPS测试地址及完整configuration创建适配器，返回200。
2. 原样重复POST `/api/v1/extensions/adapters`，不附加未知字段，实际500/INTERNAL_ERROR。
3. 再附加未知字段重复，仍500；唯一约束`uk_enterprise_adapter_name(adapter_type,display_name)`触发DuplicateKeyException。
4. H2及MySQL独立复核均为500/500，业务行、信任、角色、回执和成功审计不变。

完整准备条件、操作及影响见[详细复现](REPRODUCTION-AND-REGRESSION.md)，状态和跨库结果见[Bug清单](BUGS.json)。建议另案对创建/更新名称冲突采用受控提示，推荐409，并覆盖并发与原记录保留原名。最终返回码及扩展范围待审；D23-A要求保持本批已知业务规则，当前没有擅自更改重复键返回政策。该缺陷未修复，未计入47项历史观察或108个历史已修复Bug。

## 设计、证据与后续门槛

沿用requirements-to-test-cases技能，从两份只读规则/审批证据一次抽取，2份extracted、0警告；只取D23小节。模型schema 1.1，5条需求、5项验收标准、20个覆盖项；原D23-AC-01至05仅映射为AC-D23-01至05以满足校验器标识格式。[规范模型](TEST-CASES.json)、[方法选择](DESIGN-METHODS.json)、[设计校验](DESIGN-VALIDATION.json)、[导出统计](TEST-PLAN-summary.json)。

需求关联5/5、验收关联5/5、设计项关联20/20，零无来源用例、无效锚点、精确/近似重复及校验错误/警告。用例质量诊断100；规格就绪99、needs-clarification，保留1个低级文档问题DOC-D23-001，正是新增重复键错误处理规则待审。关联率不是代码或全产品覆盖率，高分不消除这项待确认问题。

只改2个既有生产控制器，新增1个专项Java测试，源码基线其余1425个文件保持。[变更清单](IMPLEMENTATION-CHANGE-INVENTORY.json)、[来源与哈希](SOURCE-INDEX.json)。全部后台、隔离事务/数据库执行，无可见桌面窗口；私钥只在内存，不对外投递或连接真实适配器。自有原始日志、Surefire输出、H2/MySQL临时环境已清理，保留脱敏摘要和可复用测试，见[清理记录](CLEANUP.json)。未替换安装运行时，未提交推送。

[47项历史观察台账](OBSERVATION-STATUS.json)：44项此前结果已审、D23一项已实施待审、D26资源/延迟两项等待门槛；另单列新增未修复Bug1项。D26基线未启动。历史3090项检查、108个已修复产品Bug和1个正式资产问题保持冻结，D16的189条正式用例与48个方法继续分列。

[12项环境补齐](ENVIRONMENT-BLOCKERS.json)按原字节承接，继续暂缓；本轮自有隔离QA基础设施不解除真实设备、跨端或发行环境要求。

**升级关闭超时未确认风险独立保留：**[OBS-XREG-SHUTDOWN](UNCONFIRMED-RISK.json)仍UNCONFIRMED_RETAINED、rootCauseConfirmed=false，本轮未调查或复验。历史两次复验通过不消除最初30秒超时，测试进程退出或JAR构建不作为该风险修复证据。

等待本轮结果及新增问题处理规则审核，暂不进入下一模块。
