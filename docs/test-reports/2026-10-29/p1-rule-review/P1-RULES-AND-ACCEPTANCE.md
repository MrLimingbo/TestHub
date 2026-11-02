# 6项P1观察：规则选择与验收标准

日期：2026-10-29。状态：**待用户审核，本轮只读核对与编写规格，未改代码、未执行测试或准备环境。**

上一批10项修复与回归已由用户审核通过。剩余37项中，本次只提交6项P1的规则；另31项（29项规则、2项规格）及12项环境阻塞继续暂缓。升级关闭超时仍为独立未确认风险，不并入本批。

推荐选项均未自动选中。下列HTTP状态、留存期限、清理周期和探针截止中标为建议的内容，是待确认契约，不是已实现或已验证的结论。

## 6项审阅总表

|规则卡|P1观察项|推荐组合|主要待确认|
|---|---|---|---|
|D02|OBS-03B-E002 · 普通参数与密钥跨类型同名|A|禁止隐式转换，跨类型409；是否另做显式转换|
|D05|OBS-03D-E006 · 停用、删除、改密后的登录会话|A+P0|全部旧Session失效；改密是否同时吊销PAT|
|D11|OBS-07-S005 · PAT读取调试历史的权限边界|A|历史需project:write；是否另加只读摘要API|
|D12|OBS-07-E013 · 加密回放保存、留存与告知|A+30d+L0+G0|保存字段/写者读取、30天期限、新密文清理及旧密文保留边界|
|D03|OBS-08-E011 · 接口自动化停用环境的执行边界|A|拒绝接口自动化新执行；已接受任务继续；共享P2仍暂缓|
|D25|OBS-16-E003 · 依赖故障、就绪探针及业务错误一致性|A+5s|部署模式关键依赖；受控503；探针截止|

建议审核顺序：D02 → D05 → D11/D12一起确认 → D03 → D25。它仅是后续建议顺序，当前不执行。选择B或自定义时，以该分支再补实现清单，不把A分支验收套到不同规则上。

以下验收检查组不是已执行用例；选择后再拆成正向、反向、边界及竞态用例，先建立修复前证据，再修复、后台回归、提交审核。

## D02 · 普通参数与密钥跨类型同名

主观察项：**OBS-03B-E002**。

**既有事实**：普通参数同名保存会把原密钥行转为非密钥，密钥列表不再列出。

**推荐规则**：A：禁止隐式类型转换；跨类型同名返回409，同类型合法更新保留。

**具体选项**

- **A（推荐）**：同一项目、环境及现有唯一键范围内，普通参数与密钥不能互相覆盖。HTTP409使用共享错误封装，原记录类型和值保持。
- **B（备选）**：需要显式转换能力。必须先补转换入口、独立权限、确认及审计设计；本次不把普通保存按钮改成静默转换。

**需要你确认**

- 选择A或B；推荐A，本期不新增显式转换。
- 接受按现有唯一键及规范化规则判定同名，不新增大小写转换；不自动恢复已被覆盖的历史值。

**范围与兼容边界**

- 环境代码沿用trim及小写规范化，Key沿用现有trim/合法字符和数据库唯一键契约。
- BASE_URL保留环境管理专属维护及现有400保护。
- 旧密钥类型被覆盖不证明旧明文被解密泄露；存量盘点或恢复须另列清单。

**验收标准（尚未执行）**

|编号|场景|通过标准|
|---|---|---|
|P1-D02-01|密钥K已存在，再用普通参数保存同名K|409；旧ID、类型、密文和值不变；普通列表不出现该密钥明文；无成功写入审计。|
|P1-D02-02|普通参数K已存在，再用密钥保存同名K|409；原普通参数不被加密或改为密钥。|
|P1-D02-03|同类型新增/更新和不同项目、不同环境|合法操作维持现有成功状态码；隔离域内允许独立同名；原权限、格式、长度校验保持。|
|P1-D02-04|同项目环境的并发普通/密钥同名创建|只提交一种类型，另一请求409；无500、混合状态或丢失更新；查重条件与唯一约束一致。|
|P1-D02-05|无权限、非法Key、BASE_URL及大小写/空白边界|鉴权先于敏感内容处理；401/403、现有400规则保持；不能利用规范化差异绕过已被唯一键认定的同名冲突。|
|P1-D02-06|失败响应、审计与日志|使用共享错误封装；不包含参数值、密钥值或密文；不删除或猜测恢复存量。|

