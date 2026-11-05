# D23 实施前入口与DTO清单

2026-11-04；D23-A / OBS-14-E024。先冻结本清单与先行用例，再测试和修复接口说明。本轮仅对下表给出普通外层DTO结论，不外推其他控制器或全局JSON策略。

|控制器|外层DTO|写入口|
|---|---|---|
|NotificationTemplateController|TemplateRequest|POST /api/v1/projects/{projectId}/notification-templates；PUT /api/v1/projects/{projectId}/notification-templates/{templateId}|
|ExtensionRegistryController|AdapterRequest|POST /api/v1/extensions/adapters；PUT /api/v1/extensions/adapters/{adapterId}|
|ExtensionRegistryController|SigningKeyRequest|POST /api/v1/extensions/signing-keys|
|ExtensionRegistryController|PackageRequest|POST /api/v1/extensions/plugins/packages|

总计2个普通控制器、4类DTO、6个写入口。GET列表用于验证公开字段及落库结果；无JSON请求体的DELETE、启停操作不计为外层DTO入口。

严格边界核验：NotificationTransferController的POST /api/v1/projects/{projectId}/notification-templates/transfer（ImportRequest及嵌套Snapshot/Template）；NotificationSyncController的POST /api/v1/projects/{projectId}/notification-templates/sync（Request及嵌套Operation/Item）。二者通过byte[]和显式严格reader解码，继续拒绝未知字段，不复用普通DTO策略。

动态Map边界为AdapterRequest.configuration：合法自定义键及原值校验、加密保存和脱敏投影保持；错误值类型或必填项缺失仍按原规则拒绝。PackageRequest.manifestJson是被签名的字符串，签名和checksum基于原始UTF-8字符，内层结构遵循PluginManifest及既有业务校验。数据库JSON列可能规范化排版，不将其误说为原始字节归档；本轮不修改该存储契约。

测试仅使用自有H2/MySQL隔离库。管理员正例在隔离事务内创建临时签名公钥，私钥只在内存中，事务回滚；普通用户权限用例不预建可信签名密钥。EMAIL模板停用，不触发外部发送；适配器只验证登记，地址用保留测试域，不声称第三方接入完成。

本轮补齐4个DTO的OpenAPI描述和独立兼容契约，保持原JSON运行行为，不修改全局ObjectMapper、严格reader或PluginManifest。若专项发现与D23-A不符，再按实际证据修复，不制造必须失败的产品红测。
