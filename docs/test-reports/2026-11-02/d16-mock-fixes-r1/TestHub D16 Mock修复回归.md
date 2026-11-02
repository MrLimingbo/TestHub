# TestHub D16 Mock修复回归

- 版本：2026-11-02 五项A
- 范围：仅OBS-09-E013；保留原完整迁移/同步及事务契约。其他四个D16模块及9项/12环境/升级退出调查不执行。

## 汇总

- 用例总数：40
- 正例/反例：21/19
- 需求覆盖：5/5（100.0%）
- 验收标准覆盖：40/40（100.0%）
- 设计覆盖项：40/40（100.0%）
- 优先级分布：P2=40
- 用例类型分布：boundary=6、compatibility=6、negative=12、permission=1、positive=8、recovery=1、state=2、usability=4
- 测试设计方法分布：acceptance-criteria=4、boundary-value-2=6、compatibility-configuration=6、data-lifecycle=6、equivalence-partitioning=12、error-guessing=2、recovery-idempotency=1、role-permission=1、state-transition=2
- 文档审查问题：0（无）
- 用例质量：99.92（pass）
- 文档就绪度：100.0（ready）

## 来源

| ID | 名称 | 类型 | 路径/备注 |
|---|---|---|---|
| SRC-001 | DECISIONS.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/DECISIONS.json |
| SRC-002 | D16-RULES-AND-ACCEPTANCE.md | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/D16-RULES-AND-ACCEPTANCE.md |
| SRC-003 | FIELD-MATRIX.md | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/FIELD-MATRIX.md |
| SRC-004 | REVIEW-APPROVAL.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/REVIEW-APPROVAL.json |
| SRC-005 | APPROVED-SCOPE.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-mock-fixes-r1/APPROVED-SCOPE.json |
| SRC-006 | BASELINE-CONTRACT.md | data | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-mock-fixes-r1/BASELINE-CONTRACT.md |

## 需求覆盖

| 需求ID | 标题 | 风险 | 覆盖用例 |
|---|---|---|---|
| REQ-D16MOCK-NUM | Mock数值类型与边界 | medium | TC-D16MOCK-01, TC-D16MOCK-02, TC-D16MOCK-03, TC-D16MOCK-04, TC-D16MOCK-05, TC-D16MOCK-06, TC-D16MOCK-07, TC-D16MOCK-08, TC-D16MOCK-09, TC-D16MOCK-10, TC-D16MOCK-11, TC-D16MOCK-12, TC-D16MOCK-13, TC-D16MOCK-14, TC-D16MOCK-15, TC-D16MOCK-16 |
| REQ-D16MOCK-TEXT | 文本和空值 | medium | TC-D16MOCK-17, TC-D16MOCK-18, TC-D16MOCK-19, TC-D16MOCK-20, TC-D16MOCK-21, TC-D16MOCK-22 |
| REQ-D16MOCK-WRITE | 编辑与运行 | medium | TC-D16MOCK-23, TC-D16MOCK-24, TC-D16MOCK-25, TC-D16MOCK-26 |
| REQ-D16MOCK-EXCHANGE | 迁移同步及兼容 | medium | TC-D16MOCK-27, TC-D16MOCK-28, TC-D16MOCK-29, TC-D16MOCK-30, TC-D16MOCK-31, TC-D16MOCK-32, TC-D16MOCK-33, TC-D16MOCK-34 |
| REQ-D16MOCK-UI | 后台页面与错误 | medium | TC-D16MOCK-35, TC-D16MOCK-36, TC-D16MOCK-37, TC-D16MOCK-38, TC-D16MOCK-39, TC-D16MOCK-40 |

## 测试设计覆盖项

