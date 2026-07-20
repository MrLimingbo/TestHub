# 阶段 4 · 节点 3.1.3.3.5：gRPC 服务端流基础

## 交付范围

- gRPC 动态传输层新增服务端流调用：运行时反射或项目级离线描述符均可解析方法。
- 控制面新增短期内存会话、项目归属校验、主动取消、完成/失败状态和一小时终态清理。
- 每个用户在同一项目最多同时运行 5 条流；每条流最多保留 100 条消息、总响应最多 512 KB，单条展示最多 64 KB。达到任一总量上限会自动取消。
- 流量、JSON、Metadata、认证值不进入 API 调试历史或审计详情；审计仅记录安全摘要和计数。
- 新增 REST API：
  - `POST /api/v1/projects/{projectId}/api-debug/grpc/server-stream`
  - `GET /api/v1/projects/{projectId}/api-debug/grpc/streams`
  - `GET /api/v1/projects/{projectId}/api-debug/grpc/streams/{sessionId}`
  - `GET /api/v1/projects/{projectId}/api-debug/grpc/streams/{sessionId}/messages`
  - `POST /api/v1/projects/{projectId}/api-debug/grpc/streams/{sessionId}/cancel`
- 接口调试器现在可从发现结果中选择服务端流方法，显示实时消息、条数/字节占用、终态原因，并支持刷新和取消。

## 安全与边界

- 沿用公开主机:端口限制、内网/回环地址拦截、环境变量解析、ASCII Metadata 限制、认证脱敏和项目级访问控制。
- 服务端流采用逐条请求下一条消息的受限读取；用户取消与达到上限均会向 gRPC 调用发送取消信号。
- 本节点不支持客户端流或双向流；二者将在后续节点交付。

## 验证结果

- `./mvnw -q -pl services/platform-api -Dtest=GrpcDebugFlowTests,GrpcReflectionDebugTransportTests test`：通过。
- `./mvnw -q -pl services/platform-api test`：通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过。
- 浏览器预览：`http://localhost:5173/api-debug` 已确认 gRPC 页面展示服务端流说明、服务端流安全边界及 Metadata 复用说明。

## 人工验收建议

1. 在“接口调试”切换到 gRPC，使用在线反射或导入离线描述符。
2. 发现方法后选择标识为“服务端流”的方法，点击“调用”。
3. 检查“服务端流消息”面板持续刷新消息，点击“取消”后确认状态变为“CANCELLED”。
4. 使用持续输出的测试服务，确认第 101 条消息或总响应超过 512 KB 时状态变为“LIMIT_REACHED”。
