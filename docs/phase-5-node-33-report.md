# 阶段 5 / 节点 33：脱敏执行报告导出

## 交付内容

- 新增 `GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}/report/export`。
- 端点返回 `application/json` 附件；JSON 根对象就是既有的 v5 脱敏执行报告，不带 API 信封。
- 执行详情新增“下载脱敏报告 JSON”入口，浏览器会沿用当前登录会话下载附件。
- CLI/CI 示例增加归档命令，文件名由服务端固定为 `testhub-api-automation-execution-{executionId}.json`。

## 安全边界

- 导出复用报告服务的项目读取鉴权，不接受额外筛选、模板或数据字段。
- 导出正文与报告 API 的 `data` 完全一致，不新增 URL、请求/响应、变量、密钥、日志或自由文本。
- 附件文件名仅由服务端验证过的执行 ID 组成，避免浏览器端可控的响应头内容。

## 验证

- 控制面集成测试断言附件状态、内容类型、`Content-Disposition` 文件名、v5 契约、并行组重试计数以及 URL 脱敏。
- 前端构建验证下载入口仅在已选执行任务与项目作用域存在时生成受范围约束的地址。