| 覆盖项ID | 测试方法 | 覆盖目标 | 来源引用 | 覆盖用例 |
|---|---|---|---|---|
| COV-D16MOCK-01 | equivalence-partitioning | 优先级精确整数等价写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-01 |
| COV-D16MOCK-02 | boundary-value-2 | 优先级下边界与邻界 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-02 |
| COV-D16MOCK-03 | boundary-value-2 | 优先级上边界与邻界 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-03 |
| COV-D16MOCK-04 | error-guessing | 优先级拒绝小数和微小小数 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-04 |
| COV-D16MOCK-05 | equivalence-partitioning | 三类数值字段拒绝数字字符串 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-05 |
| COV-D16MOCK-06 | equivalence-partitioning | 三类数值字段拒绝布尔和容器 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-06 |
| COV-D16MOCK-07 | error-guessing | 溢出和原始非有限数值受控拒绝 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-07 |
| COV-D16MOCK-08 | equivalence-partitioning | 有效期接受精确整数等价写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-08 |
| COV-D16MOCK-09 | boundary-value-2 | 有效期上边界365天与邻界 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-09 |
| COV-D16MOCK-10 | boundary-value-2 | 有效期拒绝非法范围及小数 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-10 |
| COV-D16MOCK-11 | acceptance-criteria | 有效期省略和null仍为永久 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-11 |
| COV-D16MOCK-12 | equivalence-partitioning | 响应状态接受等价整数及下边界 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-12 |
| COV-D16MOCK-13 | boundary-value-2 | 响应状态上边界599可用 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-13 |
| COV-D16MOCK-14 | equivalence-partitioning | 响应状态拒绝非整数 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-14 |
| COV-D16MOCK-15 | boundary-value-2 | 响应状态拒绝邻界199和600 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-15 |
| COV-D16MOCK-16 | acceptance-criteria | 管理API可选优先级和状态默认保持 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-16 |
| COV-D16MOCK-17 | compatibility-configuration | 查询条件字符串不损失字面值 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-17 |
| COV-D16MOCK-18 | compatibility-configuration | 请求与响应头字符串保持原值 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-18 |
| COV-D16MOCK-19 | compatibility-configuration | 正文中的JSON数字作为文本保持 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-19 |
| COV-D16MOCK-20 | equivalence-partitioning | 条件及Header拒绝数值和布尔转换 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-20 |
| COV-D16MOCK-21 | equivalence-partitioning | 请求与响应正文拒绝非字符串值 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-21 |
| COV-D16MOCK-22 | compatibility-configuration | 真实null仍按原空值策略处理 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-22 |
| COV-D16MOCK-23 | data-lifecycle | 合法编辑保持启停状态 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-23 |
| COV-D16MOCK-24 | data-lifecycle | 非法编辑不改变旧规则或业务审计 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-24 |
| COV-D16MOCK-25 | acceptance-criteria | 原运行匹配和优先级顺序保持 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-25 |
| COV-D16MOCK-26 | data-lifecycle | 非法有效期不生成分享或成功审计 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-26 |
| COV-D16MOCK-27 | compatibility-configuration | 合法迁移接受等值整数 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-27 |
| COV-D16MOCK-28 | data-lifecycle | 非法迁移不留部分资产或成功回执 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-28 |
| COV-D16MOCK-29 | compatibility-configuration | 合法同步接受等值整数 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-29 |
| COV-D16MOCK-30 | data-lifecycle | 非法同步不绕过类型校验 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-30 |
| COV-D16MOCK-31 | data-lifecycle | 旧规则与分享可读不批量重写 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-31 |
| COV-D16MOCK-32 | role-permission | 项目权限保护保持 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-32 |
| COV-D16MOCK-33 | state-transition | 同步目标修订冲突仍拒绝 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-33 |
| COV-D16MOCK-34 | recovery-idempotency | 原requestKey回执规则保持 | SRC-006#document | TC-D16MOCK-34 |
| COV-D16MOCK-35 | equivalence-partitioning | 页面规则小数提示并阻止提交 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-35 |
| COV-D16MOCK-36 | equivalence-partitioning | 页面有效期非法值不发分享请求 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-36 |
| COV-D16MOCK-37 | equivalence-partitioning | 页面等价整数提交JSON数值 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-37 |
| COV-D16MOCK-38 | equivalence-partitioning | 页面条件编辑器已有字符串保护保持 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-38 |
| COV-D16MOCK-39 | acceptance-criteria | 错误信封定位字段且不泄露值 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16MOCK-39 |
| COV-D16MOCK-40 | state-transition | 永久分享不受未采用有效期值阻止 | SRC-006#document | TC-D16MOCK-40 |

