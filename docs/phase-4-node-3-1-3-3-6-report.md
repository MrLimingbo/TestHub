# 阶段 4 · 节点 3.1.3.3.6：gRPC 客户端流基础

## 交付范围

- 动态 gRPC 传输层新增客户端流调用，支持在线反射和项目级离线 Proto/DescriptorSet。
- 控制面新增客户端流短期内存会话：建立、逐条发送 JSON、完成发送（half-close）、取消、状态轮询与最终响应查看。
- 每个用户同一项目最多同时运行 5 条客户端流；每条流最多发送 100 条消息、总请求 512 KB、单条 64 KB，响应上限 512 KB。
- 客户端流可设置 500 ms 至 60 秒的超时；请求方向完成后仍会等待服务端最终响应。
- 流消息、认证值、Metadata 原值和最终响应只存在当前控制面内存会话，不写入 API 调试历史或审计详情。

## REST API

- `POST /api/v1/projects/{projectId}/api-debug/grpc/client-streams`
- `GET /api/v1/projects/{projectId}/api-debug/grpc/client-streams`
- `GET /api/v1/projects/{projectId}/api-debug/grpc/client-streams/{sessionId}`
- `POST /api/v1/projects/{projectId}/api-debug/grpc/client-streams/{sessionId}/messages`
- `POST /api/v1/projects/{projectId}/api-debug/grpc/client-streams/{sessionId}/complete`
- `POST /api/v1/projects/{projectId}/api-debug/grpc/client-streams/{sessionId}/cancel`

## 前端交互

1. 在 gRPC 方法发现结果中选择“客户端流”。
2. 点击顶部“调用”建立会话。
3. 编辑“待提交消息 JSON”，重复点击“提交当前消息”。
4. 点击“完成发送”，等待并查看最终响应；必要时可取消。

## 验证结果

- 客户端流控制面集成测试覆盖建立、提交、半关闭、最终响应和内存隔离。
- 动态传输层集成测试使用离线描述符及本地临时 gRPC 服务，覆盖两条客户端消息、完成发送和最终响应。
- `./mvnw -q -pl services/platform-api test`：通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过。

## 后续范围

- 双向流（并发收发、发送队列、背压可视化）不属于本节点，将在下一节点交付。
