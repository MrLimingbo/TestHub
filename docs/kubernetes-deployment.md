# Kubernetes Helm 部署与回滚手册

`infra/helm/testhub` 部署无状态的 TestHub Web 与 Platform API。MySQL、Redis、MinIO、备份存储、Runner 和 Alertmanager 均由集群或企业基础设施单独管理；Chart 不创建数据库卷、不写入凭据，也不会暴露 API Service 到集群外。

## 部署前提

- Kubernetes 1.28 或更高版本、Helm 3、TLS Ingress Controller；启用 HPA 时还需要 Metrics Server，启用 `ServiceMonitor` 时还需要 Prometheus Operator CRD。
- 预先可访问的 MySQL 8.4、Redis 和兼容 S3 的对象存储；数据库必须由备份/恢复流程覆盖。
- Platform API 与 Web 镜像必须先通过发布供应链门禁，使用不可变 tag 或 digest；禁止 `latest`。
- 从 `infra/helm/testhub/values.production.example.yaml` 复制出只在部署侧保存的 values 文件。不要把它与 Secret 一起提交到代码库。

## 运行时 Secret

Chart 使用 `api.existingSecret` 引用已经存在的 Secret，并通过 `envFrom` 注入。部署方使用受限权限的 env 文件或密钥管理器创建它，例如：

```bash
kubectl -n testhub create secret generic testhub-runtime \
  --from-env-file=/secure/testhub/runtime.env
```

`runtime.env` 至少应包含以下键，名称必须保持不变：

```text
TESTHUB_DB_PASSWORD=
TESTHUB_REDIS_PASSWORD=
TESTHUB_MINIO_ACCESS_KEY=
TESTHUB_MINIO_SECRET_KEY=
TESTHUB_ENCRYPTION_KEY=
TESTHUB_ENCRYPTION_KEY_ID=
TESTHUB_PERFORMANCE_RUNNER_HEARTBEAT_KEY=
TESTHUB_UI_AUTOMATION_RUNNER_HEARTBEAT_KEY=
TESTHUB_MOBILE_AUTOMATION_RUNNER_HEARTBEAT_KEY=
```

轮换窗口才额外加入 `TESTHUB_PREVIOUS_ENCRYPTION_KEYS` 和 `TESTHUB_SECRET_REKEY_ENABLED`。Secret 值、内部地址、数据库导出和实际备份都不得写入 Helm values、CI 日志或运行与发布页面。

## 校验与计划

无集群、无 Helm 本机安装时可用容器化验证：

```bash
scripts/deployment/validate-helm-chart.sh
```

拥有部署集群凭据的操作员先创建只含非密钥配置的 values 文件，然后只做客户端渲染计划：

```bash
scripts/deployment/helm-release.sh plan \
  --release testhub --namespace testhub \
  --values /secure/testhub/values.production.yaml
```

计划应人工核对：镜像不是 `latest`、Web Ingress 使用 TLS、API 是 ClusterIP、`existingSecret` 存在、资源请求/限制与容量基线一致。若要启用 NetworkPolicy，必须同时提供 Prometheus 抓取器选择器并验证 API 到 MySQL/Redis/MinIO 的 egress。

## 升级

升级前完成备份完整性校验、容量基线和版本兼容评审。经审批后才执行：

```bash
scripts/deployment/helm-release.sh apply \
  --release testhub --namespace testhub \
  --values /secure/testhub/values.production.yaml \
  --timeout 15m --approve APPLY_TESTHUB_HELM_RELEASE
```

脚本采用 `helm upgrade --install --atomic --wait`。若新 Web/API 未通过就绪探针，Helm 会回滚本次工作负载变更。升级成功后应执行：

```bash
kubectl -n testhub rollout status deployment/testhub-api --timeout=5m
kubectl -n testhub rollout status deployment/testhub-web --timeout=5m
scripts/operations/capacity-baseline.sh --url https://testhub.example.com/actuator/health/readiness
```

最后一条基线命令应改为 API 内部受控地址；公开 Web Ingress 会拒绝 `/actuator`，不能将该内部健康端点暴露到互联网。

## 回滚边界

先查看历史并选择经兼容性评审的 revision：

```bash
helm history testhub --namespace testhub
scripts/deployment/helm-release.sh rollback \
  --release testhub --namespace testhub --revision 4 \
  --timeout 15m --approve ROLLBACK_TESTHUB_HELM_RELEASE
```

该回滚只恢复 Kubernetes 工作负载清单和镜像，不会撤销 Flyway 数据库迁移、恢复 MySQL/MinIO 数据、重放 Redis 会话或复活在途 Runner 任务。只有当旧应用版本与当前 schema 兼容，且升级迁移均为向后兼容的扩展变更时，才允许直接工作负载回滚。任何需要数据回退的故障必须转入[备份与恢复运行手册](backup-recovery.md)中的隔离恢复流程，并使用经审批的维护窗口。
