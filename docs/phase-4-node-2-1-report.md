# 阶段 4.2.1：OpenAPI / Swagger 与 cURL 导入导出

验收日期：2026-07-13

## 交付内容

- 新增 OpenAPI 3.0/3.1、Swagger 2.0 的 JSON/YAML 文本与本地文件导入；文档中的路径、方法、参数、请求体、响应示例和摘要会转换为项目接口资产与初始版本。
- 新增 cURL 导入，支持 `-X`/`--request`、`-H`/`--header`、`-d`/`--data`/`--data-raw`/`--data-binary`，并识别查询参数、请求头和请求体。
- 支持按当前项目或指定接口目录导出 OpenAPI 3.1 JSON；支持从当前接口生成 cURL 预览。
- 所有导入操作先验证目标目录与内容；同一事务中的任一接口冲突或格式错误会回滚，避免部分写入。
- 导入、导出均沿用项目范围读写权限；导入成功会写入审计日志。导出的 cURL 仅生成文本，不会发起网络请求。
- 接口设计工作台新增“导入 / 导出”入口，支持选择 `.json`、`.yaml`、`.yml` 文件或粘贴规范，并显示实时导入结果和 cURL 预览。

## 接口

- `POST /api/v1/projects/{projectId}/api-design/interchange/openapi/import`
- `POST /api/v1/projects/{projectId}/api-design/interchange/curl/import`
- `GET /api/v1/projects/{projectId}/api-design/interchange/openapi/export`
- `GET /api/v1/projects/{projectId}/api-design/definitions/{apiId}/interchange/curl`

## 验证结果

- `./mvnw -q -pl services/platform-api -Dtest=ApiInterchangeFlowTests test`：通过，覆盖 OpenAPI YAML、Swagger 2.0、cURL 导入、OpenAPI 导出、cURL 导出及续行格式。
- `./mvnw -q -pl services/platform-api test`：通过，21 个后端测试全部成功。
- `npx pnpm@10.13.1 lint`、`npx pnpm@10.13.1 test`、`npx pnpm@10.13.1 build`：通过；前端测试 3 项全部成功。
- 浏览器实测：在 `/api-design` 中导入 cURL `POST /api/v1/preview/exchange-172` 成功，并可预览不含多余 `+` 前缀的标准 cURL 续行命令。

## 浏览器验收路径

1. 打开 `/api-design`，在接口定义区点击“导入 / 导出”。
2. 在 OpenAPI / Swagger 页签中选择目录，粘贴或选择 JSON/YAML 规范后点击“校验并导入”。
3. 切换到 cURL 页签，输入命令并导入，确认结果与接口列表同步更新。
4. 点击“导出 OpenAPI 3.1”验证下载；选中任一接口后点击“生成当前接口 cURL”，确认预览包含正确方法、URL、查询参数、请求头和请求体。

下一节点为 4.2.2：Postman Collection 与 HAR 导入导出兼容能力。
