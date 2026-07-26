# 阶段 4 · 节点 4.2.2.1：Postman Collection 导入导出

**状态：待产品验收**

## 本节点交付

- 新增 Postman Collection v2.0/v2.1 JSON 导入，导入前完整校验，错误文档不会写入部分接口资产。
- 支持递归读取 Collection 文件夹、HTTP 方法、变量化 URL、查询参数、路径参数、启用的请求头、常见请求体（raw JSON、urlencoded、form-data 文本字段、GraphQL）和首个响应示例。
- Collection 文件夹以 `目录 / 接口名称` 的形式保留在 TestHub 接口名称中；本节点不重建独立的 Postman 文件夹层级。
- 新增项目或目录范围的 Postman v2.1 Collection 导出，包含 `baseUrl` 变量、请求参数、请求头、请求体和基础成功响应示例。
- 接口：
  - `POST /api/v1/projects/{projectId}/api-design/interchange/postman/import`
  - `GET /api/v1/projects/{projectId}/api-design/interchange/postman/export?catalogId={catalogId}`
- 前端“导入 / 导出接口资产”弹窗增加 Postman Collection 选项、专用 JSON 输入提示和“导出 Postman Collection”操作。

## 当前兼容边界

- 本节点不迁移 Postman 认证配置、Cookie、事件脚本、测试脚本、预请求脚本、Mock 示例集或 Collection Runner 配置；这些能力属于接口调试与自动化节点。
- 仅接受 Postman Collection v2.0/v2.1 JSON，不接受 Environment、Globals 或非 Collection 文件。

## 验证记录

- 后端：`./mvnw -q -pl services/platform-api test` 通过，共 22 个测试；新增 `PostmanCollectionFlowTests` 覆盖嵌套 Collection 的导入、`:orderId` 到 `{orderId}` 转换、参数/请求体映射及 v2.1 导出。
- 前端：`npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build` 通过（3 个前端测试）。构建仅保留既有 Monaco source map 缺失提示和大体积分包提示。
- 浏览器：在“交易中台 V3 / 订单服务”导入嵌套 Collection，生成 `浏览器导入目录 / Postman 浏览器验收`。已确认路径 `/api/v1/preview/postman-browser`、查询参数 `draft`、`Authorization` 与 `Content-Type` 请求头、JSON 请求体和 `{\"ok\":true}` 响应示例均正确回显。预览页面已保留在浏览器中。
- `git diff --check` 通过。

## 下一节点

在本节点验收通过后，进入 **4.2.2.2：HAR 导入导出**。