[历史验收证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03b-environments/execution-results.json) · [ProjectParameterService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/parameter/ProjectParameterService.java) · [EnvironmentSecretService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/parameter/EnvironmentSecretService.java) · [ProjectParameterRepository.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/parameter/ProjectParameterRepository.java)

## D05 · 停用、删除、改密后的登录会话

主观察项：**OBS-03D-E006**。

**既有事实**：停用、删除或改密后旧会话仍可访问me；旧密码新登录被拒。

**推荐规则**：A：安全事件成功提交后，目标账号全部既有Session失效；管理员给自己改密也重新登录。PAT联动单列确认。

**具体选项**

- **A（推荐）**：停用、软删除、管理员重置密码后，全部目标账号既有Session在下一次请求被拒绝；包括管理员重置自己密码。无新增本人改密入口。
- **B（备选）**：保留会话到原过期时间。须明确接受风险和敏感操作复核规则，不将其描述为立即注销；建议不选。

**D05-PAT**（推荐P0，当前未选）：

- **P0**：保持现有PAT规则：停用/删除不可用，改密不额外联动吊销；已存在的手工撤销、过期及项目/scope校验保持。
- **P1**：重置密码时连同目标账号的PAT全部吊销；会中断相关CI，须明确纳入本批。

**需要你确认**

- 选择A或B，以及D05-PAT的P0/P1。推荐A+P0。
- 本期仅使用现有管理员账号管理和PUT /api/v1/accounts/{id}/password，不新增个人改密或凭据管理功能。

**范围与兼容边界**

- 目前静态代码确认的是管理员重置密码入口；此前“若已有本人改密则轮换当前会话”不当成本期必做新功能。
- 已开始的业务请求/Runner任务不被假称为已撤销；账号安全事件的后续请求边界单独验证。
- 用户不可停用/删除当前登录账号的409保护保持；新密码不进入会话、审计或日志。

**验收标准（尚未执行）**

|编号|场景|通过标准|
|---|---|---|
|P1-D05-01|目标账号在两浏览器登录后被停用/删除|安全事件提交成功后，两个旧Cookie访问me及代表性受保护读取均401；写入用新鲜CSRF证明仍不能执行。CSRF自身不合法时可先返回现有403，不能造成写入。|
|P1-D05-02|管理员重置另一账号密码|目标的全部旧Session失效；旧密码新登录失败，新密码可登录；执行重置的其他管理员会话保持。|
|P1-D05-03|管理员通过现有入口重置自己密码|成功响应后当前及其他旧Session不能继续认证，提示重新登录；不绕开当前账号不可停用/删除保护。|
|P1-D05-04|重新启用账号或应用重启/多实例|旧Cookie不能复活；新登录正常。服务端共享会话与桌面持久会话分别覆盖，不能只清一个浏览器Cookie。|
|P1-D05-05|安全事件提交与并发请求的边界|提交之后发起的新请求不得凭旧身份通过；提交之前已进入执行的操作单独记录，不承诺服务端回滚。|
|P1-D05-06|普通资料更新和其他用户|仅改姓名/邮箱等非安全资料不导致无关注销；其他账号与项目身份不受影响。|
|P1-D05-07|PAT选择与故障分支|P0维持现有改密后的PAT行为；P1则实际吊销并验证CI失败为预期。停用/删除、手工撤销、过期均不得通过PAT认证。无法完成安全判定不能假报成功或放行。|

[历史验收证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-03d-accounts-config/regression-r1/execution-results.json) · [AccountManagementService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/account/AccountManagementService.java) · [AccountManagementController.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/account/AccountManagementController.java) · [AuthController.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/account/AuthController.java) · [RedisSessionConfiguration.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/security/RedisSessionConfiguration.java) · [TokenRepository.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/access/TokenRepository.java)

## D11 · PAT读取调试历史的权限边界

主观察项：**OBS-07-S005**。

**既有事实**：PAT读scope可读描述但不能读历史，写scope可执行及读历史但不能读描述。

**推荐规则**：A：保留含解密回放草稿的历史需project:write；project:read不自动取得解密能力。

**具体选项**

