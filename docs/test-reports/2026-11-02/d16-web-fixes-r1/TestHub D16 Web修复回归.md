# TestHub D16 Web修复回归

- 版本：2026-11-02 五项A
- 范围：仅OBS-11-E021；移动与质量不推进，其他9项/12环境/升级退出调查暂缓。

## 汇总

- 用例总数：56
- 正例/反例：22/34
- 需求覆盖：7/7（100.0%）
- 验收标准覆盖：56/56（100.0%）
- 设计覆盖项：56/56（100.0%）
- 优先级分布：P2=56
- 用例类型分布：boundary=5、compatibility=9、negative=24、permission=1、positive=9、recovery=1、state=3、usability=4
- 测试设计方法分布：acceptance-criteria=8、boundary-value-2=5、compatibility-configuration=9、data-lifecycle=10、equivalence-partitioning=19、recovery-idempotency=1、role-permission=1、state-transition=3
- 文档审查问题：0（无）
- 用例质量：99.89（pass）
- 文档就绪度：100.0（ready）

## 来源

| ID | 名称 | 类型 | 路径/备注 |
|---|---|---|---|
| SRC-001 | DECISIONS.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/DECISIONS.json |
| SRC-002 | D16-RULES-AND-ACCEPTANCE.md | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/D16-RULES-AND-ACCEPTANCE.md |
| SRC-003 | FIELD-MATRIX.md | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/FIELD-MATRIX.md |
| SRC-004 | REVIEW-APPROVAL.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-rule-review/REVIEW-APPROVAL.json |
| SRC-005 | APPROVED-SCOPE.json | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-web-fixes-r1/APPROVED-SCOPE.json |
| SRC-006 | BASELINE-CONTRACT.md | requirement | /Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-11-02/d16-web-fixes-r1/BASELINE-CONTRACT.md |

## 需求覆盖

| 需求ID | 标题 | 风险 | 覆盖用例 |
|---|---|---|---|
| REQ-D16WEB-NUM | Web D16 NUM | medium | TC-D16WEB-01, TC-D16WEB-02, TC-D16WEB-03, TC-D16WEB-04, TC-D16WEB-05, TC-D16WEB-06, TC-D16WEB-07, TC-D16WEB-08, TC-D16WEB-09, TC-D16WEB-10, TC-D16WEB-11, TC-D16WEB-12, TC-D16WEB-13, TC-D16WEB-14, TC-D16WEB-15, TC-D16WEB-16, TC-D16WEB-17, TC-D16WEB-18, TC-D16WEB-19, TC-D16WEB-20, TC-D16WEB-21 |
| REQ-D16WEB-TEXT | Web D16 TEXT | medium | TC-D16WEB-22, TC-D16WEB-23, TC-D16WEB-24, TC-D16WEB-25, TC-D16WEB-26, TC-D16WEB-27 |
| REQ-D16WEB-LIFE | Web D16 LIFE | medium | TC-D16WEB-28, TC-D16WEB-29, TC-D16WEB-30, TC-D16WEB-31, TC-D16WEB-38, TC-D16WEB-43, TC-D16WEB-44, TC-D16WEB-45 |
| REQ-D16WEB-EXCHANGE | Web D16 EXCHANGE | medium | TC-D16WEB-32, TC-D16WEB-33, TC-D16WEB-34, TC-D16WEB-35, TC-D16WEB-36, TC-D16WEB-37 |
| REQ-D16WEB-OLD | Web D16 OLD | medium | TC-D16WEB-39, TC-D16WEB-40, TC-D16WEB-41, TC-D16WEB-42 |
| REQ-D16WEB-UI | Web D16 UI | medium | TC-D16WEB-46, TC-D16WEB-47, TC-D16WEB-48, TC-D16WEB-49, TC-D16WEB-50, TC-D16WEB-51 |
| REQ-D16WEB-RUN | Web D16 RUN | medium | TC-D16WEB-52, TC-D16WEB-53, TC-D16WEB-54, TC-D16WEB-55, TC-D16WEB-56 |

## 测试设计覆盖项

