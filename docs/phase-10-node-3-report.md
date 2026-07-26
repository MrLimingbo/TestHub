# 阶段 10 · 节点 3：发布供应链与密钥轮换

## 交付内容

- 新增固定版本的 Syft SBOM 与 Trivy 镜像扫描脚本，以及本地 `release-gate.sh`。门禁检查生产 Compose、构建 Platform API/Web 镜像、生成 CycloneDX SBOM，并阻断可修复的 HIGH/CRITICAL 漏洞。
- GitHub Actions 新增 `release-gate`，归档 SBOM；Platform API CI 改为先启动 MySQL、Redis、MinIO 再运行集成测试。
- AES-GCM 密文升级为带密钥标识的 `v2:<key-id>:...`；历史 `v1` 密文和由旧密钥标识的 v2 密文均可在轮换窗口读取。
- 提供受控的重加密服务：仅在配置显式启用、系统管理员确认、旧密钥回退存在时才会重加密持久密文，并写入不含密钥的审计记录。
- 运行与发布页增加密钥环、重加密窗口、CycloneDX SBOM、镜像扫描和发布门禁状态；不显示密钥值，不允许页面直接开启轮换窗口。
- 新增[发布供应链与密钥轮换](release-security.md)手册，定义两阶段轮换、审计、回退与分支保护规则。
- Platform API 升级至 Spring Boot 3.5.14，并更新 MinIO、Jackson、Netty、Tomcat 与 Bouncy Castle；运行时改为非 root 的 distroless Java 21 Debian 13 镜像。Web 镜像升级至固定的 NGINX unprivileged 1.31.3 Alpine 标签。

## 验收范围

- 密钥环单元测试覆盖 v2 主密钥写入、旧密钥回退读取、历史 v1 读取及缺失旧密钥时的拒绝。
- 运维接口测试覆盖默认关闭的重加密窗口及管理员确认请求的拒绝路径。
- `2026-07-24` 本地发布门禁已通过：生产 Compose 配置校验、Platform API/Web 生产镜像构建、两份 CycloneDX SBOM 生成和两个镜像的可修复 HIGH/CRITICAL 漏洞扫描均成功。验证产物位于本机临时目录，未写入仓库。
- 真实重加密和分支保护配置必须在经审批的维护窗口或仓库管理员配置中执行。

## 验证记录

- `./mvnw -q -pl services/platform-api test` 通过；覆盖密钥格式兼容、重加密默认拒绝、Runner 心跳约束及既有控制面集成流。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build` 通过。
- `bash -n scripts/security/*.sh`、`docker compose --env-file .env.production.example --profile operations -f compose.production.yaml config --quiet` 与 `git diff --check` 通过。
- `TESTHUB_SECURITY_ARTIFACT_DIR=/tmp/testhub-node3-release-gate scripts/security/release-gate.sh` 通过，生成 Platform API 和 Web 两份 CycloneDX JSON SBOM，并完成镜像扫描。
- 浏览器预览通过：`/operations` 显示主密钥标识、默认关闭的重加密窗口、SBOM、镜像扫描和供应链门禁；页面不暴露密钥值。

## 后续节点

下一节点将建立容量基准、资源告警阈值与故障恢复验收，然后继续 Kubernetes Helm Chart、升级回滚和最终发布演练。