- **A（推荐）**：保持现有GET /api/v1/projects/{projectId}/api-debug/history要求project:write及允许项目角色，补齐页面、文档和权限矩阵；不新增API。
- **B（备选）**：在A基础上新增GET /api/v1/projects/{projectId}/api-debug/history/summaries，只接受project:read及项目读取角色，返回白名单摘要；原history仍需write。

**需要你确认**

- 选择A或B。推荐A；HTTP GET本身不构成放宽权限的理由。
- 写scope不隐含读scope：若同一令牌既要读描述符又要取完整历史，应显式带project:read和project:write；run:execute不等同HTTP调试权限。

**范围与兼容边界**

- scope、令牌绑定项目及当前账号角色同时满足；无权限不得先读取/解密再过滤响应。
- B若选中，摘要字段限定id、method、protocol、去除userinfo/query/fragment的URL、响应状态、耗时、大小、结果及时间；不含replayDraft、请求头、Auth、正文、环境值、流消息或响应原文。
- D12决定有权写者能回放哪些字段及期限；两项应先定规则再一起做权限/留存交叉回归。

**验收标准（尚未执行）**

|编号|场景|通过标准|
|---|---|---|
|P1-D11-01|project:read、project:write、read+write、run:execute矩阵|同一合法项目角色下，read只读描述符但原history403；write可读原history但描述符403；read+write两者可读；run:execute单独访问两者403。|
|P1-D11-02|历史有/无草稿、过期草稿与导入脱敏历史|权限不能随当前行是否含草稿而放宽；D12到期后可返回摘要及空草稿，但原history入口仍按同一scope契约。|
|P1-D11-03|PAT绑定项目与项目成员权限变化|存在的其他项目403；不存在项目沿用404；即使scope够，账号无相应允许角色也不能获取明文。|
|P1-D11-04|失效Bearer和有效浏览器Cookie同时存在|无效、过期、撤销的Bearer仍401，不回退到Cookie冒充成功；令牌不得写进浏览器Session。|
|P1-D11-05|B新增只读摘要入口（仅选B时）|read和read+write返回200白名单；缺read返回403；无解密动作或额外网络执行；任何请求/响应原文均不在摘要。|
|P1-D11-06|浏览器角色与协议能力回归|SYSTEM_ADMIN、ORG_ADMIN、PROJECT_ADMIN、TEST_LEAD、TESTER按现有写角色规则处理；只读角色无完整回放。HTTP/gRPC描述符原能力不倒置。|

[历史验收证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json) · [ApiDebugService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/apidebug/ApiDebugService.java) · [ApiDebugController.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/apidebug/ApiDebugController.java) · [AccessService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/access/AccessService.java) · [PersonalAccessTokenAuthenticationFilter.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/security/PersonalAccessTokenAuthenticationFilter.java)

## D12 · 加密回放保存、留存与告知

主观察项：**OBS-07-E013**。

**既有事实**：回放加密存储请求头、认证和正文，并向有写权限者解密返回；页面声称不保存。

**推荐规则**：A：保留加密回放并如实告知；建议回放期限30天，新策略记录的密文到期后按24小时清理周期回收，存量只限制回放、不自动物理删除。

**具体选项**

- **A（推荐）**：保存受控的加密请求配置，供满足D11的同项目写者手动回放。明确字段、期限、清理范围和存量例外，不再声称“只保存摘要/不保存请求”。
- **B（备选）**：新历史只存脱敏摘要，不保存可回放请求配置；相关手动保存草稿能力需明确停用/提示。旧密文先保留但不返回明文，不自动批量删除。

**D12-TTL**（推荐30d，当前未选）：

- **30d**：30×24小时；命名DRAFT与执行历史一致，从原记录createdAt计算。
- **7d**：7×24小时，同一口径。
- **90d**：90×24小时，同一口径。
- **CUSTOM**：用户明确正整数天数；不接受隐含永久保留。

**D12-OLD**（推荐L0，当前未选）：

- **L0**：存量按同一原创建时间计算回放可用期；到期不返回草稿明文，保留旧密文并另提交只读盘点；不批量删除/改写旧行。
- **L1**：存量暂维持原回放和保留方式，界面标明历史例外及未到期化风险；后续另审迁移/清理。

**D12-GC**（推荐G0，当前未选）：

- **G0**：只清理明确纳入新策略的到期replay密文，保留摘要；到期读取立即禁止回放，启动后及服务正常运行期间最多每24小时一次回收。停机期间不承诺物理删除。
- **G1**：本期只作到期读取限制，暂不清密文；物理留存没有30天保证，须明确接受并另审清理。

