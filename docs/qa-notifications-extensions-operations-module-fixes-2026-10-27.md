# 通知、集成、插件与运维5项修复

用户授权修复QA-NIO-001～005并后台回归，完成后等待审核。

- QA-NIO-001：通知表单保存传入项目、视图和表单会话检查；令牌返回后先检查取消/重开/切项目/卸载状态，旧草稿不发POST或PUT。
- QA-NIO-002：可信签名密钥登记使用当前表单会话检查，取消或离页后等待中的令牌不会继续建立信任记录。
- QA-NIO-003：签名清单安装在取得CSRF后校验当前表单会话；取消或离页不再提交旧安装请求。
- QA-NIO-004：适配器创建和编辑共用提交前表单归属检查，已取消草稿不再持久化凭据或元数据。
- QA-NIO-005：readiness明确包含readinessState与minio健康指标；存储故障返回503/DOWN，恢复后200/UP；liveness及空桶惰性创建规则保持。

业务文件：src/pages/NotificationTemplatesPage.vue、src/pages/PluginsPage.vue、services/platform-api/src/main/resources/application.yml。新增38项组件与4项后端回归测试；两个旧组件断言加强为取消后零写入。

定向前端66/66，全量前端755/755；隔离H2后端34/34，lint/类型检查/构建通过。原187项后台回归180通过/0失败/7观察，原10项独立复验通过，9项取消落库新增均为0。

[完整测试报告](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/TEST-REPORT.md)

[Bug详细回归](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-14-notifications-extensions-operations-regression/BUG-REGRESSION.md)

只使用隔离资源和自有TLS，测试进程与临时数据已清理；无安装、无提交、未进入下一模块。状态：等待用户审核。
