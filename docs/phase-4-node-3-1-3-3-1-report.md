# 阶段 4 · 节点 4.3.1.3.3.1：gRPC Unary 基础调试

**状态：待产品验收**

## 本节点交付

- 在接口调试器新增 gRPC 协议入口，支持目标 `主机:端口`、明文/TLS、服务、方法、JSON 请求体和 0.5–10 秒超时。
- 新增项目级 `POST /api/v1/projects/{projectId}/api-debug/grpc/unary`，通过服务端反射获取描述符，将 JSON 转换为动态 Protobuf 消息后执行 Unary 调用。
- 反射先尝试稳定版 v1；仅在服务未实现 v1 时回退 v1alpha，以兼容仍使用旧反射协议的服务。
- 支持环境变量、环境密钥和动态值的仅执行期解析；请求 JSON、响应 JSON、反射描述符及密钥均不写入历史或审计。
- 调试历史仅保存 `grpc://` / `grpcs://主机:端口/服务/方法` 脱敏摘要、gRPC 状态码、时长与结果状态；审计仅保存服务、方法、TLS、环境和状态。
- 限制目标为公网可解析地址，阻断内网、回环、链路本地、组播和保留地址；响应上限为 512 KB。
- UI 提供受控的成功/失败响应状态；未启用反射、服务或方法不存在、超时、参数不匹配、未认证和仅支持 Unary 等情况均显示不泄露内部细节的提示。

## 验证

- `./mvnw -q -pl services/platform-api -Dtest=GrpcReflectionDebugTransportTests test`：通过。测试启动仅在测试进程内运行的 gRPC Health 服务及 Reflection v1alpha，真实验证“反射描述符 → JSON → 动态 Protobuf → Unary 响应”链路。
- `./mvnw -q -pl services/platform-api test`：通过。
- 已在浏览器打开 `http://localhost:5173/api-debug`，切换 gRPC，验证表单、TLS、超时和请求发送。公开 `grpcb.in:9000` 当前返回 `NOT_FOUND`，界面正确展示“目标服务未启用反射或服务、方法不存在”；该外部服务状态不影响本地真实反射集成测试。
- 本地控制面已重启并运行在 `http://localhost:8080`。

## 本节点边界

- 仅支持 Unary 调用。
- 当前按用户填写的服务名查询反射描述符；不提供服务/方法枚举界面。
- Metadata、认证、Proto 文件导入和流式调用留待后续节点。

## 下一节点

建议下一小节点为 **4.3.1.3.3.2：gRPC Metadata 与认证**，补齐受控的自定义 Metadata、Bearer/Basic/API Key 认证与脱敏存储边界。
