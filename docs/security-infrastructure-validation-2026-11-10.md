# MySQL/Redis/S3 与容器安全追加验收

> 后续 8 条 HIGH 的修复与可利用性复核已完成：Expat 2.10.0 已落地，util-linux 受影响组件确认不在运行时镜像中。最终镜像与逐项结果见 [2026-11-11 HIGH 复核报告](security-high-risk-validation-2026-11-11.md)。以下保留本轮基础设施验收的历史记录。

本报告接续 `security-review-2026-11-10.md`，更新至 2026-11-11。已完成本轮可修复问题、1380 项完整后端验收、API/Web 最终镜像复扫和容器启动检查。两镜像均无扫描数据库标明可修复的漏洞；API 仍有 79 条基础库包/CVE 记录（含 8 HIGH），不能宣称镜像零漏洞。范围为仓库隔离 MySQL/Redis/MinIO 验收及发布门禁规定的 API/Web 两个 Linux arm64 交付镜像，不含可选 Runner、基础设施镜像及其他架构的漏洞扫描。

## 首轮结果与修复

| 检查 | 实际结果 | 处理 |
| --- | --- | --- |
| 隔离 MySQL/Redis/S3 完整验收 | 运行中出现主机 `No space left on device`，随后数据库连接失败、Colima/containerd 存储 I/O 错误；Maven 最终记录 1211 项、20 失败、148 错误、0 跳过，BUILD FAILURE | 新增磁盘预检和运行中停止保护；后续专用环境完整回归 1380 项全部通过，首轮基础设施失败未被计作通过 |
| API 镜像构建与 SBOM | 构建成功，生成 CycloneDX SBOM | 保留摘要信息供最终复验核对 |
| Web 镜像构建与 SBOM | 构建成功，生成 CycloneDX SBOM；首轮门禁在 API 扫描失败后退出，未执行 Web 扫描 | 最终完整门禁与全级别扫描通过，Web 0 条告警 |
| API 镜像 Trivy 扫描 | Debian `zlib1g` 返回 2 条可修复 HIGH：CVE-2026-76844、CVE-2026-85091；应用 JAR 无该门禁阈值下的漏洞记录 | 更新官方 `java21-debian13:nonroot` 固定摘要；最终镜像的 zlib 已为修复版本，2 条告警均消失 |
| 漏洞数据库下载 | 默认 GCR 镜像源拒绝连接，Trivy 未自动回退；直接 GHCR 下载曾超出默认 5 分钟 | 扫描脚本支持显式官方源、容器可达的 HTTPS 代理及可配置总时限，失败仍阻断门禁 |

