# 47项逐条处理建议

全部状态：PROPOSED_NOT_EXECUTED；优先级不是缺陷严重度，原观察尚未关闭。

## P1 · OBS-03B-E002 · 普通参数与密钥同名覆盖

模块：项目环境、参数与密钥；处理类型：确认规则后处理；规则卡：D02。

**历史事实**：普通参数同名保存会把原密钥行转为非密钥，密钥列表不再列出。

**建议**：建议普通参数和密钥跨类型同名返回409，禁止隐式转换。

**验收关注**：普通/密钥并发同名不互相覆盖；原密钥加密和值保持；同类型合法更新仍可用。

**兼容及限制**：观察证明类型被覆盖，不等于旧密钥已被解密泄露；不自动恢复或转换存量值。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03b-environments/execution-results.json)

## P1 · OBS-03D-E006 · 停用删除改密后的已登录会话

模块：账号管理与系统参数；处理类型：确认规则后处理；规则卡：D05。

**历史事实**：停用、删除或改密后旧会话仍可访问me；旧密码新登录被拒。

**建议**：建议停用/删除使全部Session失效，管理员重置密码注销目标会话，本人改密采用会话轮换。

**验收关注**：旧Cookie的me及敏感读写均按规则401；恢复后新登录正常；其他账号不受影响。

**兼容及限制**：目前仅证实旧会话me仍200，不能据此断言全部业务写入可继续；PAT联动规则另明确。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-03d-accounts-config/regression-r1/execution-results.json)

## P1 · OBS-07-E013 · 回放密文与安全边界说明冲突

模块：接口调试；处理类型：确认规则后处理；规则卡：D12。

**历史事实**：回放加密存储请求头、认证和正文，并向有写权限者解密返回；页面声称不保存。

**建议**：建议先确认保留加密回放能力还是只存摘要，再同步存储边界、权限和文案。

**验收关注**：保存字段与文案一致；未授权不能取得明文；密文、日志、留存和删除按批准规则验证。

**兼容及限制**：加密不等于不保存；不擅自删除现有草稿或改变已授权读写权限。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)

## P1 · OBS-07-S005 · PAT scope

模块：接口调试；处理类型：确认规则后处理；规则卡：D11。

**历史事实**：PAT读scope可读描述但不能读历史，写scope可执行及读历史但不能读描述。

**建议**：建议含解密草稿的历史继续要求写权限；只读历史若开放应另做脱敏返回。

**验收关注**：读scope不能取得解密草稿；写scope按项目隔离；描述符权限回归。

**兼容及限制**：不能仅因GET就放宽权限；只读脱敏能力需独立定义字段。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)

## P1 · OBS-08-E011 · 停用环境跨入口一致性

模块：接口自动化；处理类型：确认规则后处理；规则卡：D03。

**历史事实**：停用环境仍能通过普通、预览、数据驱动及定时入口执行。

**建议**：建议停用环境拒绝所有入口的新执行，只读预览可保留。

**验收关注**：手动、API、数据驱动、定时和Webhook均一致；不会创建被禁止的新任务；已有执行按批准规则处理。

**兼容及限制**：推荐不自动取消已接受的运行；启停与取消是两个动作，需明确边界。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-08-api-automation/regression-r1/execution-results.json)

## P1 · OBS-16-E003 · Redis故障时健康、readiness与业务一致性

模块：横向回归；处理类型：确认规则后处理；规则卡：D25。

**历史事实**：Redis故障时总health503、readiness200、auth/me500，恢复后可用。

**建议**：建议按部署模式把关键依赖纳入readiness，liveness不依赖外部服务。

**验收关注**：服务端Redis故障就绪失败且业务受控反馈；恢复后可用；桌面不检查未使用的Redis。

**兼容及限制**：本轮只确认历史health503/readiness200/me500，不改策略或500为通过。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-final.json)

## P2 · OBS-03B-E001 · 无定义环境配置的存储与可见性