| 覆盖项ID | 测试方法 | 覆盖目标 | 来源引用 | 覆盖用例 |
|---|---|---|---|---|
| COV-D16WEB-01 | equivalence-partitioning | 视口宽精确等值 | SRC-006#document | TC-D16WEB-01 |
| COV-D16WEB-02 | equivalence-partitioning | 视口高精确等值 | SRC-006#document | TC-D16WEB-02 |
| COV-D16WEB-03 | boundary-value-2 | 宽边界及相邻越界 | SRC-006#document | TC-D16WEB-03 |
| COV-D16WEB-04 | boundary-value-2 | 高边界及相邻越界 | SRC-006#document | TC-D16WEB-04 |
| COV-D16WEB-05 | equivalence-partitioning | 视口普通及微小小数 | SRC-006#document | TC-D16WEB-05 |
| COV-D16WEB-06 | equivalence-partitioning | 视口字符串和错误类型 | SRC-006#document | TC-D16WEB-06 |
| COV-D16WEB-07 | compatibility-configuration | 视口缺失null默认 | SRC-006#document | TC-D16WEB-07 |
| COV-D16WEB-08 | equivalence-partitioning | WAIT精确等值超时 | SRC-006#document | TC-D16WEB-08 |
| COV-D16WEB-09 | boundary-value-2 | WAIT毫秒边界 | SRC-006#document | TC-D16WEB-09 |
| COV-D16WEB-10 | equivalence-partitioning | 非WAIT精确等值超时 | SRC-006#document | TC-D16WEB-10 |
| COV-D16WEB-11 | boundary-value-2 | 非WAIT不同下界 | SRC-006#document | TC-D16WEB-11 |
| COV-D16WEB-12 | equivalence-partitioning | 步骤小数原始精度 | SRC-006#document | TC-D16WEB-12 |
| COV-D16WEB-13 | equivalence-partitioning | 步骤数字字符串和容器 | SRC-006#document | TC-D16WEB-13 |
| COV-D16WEB-14 | equivalence-partitioning | 大整数指数及原始非有限JSON | SRC-006#document | TC-D16WEB-14 |
| COV-D16WEB-15 | equivalence-partitioning | WAIT缺失null必须拒绝 | SRC-006#document | TC-D16WEB-15 |
| COV-D16WEB-16 | compatibility-configuration | 非WAIT可选超时默认 | SRC-006#document | TC-D16WEB-16 |
| COV-D16WEB-17 | equivalence-partitioning | 执行超时精确等值 | SRC-006#document | TC-D16WEB-17 |
| COV-D16WEB-18 | boundary-value-2 | 执行秒数边界 | SRC-006#document | TC-D16WEB-18 |
| COV-D16WEB-19 | equivalence-partitioning | 执行小数字符串类型拒绝 | SRC-006#document | TC-D16WEB-19 |
| COV-D16WEB-20 | compatibility-configuration | 缺失执行正文及null默认 | SRC-006#document | TC-D16WEB-20 |
| COV-D16WEB-21 | equivalence-partitioning | 定时同一执行超时入口 | SRC-006#document | TC-D16WEB-21 |
| COV-D16WEB-22 | equivalence-partitioning | 变量显式字符串保真 | SRC-006#document | TC-D16WEB-22 |
| COV-D16WEB-23 | equivalence-partitioning | 变量非字符串值 | SRC-006#document | TC-D16WEB-23 |
| COV-D16WEB-24 | compatibility-configuration | 变量真实null兼容 | SRC-006#document | TC-D16WEB-24 |
| COV-D16WEB-25 | equivalence-partitioning | 步骤value和expectedText字符串 | SRC-006#document | TC-D16WEB-25 |
| COV-D16WEB-26 | equivalence-partitioning | 步骤文本错误类型 | SRC-006#document | TC-D16WEB-26 |
| COV-D16WEB-27 | equivalence-partitioning | 步骤文本null和必填关系 | SRC-006#document | TC-D16WEB-27 |
| COV-D16WEB-28 | state-transition | 合法编辑与旧清单冻结 | SRC-006#document | TC-D16WEB-28 |
| COV-D16WEB-29 | data-lifecycle | 非法配置编辑原子性 | SRC-006#document | TC-D16WEB-29 |
| COV-D16WEB-30 | data-lifecycle | 非法场景编辑原子性 | SRC-006#document | TC-D16WEB-30 |
| COV-D16WEB-31 | data-lifecycle | 后步骤非法整批拒绝 | SRC-006#document | TC-D16WEB-31 |
| COV-D16WEB-32 | compatibility-configuration | 合法完整迁移等值 | SRC-006#document | TC-D16WEB-32 |
| COV-D16WEB-33 | data-lifecycle | 非法完整迁移不得绕过 | SRC-006#document | TC-D16WEB-33 |
| COV-D16WEB-34 | compatibility-configuration | 同步等值数字精确规范化 | SRC-006#document | TC-D16WEB-34 |
| COV-D16WEB-35 | data-lifecycle | 同步原始微小小数及文本错误 | SRC-006#document | TC-D16WEB-35 |
| COV-D16WEB-36 | recovery-idempotency | 交换回执幂等重放 | SRC-006#document | TC-D16WEB-36 |
| COV-D16WEB-37 | state-transition | 同步过期revision | SRC-006#document | TC-D16WEB-37 |
| COV-D16WEB-38 | role-permission | 写权限与项目隔离 | SRC-006#document | TC-D16WEB-38 |
| COV-D16WEB-39 | compatibility-configuration | 存量只读无批量改写 | SRC-006#document | TC-D16WEB-39 |
| COV-D16WEB-40 | data-lifecycle | 旧非法新手动入队 | SRC-006#document | TC-D16WEB-40 |
| COV-D16WEB-41 | data-lifecycle | 旧非法定时和重试入队 | SRC-006#document | TC-D16WEB-41 |
| COV-D16WEB-42 | data-lifecycle | 旧排队运行工件预检 | SRC-006#document | TC-D16WEB-42 |
| COV-D16WEB-43 | equivalence-partitioning | 字段索引与隐私错误 | SRC-006#document | TC-D16WEB-43 |
| COV-D16WEB-44 | compatibility-configuration | 非目标字段契约保持 | SRC-006#document | TC-D16WEB-44 |
| COV-D16WEB-45 | data-lifecycle | 页面对象引用保持 | SRC-006#document | TC-D16WEB-45 |
| COV-D16WEB-46 | acceptance-criteria | 页面视口非法原文 | SRC-006#document | TC-D16WEB-46 |
| COV-D16WEB-47 | acceptance-criteria | 页面视口等值写法 | SRC-006#document | TC-D16WEB-47 |
| COV-D16WEB-48 | acceptance-criteria | 页面WAIT及非WAIT超时 | SRC-006#document | TC-D16WEB-48 |
| COV-D16WEB-49 | acceptance-criteria | 页面执行在浏览器准备前拒绝 | SRC-006#document | TC-D16WEB-49 |
| COV-D16WEB-50 | state-transition | 页面清空及类型切换 | SRC-006#document | TC-D16WEB-50 |
| COV-D16WEB-51 | data-lifecycle | 页面变量和步骤移除归属 | SRC-006#document | TC-D16WEB-51 |
| COV-D16WEB-52 | acceptance-criteria | Runner视口在资源前拒绝 | SRC-006#document | TC-D16WEB-52 |
| COV-D16WEB-53 | acceptance-criteria | Runner超时类型及边界 | SRC-006#document | TC-D16WEB-53 |
| COV-D16WEB-54 | acceptance-criteria | Runner变量与步骤文本 | SRC-006#document | TC-D16WEB-54 |
| COV-D16WEB-55 | compatibility-configuration | Runner合法工件与受控真实步骤 | SRC-006#document | TC-D16WEB-55 |
| COV-D16WEB-56 | acceptance-criteria | Runner运行请求拒绝先于准备 | SRC-006#document | TC-D16WEB-56 |

