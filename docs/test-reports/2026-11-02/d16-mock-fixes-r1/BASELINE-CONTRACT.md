# Mock已审核兼容基准

以下为当前实现的静态基准，用于遵守保留已审核行为的要求，不新增产品规则。代码修改前记录源文件SHA。

## 默认与空值

管理API中，priority省略/null为0；responseStatus省略/null为200；expiresInDays省略/null为永久有效。条件/响应头的null值归空字符串，正文原空值策略保持，不把真实null变为字符串"null"。

## 匹配与事务

原优先级顺序、字符串条件、Header及正文匹配规则保持；拒绝类型错误时不写业务记录、成功业务审计或可用分享。旧规则及历史分享可读，不做批量迁移。

## 完整迁移与同步协议

迁移/同步仍要求原有完整、已规范化资料，保留名称、权限、并发修订和requestKey回执规则。迁移新服务停用，导入的分享元数据保持撤销；同步不复制公共key或分享、不改变本端启停状态。

## 页面永久选项

页面勾选永不过期时，提交expiresInDays=null；未采用的有效期输入不应阻止永久分享。原JSON条件编辑器已要求值为字符串，这项现有保护继续验证。

## 取证源文件

|文件|修改前SHA256|
|---|---|
|services/platform-api/src/main/java/io/testhub/platform/mock/MockService.java|b05800dc5160f621721e8eb8a3bd655d39dccd6803a60def1718b0716909c04f|
|services/platform-api/src/main/java/io/testhub/platform/mock/MockSyncService.java|80ed5d17824bccd139abecb5c5e010c81aa0c23a351b0bc639137bb3c4e11609|
|services/platform-api/src/main/java/io/testhub/platform/mock/MockTransferService.java|320994d3cb4d151b03f69de5c91383aa5c839c9d4bef731ac19a75f40d4b9d79|
|src/pages/MockServicePage.vue|e28c776a447a2d86474a08f477f9fff77b39201138ce134e9a232b633849d613|

关键原文摘录：
- services/platform-api/src/main/java/io/testhub/platform/mock/MockService.java:221：`int priority = input.priority() == null ? 0 : input.priority();`
- services/platform-api/src/main/java/io/testhub/platform/mock/MockService.java:227：`int responseStatus = input.responseStatus() == null ? 200 : input.responseStatus();`
- services/platform-api/src/main/java/io/testhub/platform/mock/MockService.java:139：`Instant expiresAt = expiresInDays == null ? null : Instant.now().plus(expiresInDays, ChronoUnit.DAYS);`
- services/platform-api/src/main/java/io/testhub/platform/mock/MockService.java:347：`String value = entry.getValue() == null ? "" : entry.getValue();`
- services/platform-api/src/main/java/io/testhub/platform/mock/MockSyncService.java:128：`if(i.rule()!=null&&(!name(i.ruleName())||!i.ruleName().equals(i.rule().name())||!i.rule().equals(mocks.normalizeRule(i.rule())))) throw invalid("Mock 规则字段不合法或尚未规范化");`
- services/platform-api/src/main/java/io/testhub/platform/mock/MockSyncService.java:80：`if(!payloadHash.equals(prior.get().getKey())) throw conflict("同步请求标识已用于其他 Mock 内容");`
- services/platform-api/src/main/java/io/testhub/platform/mock/MockTransferService.java:133：`repository.revokeShare(projectId, created.id(), shareId);`
- services/platform-api/src/main/java/io/testhub/platform/mock/MockTransferService.java:136：`mocks.setServerEnabled(principal, projectId, created.id(), false);`
- src/pages/MockServicePage.vue:207：`if (typeof headerValue !== 'string') throw new Error(`${label}值必须是字符串`)`
- src/pages/MockServicePage.vue:305：`name: shareForm.name, expiresInDays: shareNeverExpires.value ? null : shareForm.expiresInDays,`
