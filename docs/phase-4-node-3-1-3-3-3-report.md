# 阶段 4 · 节点 4.3.1.3.3.3：gRPC 服务与方法发现

**状态：待产品验收**

## 本节点交付

- 新增项目级 `POST /api/v1/projects/{projectId}/api-debug/grpc/discovery`：未提供服务名时返回反射服务列表，提供服务名时返回方法名与调用类型。
- 服务与方法发现复用目标校验、环境变量、环境密钥、ASCII Metadata、Bearer/Basic/API Key 认证与 0.5–10 秒超时边界；同一份 Metadata 会附加到反射调用。
- 发现结果仅在当前响应返回，最多 500 项；不创建调试历史，不保存反射描述符、Metadata 值或认证值。审计仅记录发现范围、数量、状态、TLS、环境、Metadata 数量及认证类型。
- UI 在 gRPC 表单中加入“发现服务”“发现方法”操作与可选下拉列表；流式方法会标注类型并禁用选择，避免误以为当前节点已支持流式调用。
- 兼容稳定 Reflection v1 与仅提供 v1alpha 的服务；本地真实服务测试验证服务、方法发现及受保护 Metadata 的传递。

## 验证

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -q -pl services/platform-api -Dtest=GrpcDebugFlowTests,GrpcReflectionDebugTransportTests test`：通过。覆盖项目级发现、密钥解析、认证 Metadata、真实 Reflection v1alpha 服务的服务/方法发现，以及 Unary 与服务端流类型识别。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -q -pl services/platform-api test`：通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过。Monaco sourcemap 缺失与较大 chunk 警告仍为既有非阻断警告。
- 本地控制面已重启并运行于 `http://localhost:8080`；浏览器验收入口为 `http://localhost:5173/api-debug`。

## 本节点边界

- 发现能力依赖目标服务显式暴露 Reflection；未暴露时系统仅返回受控错误，不会尝试猜测 Proto 定义。
- 当前仅支持发现并执行 Unary；客户端流、服务端流和双向流会显示为不可选择，留待流式调用节点。
- Proto 文件导入、离线描述符缓存与跨会话保存服务目录尚未开始。

## 下一节点

建议下一小节点为 **4.3.1.3.3.4：gRPC Proto 导入与离线描述符**，在保持项目隔离、文件安全扫描和不持久化敏感执行数据的前提下，支持未启用 Reflection 的服务。
