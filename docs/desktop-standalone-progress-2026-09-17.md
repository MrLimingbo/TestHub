# 桌面独立运行改造进度（2026-09-17）

后续更新：[2026-09-18 本地会话与安全状态持久化](desktop-standalone-progress-2026-09-18.md)。本文保留当日阶段证据。

本轮已实现注册、个人工作区、本地文件存储与实验性 H2 文件数据库，并完成服务模式兼容回归。尚未达到可公开分发的独立安装包门槛：桌面默认入口、本地会话/队列和运行时打包仍待完成。桌面数据库模式已不依赖 MySQL/MinIO，但仍需要 Redis；现有网页联调继续使用原开发服务。

## 已确认要求

- 安装后默认进入无需注册的本地工作区；现有企业服务模式保留。
- 用户可选注册/登录，账号数据应与对应网页端共享。首期联调地址为 `http://localhost:5173/login`，以后再部署云端；该地址不写入公共安装包的在线服务默认配置。
- 浏览器、JMeter 等执行组件允许首次使用时自动下载。
- 未登录数据保存在本机，可备份迁移。本地资料转入账号由用户明确选择，不能注册时自动上传全部资料。
- 已询问：账号工作区是否也要求断网编辑、恢复联网后同步。尚未收到明确选择；此问题不影响未登录本地工作区的离线要求。

## 已实现

### 自助注册与个人工作区

- 新增 `GET /api/v1/auth/capabilities`、`POST /api/v1/auth/register`。保留现有错误结构、CSRF、登录和退出接口。
- 注册采用 BCrypt，限制密码 UTF-8 字节数；账号规范化、邮箱校验、唯一约束冲突处理及每来源限流。
- 同一事务创建账号、独立组织、默认项目、默认环境及成员关系；仅授予自己工作区的组织/项目管理权限，不授予 `SYSTEM_ADMIN`。
- 登录和注册轮换已有会话 ID。前端使用已有登录页增加注册切换，成功后加载新账号上下文；去除登录页默认填充的演示密码。
- 普通账号的账号选项只返回自己及同工作区成员；系统管理员仍可选择全站有效账号，避免开放注册后泄露其他工作区的账号列表。
- 自助注册默认关闭，企业部署行为不因升级自动开放。

本机联调启动：

```sh
./start.sh --with-registration
```

此选项开启后台注册，并将开发页面与 API 限制在本机地址。原有 `--with-api-runner` 可以组合使用。这是开发联调命令，不是最终用户安装 App 后需要执行的命令。

### 本地文件存储

- 新增 `ObjectStorage`，提供原有 MinIO 实现和 `FilesystemObjectStorage`。
- 项目附件、性能工件、Web UI 工件、移动端工件及健康检查统一使用该接口。保留业务层的项目权限、元数据、审计、工件类型/大小/校验和限制与下载响应。
- 企业模式默认继续使用 MinIO。文件模式不创建 MinioClient，也不读取 MinIO 连接参数。
- 本地对象键经过哈希映射，不直接作为路径；拒绝桶/对象符号链接；临时文件完成长度校验和落盘后原子替换。失败上传不能覆盖原文件。
- 本地目录必须为显式绝对路径。仅在支持 POSIX 权限的文件系统上将专用对象根目录设为仅当前用户可访问；Windows 权限与进程行为仍需 Windows 实机验证。

文件模式配置（仅开发/后续桌面启动器使用）：

```sh
TESTHUB_STORAGE_TYPE=filesystem
TESTHUB_STORAGE_DIRECTORY=/absolute/path/to/dedicated-testhub-objects
```

必须使用新的专用目录，不能指向个人文档根目录。切换存储类型不会自动迁移现有 MinIO 对象；默认服务配置保持不变。本次验收使用隔离目录和新建测试项目。

## 实际验证

证据目录：`.testhub/standalone-20260917/`（Git 忽略目录，不含公开交付文件）。

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| 默认服务模式后台全量回归（隔离 MySQL 8.4） | 151 通过，0 失败，0 跳过 | `mysql-isolated-final.log` |
| 桌面 H2 文件数据库全量回归 | 151 通过，0 失败，0 跳过 | `embedded-final-v5.log` |
| H2 真实 HTTP、正常退出与强制终止后恢复 | 新建账号、项目、附件元数据和 65,536 字节文件均保留；拒绝匿名请求与演示账号登录 | `embedded-http-result.json`、`embedded-http*.log` |
| 注册、权限、本地存储配置等最终针对性回归 | 26 通过，0 失败，0 跳过 | `final-targeted.log` |
| 本地文件模式，故意配置不可达 MinIO 地址 | 12 通过，覆盖附件/性能/Web UI/移动端工件与运维检查 | `local-storage-regression.log` |
| 前端全量测试 | 105 通过 | 本轮终端输出；会话变更后又重跑 5 项通过 |
| ESLint、Vue 类型检查、生产构建 | 已通过 | `frontend-build.log`；最终构建另见 `frontend-final-build.log` |
| 原有桌面核心测试 | 7 通过 | 本轮终端输出 |
| Electron 注册、独立浏览器登录同一账号 | 已执行通过，网页可读到桌面修改的项目名称 | `registration-ui-result.json`、双端截图 |
| 真实 HTTP 文件上传及后台进程重启后下载 | 65,536 字节，重新登录后 SHA-256 一致，MinIO 地址不可达 | `storage-http-result.json`、`local-storage-api*.log` |
| 本机启动脚本带注册选项 | API/页面/CSRF 就绪通过，只监听回环地址 | `registration-start.log` |

