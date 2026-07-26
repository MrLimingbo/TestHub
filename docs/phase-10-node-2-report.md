# 阶段 10 · 节点 2：备份恢复与迁移基线

## 交付内容

- 增加 `infra/backup/testhub-backup.sh`：对 MySQL 进行单事务逻辑备份，镜像 MinIO 业务对象，保留 Redis 取证快照，生成无凭据的清单与 SHA-256 完整性校验。
- 增加 `infra/backup/testhub-restore.sh`：先验证备份、要求 API 停止与显式 `--confirm-restore`，再替换 MySQL 和 MinIO；Redis 只清空易失会话/队列状态，不将陈旧运行状态带回生产。
- `compose.production.yaml` 增加按需运行的 `minio-client` 运维配置。它位于 `operations` profile，默认不随应用启动，仅供脚本执行一次性对象镜像。
- “运行与发布”新增数据保护区域：展示当前成功的 Flyway 迁移版本、备份完整性校验边界、恢复确认防护与 Redis 易失数据策略；页面不触发备份或恢复，也不展示凭据。
- 新增[备份与恢复运行手册](backup-recovery.md)，包含备份目录要求、恢复前检查、破坏性恢复确认、恢复后验证与保留策略。

## 验收范围

- `bash -n infra/backup/testhub-backup.sh infra/backup/testhub-restore.sh`、`testhub-backup.sh --help`、`testhub-restore.sh --help` 与备份脚本 `create --dry-run`：通过。
- `./mvnw -q -pl services/platform-api -Dtest=OperationsFlowTests test` 与 `./mvnw -q -pl services/platform-api test`：通过；运维 API 自动化测试覆盖 Flyway 最近成功版本和恢复确认防护状态。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`、生产 Compose 配置检查和 MinIO Client 只读降权容器启动检查：通过。
- 浏览器预览：`/operations` 正常显示 V47、备份完整性待办、恢复确认防护和 Redis 易失状态策略；“刷新检查”正常工作。
- 恢复脚本没有在本地开发库执行：它会删除目标 MySQL 数据库并替换对象桶内容，必须在隔离恢复演练或经人工批准的维护窗口中运行。

## 后续节点

下一节点将处理依赖/SBOM 与镜像扫描、密钥轮换和发布门禁；随后继续容量基准、Kubernetes 交付、升级回滚和故障恢复验收。