## 测试用例

### Mock / D16类型规则

#### TC-D16MOCK-01 优先级精确整数等价写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-01
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-01
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 以10、10.0、1e1创建规则并读取优先级 | 10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-02 优先级下边界与邻界

- 类型：boundary
- 正反例：positive
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-02
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-02
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对照priority=0与-1，检查接受/拒绝及写入效果 | 0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-03 优先级上边界与邻界

- 类型：boundary
- 正反例：positive
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-03
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-03
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对照priority=1000与1001，检查接受/拒绝及写入效果 | 0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-04 优先级拒绝小数和微小小数

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：error-guessing
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-04
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-04
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 原始JSON提交priority=1.5、1.0000000000000001并核对无资产写入 | 1.5、负小数、1.0000000000000001原精度判非整数，400且业务未写入。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“1.5、负小数、1.0000000000000001原精度判非整数，400且业务未写入。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-05 三类数值字段拒绝数字字符串

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-05
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-05
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 分别把priority、expiresInDays、responseStatus改为数字字符串提交 | 按N-A拒绝整数字段"10"、" 10 "、"10.0"和错误率"2.50"；若选B按明示白名单验收。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“按N-A拒绝整数字段"10"、" 10 "、"10.0"和错误率"2.50"；若选B按明示白名单验收。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-06 三类数值字段拒绝布尔和容器

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-06
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-06
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 分别为三类数值字段提交true、数组和对象 | 数值字段true、[]、{}、空串拒绝；缺失/null分别按字段矩阵。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“数值字段true、[]、{}、空串拒绝；缺失/null分别按字段矩阵。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-07 溢出和原始非有限数值受控拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：error-guessing
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-07
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-07
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 原始字节提交巨大整数/指数及裸NaN、Infinity，不先用JSON序列化改变数据 | 18446744073709551616、1e10000及超范围值受控拒绝；不得long/int回绕或500。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“18446744073709551616、1e10000及超范围值受控拒绝；不得long/int回绕或500。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-08 有效期接受精确整数等价写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-08
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-08
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 以1、1.0、1e0创建分享并核对按整数天计算 | 10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-09 有效期上边界365天与邻界

- 类型：boundary
- 正反例：positive
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-09
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-09
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对照365与366天创建分享 | null/省略仍永久；1/365允许，0/366和1.5拒绝；拒绝不生成可用分享。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“null/省略仍永久；1/365允许，0/366和1.5拒绝；拒绝不生成可用分享。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-10 有效期拒绝非法范围及小数

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-10
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-10
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交0、负数和1.5/微小小数有效期 | null/省略仍永久；1/365允许，0/366和1.5拒绝；拒绝不生成可用分享。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“null/省略仍永久；1/365允许，0/366和1.5拒绝；拒绝不生成可用分享。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-11 有效期省略和null仍为永久

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-11
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-11
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 分别省略expiresInDays和传null创建分享，核对永久语义 | null/省略仍永久；1/365允许，0/366和1.5拒绝；拒绝不生成可用分享。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“null/省略仍永久；1/365允许，0/366和1.5拒绝；拒绝不生成可用分享。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-12 响应状态接受等价整数及下边界

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-12
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-12
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 以200、200.0、2e2创建规则并读取响应状态 | 0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-13 响应状态上边界599可用

