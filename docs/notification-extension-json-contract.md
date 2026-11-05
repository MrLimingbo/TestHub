# 通知与扩展普通JSON兼容契约

2026-11-04；D23-A / OBS-14-E024。仅适用于[实施前清单](test-reports/2026-11-04/d23-json-policy-fixes-r1/SCOPE-INVENTORY.md)内的2个控制器、4类外层DTO、6个写入口。

## 普通外层DTO

NotificationTemplateController.TemplateRequest的创建/更新，以及ExtensionRegistryController.AdapterRequest的创建/更新、SigningKeyRequest登记和PackageRequest登记/升级，保持兼容忽略未知外层字段。已知字段合法时，附加未知标量、对象或数组的结果与未附加时的已知字段投影一致；未知字段不入库、不执行、不进入成功审计或权限决策。

忽略未知字段不放宽已知字段校验。拼错必填字段仍按缺失处理，错误JSON类型、渠道/适配器/公钥/签名/清单业务规则保持。项目、组织、创建/更新用户取路径与登录主体；附加admin、roles、organizationId、projectId或userId不取得权限。普通用户不能登记可信公钥、适配器或插件。已知数据库唯一约束等原业务前置条件仍适用。

4类DTO在OpenAPI组件说明中明确上述政策；AdapterRequest.configuration和PackageRequest.manifestJson另有字段说明。此结论不外推其他业务接口，不更改全局ObjectMapper。

## 动态配置与签名内容

configuration是Map<String,String>，合法自定义键遵循既有键/值校验和所选类型必填键要求，保存在加密配置中；公开响应只列配置键，不回显密文或配置值。更新省略或null保留原配置，创建需要完整配置。动态Map中的合法键不会因普通DTO的未知字段政策被丢掉。

manifestJson是签名原文字符串。签名和SHA-256使用提交的原始UTF-8字符，包括首尾空白；外层兼容处理不得trim或重新序列化此字符串。内层PluginManifest及权限、入口、版本等业务校验沿用独立契约，不声明任意未知内层字段的新接收/拒绝政策。数据库JSON列可能规范化排版，现有JSON落库并非原始字节归档；本轮验证原始签名/checksum和已知内层内容，不改变存储模型。

## 企业适配器名称冲突

用户已批准BUG-D23-001采用409。创建或更新命中既有`(adapter_type, display_name)`唯一约束时，返回HTTP409及共享REQUEST_REJECTED错误，提示同类型名称已存在，不回显SQL、冲突名称或配置值。失败时不部分修改记录、密文、状态或成功审计；并发争用同一组合只有一个成功、另一个409。原记录保留自己的类型和名称允许更新，不同类型可同名，原trim、排序及其他校验保持。

该规则在适配器写入阶段转换DuplicateKeyException，仍以数据库唯一约束仲裁。其他异常、权限、CSRF、缺失记录和审计失败保持原处理。前端沿用失败保留草稿流程，可改为自由名称后重试。

## 严格入口与测试边界

通知迁移ImportRequest及嵌套Snapshot/Template、通知同步Request及嵌套Operation/Item继续由原严格reader解码，未知字段返回400，无模板、回执或成功审计写入。它们不复用本批普通外层DTO策略；其他严格业务入口保持原代码和全量回归。

仅使用自有隔离数据库和事务；签名私钥在测试内存中，普通账号权限用例不建立可信密钥。EMAIL夹具停用，不发送通知；企业适配器只登记配置，不据此宣称真实服务接入通过。

重复适配器名称500已按获批409规则修复并复验，当前结果见[更新后的D23报告](test-reports/2026-11-04/d23-json-policy-fixes-r2/FINAL-REPORT.md)，等待用户审核；[初轮复现快照](test-reports/2026-11-04/d23-json-policy-fixes-r1/REPRODUCTION-AND-REGRESSION.md)保留。12项环境补齐暂缓，升级关闭超时独立未确认。
