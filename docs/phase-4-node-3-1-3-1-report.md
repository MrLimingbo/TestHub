# 阶段 4 · 节点 4.3.1.3.1：GraphQL 与 SOAP 调试

**状态：待产品验收**

## 本节点交付

- 调试器协议选择新增 HTTP、GraphQL 与 SOAP；三种协议均复用既有的受限 HTTP 执行、环境变量、动态值、认证与历史脱敏边界。
- GraphQL 请求自动构造标准 JSON：`query`、`variables` 和可选 `operationName`；`variables` 必须是 JSON 对象，协议强制使用 POST 和 `Content-Type: application/json`。
- SOAP 1.1 请求支持 XML Envelope 与可选 `SOAPAction`；协议强制使用 POST 和 `Content-Type: text/xml; charset=utf-8`。
- 协议 Content-Type 会覆盖编辑器里遗留的 HTTP Content-Type，防止切换协议后以错误媒体类型发送。
- GraphQL 查询、变量、操作名、SOAP Envelope 与 SOAPAction 都支持已有的 `{{VARIABLE_NAME}}` 和动态值替换。

## 当前兼容边界

- GraphQL 通过 HTTP JSON 端点发送；本节点不加载远程 schema、不提供 introspection、订阅或 GraphQL WebSocket。
- SOAP 仅覆盖 SOAP 1.1 XML Envelope；不包含 WSDL 导入、SOAP 1.2、MTOM/附件或 WS-Security 签名。
- WebSocket 与 gRPC 将在下两个独立节点实现。

## 验证记录

- 后端：`./mvnw -q -pl services/platform-api test` 通过，共 26 个测试。`ApiDebugFlowTests` 覆盖 GraphQL JSON 构造、variables/operationName、SOAP Envelope/SOAPAction，以及 SOAP 覆盖错误手工 Content-Type 的行为。
- 前端：`npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build` 通过（3 个前端测试）。构建仅保留既有 Monaco source map 缺失提示和大体积分包提示。
- 浏览器：向 `https://postman-echo.com/post` 发送真实 GraphQL 请求，目标回显 `query`、`variables` 和 `operationName`；随后发送 SOAP 请求，目标回显 XML Envelope、`SOAPAction` 与最终的 `text/xml; charset=utf-8` Content-Type。
- `git diff --check` 通过。

## 下一节点

在本节点验收通过后，进入 **4.3.1.3.2：WebSocket 调试**。
