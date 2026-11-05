# TestHub D16性能修复回归

- 版本：2026-11-02 五项A
- 范围：仅OBS-10-E011；不推进Web，不补齐原12环境，不调查升级关闭；六输入先行冻结。

## 汇总

- 用例总数：63
- 正例/反例：27/36
- 需求覆盖：7/7（100.0%）
- 验收标准覆盖：63/63（100.0%）
- 设计覆盖项：63/63（100.0%）
- 优先级分布：P2=63
- 用例类型分布：boundary=11、compatibility=8、negative=22、permission=1、positive=16、recovery=1、state=4
- 测试设计方法分布：acceptance-criteria=4、boundary-value-2=11、compatibility-configuration=8、data-lifecycle=6、decision-table=4、equivalence-partitioning=22、error-guessing=2、recovery-idempotency=1、role-permission=1、state-transition=4
- 文档审查问题：0（无）
- 用例质量：99.33（pass）
- 文档就绪度：100.0（ready）

## 来源

| ID | 名称 | 类型 | 路径/备注 |
|---|---|---|---|
| SRC-001 | DECISIONS.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/DECISIONS.json |
| SRC-002 | D16-RULES-AND-ACCEPTANCE.md | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/D16-RULES-AND-ACCEPTANCE.md |
| SRC-003 | FIELD-MATRIX.md | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/FIELD-MATRIX.md |
| SRC-004 | REVIEW-APPROVAL.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/REVIEW-APPROVAL.json |
| SRC-005 | APPROVED-SCOPE.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-performance-fixes-r1/APPROVED-SCOPE.json |
| SRC-006 | BASELINE-CONTRACT.md | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-performance-fixes-r1/BASELINE-CONTRACT.md |

## 需求覆盖

| 需求ID | 标题 | 风险 | 覆盖用例 |
|---|---|---|---|
| REQ-D16PERF-NUM | 性能D16 NUM | medium | TC-D16PERF-01, TC-D16PERF-02, TC-D16PERF-03, TC-D16PERF-04, TC-D16PERF-05, TC-D16PERF-06, TC-D16PERF-07, TC-D16PERF-08, TC-D16PERF-09, TC-D16PERF-10, TC-D16PERF-11, TC-D16PERF-12, TC-D16PERF-13, TC-D16PERF-14, TC-D16PERF-15, TC-D16PERF-16, TC-D16PERF-17, TC-D16PERF-18, TC-D16PERF-19, TC-D16PERF-20, TC-D16PERF-21, TC-D16PERF-22, TC-D16PERF-23, TC-D16PERF-24 |
| REQ-D16PERF-REL | 性能D16 REL | medium | TC-D16PERF-25, TC-D16PERF-26, TC-D16PERF-27, TC-D16PERF-28, TC-D16PERF-29 |
| REQ-D16PERF-DEC | 性能D16 DEC | medium | TC-D16PERF-30, TC-D16PERF-31, TC-D16PERF-32, TC-D16PERF-33, TC-D16PERF-34 |
| REQ-D16PERF-TEXT | 性能D16 TEXT | medium | TC-D16PERF-35, TC-D16PERF-36, TC-D16PERF-37, TC-D16PERF-38, TC-D16PERF-39, TC-D16PERF-40, TC-D16PERF-41, TC-D16PERF-42 |
| REQ-D16PERF-LIFE | 性能D16 LIFE | medium | TC-D16PERF-43, TC-D16PERF-44, TC-D16PERF-45, TC-D16PERF-46, TC-D16PERF-47, TC-D16PERF-48, TC-D16PERF-49, TC-D16PERF-50, TC-D16PERF-51, TC-D16PERF-52, TC-D16PERF-53, TC-D16PERF-54, TC-D16PERF-55, TC-D16PERF-56 |
| REQ-D16PERF-UI | 性能D16 UI | medium | TC-D16PERF-57, TC-D16PERF-58, TC-D16PERF-59, TC-D16PERF-60, TC-D16PERF-61 |
| REQ-D16PERF-RUN | 性能D16 RUN | medium | TC-D16PERF-62, TC-D16PERF-63 |

## 测试设计覆盖项