**A方案字段白名单与读取边界**

- HTTP/GraphQL/SOAP：name、method、原始url、启用的headers（包括Cookie）、body、timeoutMs、environmentCode及已支持协议配置。
- Auth只保留当前启用模式需要的字段；不保存未启用模式的隐藏旧值。
- gRPC Unary：现有endpoint/tls/service/method/requestJson/timeoutMs/environmentCode/metadata/启用Auth/descriptorId。
- 用户直接填写的认证和正文可包含敏感内容，应加密并告知；环境密钥引用保留引用，不另保存解析出的环境密钥值。
- 不新增原始响应正文、原始protobuf描述符、WebSocket/流消息的历史持久化。
- 不额外读取本地文件、文件路径或上传文件原件。既有body中用户内嵌的内容属于正文，不宣称可以自动识别全部秘密。
- 继续仅存既有脱敏URL/执行摘要；不把加密草稿、请求头、认证或正文写入普通摘要、审计、错误详情或日志。
- 满足D11的同项目写角色可读取未到期草稿，并非仅创建人；不新增跨项目或普通只读用户的解密权限。

**需要你确认**

- 选择A或B。A推荐组合为A+30d+L0+G0；4个选项均需确认，推荐不自动选中。
- 仅新策略标记的记录纳入G0；任何旧密文、摘要、备份或导出文件的批量清理都不包含在该组合中。
- 允许保存的字段与角色见下方白名单。若选B，TTL/GC对新回放不适用，存量只保留密文且禁止明文回放，清理仍另审。

**范围与兼容边界**

- 本次只提出具体建议，未新增TTL、清理任务、schema或删除入口；30天和24小时是待选产品规则，不是当前行为或法规要求。
- 建议采用UTC Instant计算，now>=createdAt+TTL立即不可回放；缺失/不可信创建时间不猜测补写，进入待盘点且不回放。读取/导入/重启不延长原期限。
- 到期禁止回放与物理删除是两个状态；离线备份、导出及已经交付客户端的副本不会被服务端清理任务追溯擦除。
- 不把只读摘要功能B（D11）隐含纳入本项，不改暂停中的脱敏重发P2规则。

**验收标准（尚未执行）**

|编号|场景|通过标准|
|---|---|---|
|P1-D12-01|A字段保存白名单和B摘要模式|A仅按白名单保存加密配置；禁用Auth隐藏值不入草稿；B新记录replay字段为空且界面不承诺保存草稿成功。HTTP与gRPC Unary分别验证。|
|P1-D12-02|可识别测试敏感标记|原始数据库草稿列中无明文字面标记；未授权响应、摘要、审计和日志不含该标记。授权回放仅返回策略允许字段。|
|P1-D12-03|权限与告知|页面、帮助和相关代码注释明确加密保存、项目写者可解密、有效期及旧数据例外；D11缺scope/跨项目不能取明文。|
|P1-D12-04|TTL前后及边界时刻|使用可控时钟验证到期前可回放，恰好到期及之后立即不可回放；摘要仍可见并明确到期；重启、读取及导入不续期。|
|P1-D12-05|新策略密文回收G0|仅清新策略到期草稿密文；合法未到期记录不受影响；正常运行最长24小时清理周期及启动补偿可验证；清理失败可见、重试不删错行。|
|P1-D12-06|G1物理保留例外|若选G1，过期也禁止明文回放但密文继续保留；文案不得声称已删除或保证物理30天保留。|
|P1-D12-07|存量L0/L1|L0按原时间限制回放且不改写/删除旧密文；L1明确保留例外。未知时间不伪造；原摘要、记录数及旧备份不因本项批量清理。|
|P1-D12-08|失败与兼容|加密失败不得落明文或假报草稿保存成功；解密失败只给脱敏错误，不能返回密文、栈或越权回退；旧客户端缺新增字段时保持可解析。|

[历史验收证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json) · [ApiDebugService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/apidebug/ApiDebugService.java) · [GrpcDebugService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/apidebug/GrpcDebugService.java) · [ApiDebugHistoryRepository.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/apidebug/ApiDebugHistoryRepository.java) · [V58__api_debug_history_replay_drafts.sql](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/resources/db/migration/V58__api_debug_history_replay_drafts.sql) · [ApiDebugPage.vue](/Users/limingbo/Documents/codeXProject/testHub/src/pages/ApiDebugPage.vue)

