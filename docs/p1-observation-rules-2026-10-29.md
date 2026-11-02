# 已批准的6项P1规则

用户于2026-10-29批准：D02=A、D05=A/P0、D11=A、D12=A/30d/L0/G0、D03=A、D25=A/5s。本文件描述本轮实现契约；执行结果见同日`test-reports/p1-fixes-r1`。其余31项观察与12项环境补齐仍暂缓，升级关闭超时另行保留未确认风险。

## 参数与会话

同一项目、环境和数据库唯一键范围内，普通参数与密钥跨类型同名保存返回409，不隐式转换、不改写原值；同类型更新保持原规则。类型判断、写入及环境停用与执行提交，分别在同一项目数据库锁保护下串行化。

账号停用、删除或重置密码会增加持久安全版本。每次受保护的浏览器请求检查当前账号状态和版本；旧Session返回401，停用后再启用也不能恢复旧Session。普通资料修改不失效。提交安全事件之前已进入执行的请求不承诺回滚。自改密码成功后界面清理当前身份并提示重新登录。P0保留密码重置后的现有PAT行为；停用/删除账号仍不得使用PAT。

## 调试历史读取与加密回放

`GET /api/v1/projects/{projectId}/api-debug/history`沿用`project:write`及当前项目写角色要求；不新增只读摘要API。scope、令牌绑定项目和当前账号角色必须同时满足。HTTP GET不代表仅返回公开摘要。

|PAT显式scope|gRPC描述符列表|原调试历史（含可用草稿）|
|---|---|---|
|project:read|允许|403|
|project:write|403|允许|
|project:read + project:write|允许|允许|
|run:execute|403|403|

上述允许仍须满足项目与角色条件。浏览器沿用SYSTEM_ADMIN、ORG_ADMIN、PROJECT_ADMIN、TEST_LEAD、TESTER等既有写角色规则；撤销项目成员权限不能凭旧scope继续解密。

HTTP/GraphQL/SOAP保存既有请求配置白名单：name、method、原始url、启用headers（包括Cookie）、body、timeoutMs、environmentCode及已支持协议配置。gRPC Unary保存endpoint/tls/service/method/requestJson/timeoutMs/environmentCode/metadata/当前Auth/descriptorId。Auth只保存当前模式需要的字段，未启用模式的隐藏旧值不保存。环境密钥保留引用，不能另存解析值。草稿列加密保存；摘要、审计和错误不包含草稿原文。无新增响应原文、原始描述符、本地文件或WebSocket/流消息的历史持久化。

有效期按UTC原创建时间加30天计算，`now >= expiresAt`立即不可回放。读取、导入和重启不延长期限。缺失或未来创建时间禁止回放；解密失败明确显示不可用。API继续保留摘要及可解析的新增字段：`replayExpiresAt`和`replayStatus`。状态包括AVAILABLE、EXPIRED、UNKNOWN_CREATED_AT、UNREADABLE、SUMMARY_ONLY；不可用时`replayDraft`为空。

L0：旧密文按原时间限制读取，不改写、不批量删除旧密文。G0：只有新原生记录显式标记`replay_retention_managed=true`；到期后清空其密文列，摘要、ID、时间及历史记录仍保留。迁移默认false，迁入旧历史保守维持false。启动时补偿，并在正常运行期间最多每24小时一次清理；停机期间不保证物理删除。失败记录脱敏错误，后续重试。旧备份、导出文件及其他观察不纳入此清理。

## 接口自动化停用环境

以运行配置覆盖后的有效环境判断。停用环境拒绝普通、数据驱动、PAT/API、入站Webhook和执行预览的新执行，返回409且不留下任务/manifest/outbox半成品。Quartz本次触发记录ENVIRONMENT_DISABLED跳过，不增加Runner连续失败数、不自动停用计划、不补跑跳过批次。纯配置预览仍可读取。已接受队列和运行任务继续使用原manifest快照；既有取消能力保持。共享P2参数环境观察仍暂缓。

## 依赖故障与5秒探针

服务端readiness包含readinessState、数据库、Redis及现有MinIO检查；桌面包含readinessState、本地数据库及文件存储，不要求未使用的Redis。可选Runner不纳入readiness。空桶维持按首次写入创建的既有就绪语义。

公开health/readiness/liveness只返回状态，绕过Session存储。health/readiness使用最多4个探针线程、不排队，在5秒截止内返回（内部等待预算4.8秒）；超时或饱和503。liveness仅进程状态，独立于依赖探针，避免依赖故障触发重启风暴。Redis连接/命令超时分别1秒/2秒；存储元数据探测使用独立、有界客户端，不改变文件传输超时。

已知依赖不可用错误使用共享`DEPENDENCY_UNAVAILABLE`封装返回503，包括Spring Session加载/保存阶段。无法确认身份时不放行；能判断的401/403保持；普通程序缺陷仍500。依赖恢复后合法业务可恢复，已失效的Session仍须重新认证。此项不修改桌面升级退出逻辑、超时阈值或发行门槛。