| 覆盖项ID | 测试方法 | 覆盖目标 | 来源引用 | 覆盖用例 |
|---|---|---|---|---|
| COV-D16PERF-01 | equivalence-partitioning | threadCount精确整数等值写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-01 |
| COV-D16PERF-02 | equivalence-partitioning | rampUpSeconds精确整数等值写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-02 |
| COV-D16PERF-03 | equivalence-partitioning | durationSeconds精确整数等值写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-03 |
| COV-D16PERF-04 | equivalence-partitioning | stepCount精确整数等值写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-04 |
| COV-D16PERF-05 | equivalence-partitioning | stepIntervalSeconds精确整数等值写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-05 |
| COV-D16PERF-06 | equivalence-partitioning | targetTps精确整数等值写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-06 |
| COV-D16PERF-07 | equivalence-partitioning | p95ThresholdMillis精确整数等值写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-07 |
| COV-D16PERF-08 | equivalence-partitioning | expectedStatusCode精确整数等值写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-08 |
| COV-D16PERF-09 | equivalence-partitioning | rendezvousUsers精确整数等值写法 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-09 |
| COV-D16PERF-10 | boundary-value-2 | threadCount边界及关联可行上限 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-10 |
| COV-D16PERF-11 | boundary-value-2 | rampUpSeconds边界及关联可行上限 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-11 |
| COV-D16PERF-12 | boundary-value-2 | durationSeconds边界及关联可行上限 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-12 |
| COV-D16PERF-13 | boundary-value-2 | stepCount边界及关联可行上限 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-13 |
| COV-D16PERF-14 | boundary-value-2 | stepIntervalSeconds边界及关联可行上限 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-14 |
| COV-D16PERF-15 | boundary-value-2 | targetTps边界及关联可行上限 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-15 |
| COV-D16PERF-16 | boundary-value-2 | p95ThresholdMillis边界及关联可行上限 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-16 |
| COV-D16PERF-17 | boundary-value-2 | expectedStatusCode边界及关联可行上限 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-17 |
| COV-D16PERF-18 | boundary-value-2 | rendezvousUsers边界及关联可行上限 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-18 |
| COV-D16PERF-19 | equivalence-partitioning | 九类整数字段非整数拒绝 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-19 |
| COV-D16PERF-20 | equivalence-partitioning | 数值字符串不能绕过九整数约束 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-20 |
| COV-D16PERF-21 | equivalence-partitioning | 数值字段布尔和容器错误类型 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-21 |
| COV-D16PERF-22 | error-guessing | 大整数指数及原始非有限JSON拒绝 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-22 |
| COV-D16PERF-23 | equivalence-partitioning | 三必填整数缺失和真实null | SRC-002#heading/D16-NULL：缺失-null与任务进度 | TC-D16PERF-23 |
| COV-D16PERF-24 | compatibility-configuration | 可选数值缺失和null保持无设置 | SRC-003#document | TC-D16PERF-24 |
| COV-D16PERF-25 | decision-table | 阶梯阶段及间隔必须提供 | SRC-003#document | TC-D16PERF-25 |
| COV-D16PERF-26 | decision-table | 非阶梯禁止阶段参数 | SRC-006#document | TC-D16PERF-26 |
| COV-D16PERF-27 | boundary-value-2 | 阶梯总时长临界与溢出 | SRC-006#document | TC-D16PERF-27 |
| COV-D16PERF-28 | decision-table | 阶梯TPS与阶段数关系 | SRC-006#document | TC-D16PERF-28 |
| COV-D16PERF-29 | decision-table | 阶梯加压时长与集合点互斥 | SRC-006#document | TC-D16PERF-29 |
| COV-D16PERF-30 | equivalence-partitioning | 错误率真小数保持 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-30 |
| COV-D16PERF-31 | equivalence-partitioning | 错误率超过两位精度不舍入 | SRC-006#document | TC-D16PERF-31 |
| COV-D16PERF-32 | boundary-value-2 | 错误率0与100边界 | SRC-006#document | TC-D16PERF-32 |
| COV-D16PERF-33 | equivalence-partitioning | 错误率数字字符串和类型错误 | SRC-002#heading/D16-N：数字字符串是否兼容 | TC-D16PERF-33 |
| COV-D16PERF-34 | compatibility-configuration | 错误率缺失null无阈值 | SRC-003#document | TC-D16PERF-34 |
| COV-D16PERF-35 | equivalence-partitioning | 请求头显式字符串保真 | SRC-006#document | TC-D16PERF-35 |
| COV-D16PERF-36 | equivalence-partitioning | query显式字符串保真 | SRC-006#document | TC-D16PERF-36 |
| COV-D16PERF-37 | equivalence-partitioning | 请求正文JSON字符串可用 | SRC-006#document | TC-D16PERF-37 |
| COV-D16PERF-38 | equivalence-partitioning | 响应包含断言字符串与trim保持 | SRC-006#document | TC-D16PERF-38 |
| COV-D16PERF-39 | compatibility-configuration | CSV模板与字符串数据保持 | SRC-006#document | TC-D16PERF-39 |
| COV-D16PERF-40 | equivalence-partitioning | Header和query非字符串值拒绝 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-40 |
| COV-D16PERF-41 | equivalence-partitioning | 正文断言CSV非字符串拒绝 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-41 |
| COV-D16PERF-42 | compatibility-configuration | 文本真实null及缺失语义保持 | SRC-006#document | TC-D16PERF-42 |
| COV-D16PERF-43 | state-transition | 合法编辑版本与历史清单冻结 | SRC-006#document | TC-D16PERF-43 |
| COV-D16PERF-44 | data-lifecycle | 非法编辑不动原计划版本 | SRC-006#document | TC-D16PERF-44 |
| COV-D16PERF-45 | data-lifecycle | 多线程组晚项非法整批原子 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-45 |
| COV-D16PERF-46 | compatibility-configuration | 合法迁移精确整数与小数 | SRC-006#document | TC-D16PERF-46 |
| COV-D16PERF-47 | data-lifecycle | 非法迁移类型不得转换后入库 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-47 |
| COV-D16PERF-48 | compatibility-configuration | 合法同步等值数字保持规范协议 | SRC-006#document | TC-D16PERF-48 |
| COV-D16PERF-49 | data-lifecycle | 非法同步不得绕过字段类型 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-49 |
| COV-D16PERF-50 | compatibility-configuration | 存量读取不批量改写 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-50 |
| COV-D16PERF-51 | data-lifecycle | 旧非法定义新入队前拒绝 | SRC-006#document | TC-D16PERF-51 |
| COV-D16PERF-52 | role-permission | 项目写权限与范围保持 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-52 |
| COV-D16PERF-53 | state-transition | 更新版本必填与冲突保持 | SRC-006#document | TC-D16PERF-53 |
| COV-D16PERF-54 | state-transition | 同步过期revision保持 | SRC-006#document | TC-D16PERF-54 |
| COV-D16PERF-55 | recovery-idempotency | 迁移同步回执重放幂等 | SRC-006#document | TC-D16PERF-55 |
| COV-D16PERF-56 | error-guessing | 字段及线程组索引定位且不泄密 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED） | TC-D16PERF-56 |
| COV-D16PERF-57 | acceptance-criteria | 页面九整数非法输入保留并阻止保存 | SRC-006#document | TC-D16PERF-57 |
| COV-D16PERF-58 | acceptance-criteria | 页面精确整数写法转JSON数值 | SRC-006#document | TC-D16PERF-58 |
| COV-D16PERF-59 | acceptance-criteria | 页面错误率不四舍五入 | SRC-006#document | TC-D16PERF-59 |
| COV-D16PERF-60 | state-transition | 页面可选清空和模型切换 | SRC-006#document | TC-D16PERF-60 |
| COV-D16PERF-61 | data-lifecycle | 页面多组原输入独立 | SRC-006#document | TC-D16PERF-61 |
| COV-D16PERF-62 | acceptance-criteria | Runner坏数字文本受控预检拒绝 | SRC-006#document | TC-D16PERF-62 |
| COV-D16PERF-63 | compatibility-configuration | Runner原协议精确合法值兼容 | SRC-006#document | TC-D16PERF-63 |

