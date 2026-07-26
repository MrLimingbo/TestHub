# 阶段 5.2：独立 API Runner 任务协议与服务骨架报告

## 交付范围

- 新增 `services/api-runner` 独立 Node.js 22 服务，可在控制面之外安装、启动与停止。
- 定义 Redis Streams v1：任务流、事件流、死信流、消费者组和 Runner 心跳的稳定 key；详见 [API Runner Redis Streams 协议 v1](api-runner-protocol-v1.md)。
- Runner 使用两条 Redis 连接：阻塞式 `XREADGROUP` 仅读取任务，命令连接独立负责心跳、事件、死信和确认。
- 新增控制面 Runner 状态接口，并在接口自动化页显示来自 Redis 的在线心跳。
- 提供 Runner 的 pnpm 脚本、Dockerfile、Compose 可选 `runner` profile 与启动文档。

## 安全与阶段边界

- 任务契约只允许场景与作用域 ID、版本、时限和无密钥清单引用；字段白名单拒绝认证令牌、Cookie、密钥、脚本和请求体。
- Runner 收到合法任务只发布 `REJECTED / EXECUTION_DISABLED` 后确认；不读取清单、不发送 HTTP 请求、不访问数据库，也不执行用户脚本。
- 非法任务只进入死信流，死信不保存不可信字段值，只记录消息 ID、错误代码和字段名。
- 状态接口要求调用者拥有目标项目读取权限；过期心跳不会显示。
- 本节点不创建运行任务、不提供取消/重试/超时回收，也不接入真实执行引擎；这些由后续节点实现。

## 接口与运行方式

- Runner 状态：`GET /api/v1/projects/{projectId}/api-automation/runner-status`
- 本地运行：`npx pnpm@10.13.1 --filter @testhub/api-runner start`
- Docker Compose：`docker compose --profile runner up -d --build api-runner`

## 验证记录

- `npx pnpm@10.13.1 --filter @testhub/api-runner lint && npx pnpm@10.13.1 --filter @testhub/api-runner test`：通过，6 项协议与拒绝执行测试通过。
- 本地 Runner 已连接本地 Redis；读取到的心跳为 `runnerType=api`、`protocolVersion=1`、`executionEnabled=false`。
- `./mvnw -q -pl services/platform-api -Dtest=ApiRunnerStatusFlowTests test`：通过，覆盖受保护状态接口与有效心跳展示。
- `./mvnw -q -pl services/platform-api test`：通过，Flyway 13 个迁移和完整控制面回归通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过。
- `docker compose config --quiet`：通过。Docker 镜像构建已走到容器内 Corepack 下载 pnpm 的步骤，但本机 Docker 到 npm registry 的下载卡住，因环境网络阻塞而未完成镜像构建；本地 pnpm 安装和运行已完成验证。

## 用户预览步骤

1. 打开 `http://localhost:5173/api-automation` 并刷新页面。
2. 确认页面上方出现“Runner 协议已连接”，显示 `api-runner-local` 与最近心跳时间；执行引擎仍应提示禁用。
3. 继续编辑、保存场景，确认原有场景编排和版本记录不受影响。
4. 确认页面没有“立即执行”入口，也不会因此节点发起任何网络请求。
