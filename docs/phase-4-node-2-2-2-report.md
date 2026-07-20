# 阶段 4 · 节点 4.2.2.2：HAR 导入导出

**状态：待产品验收**

## 本节点交付

- 新增 HAR 1.2 JSON 导入，递归处理 `log.entries` 中的 HTTP/HTTPS 请求；非 HTTP 条目会跳过，不会被写成接口资产。
- 导入映射 HTTP 方法、URL 路径、查询参数、请求头、`postData` 请求体、响应状态码和响应内容；`application/x-www-form-urlencoded` 的参数型请求体会转换为表单字符串。
- Base64 编码的响应内容会以 `data:<mime>;base64,...` 形式保留，避免二进制内容被误当作文本。
- 新增 HAR 1.2 导出，可按项目或接口目录导出，并生成标准 `log`、`creator`、`entries`、request、response、cache 与 timings 结构；导出支持可选 `baseUrl`。
- 接口：
  - `POST /api/v1/projects/{projectId}/api-design/interchange/har/import`
  - `GET /api/v1/projects/{projectId}/api-design/interchange/har/export?catalogId={catalogId}&baseUrl={baseUrl}`
- 前端“导入 / 导出接口资产”增加 HAR 1.2 导入选项、`.har` 文件选择、专用提示和“导出 HAR 1.2”操作。

## 当前兼容边界

- 仅接受 HAR 1.2 JSON，不导入浏览器页面、性能、Cookie、缓存、WebSocket 或自定义扩展字段。
- HAR 没有接口名称与路径参数的统一语义；导入名称使用 `方法 + 路径`，URL 中的具体路径值不会被自动推断为 `{pathParam}`。
- 导出默认使用 `https://api.example.com` 作为基础地址；调用方可通过 `baseUrl` 覆盖。

## 验证记录

- 后端：`./mvnw -q -pl services/platform-api test` 通过，共 23 个测试。新增 `HarArchiveFlowTests` 覆盖 HAR 1.2 导入、参数/请求体/响应映射，以及带自定义基础地址的 HAR 再导出。
- 前端：`npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build` 通过（3 个前端测试）。构建仅保留既有 Monaco source map 缺失提示和大体积分包提示。
- 浏览器：在“交易中台 V3 / 订单服务”导入 HAR 1.2 示例，生成 `POST /api/v1/preview/har-browser`。已确认路径、`mode` 查询参数、`X-Trace` 与自动补齐的 `Content-Type`、JSON 请求体和 `{\"ok\":true}` 响应示例均正确回显。预览页面已保留在浏览器中。
- `git diff --check` 通过。

## 下一节点

本节点验收通过后，阶段 4.2 的资产格式导入导出完成，下一节点进入 **阶段 4.3：接口调试与 Mock**。