## 测试用例

### 性能测试 / D16类型规则

#### TC-D16PERF-01 threadCount精确整数等值写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-01
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-01
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交threadCount的合法JSON整数、等值小数和指数写法 | threadCount保存为相同数学整数，返回200；没有截断小数部分，其他字段保留原语义 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-02 rampUpSeconds精确整数等值写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-02
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-02
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交rampUpSeconds的合法JSON整数、等值小数和指数写法 | rampUpSeconds保存为相同数学整数，返回200；没有截断小数部分，其他字段保留原语义 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-03 durationSeconds精确整数等值写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-03
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-03
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交durationSeconds的合法JSON整数、等值小数和指数写法 | durationSeconds保存为相同数学整数，返回200；没有截断小数部分，其他字段保留原语义 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-04 stepCount精确整数等值写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-04
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-04
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交stepCount的合法JSON整数、等值小数和指数写法 | stepCount保存为相同数学整数，返回200；没有截断小数部分，其他字段保留原语义 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-05 stepIntervalSeconds精确整数等值写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-05
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-05
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交stepIntervalSeconds的合法JSON整数、等值小数和指数写法 | stepIntervalSeconds保存为相同数学整数，返回200；没有截断小数部分，其他字段保留原语义 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-06 targetTps精确整数等值写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-06
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-06
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交targetTps的合法JSON整数、等值小数和指数写法 | targetTps保存为相同数学整数，返回200；没有截断小数部分，其他字段保留原语义 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-07 p95ThresholdMillis精确整数等值写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-07
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-07
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交p95ThresholdMillis的合法JSON整数、等值小数和指数写法 | p95ThresholdMillis保存为相同数学整数，返回200；没有截断小数部分，其他字段保留原语义 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-08 expectedStatusCode精确整数等值写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-08
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-08
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交expectedStatusCode的合法JSON整数、等值小数和指数写法 | expectedStatusCode保存为相同数学整数，返回200；没有截断小数部分，其他字段保留原语义 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-09 rendezvousUsers精确整数等值写法

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-09
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-09
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交rendezvousUsers的合法JSON整数、等值小数和指数写法 | rendezvousUsers保存为相同数学整数，返回200；没有截断小数部分，其他字段保留原语义 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“10、10.0、1e1按推荐I-A均精确得到10；按各字段边界允许或拒绝。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-10 threadCount边界及关联可行上限

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-10
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-10
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交threadCount上下界和各自相邻越界值；阶段/集合点按线程数准备，间隔同时验证总时长 | threadCount范围1..100000内且关联可行的边界返回200；相邻越界或总时长超限400，计划/线程组/审计未新增 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“线程/加压/持续/TPS/P95/状态/集合点逐字段边界及小数、类型错误验证，拒绝不创建计划。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-11 rampUpSeconds边界及关联可行上限

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-11
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-11
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交rampUpSeconds上下界和各自相邻越界值；阶段/集合点按线程数准备，间隔同时验证总时长 | rampUpSeconds范围0..86400内且关联可行的边界返回200；相邻越界或总时长超限400，计划/线程组/审计未新增 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“线程/加压/持续/TPS/P95/状态/集合点逐字段边界及小数、类型错误验证，拒绝不创建计划。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-12 durationSeconds边界及关联可行上限

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-12
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-12
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交durationSeconds上下界和各自相邻越界值；阶段/集合点按线程数准备，间隔同时验证总时长 | durationSeconds范围10..86400内且关联可行的边界返回200；相邻越界或总时长超限400，计划/线程组/审计未新增 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“线程/加压/持续/TPS/P95/状态/集合点逐字段边界及小数、类型错误验证，拒绝不创建计划。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-13 stepCount边界及关联可行上限

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-13
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-13
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交stepCount上下界和各自相邻越界值；阶段/集合点按线程数准备，间隔同时验证总时长 | stepCount范围2..20内且关联可行的边界返回200；相邻越界或总时长超限400，计划/线程组/审计未新增 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“线程/加压/持续/TPS/P95/状态/集合点逐字段边界及小数、类型错误验证，拒绝不创建计划。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-14 stepIntervalSeconds边界及关联可行上限

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-14
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-14
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交stepIntervalSeconds上下界和各自相邻越界值；阶段/集合点按线程数准备，间隔同时验证总时长 | stepIntervalSeconds范围1..86400内且关联可行的边界返回200；相邻越界或总时长超限400，计划/线程组/审计未新增 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“线程/加压/持续/TPS/P95/状态/集合点逐字段边界及小数、类型错误验证，拒绝不创建计划。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-15 targetTps边界及关联可行上限

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-15
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-15
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交targetTps上下界和各自相邻越界值；阶段/集合点按线程数准备，间隔同时验证总时长 | targetTps范围1..1000000内且关联可行的边界返回200；相邻越界或总时长超限400，计划/线程组/审计未新增 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“线程/加压/持续/TPS/P95/状态/集合点逐字段边界及小数、类型错误验证，拒绝不创建计划。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-16 p95ThresholdMillis边界及关联可行上限

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-16
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-16
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交p95ThresholdMillis上下界和各自相邻越界值；阶段/集合点按线程数准备，间隔同时验证总时长 | p95ThresholdMillis范围1..600000内且关联可行的边界返回200；相邻越界或总时长超限400，计划/线程组/审计未新增 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“线程/加压/持续/TPS/P95/状态/集合点逐字段边界及小数、类型错误验证，拒绝不创建计划。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-17 expectedStatusCode边界及关联可行上限

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-17
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-17
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交expectedStatusCode上下界和各自相邻越界值；阶段/集合点按线程数准备，间隔同时验证总时长 | expectedStatusCode范围100..599内且关联可行的边界返回200；相邻越界或总时长超限400，计划/线程组/审计未新增 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“线程/加压/持续/TPS/P95/状态/集合点逐字段边界及小数、类型错误验证，拒绝不创建计划。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-18 rendezvousUsers边界及关联可行上限

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-18
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-18
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交rendezvousUsers上下界和各自相邻越界值；阶段/集合点按线程数准备，间隔同时验证总时长 | rendezvousUsers范围2..10内且关联可行的边界返回200；相邻越界或总时长超限400，计划/线程组/审计未新增 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“线程/加压/持续/TPS/P95/状态/集合点逐字段边界及小数、类型错误验证，拒绝不创建计划。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-19 九类整数字段非整数拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-19
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-19
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 逐字段发送普通小数与可被double舍入的微小小数原始JSON | 全部400且计划、线程组、审计计数不变 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“1.5、负小数、1.0000000000000001原精度判非整数，400且业务未写入。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-20 数值字符串不能绕过九整数约束

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-20
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-20
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对九字段发送数字字符串和带空白数字字符串 | 全部400，不把字符串绑定为Integer | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“按N-A拒绝整数字段"10"、" 10 "、"10.0"和错误率"2.50"；若选B按明示白名单验收。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-21 数值字段布尔和容器错误类型

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-21
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-21
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对九字段提交true、数组和对象 | 全部400，不创建部分计划或线程组 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“数值字段true、[]、{}、空串拒绝；缺失/null分别按字段矩阵。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-22 大整数指数及原始非有限JSON拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：error-guessing
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-22
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-22
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 逐字段发送溢出值、大指数和原始NaN/Infinity/-Infinity | 受控400，无int回绕或500，无业务写入 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“18446744073709551616、1e10000及超范围值受控拒绝；不得long/int回绕或500。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-23 三必填整数缺失和真实null

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-23
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-23
- 来源引用：SRC-002#heading/D16-NULL：缺失-null与任务进度, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 分别省略或置null线程数、加压时长和持续时长 | 400，无计划/线程组/审计写入 | SRC-002#heading/D16-NULL：缺失-null与任务进度：“保留矩阵中既有可选字段的默认值、无阈值或永久有效语义；必填字段缺失/null返回400。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-24 可选数值缺失和null保持无设置

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-24
- 需求引用：REQ-D16PERF-NUM
- 验收标准引用：AC-D16PERF-24
- 来源引用：SRC-003#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 省略或置null可选TPS/P95/状态/集合点/错误率及非阶梯阶段字段 | 200，保持无阈值/无设置，非阶梯阶段字段仍null | SRC-003#document：“省略/null无对应设置，既有关联约束保持” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-25 阶梯阶段及间隔必须提供

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：decision-table
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-25
- 需求引用：REQ-D16PERF-REL
- 验收标准引用：AC-D16PERF-25
- 来源引用：SRC-003#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | STEPPED_RAMP分别缺失或置null阶段数、间隔 | 400，不生成不完整阶梯定义 | SRC-003#document：“仅STEPPED_RAMP必填；其他模型不允许配置” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-26 非阶梯禁止阶段参数

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：decision-table
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-26
- 需求引用：REQ-D16PERF-REL
- 验收标准引用：AC-D16PERF-26
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | STEADY/RAMP_UP传入阶段数或间隔 | 400；不给非阶梯套用阶梯参数 | SRC-006#document：“非阶梯禁止阶段字段” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-27 阶梯总时长临界与溢出

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-27
- 需求引用：REQ-D16PERF-REL
- 验收标准引用：AC-D16PERF-27
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 构造总时长恰好86400和刚超过86400的阶梯计划 | 临界接受，超过400，原计划和回执不变 | SRC-006#document：“阶梯总时长<=86400” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-28 阶梯TPS与阶段数关系

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：decision-table
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-28
- 需求引用：REQ-D16PERF-REL
- 验收标准引用：AC-D16PERF-28
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 设置TPS等于阶段数及小于阶段数；保持其他参数合法 | 相等接受，小于400；非法资料不写入 | SRC-006#document：“TPS>=阶段数” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-29 阶梯加压时长与集合点互斥

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：decision-table
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-29
- 需求引用：REQ-D16PERF-REL
- 验收标准引用：AC-D16PERF-29
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对阶梯分别设置非0加压时长及非空集合点 | 均400，不绕过原关联条件 | SRC-006#document：“阶梯禁止集合点” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-30 错误率真小数保持

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-30
- 需求引用：REQ-D16PERF-DEC
- 验收标准引用：AC-D16PERF-30
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 发送数值2.50及可行等值写法 | 200并保存精确数值2.50，不取整也不因整数规则拒绝 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“Header/Query/CSV/文本契约按S；errorThresholdPercent数值2.50允许，2.501拒绝，不能取整。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-31 错误率超过两位精度不舍入

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-31
- 需求引用：REQ-D16PERF-DEC
- 验收标准引用：AC-D16PERF-31
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 发送2.501、2.500和超过2位的微小小数 | 400，已存阈值不变；冗余尾零仍遵守原BigDecimal scale规则 | SRC-006#document：“2.50接受，2.501和2.500拒绝，不静默舍入。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-32 错误率0与100边界

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-32
- 需求引用：REQ-D16PERF-DEC
- 验收标准引用：AC-D16PERF-32
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交0/100及刚小于0、刚大于100数值 | 0和100接受，范围外400，无部分数据 | SRC-006#document：“errorThresholdPercent沿用BigDecimal.scale()<=2、0..100” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-33 错误率数字字符串和类型错误

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-33
- 需求引用：REQ-D16PERF-DEC
- 验收标准引用：AC-D16PERF-33
- 来源引用：SRC-002#heading/D16-N：数字字符串是否兼容, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 发送"2.50"、布尔、数组、对象、原始非有限JSON | 全部400，不自动解析字符串或非数值类型 | SRC-002#heading/D16-N：数字字符串是否兼容：“错误率仍允许数值小数，但不接受数字字符串。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-34 错误率缺失null无阈值

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-34
- 需求引用：REQ-D16PERF-DEC
- 验收标准引用：AC-D16PERF-34
- 来源引用：SRC-003#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 省略或置null错误率 | 200，errorThresholdPercent为null而不是0阈值 | SRC-003#document：“省略/null无阈值” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-35 请求头显式字符串保真

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-35
- 需求引用：REQ-D16PERF-TEXT
- 验收标准引用：AC-D16PERF-35
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 保存合法Header中字符串001、false、null及空串 | 返回200，按既有trim保留字符串内容 | SRC-006#document：“requestHeaders值真实null归空串且trim” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-36 query显式字符串保真

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-36
- 需求引用：REQ-D16PERF-TEXT
- 验收标准引用：AC-D16PERF-36
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 保存query中字符串001、false、null和空串 | 返回200，字符串及原空白语义保持 | SRC-006#document：“queryParameters值真实null归空串且保留其他空白” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-37 请求正文JSON字符串可用

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-37
- 需求引用：REQ-D16PERF-TEXT
- 验收标准引用：AC-D16PERF-37
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | POST正文显式使用包含JSON数字的字符串 | 保存文本不把内部JSON数字视为字段类型错误；GET/HEAD正文限制保持 | SRC-006#document：“非空正文仅非GET/HEAD允许” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-38 响应包含断言字符串与trim保持

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-38
- 需求引用：REQ-D16PERF-TEXT
- 验收标准引用：AC-D16PERF-38
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 保存显式响应包含字符串 | 返回200，内容按原非空trim规则保留 | SRC-006#document：“responseContains缺失/null/空白为null、非空trim” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-39 CSV模板与字符串数据保持

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-39
- 需求引用：REQ-D16PERF-TEXT
- 验收标准引用：AC-D16PERF-39
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 保存合法CSV字符串及引用该变量的正文/参数 | 200，保持CSV换行、变量及字符串含义，不新增模板语义 | SRC-006#document：“CSV换行规范化，保留唯一变量名、行列、模板检查。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-40 Header和query非字符串值拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-40
- 需求引用：REQ-D16PERF-TEXT
- 验收标准引用：AC-D16PERF-40
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 两类Map逐个发送数值/布尔/数组/对象值 | 全部400，不将值自动字符串化 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“列明文本字段拒绝数字/布尔/容器；"001"、"false"、"null"、模板引用及合法空串保留，真实null按矩阵。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-41 正文断言CSV非字符串拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-41
- 需求引用：REQ-D16PERF-TEXT
- 验收标准引用：AC-D16PERF-41
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 三类文本字段逐个发送数值/布尔/容器 | 全部400且没有业务写入 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“列明文本字段拒绝数字/布尔/容器；"001"、"false"、"null"、模板引用及合法空串保留，真实null按矩阵。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-42 文本真实null及缺失语义保持

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-42
- 需求引用：REQ-D16PERF-TEXT
- 验收标准引用：AC-D16PERF-42
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 分别缺失/null两类Map和三类正文/断言/CSV及Map内null值 | 200；Map空集合、Map值空串、可选文本null遵循原规则 | SRC-006#document：“两类Map缺失/null为空Map” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-43 合法编辑版本与历史清单冻结

