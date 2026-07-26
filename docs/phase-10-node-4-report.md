# 阶段 10 · 节点 4：容量基准、资源告警与恢复验收

## 交付内容

- 新增受控控制面容量基线脚本 `scripts/operations/capacity-baseline.sh`。它只允许请求就绪探针，限制请求数和并发数，输出不含 URL、凭据或响应体的 JSON 证据，并在 p95 或失败率越过阈值时返回非零状态。
- 为 Platform API 增加 Tomcat 线程/连接队列、Hikari 连接池及容量基线的显式配置项，并通过 `.env.production.example` 和生产 Compose 注入。默认值是保守起点，不构成业务容量承诺。
- 新增 `infra/monitoring/prometheus.yml`、`testhub-alerts.yml` 及 Compose `operations` profile 的内部 Prometheus 服务。告警覆盖 API 不可用、5xx 比率、API p95 延迟、JVM 堆压力和 Hikari 池饱和度；不发布主机端口，也不包含任何 Alertmanager 凭据。
- 新增非破坏性 `scripts/operations/fault-recovery-drill.sh`：`plan` 输出隔离恢复演练步骤，`verify` 仅校验已存在备份的完整性和就绪探针。它不会停止服务、恢复数据或传递恢复确认参数。
- 运行与发布接口/页面增加“容量基准与恢复演练”区块，显示默认阈值、受控基线、资源告警与待隔离演练项；页面不返回内部端点、密钥或堆栈。
- 新增[容量与韧性运行手册](capacity-and-resilience.md)，并更新[生产部署基线](production-deployment.md)。

## 本地验收

- `2026-07-24` 在本地 Platform API 就绪探针执行了 24 次、并发 4 的受控基线：p50 5 ms、p95 76 ms、最大 76 ms、失败 0/24；阈值为 p95 500 ms 与失败率 1%，结果通过。证据输出在本机临时目录 `/tmp/testhub-node4-capacity-baseline.json`，未写入仓库。
- Prometheus `promtool check rules` 验证通过，确认 5 条规则可解析。
- 故障恢复计划和“缺失备份必须拒绝”的路径已验证。实际恢复会覆盖 MySQL/MinIO，未在本地开发数据上执行；完整恢复验收仍必须在经批准的隔离环境使用真实备份完成。
- 浏览器预览通过：`/operations` 显示容量控制 2/3、默认基线、告警规则说明和隔离恢复演练待办；页面继续隐藏内部地址与密钥。

## 验证记录

- `./mvnw -q -pl services/platform-api test` 通过，包含运行与发布接口的容量控制投影断言。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build` 通过。Vitest 运行时仍会输出 Monaco 上游缺失 source map 的既有警告，未影响通过结果。
- `bash -n scripts/operations/*.sh infra/backup/*.sh`、`docker compose --env-file .env.production.example --profile operations -f compose.production.yaml config --quiet`、`docker run --rm --entrypoint promtool -v "$PWD/infra/monitoring:/etc/prometheus:ro" prom/prometheus:v3.5.0 check rules /etc/prometheus/testhub-alerts.yml` 和 `git diff --check` 均通过。
- `scripts/operations/capacity-baseline.sh --requests 24 --concurrency 4 --p95-ms 500 --max-error-percent 1 --output /tmp/testhub-node4-capacity-baseline.json` 通过。

## 后续节点

下一节点将交付 Kubernetes Helm Chart、升级与回滚流程，然后进入最终生产发布演练与阶段 10 收尾。
