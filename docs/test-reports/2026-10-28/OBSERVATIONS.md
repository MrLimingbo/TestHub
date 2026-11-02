# 遗留观察项清单

47条模块观察记录，不计通过；策略相近的多模块记录保留原用例标识，不宣称47个互斥问题。根因未确认的升级关闭超时单独列入UNCONFIRMED-RISK.md。

|编号/模块|观察项|已观察事实|待明确规则|来源|
|---|---|---|---|---|
|OBS-03B-E001 · 项目环境、参数与密钥|无定义环境配置的存储与可见性|无定义环境的参数和密钥仍可保存204，环境列表无对应定义。|明确是否允许自由环境代码及其展示和迁移规则。|[E001](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03b-environments/execution-results.json)|
|OBS-03B-E002 · 项目环境、参数与密钥|普通参数与密钥同名覆盖|普通参数同名保存会把原密钥行转为非密钥，密钥列表不再列出。|明确普通参数与密钥同名覆盖、提示和确认策略。|[E002](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03b-environments/execution-results.json)|
|OBS-03B-E005 · 项目环境、参数与密钥|停用环境参数与密钥写入|停用环境仍允许参数和密钥API写入，页面表单禁选该环境。|明确停用只限制执行还是也限制配置写入。|[E005](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03b-environments/execution-results.json)|
|OBS-03C-E005 · 文件管理与操作日志|筛选后其他动作选项可达性|选择某审计动作后，其他动作选项不再提供，须先清空。|明确动作选项是否始终完整可达。|[E005](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-03c-files-audit/execution-results.json)|
|OBS-03C-E007 · 文件管理与操作日志|全局审计刷新失败后的旧数据提示|全局审计刷新失败有错误提示，但仍显示旧记录。|明确旧数据标识、清空及重试展示规则。|[E007](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-03c-files-audit/execution-results.json)|
|OBS-03D-E006 · 账号管理与系统参数|停用删除改密后的已登录会话|停用、删除或改密后旧会话仍可访问me；旧密码新登录被拒。|明确是否强制注销既有会话，以及生效时点。|[E006](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-03d-accounts-config/regression-r1/execution-results.json)|
|OBS-03D-E007 · 账号管理与系统参数|搜索SQL通配符语义|搜索百分号和下划线按SQL通配符匹配。|明确应支持通配语义还是按字面量搜索。|[E007](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-03d-accounts-config/regression-r1/execution-results.json)|
|OBS-05-E002 · 测试计划与交付|计划PUT变更编号的响应语义|PUT中发送的新计划编号未应用，仍返回旧编号。|明确编号是否不可修改及忽略或拒绝的反馈。|[E002](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-05-plans-delivery/regression-r1/execution-results.json)|
|OBS-05-E003 · 测试计划与交付|历史执行用例标题是否跟随源资产|历史执行用例标题跟随源资产改名，步骤快照仍保留。|明确历史标题与步骤各自的快照边界。|[E003](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-05-plans-delivery/regression-r1/execution-results.json)|
|OBS-06-E006 · 接口设计与导入|非200响应码导入导出保持范围|导入存储201响应码，但OpenAPI导出只产生200。|明确非200响应码的保留范围及降级说明。|[E006](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-06-api-design/regression-r1/execution-results.json)|
|OBS-06-E012 · 接口设计与导入|复制目录中途失败的保留与重试|目录复制中途失败保留部分资产，重试生成另一完整分支。|明确续传、幂等、清理和重复分支处理入口。|[E012](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-06-api-design/regression-r1/execution-results.json)|
|OBS-07-S005 · 接口调试|PAT scope|PAT读scope可读描述但不能读历史，写scope可执行及读历史但不能读描述。|明确含解密回放草稿的历史权限例外并统一文档。|[S005](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)|
|OBS-07-U012 · 接口调试|脱敏旧历史禁止直接重发|旧脱敏历史可载入且未自动发送；当前入口只有载入、没有独立重发按钮，专用警告路径不可达。|明确手动发送脱敏请求的限制与告知。|[U012](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)|
|OBS-07-E006 · 接口调试|旧HTTP响应跨项目归属|跨项目的旧HTTP响应被丢弃，但仍出现旧完成全局通知。|明确跨项目完成通知的归属和保留规则。|[E006](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)|
|OBS-07-E012 · 接口调试|WS历史重发前旧会话处理|载入WS历史后，旧服务端会话仍保留OPEN。|明确关闭、复用、超时回收与会话可见性。|[E012](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)|
|OBS-07-E013 · 接口调试|回放密文与安全边界说明冲突|回放加密存储请求头、认证和正文，并向有写权限者解密返回；页面声称不保存。|核对产品存储边界、文案与授权规则，明确敏感信息告知。|[E013](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)|
|OBS-07-F012 · 接口调试|正式接口高级调试交接|高级调试交接保留method、URL、header、body等，但未传递独立Auth。|明确认证字段映射及用户提示。|[F012](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/execution-results.json)|
|OBS-08-E010 · 接口自动化|WAIT小数与非有限数值|WAIT小数配置可保存，执行失败。|明确整数、小数及非有限值的输入与执行校验。|[E010](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-08-api-automation/regression-r1/execution-results.json)|
|OBS-08-E011 · 接口自动化|停用环境跨入口一致性|停用环境仍能通过普通、预览、数据驱动及定时入口执行。|统一各执行入口的环境启用规则。|[E011](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-08-api-automation/regression-r1/execution-results.json)|
|OBS-08-E012 · 接口自动化|运行面板通知选项实际语义|运行面板的本页提醒仅为本地提交提示，没有向他人发送消息。|明确持久化、跨页面提醒和通知集成语义。|[E012](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-08-api-automation/regression-r1/execution-results.json)|
|OBS-09-E012 · Mock|公开分享页剪贴板拒绝|公开分享页复制被浏览器拒绝时，存在错误但反馈契约不明确。|明确复制失败的可见提示与恢复交互。|[E012](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-09-mock-regression/execution-regression.json)|
|OBS-09-E013 · Mock|优先级与有效期小数输入|优先级或有效期小数被转为整数，条件数字转字符串。|统一JSON整数和字符串转换策略。|[E013](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-09-mock-regression/execution-regression.json)|
|OBS-10-E010 · 性能测试|去空白query键冲突|去首尾空白后的query键冲突由后值覆盖。|明确冲突拒绝或覆盖及提示规则。|[E010](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-10-performance-regression/execution-final.json)|
|OBS-10-E011 · 性能测试|小数到整数输入转换|线程数小数被转换为整数，值数字转换为字符串。|明确精度、类型与拒绝规则。|[E011](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261026/module-10-performance-regression/execution-final.json)|
|OBS-11-E020 · Web UI自动化|变量键trim后重名策略|变量键trim后重名由后值覆盖。|明确重名检测与覆盖规则。|[E020](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-11-web-ui-regression/execution-final.json)|
|OBS-11-E021 · Web UI自动化|数值到整数/字符串的转换策略|视口数值转换为整数，变量数字转换为字符串。|统一数值类型校验策略。|[E021](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-11-web-ui-regression/execution-final.json)|
|OBS-11-E022 · Web UI自动化|页面对象保存与执行冻结时点|页面声称保存时冻结页面对象，实际入队时重新解析。|明确保存时与入队时各自冻结范围并同步文案。|[E022](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-11-web-ui-regression/execution-final.json)|
|OBS-12-E020 · 移动端自动化|JSON数字转整数/字符串策略|超时数字被转为整数，变量数字转字符串。|明确移动端JSON转换策略。|[E020](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-12-mobile-regression/execution-final.json)|
|OBS-12-E022 · 移动端自动化|跨平台定位策略使用|Android资产可保存iOS定位策略。|明确是否允许跨平台保存及执行前校验规则；真机验证仍阻塞。|[E022](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-12-mobile-regression/execution-final.json)|
|OBS-13-E009 · 质量报告与任务|task保存操作归属/reload_reopen|任务保存后的刷新期间，新草稿暂不可用。|明确刷新锁定范围和重新打开行为。|[E009](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)|
|OBS-13-E014 · 质量报告与任务|任务行操作等待CSRF期间切项目|任务行操作等待CSRF时切换项目，仍写入原项目，新项目不受影响。|明确离页或切项目后是否撤销已触发的行操作。|[E014](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)|
|OBS-13-E015 · 质量报告与任务|超过50可访问项目的默认页面|超过50个可访问项目时默认全选，而接口单次最多接受50个。|明确默认选择、分页和超限提示。|[E015](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)|
|OBS-13-E017 · 质量报告与任务|旧保存未完成时新草稿保存能力|旧保存未完成时，新草稿保存按钮仍受旧loading影响，但草稿未被覆盖。|明确不同表单会话的保存可用性。|[E017](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)|
|OBS-13-E018 · 质量报告与任务|进度与周期JSON小数转换|任务进度、报告周期的小数被转换为整数。|明确是否拒绝小数或如何取整。|[E018](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)|
|OBS-13-E019 · 质量报告与任务|报告项目参数末尾逗号|报告项目参数末尾逗号返回400，空筛选按默认项目集合处理。|明确空值、空段及默认选择语义。|[E019](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)|
|OBS-13-E020 · 质量报告与任务|无版本计划并发编辑策略|计划没有版本校验，后一次编辑覆盖前一次值。|明确乐观锁、冲突反馈或最后写入规则。|[E020](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-13-quality-tasks-regression/execution-final.json)|
|OBS-14-E004 · 通知、集成、插件与运维|notification保存归属/reload_reopen|通知模板保存刷新期间不能重新打开新建表单。|明确刷新锁定与重开行为。|[E004](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)|
|OBS-14-E014 · 通知、集成、插件与运维|package保存归属/reload_reopen|插件包保存刷新期间不能重新打开新建表单。|明确刷新锁定与重开行为。|[E014](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)|
|OBS-14-E019 · 通知、集成、插件与运维|adapter保存归属/reload_reopen|适配器保存刷新期间不能重新打开新建表单。|明确刷新锁定与重开行为。|[E019](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)|
|OBS-14-E022 · 通知、集成、插件与运维|通知行操作切项目撤销策略|通知行操作切项目后仍写入原项目。|明确行操作的切项目撤销策略。|[E022](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)|
|OBS-14-E023 · 通知、集成、插件与运维|全局插件行操作离页撤销策略|全局插件行操作离页后仍执行已触发写入。|明确全局操作离页撤销与完成告知规则。|[E023](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)|
|OBS-14-E024 · 通知、集成、插件与运维|普通JSON额外字段策略|普通JSON请求接受额外未知字段。|明确严格拒绝或兼容忽略的范围。|[E024](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)|
|OBS-14-E025 · 通知、集成、插件与运维|配置键空白归一冲突|配置键去空白归一后发生冲突仍被接受。|明确归一、重名和覆盖规则。|[E025](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/execution-final.json)|
|OBS-15-E001 · 桌面工作区与跨端|原生同步预览在离页后的旧响应|原生同步预览为只读，但持有工作区锁，预览返回前不能关闭窗口。|明确长时间预览取消及窗口关闭策略。|[E001](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-15-desktop-workspace-cross-end/execution-final.json)|
|OBS-16-E001 · 横向回归|进程RSS/CPU/句柄持续观察|真实30分钟进程RSS、CPU和句柄采样已保存，未提供泄漏或资源阈值。|给出业务负载、资源阈值和更长稳定性窗口后再判定。|[E001](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-final.json)|
|OBS-16-E002 · 横向回归|业务与健康延迟持续观察|31轮业务和健康延迟已采样，缺少业务SLA；窗口与其他独立验收有重叠。|明确业务SLA、负载和容量基线，不外推多日或容量结论。|[E002](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-final.json)|
|OBS-16-E003 · 横向回归|Redis故障时健康、readiness与业务一致性|Redis故障时总health503、readiness200、auth/me500，恢复后可用。|明确readiness是否纳入Redis及依赖故障时的业务反馈。|[E003](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-final.json)|
