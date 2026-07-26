# 最终生产发布演练与验收

本手册规定从本地候选到真实生产发布的分层流程。它不会替代变更审批、集群权限、业务系统授权或数据恢复授权。

## 1. 本地候选门禁

先启动本地控制面和依赖，然后运行完整的可重复门禁：

```bash
scripts/release/final-acceptance.sh \
  --url http://127.0.0.1:8080/actuator/health/readiness \
  --artifact-dir /secure/evidence/testhub-release-$(date -u +%Y%m%dT%H%M%SZ)
```

它依次验证脚本、Compose、Helm、Prometheus 规则、Platform API、Web、受控就绪探针、非破坏性恢复计划、生产镜像、CycloneDX SBOM 和可修复 HIGH/CRITICAL 漏洞扫描。输出 `acceptance.json` 以及引用的证据文件；默认结果为 `CANDIDATE`。`--skip-supply-chain` 仅用于本地快速调试，结果将标记为 `LOCAL_ONLY`，不得用于发布审批。

## 2. 发布前变更评审

发布负责人必须确认：

1. [生产验收矩阵](production-acceptance-matrix.md)中对应的本地自动化均通过，真实环境条目已有负责人和时间窗。
2. 镜像采用不可变 digest 或版本 tag；SBOM、漏洞扫描和 Helm 渲染证据已归档。
3. MySQL/MinIO 备份已完整性校验，恢复负责人、RTO/RPO 和隔离恢复环境已经确定。
4. Helm values 和 Kubernetes Secret 仅位于受限部署侧，未进入代码、日志或工单附件。
5. 迁移均向后兼容；若需要数据回退，已准备独立维护窗口，不能依赖 `helm rollback`。
6. 业务压测目标、外部 Webhook/通知接收方和真实 Runner 已获得系统所有者授权。

## 3. 隔离集群演练

在临时命名空间或隔离集群中执行：

1. 创建运行时 Secret 和部署 values，运行 `helm-release.sh plan`，人工核对清单。
2. 用 `helm-release.sh apply` 部署，验证 API/Web 就绪、TLS、登录、关键测试资产读取和附件读取。
3. 验证 Ingress 不暴露 `/actuator`，而集群内 Prometheus 能抓取 API 指标。
4. 演练一个新 API/Web 镜像的原子升级；让一个新 Pod 失败，确认 Helm 自动回退工作负载。
5. 在 schema 兼容前提下选择已知 revision，演练受控工作负载回滚；不恢复数据、不重放 Redis。
6. 按[容量与韧性运行手册](capacity-and-resilience.md)执行内部就绪基线和隔离恢复计划。

## 4. 生产窗口

只有隔离演练证据通过，且变更委员会批准后，才允许对生产集群执行同一流程。生产窗口必须实时记录：发布 revision、镜像 digest、迁移版本、就绪时间、容量基线、告警状态、回滚决定和最终负责人签字。若任何关键项失败，停止扩展、保留证据，并根据[部署与回滚手册](kubernetes-deployment.md)决定工作负载回滚或数据恢复流程。