## 测试用例

### Web UI自动化 / D16类型规则

#### TC-D16WEB-01 视口宽精确等值

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-01
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-01
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 按原始JSON提交合法整数、等值小数、指数宽度 | 200，精确保存同一宽度整数 | SRC-006#document：“viewportWidth 320..3840” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-02 视口高精确等值

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-02
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-02
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 按原始JSON提交合法整数、等值小数、指数高度 | 200，精确保存同一高度整数 | SRC-006#document：“viewportHeight 320..2160” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-03 宽边界及相邻越界

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-03
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-03
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交320/3840及319/3841宽度 | 边界200，越界400，无部分资产 | SRC-006#document：“viewportWidth 320..3840” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-04 高边界及相邻越界

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-04
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-04
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交320/2160及319/2161高度 | 边界200，越界400，无部分资产 | SRC-006#document：“viewportHeight 320..2160” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-05 视口普通及微小小数

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-05
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-05
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交有小数部分的原始宽高JSON | 400，不截断、不新增配置 | SRC-006#document：“页面在转换Number前检查原始文本” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-06 视口字符串和错误类型

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-06
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-06
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交数字字符串、布尔、数组、对象宽高 | 400，不隐式转换JSON类型 | SRC-006#document：“类型规则只对D16矩阵字段生效” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-07 视口缺失null默认

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-07
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-07
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 分别缺失或置null视口宽高 | 200，默认宽1440高900 | SRC-006#document：“省略/null为1440/900” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-08 WAIT精确等值超时

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-08
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-08
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | WAIT使用精确等值JSON整数超时 | 200，同一整数保存 | SRC-006#document：“WAIT timeoutMillis必填1..60000” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-09 WAIT毫秒边界

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-09
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-09
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交WAIT 1/60000及0/60001 | 边界200，越界400且无场景写入 | SRC-006#document：“WAIT timeoutMillis必填1..60000” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-10 非WAIT精确等值超时

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-10
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-10
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 非WAIT步骤使用精确等值整数超时 | 200，精确保存毫秒值 | SRC-006#document：“其他类型有值时100..60000” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-11 非WAIT不同下界

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-11
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-11
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 非WAIT提交100/60000及99/60001 | 边界200，越界400，不借WAIT下界绕过 | SRC-006#document：“其他类型有值时100..60000” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-12 步骤小数原始精度

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-12
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-12
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | WAIT/非WAIT分别提交普通和微小小数 | 400，步骤/场景/审计未写入 | SRC-006#document：“原始微小小数在控制面/原始存储读取边界检查” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-13 步骤数字字符串和容器

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-13
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-13
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 两类步骤传数字字符串、布尔或容器超时 | 400，不解析为整数 | SRC-006#document：“类型规则只对D16矩阵字段生效” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-14 大整数指数及原始非有限JSON

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-14
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-14
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 目标数字字段提交溢出、大指数、原始NaN/Infinity | 受控400，不回绕或500，无写入 | SRC-006#document：“原始微小小数在控制面/原始存储读取边界检查” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-15 WAIT缺失null必须拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-15
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-15
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 省略或置null WAIT超时 | 400，不默认为0或30000 | SRC-006#document：“WAIT清空不能变为0或默认等待” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-16 非WAIT可选超时默认

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-16
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-16
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 省略或置null非WAIT超时并读取/入队 | 仍存null，Runner执行采用30000 | SRC-006#document：“缺失/null保持null并由Runner默认30000” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-17 执行超时精确等值

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-17
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-17
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 执行请求使用精确等值数字超时 | 200，新执行deadline与整数超时一致 | SRC-006#document：“execute timeoutSeconds 10..3600” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-18 执行秒数边界

