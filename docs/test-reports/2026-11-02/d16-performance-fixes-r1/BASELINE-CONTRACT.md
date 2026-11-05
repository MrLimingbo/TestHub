# 性能兼容基准（修复前冻结）

依据当前PerformanceTestPlanController/Service、Transfer/Sync、ExecutionService/Repository及JMeter protocol静态检查；源码SHA见SOURCE-BASELINE.json。批准规则在独立REVIEW-APPROVAL，不改原提案状态。

## 写入、文本与错误率

9类整数字段的范围及条件见批准FIELD-MATRIX；ThreadGroupRequest与ThreadGroupInput目前使用Integer、BigDecimal和Map<String,String>，绑定可能丢失原始JSON类型。errorThresholdPercent沿用BigDecimal.scale()<=2、0..100；2.50接受，2.501和2.500拒绝，不静默舍入。整数越界沿用既有HTTP400业务/BeanValidation信封，不把新类型策略扩展到expectedVersion。

requestHeaders值真实null归空串且trim，queryParameters值真实null归空串且保留其他空白；两类Map缺失/null为空Map。requestBody缺失/null/空白为null，非空正文仅非GET/HEAD允许；responseContains缺失/null/空白为null、非空trim。dataSetCsv缺失/null/空白为null，CSV换行规范化，保留唯一变量名、行列、模板检查。

## 生命周期和跨入口

创建默认DRAFT；更新必须携带expectedVersion，缺失/null400、旧版本409，合法更新版本+1且原执行清单冻结。拒绝不新增计划/线程组、执行/manifest/outbox、成功回执或审计。

性能迁移使用完整版本1 snapshot/plans/threadGroups并新建DRAFT，整批事务，重放返回原回执；同步必须是完整规范化Item、校验目标revision/actor，已存目标保持id/status并版本+1，新计划DRAFT，重放不重复写入。不得将普通可选字段默认值用于放宽完整交换协议。

旧计划和旧清单可读、不批量重写；旧非法配置新入队按批准D16-OLD预检拒绝，旧已排队manifest和已运行状态不得由本次拒绝改写。存量已丢失精度/类型不能反推恢复。

## Runner、环境与范围

JMeter runtime协议版本1..6已有Number.isSafeInteger和STRING参数检查，内部运行工件durationSeconds原1..86400、控制面计划原10..86400；本轮保持既有Runner协议短时兼容，不放宽控制面10秒下界。P95/错误率供控制面质量判断，Runner未消费这两个阈值；不新增未约定运行字段或调整Redis任务字符串协议。

权限、项目作用域、版本、阶梯总时长<=86400、非阶梯禁止阶段字段、阶梯禁止集合点、TPS>=阶段数均保留。stepIntervalSeconds虽单字段上限86400，在阶段至少2且持续至少10时，可行总时长边界须同时满足，不把不可行组合当正例。

UI应校验原始文本再转Number，拒绝不舍入、不发业务POST，保留草稿且可定位线程组/字段；可选字段留空为无设置，禁用的非适用字段遵循原loadModelChanged清理，不串用其他线程组的原输入。
