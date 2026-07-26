# 容量与韧性运行手册

本手册定义阶段 10 节点 4 的控制面容量基线、资源告警和恢复演练证据。它不将就绪探针基线误表述为业务压力、Runner 容量或灾难恢复能力。

## 容量基线

`scripts/operations/capacity-baseline.sh` 只允许请求 `.../actuator/health/readiness`，不接受查询参数、用户名密码、请求体或业务接口地址。默认基线为 60 次请求、并发 6、p95 小于等于 500 ms、失败率小于等于 1%。它在阈值不满足时以非零状态退出，并产生不含 URL、密钥或响应体的 JSON 证据。

在部署后的受控网络中执行：

```bash
scripts/operations/capacity-baseline.sh \
  --url https://testhub.internal/actuator/health/readiness \
  --requests 60 --concurrency 6 --p95-ms 500 --max-error-percent 1 \
  --output /secure/evidence/testhub-capacity-baseline.json
```

变更线程数、数据库连接池、容器内存或副本数前后均应执行一次，并在变更记录中归档 JSON。生产默认值由 `TESTHUB_SERVER_*`、`TESTHUB_DB_*` 和 `TESTHUB_CAPACITY_BASELINE_*` 环境变量声明；这些值是保守起点，不是容量承诺。业务接口、导入导出、性能 Runner 和多租户峰值必须另建带授权目标的容量方案。

## 指标与告警

Docker Compose 的 `operations` profile 提供内部网络中的 Prometheus：

```bash
docker compose --env-file .env.production --profile operations -f compose.production.yaml up -d prometheus
```

它不会发布主机端口，也不包含 Alertmanager 接收器或通知凭据。由部署方在受限网络中配置 Alertmanager 路由，并把凭据放在密钥管理系统。规则位于 `infra/monitoring/testhub-alerts.yml`：

| 告警 | 阈值 | 持续时间 | 初步处置 |
| --- | --- | --- | --- |
| API 不可用 | `up == 0` | 2 分钟 | 查看保护日志、依赖健康和就绪探针 |
| 5xx 比例 | 大于 2% | 5 分钟 | 用请求 ID 关联日志；不要自动重试破坏性操作 |
| API p95 延迟 | 大于 750 ms | 5 分钟 | 对照已归档基线，再调整线程或池 |
| JVM 堆压力 | 大于 85% | 10 分钟 | 检查分配与 GC；不要直接放大内存 |
| Hikari 连接池 | 大于 80% | 5 分钟 | 检查慢查询和 MySQL 容量 |

上线前验证规则语法：

```bash
docker run --rm -v "$PWD/infra/monitoring:/etc/prometheus:ro" \
  prom/prometheus:v3.5.0 promtool check rules /etc/prometheus/testhub-alerts.yml
```

## 故障恢复演练

恢复脚本可以替换 MySQL 和 MinIO 数据，因此生产恢复是经批准的破坏性操作。本节点不会自动停止任何服务、执行恢复或提供恢复确认参数。

先执行非破坏性计划：

```bash
scripts/operations/fault-recovery-drill.sh plan
```

对已经生成的备份执行前置验收（检查 SHA-256 清单并读取就绪探针）：

```bash
scripts/operations/fault-recovery-drill.sh verify \
  --backup /secure/backups/testhub-YYYYMMDDTHHMMSSZ \
  --url https://testhub.internal/actuator/health/readiness
```

完整演练必须在隔离的 Compose 项目或临时 Kubernetes 命名空间完成，并至少记录：

1. 备份 ID、Flyway 版本、恢复负责人和开始/结束时间。
2. API 停止后使用 `infra/backup/testhub-restore.sh restore` 的明确确认操作，以及 MySQL/MinIO 恢复结果。
3. API 启动后的就绪状态、管理员登录、接口资产读取、功能测试记录读取及附件读取。
4. Redis 会话与任务认领不会重放；Runner 离线、在途执行和通知重试按照各模块的恢复语义验证。
5. 恢复时长、数据差异、遗留风险和证据归档位置。

生产环境在一次成功的隔离演练后仍应按季度和重大版本升级前复演。任何实际恢复均需变更审批、维护窗口和独立回滚计划。