- 类型：boundary
- 正反例：negative
- 优先级：P2
- 测试设计方法：boundary-value-2
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-18
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-18
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交执行10/3600及9/3601 | 边界入队，越界400且无执行资产 | SRC-006#document：“execute timeoutSeconds 10..3600” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-19 执行小数字符串类型拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-19
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-19
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 执行提交小数、微小小数、数字字符串及容器 | 400，无execution/manifest/outbox | SRC-006#document：“execute timeoutSeconds 10..3600” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-20 缺失执行正文及null默认

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-20
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-20
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 执行时缺失正文、空对象或超时null | 200，默认执行300秒 | SRC-006#document：“缺失正文、空对象或null值均默认300” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-21 定时同一执行超时入口

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-21
- 需求引用：REQ-D16WEB-NUM
- 验收标准引用：AC-D16WEB-21
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 定时配置提交合法等值数字再提交小数/数字字符串 | 合法200，非法400，原定时/Quartz/审计未改变 | SRC-006#document：“定时计划的timeoutSeconds是执行超时相同值的另一写入口” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-22 变量显式字符串保真

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-22
- 需求引用：REQ-D16WEB-TEXT
- 验收标准引用：AC-D16WEB-22
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 提交001/false/null/空串及合法占位符变量值 | 200，原文本保留 | SRC-006#document：“值为字符串001/false/null按原文本保留” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-23 变量非字符串值

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-23
- 需求引用：REQ-D16WEB-TEXT
- 验收标准引用：AC-D16WEB-23
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 变量值依次提交数值、布尔、数组、对象 | 400，不自动字符串化 | SRC-006#document：“variables缺失/null为空Map” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-24 变量真实null兼容

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-24
- 需求引用：REQ-D16WEB-TEXT
- 验收标准引用：AC-D16WEB-24
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 缺失/null变量集合及变量值真实null | 集合空Map，值null归空串 | SRC-006#document：“variables缺失/null为空Map，值null归空串” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-25 步骤value和expectedText字符串

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-25
- 需求引用：REQ-D16WEB-TEXT
- 验收标准引用：AC-D16WEB-25
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | FILL/ASSERT_TEXT提交显式字符串及变量占位符 | 200，保留文本和原trim规则 | SRC-006#document：“步骤value/expectedText按原blankToNull trim处理” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-26 步骤文本错误类型

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-26
- 需求引用：REQ-D16WEB-TEXT
- 验收标准引用：AC-D16WEB-26
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 步骤value/expectedText传数值、布尔、容器 | 400，无步骤或场景写入 | SRC-006#document：“类型规则只对D16矩阵字段生效” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-27 步骤文本null和必填关系

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-27
- 需求引用：REQ-D16WEB-TEXT
- 验收标准引用：AC-D16WEB-27
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | FILL值或ASSERT_TEXT期望缺失/null，另试非要求类型可选空值 | 必需内容400，其他按既有可选空值规则保存 | SRC-006#document：“FILL值必填、ASSERT_TEXT期望必填且禁止换行” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-28 合法编辑与旧清单冻结

