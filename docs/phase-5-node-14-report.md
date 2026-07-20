# 阶段 5 · 节点 5.14：受限 HTTP API Runner 执行闭环

## 交付内容

- 增加可信 Runner 专用的短时 HTTP 运行时工件领取接口，签名绑定任务、执行和租约指纹。
- 工件从提交版本的不可变场景修订与已冻结运行时变量快照即时生成，后续编辑场景或参数不会影响已提交任务。
- 独立 Node Runner 可在显式双端开关与 Origin 白名单都配置后，领取工件、执行顺序 HTTP 请求并回传固定的接收、运行、成功或失败状态。
- 页面可区分“可信但执行关闭”与“受限 HTTP Runner 已启用”。

## 安全边界

- 默认关闭：控制面和 Runner 任一端未显式启用时，仍为安全预检模式。
- 仅支持 `REQUEST` 的 method / URL / body / contentType；不支持环境密钥、脚本、Cookie、请求头、重定向、数据库、断言或流程控制。
- Runner 只允许精确 Origin 白名单，并阻止私有网络地址；校验后的 DNS 地址固定用于连接，请求/响应内容、变量值和租约不写入日志或 Redis Stream。
- 控制面进程不会执行任何用户脚本。

## 启用方式

生产部署需要同时配置：

```text
TESTHUB_API_RUNNER_HTTP_EXECUTION_ENABLED=true
TESTHUB_RUNNER_HEARTBEAT_KEY=<至少 32 字符的独立密钥>
RUNNER_HEARTBEAT_KEY=<同一密钥>
TESTHUB_CONTROL_PLANE_URL=https://platform.example.com
RUNNER_HTTP_EXECUTION_ENABLED=true
RUNNER_ALLOWED_ORIGINS=https://api.example.com
```

不要把上述密钥写入仓库或前端配置。当前 Docker Compose 开发模板保持关闭，防止默认环境产生外部网络请求。