双端联调验证的是在线共享账号数据，没有实现或宣称离线数据同步。移动端工件回归不是连接真机执行验证。没有替换 `/Applications/TestHub.app`，没有重新发布安装包、提交或推送 Git。

## 本地数据库兼容性证据

用当前 Spring Boot 依赖管理对应的 H2 2.3.232，在隔离内存数据库中执行未修改的 61 个 MySQL 迁移：前 9 个通过，第 10 个因 MySQL 多项 ALTER 语法失败；`CAST(string AS JSON)` 得到 JSON 字符串而非对象，`JSON_SEARCH` 不存在。日志见 `h2-compatibility.log`。

依据 [H2 官方兼容模式说明](https://h2database.com/html/features.html)及 [JSON 类型绑定说明](https://h2database.com/html/datatypes.html)，目前已完成以下兼容实现：

- 引入 Spring Boot 管理的 H2 2.3.232，新增 `application-desktop.yml` 和桌面专用数据库配置；默认服务配置仍使用 MySQL。
- 从既有 V1–V61 结构生成仅用于全新桌面数据库的 V1 基线；排除 10 条针对旧数据的升级语句。原 MySQL 迁移文件完全保留，不把该脚本用作企业数据转换器。
- 基线生成器固定源版本与 SHA-256，可用 `node scripts/desktop/generate-database-baseline.mjs` 检查。首次发布后禁止重生成 V1，后续变更必须新增 V2 等迁移。
- 桌面 JdbcClient 边界适配 JSON 参数和时间运算，保留 SQL 字符串原文；实现当前业务使用的 JSON_LENGTH/JSON_SEARCH 形态。未支持的查询形态明确报错。
- 共享仓储显式绑定 JSON；发布接口版本、更新执行步骤改用带原项目约束的单表更新。待投递任务取消使用事务锁同时处理执行记录和 Outbox，覆盖竞争认领及回滚，保留原状态语义。
- 执行日志使用单调 ULID，避免相同事务时间戳下日志排序随机。运维页通过数据库元数据展示实际引擎名称。
- 桌面数据目录必须为专用绝对路径，拒绝根目录、JDBC 分隔符、控制字符、数据库符号链接与空数据库密码；在 POSIX 文件系统设置目录权限 0700。桌面配置禁用演示账号初始化，不能用公开演示密码进入本地库。

真实进程验证显式设置不可达的 MySQL/MinIO 地址，仍可通过 HTTP 注册、上传、重新登录和下载；分别经历 SIGTERM、SIGKILL 后文件内容校验一致。此验证仍使用专用 Redis，不代表会话和任务队列已脱离 Redis，也不是整机断电或 Windows 验收。

回归中保留了失败证据并核实原因：直接使用开发库时，已有用户参数 `BASE_URL` 使两个测试的“空项目”假设不成立（`mysql-regression-2051.log`），未删除该参数；随后改用独立 MySQL。独立实例首轮缺少仓库 Compose 的时区配置，导致两项超时工件验收失败（`mysql-isolated-regression.log`）；对齐 `+08:00` 和 `READ-COMMITTED` 后全量通过，没有放宽业务断言或更改超时规则。H2 随后紧接 MySQL 回归共用 Redis 时，有一项测试的固定 nonce 被防重放策略正确拒绝（`embedded-final-v4.log`）；改为独立 Redis 数据库索引后通过，防重放策略保持不变。

开发验收需指定新的专用目录与密码：

```sh
# 另需设置隔离测试 Redis 的连接环境变量，不能共享正在使用的任务队列。
TESTHUB_DESKTOP_DATA_DIRECTORY=/absolute/path/to/new-testhub-data \
TESTHUB_LOCAL_DB_PASSWORD='<dedicated-test-password>' \
./mvnw -pl services/platform-api -Dspring.profiles.active=desktop \
  -Dspring.data.redis.database=2 -Dtesthub.bootstrap.demo-enabled=true test
```

上面的演示初始化开关仅用于既有回归夹具。最终桌面运行不得打开该开关。当前尚无已经发布的旧桌面数据库，企业历史数据导入、未来本地版本升级及备份恢复仍需单独实现和验收。

## 下一阶段必须完成

1. 保持两种数据库回归通过，并补齐未来本地版本升级、企业数据导入与备份恢复；全新 H2 结构和现有业务回归已通过。
2. 完成本地会话与可靠任务队列适配，包括 Runner 心跳、防重放、租约、取消、超时与进程恢复。
3. 桌面内置前端、后端及运行时，自动启动本地身份和工作区，处理端口、鉴权、退出和崩溃恢复。
4. 将本地资料迁入账号，以及对应范围的下载、冲突处理、版本和删除同步；不能用在线共享接口替代离线同步要求。
5. 执行组件完整性校验与按需安装，备份恢复，逐模块真实执行回归。
6. Mac 双架构及 Windows 干净系统安装验收，完成后才生成可分发的新安装包。