- 类型：state
- 正反例：positive
- 优先级：P2
- 测试设计方法：state-transition
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-28
- 需求引用：REQ-D16WEB-LIFE
- 验收标准引用：AC-D16WEB-28
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 修改配置及场景合法数值并核对旧manifest | 版本+1，旧manifest保持原内容 | SRC-006#document：“合法编辑版本+1，旧执行manifest冻结” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-29 非法配置编辑原子性

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-29
- 需求引用：REQ-D16WEB-LIFE
- 验收标准引用：AC-D16WEB-29
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 已有配置PUT非法宽/高小数 | 400，旧版本和配置不变 | SRC-006#document：“创建/编辑拒绝不写部分资产或成功审计” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-30 非法场景编辑原子性

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-30
- 需求引用：REQ-D16WEB-LIFE
- 验收标准引用：AC-D16WEB-30
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 已有场景PUT非法步骤或变量类型 | 400，旧场景/步骤/版本未改 | SRC-006#document：“创建/编辑拒绝不写部分资产或成功审计” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-31 后步骤非法整批拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-31
- 需求引用：REQ-D16WEB-LIFE
- 验收标准引用：AC-D16WEB-31
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 前步骤合法、后步骤含非法超时/文本 | 400，无部分场景，定位后步骤索引 | SRC-006#document：“页面在转换Number前检查原始文本” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-32 合法完整迁移等值

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-32
- 需求引用：REQ-D16WEB-EXCHANGE
- 验收标准引用：AC-D16WEB-32
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对完整迁移快照中目标整数用等值写法 | 导入正常，配置/定时停用、不复制执行历史 | SRC-006#document：“导入配置/定时计划停用，不复制执行历史” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-33 非法完整迁移不得绕过

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-33
- 需求引用：REQ-D16WEB-EXCHANGE
- 验收标准引用：AC-D16WEB-33
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 迁移中的目标数字/变量/文本逐项错误类型 | 400，整批资产及成功回执/审计未写入 | SRC-006#document：“迁移为完整版本1快照” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-34 同步等值数字精确规范化

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-34
- 需求引用：REQ-D16WEB-EXCHANGE
- 验收标准引用：AC-D16WEB-34
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 完整同步Item用目标整数的.0/指数等值写法 | 正常同步，保持其他完整规范化约束 | SRC-006#document：“仅目标数字字段的精确等值写法可规范为整数” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-35 同步原始微小小数及文本错误

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-35
- 需求引用：REQ-D16WEB-EXCHANGE
- 验收标准引用：AC-D16WEB-35
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | definition通用Map内放原始微小小数或非字符串值 | 400，原精度先判断、不改资产/版本/回执 | SRC-006#document：“解析原始浮点时需保留精度，再检查目标字段” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-36 交换回执幂等重放

