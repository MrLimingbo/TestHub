# 发布供应链与密钥轮换

阶段 10 的发布门禁不会在控制面运行时下载扫描数据库、构建镜像或处理原始密钥；这些动作只在本地受控运维环境和 CI 中执行。

## 本地或 CI 发布门禁

在完成应用测试后执行：

```bash
chmod 700 scripts/security/*.sh
TESTHUB_SECURITY_ARTIFACT_DIR=artifacts/security \
  scripts/security/release-gate.sh
```

门禁会检查生产 Compose 配置、构建 Platform API 与 Web 镜像、验收 API 原生库与 Java 调用路径、为两者生成 CycloneDX JSON SBOM，并用 Trivy 阻断可修复的 `HIGH` / `CRITICAL` 漏洞。需要本机 Python 3、JDK 21（`JAVA_HOME` 或 `javac`）和 Docker。SBOM 与原生验收 JSON 位于 `artifacts/security/`，应作为发行版本的不可变构件归档。

单独使用：

```bash
scripts/security/generate-sbom.sh testhub/platform-api:0.1.0 artifacts/platform-api.cdx.json
scripts/security/scan-image.sh testhub/platform-api:0.1.0
python3 scripts/security/verify-native-image.py testhub/platform-api:0.1.0
```

要用实际 API 镜像验收 MySQL、Redis 和 S3，先在专用 QA Docker context 准备 API、MySQL、Redis 和固定源码构建的 MinIO 镜像，再运行 `python3 scripts/security/verify-platform-image.py --context "$QA_DOCKER_CONTEXT" "$QA_PLATFORM_IMAGE"`。脚本使用生产 Compose 及临时覆盖配置，验证 MySQL 元数据和审计、Redis 会话跨 API 重启以及对象字节和删除，并清理自己创建的服务和卷。完整 Maven 后端套件使用独立的 `scripts/desktop/test-mysql-backend.mjs`。

原生验收从实际镜像导出文件清单及应用 JAR，检查 Expat 包内文件校验、旧库移除、非 root 用户以及 mount/nsenter/libmount、Shell 和 SUID/SGID 文件缺失；随后在无网络、只读根文件系统、无 capabilities、禁止提权的容器中运行窄/宽字符库回归、XLSX 往返和字体加载/渲染。临时目录与容器会自动清理；辅助编译镜像只用于 QA。可用 `--baseline-image <旧版固定摘要镜像>` 增加旧库负向对照和 ABI 符号比较。不要把默认 Colima 或共享服务作为验收环境。

API 原生库覆盖层使用固定摘要的 Expat 2.10.0 源码与已合并的 CVE-2026-124317 补丁，生成 `libexpat1 2.10.0-1+testhub1`。FreeType 使用固定摘要的 Debian 官方 `libfreetype6 2.14.3+dfsg-3`，Temurin 的字体库入口链接同一修复库。构建校验补丁、包身份及 ABI，原生验收检查 AWT 的实际内存映射。基础包版本变化会让构建失败，要求重新审查覆盖层。具体版本、26 个 MEDIUM/UNKNOWN 的可利用性及回归结果见 [原生库复核报告](security-medium-risk-validation-2026-11-12.md)。未来 Debian 13/Distroless/Temurin 提供相应正式修复后，应更新基础镜像摘要、移除临时覆盖层，再完整执行原生验收和镜像扫描。

追加修复将 GCC 运行库三件套固定为 Debian 官方 `16.2.0-4`，构建时检查旧导出符号、SONAME 和 GLIBC 需求。镜像通过 `/app/testhub-runtime-guard` 启动 JVM，在进入解析器前校验 DNS 搜索列表并固定到 LOCALDOMAIN，同时约束 OS locale 和字体配置路径。搜索列表规范化后最多 255 字节，错误配置以 78 退出。Compose 可通过 LOCALDOMAIN、Helm 可通过 `api.dnsSearchDomains` 提供显式覆盖。Helm 在渲染阶段拒绝多行或超长的覆盖值，运行时守卫也校验继承自 Pod 的搜索列表。搜索域变更需要重启 API。原生门禁覆盖服务别名、命名空间后缀和配置重载。 [19 项条件性风险台账](security-conditional-risk-validation-2026-11-12.md)

扫描器镜像使用固定版本；升级扫描器、基础镜像或漏洞阈值应通过常规变更评审。`--ignore-unfixed` 只忽略上游没有可用修复版本的问题，不能替代风险评估和基础镜像升级。

