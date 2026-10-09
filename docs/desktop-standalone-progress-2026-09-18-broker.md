# 本地持久任务队列与 Runner 通信验证

日期：2026-09-18。基线仍为 `5d93330`，本轮改动未提交。前一阶段见 [会话与安全状态适配](desktop-standalone-progress-2026-09-18.md)。

## 已完成的边界

- 新增 `RuntimeStreams`、`RunnerRegistry`；现有任务发布、事件/结果消费、四类 Runner 状态服务使用同一边界。企业模式由 Redis 实现，原流名称、消息字段、签名验证、任务状态机保持不变。
- `desktop` 模式使用 `DesktopRuntimeBroker` 和 Flyway V3，持久保存消息、消费组游标、pending、所属消费者、投递时间以及心跳 TTL。V1/V2 和原 MySQL 迁移未修改；新增测试验证 V2 会话、安全 nonce 在 V3 升级后保留。
- 内嵌数据库由一个 JVM 持有；队列变更在独立短连接事务中提交，通过单例同步锁串行化写入，避免外层业务连接池耗尽，也不随业务回滚撤销已经接受的消息。
- 新本地消费组从现存消息开始，避免 Runner 晚于入队启动时丢任务。已确认记录不会再次读取；未确认记录可在重启后恢复。后台消费者首次恢复 pending，后续失败记录等待 30 秒重试。Runner 恢复自己的 pending；遗留任务认领不会夺取仍有有效心跳的消费者，旧拥有者不能替新拥有者确认消息。
- 普通流保留至少 50,000 条诊断尾部及 60 秒数据，永远保留 pending 和任意消费组尚未读取的消息；尚无消费组的任务不裁剪。无消费组的 dead-letter 流保留至少 1,000 条诊断尾部。清理有专项测试。
- 新增仅 `desktop` 注册的 `/internal/desktop-broker/v1/command`。限制回环来源、回环 Host、无 Origin 的原生请求；每个 engine/runnerId 使用独立派生 HMAC 凭据。凭据不能跨引擎或身份使用，Runner 只能读取本引擎任务/取消流、发布本引擎结果流，不能向任务流注入任务，不能执行任意 Redis 命令。缺失根密钥时拒绝访问。
- 四类 Node Runner 使用共享 `services/runner-transport/client.mjs`；没有本地 broker 配置时调用原 Redis factory。本地 HTTP 读取响应丢失后恢复 pending，并过滤本进程已交付的在途记录；退出中止长轮询；不携带本地凭据跟随重定向。四个 Dockerfile 已包含共享模块。
- desktop 已无运行时 Redis Bean 依赖，排除 Redis 自动配置，健康检查改为真正查询本地 broker。企业 Redis 健康检查保持。真实后端在 Redis/MySQL/MinIO 全部指向不可达端口时，聚合健康接口为 HTTP 200。