- 类型：recovery
- 正反例：positive
- 优先级：P2
- 测试设计方法：recovery-idempotency
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-36
- 需求引用：REQ-D16WEB-EXCHANGE
- 验收标准引用：AC-D16WEB-36
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 重放同一迁移/同步请求 | 返回原回执，无重复资产或版本 | SRC-006#document：“事务和回执重放均保留” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-37 同步过期revision

- 类型：state
- 正反例：negative
- 优先级：P2
- 测试设计方法：state-transition
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-37
- 需求引用：REQ-D16WEB-EXCHANGE
- 验收标准引用：AC-D16WEB-37
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 读取revision后修改目标，提交旧revision | 409且无同步写入 | SRC-006#document：“同步旧revision409” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-38 写权限与项目隔离

- 类型：permission
- 正反例：negative
- 优先级：P2
- 测试设计方法：role-permission
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-38
- 需求引用：REQ-D16WEB-LIFE
- 验收标准引用：AC-D16WEB-38
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 其他账号/只读角色发起合法写入或执行 | 403，不跨项目写入 | SRC-006#document：“组织/项目权限” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-39 存量只读无批量改写

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-39
- 需求引用：REQ-D16WEB-OLD
- 验收标准引用：AC-D16WEB-39
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 读取旧场景与manifest并核对原始数据库内容 | 保持原读契约，无批量转换或重写 | SRC-006#document：“旧定义及旧manifest可读无批量重写” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-40 旧非法新手动入队

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-40
- 需求引用：REQ-D16WEB-OLD
- 验收标准引用：AC-D16WEB-40
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 旧配置注入越界视口/步骤原始小数或数字变量，再手动执行 | 400，无新执行/清单/outbox，旧清单不变 | SRC-006#document：“旧非法定义新入队不得生成execution/manifest/outbox” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-41 旧非法定时和重试入队

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-41
- 需求引用：REQ-D16WEB-OLD
- 验收标准引用：AC-D16WEB-41
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 准备旧非法定义，从定时与重试入口触发 | 拒绝创建新执行，不绕过共享预检 | SRC-006#document：“手动/定时/重试新入队使用同一目标规则” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-42 旧排队运行工件预检

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-42
- 需求引用：REQ-D16WEB-OLD
- 验收标准引用：AC-D16WEB-42
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 旧排队manifest原始目标类型改坏后申请运行配置 | 400，未签发可用工件或成功审计，旧manifest不重写 | SRC-006#document：“不先发可用运行工件或成功签发审计” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-43 字段索引与隐私错误

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：equivalence-partitioning
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-43
- 需求引用：REQ-D16WEB-LIFE
- 验收标准引用：AC-D16WEB-43
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 后步骤目标字段提交合成私有标记 | 400只定位固定字段/索引，不回显值或变量键 | SRC-006#document：“非法数值保留并定位字段/步骤” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-44 非目标字段契约保持

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-44
- 需求引用：REQ-D16WEB-LIFE
- 验收标准引用：AC-D16WEB-44
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 对metadata及非目标重试参数执行原可用写法 | 原行为保持，不扩大新类型政策 | SRC-006#document：“maxRetryAttempts和retryBackoffSeconds不改” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-45 页面对象引用保持

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-45
- 需求引用：REQ-D16WEB-LIFE
- 验收标准引用：AC-D16WEB-45
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 场景通过pageObjectId引用既有对象并入队 | 定位器按原引用解析，旧manifest仍冻结 | SRC-006#document：“引用页面对象的解析与功能计划关联保持” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-46 页面视口非法原文

- 类型：usability
- 正反例：negative
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-46
- 需求引用：REQ-D16WEB-UI
- 验收标准引用：AC-D16WEB-46
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 输入宽高小数/越界并保存 | 保留原文/字段错误，业务写请求0 | SRC-006#document：“页面在转换Number前检查原始文本” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-47 页面视口等值写法

- 类型：usability
- 正反例：positive
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-47
- 需求引用：REQ-D16WEB-UI
- 验收标准引用：AC-D16WEB-47
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 输入合法.0/指数宽高并保存 | 发送精确JSON number | SRC-006#document：“页面在转换Number前检查原始文本” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-48 页面WAIT及非WAIT超时