模块：项目环境、参数与密钥；处理类型：确认规则后处理；规则卡：D01。

**历史事实**：无定义环境的参数和密钥仍可保存204，环境列表无对应定义。

**建议**：建议新写入必须引用已定义环境，存量孤立配置先只读盘点。

**验收关注**：合法环境正常保存；未定义环境受控拒绝且无行/审计新增；旧数据不被自动改写。

**兼容及限制**：将改变旧式自由环境代码写入，需兼容及存量处理方案。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03b-environments/execution-results.json)

## P2 · OBS-03B-E005 · 停用环境参数与密钥写入

模块：项目环境、参数与密钥；处理类型：确认规则后处理；规则卡：D03。

**历史事实**：停用环境仍允许参数和密钥API写入，页面表单禁选该环境。

**建议**：建议停用限制新执行，仍允许有权限者维护配置，并统一前端状态说明。

**验收关注**：停用环境配置维护与已选规则一致，不能由隐藏页面选项代替API校验。

**兼容及限制**：与接口自动化启停策略一并确认，避免跨入口含义不同。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03b-environments/execution-results.json)

## P2 · OBS-03C-E005 · 筛选后其他动作选项可达性

模块：文件管理与操作日志；处理类型：确认规则后处理；规则卡：D04。

**历史事实**：选择某审计动作后，其他动作选项不再提供，须先清空。

**建议**：建议动作下拉取权限范围内的可选动作集合，不随当前筛选收缩。

**验收关注**：选择A后可直接选B；超过最近100条之外的合法动作仍按所选规则可达；不泄露其他权限域动作。

**兼容及限制**：需要确定全量可见动作、固定字典或仅最近动作，不能仅缓存首屏冒充完整集合。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-03c-files-audit/execution-results.json)

## P2 · OBS-03C-E007 · 全局审计刷新失败后的旧数据提示

模块：文件管理与操作日志；处理类型：保守修复候选；规则卡：无需另选业务规则，按候选边界审核。

**历史事实**：全局审计刷新失败有错误提示，但仍显示旧记录。

**建议**：保留现有旧数据，明确显示上次成功时间和刷新失败标识，提供重试。

**验收关注**：失败时旧行有清晰旧数据标识；新成功清除错误；迟到请求不改新结果。

**兼容及限制**：不删除旧行、不改变保留策略，不依赖新增后端业务规则。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-03c-files-audit/execution-results.json)

## P2 · OBS-05-E003 · 历史执行用例标题是否跟随源资产

模块：测试计划与交付；处理类型：确认规则后处理；规则卡：D08。

**历史事实**：历史执行用例标题跟随源资产改名，步骤快照仍保留。

**建议**：建议新执行批次保存用例标题快照，旧记录缺快照时明确标识。

**验收关注**：源用例改名不改新批次历史标题；步骤快照保持；旧历史不伪造原始标题。

**兼容及限制**：新字段或兼容迁移待设计，不批量用当前名称回填成历史事实。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-05-plans-delivery/regression-r1/execution-results.json)

## P2 · OBS-06-E006 · 非200响应码导入导出保持范围

模块：接口设计与导入；处理类型：确认规则后处理；规则卡：D09。

**历史事实**：导入存储201响应码，但OpenAPI导出只产生200。

**建议**：建议按已保存的实际响应码导出，缺失时才默认200。

**验收关注**：201等状态导入、保存、导出后含义保持；200回归不变；不凭空扩展多响应模型。

**兼容及限制**：导出契约会变化，需确认当前单响应模型的保留范围。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-06-api-design/regression-r1/execution-results.json)

## P2 · OBS-06-E012 · 复制目录中途失败的保留与重试

模块：接口设计与导入；处理类型：确认规则后处理；规则卡：D10。

**历史事实**：目录复制中途失败保留部分资产，重试生成另一完整分支。