- 类型：boundary
- 正反例：positive
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-13
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-13
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 用599创建规则并读取保存状态 | 0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-14 响应状态拒绝非整数

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-14
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-14
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 以响应状态的小数和微小小数提交规则 | 1.5、负小数、1.0000000000000001原精度判非整数，400且业务未写入。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“1.5、负小数、1.0000000000000001原精度判非整数，400且业务未写入。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-15 响应状态拒绝邻界199和600

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-15
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-15
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 用199和600创建规则并核对无写入 | 0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-16 管理API可选优先级和状态默认保持

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-16
- 需求引用：REQ-D16MOCK-NUM
- 验收标准引用：AC-D16MOCK-16
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 分别省略或置null优先级/响应状态，读取默认值 | 逐项核对Mock默认优先级/状态/永久、Web视口、Web/移动执行超时和移动步骤超时。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“逐项核对Mock默认优先级/状态/永久、Web视口、Web/移动执行超时和移动步骤超时。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-17 查询条件字符串不损失字面值

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-17
- 需求引用：REQ-D16MOCK-TEXT
- 验收标准引用：AC-D16MOCK-17
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 保存显式字符串001、false、null及合法空串，再读取查询条件 | 列明文本字段拒绝数字/布尔/容器；"001"、"false"、"null"、模板引用及合法空串保留，真实null按矩阵。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“列明文本字段拒绝数字/布尔/容器；"001"、"false"、"null"、模板引用及合法空串保留，真实null按矩阵。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-18 请求与响应头字符串保持原值

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-18
- 需求引用：REQ-D16MOCK-TEXT
- 验收标准引用：AC-D16MOCK-18
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 保存合法Header字符串并读取，核对原Header规范化规则 | 条件、Header值、正文数字/布尔拒绝；明确JSON正文字符串可用，拒绝不改旧匹配行为。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“条件、Header值、正文数字/布尔拒绝；明确JSON正文字符串可用，拒绝不改旧匹配行为。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-19 正文中的JSON数字作为文本保持

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-19
- 需求引用：REQ-D16MOCK-TEXT
- 验收标准引用：AC-D16MOCK-19
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 用显式JSON正文字符串及数字外观字符串保存规则，读取正文 | 条件、Header值、正文数字/布尔拒绝；明确JSON正文字符串可用，拒绝不改旧匹配行为。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“条件、Header值、正文数字/布尔拒绝；明确JSON正文字符串可用，拒绝不改旧匹配行为。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-20 条件及Header拒绝数值和布尔转换

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-20
- 需求引用：REQ-D16MOCK-TEXT
- 验收标准引用：AC-D16MOCK-20
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 把查询条件/请求头/响应头值换为数字、布尔或容器提交 | 条件、Header值、正文数字/布尔拒绝；明确JSON正文字符串可用，拒绝不改旧匹配行为。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“条件、Header值、正文数字/布尔拒绝；明确JSON正文字符串可用，拒绝不改旧匹配行为。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-21 请求与响应正文拒绝非字符串值

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-21
- 需求引用：REQ-D16MOCK-TEXT
- 验收标准引用：AC-D16MOCK-21
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 把请求正文与响应正文换为数字、布尔或容器后提交 | 条件、Header值、正文数字/布尔拒绝；明确JSON正文字符串可用，拒绝不改旧匹配行为。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“条件、Header值、正文数字/布尔拒绝；明确JSON正文字符串可用，拒绝不改旧匹配行为。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-22 真实null仍按原空值策略处理

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-22
- 需求引用：REQ-D16MOCK-TEXT
- 验收标准引用：AC-D16MOCK-22
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交原先允许的条件/Header/null正文，核对未字符串化为null | 列明文本字段拒绝数字/布尔/容器；"001"、"false"、"null"、模板引用及合法空串保留，真实null按矩阵。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“列明文本字段拒绝数字/布尔/容器；"001"、"false"、"null"、模板引用及合法空串保留，真实null按矩阵。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-23 合法编辑保持启停状态

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-23
- 需求引用：REQ-D16MOCK-WRITE
- 验收标准引用：AC-D16MOCK-23
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 编辑合法规则的优先级及正文，读取修改与原启停状态 | 创建/编辑/相应预览或保存入口同一字段规则，拒绝不产生部分子记录。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“创建/编辑/相应预览或保存入口同一字段规则，拒绝不产生部分子记录。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-24 非法编辑不改变旧规则或业务审计

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-24
- 需求引用：REQ-D16MOCK-WRITE
- 验收标准引用：AC-D16MOCK-24
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对已有规则提交小数或非字符串字段，核对旧内容与审计 | 创建/编辑/相应预览或保存入口同一字段规则，拒绝不产生部分子记录。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“创建/编辑/相应预览或保存入口同一字段规则，拒绝不产生部分子记录。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-25 原运行匹配和优先级顺序保持

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-25
- 需求引用：REQ-D16MOCK-WRITE
- 验收标准引用：AC-D16MOCK-25
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 创建不同优先级合法规则，使用受控运行请求检查匹配结果 | 0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“0/1000和200/599边界与默认保持；小数/越界拒绝，既有规则顺序不变。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-26 非法有效期不生成分享或成功审计

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-26
- 需求引用：REQ-D16MOCK-WRITE
- 验收标准引用：AC-D16MOCK-26
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对同一服务提交非法有效期，核对分享记录与成功业务审计不增加 | null/省略仍永久；1/365允许，0/366和1.5拒绝；拒绝不生成可用分享。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“null/省略仍永久；1/365允许，0/366和1.5拒绝；拒绝不生成可用分享。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-27 合法迁移接受等值整数

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-27
- 需求引用：REQ-D16MOCK-EXCHANGE
- 验收标准引用：AC-D16MOCK-27
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 迁移含合法整数写法的规则，读取目标及既有停用/分享元数据规则 | 本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-28 非法迁移不留部分资产或成功回执

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-28
- 需求引用：REQ-D16MOCK-EXCHANGE
- 验收标准引用：AC-D16MOCK-28
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 在迁移资料中提交小数、微小小数、数字字符串或非字符串条件 | 本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-29 合法同步接受等值整数

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-29
- 需求引用：REQ-D16MOCK-EXCHANGE
- 验收标准引用：AC-D16MOCK-29
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对完整规范化资料提交等价整数写法同步并读取目标 | 本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-30 非法同步不绕过类型校验

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-30
- 需求引用：REQ-D16MOCK-EXCHANGE
- 验收标准引用：AC-D16MOCK-30
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对同步资料提交小数、微小小数或字符串条件错误，检查资产和回执 | 本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-31 旧规则与分享可读不批量重写

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-31
- 需求引用：REQ-D16MOCK-EXCHANGE
- 验收标准引用：AC-D16MOCK-31
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 记录旧规则与历史分享，读取和预览后比对没有后台改写 | 旧记录与旧清单只读可见；不批量改值，不推断被截断前原值。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“旧记录与旧清单只读可见；不批量改值，不推断被截断前原值。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-32 项目权限保护保持

