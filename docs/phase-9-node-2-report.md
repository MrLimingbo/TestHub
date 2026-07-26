# 阶段 9 · 节点 2：受控 Appium 驱动、执行控制与截图工件

## 交付内容

- Appium Runner 增加受签名心跳、任务取消消费与控制面运行时清单获取；执行默认保持关闭，只有显式启用且同时配置控制面地址、设备农场地址和精确 Origin 白名单后才会尝试连接设备。
- 使用 W3C WebDriver HTTP 协议实现独立驱动适配：设备端地址不能携带凭据、查询参数或片段；仅允许 localhost HTTP 或白名单内 HTTPS 地址，且仅传递受控的 Appium capability 集合。
- 支持启动、点击、输入、可见/文本断言、等待、截图步骤；超时与取消都在 Runner 边界检查，并在退出时关闭 WebDriver Session。
- 控制面新增执行取消、截止时间扫描和 Runner 取消 Outbox。执行状态新增 `CANCELLING`、`CANCELLED`、`TIMED_OUT`，以避免取消请求被迟到事件覆盖。
- 新增 Runner 内部运行时配置和截图上传接口。内部请求须通过可信运行中 Runner、HMAC、60 秒时钟窗口与一次性 nonce 校验；截图仅接受校验和匹配的 PNG，最大 25 MB。
- 截图工件写入 MinIO，并提供项目权限保护的列表、在线查看和下载接口。报告抽屉增加执行取消入口、取消/超时状态提示及截图预览区域。
- 新增 V46 迁移，持久化取消请求时间和移动端执行工件；保留组织、项目、执行和 Runner 范围，以便审计追溯。

## 验证

- `./mvnw -q -pl services/platform-api -DskipTests compile`：通过。
- `./mvnw -q -pl services/platform-api -Dtest=MobileAutomationFlowTests test`：通过；覆盖截图工件归档与内联查看、取消接口、取消 Outbox、冻结清单和 Runner 事件流。
- `./mvnw -q -pl services/platform-api test`：全套通过。
- `npx pnpm@10.13.1 typecheck && npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过；Vitest 仅输出已有 Monaco sourcemap 缺失警告。
- `npx pnpm@10.13.1 --dir services/appium-runner lint && npx pnpm@10.13.1 --dir services/appium-runner test`：5 项通过，覆盖任务/取消协议、心跳签名、白名单请求边界和截图上传。
- `docker compose config` 与 `git diff --check`：通过。
- 浏览器预览：`/mobile-automation` 路由、隔离执行安全提示、资产空态和“新建场景”声明式编排入口可正常显示。

## 部署边界

本地开发环境没有启动真实设备农场，Runner 因此保持默认禁用，也未向任何真实设备发送命令。部署真实执行环境时，需要单独注入设备农场地址白名单、控制面地址和长度合规的 Runner 心跳密钥，并在运行环境的网络策略中只放行这些目标。

## 下一节点

本节点完成移动端执行控制与受限驱动闭环。验收通过后进入阶段 9 的下一节点：插件包签名、插件权限/生命周期，以及 LDAP/OIDC、企业消息和缺陷系统适配器的可插拔边界。