**建议**：建议一次目录复制原子提交，失败整批回滚；同一请求重试不重复新增。

**验收关注**：中途和审计故障均无新半成品；重试不新增重复分支；原资产及引用保持。

**兼容及限制**：与现有保留部分成功策略不同；已存在半成品仅盘点，不自动删除。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-06-api-design/regression-r1/execution-results.json)

## P2 · OBS-07-E012 · WS历史重发前旧会话处理

模块：接口调试；处理类型：确认规则后处理；规则卡：D14。

**历史事实**：载入WS历史后，旧服务端会话仍保留OPEN。

**建议**：建议替换本页连接时先关闭本页旧WS会话；只载入配置不自动新建连接。

**验收关注**：替换连接的旧会话到达终态；其他页面或账号会话不受影响；失败可恢复。

**兼容及限制**：保留多会话是另一可选产品策略，不能全局关闭所有会话。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)

## P2 · OBS-07-F012 · 正式接口高级调试交接

模块：接口调试；处理类型：确认规则后处理；规则卡：D15。

**历史事实**：高级调试交接保留method、URL、header、body等，但未传递独立Auth。

**建议**：建议同项目高级调试交接已建模且允许的Auth结构或密钥引用，未支持部分明确提示。

**验收关注**：支持的认证模式交接正确；不跨项目、不把敏感原文写URL/日志；未支持模式不伪装已传入。

**兼容及限制**：先核对正式接口Auth模型；不为补交接擅自新增认证存储模型。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)

## P2 · OBS-07-U012 · 脱敏旧历史禁止直接重发

模块：接口调试；处理类型：确认规则后处理；规则卡：D13。

**历史事实**：旧脱敏历史可载入且未自动发送；当前入口只有载入、没有独立重发按钮，专用警告路径不可达。

**建议**：建议在所有实际发送入口统一阻止未补齐的脱敏历史请求，允许只读载入。

**验收关注**：嵌入页、独立页、重发和手动发送均验证；未补齐时目标请求数0；补齐后可正常发送。

**兼容及限制**：当前独立页已有重发保护，应先复核嵌入及手动发送路径；不按任意星号误判用户数据。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)

## P2 · OBS-08-E010 · WAIT小数与非有限数值

模块：接口自动化；处理类型：确认规则后处理；规则卡：D16。

**历史事实**：WAIT小数配置可保存，执行失败。

**建议**：建议整数业务字段拒绝小数，保存前校验WAIT，非有限原始JSON受控拒绝。

**验收关注**：保存、预检、执行校验一致；整数边界可用；小数或非法JSON不落库。

**兼容及限制**：与其他模块数值规则统一；不能把JSON序列化为null的样例当成非有限数实测。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-08-api-automation/regression-r1/execution-results.json)

## P2 · OBS-09-E013 · 优先级与有效期小数输入

模块：Mock；处理类型：确认规则后处理；规则卡：D16。

**历史事实**：优先级或有效期小数被转为整数，条件数字转字符串。

**建议**：建议优先级和有效期整数拒绝小数，字符串条件遵循统一类型规则。

**验收关注**：整数字段不静默截断，条件数值转字符串是否允许按确认矩阵验证。

**兼容及限制**：涉及旧客户端类型兼容，不全局修改ObjectMapper来替代逐字段规则。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-09-mock-regression/execution-regression.json)

## P2 · OBS-10-E010 · 去空白query键冲突

模块：性能测试；处理类型：确认规则后处理；规则卡：D17。

**历史事实**：去首尾空白后的query键冲突由后值覆盖。

**建议**：建议trim后query键冲突在合并前拒绝，保留原配置。

**验收关注**：客户端和API不静默覆盖；拒绝时数据/审计无变化；合法不同键保持。

**兼容及限制**：不统一把所有query键转小写；精确重名与归一重名分别覆盖。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-10-performance-regression/execution-final.json)

## P2 · OBS-10-E011 · 小数到整数输入转换

