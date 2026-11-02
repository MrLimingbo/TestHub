# TestHub D16接口自动化修复回归

- 版本：2026-11-02 五项A
- 范围：仅OBS-08-E010；其他五模块按模块审核门槛等待，其他9项/12环境/升级关闭不执行。

## 汇总

- 用例总数：30
- 正例/反例：13/17
- 需求覆盖：5/5（100.0%）
- 验收标准覆盖：30/30（100.0%）
- 设计覆盖项：30/30（100.0%）
- 优先级分布：P2=30
- 用例类型分布：boundary=2、compatibility=4、negative=14、permission=1、positive=7、state=1、usability=1
- 测试设计方法分布：boundary-value-2=2、compatibility-configuration=4、data-lifecycle=8、equivalence-partitioning=8、error-guessing=2、role-permission=1、scenario=4、state-transition=1
- 文档审查问题：0（无）
- 用例质量：99.9（pass）
- 文档就绪度：100.0（ready）

## 来源

| ID | 名称 | 类型 | 路径/备注 |
|---|---|---|---|
| SRC-001 | DECISIONS.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/DECISIONS.json |
| SRC-002 | D16-RULES-AND-ACCEPTANCE.md | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/D16-RULES-AND-ACCEPTANCE.md |
| SRC-003 | FIELD-MATRIX.md | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/FIELD-MATRIX.md |
| SRC-004 | REVIEW-APPROVAL.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/REVIEW-APPROVAL.json |
| SRC-005 | APPROVED-SCOPE.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-api-automation-fixes-r1/APPROVED-SCOPE.json |

## 需求覆盖

| 需求ID | 标题 | 风险 | 覆盖用例 |
|---|---|---|---|
| REQ-D16API-NUM | WAIT原始类型及精确整数 | medium | TC-D16API-01, TC-D16API-02, TC-D16API-03, TC-D16API-04, TC-D16API-05, TC-D16API-06, TC-D16API-07, TC-D16API-08, TC-D16API-09, TC-D16API-10 |
| REQ-D16API-WRITE | 写入入口一致性 | medium | TC-D16API-11, TC-D16API-12, TC-D16API-13, TC-D16API-14, TC-D16API-15, TC-D16API-16, TC-D16API-17, TC-D16API-18 |
| REQ-D16API-OLD | 存量及新执行边界 | medium | TC-D16API-19, TC-D16API-20, TC-D16API-21, TC-D16API-29, TC-D16API-30 |
| REQ-D16API-RUN | 受控Runner校验 | medium | TC-D16API-22, TC-D16API-23 |
| REQ-D16API-UI | 页面与兼容回归 | medium | TC-D16API-24, TC-D16API-25, TC-D16API-26, TC-D16API-27, TC-D16API-28 |

## 测试设计覆盖项

| 覆盖项ID | 测试方法 | 覆盖目标 | 来源引用 | 覆盖用例 |
|---|---|---|---|---|
| COV-D16API-01 | equivalence-partitioning | 精确整数等价数字写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-01 |
| COV-D16API-02 | boundary-value-2 | WAIT延迟下边界0可保存 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-02 |
| COV-D16API-03 | boundary-value-2 | WAIT延迟上边界600000可保存 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-03 |
| COV-D16API-04 | equivalence-partitioning | 带非零小数部分不得保存 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-04 |
| COV-D16API-05 | error-guessing | 微小小数不得先丢精度再通过 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-05 |
| COV-D16API-06 | equivalence-partitioning | 溢出与巨大指数受控拒绝 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-06 |
| COV-D16API-07 | equivalence-partitioning | WAIT拒绝数字字符串 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-07 |
| COV-D16API-08 | equivalence-partitioning | WAIT拒绝布尔和容器类型 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-08 |
| COV-D16API-09 | equivalence-partitioning | WAIT缺失和null不得归零 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-09 |
| COV-D16API-10 | error-guessing | 原始非有限JSON不能冒充null验证 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-10 |
| COV-D16API-11 | data-lifecycle | 合法编辑产生新版本且保留旧修订 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-11 |
| COV-D16API-12 | data-lifecycle | 非法编辑不改变现有版本或审计 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-12 |
| COV-D16API-13 | scenario | 公共步骤可保存合法WAIT | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-13 |
| COV-D16API-14 | data-lifecycle | 公共步骤保存与更新拒绝非法WAIT | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-14 |
| COV-D16API-15 | compatibility-configuration | 迁移合法WAIT保持可执行整数 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-15 |
| COV-D16API-16 | data-lifecycle | 迁移非法WAIT不落入成功回执 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-16 |
| COV-D16API-17 | compatibility-configuration | 同步合法WAIT不改变数值语义 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-17 |
| COV-D16API-18 | data-lifecycle | 同步非法WAIT不绕过保存校验 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-18 |
| COV-D16API-19 | data-lifecycle | 旧非法定义仍可读取且不自动改写 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-19 |
| COV-D16API-20 | scenario | 旧非法WAIT在预检与新入队前阻止 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-20 |
| COV-D16API-21 | scenario | 引用旧公共步骤和子场景也必须校验WAIT | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-21 |
| COV-D16API-22 | compatibility-configuration | Runner接受规范化的有限整数WAIT | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-22 |
| COV-D16API-23 | equivalence-partitioning | Runner拒绝非法WAIT且无目标请求 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-23 |
| COV-D16API-24 | scenario | 页面合法WAIT编辑提交数值 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-24 |
| COV-D16API-25 | equivalence-partitioning | 页面非法WAIT提示字段且不提交 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-25 |
| COV-D16API-26 | compatibility-configuration | 非WAIT配置及原类型契约保持 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-26 |
| COV-D16API-27 | role-permission | 权限隔离仍阻止越权WAIT写入 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-27 |
| COV-D16API-28 | state-transition | 乐观锁冲突不被类型修复绕过 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-28 |
| COV-D16API-29 | data-lifecycle | 旧执行清单不被新WAIT策略改写 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-29 |
| COV-D16API-30 | data-lifecycle | 旧非法WAIT可经人工明确修复后入队 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16API-30 |

