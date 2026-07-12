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
