# 项目管理与成员权限：四项缺陷修复

状态：用户已授权修复；完整回归结果以[测试报告](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03a-projects/TEST-REPORT.md)为准，完成后等待审核，不进入下一模块。

## 改动

1. `PersonalAccessTokenAuthenticationFilter` 每次Bearer请求重新查询有效令牌及账号状态，用Spring Security的`@Transient`上下文避免保存PAT身份。清除旧版本持久化的PAT身份，禁止旧Cookie独立认证；混合Cookie/Bearer按令牌作用域验证，同时保留正常浏览器会话。
2. `AccountManagementService` 对新成员账号单独生成最多80字符的默认显示名，完整账号仍支持160字符；截取时保护Unicode代理对。显式姓名和既有账号行为不变。
3. `AccessController.TokenRequest` 为每个scope增加非空/非空白验证，服务入口也显式拒绝非法元素，防止`trim()`触发500。
4. `AccessService` 在项目插入/更新的数据库写入处把`DuplicateKeyException`转为409，继续由唯一约束和事务保证单行项目、默认环境及审计完整。

未修改数据库结构、增加依赖或调整前端表单上限。

## 验证设计

- 新增`ProjectAccessFixesFlowTests`，25项执行：撤销/过期/停用，旧持久化会话，Cookie与Bearer优先级，浏览器身份保留，非法Authorization，79/80/81/159/160字符账号，Unicode边界，5组非法scope，创建与更新竞争各3次。
- 原`ApiWorkspacePreferencesFlowTests`中令牌权限场景改用真实签发的Bearer，保留原200/403和数据断言，避免用已过时的缓存PAT身份模拟请求。
- 先跑新25项及既有8项，再用`test-local-backend.mjs`运行完整隔离后端套件并打包；前端lint/test/build单列。
- 原冻结的120项完整后台重跑，5个原失败场景各追加一次确认；不增加原用例分母，不降低预期。会话夹具使用真实Spring Session仓库及编码Cookie。

## 结果与边界

源码检查、完整场景统计、历史复现及当前修复状态见[测试报告](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03a-projects/TEST-REPORT.md)与[缺陷明细](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-03a-projects/BUGS.md)。执行摘要位于同目录的`*-fix-verification.json`及`execution-results.json`，历史结果保留为`pre-fix-*`。

本轮以H2和headless Chrome验证，MySQL/Redis、已安装包和长期负载专项未执行；不能用本轮结果代替这些环境验收。无Git提交、安装或发布操作，后续模块等待用户审核。
