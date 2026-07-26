# 阶段 10 · 节点 1：生产部署与可观测性基线

## 交付内容

- 新增 Platform API 多阶段 Dockerfile 与 Web Nginx 镜像 Dockerfile，运行镜像使用非 root 用户、只读根文件系统、临时目录限制、capability drop、进程和内存限制。
- 新增独立 `compose.production.yaml`：MySQL、Redis、MinIO、API 仅处于私有内部网络，只有 Web 容器发布 HTTP 端口；生产配置不含任何可用默认密钥。
- 新增 `.env.production.example` 与 [生产部署文档](production-deployment.md)，说明镜像构建、不可变版本、密钥注入、TLS 终止、配置预检与内部指标抓取方式。
- Spring Boot Actuator 增加存活/就绪探针和 Prometheus 端点；MinIO 被加入就绪检查，探针响应始终不暴露内部连接详情。
- 新增系统管理员专用 `/api/v1/operations/overview`：对 MySQL、Redis、MinIO 执行无敏感信息的连通性检查，并展示请求追踪、CSRF、安全响应头、安全 Cookie、CORS 与指标控制的状态摘要。
- 新增“运行与发布”页面：可刷新运行检查、查看依赖健康、生产安全控制与后续发布待办；页面不显示密钥、内部地址、连接串或异常堆栈。
- CORS 来源改为可外部化的 `TESTHUB_CORS_ALLOWED_ORIGINS`；API 增加 CSP、Referrer-Policy、Permissions-Policy、X-Frame-Options 等安全响应头。Prometheus 端点仅用于内部网络，Web Nginx 明确拒绝其外部访问。

## 验证

- `./mvnw -q -pl services/platform-api -Dtest=OperationsFlowTests test`：通过；覆盖存活/就绪探针、Prometheus、管理员运维总览和安全响应头。
- `./mvnw -q -pl services/platform-api test`：全套通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过；Vitest 仅输出已有 Monaco sourcemap 缺失警告。
- `docker compose config --quiet` 与 `docker compose --env-file .env.production.example -f compose.production.yaml config --quiet`：通过。
- `docker build -f services/platform-api/Dockerfile -t testhub/platform-api:phase10-node1 .` 与 `docker build -f Dockerfile -t testhub/web:phase10-node1 .`：通过。
- 浏览器预览：`/operations` 路由、依赖健康卡片、生产安全控制、Secure Cookie 提示及“刷新检查”操作均正常显示。

## 后续节点

本节点完成生产运行基线，不代表发布准备结束。后续仍需完成备份恢复演练、依赖/SBOM 与镜像扫描、密钥轮换、容量基准、Kubernetes Helm Chart、升级回滚流程与故障恢复验收。