- 类型：state
- 正反例：positive
- 优先级：P2
- 测试设计方法：state-transition
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-43
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-43
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 编辑既有计划的合法整数，读取版本及先前执行清单 | 版本+1，先前manifest不变，合法值保存 | SRC-006#document：“合法更新版本+1且原执行清单冻结” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-44 非法编辑不动原计划版本

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-44
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-44
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对既有计划PUT小数或非字符串值 | 400，版本、线程组、审计及原数据不变 | SRC-006#document：“拒绝不新增计划/线程组、执行/manifest/outbox、成功回执或审计。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-45 多线程组晚项非法整批原子

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-45
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-45
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交前组合法、后组含非法数值的计划 | 400，所有计划/线程组均未写入，字段错误能指向后组 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“创建/编辑/相应预览或保存入口同一字段规则，拒绝不产生部分子记录。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-46 合法迁移精确整数与小数

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-46
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-46
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 迁移完整快照中九整数等价数字、合法错误率与文本 | 200，新目标DRAFT，合法值保留且无执行副本 | SRC-006#document：“性能迁移使用完整版本1 snapshot/plans/threadGroups并新建DRAFT” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-47 非法迁移类型不得转换后入库

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-47
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-47
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 完整迁移资料逐字段放入非法整数、错误率字符串或非字符串文本 | 400，整批资产与成功回执/审计未写入 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-48 合法同步等值数字保持规范协议

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-48
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-48
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交完整规范化同步Item，整数用精确等值数字，错误率保持合法scale | 同步正常，旧目标id/status保持、版本+1，新目标DRAFT | SRC-006#document：“已存目标保持id/status并版本+1，新计划DRAFT” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-49 非法同步不得绕过字段类型

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-49
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-49
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 同步Item逐字段提交非法数字或非字符串文本 | 400，无计划版本、线程组、回执或审计变化 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“本字段可达的导入/同步用同一类型规则；按既有事务或逐项回执契约处理，非法项不写入、不计成功，保留原有明示的部分成功语义。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-50 存量读取不批量改写

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-50
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-50
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 读取旧定义和清单并核对数据库行与内容 | 读取可用，没有批量转换或重写 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“旧记录与旧清单只读可见；不批量改值，不推断被截断前原值。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-51 旧非法定义新入队前拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-51
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-51
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 自有测试旧计划注入越界整数或原始数字Map，尝试新执行 | 400，无新执行/manifest/outbox；旧已排队清单和状态不变 | SRC-006#document：“旧非法配置新入队按批准D16-OLD预检拒绝，旧已排队manifest和已运行状态不得由本次拒绝改写。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-52 项目写权限与范围保持