- 类型：permission
- 正反例：negative
- 优先级：P2
- 测试设计方法：role-permission
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-32
- 需求引用：REQ-D16MOCK-EXCHANGE
- 验收标准引用：AC-D16MOCK-32
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 用无权项目会话提交合法规则，核对未跨项目写入 | 原项目/账号权限和版本冲突回归；拒绝不跨项目、不产生成功业务审计。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“原项目/账号权限和版本冲突回归；拒绝不跨项目、不产生成功业务审计。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-33 同步目标修订冲突仍拒绝

- 类型：state
- 正反例：negative
- 优先级：P2
- 测试设计方法：state-transition
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-33
- 需求引用：REQ-D16MOCK-EXCHANGE
- 验收标准引用：AC-D16MOCK-33
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 取得目标修订后修改目标，再使用旧修订同步 | 原项目/账号权限和版本冲突回归；拒绝不跨项目、不产生成功业务审计。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“原项目/账号权限和版本冲突回归；拒绝不跨项目、不产生成功业务审计。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-34 原requestKey回执规则保持

- 类型：recovery
- 正反例：positive
- 优先级：P2
- 测试设计方法：recovery-idempotency
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-34
- 需求引用：REQ-D16MOCK-EXCHANGE
- 验收标准引用：AC-D16MOCK-34
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 重放原已成功的相同迁移/同步请求，核对原回执及写入数量 | 迁移/同步仍要求原有完整、已规范化资料，保留名称、权限、并发修订和requestKey回执规则。 | SRC-006#document：“迁移/同步仍要求原有完整、已规范化资料，保留名称、权限、并发修订和requestKey回执规则。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-35 页面规则小数提示并阻止提交