## D03 · 接口自动化停用环境的执行边界

主观察项：**OBS-08-E011**。

**既有事实**：停用环境仍能通过普通、预览、数据驱动及定时入口执行。

**推荐规则**：A：停用环境拒绝接口自动化所有新执行；已被成功接受的排队/运行任务继续按快照执行，取消另行操作。

**具体选项**

- **A（推荐）**：按运行配置覆盖后的有效环境判断；普通、API/CLI、数据驱动、定时、Webhook及实际执行预览的新提交均拒绝。已提交成功的队列/运行任务继续，纯配置预览可读。
- **B（备选）**：停用还要撤销已排队任务，运行中任务另定义停止策略。需要先确认取消、状态和补偿设计，不用“停用”冒充执行已停止。

**需要你确认**

- 选择A或B。推荐A，已接受任务以完整任务/manifest/outbox成功提交为边界。
- 本批只对该P1的接口自动化执行入口落地；共享D03卡关联的P2 OBS-03B-E005（配置维护界面/API一致性）仍暂缓，不因批准本卡自动解锁。

**范围与兼容边界**

- 环境配置维护保持现行权限/写入行为，不顺带冻结参数和密钥，也不修改P2维护表单。
- 其他测试模块若需要新增启停行为，先提交影响清单，不扩大本批范围。
- 纯配置预览不能产生网络请求、任务或出站消息；真正发请求的“预览”归执行入口。
- 关联但仍暂缓：OBS-03B-E005；不计入本批6项，也不自动关闭。

**验收标准（尚未执行）**

|编号|场景|通过标准|
|---|---|---|
|P1-D03-01|普通提交、API/CLI及入站Webhook|权限/签名合法但有效环境停用时409，共享错误封装且明确环境停用；不产生新任务、manifest、outbox或Runner目标访问。|
|P1-D03-02|数据驱动执行|同一停用环境的整批新执行受控拒绝，不留下部分已入队行；不把被拒绝记录当成功执行。|
|P1-D03-03|定时触发|明确记录本次因环境停用跳过；不生成Runner执行，不计Runner连续执行失败，不自动停用计划；恢复后从后续合法触发继续，不默认补跑跳过批次。|
|P1-D03-04|运行配置覆盖与并发停用|以覆盖后有效环境而非场景默认值判断；提交与停用串行化边界明确，提交成功的有完整快照，拒绝的无半成品。|
|P1-D03-05|已接受队列/运行任务（A）|停用后仍按原manifest快照继续，既有手动取消能力保持；不重新解析当前参数或偷偷取消。|
|P1-D03-06|配置预览与执行预览|纯只读配置预览维持授权访问且无任务/网络副作用；实际执行预览受同一停用校验，不可绕过。|
|P1-D03-07|重新启用与回归|重新启用后合法新提交可正常接受；其他项目/环境和原PAT run:execute、Webhook验签、幂等及超时保护保持。|

[历史验收证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-08-api-automation/regression-r1/execution-results.json) · [ApiAutomationExecutionService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/apiautomation/ApiAutomationExecutionService.java) · [AccessService.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/access/AccessService.java)

## D25 · 依赖故障、就绪探针及业务错误一致性

主观察项：**OBS-16-E003**。

**既有事实**：Redis故障时总health503、readiness200、auth/me500，恢复后可用。

**推荐规则**：A：按部署模式纳入关键依赖；服务端Redis故障使readiness失败，受影响请求返回受控503；liveness仅反映进程。

**具体选项**

- **A（推荐）**：服务端readiness纳入readinessState、数据库、Redis，并保留既有minio存储检查；桌面纳入readinessState、本地数据库和现有文件存储检查，不添加未使用Redis。
- **B（备选）**：维持较窄readiness，但必须另定义依赖降级告警和流量接入规则；不能据此把历史readiness200/me500记为已修复。

**D25-TIMEOUT**（推荐5s，当前未选）：

- **5s**：建议健康探针在隔离故障验证中最多5秒返回200/503，外部依赖有界超时，不能无限挂起。
- **10s**：选择10秒同一口径。
- **CUSTOM**：用户指定正数秒数及部署探针预算，实施时核对原生客户端可实现性。

**需要你确认**