Debian 官方将上述 zlib 问题列为堆缓冲区溢出，修复版本为 `1:1.3.dfsg+really1.3.1-1+deb13u1`。Trivy 首轮表格中 CVE-2026-76844 的标题与包信息不一致，问题解释以 [Debian CVE-2026-76844](https://security-tracker.debian.org/tracker/CVE-2026-76844) 和 [Debian CVE-2026-85091](https://security-tracker.debian.org/tracker/CVE-2026-85091) 为依据，不能据该标题声称项目存在 webpack 路径穿越。

基础镜像摘要从 `1bc4eca3c3b351743be673d7109951b509d6f47a57648e5e2efe784a1b051c4a` 更新为 `d978eefd303c59c42f382aef811a0e41f2c68a6e5c319a23769cd3d1d5bf8e3c`；新摘要来自官方仓库的多架构索引。镜像仍以 nonroot 用户运行。

## 新增回归与保护

- `scripts/security/scan-image.test.mjs`：默认官方源、显式源与代理、超时配置、扫描器非零退出码阻断门禁，共 4 项通过，接入 release-gate CI。
- `scripts/desktop/qa-disk-guard.test.mjs`：低磁盘预检、运行中仅停止调用方任务一次、清理期间取消在途监控、文件系统读取失败，共 4 项通过，接入 MySQL 验收 CI。
- MySQL 验收启动前要求工作区所在文件系统至少 8 GiB 可用；运行时低于 2 GiB 停止本轮子进程并执行自有项目清理。该检查不代替 Docker 数据盘容量管理，有限磁盘上应顺序运行数据库验收与大型镜像构建。
- 修复隔离 MySQL 健康检查：使用 Compose 的 `$$` 转义保留容器内密码变量，并以 TCP `127.0.0.1` 检查最终监听端口，避免变量被宿主替换和初始化阶段的临时 Unix socket 提前报健康。
- 新增/修改的 JavaScript lint 和安全脚本 Bash 语法检查通过。

## 最终验证结果

| 检查 | 实际结果 |
| --- | --- |
| MySQL/Redis/S3 完整回归 | 161 个测试类、1380 项全部通过；0 失败、0 错误、0 跳过，Maven 与 JUnit XML 一致 |
| 新增定向回归 | OGNL/MyBatis 2 项、磁盘保护 4 项、扫描脚本 4 项，共 10 项通过；Java 的 2 项已包含在 1380 项中，不重复计数 |
| 本地 JAR、API/Web 镜像构建 | 全部成功；实际 JAR 和 SBOM 确认 MyBatis 3.6.0、内嵌 OGNL 3.4.16 |
| 发布门禁 | `release-gate.sh` 退出 0；Compose 配置校验、两个 CycloneDX SBOM、可修复 HIGH/CRITICAL 门禁均通过 |
| API 全级别复扫 | Java 依赖 0 条；OS 79 条：8 HIGH、47 MEDIUM、23 LOW、1 UNKNOWN；可修复条目 0，独立 CVE 45 个 |
| Web 全级别复扫 | 所有级别 0 条 |
| API 容器启动 | 最终镜像以 nonroot、只读根文件系统、去除 capabilities 启动；独立临时 H2/filesystem 就绪接口返回 UP；运行时为 Java 21.0.13 |
| Web 容器启动 | 最终镜像以 UID 101、只读根文件系统启动；首页 HTTP 200、SPA 路由回退、安全响应头通过；`/actuator/health`、`/actuator/prometheus` 返回 404 |
| npm 全量审计 | 2026-11-11 查询官方 registry，548 个依赖，所有级别 0 告警 |
| 静态检查 | 修改的脚本 ESLint、Bash 语法、Java 编译及 `git diff --check` 通过 |

全级别复扫于 2026-11-11 12:26（Asia/Shanghai）完成。Trivy 0.65.0 漏洞库更新时间为 2026-11-10 06:59 UTC，下一次更新为 2026-11-11 06:59 UTC；Java 索引更新时间为 2026-11-10 00:57 UTC，扫描时均未超过更新周期。全级别 JSON 扫描使用 `--scanners vuln --format json`，未指定 `--severity` 或 `--ignore-unfixed`。完整剩余项见 [基础镜像告警清单](security-image-findings-2026-11-11.md)。

最终镜像本地 OCI ID（Linux arm64）：

- API：`sha256:2086be535fab0458060ac841623b3e89a9294c1383542da084e51f6a36926196`。
- Web：`sha256:88a818bd000b5cb0ae6e4feae0363d3c0e4a92114d1b8c7eb6259bec6403ec79`。
- API CycloneDX SHA-256：`0ac89f040176b2d397ba6243313eb0a0061f47398fbda6c088e8079e65463ad3`。
- Web CycloneDX SHA-256：`aaacf379b26177cadd2b1206bab64f390cf891700dd9b23f280e9c1f23f05941`。

本地门禁通过 `DOCKER_CONTEXT=colima-testhub-security-20261110`、两项 `TESTHUB_*_IMAGE=testhub/security-{api,web}:qa-fixed-20261111`、`TESTHUB_SECURITY_ARTIFACT_DIR=.testhub/security-images-final-20261111` 运行。受限网络使用官方 GHCR 数据库源、容器可达代理和 20 分钟总时限；没有降低门禁阈值或禁用数据库校验。上述镜像均为测试构件，未推送或部署；原始 SBOM/扫描临时构件在提取脱敏摘要后按仓库要求清理。

## 2026-11-11 全级别复查发现

不能将既有发布门禁的“0 条可修复 HIGH/CRITICAL”解释为镜像没有漏洞。移除严重程度和 `--ignore-unfixed` 过滤后，Web 为 0 条，API 为 80 条包/CVE 记录：8 HIGH、48 MEDIUM、23 LOW、1 UNKNOWN；其中只有 MyBatis 内嵌的 OGNL 告警存在该扫描数据库标明的可用修复版本。

### MyBatis 内嵌 OGNL（已修复并完成复扫）

- `mybatis-plus-spring-boot4-starter:3.5.17` 引入 `mybatis:3.5.19`，其 JAR 内嵌 OGNL 3.4.4，命中 CVE-2026-113730（Trivy MEDIUM；上游公告现归为 Low）。此前 Maven 依赖坐标查询没有列出这份 shaded 依赖，因此 190 个直接/传递坐标的 OSV 零结果不能代替实际 JAR 扫描。
- 在 `services/platform-api/pom.xml` 管理 `org.mybatis:mybatis:3.6.0`，使用其官方发行包内嵌的 OGNL 3.4.16。直接覆盖 `ognl:ognl` 不能替换已经封装进 MyBatis JAR 的副本。
- 新增 `MyBatisDependencySecurityTests`：使用无副作用的 `String.substring` 反射代理验证拦截，旧版本出现预期失败，新版本通过；另一项验证 MyBatis Plus 动态 SQL 对含 SQL 元字符的输入仍使用绑定参数。修复前 2 项中 1 失败，修复后 2 项全通过。
- 修复后的本地 JAR、最终镜像 SBOM 均确认嵌入 OGNL 3.4.16；最终全级别镜像扫描的 Java 依赖告警为 0，CVE-2026-113730 已消失。完整 MySQL/Redis/S3 回归 1380 项通过。
- 未发现应用接受并执行不可信 OGNL 表达式的入口。该修复消除已知依赖告警，不代表 OGNL 默认上下文可以充当不可信脚本沙箱；上游明确要求由使用方配置访问控制。

依据：[OGNL 上游公告](https://github.com/orphan-oss/ognl/security/advisories/GHSA-crwc-vw2r-qr6m)、[MyBatis 3.6.0 发布记录](https://github.com/mybatis/mybatis-3/releases/tag/mybatis-3.6.0)、[官方 Maven POM](https://repo.maven.apache.org/maven2/org/mybatis/mybatis/3.6.0/mybatis-3.6.0.pom)。

### Debian 基础库剩余风险

API 的 79 条 OS 包/CVE 记录对应 45 个独立 CVE：8 HIGH、47 MEDIUM、23 LOW、1 UNKNOWN，当前 Debian 13 stable/security 数据未给出修复版本。未加入扫描忽略列表，也未混入 Debian unstable 包或删除运行库来消除扫描计数。

- `libexpat1` 的 4 条 HIGH：CVE-2026-113351、CVE-2026-115506、CVE-2026-117610、CVE-2026-118370。Debian 13 的 `2.9.0-1~deb13u1` 仍标为受影响，修复出现在 unstable 的 2.10.0-1。未确认这些原生 XML 解析路径在应用中可由请求触发，仍保留为未消除的组件告警。[Debian Expat 状态](https://security-tracker.debian.org/tracker/CVE-2026-113351)
- `libuuid1` 归属 util-linux 源包，命中 CVE-2026-76642、CVE-2026-78408、CVE-2026-78409、CVE-2026-78410 共 4 条 HIGH。公告涉及 mount/nsenter 的特权行为；实际镜像导出清单确认两种程序及 setuid 文件均不存在，默认部署还使用非 root、去除 capabilities 与禁止提权。当前镜像缺少公告所述触发程序，不能直接将源包匹配视为 4 条可利用提权漏洞；扫描记录仍完整保留。[Debian util-linux 状态](https://security-tracker.debian.org/tracker/CVE-2026-76642)

## 隔离 MySQL/Redis/S3 最终结果

2026-11-11 10:21（Asia/Shanghai），在本轮专用 Docker context `colima-testhub-security-20261110` 完成 MyBatis 升级后的全套后端验收：**1380 项，0 失败、0 错误、0 跳过，BUILD SUCCESS**，Maven 用时 13 分 30 秒；161 个 JUnit XML 报告与控制台汇总一致。11 月 10 日此前的 1378 项全通过已由本次包含新增 2 项回归的结果覆盖。执行命令为：

```bash
DOCKER_CONTEXT=colima-testhub-security-20261110 \
TESTHUB_QA_GOPROXY=https://goproxy.cn,direct \
JAVA_HOME=<JDK 21> node scripts/desktop/test-mysql-backend.mjs
```

MinIO 按仓库固定源码版本构建，Go 模块校验未关闭。第一次在新环境构建因默认 Go 代理连接超时而失败，尚未进入测试；改用可达的模块代理后构建成功。整个过程未将测试指向默认 Colima 的既有数据库。

- 上一轮 H2 条件跳过的 MySQL 审计迁移、MySQL 旧密文兼容迁移、Redis 故障/会话 HTTP 路径已实际执行通过。
- 文件上传、下载、删除与审计（`PlatformFoundationAcceptanceTests` 3 项）、CSV 数据集存储（3 项）、Runner 工件归档及项目下载边界（1 项）、功能用例传输（53 项）均通过，覆盖真实 MinIO/S3 存储路径。
- 桌面专属测试仍按其自身配置使用 H2；“完整套件通过”不表示将桌面存储替换为 MySQL。
- 检查实际容器健康命令，密码变量保留为容器内 `$MYSQL_ROOT_PASSWORD`，日志未再出现 Compose 未定义变量警告。
- 测试脚本退出 0，已自动删除本轮随机 Compose 项目的容器、网络、卷和隔离工作目录；确认该专用环境没有残留运行容器。
- 完整测试通过后执行 `JAVA_HOME=<JDK 21> ./mvnw -pl services/platform-api -am package -DskipTests`，JAR 构建成功；构建步骤跳过重复测试，没有替代上述完整验收。
- 11 月 11 日再次运行 npm 全量审计：548 个依赖，所有级别告警为 0。8 项扫描/磁盘保护脚本回归、针对性 ESLint、Bash 语法与 `git diff --check` 通过。

## 默认 Colima 恢复状态

默认 Colima 同时承载既有服务。用户已批准保留数据重启，实际重启后仍无法建立 SSH/Docker 连接，现已停止。离线快照检查发现根文件系统孤儿 inode 链和位图/空闲计数错误；仅在独立 CoW 副本中恢复 journal 并修复元数据。修复副本的根分区、启动分区再次执行 `e2fsck -fn` 均退出 0，`lost+found` 为空，原有 MySQL/Redis/MinIO 数据卷目录存在。上述结果不能证明业务数据完整，尚未切换默认盘或恢复服务。用户已明确选择“暂不切换，保留副本和原盘”，因此保持默认 Colima 停止，不再启动或切换。

原盘位于 `~/.colima/_lima/colima/diffdisk`；离线快照和修复副本位于 `.testhub/colima-recovery-20261110/default-diffdisk.snapshot`、`default-diffdisk.repaired`，权限均为 0600。两份副本已从 QA VM 卸载并解除 loop 挂载，不随测试环境清理。后续恢复必须先确认服务和业务数据完整性，不能仅凭 fsck 退出 0 宣称恢复成功。

首轮 QA 项目 `testhub-qa-78872c78-ab6b-44cb-b5c5-16783f89e2ee` 的容器、卷和网络删除被 containerd I/O 错误阻止；必须在引擎恢复后仅按该项目标签清理，不能删除用户已有卷。后续完整验收使用本轮独立创建的 `colima-testhub-security-20261110` Docker context。

## 验收收尾

2026-11-11 12:28（Asia/Shanghai），确认专用 QA 环境无残留容器、恢复副本无 loop 挂载后，已删除仅属于本轮的 `testhub-security-20261110` Colima profile 及其容器数据。临时镜像、扫描缓存、测试卷、原始扫描/SBOM 工作目录、Surefire 原始报告和本轮临时日志已清理，只保留本报告、脱敏告警清单、修复代码与回归脚本；本地最终 JAR 保留。主机可用空间恢复至约 29 GiB。

最终 `colima list` 仅显示默认 profile 为 Stopped。原盘、离线快照和修复副本仍存在且权限为 0600；未执行切换、未恢复原有服务，也未尝试启动默认环境来清理首轮 QA 残留。未提交代码、推送镜像或部署服务。