消息语义采用 pending/ACK 模型；对应 Redis 原始语义可参照官方 [XREADGROUP](https://redis.io/docs/latest/commands/xreadgroup/) 与 [XACK](https://redis.io/docs/latest/commands/xack/)。这是本地单拥有者适配，不承诺多后端实例共同访问同一 H2 文件，也不承诺任意业务副作用恰好执行一次。

## 已执行验证

证据根目录 `.testhub/broker-20260918/`。以下均为实际执行结果，专项与全量包含重叠用例，不应相加。

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| H2 desktop 后端全量，Redis/MinIO 不可达 | 165/165，0 失败、0 跳过 | `full-h2.log` |
| 原 MySQL/Redis 后端全量，独立 QA 实例 | 165/165，0 失败、0 跳过 | `full-mysql-final.log` |
| 最终 broker/鉴权/迁移专项，含随后新增 2 项 | 12/12；未因仅新增专项再次重复整套全量 | `broker-retention-final.log` |
| 新 Node 传输测试 | 6/6 | `node-client-final.log` |
| 既有四类 Runner 测试 | 首轮 79 通过、1 个真实 JMeter 测试因未指定路径跳过；随后指定已有 JMeter，单独通过该项 | `runner-tests.log`、`jmeter-runtime.log` |
| 受影响 JavaScript ESLint | 通过 | `lint-final.log` |
| 最终后端 JAR 打包 | 通过 | `package-final.log` |
| V1 基线生成校验及 diff 空白检查 | 通过，61 个历史 DDL 来源一致 | 终端输出 |

### 真实进程闭环

1. **API Runner**：随机密钥、新数据库，注册并创建任务；Runner 首次启动前入队，再 SIGKILL 后端并重启，任务仍执行到 SUCCEEDED，真实 GET `https://example.com`；运行中取消到 CANCELLED。SIGKILL Runner 后心跳到期隐藏，重启恢复 pending，已领取租约的任务到 FAILED，防止重复执行；后续新任务成功，正常退出清除心跳。见 `runner-http.mjs`、`runner-http.log`、`runner-http-result.json`。
2. **JMeter**：使用此前已下载的 5.6.3，单用户/5 TPS/10 秒，仅压测临时回环 HTTP 服务，50 次成功采样、0 错误；指标与进程资源样本通过真实队列落盘。JTL/LOG 归档并通过应用下载接口校验长度及 SHA-256；另一个运行中任务取消到 CANCELLED。见 `jmeter-http.mjs`、`jmeter-http-final.log`、`jmeter-http-result.json`。
3. **Playwright**：真实 Chromium 打开临时本地测试页，3/3 步骤通过（导航、可见断言、截图）；VIDEO/SCREENSHOT/TRACE 经文件存储归档，通过应用下载接口逐个核对长度和 SHA-256。见 `playwright-http.mjs`、`playwright-http-result.json`。
4. **Appium**：真实 Node Runner 连接本地 broker，验证可信签名心跳及退出移除；没有将设备执行启用，没有真机/模拟器执行证据。记录在 `appium-heartbeat.log` 及上述 Playwright 结果 JSON。

测试修正记录：首轮 MySQL 回归使用全局 `TESTHUB_STORAGE_TYPE=filesystem`，污染“默认 MinIO”配置测试；改用仅由 Spring Boot 加载的 `SPRING_APPLICATION_JSON` 隔离测试配置后全量通过，没有修改该测试断言。首个 API 进程验收夹具只有 WAIT、没有 REQUEST，被既有运行时协议正确拒绝；补全真实 HTTP 请求步骤后通过，没有放宽产品校验。失败证据保留为 `full-mysql.log` 和 `initial-fixture-runner-http*.log`。

## 本地通信配置（供后续桌面进程管理集成）

后端必须由桌面启动器设置 `TESTHUB_DESKTOP_BROKER_KEY`（随机、至少 32 字符），并沿用已有专用数据目录、数据库口令、数据加密密钥。Node 设置：

- `TESTHUB_DESKTOP_BROKER_URL`：本次后端进程的回环 HTTP origin，只允许字面 `127.0.0.1` 或 `::1`，不能含路径/账号/查询参数。
- `TESTHUB_DESKTOP_BROKER_TOKEN`：`HMAC-SHA256(rootKey, "testhub.desktop-broker.v1:" + engine + ":" + runnerId)` 的小写 hex。
- 原有 Runner 心跳签名密钥、控制面地址、执行开关、目标白名单继续有效。根 broker 密钥只给后端；Node 只取得自身派生凭据。

这些配置目前用于进程级验收，尚未连接 Electron 启动生命周期，不是要求最终用户手工设置的产品流程。

## 剩余交付门槛

尚未完成：桌面默认访客入口、后端/Runner 自动启动和故障恢复、内置 Java/Node/前端资源、按需引擎管理、本地备份恢复、账号离线数据迁移与冲突处理、Mac/Windows 干净系统安装验证。在线同账号共享数据不能替代离线数据同步证明。Appium 仍需实际设备/模拟器验收。

当前安装的 TestHub、旧安装包、用户开发服务与数据库未替换。此次真实闭环依然由验收脚本管理进程，因此不能称为已经交付“安装即用”的新版 App。
