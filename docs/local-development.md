# 本地 Web 启动指南

`start.sh` 是 macOS／Linux 的源码开发入口。它启动 Spring Boot API 和 Vite 网页，默认使用仓库 Compose 中的 MySQL、Redis 和 MinIO。桌面 App 的本地工作区由桌面运行时管理，不使用这个脚本。

## 一键启动

准备 Node.js 22+、包含 `javac` 的 JDK 21、Docker Compose v2 和 Docker Buildx。Docker 必须可用；已自行部署且健康的基础设施可直接复用。

```bash
./start.sh doctor --with-registration
./start.sh --with-registration
```

默认入口为 <http://127.0.0.1:5173/login>，API 健康检查为 <http://127.0.0.1:8080/actuator/health>。桌面账号连接填写 `http://127.0.0.1:5173`，不带 `/login`。注册模式下 API 和网页都绑定 `127.0.0.1`；普通开发模式保留原来的局域网监听行为，不适合直接暴露到公网。

首次运行会按锁文件安装 pnpm 10.13.1 依赖、获取缺失的 Docker 镜像，并按固定源码版本构建 MinIO；Maven 也可能下载依赖。无需先手动执行 `pnpm install` 或 `docker compose build minio`。网络失败会给出日志位置，修复后重试即可。

macOS 使用默认 Colima 实例时，可显式允许启动已停止的实例：

```bash
./start.sh --with-registration --start-colima
```

这不会打开桌面窗口，也不会自动重启运行中的 Colima、修改磁盘容量配置或删除 Docker 数据。若虚拟机本身启动失败，需要先处理 Colima 的错误。

## 生命周期

| 命令 | 行为 |
| --- | --- |
| `./start.sh` 或 `./start.sh start` | 启动缺失的服务；复用选项相同、依赖未变且健康的已有服务 |
| `./start.sh restart --with-registration` | 完成环境、端口、依赖和基础设施准备，再重启本仓库的 API／Web |
| `./start.sh stop` | 停止本仓库 API／Web；保留基础设施、Runner、数据库和文件 |
| `./start.sh status` | 检查进程归属、API 健康状态、网页及 CSRF 代理；全部就绪返回 0，否则返回非零 |
| `./start.sh doctor --with-registration` | 只做预检；不安装依赖、不拉取镜像、不启动服务、不改变已有服务 |

旧的 `./start.sh --with-registration` 和 `--with-api-runner` 用法继续有效。每次启动或重启时都应传入所需选项；`stop`、`status` 不接受启动选项。

后端源码不会因为重复 `start` 而自动重新加载。修改后端代码、注册开关、环境变量或依赖清单后使用 `restart`；Vite 继续处理前端热更新。旧脚本遗留的运行进程没有配置指纹，首次切换新版时应使用 `restart`。

脚本会比较根目录及 `apps/*`、`services/*`、`packages/*` 的依赖清单、锁文件、workspace 配置、`.npmrc` 和 Node 主版本。首次启动及这些内容变化后执行 `install --frozen-lockfile`，不会自行更新锁文件。运行中遇到依赖变化时，`start` 会要求显式 `restart`。

启动失败只清理本次新启动的 API／Web 及本次启动的 Compose 容器。已在运行的服务不会被失败清理关闭，容器和数据卷不会被删除。若显式 `restart` 已经进入重启阶段，随后 Maven 编译或应用启动失败，旧进程不会自动恢复，修复后重试即可。已经完成的依赖安装和镜像构建会保留，便于重试。

API 与 Web 两个端口会在停止任何进程前一起检查。发现其他应用占用端口或 PID 文件指向其他进程时，脚本停止操作并提示 PID；不会自动抢占端口。并发启停由 `.testhub/start.lock` 串行保护。

## 配置和日志

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `JAVA_HOME` | 自动寻找 JDK 21 | 支持 macOS JDK、Homebrew 和 PATH 中的 Java；必须有 `javac` |
| `TESTHUB_START_API_PORT` | `8080` | 同时配置 API 监听端口与 Vite 的 API 代理目标 |
| `TESTHUB_START_WEB_PORT` | `5173` | 配置网页端口；默认 CORS 本地 Origin 同步调整 |
| `TESTHUB_START_STATE_DIR` | 仓库 `.testhub/` | 日志、PID、锁和启动选项摘要；相对路径相对于仓库 |
| `TESTHUB_REGISTRATION_ENABLED` | `false` | `true` 等同于开启注册；`--with-registration` 优先 |

自定义端口或状态目录后，`start`、`restart`、`stop`、`status` 必须使用同一组变量，例如：