模块：性能测试；处理类型：确认规则后处理；规则卡：D16。

**历史事实**：线程数小数被转换为整数，值数字转换为字符串。

**建议**：建议线程数只接受合法整数，值字段按确认的字符串规则校验。

**验收关注**：小数、整数边界、数值字符串、null/缺失分别验证，避免隐式取整。

**兼容及限制**：既有调用方可能依赖宽松转换，需兼容说明。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-10-performance-regression/execution-final.json)

## P2 · OBS-11-E020 · 变量键trim后重名策略

模块：Web UI自动化；处理类型：确认规则后处理；规则卡：D17。

**历史事实**：变量键trim后重名由后值覆盖。

**建议**：建议变量键trim后重名拒绝，不采用后值覆盖。

**验收关注**：原变量与执行清单不被拒绝请求改变；合法键和作用域解析保持。

**兼容及限制**：变量大小写含义不随本次归一修复擅自改变。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-11-web-ui-regression/execution-final.json)

## P2 · OBS-11-E021 · 数值到整数/字符串的转换策略

模块：Web UI自动化；处理类型：确认规则后处理；规则卡：D16。

**历史事实**：视口数值转换为整数，变量数字转换为字符串。

**建议**：建议视口等整数拒绝小数，变量字符串类型按统一矩阵处理。

**验收关注**：保存和入队使用相同校验；支持的数值边界保持；错误指向字段。

**兼容及限制**：不对所有字段施加一个全局强制转换开关。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-11-web-ui-regression/execution-final.json)

## P2 · OBS-11-E022 · 页面对象保存与执行冻结时点

模块：Web UI自动化；处理类型：确认规则后处理；规则卡：D18。

**历史事实**：页面声称保存时冻结页面对象，实际入队时重新解析。

**建议**：建议保留入队时解析并冻结定位器，统一与保存时冻结文案的冲突。

**验收关注**：对象在入队前改变按规则生效；入队后改变不改旧manifest；历史不重写。

**兼容及限制**：若选择保存时冻结，需要版本化引用或快照设计，不能只改一句文案。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-11-web-ui-regression/execution-final.json)

## P2 · OBS-12-E020 · JSON数字转整数/字符串策略

模块：移动端自动化；处理类型：确认规则后处理；规则卡：D16。

**历史事实**：超时数字被转为整数，变量数字转字符串。

**建议**：建议超时整数拒绝小数，变量值按统一字符串规则校验。

**验收关注**：管理API和Runner清单均校验，拒绝时不建立设备会话。

**兼容及限制**：协议验证与真机验证分开记录；不宣称本项能解除真机阻塞。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-12-mobile-regression/execution-final.json)

## P2 · OBS-12-E022 · 跨平台定位策略使用

模块：移动端自动化；处理类型：确认规则后处理；规则卡：D19。

**历史事实**：Android资产可保存iOS定位策略。

**建议**：建议保存时检查定位器与所属平台，执行前再次校验。

**验收关注**：Android不能静默接受仅iOS策略，反向同理；合法公共策略保持；拒绝不建会话。

**兼容及限制**：存量不自动转换；平台无关元素模型若要保留，需要另一明确规则。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-12-mobile-regression/execution-final.json)

## P2 · OBS-13-E014 · 任务行操作等待CSRF期间切项目

模块：质量报告与任务；处理类型：确认规则后处理；规则卡：D20。

**历史事实**：任务行操作等待CSRF时切换项目，仍写入原项目，新项目不受影响。

**建议**：建议CSRF等待期间视图失效且业务请求未发出时撤销行操作。

**验收关注**：A到B再到A仍不能复活旧操作；已发出的请求按原项目归属处理，不写B。

**兼容及限制**：不把客户端取消等同于服务端撤回已提交事务。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)

## P2 · OBS-13-E015 · 超过50可访问项目的默认页面

模块：质量报告与任务；处理类型：保守修复候选；规则卡：无需另选业务规则，按候选边界审核。

