# 8 条 HIGH 的修复与可利用性复核（2026-11-11）

> 后续已为 Expat 回补 UNKNOWN 修复，并处理系统及 Temurin 的 FreeType。当前镜像版本和 MEDIUM/UNKNOWN 的逐项结果见 [2026-11-12 复核报告](security-medium-risk-validation-2026-11-12.md)。以下保留本轮 HIGH 验收记录。

Expat 的 4 条 HIGH 已通过替换官方修复包处理；util-linux 的 4 条 HIGH 经实际镜像清单核查，受影响组件不在运行时镜像中，结论为“当前镜像不受影响”，不是“libuuid 已升级”。没有增加扫描忽略规则、伪造发行版信息或改低扫描门槛。

本次仅操作独立 Colima `testhub-high-20261111`。默认 Colima 不切换、不启动；原盘、离线快照和修复副本保留。原有 MySQL/Redis/MinIO 业务数据完整性仍未启动验证，本报告不代表这些服务恢复。

收尾已停止并删除本次独立 Colima 及其数据，移除临时源码、软件包、镜像、原始日志和验收工作区；保留脱敏证据及可复用脚本。默认 Colima 最终仍为 Stopped；原盘、快照、修复副本均存在，各为 107374182400 字节、权限 0600。没有创建分支、提交或推送，也未部署到共享服务。

## 最终镜像与扫描

- 镜像：`testhub/platform-api:expat-20261111`，Linux arm64。
- OCI ID：`sha256:f3882118ff9afc3c6f845ba79eae3efcaabaeea574300a62ca28c47ddbd73884`。
- 基础镜像仍为 `gcr.io/distroless/java21-debian13:nonroot@sha256:d978eefd303c59c42f382aef811a0e41f2c68a6e5c319a23769cd3d1d5bf8e3c`。
- Expat 从 `2.9.0-1~deb13u1` 升至 `2.10.0-1`；libuuid 保持 `2.41.5-0+deb13u1`。
- 全级别扫描：2026-11-11 18:33:55（Asia/Shanghai），Trivy 0.65.0；漏洞库更新于 2026-11-10 19:01:23 UTC，Java 索引更新于 2026-11-11 01:07:27 UTC，均为本次新下载且未过期的数据库。
- **原始结果仍为 79 条 OS 包/CVE 记录：8 HIGH、47 MEDIUM、23 LOW、1 UNKNOWN，合计 45 个独立 CVE；CRITICAL 0，Java 依赖 0。** 此次没有重新构建或复扫未变更的 Web 镜像，其上一轮全级别结果为 0。
- 可修复 HIGH/CRITICAL 发布门禁通过；Syft v1.32.0 的 CycloneDX SBOM 正确识别 `libexpat1 2.10.0-1`。已保存[脱敏验证证据 JSON](security-high-risk-evidence-2026-11-11.json)，包括实际镜像 ID、16 次新旧库观测、扫描计数、数据库时间和构件摘要。

