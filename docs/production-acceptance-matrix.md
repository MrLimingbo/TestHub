# 生产发布验收矩阵

此矩阵将当前自动化覆盖、可重复发布验收门禁和必须由部署方完成的真实环境证据分开记录。通过本地门禁表示“生产候选可复现”，不等价于已经对真实集群或业务流量完成认证。

| 领域 | 自动化证据 | 本地发布门禁 | 真实环境验收 |
| --- | --- | --- | --- |
| 登录、会话与 API 错误结构 | `AuthenticationFlowTests`、`PlatformFoundationAcceptanceTests` | Platform API 测试 | HTTPS Cookie、反向代理头和退出会话复核 |
| 系统/组织/项目权限 | `AccessControlFlowTests` | Platform API 测试 | 按实际角色矩阵抽样验证 |
| 组织与项目隔离 | `PlatformFoundationAcceptanceTests#projectDataCannotCrossOrganizationBoundary` | Platform API 测试 | 两个真实组织、两个项目的越权回归 |
| 文件、审计与持久化 | `PlatformFoundationAcceptanceTests#projectFileLifecycleStoresBytesInMinioAndAuditMetadataInMySql` | Platform API 测试 | 受管 MinIO、MySQL 备份/恢复抽样 |
| 功能测试追溯闭环 | `FunctionalExecutionFlowTests` | Platform API 测试 | 需求到报告的项目内抽样 |
| 接口资产与自动化 | `ApiInterchangeFlowTests`、`ApiAutomationExecutionFlowTests` | Platform API 测试 | 经授权的隔离目标、真实 Runner 任务 |
| Runner 信任、取消和离线恢复 | `ApiRunnerTrustFlowTests`、`ApiAutomationExecutionFlowTests`、`PerformanceRunnerCapacityRecoveryFlowTests` | Platform API 测试 | Runner 断连、重启、取消和告警演练 |
| 性能测试与容量 | `PerformanceTestExecutionFlowTests`、`PerformanceRunnerCapacityRecoveryFlowTests` | 受控就绪探针基线 | 经授权压测目标和多 Runner 容量基线 |
| 控制面安全与可观测性 | `OperationsFlowTests`、`SecretCipherTests` | Compose、Prometheus 规则、Helm 渲染 | Alertmanager 路由、密钥轮换和审计抽样 |
| 供应链与部署 | 镜像构建、SBOM、Trivy、Helm lint/template | `final-acceptance.sh` | 目标集群 Ingress、PDB、HPA、ServiceMonitor 和回滚演练 |
| 故障恢复 | 备份完整性、Runner 恢复测试 | 非破坏性恢复计划 | 隔离环境恢复 MySQL/MinIO 后的业务抽样 |

发布负责人必须为每一个“真实环境验收”条目记录变更单、执行人、时间、目标环境、证据位置和未解决风险。任何涉及真实数据恢复、生产 Runner、缺陷系统通知或业务压测的动作都需要单独审批。
