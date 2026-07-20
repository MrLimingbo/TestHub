# 阶段 5 · 节点 27：受限 HTTP 请求重试

## 已交付

- `REQUEST` 步骤新增 `maxAttempts`，默认 1 次；仅 `GET`、`HEAD`、`OPTIONS` 可配置至 3 次。
- Runner 只对网络失败、`408`、`429`、`5xx` 执行固定 250ms 的有界重试，并在任务取消或临近截止时间时停止。
- HTTP 结果升级到独立 Redis Stream v2：同一逻辑请求保留 `requestSequence`，每次尝试以 1–3 的 `attemptNumber` 审计。
- MySQL 迁移 V28 将唯一约束升级为 `(execution_id, request_sequence, attempt_number)`；浏览器、REST API 和结构化报告均展示尝试级脱敏结果。
- 每次重试仅追加固定 `HTTP_RETRY_SCHEDULED` 日志，绝不写入 URL、请求正文、响应、变量、密钥或异常详情。

## 安全边界

- 非幂等方法不会重试；切换为 `POST`、`PUT`、`PATCH`、`DELETE` 会在页面自动还原为 1 次尝试，控制面和 Runner 也会独立拒绝越权配置。
- 任务、脚本和环境密钥不进入结果流；结果协议的字段变更通过 HTTP 结果 Stream v2 和协议版本 2 隔离。
- 结构化报告升级为 v3；HTTP 计数表示已持久化的尝试记录。

## 验证

- API Runner 单元测试覆盖 `503 → 204` 的 GET 重试，验证固定请求序号、递增尝试号、固定日志和无 URL 泄露。
- 控制面集成测试覆盖同一请求序号的两次尝试持久化、API 返回顺序、数据库唯一约束和额外不可信字段拒绝。
- 前端测试、类型检查、构建与浏览器预览在节点验收前执行。