Trivy 已正确识别实际安装的 Expat `2.10.0-1`，但 Debian 13 数据中这 4 条仍没有 `FixedVersion`，所以仍报 HIGH。[Debian 官方逐版本状态](https://security-tracker.debian.org/tracker/source-package/expat)明确列出 forky/sid 的 `2.10.0-1` 已修复这些问题，trixie-security 仍为 `2.9.0-1~deb13u1`。修复结论依据真实包、行为对照和官方修复记录，不能将原始扫描计数写成 0。

## 逐项结论

下表的“旧版复现”指受限 QA 容器中的原生库回归，并非已经通过 TestHub HTTP 接口实现攻击。应用的 XLSX 路径使用 POI/JDK XML 解析器；检查未发现用户输入进入原生 Expat 的请求路径。字体功能会加载 fontconfig/Expat，但其配置目录与非 root 用户主目录均为只读；未开放用户指定字体配置文件的业务能力。

| HIGH | 处理结论与实际可利用性依据 | 回归结果 | 保留的缓解措施与后续条件 |
| --- | --- | --- | --- |
| [CVE-2026-113351](https://security-tracker.debian.org/tracker/CVE-2026-113351) | **已修复。** 旧库在大 XML 后复用重置的解析器时，会错误触发分配限制。需能向同一原生解析器持续输入文档；当前未发现该 HTTP 路径。 | 窄/宽库分别输入 72 MiB 属性文档、reset 后解析小文档：旧版均 `XML_ERROR_NO_MEMORY`，2.10.0 均成功。 | 保持字体配置与主目录只读；不开放原生解析器配置或用户 XML 到字体配置的映射。 |
| [CVE-2026-115506](https://security-tracker.debian.org/tracker/CVE-2026-115506) | **已修复。** 嵌套实体放大的拒绝时机过晚，可造成 CPU 消耗。输入大小限制不能单独排除实体放大；需阻断非可信 XML 到 Expat 的路径。 | 有限嵌套实体、低阈值用例：旧版窄/宽库分别先输出 736/352 字符再拒绝；新版都在字符回调前以放大超限错误拒绝。未进行长时间 CPU 耗尽攻击。 | 保持服务内存/PID 限制、只读字体配置；新增 XML/字体能力时重新核查实体解析策略。 |
| [CVE-2026-117610](https://security-tracker.debian.org/tracker/CVE-2026-117610) | **已修复。** 外部实体子解析器第一次收到空的非终结分片，随后处理 PI 可空指针崩溃；需要特定子解析器及分片控制。 | 内存内实体处理器不访问文件/网络：旧版窄/宽库均 SIGSEGV（139）；新版均完成解析与 PI 回调。 | 不将网络流分片/外部实体交由用户控制；仍保持原生库修复，不能仅依赖调用路径暂不可达。 |
| [CVE-2026-118370](https://security-tracker.debian.org/tracker/CVE-2026-118370) | **已修复，依据官方包与修复记录。** `storeAtts()` 超大属性计数溢出可导致越界写；官方描述要求多 GiB XML、超过 2^30 个属性。当前无用户输入到 Expat 的业务路径。 | 已校验官方 2.10.0 包、ABI、实际加载版本及正常 XML；**未执行多 GiB 属性攻击，不声称动态复现此项**。对应[上游修复 PR](https://github.com/libexpat/libexpat/pull/1450)已进入该版本。 | 保持资源限制和输入边界。XLSX 的 5 MiB 上传/500 行限制不是解压后 XML 大小证明，主要依据仍是调用路径隔离及库本身升级。 |
| [CVE-2026-76642](https://github.com/util-linux/util-linux/security/advisories/GHSA-m25x-3hj9-m26f) | **当前镜像不受影响。** 漏洞位于 libmount 外部 mount helper 失败后的特权钩子；镜像没有 mount、helper 或 libmount，仅有 libuuid。 | 最终文件清单/包元数据检查通过，无 mount/libmount、SUID/SGID；libuuid 仅导出 UUID 接口，动态依赖只有 libc/加载器。未执行特权攻击。 | 保持组件缺失、UID 65532、cap-drop ALL、禁止提权、只读根目录。加入挂载工具或改为特权容器须重新评估。 |
| [CVE-2026-78408](https://github.com/util-linux/util-linux/security/advisories/GHSA-55fx-f4gg-cfhj) | **当前镜像不受影响。** 需要 root 调用 `nsenter --join-cgroup` 后泄漏打开的 cgroup 描述符；镜像无 nsenter，无该启动链。 | 最终清单无 nsenter；Java 实测 UID 65532、CapEff=0、NoNewPrivs=1。注意单独降权不能撤销已泄漏的 FD，本结论依赖无 nsenter 启动路径。 | 保持部署模板的普通容器启动方式，不通过宿主 root nsenter 启动应用；不挂载宿主 cgroup/PID 命名空间或 Docker socket。 |
| [CVE-2026-78409](https://github.com/util-linux/util-linux/security/advisories/GHSA-8f2p-47x3-43mv) | **当前镜像不受影响。** 涉及 libmount 的 `X-mount.subdir` detached-tree 路径及中间符号链接，需要相应挂载调用和内核条件；libuuid 不提供该代码。 | 最终清单无 libmount/mount，无特权或 SUID 入口；没有依靠某个宿主内核版本“碰巧不触发”来判定安全。 | 不新增 user/fstab 挂载入口、CAP_SYS_ADMIN 或特权模式；新增挂载能力时使用已修复 util-linux 并重新回归。 |
| [CVE-2026-78410](https://github.com/util-linux/util-linux/security/advisories/GHSA-rh77-686x-2f2m) | **当前镜像不受影响。** 需要受限 bind mount 的路径竞争及 root chown/chmod 后置钩子；镜像无对应 mount/libmount 代码。 | 最终清单与运行权限检查通过，无受影响组件、SUID/SGID 文件或有效 capabilities。 | 保持只读文件系统与组件缺失；不向应用添加可修改的 fstab 绑定源或特权挂载钩子。 |

util-linux 上游公告给出的修复分支版本为 2.41.6/2.42.3，但 [Debian 13 当前包](https://security-tracker.debian.org/tracker/source-package/util-linux)仍为 2.41.5。本次没有为了消除源包级告警而替换不含这些漏洞代码的 libuuid。此“不受影响”结论只覆盖上述最终应用镜像及既有部署边界，不覆盖宿主机或其他服务镜像。

## 兼容性、实现和验证

修复位于 `services/platform-api/Dockerfile` 和 `scripts/security/prepare-expat-runtime.sh`。仅覆盖官方 `libexpat1` 包，保留 Distroless、Java、glibc 和非 root 用户；不添加 sid 软件源。下载只允许 HTTPS，按架构校验 SHA-256、包名、版本和架构；校验失败立即停止。临时 BusyBox 只通过构建挂载移除旧库，不进入最终运行时。旧 `1.13.0` 文件确实不存在，新的 `1.13.1` 文件与官方包 md5 清单一致；包身份的安全校验使用 SHA-256。

官方包来源及固定摘要见脚本；[arm64 下载页](https://packages.debian.org/sid/arm64/libexpat1/download)给出 `85b512bb1342e2321c468d89476beba4b2077d75b3fb48348334408679e408d5`，[amd64 下载页](https://packages.debian.org/sid/amd64/libexpat1/download)给出 `9a333cf590099569f577c1a1d47ac1f439eab14c1faff09b7e81ce3802938003`。脚本也列出 s390x/ppc64el/riscv64 官方摘要，但**本机运行验证范围仅为 Linux arm64**；其他架构需在对应构建环境执行同一门禁，不能视为本轮已验证。

| 验证 | 结果与边界 |
| --- | --- |
| ABI/依赖 | 窄/宽库 SONAME 均保持 `.so.1`；旧导出符号全部保留；新库最高 GLIBC 符号需求 2.38，镜像为 2.41。 |
| 最终镜像原生回归 | 2 种字符库 × 4 用例，**8/8 通过**；旧基础镜像对照为 2 个正常解析成功、6 个漏洞用例按预期失败，证明回归可区分旧库与修复库。每个进程独立容器，30 秒上限、768 MiB、单 CPU、无网络、禁止 core dump。 |
| 上游源码测试 | 官方 Expat 2.10.0 tarball（SHA-256 `c624ba2998fc65df1163a49641ff3f9ab73138b11641d92c86bee0fb01b1bc7f`）默认窄字符 CTest runner：**5328 checks，0 failed**。此项编译源码测试，不代替实际包回归；宽字符官方包由上述实际库回归覆盖，未宣称跑过上游完整宽字符套件。 |
| 最终镜像 Java/字体/XLSX | 实际 JAR 中的 POI 依赖，中文/换行/数值 XLSX 往返通过；JDK DOM/SAX 为 Xerces，XLSX 检查点无原生 Expat 映射；字体枚举、宽度计算、渲染通过，LD_DEBUG 确认 fontconfig 和 Expat 初始化。OpenJDK 会随后卸载这些库，故不以最后的 maps 快照代替加载证据。 |
| 业务 XLSX 回归 | `FunctionalExchangeRoundTripTests`：**6 tests，0 failure/error/skip**，临时 H2。覆盖跨项目导入导出、类型兼容、非法数据不部分写入和权限边界。 |
| 构建/安装与扫描脚本 | 正式 Dockerfile 构建成功；`node --test scripts/security/*.test.mjs` **7/7 通过**，包含不支持架构、篡改下载、下载失败时均不得进入解包。 |
| 静态检查与发布门禁 | 新增 JS 测试文件 ESLint、Python 编译检查、全部安全脚本 Bash 语法、`git diff --check` 均通过；新增原生验收的默认发布模式再次通过，扫描门禁与 SBOM 生成成功。 |
| 最终应用启动 | 同一最终镜像在临时 H2/tmpfs、只读根目录、cap-drop ALL、禁止提权条件下 `/actuator/health/readiness` 为 **UP**；未访问默认 MySQL/Redis/MinIO。 |
| 前一轮基础设施验收 | Expat 之前的同一应用代码/MyBatis 修复已有独立 MySQL/Redis/S3 **1380/1380 通过**，见[前一轮报告](security-infrastructure-validation-2026-11-10.md)。本轮没有重跑全量数据库套件，新增验证集中于原生库、XLSX 和最终镜像。 |

当前镜像验收使用独立 Docker context 和 JDK 21。基线镜像用于包清单与 ABI 比较，Expat 兼容性探针运行在修复后的镜像上。表中的旧库对照结果来自当轮本地验收。

```bash
docker build -f services/platform-api/Dockerfile -t testhub/platform-api:expat-qa .
python3 scripts/security/verify-native-image.py testhub/platform-api:expat-qa \
  --baseline-image gcr.io/distroless/java21-debian13:nonroot@sha256:d978eefd303c59c42f382aef811a0e41f2c68a6e5c319a23769cd3d1d5bf8e3c
node --test scripts/security/*.test.mjs
./mvnw -q -pl services/platform-api -am test \
  -Dtest=FunctionalExchangeRoundTripTests -Dsurefire.failIfNoSpecifiedTests=false
bash scripts/security/scan-image.sh testhub/platform-api:expat-qa
```

发布门禁现在自动执行 `verify-native-image.py`，并保存原生验收 JSON；升级基础镜像时必须重新通过这些行为与文件清单检查。CI 已配置 JDK 21；本轮未运行远端 GitHub Actions。

## 剩余风险与后续措施

- 原始扫描仍为 8 HIGH，必须随发行材料附带本报告的逐项结论，不能单用 `--ignore-unfixed` 门禁通过证明无漏洞。此次保留原始告警，不增加 `.trivyignore` 或 VEX 排除规则。
- Expat 2.10.0 仍有 [CVE-2025-66382（MEDIUM）](https://security-tracker.debian.org/tracker/CVE-2025-66382)与 [CVE-2026-124317（UNKNOWN）](https://security-tracker.debian.org/tracker/CVE-2026-124317)等剩余记录；其他基础库告警见[上一轮全清单](security-image-findings-2026-11-11.md)。本轮未将“8 条 HIGH 已逐项处理”等同于所有级别无风险。
- 保持当前 Compose/Helm 的只读根目录、非 root、cap-drop ALL、禁止提权及资源限制；不要把字体配置目录、用户主目录、FONTCONFIG_FILE/FONTCONFIG_PATH 或 XDG_CONFIG_HOME 交给不可信请求控制。新增 JNI、XML 或字体功能时重新审查可达路径。
- 等待 Debian 13/Distroless 提供正式 Expat 修复后更新基础镜像并删除临时覆盖层；不永久停留在 sid 单包覆盖方式。包下载失效、基础包版本变化或 ABI 回归必须阻断构建，不能放宽摘要校验。
- 默认 Colima 的原盘和修复副本继续保留，业务数据恢复仍按用户后续明确授权处理。
