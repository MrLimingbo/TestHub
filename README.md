# TestHub

企业私有化的一站式测试开发平台。阶段 1–9 已完成：测试资产、接口与自动化执行、性能、质量报表、Web/移动自动化，以及受控插件与企业适配器基础。阶段 10 正在进行生产加固；部署基线见 [生产部署文档](docs/production-deployment.md)，备份与恢复操作见 [备份与恢复运行手册](docs/backup-recovery.md)，发布门禁与密钥轮换见 [发布供应链与密钥轮换](docs/release-security.md)。

## 桌面安装包

TestHub 保留现有的浏览器访问、Vite 开发、Docker Compose 和 Kubernetes 部署方式；桌面端连接用户或其组织部署的 TestHub Web 服务，不会连接维护者的本机后台。每个系统用户拥有独立的客户端配置、会话和本地网络授权；HTTP/HTTPS 调试请求由桌面主进程从用户电脑直接发出，因此可访问接口地址或项目环境变量中的内网地址，且不受浏览器 CORS / Private Network Access 限制。安装包与发布流程见[桌面安装包与发布](docs/desktop-packaging.md)。

```bash
npx pnpm@10.13.1 desktop:test
npx pnpm@10.13.1 desktop:package:mac  # 在 macOS 生成 release/desktop/TestHub.dmg
npx pnpm@10.13.1 desktop:package:win  # 仅在 Windows 生成 release/desktop/TestHub.exe
```

## 本地运行

推荐使用可重复执行的恢复脚本（需要 JDK 21、Docker/Colima）：

```bash
./start.sh
# 需要接口自动化执行能力时：
./start.sh --with-api-runner
```

每次执行都会安全重启 TestHub 自己的 Platform API 和 Web UI，并在返回成功前验证后端健康检查、CSRF 安全会话以及 Web 代理链路。MySQL、Redis 和 MinIO 已健康时会直接复用，不会因 Docker 元数据异常中断恢复；基础设施确实不可用时，脚本会对 Colima 自动重启一次并重试，且不会自动删除 Docker 数据。日志和进程文件位于 `.testhub/`。

如需分开调试，也可以手动启动：

```bash
npx pnpm@10.13.1 install
npx pnpm@10.13.1 dev

JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./mvnw -pl services/platform-api spring-boot:run
```

访问 <http://localhost:5173>，本地初始化账号如下：

- `admin@testhub.local`（系统管理员）
- `lead@testhub.local`（测试负责人）
- `tester@testhub.local`（测试工程师）

以上账号的初始密码均为 `TestHub2026!`。

### API Runner 骨架

阶段 5 新增独立 Node.js API Runner。默认只注册 Redis 心跳并执行安全预检；只有完成签名校验、双端显式开启和 Origin 白名单配置后，才会执行受限 HTTP 请求、状态码提取/断言、固定等待、状态条件和有界循环。GET/HEAD/OPTIONS 还可对网络失败、408、429、5xx 在固定 250ms 间隔内有界重试（最多 3 次）。前后置脚本仅在受限 Runner 容器中隔离运行；Runner 不会交接环境密钥。

```bash
npx pnpm@10.13.1 --filter @testhub/api-runner start
```

也可通过 Docker Compose 的可选 profile 启动：

```bash
docker compose --profile runner up -d --build api-runner
```

协议、Stream 名称与安全边界见 [API Runner Redis Streams 协议 v1](docs/api-runner-protocol-v1.md)。

### 接口自动化 CLI

`services/testhub-cli` 提供零依赖的 Node.js 22+ CLI，供 GitHub Actions、GitLab CI 或 Jenkins 提交场景执行、读取脱敏报告，并在归档前校验报告工件的 ETag 与 SHA-256：

```bash
export TESTHUB_URL='http://localhost:8080'
export TESTHUB_PROJECT_ID='01...'
export TESTHUB_SCENARIO_ID='01...'
export TESTHUB_TOKEN='thp_...'

node services/testhub-cli/src/index.js execution run \
  --timeout 300 --wait-timeout 900 --poll-interval 5 \
  --report-output './artifacts/testhub-execution-report.json'
node services/testhub-cli/src/index.js --help
```

CI 可使用 `execution wait --wait-timeout 900 --poll-interval 5` 等待执行终态：成功退出码为 `0`，业务执行失败为 `2`，客户端等待超时为 `3`。它只输出脱敏的状态摘要。

GitLab 项目可直接复用 [CI 模板](ci/gitlab/testhub-api-automation.yml)，使用受保护且脱敏的变量运行一键门禁并归档报告工件。

完整的令牌范围、下载校验和 CI 配置示例见 [接口自动化 CLI、CI 与签名 Webhook](docs/automation-cli-ci-examples.md)。

## 校验

```bash
npx pnpm@10.13.1 lint
npx pnpm@10.13.1 test
npx pnpm@10.13.1 build
```

控制面集成验收（需要先启动 MySQL、Redis 和 MinIO）：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./mvnw -pl services/platform-api test
```

该测试覆盖未登录错误结构、跨组织租户隔离、权限拒绝、文件上传/下载/删除及审计记录。

## 阶段 2 基础设施

```bash
docker compose up -d mysql redis minio
docker compose ps
```

本地端口：MySQL `13307`、Redis `6380`、MinIO API `9000`、MinIO Console `9001`。开发凭据仅用于本地环境，生产环境必须通过环境变量替换。

环境密钥使用 AES-GCM 加密存储。生产环境必须设置 `TESTHUB_ENCRYPTION_KEY`：其值为 Base64 编码的 16、24 或 32 字节 AES 密钥；仓库内置的默认值仅供本地开发使用。
