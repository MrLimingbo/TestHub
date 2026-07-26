# 生产部署基线

本模板交付最小的私有化控制面部署：Web、Platform API、MySQL 8.4、Redis 和 MinIO 都位于 Docker 私有网络，只有 Web 容器发布 HTTP 端口。TLS 必须由前置负载均衡器或 Ingress 终止；不要直接将此 HTTP 端口暴露到互联网。

## 构建镜像

```bash
docker build -f services/platform-api/Dockerfile -t registry.example.com/testhub/platform-api:0.1.0 .
docker build -f Dockerfile -t registry.example.com/testhub/web:0.1.0 .
```

构建镜像前应在 CI 中运行完整测试、依赖扫描和镜像扫描。镜像标签必须是不可变版本或摘要，不能使用 `latest`。

## 准备运行配置

1. 复制 `.env.production.example` 到部署环境的密钥管理系统或受限权限的 `.env.production`。
2. 填写唯一的 MySQL、Redis、MinIO 和 AES-GCM 密钥；生产环境不得使用仓库中的开发默认值。
3. 将 `TESTHUB_CORS_ALLOWED_ORIGINS` 设置为公开 HTTPS 域名，不要包含通配符、HTTP 或开发端口。
4. 在反向代理/Ingress 配置 TLS，并将真实来源协议通过 `X-Forwarded-Proto` 传递给 Web/API。

部署前先做无副作用配置检查：

```bash
set -a
. ./.env.production
set +a
docker compose --env-file .env.production -f compose.production.yaml config --quiet
```

然后启动：

```bash
docker compose --env-file .env.production -f compose.production.yaml up -d
docker compose --env-file .env.production -f compose.production.yaml ps
```

## 探针与指标

- `GET /actuator/health/liveness` 与 `GET /actuator/health/readiness`：详情永不公开，可由容器和 Kubernetes 探针使用。
- `GET /actuator/prometheus`：仅在 API 内部网络暴露，Web Nginx 明确拒绝该路径；Prometheus 应部署在相同私有网络或 Kubernetes Cluster 内。
- “运行与发布”页面只显示 MySQL、Redis、MinIO 的摘要状态和安全控制，不显示内部地址、凭据或异常堆栈。
- 容量基线、资源告警阈值和隔离恢复演练流程见[容量与韧性运行手册](capacity-and-resilience.md)。Compose 的 `operations` profile 可启动无主机端口的 Prometheus；Alertmanager 的接收器凭据必须留在部署侧密钥管理系统。
- Kubernetes 私有化部署、Helm 原子升级和工作负载回滚边界见[Kubernetes Helm 部署与回滚手册](kubernetes-deployment.md)。
- 最终候选门禁、验收矩阵和真实环境发布窗口要求见[最终生产发布演练与验收](production-release-acceptance.md)。

## 本节点尚未覆盖的发布工作

备份脚本、恢复防护和完整性校验已在节点 2 交付，具体使用方式见[备份与恢复运行手册](backup-recovery.md)。依赖/SBOM 与镜像扫描、密钥轮换和发布门禁已在节点 3 交付，具体流程见[发布供应链与密钥轮换](release-security.md)。容量基准、资源告警阈值和恢复演练验收已在节点 4 交付；Kubernetes Helm Chart 与升级/回滚流程已在节点 5 交付；节点 6 提供最终候选验收门禁和真实环境演练清单。生产集群变更仍需部署方独立授权。
