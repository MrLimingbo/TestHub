# 阶段 5 · 节点 20：接口自动化数据集与数据驱动执行

完成日期：2026-07-16

## 已交付

- MySQL Flyway `V23`：新增项目级 `api_automation_data_set`，并为执行任务增加 `data_set_id` 与 `data_set_row_number` 追溯字段。
- CSV / JSON 数据集 API：创建、替换、删除、列表与前五行预览，带字段、大小、行数、CSV 引号及重复字段校验。
- 数据驱动执行：每行独立创建执行、内部运行时快照、控制面日志与 Runner Outbox 任务；普通单场景执行保持兼容。
- 安全约束：数据集不能覆盖当前环境密钥；行值不会进入任务消息、执行清单、日志、审计详情、运行时摘要或报告。
- Web：接口自动化页新增数据集资产列表、CSV / JSON 粘贴导入、受项目权限保护的预览、逐行执行弹窗，以及执行列表和详情中的数据集行号追溯。
- 自动化测试：覆盖 JSON / CSV、CSV 格式拒绝、逐行创建、运行时值冻结、API/Outbox/审计脱敏。

## 验证

```bash
npx pnpm@10.13.1 lint
npx pnpm@10.13.1 test
npx pnpm@10.13.1 build
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -q -pl services/platform-api -Dtest=ApiAutomationDataSetFlowTests test
```

以上命令在本节点完成时均已成功执行。Flyway 迁移已在本地 MySQL 8.4 开发库验证至版本 23。

## 待后续节点

- 上传大型 CSV / JSON 文件并以 MinIO 保存原始附件。
- 变量数据生成器、迭代器、组合数据、更多数据源与数据脱敏策略。
- 前后置 JavaScript 及其独立 Runner 隔离能力。