**历史事实**：超过50个可访问项目时默认全选，而接口单次最多接受50个。

**建议**：超过50个可访问项目时先进入明确待选择状态，阻止无效自动请求，提示最多50项。

**验收关注**：51项以上初载不发超限或隐式全量请求；选择1至50项可查，选择第51项给提示。

**兼容及限制**：不能把空数组当待选择后仍发送，因为当前空筛选可代表全部；不自动截取前50项。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)

## P2 · OBS-13-E017 · 旧保存未完成时新草稿保存能力

模块：质量报告与任务；处理类型：保守修复候选；规则卡：无需另选业务规则，按候选边界审核。

**历史事实**：旧保存未完成时，新草稿保存按钮仍受旧loading影响，但草稿未被覆盖。

**建议**：按表单会话隔离保存loading，使旧保存不占用新草稿按钮状态。

**验收关注**：旧操作结束不解锁/关闭新保存；新草稿独立；拒绝重复点击与切项目保护保持。

**兼容及限制**：不改变已经提交的旧请求业务结果，不新增自动保存。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)

## P2 · OBS-13-E018 · 进度与周期JSON小数转换

模块：质量报告与任务；处理类型：确认规则后处理；规则卡：D16。

**历史事实**：任务进度、报告周期的小数被转换为整数。

**建议**：建议进度和报告周期整数拒绝小数，不静默取整。

**验收关注**：边界值和类型错误分别验证；拒绝无进度/计划或审计改写。

**兼容及限制**：保留任务状态机和周期上下限，不借此扩大范围。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)

## P2 · OBS-13-E020 · 无版本计划并发编辑策略

模块：质量报告与任务；处理类型：确认规则后处理；规则卡：D22。

**历史事实**：计划没有版本校验，后一次编辑覆盖前一次值。

**建议**：建议计划增加兼容的版本校验，过期编辑409且不覆盖。

**验收关注**：两个相同版本编辑仅一方成功，另一方刷新后可重试；历史报告不变。

**兼容及限制**：需明确无version旧客户端过渡方案，不直接全局拒绝旧请求。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)

## P2 · OBS-14-E022 · 通知行操作切项目撤销策略

模块：通知、集成、插件与运维；处理类型：确认规则后处理；规则卡：D20。

**历史事实**：通知行操作切项目后仍写入原项目。

**建议**：建议通知行操作在CSRF等待中切项目时撤销尚未发出的请求。

**验收关注**：旧项目和新项目均不被作废请求新增写入；已发出的结果仍归原记录。

**兼容及限制**：对已投递通知不承诺撤回，避免重复发送。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)

## P2 · OBS-14-E023 · 全局插件行操作离页撤销策略

模块：通知、集成、插件与运维；处理类型：确认规则后处理；规则卡：D20。

**历史事实**：全局插件行操作离页后仍执行已触发写入。

**建议**：建议全局插件行操作在视图失效后取消尚未发出的业务请求。

**验收关注**：离页后待CSRF操作不继续写；已发操作有原记录标识，新页面不被更新。

**兼容及限制**：不能把全局操作错误绑定后来选中的项目。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)

## P2 · OBS-14-E025 · 配置键空白归一冲突

模块：通知、集成、插件与运维；处理类型：确认规则后处理；规则卡：D17。

**历史事实**：配置键去空白归一后发生冲突仍被接受。

**建议**：建议配置键去空白后冲突在落库前拒绝。

**验收关注**：原值、密钥标记和权限保持；并发归一重名无静默覆盖。

**兼容及限制**：不自动清理存量重名；保留不同配置域各自大小写规则。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)

## P2 · OBS-15-E001 · 原生同步预览在离页后的旧响应

模块：桌面工作区与跨端；处理类型：确认规则后处理；规则卡：D24。

**历史事实**：原生同步预览为只读，但持有工作区锁，预览返回前不能关闭窗口。

