# TestHub

企业私有化的一站式测试开发平台。当前已完成阶段 1 UI 以及阶段 2 基础平台：身份会话、RBAC、项目参数、环境密钥、系统配置、审计日志、通知模板、任务中心和 MinIO 文件服务。

## 本地运行

```bash
npx pnpm@10.13.1 install
npx pnpm@10.13.1 dev
```

另开终端启动控制面（需要 JDK 21）：

```bash
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