Linux amd64、arm64、s390x、ppc64le、riscv64 的 `libstdc++6` 使用同一份 CVE-2026-125709 上游回补，包版本为 `16.2.0-4+testhub1`。构建使用固定摘要的 Debian 源包和补丁序列，逐项比对原包动态导出、动态依赖、ELF flags、GNU 属性和架构属性。arm64 检查 BTI/PAC/GCS，amd64 检查文件流入口的 CET 指令。原生门禁在新旧两套 C++ 字符串 ABI 下验证窄、宽字符文件缓冲、线程和 long double，并在 ppc64le 加测 IBM long double ABI。 [arm64 回补报告](security-gcc-filebuf-backport-2026-11-17.md) · [amd64 核查](security-gcc-filebuf-amd64-2026-11-17.md) · [其余架构与 Snappy 核查](security-gcc-filebuf-ports-2026-11-17.md)

同五架构的 `libc6 2.41-12+deb13u4+testhub1` 包含 CVE-2026-8674 的上游 DNS 回补。生产构建校验源包和补丁摘要，并与固定镜像中原版 libc 和加载器对照 ABI、架构属性和安全属性。发布原生验收验证实际镜像的包身份、补丁提交和完整 `libc6` 文件清单。现有运行时 DNS 守卫与 Helm 校验继续生效。 [glibc 回补、实际加载及隔离验收](security-glibc-dns-ports-2026-11-18.md)

安全验收还应保存一次不带 `--severity` 和 `--ignore-unfixed` 的 Trivy JSON 结果，核对低于门禁阈值及当前发行版尚无修复的条目，区分“门禁通过”和“全部级别零告警”。应扫描最终镜像中的实际 JAR：例如 MyBatis 内嵌并重定位的 OGNL 不会作为独立依赖出现在普通 Maven 依赖树中，单独覆盖 `ognl:ognl` 也不能替换该副本。发现此类问题时升级封装它的上游组件，并补充实际嵌入版本的行为回归。

Trivy 的备用数据库源只针对部分错误（如 HTTP 429/5xx）自动切换；网络拒绝连接可能直接终止。受限网络可用 `TESTHUB_TRIVY_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-db:2` 和 `TESTHUB_TRIVY_JAVA_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-java-db:1` 显式选择可达的官方源，仍会下载并使用有效漏洞数据库。需要代理时设置 `TESTHUB_SECURITY_HTTPS_PROXY` 为**容器内可达**的代理地址；例如 Colima/Lima 可用 `host.lima.internal` 访问宿主机代理，不能直接照搬宿主机的 `127.0.0.1`。代理值只通过环境传递，不写入命令参数或报告。详见 [Trivy 数据库配置](https://trivy.dev/docs/dev/configuration/db/) 和 [Lima 网络说明](https://lima-vm.io/docs/config/network/user/)。

扫描脚本的本地回归：`node --test scripts/security/scan-image.test.mjs`，覆盖默认源、显式源/代理和失败退出码。

首次下载 Java 索引或在慢速网络扫描时，可设置 `TESTHUB_TRIVY_TIMEOUT=20m` 调整 Trivy 的总时限；默认保持 5 分钟。超时或数据库下载失败仍会使门禁失败。

GitHub Actions 中 `web`、`platform-api` 与 `release-gate` 三项检查应设置为目标分支的必需状态；`release-gate` 会上传 SBOM 产物。分支保护规则属于仓库管理员的外部配置，不由代码仓库静默修改。

## 数据加密密钥轮换

新写入的密文格式为 `v2:<key-id>:<payload>`。旧 `v1` 密文在轮换期间仍可解密，完成重加密后会被转换到当前 v2 主密钥。密钥 ID 不属于敏感数据；Base64 AES 密钥本身绝不能写进仓库、工单、审计详情或命令行日志。

轮换采用两阶段流程：

1. 完成经过验证的备份，并建立维护窗口。生成新 AES 密钥和新的 `TESTHUB_ENCRYPTION_KEY_ID`。
2. 在所有 API 副本上部署新主密钥，同时把旧密钥作为 `TESTHUB_PREVIOUS_ENCRYPTION_KEYS=old-id:Base64OldKey` 配置；保持 `TESTHUB_SECRET_REKEY_ENABLED=false`，确认所有副本能读取业务密文。
3. 在维护窗口启用 `TESTHUB_SECRET_REKEY_ENABLED=true`，确保只有一个控制面写入者。系统管理员调用受保护的 `POST /api/v1/operations/secret-rekey`，请求体只包含固定确认文本 `REKEY_PERSISTED_SECRETS`，不会传输或返回任何密钥值。
4. 核对审计事件 `PERSISTED_SECRETS_REKEYED`、运维页面的密钥环状态、解密相关业务冒烟测试和备份。该操作在一个数据库事务中更新项目环境密钥、自动化 Webhook、通知签名、投递快照和企业适配器配置。
5. 关闭重加密窗口，移除 `TESTHUB_PREVIOUS_ENCRYPTION_KEYS`，滚动重启所有 API 副本，并再次验证密钥读取。

重加密在以下条件下会拒绝执行：窗口未开启、调用者不是系统管理员、确认文本不匹配、没有旧密钥回退配置，或任意现存密文无法解密。遇到失败应保持 API 停止并从备份恢复或修正密钥环，不要移除旧密钥。