- 类型：permission
- 正反例：negative
- 优先级：P2
- 测试设计方法：role-permission
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-52
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-52
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 无写权限账号提交合法计划 | 沿用原403，目标数据未改变，不跨项目写入 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“原项目/账号权限和版本冲突回归；拒绝不跨项目、不产生成功业务审计。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-53 更新版本必填与冲突保持

- 类型：state
- 正反例：negative
- 优先级：P2
- 测试设计方法：state-transition
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-53
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-53
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 省略/null版本及过期版本编辑合法计划 | 缺失/null400，旧版本409，无版本或子记录改变 | SRC-006#document：“缺失/null400、旧版本409” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-54 同步过期revision保持

- 类型：state
- 正反例：negative
- 优先级：P2
- 测试设计方法：state-transition
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-54
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-54
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 读取revision后合法修改目标，再提交旧revision | 409，旧同步不写入新内容 | SRC-006#document：“校验目标revision/actor” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-55 迁移同步回执重放幂等

- 类型：recovery
- 正反例：positive
- 优先级：P2
- 测试设计方法：recovery-idempotency
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-55
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-55
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 重复提交同一成功迁移和同步请求 | 返回原回执，不重复新增资产、版本或审计 | SRC-006#document：“重放返回原回执” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-56 字段及线程组索引定位且不泄密

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：error-guessing
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-56
- 需求引用：REQ-D16PERF-LIFE
- 验收标准引用：AC-D16PERF-56
- 来源引用：SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 在后线程组的数值/条件值提交合成私有标记 | 400错误可定位字段/索引，不回显合成提交值、条件键或正文 | SRC-002#heading/35组验收目标（全部NOT_EXECUTED）：“UI小数提示并阻止提交，不先四舍五入；HTTP400沿用错误信封并可定位字段/索引，不回显认证或正文值。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-57 页面九整数非法输入保留并阻止保存

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-57
- 需求引用：REQ-D16PERF-UI
- 验收标准引用：AC-D16PERF-57
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 各可见整数控件输入小数/越界值点击保存 | 保留原文并显示线程组/字段错误，业务POST为0 | SRC-006#document：“UI应校验原始文本再转Number，拒绝不舍入、不发业务POST，保留草稿且可定位线程组/字段” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-58 页面精确整数写法转JSON数值

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-58
- 需求引用：REQ-D16PERF-UI
- 验收标准引用：AC-D16PERF-58
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 各可见整数字段输入精确等值写法，保存合法计划 | 发出合法JSON number，后端获得同一精确整数 | SRC-006#document：“UI应校验原始文本再转Number” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-59 页面错误率不四舍五入

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-59
- 需求引用：REQ-D16PERF-UI
- 验收标准引用：AC-D16PERF-59
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 输入错误率2.501点击保存，再改为2.50 | 非法值保留且不POST，合法值数值2.5成功发送，不取整 | SRC-006#document：“2.50接受，2.501和2.500拒绝，不静默舍入。” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-60 页面可选清空和模型切换