- 类型：usability
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-35
- 需求引用：REQ-D16MOCK-UI
- 验收标准引用：AC-D16MOCK-35
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 在规则编辑器输入小数和微小小数并提交，观察提示和业务请求 | UI小数提示并阻止提交，不先四舍五入；HTTP400沿用错误信封并可定位字段/索引，不回显认证或正文值。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“UI小数提示并阻止提交，不先四舍五入；HTTP400沿用错误信封并可定位字段/索引，不回显认证或正文值。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-36 页面有效期非法值不发分享请求

- 类型：usability
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-36
- 需求引用：REQ-D16MOCK-UI
- 验收标准引用：AC-D16MOCK-36
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 输入非法有效期创建分享，观察提示及业务请求 | UI小数提示并阻止提交，不先四舍五入；HTTP400沿用错误信封并可定位字段/索引，不回显认证或正文值。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“UI小数提示并阻止提交，不先四舍五入；HTTP400沿用错误信封并可定位字段/索引，不回显认证或正文值。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-37 页面等价整数提交JSON数值

- 类型：usability
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-37
- 需求引用：REQ-D16MOCK-UI
- 验收标准引用：AC-D16MOCK-37
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 页面输入10.0、1e1或合法状态码等值写法后保存 | 10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-38 页面条件编辑器已有字符串保护保持

- 类型：usability
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-38
- 需求引用：REQ-D16MOCK-UI
- 验收标准引用：AC-D16MOCK-38
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 在查询条件或Header JSON编辑器填数字值并尝试保存 | 条件、Header值、正文数字/布尔拒绝；明确JSON正文字符串可用，拒绝不改旧匹配行为。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“条件、Header值、正文数字/布尔拒绝；明确JSON正文字符串可用，拒绝不改旧匹配行为。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-39 错误信封定位字段且不泄露值

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-39
- 需求引用：REQ-D16MOCK-UI
- 验收标准引用：AC-D16MOCK-39
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交包含合成敏感标记的类型错误，检查HTTP400、字段定位和错误正文 | UI小数提示并阻止提交，不先四舍五入；HTTP400沿用错误信封并可定位字段/索引，不回显认证或正文值。 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“UI小数提示并阻止提交，不先四舍五入；HTTP400沿用错误信封并可定位字段/索引，不回显认证或正文值。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。

#### TC-D16MOCK-40 永久分享不受未采用有效期值阻止

- 类型：state
- 正反例：positive
- 优先级：P2
- 测试设计方法：state-transition
- 设计依据：按批准规则和原兼容基准设计一个主要字段/入口目标；独立边界与入口分别有COV，数据变体不另计用例。
- 覆盖项引用：COV-D16MOCK-40
- 需求引用：REQ-D16MOCK-UI
- 验收标准引用：AC-D16MOCK-40
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：全新隔离QA账号/项目/Mock服务；按用例准备原规则、分享或同步版本。
- 测试数据：使用引用规则中的数值/类型/边界；其余用{合法隔离资产}等符号代表，具体夹具见复用QA测试。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 先输入非法有效期再勾选永不过期，提交并核对expiresInDays为null | 页面勾选永不过期时，提交expiresInDays=null；未采用的有效期输入不应阻止永久分享。 | SRC-006#document：“页面勾选永不过期时，提交expiresInDays=null；未采用的有效期输入不应阻止永久分享。” |

> 备注：先行设计不含实际状态；各环境和变体按独立结果文件汇总，不增加正式分母。
