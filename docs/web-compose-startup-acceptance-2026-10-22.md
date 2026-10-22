# Colima／Buildx 修复与 Docker 启动验收（2026-10-22）

## 结果

本机 Colima、Docker 和 Buildx 已恢复可用；独立 Compose 环境的 MySQL、Redis、MinIO、API Runner 与真实 API／Web 启动验收通过。全程后台运行，未启动可见浏览器或桌面窗口。

上一轮启动脚本、测试及文档已提交 GitHub main：[`1b694438`](https://github.com/MrLimingbo/TestHub/commit/1b694438ce816537a97970f864f95fc364d45e86)。对应 [verify 工作流](https://github.com/MrLimingbo/TestHub/actions/runs/39743775498)的 Web、platform-api、release-gate 三个作业全部成功。

## 本机修复

| 组件 | 修复前 | 修复后 |
| --- | --- | --- |
| Colima | 0.8.1，启动后 SSH 无法就绪 | 0.10.3，原有默认实例正常运行 |
| Lima | 1.2.0 | 2.2.1 |
| Docker Buildx | 插件缺失 | 0.38.1，可连接当前 Colima 引擎并构建镜像 |
| Docker Engine | 不可连接 | 27.4.0，可正常查询、构建和运行容器 |

停止实例后，先用 APFS 写时复制建立磁盘和配置副本。离线检查发现根文件系统存在孤儿 inode 链及分配位图／计数不一致；待处理的孤儿 inode 均为引用数为零的已解除链接文件。修复在分区副本中进行，随后通过完整只读检查，再把 14 个有差异的 4KiB 块写入整盘副本，复查通过后替换活动磁盘。

原有三个 TestHub 命名数据卷的目录清单在副本修复前后保持一致；原有 48 个 Docker 数据卷在验收结束后均保留。未删除、重建或初始化原有业务数据库及文件存储。实例继续使用原来的 SSHFS 挂载配置。

修复后启动检查未发现本次启动新增的内核文件系统或 I/O 错误。`./start.sh doctor --with-registration --with-api-runner` 在默认配置下通过。当前默认实例保持运行，应用后续启动可复用本轮构建的 MinIO 和 API Runner 镜像。

## 隔离真实验收

使用 `scripts/dev/accept-startup-compose.mjs`，从源码构建独立标签的镜像，使用新建 MySQL／Redis／MinIO 数据卷、随机端口和凭据；所有业务写入均位于此项目。

| 验收项 | 结果 |
| --- | --- |
| Doctor、Buildx 环境预检 | 通过 |
| MinIO 固定源码下载、SHA-256 校验、编译和 OCI 源码标记 | 通过；提交 `0d7408fc9969caf07de6a8c3a84f9fbb10a6739e` |
| API Runner 当前源码镜像构建和容器启动 | 通过 |
| API 健康状态、Vite 网页与 CSRF 代理 | 通过 |
| 注册、会话及 MinIO 文件上传／下载内容核对 | 通过 |
| API Runner 签名心跳 | `VERIFIED`，真实执行保持 `false` |
| 重复启动复用 API／Web PID 和容器 | 通过 |
| 重启 API／Web 后会话、账号和 MinIO 对象保留 | 通过 |
| 重启独占容器后重新登录及文件内容核对 | 通过 |
| 停止应用、清理测试资源、核对原有资源 | 通过 |

首次专用构建器尝试未通过，测试资源已清理。后续为专用构建器配置与 daemon 一致的代理及构建代理参数、启用构建结果导入后，完整验收通过；仓库全局代理配置未被更改。

本轮额外验证 `eslint scripts/dev/accept-startup-compose.mjs` 和 `git diff --check` 通过。上一轮已有的 15 项启动回归、376 项前端测试和生产构建在该 main 提交的本地及 CI 检查中通过。

## 清理

独占 Compose 容器、网络、数据卷、临时镜像标签、专用 Buildx 构建器及其缓存、测试账号、对象和原始日志均已清理。临时磁盘／分区副本及配置备份也已在修复与验收通过后移除。保留可复用脚本及以下脱敏摘要：

- `.testhub/startup-compose-acceptance.json`：真实 Compose 启动验收。
- `.testhub/colima-repair-acceptance.json`：本机修复、数据卷保留和清理确认。

原有工作区、Mac App、业务数据卷和 `output/` 保留。本轮新增验收脚本及文档更新已审核通过，提交状态以仓库记录为准。

## 参考

- [Colima 官方安装与插件说明](https://colima.run/docs/installation/)
- [Lima 磁盘与 APFS 副本说明](https://lima-vm.io/docs/dev/internals/)
- [Docker 构建器环境配置](https://docs.docker.com/build/builders/drivers/docker-container/)
- [Docker 构建代理参数](https://docs.docker.com/build/building/variables/)