## 测试用例

### 接口自动化 / WAIT

#### TC-D16API-01 精确整数等价数字写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-01
- 需求引用：REQ-D16API-NUM
- 验收标准引用：AC-D16API-01
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 新建含WAIT的合法场景，分别提交10、10.0及1e1，读取保存结果 | 10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-02 WAIT延迟下边界0可保存

- 类型：boundary
- 正反例：positive
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-02
- 需求引用：REQ-D16API-NUM
- 验收标准引用：AC-D16API-02
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 以0毫秒创建WAIT；读取保存定义 | 0/600000允许；-1/600001拒绝；新建与编辑采用相同规则。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“0/600000允许；-1/600001拒绝；新建与编辑采用相同规则。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-03 WAIT延迟上边界600000可保存

- 类型：boundary
- 正反例：positive
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-03
- 需求引用：REQ-D16API-NUM
- 验收标准引用：AC-D16API-03
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 以600000毫秒创建WAIT；读取保存定义 | 0/600000允许；-1/600001拒绝；新建与编辑采用相同规则。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“0/600000允许；-1/600001拒绝；新建与编辑采用相同规则。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-04 带非零小数部分不得保存

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-04
- 需求引用：REQ-D16API-NUM
- 验收标准引用：AC-D16API-04
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交1.5及负小数的WAIT并检查资产/修订未增加 | 1.5、负小数、1.0000000000000001原精度判非整数，400且业务未写入。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“1.5、负小数、1.0000000000000001原精度判非整数，400且业务未写入。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-05 微小小数不得先丢精度再通过

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：error-guessing
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-05
- 需求引用：REQ-D16API-NUM
- 验收标准引用：AC-D16API-05
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 原始JSON提交1.0000000000000001并核对无新修订 | 1.5、负小数、1.0000000000000001原精度判非整数，400且业务未写入。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“1.5、负小数、1.0000000000000001原精度判非整数，400且业务未写入。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-06 溢出与巨大指数受控拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-06
- 需求引用：REQ-D16API-NUM
- 验收标准引用：AC-D16API-06
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 原始JSON提交18446744073709551616、1e10000，检查拒绝及无资产写入 | 18446744073709551616、1e10000及超范围值受控拒绝；不得long/int回绕或500。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“18446744073709551616、1e10000及超范围值受控拒绝；不得long/int回绕或500。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-07 WAIT拒绝数字字符串

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-07
- 需求引用：REQ-D16API-NUM
- 验收标准引用：AC-D16API-07
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交"10"、" 10 "、"10.0"、"NaN"的WAIT | 按N-A拒绝整数字段"10"、" 10 "、"10.0"和错误率"2.50"；若选B按明示白名单验收。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“按N-A拒绝整数字段"10"、" 10 "、"10.0"和错误率"2.50"；若选B按明示白名单验收。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-08 WAIT拒绝布尔和容器类型

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-08
- 需求引用：REQ-D16API-NUM
- 验收标准引用：AC-D16API-08
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交true、[]、{}和空串的WAIT | 数值字段true、[]、{}、空串拒绝；缺失/null分别按字段矩阵。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“数值字段true、[]、{}、空串拒绝；缺失/null分别按字段矩阵。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-09 WAIT缺失和null不得归零

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-09
- 需求引用：REQ-D16API-NUM
- 验收标准引用：AC-D16API-09
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 省略delayMillis或传null再尝试保存 | 数值字段true、[]、{}、空串拒绝；缺失/null分别按字段矩阵。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“数值字段true、[]、{}、空串拒绝；缺失/null分别按字段矩阵。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-10 原始非有限JSON不能冒充null验证

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：error-guessing
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-10
- 需求引用：REQ-D16API-NUM
- 验收标准引用：AC-D16API-10
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 逐一发送含裸NaN、Infinity、-Infinity的原始HTTP正文 | 发送原始NaN/Infinity/-Infinity字节应400；不能使用JSON.stringify后变null的样例冒充验证。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“发送原始NaN/Infinity/-Infinity字节应400；不能使用JSON.stringify后变null的样例冒充验证。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-11 合法编辑产生新版本且保留旧修订

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-11
- 需求引用：REQ-D16API-WRITE
- 验收标准引用：AC-D16API-11
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 读取原场景修订后，以合法整数更新WAIT，再读取新旧修订 | 创建/编辑/相应预览或保存入口同一字段规则，拒绝不产生部分子记录。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“创建/编辑/相应预览或保存入口同一字段规则，拒绝不产生部分子记录。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-12 非法编辑不改变现有版本或审计

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-12
- 需求引用：REQ-D16API-WRITE
- 验收标准引用：AC-D16API-12
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对已有场景以小数更新WAIT；检查现有定义、修订和业务审计 | WAIT=1.5保存400，不新增场景/修订/成功业务审计，不留先保存后执行才失败的入口。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“WAIT=1.5保存400，不新增场景/修订/成功业务审计，不留先保存后执行才失败的入口。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-13 公共步骤可保存合法WAIT

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：scenario
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-13
- 需求引用：REQ-D16API-WRITE
- 验收标准引用：AC-D16API-13
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 在公共步骤中保存合法WAIT并读取定义 | 可复用步骤、导入/同步、预检/展开采用同一规则；旧非法定义新入队阻止，不产生业务执行/outbox。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“可复用步骤、导入/同步、预检/展开采用同一规则；旧非法定义新入队阻止，不产生业务执行/outbox。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-14 公共步骤保存与更新拒绝非法WAIT

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-14
- 需求引用：REQ-D16API-WRITE
- 验收标准引用：AC-D16API-14
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 在公共步骤新建和更新中提交小数/字符串WAIT，检查拒绝原子性 | 可复用步骤、导入/同步、预检/展开采用同一规则；旧非法定义新入队阻止，不产生业务执行/outbox。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“可复用步骤、导入/同步、预检/展开采用同一规则；旧非法定义新入队阻止，不产生业务执行/outbox。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-15 迁移合法WAIT保持可执行整数

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-15
- 需求引用：REQ-D16API-WRITE
- 验收标准引用：AC-D16API-15
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 按现有导入协议迁移合法WAIT，读取目标定义与回执 | 本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-16 迁移非法WAIT不落入成功回执

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-16
- 需求引用：REQ-D16API-WRITE
- 验收标准引用：AC-D16API-16
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 迁移含非法WAIT的场景/公共步骤修订，检查事务与回执 | 本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-17 同步合法WAIT不改变数值语义

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-17
- 需求引用：REQ-D16API-WRITE
- 验收标准引用：AC-D16API-17
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 通过场景/公共步骤同步入口提交合法WAIT并读取目标 | 本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-18 同步非法WAIT不绕过保存校验

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-18
- 需求引用：REQ-D16API-WRITE
- 验收标准引用：AC-D16API-18
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 同步含小数或字符串WAIT的定义，检查目标和回执未写入 | 本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-19 旧非法定义仍可读取且不自动改写

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-19
- 需求引用：REQ-D16API-OLD
- 验收标准引用：AC-D16API-19
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 在隔离数据库建立旧非法WAIT定义，读取定义及旧修订并比对原值 | 旧记录与旧清单只读可见；不批量改值，不推断被截断前原值。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“旧记录与旧清单只读可见；不批量改值，不推断被截断前原值。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-20 旧非法WAIT在预检与新入队前阻止

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：scenario
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-20
- 需求引用：REQ-D16API-OLD
- 验收标准引用：AC-D16API-20
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对旧非法WAIT直接执行预览和入队；检查执行、任务及outbox未新增 | 可复用步骤、导入/同步、预检/展开采用同一规则；旧非法定义新入队阻止，不产生业务执行/outbox。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“可复用步骤、导入/同步、预检/展开采用同一规则；旧非法定义新入队阻止，不产生业务执行/outbox。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-21 引用旧公共步骤和子场景也必须校验WAIT

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：scenario
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-21
- 需求引用：REQ-D16API-OLD
- 验收标准引用：AC-D16API-21
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对引用旧非法公共步骤/子场景的根场景尝试新入队 | 可复用步骤、导入/同步、预检/展开采用同一规则；旧非法定义新入队阻止，不产生业务执行/outbox。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“可复用步骤、导入/同步、预检/展开采用同一规则；旧非法定义新入队阻止，不产生业务执行/outbox。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-22 Runner接受规范化的有限整数WAIT

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-22
- 需求引用：REQ-D16API-RUN
- 验收标准引用：AC-D16API-22
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 向受控Runner提供合法规范化WAIT，在虚拟时钟下完成预检/等待 | 10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-23 Runner拒绝非法WAIT且无目标请求

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-23
- 需求引用：REQ-D16API-RUN
- 验收标准引用：AC-D16API-23
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 向受控Runner提供小数、数字字符串或非有限WAIT；核对无目标HTTP调用 | 可复用步骤、导入/同步、预检/展开采用同一规则；旧非法定义新入队阻止，不产生业务执行/outbox。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“可复用步骤、导入/同步、预检/展开采用同一规则；旧非法定义新入队阻止，不产生业务执行/outbox。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-24 页面合法WAIT编辑提交数值

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：scenario
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-24
- 需求引用：REQ-D16API-UI
- 验收标准引用：AC-D16API-24
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 在页面输入精确整数等价写法并手动保存，检查发送JSON number | UI小数提示并阻止提交，不先四舍五入；HTTP400沿用错误信封并可定位字段/索引，不回显认证或正文值。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“UI小数提示并阻止提交，不先四舍五入；HTTP400沿用错误信封并可定位字段/索引，不回显认证或正文值。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-25 页面非法WAIT提示字段且不提交

