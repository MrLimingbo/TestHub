# 阶段 4 · 节点 3.1.3.3.7：gRPC 双向流基础

## 交付范围

- 新增项目作用域的双向流会话：建立、逐条提交 JSON、实时读取服务端消息、完成发送与取消。
- 新增 `/api/v1/projects/{projectId}/api-debug/grpc/bidi-streams` 及会话、消息、完成和取消子资源。
- 使用动态 Protobuf 描述符支持在线反射与离线 `.proto` / DescriptorSet 调用；仅允许真正的双向流方法。
- 双向会话不写入 API 调试历史：请求、响应、Metadata 与认证值仅存在于当前控制面内存。

## 安全与边界

- 复用公开 gRPC 主机:端口校验、变量/密钥执行期解析、ASCII Metadata 与认证限制。
- 每个用户/项目最多 5 个活跃双向会话；请求和响应方向分别限制为 100 条消息、512 KB，单条显示最多 64 KB。
- 客户端请求发送在订阅中串行化；每收到一条响应后按条请求下一条，避免绕过入站流控。
- 点击“完成发送”仅半关闭客户端方向，仍允许服务端继续发送；取消和平台停机都会释放网络调用。

## 验证

- `./mvnw -q -pl services/platform-api -Dtest=GrpcDebugFlowTests,GrpcReflectionDebugTransportTests test`
- `./mvnw -q -pl services/platform-api test`
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`

前端构建中的 Monaco source map 缺失与大包体积为既有非阻断警告；类型检查、测试和生产构建均已通过。
