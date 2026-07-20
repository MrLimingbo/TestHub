# 阶段 4 · 节点 4.3.1.3.3.2：gRPC Metadata 与认证

**状态：待产品验收**

## 本节点交付

- gRPC Unary 调试器新增 `Metadata` 与“认证”标签页，支持自定义 ASCII Metadata，以及 Bearer、Basic、API Key 三种认证方式。
- 控制面新增 Metadata、认证的项目级 API 契约；变量、环境密钥和动态值仅在调用时解析，认证值不会回显、写入调试历史或审计详情。
- gRPC 传输层将已验证的 Metadata 同时附加到服务反射和实际 Unary 调用，因而可调试需要认证才能读取反射描述符的服务。
- Metadata 限制为最多 32 项（含认证）、总长度 24 KB、单值 8192 字符；拒绝重复键、`grpc-` 保留前缀、`-bin` 二进制键和不可打印 ASCII 值。
- Bearer 与 Basic 使用 `authorization` Metadata；API Key 仅允许作为 Metadata。认证键与自定义 Metadata 重复时会在建立网络连接前拒绝请求。
- 历史仍只保存脱敏的 gRPC 目标、状态、耗时和结果；审计仅额外记录 `metadataCount` 与认证类型，不记录任何键值或凭据。

## 验证

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -q -pl services/platform-api -Dtest=GrpcDebugFlowTests,GrpcReflectionDebugTransportTests test`：通过。包含环境密钥解析、Bearer Metadata、保留键拦截，以及真实本地 Reflection + Unary 服务的 Metadata 传递验证。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -q -pl services/platform-api test`：通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过。构建仍报告既有 Monaco sourcemap 缺失与较大 chunk 警告，不影响测试和产物。
- 已重启本地控制面，运行于 `http://localhost:8080`；浏览器验收入口为 `http://localhost:5173/api-debug`。

## 本节点边界

- 仅支持 ASCII Metadata；二进制 `-bin` Metadata 留待独立的安全编码方案。
- 仅支持反射发现后的 Unary 调用；Proto 文件导入、服务/方法枚举、客户端/服务端/双向流式调用仍未开始。

## 下一节点

建议下一小节点为 **4.3.1.3.3.3：gRPC 服务与方法发现**，提供受控的反射服务/方法枚举和可选填充，继续保持不持久化描述符与访问控制边界。
