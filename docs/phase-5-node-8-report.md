# 阶段 5 · 节点 5.8：Runner 签名心跳与可信状态

## 交付内容

- API Runner 支持使用 `RUNNER_HEARTBEAT_KEY` 对固定心跳字段进行 HMAC-SHA-256 签名。
- 控制面使用 `TESTHUB_RUNNER_HEARTBEAT_KEY` 验证签名，并对每个在线 Runner 返回 `VERIFIED`、`UNVERIFIED` 或 `NOT_CONFIGURED` 状态。
- 接口自动化页面明确区分“协议在线”和“身份已验证”；未验证 Runner 不会被视为后续敏感配置的接收方。
- 保持协议兼容：没有签名字段的旧 Runner 仍可在线执行现有安全预检，但只能显示为未验证。

## 运行配置

生产部署时，应通过密钥管理系统分别向控制面注入 `TESTHUB_RUNNER_HEARTBEAT_KEY`、向 Runner 注入相同值的 `RUNNER_HEARTBEAT_KEY`。密钥至少 32 字符，不写入配置文件、镜像、数据库、Redis 或日志。

## 安全边界

- 心跳签名只覆盖固定元数据，不覆盖任务、变量、请求内容或密钥。
- 未验证或未配置签名的 Runner 绝不获得后续运行时配置交接资格。
- 本节点不启用脚本、网络请求、数据库步骤，也不向 Runner 传递环境密钥。

## 验收建议

1. 访问接口自动化场景页，确认在线 Runner 显示“未配置校验”或“未验证”而非“已验证”。
2. 在控制面与 Runner 运行环境注入相同的心跳密钥并重启二者，确认状态变为“已验证”。
3. 使用错误的 Runner 密钥重启 Runner，确认状态保持在线但变为“未验证”。