- 类型：usability
- 正反例：negative
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-48
- 需求引用：REQ-D16WEB-UI
- 验收标准引用：AC-D16WEB-48
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 两类步骤输入小数/错误下界，后改合法整数 | 非法不提交且保留原文，合法值精确发送 | SRC-006#document：“WAIT timeoutMillis必填1..60000” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-49 页面执行在浏览器准备前拒绝

- 类型：usability
- 正反例：negative
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-49
- 需求引用：REQ-D16WEB-UI
- 验收标准引用：AC-D16WEB-49
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 执行输入非法小数或数字溢出并提交 | 不调用prepareBrowser、不发业务POST | SRC-006#document：“执行非法输入在prepareBrowser前拒绝” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-50 页面清空及类型切换

- 类型：state
- 正反例：positive
- 优先级：P2
- 测试设计方法：state-transition
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-50
- 需求引用：REQ-D16WEB-UI
- 验收标准引用：AC-D16WEB-50
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 非WAIT超时清空、WAIT必填及类型切换 | 可选值无设置，WAIT空拒绝，类型切换不复用旧原文 | SRC-006#document：“类型切换/步骤删除不串用原输入” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-51 页面变量和步骤移除归属

- 类型：positive
- 正反例：positive
- 优先级：P2
- 测试设计方法：data-lifecycle
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-51
- 需求引用：REQ-D16WEB-UI
- 验收标准引用：AC-D16WEB-51
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 变量非字符串校验，删除旧步骤后保存另一合法超时步骤 | 变量沿原字符串防线；保留步骤使用自身原输入 | SRC-006#document：“类型切换/步骤删除不串用原输入” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-52 Runner视口在资源前拒绝

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-52
- 需求引用：REQ-D16WEB-RUN
- 验收标准引用：AC-D16WEB-52
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 向纯运行预检提供视口小数/字符串/越界 | 拒绝，不准备或启动浏览器 | SRC-006#document：“在prepareBrowser/browser.launch前检查目标视口” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-53 Runner超时类型及边界

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-53
- 需求引用：REQ-D16WEB-RUN
- 验收标准引用：AC-D16WEB-53
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 给WAIT/非WAIT提供非法超时/必填null | 预检拒绝，非WAIT null仍默认30000 | SRC-006#document：“WAIT必填及非WAIT null默认30000保持” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-54 Runner变量与步骤文本

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-54
- 需求引用：REQ-D16WEB-RUN
- 验收标准引用：AC-D16WEB-54
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 传非字符串变量或步骤值/期望，另试允许null | 非法类型预检拒绝，原null/必填语义保持 | SRC-006#document：“变量/文本类型” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-55 Runner合法工件与受控真实步骤

- 类型：compatibility
- 正反例：positive
- 优先级：P2
- 测试设计方法：compatibility-configuration
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-55
- 需求引用：REQ-D16WEB-RUN
- 验收标准引用：AC-D16WEB-55
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | 验证合法工件并在自有loopback真实headless Chrome跑受控步骤 | 等值整数/文本保持；NAVIGATE/FILL/ASSERT_TEXT/WAIT受控完成 | SRC-006#document：“自有loopback/headless实际NAVIGATE/FILL/ASSERT_TEXT/WAIT步骤验收” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。

#### TC-D16WEB-56 Runner运行请求拒绝先于准备

- 类型：negative
- 正反例：negative
- 优先级：P2
- 测试设计方法：acceptance-criteria
- 设计依据：每个字段、入口、状态或资源前拒绝目标独立COV；同一分区变体不重复计数。
- 覆盖项引用：COV-D16WEB-56
- 需求引用：REQ-D16WEB-RUN
- 验收标准引用：AC-D16WEB-56
- 来源引用：SRC-006#document, SRC-002#heading/35组验收目标（全部NOT_EXECUTED）, SRC-004#json-root, SRC-005#json-root
- 前置条件：自有隔离账号/项目；按场景准备合法浏览器配置、步骤、版本及必要旧数据夹具。
- 测试数据：{合法隔离配置和场景}；只修改本案例目标字段，其他字段和作用域保持合法。

| # | 操作 | 预期结果 | 原文证据 |
|---:|---|---|---|
| 1 | Runner从受控HTTP收到非法运行工件后调用run | 失败且prepareBrowser计数0，无工作区或浏览器资源 | SRC-006#document：“Runner运行工件在prepareBrowser/browser.launch前检查” |

> 备注：先行设计无执行状态；工程层、变体及环境次数不加正式分母。
