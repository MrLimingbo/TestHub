# 阶段 3.3.1：测试资产治理

验收日期：2026-07-12

## 交付内容

- 新增项目级分类标签：标签名称在项目内唯一，删除标签会级联解除资产标签绑定。
- 新增需求、测试用例、测试计划三类资产的标签绑定 API；读取、写入和绑定对象均校验项目范围。
- 新增自定义字段定义：支持文本、数字、日期、下拉选择四种类型，支持必填与下拉选项配置。
- 自定义字段标识只允许小写字母、数字和下划线，创建后保持稳定，便于后续导入导出及 API 集成。
- 新增“测试资产治理”页面，包含项目切换、标签管理、自定义字段创建/编辑/删除，以及权限范围说明。
- 功能测试页增加“资产治理”入口。

## 数据与安全边界

- Flyway `V4__functional_asset_governance.sql` 创建 `functional_tag`、`functional_asset_tag` 和 `functional_custom_field`。
- 所有新增记录均包含组织/项目范围；资产标签绑定会验证标签和资产同属当前项目。
- 仅系统管理员、组织管理员、项目管理员、测试负责人和测试工程师拥有写权限；所有写操作记录审计事件。

## 验证结果

- `./mvnw -q -pl services/platform-api -Dtest=FunctionalGovernanceFlowTests test`：通过。
- `./mvnw -q -pl services/platform-api test`：通过，18 个测试全部成功。
- `npx pnpm@10.13.1 exec eslint src/pages/FunctionalGovernancePage.vue src/pages/FunctionalTestingPage.vue src/router/index.ts src/test/router.test.ts`：通过。
- `npx pnpm@10.13.1 typecheck`、`npx pnpm@10.13.1 test`：通过，3 个前端测试全部成功。
- `npx pnpm@10.13.1 build`：通过；构建保留既有 Monaco/Arco 大包体积提示。
- `git diff --check`：通过。

## 浏览器验收路径

1. 登录后访问 `/functional/governance`，确认能看到“测试资产治理”。
2. 在“分类标签”新增一个标签，切换颜色后确认立即显示。
3. 在“自定义字段”新建一个适用于“测试用例”的下拉字段，填入逗号分隔选项并保存。
4. 编辑该字段的名称、必填设置或选项，确认字段标识不可编辑且修改后列表刷新。
5. 返回“功能测试”，确认可通过“资产治理”入口回到该页面。

下一节点将单独实现缺陷关联与 Excel 导入导出，待本节点浏览器验收通过后开始。
