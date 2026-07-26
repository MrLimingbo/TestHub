# 阶段 10 · 节点 5：Kubernetes Helm、升级与回滚

## 交付内容

- 新增 `infra/helm/testhub` Helm v3 Chart，部署无状态的 Platform API 与 Web；数据服务、Runner、备份和 Alertmanager 保持为外部/受管依赖。
- API 与 Web 均提供滚动升级策略、就绪/存活探针、只读根文件系统、最小权限、安全临时卷、资源请求/限制、PDB 和可选 HPA。
- Web Nginx 配置由 Chart 注入，反向代理到 release 专属 API Service；公网 Ingress 只指向 Web，API 和 Prometheus 指标保持集群内访问。
- Chart 仅引用已有 `api.existingSecret`，不生成 Secret 或默认凭据；提供生产 values 示例与运行时 Secret 键清单。
- 可选 ServiceMonitor 与 NetworkPolicy 已加入。NetworkPolicy 默认关闭，必须先按集群内 Prometheus 和外部依赖的选择器完成验证后才允许开启。
- 新增 `scripts/deployment/helm-release.sh`：`plan` 是客户端渲染；`apply` 必须使用明确确认并采用 `--atomic --wait`；`rollback` 同样要求明确确认，并明确只回滚工作负载而不回滚 Flyway 或数据。
- 新增容器化 Chart 校验脚本，并将它加入 GitHub Actions release-gate。
- 运行与发布页增加 Helm 部署与渐进升级/回滚控制项；不展示集群地址、Secret 名称以外的敏感信息或凭据值。
- 新增[Kubernetes Helm 部署与回滚手册](kubernetes-deployment.md)。

## 验收范围与边界

- `alpine/helm:3.17.3` 中的 `helm lint` 与带 Ingress、ServiceMonitor、NetworkPolicy 的完整模板渲染均已通过；生成物包含 Deployment、Service、PDB、Ingress、ServiceMonitor、NetworkPolicy 和运行时 ConfigMap。
- 应用完整测试覆盖运维接口投影中的 Helm 控制项；前端构建覆盖运行与发布页面。
- 当前没有连接 Kubernetes 集群，也未提供运行时 Secret 或部署 values，因此未执行 `apply`、`rollback`、数据库迁移或生产流量切换。这些动作保留给经审批的最终发布演练。
- Chart 的工作负载回滚不会撤销 Flyway schema、MySQL/MinIO 数据、Redis 会话或在途 Runner 任务；此边界已在脚本与手册中固定说明。

## 验证记录

- `scripts/deployment/validate-helm-chart.sh` 通过（容器化 `helm lint` 和 `helm template`）。
- `docker run --rm -v "$PWD:/workspace:ro" -w /workspace alpine/helm:3.17.3 template testhub infra/helm/testhub --namespace testhub --values infra/helm/testhub/ci-values.yaml` 通过。
- `bash -n scripts/deployment/*.sh`、`docker compose --env-file .env.production.example --profile operations -f compose.production.yaml config --quiet` 与 `git diff --check` 通过。
- `./mvnw -q -pl services/platform-api test` 通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build` 通过。Vitest 会输出 Monaco 上游缺失 source map 的既有警告，未影响通过结果。
- 浏览器预览：`/operations` 显示 Kubernetes Helm 部署和渐进升级/回滚防护两个已启用控制项；页面不显示密钥或内部地址。

## 后续节点

下一节点进行阶段 10 最终生产发布演练：端到端回归、权限矩阵、租户隔离、故障恢复和并发容量验收的证据汇总。任何真实集群或生产操作均需单独获得部署授权。
