# 接口模块：gRPC 描述符显式迁移

状态：源码联调及内部 Mac arm64 包本项复验通过。接口测试模块整体尚未验收，不进入下一模块。

## 本轮实现

新增 `GrpcDescriptorTransferService` 应用服务，扩展独立接口迁移协议 V2。用户明确勾选“同时复制全部 gRPC 描述符”后，复制项目中的编译后 FileDescriptorSet，并按新描述符 ID 重写用例请求快照的 descriptorId 引用。目标记录归当前账号；存储格式标记为 DESCRIPTOR_SET，创建时间是本次导入时间，不冒充原创建人或时间。原始 .proto 文件不重新编译或执行脚本。

默认不勾选。未选但用例有描述符引用时阻断；选中后仍要求引用完整、二进制及 SHA-256 一致、protobuf 可解析、依赖完整且有可发现的服务。单次最多 20 项，单个 1 MiB，总计 4 MiB，仍受整个快照的大小限制。目标同名冲突整批回滚，不覆盖旧描述符。

V1 默认导出和 capabilities.version=1 保持不变，capabilities.versions 增加 2；旧客户端仍可进行 V1 迁移。桌面按选择协商版本，预览显示描述符数量，确认框列明上传范围，失败重试沿用既有幂等协议。

## 验证

- 隔离 H2 后端全量 **280/280**、JAR 打包成功：`.testhub/api-module-20260928/grpc-backend-all.log`。
- 隔离 MySQL 偏好及迁移专项 **27/27**：`mysql-tests.log`。新建独立 MySQL/Redis 容器已停止，保留测试数据；未连接原业务数据库。
- 桌面单元测试 **95/95**，lint 通过：`grpc-desktop-all.log`、`grpc-lint.log`。
- 真实源码 Electron **10/10**：`api-transfer-grpc.log`、`api-transfer-grpc-result.json`。包括默认范围阻断、明确选择、预览无写入、取消、文件与描述符同时迁移、用例引用重映射、服务与方法发现、独立网页账号读取和返回本地原件一致。
- 迁移专项覆盖摘要不匹配、合法摘要但非法 protobuf、缺失引用、目标同名冲突回滚、服务/方法发现、幂等重试；旧 V1 专项继续通过。

- 内部 Mac arm64 目录包构建成功，正式发行门槛仍关闭，未签名、未公证、未安装或发布。提取包内 ASAR 使用开发 Electron 与全新用户目录加载，并使用包内 Java/后端/前端运行组件，真实迁移 **10/10** 通过：`api-transfer-grpc-packaged.log`、`api-transfer-grpc-packaged-result.json`。5 个关键桌面文件与源码 SHA-256 一致：`grpc-package-hashes.json`。这不等价于正式安装与 Gatekeeper 验收。

## 尚未完成

调试历史/可回放草稿迁移、账号/项目切换竞态的完整 GUI 验证、既有协议执行与交换的完整桌面回归，以及模块整体安装包复验仍需推进。此次仅验证描述符发现与引用，没有将其表述为迁移后的所有 gRPC 调用均已实际执行。Windows 尚无本轮实机验收结果。仍是用户确认的一次性迁移，不是双向自动同步。