- 类型：usability
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-25
- 需求引用：REQ-D16API-UI
- 验收标准引用：AC-D16API-25
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 页面输入小数/微小小数并尝试保存或执行，检查提示和业务请求 | UI小数提示并阻止提交，不先四舍五入；HTTP400沿用错误信封并可定位字段/索引，不回显认证或正文值。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“UI小数提示并阻止提交，不先四舍五入；HTTP400沿用错误信封并可定位字段/索引，不回显认证或正文值。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-26 非WAIT配置及原类型契约保持

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-26
- 需求引用：REQ-D16API-UI
- 验收标准引用：AC-D16API-26
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对照通用非WAIT步骤配置，检查原字符串/布尔/数值契约未扩展修改 | 真实小数百分比、通用步骤config、原expectedVersion及未列字段契约保持。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“真实小数百分比、通用步骤config、原expectedVersion及未列字段契约保持。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-27 权限隔离仍阻止越权WAIT写入

- 类型：permission
- 正反例：negative
- 优先级：P2
- 测试设计方法：role-permission
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-27
- 需求引用：REQ-D16API-UI
- 验收标准引用：AC-D16API-27
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 用原有无权项目会话尝试写WAIT，核对原项目资产未改变 | 原项目/账号权限和版本冲突回归；拒绝不跨项目、不产生成功业务审计。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“原项目/账号权限和版本冲突回归；拒绝不跨项目、不产生成功业务审计。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-28 乐观锁冲突不被类型修复绕过