- 选择A或B，以及探针故障响应上限。推荐A+5s；该值是待确认的健康探针截止，不是业务P95/P99或容量SLA。
- 桌面desktopBroker现有独立健康检查保持；Runner、插件、邮件等未配置/可选组件不新增为控制面就绪的强制条件。

**范围与兼容边界**

- 当前配置已把minio加入readiness；本项不能为修Redis反而移除现有存储检查。minio组件在桌面实际调用文件存储，不代表本轮补齐外部S3环境。
- 只把明确的依赖不可用映射503，不全局把产品异常500改成503。鉴权可判定时保留401/403；依赖故障导致无法完成会话鉴权时503且不放行。
- 本轮只列规则；后续只能使用已具备的隔离故障环境，缺条件则报阻塞，不启动12项环境补齐。D26资源/业务负载规格继续暂缓。

**验收标准（尚未执行）**

|编号|场景|通过标准|
|---|---|---|
|P1-D25-01|服务端Redis断开/拒绝连接/超时|readiness503，总health按关键依赖聚合503；liveness在进程正常时200；依赖Redis会话的me返回脱敏503，不出现未处理500或身份绕过。|
|P1-D25-02|数据库或现有存储检查失败|按关键依赖readiness503并给出不含凭据/端点详情的反馈；保留现有空存储桶懒创建的正常就绪语义。|
|P1-D25-03|桌面无Redis正常启动|只检查实际使用的本地数据库/文件存储，Redis未配置不使其失败；不检查或探测用户未启用的外部服务。|
|P1-D25-04|鉴权与错误分类|能判定的无认证401和无权限403保持；不能判断身份时受控503，不借机开放接口；普通程序缺陷仍按既有500处理。|
|P1-D25-05|探针超时与资源释放|按选定5s/10s/自定义截止返回，不无限等待；探测为只读，有界连接/线程，失败不造成重启风暴或泄露连接。|
|P1-D25-06|依赖恢复与会话状态|恢复后就绪和合法业务恢复；若隔离Redis重建丢失了会话，旧会话应401并允许重新登录，不冒称旧Cookie自动恢复。|
|P1-D25-07|公开探针、可选Runner与现有契约|公开探针仍隐藏详情；可选Runner离线不单独拖垮控制面readiness；不放宽网络名单、权限或发行distributionReady门槛。|

[历史验收证据](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-final.json) · [application.yml](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/resources/application.yml) · [application-desktop.yml](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/resources/application-desktop.yml) · [MinioHealthIndicator.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/operations/MinioHealthIndicator.java) · [DesktopBrokerHealthIndicator.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/desktop/DesktopBrokerHealthIndicator.java) · [SecurityConfiguration.java](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/security/SecurityConfiguration.java)

## 审核后才可执行的共同验收门槛

- 逐项规则与相应子选项明确后，提交可追溯的用例及原场景复现；选A不意味着必须修改已符合规则的接口。
- 使用自有隔离数据；浏览器/桌面验收保持后台；验收包含跨项目、角色、Bearer/Cookie、迟到响应、失败恢复和原10项修复的相关保护。
- 后端检查只走仓库隔离验收脚本，不使用默认数据库；未具备的真实依赖验证保留阻塞，不补齐12项历史阻塞，也不把替身当真实通过。
- 需要持久化字段时走现有H2/MySQL迁移并分别验证；不手改生产数据。D12的G0只涉及明确纳入新策略的到期密文，旧行/摘要/备份清理另审。
- 不能通过改错误码、改提示、延长超时或删除有效旧测试掩盖问题；回归报告单列正向/反向数量、通过/失败/阻塞及复现。

## 暂缓与独立风险

31项其他观察与12项环境阻塞保持暂缓；D03关联P2仍在31项内。当前6张卡及所有子选项都是未选，原26张决策卡也未被自动批准。

**OBS-XREG-SHUTDOWN / UNCONFIRMED_RETAINED**：原一次离屏关闭超过30秒保留，根因未确认；历史两次候选复验0.769秒、0.492秒不构成风险关闭。本轮不复现、不加退出日志、不改阈值。

[独立风险记录](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-29/UNCONFIRMED-RISK.md) · [继续暂缓的12项环境清单](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-29/ENVIRONMENT-PREPARATION.md) · [本批审核填写单](/Users/limingbo/Documents/codeXProject/testHub/docs/test-reports/2026-10-29/p1-rule-review/USER-REVIEW.md)
