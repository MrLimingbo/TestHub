# 阶段 5 · 节点 30：数据集文件导入与 MinIO 原始附件追溯

完成日期：2026-07-17

## 已交付

- MySQL Flyway `V29`：为 `api_automation_data_set` 增加可为空的 `source_file_id`，以外键关联项目级 `file_object`；原有手工录入数据集保持兼容。
- 新增 `POST /api/v1/projects/{projectId}/api-automation/data-sets/import`：接收 `name`、`sourceFormat`、`file` 三个 multipart 字段，复用现有项目文件 MinIO 存储能力。
- 导入前强制验证 `.csv` / `.json` 扩展名、UTF-8 编码、512 KiB 上限以及既有字段、行数、CSV 引号和 JSON 结构限制。解析失败时不会写入 MinIO。
- 规范化后的行仍保存在数据集表，原始文件只以项目文件元数据关联。列表、详情和数据预览显示文件名、类型、大小及受项目权限保护的下载链接。
- 前端数据集弹窗增加“粘贴内容 / 上传原始文件”切换；文件导入时自动按扩展名选择来源格式，并清楚提示原文件不会发送给 Runner。
- 发生数据集持久化冲突时，控制面会尝试补偿删除刚上传的项目文件；附件下载仍沿用统一的项目文件权限边界。

## 安全边界

- 原始附件仅能由当前项目读取成员下载，不能跨项目读取。
- 执行任务、Outbox、执行清单、运行时摘要、日志、审计数据和结构化报告不携带原文件或行值；Runner 不访问 MinIO 原文件。
- 删除数据集不自动删除归档后的项目附件，以保留项目文件审计和避免误删可追溯材料；附件生命周期由项目文件管理功能负责。

## 验证

```bash
npx pnpm@10.13.1 lint
npx pnpm@10.13.1 test -- --run src/test/router.test.ts
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -q -pl services/platform-api -Dtest=ApiAutomationDataSetFlowTests test
```

以上检查已通过。数据集集成测试覆盖 CSV multipart 导入、MinIO 原字节下载、元数据返回与错误扩展名在存储前被拒绝。

## 后续

- 大型文件的异步解析、进度和病毒扫描。
- 数据生成器、组合数据源、迭代器与更细粒度的数据脱敏策略。
