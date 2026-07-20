# 阶段 4 · 节点 4.3.1.3.3.4：gRPC Proto 导入与离线描述符

状态：已完成开发与验证，等待产品验收。

## 交付内容

- 新增项目级 `grpc_descriptor` 资产与 Flyway V8 迁移。仅持久化经过校验的 `FileDescriptorSet`、摘要和计数；不保存原始请求、响应、Metadata 或认证值。
- 新增 `.proto` 多文件导入（最多 16 个、总计 512 KB），支持同批次平铺文件的 `import`、消息、枚举、oneof、map、服务与 Unary/流式方法描述。Proto2、`extend` 和复杂语言扩展应改用预编译的 FileDescriptorSet。
- 支持导入一个 protoc 生成的 `.desc` / `.pb` / `.bin` FileDescriptorSet；服务端在写入前构建并校验全部依赖。
- 新增离线服务/方法发现接口；选择离线描述符后，不会连接目标服务或调用远端反射接口。
- gRPC Unary 请求新增可选 `descriptorId`。选中离线描述符时，动态调用直接使用该描述符，不再依赖目标服务启用 Reflection。
- 接口调试页新增描述符来源卡片、上传入口、在线/离线状态、离线服务/方法选择与删除操作。

## API

- `GET /api/v1/projects/{projectId}/api-debug/grpc/descriptors`
- `POST /api/v1/projects/{projectId}/api-debug/grpc/descriptors`（multipart：`name`、`files`）
- `GET /api/v1/projects/{projectId}/api-debug/grpc/descriptors/{descriptorId}/discovery?service=`
- `DELETE /api/v1/projects/{projectId}/api-debug/grpc/descriptors/{descriptorId}`
- `POST /api/v1/projects/{projectId}/api-debug/grpc/unary` 新增可选 `descriptorId`

## 安全与边界

- 描述符始终受组织与项目范围及既有写入权限约束；导入、发现和删除写入审计摘要。
- `.proto` 在 Spring Boot JVM 内以受限声明式解析器处理，不启动 `protoc` 或执行任何用户代码。
- 无论在线还是离线调用，目标地址、TLS、超时、Metadata、认证、变量解析和 SSRF 防护沿用现有 gRPC 调试边界。
- 离线描述符上限为 1 MB，发现结果上限为 500 条；流式方法会展示但当前仍不可调用。

## 验证记录

- `./mvnw -q -pl services/platform-api -Dtest=GrpcDebugFlowTests,GrpcReflectionDebugTransportTests test`
- `./mvnw -q -pl services/platform-api test`
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`
- 浏览器验收：`/api-debug` 的 gRPC 模式已确认显示离线描述符来源、文件选择与导入、在线/离线状态、服务/方法发现和安全边界说明。

## 下一节点

建议进入 `4.3.1.3.3.5：gRPC 流式调用基础`，先实现服务端流的受限读取、取消、条数/字节上限与实时日志，再逐步扩展客户端流和双向流。
