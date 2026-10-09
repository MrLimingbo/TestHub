# 接口测试模块：本地工作区收尾验收

状态：接口测试模块的源码与内部 Mac arm64 包已完成隔离回归，等待用户审核本模块。本报告不代表整个安装即用改造或正式发行完成。

## 本轮修复

- 迁移预览后切换账号再次预览，原生页面会显示当前预览账号。真实桌面测试确认旧账号预览不能提交，服务端写入前失败可重试，写入成功但响应丢失时使用相同请求键取回回执，不会重复导入目录、接口或版本。
- 桌面 GraphQL、SOAP 继续按原请求发送，同时在加密调试历史中保留真实协议与回放字段。主进程从实际发送正文生成回放字段并验证格式，拒绝不支持的协议与无效正文；网页历史不再把它们误记为 HTTP。
- 本地工作区的 WebSocket 和 gRPC 现在使用已有“本地执行设置”的精确目标授权。WS/WSS 分别对应 HTTP/HTTPS 地址；gRPC 是否启用 TLS 决定对应 HTTP/HTTPS。主机与端口须匹配，链路本地、组播和保留地址仍禁止，DNS 名称不能借授权访问私网。在线服务的私网访问限制不变。
- 成功建立的 WebSocket 历史没有 HTTP 状态码，页面现显示绿色“已连接”，不再误报“失败”。流会话仍只保存在当前进程内存中。

## 已验证

证据日志及结果 JSON 位于 `.testhub/api-module-20260928/`。

- 全新隔离 H2 的后端全套 **287/287** 与 JAR 构建通过：`protocol-backend-all.log`。既有隔离 MySQL 偏好与迁移专项 **32/32** 见此前 `mysql-tests.log`；新授权代码不修改数据库结构。
- 前端 **126/126**、桌面 **98/98**、lint 和生产前端构建通过：`protocol-front-ui.log`、`protocol-desktop-final.log`、`protocol-lint-ui.log`、`protocol-build-ui.log`。
- 源码真实 Electron 迁移身份与重试 **7/7**：`native-retry-result.json`。项目 A/B 延迟读取、迟到写入、真实 409、断网重试、换账号隔离另有前期源码和包内各 **5/5**。
- 源码真实 Electron HTTP、GraphQL、SOAP 请求与历史 **3/3**，OpenAPI/Postman/HAR 跨独立项目导出再导入 **3/3**，无效文档不写入且刷新后目录仍可见，共 **8/8**：`protocol-interchange-result.json`。
- 源码真实 Electron WebSocket 回显与关闭、gRPC 反射发现及 Unary/服务端流/客户端流/双向流，历史与成功 WebSocket 标签，共 **8/8**：`stream-protocols-result.json`。测试目标在本机新建、只对本次干净工作区授权精确地址。
- 最终内部 Mac arm64 包用包内 Java、后端、前端和提取的桌面源码，在全新用户目录重复协议与数据交换 **8/8**、WebSocket/gRPC 与页面状态 **8/8**：`protocol-interchange-api-final.log`、`stream-protocols-api-final.log`。最终截图 `output/playwright/api-module-20260928/stream-protocols-packaged.png` 可见成功 WebSocket 历史显示“已连接”。
- 同一最终内部包的原生接口迁移与可选历史 **13/13**：`api-transfer-history-api-final.log`。浏览器账号端看到相同资产，迁入历史可载入回放而不会自动执行，本地原件仍在。
- 同一最终内部包的目录偏好项目切换、迟到响应、真实 409、失败重试、退出后换账号隔离 **5/5**：`preferences-races-api-final.log`。最终包合计 **34/34** 项真实桌面流程通过；测试均使用全新工作区和独立账号服务。
- 先前独立账号的数据迁移 V1–V3 已通过目录、全部版本、用例、可选文件、gRPC 描述符及可选调试历史/回放草稿的源码与内部包复验；详情见 [接口审计](desktop-standalone-progress-2026-09-28-api-audit.md)、[gRPC 迁移](desktop-standalone-progress-2026-09-29-api-grpc.md)、[历史迁移](desktop-standalone-progress-2026-09-29-api-history.md)。

## 交付边界

内部 Mac arm64 目录包 `package-api-final/mac-arm64/TestHub.app` 属于开发验收，其 `distributionReady=false`；仍未签名、公证、安装或发布。源码与提取 ASAR 的包内代码及运行组件 **12 项 SHA-256 一致**，见 `api-final-package-hashes.json`；真实桌面复验使用开发 Electron、全新用户目录及包内 Java/后端/前端。此方式不证明 Gatekeeper 或正式安装器首启。Windows、Mac x64 和正式分发仍需按总计划验证。

本地资料到账号是用户明确选择的一次性迁移；本轮不实现离线自动双向同步。接口自动化和其他模块仍需单独实施及验收。