- 类型：state
- 正反例：negative
- 优先级：P2
- 测试设计方法：state-transition
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-28
- 需求引用：REQ-D16API-UI
- 验收标准引用：AC-D16API-28
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 合法更新后再用旧版本保存WAIT，核对原冲突保护 | 原项目/账号权限和版本冲突回归；拒绝不跨项目、不产生成功业务审计。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“原项目/账号权限和版本冲突回归；拒绝不跨项目、不产生成功业务审计。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-29 旧执行清单不被新WAIT策略改写

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-29
- 需求引用：REQ-D16API-OLD
- 验收标准引用：AC-D16API-29
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 记录已有清单内容/校验和，修改定义或触发预检后比对旧清单 | 旧记录与旧清单只读可见；不批量改值，不推断被截断前原值。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“旧记录与旧清单只读可见；不批量改值，不推断被截断前原值。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。

#### TC-D16API-30 旧非法WAIT可经人工明确修复后入队

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准A及既有WAIT观察设计一个主要业务目标；同一分区的数据变体不重复计为正式用例。
- 覆盖项引用：COV-D16API-30
- 需求引用：REQ-D16API-OLD
- 验收标准引用：AC-D16API-30
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：在全新隔离QA项目/账号及受控Runner/浏览器夹具执行；所需原资产与版本按用例准备。
- 测试数据：使用本用例引用的验收规则中的数字/类型与边界；未给具体资产名时使用{有效隔离资产}。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 读取旧非法WAIT后明确改为合法整数并保存，核对新入队使用新值及旧快照保持 | 新入队前校验；旧排队任务在预检校验规范化运行值、失败有原因且不创建外部资源；已有运行不自动取消。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“新入队前校验；旧排队任务在预检校验规范化运行值、失败有原因且不创建外部资源；已有运行不自动取消。” |

> 备注：实际执行状态保存在独立结果文件；数字/入口变体是工程证据，不增加正式分母。
