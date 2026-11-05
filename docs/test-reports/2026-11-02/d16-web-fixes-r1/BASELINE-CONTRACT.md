# Web兼容基准（修复前冻结）

源码SHA见SOURCE-BASELINE；批准源为独立审批。类型规则只对D16矩阵字段生效，定时计划的timeoutSeconds是执行超时相同值的另一写入口，保留10..3600/default300；maxRetryAttempts和retryBackoffSeconds不改。

## 数值和空值

viewportWidth 320..3840、viewportHeight 320..2160，省略/null为1440/900。WAIT timeoutMillis必填1..60000；其他类型有值时100..60000，缺失/null保持null并由Runner默认30000。execute timeoutSeconds 10..3600，缺失正文、空对象或null值均默认300。原范围、版本、权限和类型所需步骤值规则不变。

variables缺失/null为空Map，值null归空串；值为字符串001/false/null按原文本保留，不允许换行，trim后的键仍需唯一。步骤value/expectedText按原blankToNull trim处理，FILL值必填、ASSERT_TEXT期望必填且禁止换行；其他类型仍遵守原空值/可选规则。正文内变量占位符保留。URL、selector、id/type/name及通用metadata不扩大类型限制。

## 完整交换和旧数据

创建/编辑拒绝不写部分资产或成功审计；合法编辑版本+1，旧执行manifest冻结，引用页面对象的解析与功能计划关联保持。迁移为完整版本1快照，导入配置/定时计划停用，不复制执行历史；同步完整规范化Item、actor/revision/引用、事务和回执重放均保留。仅目标数字字段的精确等值写法可规范为整数，不放宽其他字段或缺失默认导致的非规范资料。

UiAutomationStep同时用于数据库读取，不能给该存储域类型全局加严格反序列化而破坏旧资料可读。旧定义及旧manifest可读无批量重写；旧已截断/转换输入不能恢复。新建/编辑/迁移/同步、手动/定时/重试新入队使用同一目标规则。旧非法定义新入队不得生成execution/manifest/outbox；旧已排队manifest只在运行预检拒绝，不重写、不自动取消旧运行，不先发可用运行工件或成功签发审计。

同步definition是Map<String,Object>，解析原始浮点时需保留精度，再检查目标字段；原完整规范化比较保留，只允许目标数字节点精确等值的规范化。存量读取及非目标字段的旧契约不改。

## 页面和Runner

页面在转换Number前检查原始文本，非法数值保留并定位字段/步骤，不发业务POST；执行非法输入在prepareBrowser前拒绝。非WAIT可选超时清空保持Runner默认，WAIT清空不能变为0或默认等待；类型切换/步骤删除不串用原输入。

Runner运行工件在prepareBrowser/browser.launch前检查目标视口、超时及变量/文本类型；WAIT必填及非WAIT null默认30000保持。对纯预检和自有loopback/headless实际NAVIGATE/FILL/ASSERT_TEXT/WAIT步骤验收，不宣称部署远端Runner或解除原跨平台环境阻塞。JSON在JS Number中已丢失的原始精度不能在值层恢复，原始微小小数在控制面/原始存储读取边界检查。

## 不变量

原版本和作用域、停用配置拒绝执行、幂等回执、同步旧revision409、组织/项目权限、旧清单冻结继续回归。Web完成后等审核，移动/质量报告、另外9项观察、12项环境继续等待；升级关闭风险UNCONFIRMED_RETAINED，根因未确认，本轮不复验升级退出。