```bash
export TESTHUB_START_API_PORT=18080
export TESTHUB_START_WEB_PORT=15173
./start.sh --with-registration
./start.sh status
./start.sh stop
```

脚本控制 API 端口，以 `TESTHUB_START_API_PORT` 为准。直接运行 Vite 时，可用 `TESTHUB_VITE_API_ORIGIN` 指定 API Origin；未设置时保持原来的 `http://localhost:8080`。

后端已有的 `TESTHUB_DB_*`、`TESTHUB_REDIS_*`、`TESTHUB_MINIO_*`、`SPRING_*` 配置继续通过环境传递。显式使用 `TESTHUB_STORAGE_TYPE=filesystem` 时还需指定 `TESTHUB_STORAGE_DIRECTORY`，此时不探测 MinIO。这些配置不会把已有数据库自动切换或迁移到新工作区。

自定义基础设施应先自行启动。若需要脚本管理它，提供与端口、凭据一致的 `COMPOSE_FILE`／`COMPOSE_PROJECT_NAME`。自定义端点不可用时，脚本不会拿默认 Compose 冒充修复。已有 Compose 容器不会被自动重建；更改容器配置或 Runner 代码后，需要先按自己的 Compose 配置重新构建、更新相关容器。

脚本不执行或加载 `.env` 为 shell 脚本。Compose 仍按自己的规则读取 `.env`，API 的环境变量应显式 `export`；二者的数据库和 Redis 凭据必须一致。

日志默认位于 `.testhub/platform-api.log`、`web.log`、`dependencies.log`、`docker-compose.log`。启动失败不会把其他进程命令行或环境变量打印到终端，启动配置只保存摘要。日志和状态文件不提交 Git。

## 执行器范围

`--with-api-runner` 仅准备并启动 API Runner 容器。用户明确请求的容器启动失败时，整个启动命令返回失败，不再以“可选警告”掩盖失败；已有健康 API／Web 保留。

API、JMeter、Playwright 和 Appium 的真实执行仍按各模块的启用开关、信任校验及目标白名单控制。这个脚本不会自动授权测试目标，也不会默认启动性能、Web UI 或移动端 Runner。对应配置见 `compose.yaml` 及各 Runner 的 README。

## 验证和测试隔离

```bash
bash -n start.sh
npx pnpm@10.13.1 test:startup
```

启动回归会创建临时仓库、随机回环端口和进程，覆盖健康复用、依赖更新、端口冲突、Docker／Buildx 预检、部分容器启动失败、进程回滚和启停锁，结束后删除测试目录。该检查已接入 `verify` 工作流的 Web 作业。

真实 Web 启停验收可使用本机 MySQL 8.4 和 Redis 7.4 二进制：

```bash
TESTHUB_QA_MYSQL_BASE_DIR=/path/to/mysql \
TESTHUB_QA_REDIS_SERVER=/path/to/redis-server \
JAVA_HOME=/path/to/jdk-21 \
node scripts/dev/accept-startup.mjs
```

它新建独占 MySQL 数据目录、Redis 实例和文件存储，使用随机端口与随机凭据，真实启动 API／Vite，验证注册、登录、CSRF、健康复用、重启后会话和账号保留。全程后台运行，不打开浏览器。结束后停止所拥有的进程并清理数据库、文件和日志；只留下 `.testhub/startup-acceptance.json` 脱敏摘要。该验收验证 MySQL／Redis／文件存储路径，不代表 Docker／MinIO 的真实启动验收。

真实 Docker／MinIO／API Runner 验收使用独立 Compose 项目：

```bash
JAVA_HOME=/path/to/jdk-21 node scripts/dev/accept-startup-compose.mjs
```

它使用随机端口、凭据和新数据卷，重新构建固定源码的 MinIO 与当前 API Runner 镜像，验证签名心跳、注册登录、文件上传下载，以及应用／容器重启后的数据保留。Runner 的真实执行保持停用。专用 Buildx 构建器复用 Docker daemon 的代理设置，并把代理用于构建阶段；不改写全局 Docker 代理配置或当前构建器选择。需要替换 Go 模块代理时，可显式设置 `TESTHUB_QA_GOPROXY`。

验收结束后移除本次 Compose 容器、网络、数据卷、镜像标签、构建器及其缓存和临时目录；核对原有容器、数据卷仍然存在，只保留 `.testhub/startup-compose-acceptance.json` 脱敏摘要。失败时摘要包含经过凭据脱敏的错误片段，原始日志随测试目录清理。未确认 API／Web 已停止时，清理会报错并保留诊断状态。
