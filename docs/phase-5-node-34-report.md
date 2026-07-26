# 阶段 5 / 节点 34：执行报告完整性校验

## 交付内容

- 新增只读 `GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}/report/integrity`，返回 v5 脱敏报告下载工件的 SHA-256、ETag、文件名和媒体类型。
- JSON 下载端点以实际响应字节计算 `X-TestHub-Report-SHA256` 与强 ETag，并以 `private, no-cache` 响应缓存策略交付。
- 支持 `If-None-Match`：当导出的实际报告字节未变化时返回 `304 Not Modified`，仍带有 ETag 与 SHA-256。
- 执行详情显示 SHA-256，提示其与下载响应头一致，可用于 CI 归档校验。

## 安全边界

- 哈希仅覆盖既有脱敏报告字节，不额外读取或发送请求配置、变量、密钥、日志或 Runner 用户内容。
- 完整性元数据与下载端点都复用项目读取鉴权；不同项目和未授权成员无法探测执行工件是否存在。
- 文件名只由服务端已校验的执行 ID 组成；条件请求只匹配服务端计算的 ETag，不解释自定义表达式。

## 验证

- 控制面集成测试验证 SHA-256 格式、元数据与下载响应头一致性、ETag 以及 `If-None-Match → 304`。
- 浏览器详情页从完整性端点读取并展示固定长度校验和，不显示报告以外的敏感数据。
