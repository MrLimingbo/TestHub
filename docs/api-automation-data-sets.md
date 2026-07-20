# 接口自动化数据集与数据驱动执行

## 目标与边界

本节点为项目内接口自动化提供 CSV / JSON 数据集。一个数据集与一个场景绑定执行时，控制面会为每一行创建一条独立的 `api_automation_execution`、运行时快照和安全 Outbox 任务。

- 数据集是项目级资产：`organization_id`、`project_id`、创建者和审计记录均被持久化。
- JSON 仅接受非空对象数组；CSV 的第一行必须是字段名。
- 字段名必须匹配 `[A-Za-z_][A-Za-z0-9_]{0,63}`；最多 50 字段、200 行、单值 10,000 字符、源内容 512 KiB。
- CSV / JSON 原文件可经 `multipart/form-data` 导入。导入前会验证 UTF-8 编码、扩展名、格式、大小和数据结构；验证成功的原文件按项目范围归档到 MinIO，数据集只关联其文件 ID、名称、类型和字节数。
- 读取成员可查看前五行预览；有项目写入角色的成员可以创建、替换、删除与发起数据驱动执行。
- 环境密钥不属于数据集。若数据集字段与场景环境的密钥名重名，提交执行会被拒绝。

## API

所有端点位于 `/api/v1/projects/{projectId}/api-automation/data-sets`：

- `GET /`：列出不含行值的元数据。
- `GET /{dataSetId}`：返回元数据与前五行预览。
- `POST /`、`PUT /{dataSetId}`：请求体为 `name`、`sourceFormat`（`JSON` 或 `CSV`）和 `content`。
- `POST /import`：`multipart/form-data`，字段为 `name`、`sourceFormat` 和 `file`。文件名扩展名必须与来源格式一致（`.json` 或 `.csv`），最大 512 KiB。响应中的 `sourceFile` 仅包含项目文件元数据；原文件下载沿用 `GET /api/v1/projects/{projectId}/files/{fileId}/download` 的项目读取权限。
- `DELETE /{dataSetId}`：删除源数据集；已经提交的执行快照仍可追溯。
- `POST /{dataSetId}/scenarios/{scenarioId}/executions`：以 `{ "timeoutSeconds": 300 }` 按行创建任务。

执行响应只含数据集 ID、数据集名称、行号和执行元数据，不包含行值。

## 变量优先级与安全输出

对每一行，变量来源按以下优先级选择：

1. 项目非密钥参数
2. 场景变量
3. 数据集行
4. 环境密钥（只保留名称，绝不读取或解密取值）
5. Runner 本地提取变量

数据集行仅以 `DATA_SET` 来源进入控制面的内部运行时快照。任务 Outbox 消息、执行清单、日志、审计详情和结构化报告都不携带行值；它们最多包含数据集 ID、行号、字段名、来源或校验和。原始附件也不会进入这些执行链路，Runner 不读取 MinIO 原文件。实际 Runner 仍只能通过已有的签名租约读取其单次运行需要的冻结配置。

原始附件是可追溯的项目文件，而不是执行物。删除数据集不会删除其已经归档的项目附件，避免破坏其他项目审计或下载记录；文件生命周期由项目文件管理页面控制。