**建议**：建议提供协作式取消预览，确认读取终止和锁释放后再允许关闭。

**验收关注**：取消有真实确认，窗口可关闭且不再回调；预览保持只读；写操作锁不被错误解除。

**兼容及限制**：不能用强杀或只隐藏窗口冒充取消；与升级关闭超时风险分开。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-15-desktop-workspace-cross-end/execution-final.json)

## P2 · OBS-16-E001 · 进程RSS/CPU/句柄持续观察

模块：横向回归；处理类型：先补验收规格；规则卡：D26。

**历史事实**：真实30分钟进程RSS、CPU和句柄采样已保存，未提供泄漏或资源阈值。

**建议**：先明确负载、资源阈值、持续时长和采样口径，再补长时对照验证。

**验收关注**：采样定义可比较，区分区间CPU与进程均值；无阈值不下泄漏或达标结论。

**兼容及限制**：现有30分钟记录保留，不外推多日稳定性。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-final.json)

## P2 · OBS-16-E002 · 业务与健康延迟持续观察

模块：横向回归；处理类型：先补验收规格；规则卡：D26。

**历史事实**：31轮业务和健康延迟已采样，缺少业务SLA；窗口与其他独立验收有重叠。

**建议**：先明确业务时延/错误率SLA、目标负载和运行窗口，再执行分级验证。

**验收关注**：有可复核负载与P95/P99/错误分布；基线与合格判定分开。

**兼容及限制**：不直接拿健康探针或与其他验收重叠的样本当业务容量基线。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-final.json)

## P3 · OBS-03D-E007 · 搜索SQL通配符语义

模块：账号管理与系统参数；处理类型：确认规则后处理；规则卡：D06。

**历史事实**：搜索百分号和下划线按SQL通配符匹配。

**建议**：建议普通搜索按字面子串处理百分号、下划线和反斜杠。

**验收关注**：三种字符按选定语义匹配，分页和总数一致，仍使用参数化查询。

**兼容及限制**：通配符语义不等同SQL注入；改变搜索语义前确认使用习惯。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-03d-accounts-config/regression-r1/execution-results.json)

## P3 · OBS-05-E002 · 计划PUT变更编号的响应语义

模块：测试计划与交付；处理类型：确认规则后处理；规则卡：D07。

**历史事实**：PUT中发送的新计划编号未应用，仍返回旧编号。

**建议**：建议计划编号创建后不可修改，发送不同编号时400明确提示，页面只读。

**验收关注**：同编号更新正常；不同编号受控拒绝且原值不变；省略字段按原约束处理。

**兼容及限制**：将改变静默忽略不同编号的API反馈，需告知已有调用方。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-05-plans-delivery/regression-r1/execution-results.json)

## P3 · OBS-07-E006 · 旧HTTP响应跨项目归属

模块：接口调试；处理类型：保守修复候选；规则卡：无需另选业务规则，按候选边界审核。

**历史事实**：跨项目的旧HTTP响应被丢弃，但仍出现旧完成全局通知。

**建议**：保留现有完成通知，但增加原项目和执行标识，避免误认当前项目结果。

**验收关注**：A切B后旧通知明确标A，B数据和加载状态不变；不额外触发执行。

**兼容及限制**：只澄清归属，不改变是否取消已提交请求或是否通知的现有行为。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)

## P3 · OBS-08-E012 · 运行面板通知选项实际语义

模块：接口自动化；处理类型：保守修复候选；规则卡：无需另选业务规则，按候选边界审核。

**历史事实**：运行面板的本页提醒仅为本地提交提示，没有向他人发送消息。

**建议**：把提醒文案明确为本页提交提示，不声称保存该开关或发送外部通知。

**验收关注**：勾选只影响已有本页提示；刷新后的行为说明准确；无通知或订阅写入。

**兼容及限制**：保留当前功能范围，不顺带新增持久化、跨页提醒或通知投递。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-08-api-automation/regression-r1/execution-results.json)

