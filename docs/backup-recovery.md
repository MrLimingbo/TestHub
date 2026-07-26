# 备份与恢复运行手册

本手册覆盖 TestHub 的持久数据恢复边界：MySQL 为业务事实来源，MinIO 保存附件、日志和报告工件；Redis 保存会话、队列和临时执行状态，不应在灾难恢复后直接重放。

脚本不会把数据库、对象存储或 Redis 密码写进备份目录。备份目录本身仍必须置于受控、加密且具备访问审计的存储中。

## 生成并验证备份

在部署主机或受限运维作业中执行，输出目录必须是挂载的加密备份卷或备份网关目录：

```bash
chmod 700 infra/backup/testhub-*.sh
infra/backup/testhub-backup.sh create \
  --env-file /run/secrets/testhub.env \
  --output /mnt/testhub-backups

infra/backup/testhub-backup.sh verify \
  --backup /mnt/testhub-backups/testhub-YYYYMMDDTHHMMSSZ
```

每个备份包含以下内容：

- `mysql.sql.gz`：使用单事务逻辑导出的业务库，包含 Flyway 历史。
- `minio/`：业务对象桶的镜像；空桶会保存一个受控标记。
- `redis.rdb`：仅用于事故取证的 Redis 快照，不作为恢复输入。
- `manifest.env`：格式版本、创建时间和最近成功的 Flyway 版本，不含凭据。
- `checksums.sha256`：所有恢复工件的 SHA-256 校验清单。

`verify` 必须在创建后、复制到异地后以及恢复演练前各执行一次。脚本的 `--dry-run` 可用于 CI 或变更评审，不会连接数据服务。

## 恢复前检查

1. 建立维护窗口，冻结写入，并确保当前 `api` 服务已经停止。
2. 在隔离环境先恢复同一份备份，执行 Flyway 校验和关键业务冒烟测试。
3. 对照备份清单核对预期 Flyway 版本；恢复脚本会先强制运行完整性校验。
4. 准备独立的回滚点。恢复会替换 MySQL 数据库和 MinIO 桶内容，不能用来覆盖仍需保留的生产数据。

## 执行恢复

以下命令具有破坏性；必须在人工复核后显式写出 `--confirm-restore`：

```bash
docker compose --env-file /run/secrets/testhub.env -f compose.production.yaml stop api

infra/backup/testhub-restore.sh restore \
  --env-file /run/secrets/testhub.env \
  --backup /mnt/testhub-backups/testhub-YYYYMMDDTHHMMSSZ \
  --confirm-restore
```

恢复过程会执行 MySQL 数据库替换、MinIO 桶镜像替换，并清空 Redis 的会话/队列等易失状态。随后仍需先检查数据库 Flyway 历史、对象数量、管理员登录和关键项目读取，再显式启动 API：

```bash
docker compose --env-file /run/secrets/testhub.env -f compose.production.yaml up -d api
docker compose --env-file /run/secrets/testhub.env -f compose.production.yaml logs --tail=100 api
```

若 API 没有通过就绪探针，不要继续开放 Web 流量；应保留现场并根据日志、备份清单和数据库快照排查。

## 调度与保留建议

- 生产环境应由受限 CI、Kubernetes CronJob 或备份平台每日执行 `create` 与 `verify`，不要把 `.env.production` 放进代码仓库或常规日志。
- 最少保留日、周、月三个恢复点，并在异地存储保存一份不可变副本。
- 每季度对最近备份进行一次隔离恢复演练；把演练时间、备份 ID、校验结果和问题记录到运维审计系统。
- MinIO 生产部署应另外启用底层卷快照或对象存储版本化，作为逻辑镜像之外的第二道恢复保障。
