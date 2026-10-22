# Web 启动脚本优化验收（2026-10-22）

## 本轮实现

- 保留 `start.sh` 原入口及注册、API Runner 选项，增加 `start`、`restart`、`stop`、`status`、`doctor`。
- 健康服务复用；依赖清单或锁文件变化时执行冻结锁文件安装。
- 重启前检查两个应用端口、进程归属、JDK 和基础设施；失败时只停止本次新启动的服务，不删除数据库或数据卷。
- 支持自定义 API／Web 端口，并同步 Vite 代理和默认 CORS Origin。
- 移除自动重启 Colima、修改 VM 磁盘配置的恢复逻辑；仅在显式 `--start-colima` 时尝试启动已停止的默认实例。
- 新增后台隔离验收脚本、使用指南及 CI 启动回归入口。

## 已执行验证

| 检查 | 结果 |
| --- | --- |
| `bash -n start.sh`、`git diff --check` | 通过 |
| `pnpm lint` | 通过 |
| `pnpm test:startup` | 15 项通过，包括进程复用、依赖安装失败、端口冲突、PID 复用保护、容器部分失败回滚和分批 JSON 管道输入 |
| `pnpm test` | 57 个测试文件、376 项通过 |
| `pnpm build` | 类型检查及 Vite 构建通过；仍有现有的大体积 chunk 提示 |
| `node scripts/dev/accept-startup.mjs` | 两轮真实后台验收通过：独占 MySQL、Redis、文件存储、Spring Boot API、Vite |

真实验收覆盖网页注册、登录、API 健康检查、CSRF 代理、重复启动不换 PID、重启后 Redis 会话和 MySQL 账号保留，以及停止 API／Web 后基础设施仍可用。没有执行针对原有业务数据库的测试，也没有启动桌面 App 或可见浏览器。

测试进程、临时仓库、数据库、Redis、账号、文件、凭据和日志已清理；仅保留可复用脚本及 `.testhub/startup-acceptance.json` 脱敏摘要。原有工作区、安装程序和 `output/` 未清理。

## 初次验收时的环境限制（后续已关闭）

以下保留首次验收时的状态。Colima／Buildx 修复及真实 Docker／MinIO／API Runner 验收现已完成，详见[后续验收记录](web-compose-startup-acceptance-2026-10-22.md)。

本机默认 Colima 启动一直等待 SSH 就绪，未能提供 Docker daemon；已停止本轮尝试启动的实例，保留原有 VM 数据。默认环境运行 `./start.sh doctor` 正确返回 Docker 不可用；Docker Buildx 插件也尚不可用。

因此，本轮没有完成真实 Docker／MinIO 冷启动及真实 API Runner 容器启动验收；相应失败保护通过隔离进程与命令夹具验证。恢复 Docker／Buildx 后，还需使用独占 Compose 项目复验这些路径，不能把 MySQL／Redis／文件存储验收等同于 Docker／MinIO 验收。

使用方式见[本地 Web 启动指南](local-development.md)。本轮代码与文档的提交状态以仓库记录为准。