## P3 · OBS-09-E012 · 公开分享页剪贴板拒绝

模块：Mock；处理类型：保守修复候选；规则卡：无需另选业务规则，按候选边界审核。

**历史事实**：公开分享页复制被浏览器拒绝时，存在错误但反馈契约不明确。

**建议**：处理剪贴板拒绝和不可用，显示失败反馈并保留可手动复制的地址。

**验收关注**：拒绝时无未处理异常和假成功提示；成功/拒绝/不支持三种分区均可恢复。

**兼容及限制**：测试使用隔离页面替身，不写用户系统剪贴板。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-09-mock-regression/execution-regression.json)

## P3 · OBS-13-E009 · task保存操作归属/reload_reopen

模块：质量报告与任务；处理类型：保守修复候选；规则卡：无需另选业务规则，按候选边界审核。

**历史事实**：任务保存后的刷新期间，新草稿暂不可用。

**建议**：在保留请求归属保护的前提下，解耦列表刷新与打开新草稿。

**验收关注**：刷新中可开新草稿；迟到列表和旧保存不改新表单；切项目保护保持。

**兼容及限制**：只解耦UI状态，不改变任务记录生命周期或权限。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)

## P3 · OBS-13-E019 · 报告项目参数末尾逗号

模块：质量报告与任务；处理类型：确认规则后处理；规则卡：D21。

**历史事实**：报告项目参数末尾逗号返回400，空筛选按默认项目集合处理。

**建议**：建议省略筛选保留当前默认语义，显式列表中的空段和尾逗号400并补齐文档。

**验收关注**：省略、空值、空段、重复ID和超限分别验证，返回范围与权限一致。

**兼容及限制**：与超过50项目的前端选择修复区分，不默默把空段解释成全部项目。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)

## P3 · OBS-14-E004 · notification保存归属/reload_reopen

模块：通知、集成、插件与运维；处理类型：保守修复候选；规则卡：无需另选业务规则，按候选边界审核。

**历史事实**：通知模板保存刷新期间不能重新打开新建表单。

**建议**：解耦通知列表刷新和新建表单，保留表单会话归属保护。

**验收关注**：刷新中可开新表单；旧响应不改草稿；失败和重试不导致跨项目写入。

**兼容及限制**：不改变通知渠道、投递或重试语义。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)

## P3 · OBS-14-E014 · package保存归属/reload_reopen

模块：通知、集成、插件与运维；处理类型：保守修复候选；规则卡：无需另选业务规则，按候选边界审核。

**历史事实**：插件包保存刷新期间不能重新打开新建表单。

**建议**：解耦插件包列表刷新和新建表单状态。

**验收关注**：迟到刷新不关闭或污染新草稿；原权限和字段校验保持。

**兼容及限制**：不顺带安装或启用插件包。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)

## P3 · OBS-14-E019 · adapter保存归属/reload_reopen

模块：通知、集成、插件与运维；处理类型：保守修复候选；规则卡：无需另选业务规则，按候选边界审核。

**历史事实**：适配器保存刷新期间不能重新打开新建表单。

**建议**：解耦适配器列表刷新和新建表单状态。

**验收关注**：新草稿不被旧回调重置；页面离开和失败恢复保持。

**兼容及限制**：不改变适配器可信来源及执行权限。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)

## P3 · OBS-14-E024 · 普通JSON额外字段策略

模块：通知、集成、插件与运维；处理类型：确认规则后处理；规则卡：D23。

**历史事实**：普通JSON请求接受额外未知字段。

**建议**：建议普通CRUD维持兼容忽略未知字段，迁移/签名等严格入口继续拒绝。

**验收关注**：未知字段不入库、不提升权限；严格入口不放宽；文档与行为一致。

**兼容及限制**：如改为全量严格400，需逐接口兼容评估，不能一键全局切换。

[原始观察证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)