- 类型：state
- 正反例：positive
- 优先级：P2
- 测试设计方法：state-transition
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-60
- 需求引用：REQ-D16PERF-UI
- 验收标准引用：AC-D16PERF-60
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 清空可选数值，切换阶梯/非阶梯并保存 | 清空为无设置，不把空串变0；非适用字段按原清理语义 | SRC-006#document：“可选字段留空为无设置，禁用的非适用字段遵循原loadModelChanged清理” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-61 页面多组原输入独立

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-61
- 需求引用：REQ-D16PERF-UI
- 验收标准引用：AC-D16PERF-61
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 新增线程组并修改不同数字，再移除一组后保存 | 保留组的数字来自自身，非法输入不会串到其他组 | SRC-006#document：“不串用其他线程组的原输入” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-62 Runner坏数字文本受控预检拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-62
- 需求引用：REQ-D16PERF-RUN
- 验收标准引用：AC-D16PERF-62
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 给受控Runner预检提供非法数值或非字符串参数 | 预检失败，不启动JMeter或创建目标请求 | SRC-006#document：“JMeter runtime协议版本1..6已有Number.isSafeInteger和STRING参数检查” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。

#### TC-D16PERF-63 Runner原协议精确合法值兼容

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：逐字段或独立入口/关联结果建立COV；同一目标的数据变体不重复计数。
- 覆盖项引用：COV-D16PERF-63
- 需求引用：REQ-D16PERF-RUN
- 验收标准引用：AC-D16PERF-63
- 来源引用：SRC-006#document, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有全新隔离账号和项目，按本案例准备所需合法计划、版本或线程组。
- 测试数据：{其余字段合法的自有隔离计划}；各变体保持其他字段/版本不变

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 用原协议合法工件进行纯校验，不启动负载 | 返回规范化工件，控制面仍要求持续至少10秒，原Runner内部短时兼容保留 | SRC-006#document：“本轮保持既有Runner协议短时兼容，不放宽控制面10秒下界” |

> 备注：先行设计不写执行状态；工程变体/环境次数不加正式分母。
