# 剩余 19 项条件性风险追加修复（2026-11-12，2026-11-17 复验）

本轮对前一轮保留的 19 个独立 CVE 逐项处理。1 项运行库问题已修复，5 项增加了执行时缓解，13 项保留条件性风险及后续回补要求。DNS 搜索域校验、进程环境固定和真实 GCC 运行库升级均已写入正式镜像构建，发布门禁增加相应回归。完整观测见[脱敏证据 JSON](security-conditional-risk-evidence-2026-11-12.json)。

当前状态见 [2026-11-18 上游与发布产物复核](#2026-11-18-上游与发布产物复核)及 [glibc DNS 回补与 Temurin 调试证据](security-glibc-dns-ports-2026-11-18.md)。CVE-2026-125709 是后续新增的独立项，已回补到五个 Linux 架构。 [共享库复验](security-gcc-filebuf-ports-2026-11-17.md) · [Snappy 静态链接复现](security-snappy-static-reproduction-2026-11-18.md)

最终复核将 CVE-2026-8674 更新为已回补，原 19 项现为 2 项已修复、4 项已落实运行缓解、13 项保留条件性风险。四项 GCC 模板均在五架构匹配的 Temurin 符号和 DWARF 中未见相关实现。条件性跟踪适用于新增或重新构建的原生依赖。 [最终处理结论](security-glibc-dns-ports-2026-11-18.md)

11 月 12 日首轮镜像为 `testhub/platform-api:conditional-final`，Linux arm64，镜像 ID 为 `sha256:e5913ab9f6172be41fef1a7ff0b299e37d1c379a193a575a0d96f487c80b14e7`。本轮使用独立 Colima `testhub-conditional-20261112`。默认 Colima 维持 Stopped，原盘、快照及修复副本保留。

DNS 缓解位于 `services/platform-api/runtime-guard.c`。该启动器在 JVM 启动前执行，先使用 LOCALDOMAIN，否则读取当前 resolv.conf 中最后一条非空且从行首开始的 domain/search 指令，保持正常搜索顺序和环境覆盖关系。列表按空白规范化后限为 255 字节，为 glibc 的 256 字节 defdname 留出终止字节；依据来自[上游修复说明](https://github.com/bminor/glibc/commit/506ea57086bfb9ce3daff1c14246a1cb532aba0a)。超长或多行环境配置会以 78 退出并给出修正 LOCALDOMAIN 的提示。正常列表在 exec JVM 前写入进程环境，后续文件重载保留已校验的搜索域；nameserver 仍由 resolv.conf 提供。修改搜索域后需要重启 API。启动时被注入无效配置仍会阻止启动，需要运维修正或提供短列表。

Compose 新增可选 LOCALDOMAIN 透传，未配置时保持有效环境中的缺省状态。实际 Compose 容器已验证仍继承测试 resolver 搜索域。Helm 新增可选 `api.dnsSearchDomains`，留空时沿用 Pod 原有配置，`.` 可选择根后缀。两种入口都使用镜像启动器；自定义 entrypoint 的部署应保留该入口。解析语义参考 [resolv.conf 文档](https://man7.org/linux/man-pages/man5/resolv.conf.5.html)和 [Compose 环境变量规则](https://docs.docker.com/reference/compose-file/services/#environment)。

启动器同时固定 LC_ALL 为 C.UTF-8、清除 LANGUAGE，并将 FONTCONFIG_FILE/PATH、HOME 和 XDG_CONFIG_HOME 指向镜像内的只读字体配置。Java/AWT、XLSX 与正常 API 启动验收通过。C.UTF-8 约束收紧 gettext 和平台编码入口；显式原生 iconv 调用及 Expat 底层缺陷继续按下表跟踪。

C++ 支持库使用固定 SHA-256 的 Debian 官方 gcc-16-base、libgcc-s1、libstdc++6 三件套 `16.2.0-4`。原 gcc-14-base 数据与旧共享库已移除，包元数据和文件清单与实际内容一致。构建要求旧导出符号及符号版本全部保留、SONAME 相同，新增 GLIBC 需求不高于现有 2.41。[上游 ABI 策略](https://gcc.gnu.org/onlinedocs/libstdc++/manual/abi.html)和 [CVE-2026-95619 修复状态](https://security-tracker.debian.org/tracker/CVE-2026-95619)支持该方案。当前 JDK 和 glibc 固定版本继续使用，编译期模板问题仍保留调用方构建边界。

| CVE | 本轮结果 | 依据与验证 | 缓解或后续 |
| --- | --- | --- | --- |
| [CVE-2026-8674](https://security-tracker.debian.org/tracker/CVE-2026-8674) | 已落实启动和重载缓解，glibc 库待回补 | 新启动器在 JVM 前读取有效搜索列表，规范化后最多 255 字节，超限或多行 LOCALDOMAIN 以 78 退出。校验通过后固定 LOCALDOMAIN，保留已有顺序。服务别名和搜索后缀解析、初始化后合法配置重载测试通过。11 月 17 日复验补齐了空指令、缩进指令的忽略规则。 | 需要修改搜索域时配置 LOCALDOMAIN 或 Helm api.dnsSearchDomains，并重启 API。启动时无效配置仍须修正，nameserver 配置继续由 resolv.conf 提供。 |
| [CVE-2025-66382](https://security-tracker.debian.org/tracker/CVE-2025-66382) | 已落实执行环境缓解，库待上游修复 | 启动器固定 FONTCONFIG_FILE/PATH、HOME、XDG_CONFIG_HOME 到镜像内只读配置路径。当前无业务字体配置导入入口，上游 issue 1076 仍开放。 | 保持 /etc/fonts 和 /home/nonroot 可信、只读。后续采用包含正式修复的 Expat 版本。 |
| [CVE-2026-77117](https://security-tracker.debian.org/tracker/CVE-2026-77117) | 环境入口已收紧，显式 iconv 场景仍待回补 | 启动器将 OS locale 固定为 C.UTF-8，避免平台编码从环境切换到受影响格式。镜像仍保留对应 gconv 模块，显式 iconv_open 的风险继续跟踪，当前未发现业务输入到该原生接口的可控调用链。 | 后续采用匹配 glibc 转换模块回补，新增显式原生转换时限制编码、输入和无进展重试。 |
| [CVE-2026-80489](https://security-tracker.debian.org/tracker/CVE-2026-80489) | 环境入口已收紧，显式 iconv 场景仍待回补 | 启动器将 OS locale 固定为 C.UTF-8，避免平台编码从环境切换到受影响格式。镜像仍保留对应 gconv 模块，显式 iconv_open 的风险继续跟踪，当前未发现业务输入到该原生接口的可控调用链。 | 后续采用匹配 glibc 转换模块回补，新增显式原生转换时限制编码、输入和无进展重试。 |
| [CVE-2026-84243](https://security-tracker.debian.org/tracker/CVE-2026-84243) | 已落实执行环境缓解，glibc 库待回补 | 启动器在 JVM 前强制 LC_ALL=C.UTF-8 并清除 LANGUAGE。镜像仅有 C.utf8，未携带 .mo 目录，已有 locale/字体兼容性回归通过。 | 保留启动器和只读配置约束。新增 locale、消息目录或自定义启动入口时重新验证。 |
| [CVE-2026-95619](https://security-tracker.debian.org/tracker/CVE-2026-95619) | 已修复运行库 | libstdc++6/libgcc-s1/gcc-16-base 同步更新至官方 16.2.0-4。固定 SHA-256，旧符号及符号版本全部保留，SONAME 不变，GLIBC 需求不高于现有 2.41。正常对齐分配、异常展开、线程/容器回归通过。 | 跟随匹配的基础镜像更新，保留包身份、ABI 和实际运行回归。该结论针对运行库中的对齐 operator new。 |
| [CVE-2026-18374](https://security-tracker.debian.org/tracker/CVE-2026-18374) | 未发现可控业务路径，继续等待匹配回补 | fopen 的 ,ccs= 模式由攻击者控制。JDK/原生库使用文件 API，但未发现业务控制原生 mode 参数。 | 避免把请求值拼入原生文件打开模式，跟随匹配的 glibc 修复。 |
| [CVE-2026-19499](https://security-tracker.debian.org/tracker/CVE-2026-19499) | 未发现可控业务路径，继续等待匹配回补 | strfmon/strfmon_l 的宽度填充。325 个 ELF 的动态导入检查未发现该接口，Java 金额格式化使用 Java API。 | 新增原生货币格式化时审查输出容量与格式来源，跟随匹配的 glibc 修复。 |
| [CVE-2026-19542](https://security-tracker.debian.org/tracker/CVE-2026-19542) | 未发现可控业务路径，继续等待匹配回补 | tdelete 的大规模树操作。动态导入未发现 tdelete，Java 集合使用独立实现。 | 新增原生树结构时限制规模并使用修复版本，跟随匹配的 glibc 修复。 |
| [CVE-2026-6368](https://security-tracker.debian.org/tracker/CVE-2026-6368) | 未发现可控业务路径，继续等待匹配回补 | wordexp 加 WRDE_APPEND。动态导入未发现 wordexp，镜像也无 Shell。 | 保持参数数组和独立执行边界，避免添加 Shell 展开，跟随匹配的 glibc 修复。 |
| [CVE-2026-6791](https://security-tracker.debian.org/tracker/CVE-2026-6791) | 未发现可控业务路径，继续等待匹配回补 | wordexp 的超长波浪号用户名展开。动态导入未发现 wordexp，未发现业务原生展开路径。 | 限制展开输入并避免将请求交给 wordexp，跟随匹配的 glibc 修复。 |
| [CVE-2026-5435](https://security-tracker.debian.org/tracker/CVE-2026-5435) | 未发现可控业务路径，继续等待匹配回补 | 弃用的 DNS 记录打印接口写出。动态导入未发现 ns_printrr/ns_printrrf/fp_nquery，普通 DNS 解析不使用这些调试接口。 | 保持诊断代码使用受支持接口，避免引入这些打印函数，跟随匹配的 glibc 修复。 |
| [CVE-2026-6238](https://security-tracker.debian.org/tracker/CVE-2026-6238) | 未发现可控业务路径，继续等待匹配回补 | 弃用的 DNS 记录打印接口读取。同类打印接口缺少业务调用证据，DNS getaddrinfo 的存在不能证明该问题可达。 | 新增 DNS 诊断功能时使用长度安全的解析和格式化实现，跟随匹配的 glibc 修复。 |
| [CVE-2026-100290](https://security-tracker.debian.org/tracker/CVE-2026-100290) | 未发现可控业务路径，继续等待匹配回补 | POSIX regcomp/regexec 在特定分配失败下清理出错。原生导入未发现这两项，业务 Pattern 是 Java 正则，间接或内部调用仍有分析边界。 | 保持内存限额并监控 OOM，新增 POSIX 正则调用时重新审查，跟随匹配的 glibc 修复。 |
| [CVE-2026-115819](https://security-tracker.debian.org/tracker/CVE-2026-115819) | 未发现可控业务路径，继续等待匹配回补 | 宽 scanf 向窄字符数组输出的终止字节。导入可见的是窄 sscanf/fscanf，未发现受影响的宽 scanf 调用。 | 避免宽 scanf 向窄数组输出，保持目标容量与格式校验，跟随匹配的 glibc 修复。 |
| [CVE-2026-102010](https://security-tracker.debian.org/tracker/CVE-2026-102010) | GCC 16 的头文件已修复，既有编译产物待确认 | PBDS 二叉堆的 erase_if 模板可编入调用方，更新共享库后现有 JDK 二进制仍需独立确认。当前未发现业务调用路径。 | 确认 JDK 构建工具链包含修复，新增 C++ 模块采用修复后的头文件。 |
| [CVE-2026-114690](https://security-tracker.debian.org/tracker/CVE-2026-114690) | 保留条件性风险 | 涉及并行搜索模板，Debian GCC 16 仍记录为易受影响。当前未发现业务调用路径，共享库更新无法覆盖已编入调用方的模板。 | 新增原生模块时复核并行搜索调用，跟随修复后的工具链和 JDK。 |
| [CVE-2026-114691](https://security-tracker.debian.org/tracker/CVE-2026-114691) | 保留条件性风险 | 涉及vector<bool> 的元素计数，Debian GCC 16 仍记录为易受影响。当前未发现业务调用路径，共享库更新无法覆盖已编入调用方的模板。 | 新增原生模块时校验容器容量，跟随修复后的工具链和 JDK。 |
| [CVE-2026-115455](https://security-tracker.debian.org/tracker/CVE-2026-115455) | 保留条件性风险 | 涉及宽字符流到 bitset 的提取模板，Debian GCC 16 仍记录为易受影响。当前未发现业务调用路径，共享库更新无法覆盖已编入调用方的模板。 | 避免通过该接口处理不可信宽字符流，跟随修复后的工具链和 JDK。 |

同一漏洞库的前后对照从 **83 条记录降至 65 条**，MEDIUM **47→32**、LOW **23→20**，HIGH 保持 **8**、UNKNOWN 保持 **5**，CRITICAL 为 **0**。独立 CVE 从 **47→41**，Java 依赖记录为 **0**。首轮复扫于 2026-11-12 15:52:31（Asia/Shanghai）完成，Trivy 0.65.0，漏洞库更新时间为 2026-11-12 07:01:33 UTC，Java 索引为 2026-11-12 00:58:04 UTC。原始扫描保留全部严重级别和未修复项，现有可修复 HIGH/CRITICAL 发布门禁通过。

GCC 记录的减少伴随源包从 gcc-14 变更为 gcc-16，Debian 13 的匹配范围也随之改变。[GCC 16 官方记录](https://security-tracker.debian.org/tracker/source-package/gcc-16)仍列有部分模板问题，所以保留 4 个模板 CVE 的项目风险记录。CVE-2026-95619 的修复结论依据真实运行库版本、官方修复状态和兼容性验证。DNS、locale、字体路径约束属于运行时缓解，其底层库记录仍存在于扫描中。

| 验证 | 结果 |
| --- | --- |
| 原生库与执行环境 | 21 次调用通过，含既有 Expat/FreeType 回归、C++ 对齐分配/异常展开/线程及启动环境检查；解析器内部 7 项检查通过 |
| DNS 配置边界 | 7 个场景通过，覆盖环境优先级、空值、最后指令、超长文件/环境、多行拒绝；均在进入 glibc DNS 解析前完成校验 |
| DNS 与服务别名 | 3 组通过，覆盖 mysql/redis/minio 短名、命名空间搜索后缀和已初始化解析器后的合法配置重载 |
| 部署配置 | Compose 默认继承和显式覆盖测试通过，实际容器继承测试通过；Helm lint、默认及显式 DNS 值渲染通过 |
| 业务与启动 | 临时 H2 的 XLSX 6/6 通过；正式镜像默认 entrypoint 下 API readiness 为 UP |
| 安装/扫描脚本 | 16/16 通过，包含新增 GCC 包身份/摘要失败路径和 DNS 配置传递 |
| 静态检查 | 变更 JS 测试 ESLint、Python 编译检查、Bash 语法及 git diff --check 通过 |
| 供应链 | CycloneDX SBOM 确认三件套 16.2.0-4，实际包文件清单校验通过，全级别扫描和发布门禁通过 |

DNS 网络验收使用独立 Docker 内部网络中的 HTTP 夹具，验证指定服务名与搜索后缀的 DNS/TCP 行为。当前未运行实际 Kubernetes 集群，也未重跑前轮 MySQL/Redis/S3 的 1380 项完整协议验收。此次新增运行范围为 Linux arm64；C++ 用例使用正常分配与线程操作，未进行内存破坏攻击复现。

生产配置示例可使用以下覆盖方式。应用会验证实际值，保持列表总长在上述容量内。未设置覆盖时继承现有搜索列表。

```bash
LOCALDOMAIN='team.example.test example.test' docker compose --env-file .env.production -f compose.production.yaml up -d api
helm upgrade --install testhub infra/helm/testhub --namespace testhub \
  --values deployment-values.yaml \
  --set-string 'api.dnsSearchDomains=testhub.svc.cluster.local svc.cluster.local cluster.local'
```

这些命令供后续部署评审使用，本轮仅修改源码和隔离验收环境。需保留当前非 root、只读根目录、cap-drop ALL、禁止提权及资源限制，并持续跟进 Debian 13/Temurin 的匹配安全更新。若采用自定义启动命令，应经由 `/app/testhub-runtime-guard` 启动。

## 2026-11-17 兼容性复验与最终复扫

启动器现在忽略空 domain/search 指令和缩进指令，与镜像中 glibc 2.41 的解析行为一致。新增用例在首轮镜像中观察到有效搜索域被错误覆盖，在重建后的最终镜像中通过。Debian 13 的 glibc 2.41-12+deb13u4 仍受影响，生产应继续使用镜像启动器，并在修改合法搜索列表后重启 API。[glibc 2.41 解析实现](https://raw.githubusercontent.com/bminor/glibc/glibc-2.41/resolv/res_init.c) · [Debian CVE-2026-8674 状态](https://security-tracker.debian.org/tracker/CVE-2026-8674)

复验镜像为 `testhub/platform-api:conditional-final`，Linux arm64，镜像 ID 为 `sha256:edaba7e8f2582f1e49c95c51f819321ce1f085e39b71116f57c0619f1087152c`。原生和执行环境 22 次调用通过，含 8 个 DNS 配置场景。3 个 DNS/TCP 场景全部通过，覆盖服务短名、命名空间搜索后缀和解析器初始化后的配置重载。实际 Compose 容器的继承与显式覆盖均通过，Helm 默认及覆盖配置通过 lint 与渲染。默认镜像入口使用临时 H2 启动，readiness 为 UP。安全脚本测试 16/16 通过。复验范围为 Linux arm64 的隔离容器与 Helm 模板渲染。

2026-11-17 10:08（Asia/Shanghai）使用 Trivy 0.65.0 完成复扫。漏洞库和 Java 索引均更新于 11 月 17 日，UTC 时间分别为 01:05:25 和 00:59:10。使用同一漏洞库复扫 GCC 14 基线和最终镜像，发布门禁通过。

| 计数 | GCC 14 基线 | 最终镜像 |
| --- | ---: | ---: |
| 全部记录 | 87 | 66 |
| CRITICAL | 0 | 0 |
| HIGH | 8 | 8 |
| MEDIUM | 50 | 32 |
| LOW | 23 | 20 |
| UNKNOWN | 6 | 6 |
| 独立 CVE | 49 | 42 |
| Java 依赖命中 | 0 | 0 |
| 有可用修复的 HIGH/CRITICAL | 0 | 0 |

原有 19 项仍为 1 项已修复、5 项已落实缓解、13 项保留条件性风险。脱敏证据 JSON 记录了每项 CVE 在复扫中的包命中、验证结果、扫描库时间和产物摘要。扫描库更新后应继续跟踪以下两项。

| CVE | 复扫观察 | 依据和措施 |
| --- | --- | --- |
| CVE-2026-128241 | 最终镜像新增 1 条 UNKNOWN，归在 Expat 源包 | 受影响组件是 `xmlwf`。最终镜像清单确认没有打包该 CLI，发布回归已加入缺失检查，继续只打包 Expat 共享库。[Debian 安全记录](https://security-tracker.debian.org/tracker/CVE-2026-128241) |
| CVE-2026-125709 | GCC 14 基线新增 3 条 MEDIUM，最终镜像无匹配记录 | Debian 仍标记 GCC 16.2.0-4 受影响。检查最终镜像的 333 个 ELF 文件，未发现外部 C++ 文件流动态导入。检查无法覆盖静态和库内调用。新增原生写入路径应检查写入失败，后续采用修复包。[Debian 安全记录](https://security-tracker.debian.org/tracker/CVE-2026-125709) |

隔离 Colima、测试容器和数据卷已清理，临时验收文件已移至废纸篓。默认 Colima 保持 Stopped，原盘、离线快照和修复副本都在原位置。三份文件的大小和权限复核为 100 GiB、0600。

## 2026-11-18 上游与发布产物复核

从官方 Distroless OCI 注册表获取固定索引 `sha256:d978eefd303c59c42f382aef811a0e41f2c68a6e5c319a23769cd3d1d5bf8e3c` 及当日 `nonroot` 索引 `sha256:6b60e44d652d66a9e2e584364c96adac4df7133674b2834b6f71741b7cdb1ff3`，按层校验 SHA-256 并读取实际包清单。五架构两组镜像的 libc6/libc-bin 均为 `2.41-12+deb13u4`，Expat 为 `2.9.0-1~deb13u1`，FreeType 为 `2.13.3+dfsg-1+deb13u1`，libstdc++6 为 `14.2.0-19`。检查的 Temurin 21.0.13+9-LTS ELF 在两组镜像中逐架构 SHA-256 相同。保留现有基础镜像摘要和已验证覆盖层。 [Distroless Java](https://github.com/GoogleContainerTools/distroless/tree/main/java) · [Debian glibc](https://security-tracker.debian.org/tracker/source-package/glibc) · [Debian Expat](https://security-tracker.debian.org/tracker/source-package/expat) · [Debian FreeType](https://security-tracker.debian.org/tracker/source-package/freetype)

Adoptium 的 21.0.13+9 是本次核查的最新正式 Temurin 21 发布。五份官方 SBOM 的资产 SHA-256 均通过校验。SBOM 和随附 libjvm 的 ELF 编译器记录一致，amd64、arm64、ppc64le、s390x 使用 GCC 11.3.0，riscv64 使用 GCC 14.2.0。五份 SBOM 指向同一 OpenJDK 源码提交 `2ab10ca20c191701c3a9f3153bfc6e93d63714c1`。核查该源码的 `src` 和 `make`，未发现四类受影响模板的直接引用或并行模式构建参数。检查两组镜像中每份 JRE 的 40–43 个 ELF 的动态符号，也未发现相关模板。源码和动态符号覆盖存在依赖内联及剥离后局部符号的限制，因此保留四项条件性结论。 [Temurin 发布及 SBOM](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9) · [对应 OpenJDK 源码](https://github.com/adoptium/jdk21u/commit/2ab10ca20c191701c3a9f3153bfc6e93d63714c1)

### 逐项状态

按部署配置可达、现有原生组件受限、业务调用缺乏证据的顺序排列。Debian glibc 2.43-7 已修复多项 glibc 问题。Debian 13 的加载器、libc、转换模块、locale 和 NSS 要作为同一组合验证。

| CVE | 当前结论与证据 | 剩余缓解或复核条件 |
| --- | --- | --- |
| [CVE-2026-8674](https://security-tracker.debian.org/tracker/CVE-2026-8674) | **加强缓解。** Java 实际使用 glibc DNS，风险入口为部署搜索域。Debian 13 原包仍受影响。Helm 现于渲染期拒绝守卫会拒绝的多行或超限覆盖值。 | 通过守卫启动。限制继承配置和 DHCP／VPN 搜索列表，变更后重启 API。继续跟踪同版本回补及目标 Kubernetes 验证。 |
| [CVE-2025-66382](https://security-tracker.debian.org/tracker/CVE-2025-66382) | **保留缓解。** Debian 跟踪器仍未列出正式修复；字体发现会加载 Expat，配置路径固定且只读。 | 保持字体目录和主目录可信、只读；新增字体配置导入时复查。 |
| [CVE-2026-77117](https://security-tracker.debian.org/tracker/CVE-2026-77117) | **保留缓解。** Debian 13 受影响；SHIFT_JISX0213 原生转换模块仍在，未发现请求控制调用的路径。 | 保持 C.UTF-8；新增原生转换时限制编码、输入和无进展重试，跟踪匹配的 gconv 回补。 |
| [CVE-2026-80489](https://security-tracker.debian.org/tracker/CVE-2026-80489) | **保留缓解。** Debian 13 受影响；EUC_JISX0213 原生转换模块仍在，未发现请求控制调用的路径。 | 同步验证转换模块和 libc，维护编码及重试约束。 |
| [CVE-2026-84243](https://security-tracker.debian.org/tracker/CVE-2026-84243) | **保留缓解。** Debian 13 和 2.43-7 均受影响；守卫固定 C.UTF-8、清除 LANGUAGE，镜像无消息翻译目录。 | 新增 locale、翻译目录或自定义入口时复核。 |
| [CVE-2026-102010](https://security-tracker.debian.org/tracker/CVE-2026-102010) | **已核对头文件修复，保留既有 JRE 风险。** Debian GCC 16.2.0-4 修复 PBDS 二叉堆模板；Temurin 使用较早的工具链，源码和 ELF 未见直接使用。 | 新 C++ 构建采用已修复头文件；JRE 更新时复核厂商工具链及匹配调试符号。 |
| [CVE-2026-114690](https://security-tracker.debian.org/tracker/CVE-2026-114690) | **保留条件性风险。** Debian GCC 16.2.0-4 仍受影响；检查范围内未见 GNU 并行搜索模板或并行模式开关。 | 新增原生模块保持标准串行算法，采用修复头文件重新编译调用方。 |
| [CVE-2026-114691](https://security-tracker.debian.org/tracker/CVE-2026-114691) | **保留条件性风险。** Debian GCC 16.2.0-4 仍受影响；检查范围内未见 `std::vector<bool>` 相关路径。 | 原生容器的容量取自可信上限；修复后重新编译实际调用方。 |
| [CVE-2026-115455](https://security-tracker.debian.org/tracker/CVE-2026-115455) | **保留条件性风险。** Debian GCC 16.2.0-4 仍受影响；检查范围内未见宽流提取到 `std::bitset` 的路径。跟踪器对 Debian GCC 11/12 列为不受影响，Temurin GCC 11 的工具链身份不能单凭主版本号推断。 | 避免用该接口解析不可信输入。riscv64 的 GCC 14.2.0 保留重点复核。 |
| [CVE-2026-18374](https://security-tracker.debian.org/tracker/CVE-2026-18374) | **待同版本修复。** Debian 13 受影响；未发现用户控制原生文件打开模式的业务路径。 | 保持原生文件模式固定。 |
| [CVE-2026-19499](https://security-tracker.debian.org/tracker/CVE-2026-19499) | **待同版本修复。** Debian 13 受影响；既有 ELF 检查未见受影响的原生货币格式化导入。 | 使用现有 Java 格式化路径；若新增原生调用，约束格式及缓冲容量。 |
| [CVE-2026-19542](https://security-tracker.debian.org/tracker/CVE-2026-19542) | **待同版本修复。** Debian 13 受影响；既有 ELF 检查未见受影响的原生树操作导入。 | 新增原生树时限制规模，验证所用 libc。 |
| [CVE-2026-6368](https://security-tracker.debian.org/tracker/CVE-2026-6368) | **待同版本修复。** Debian 13 受影响；镜像无 Shell，既有检查未见 `wordexp` 导入。 | 保持独立参数数组和执行边界。 |
| [CVE-2026-6791](https://security-tracker.debian.org/tracker/CVE-2026-6791) | **待同版本修复。** Debian 13 受影响；未发现路径展开进入原生 `wordexp` 的业务证据。 | 持续避免将请求作为 Shell／wordexp 展开输入。 |
| [CVE-2026-5435](https://security-tracker.debian.org/tracker/CVE-2026-5435) | **待同版本修复。** Debian 13 受影响；既有 ELF 检查未见受影响的 DNS 诊断打印导入。 | DNS 诊断使用受支持接口。 |
| [CVE-2026-6238](https://security-tracker.debian.org/tracker/CVE-2026-6238) | **待同版本修复。** Debian 13 受影响；未发现 DNS 诊断打印的业务路径。 | 新增诊断功能时核查解析和输出长度。 |
| [CVE-2026-100290](https://security-tracker.debian.org/tracker/CVE-2026-100290) | **待上游修复。** Debian 13 和 2.43-7 均受影响；既有 ELF 检查未见 POSIX 正则导入，业务使用 Java Pattern。 | 保持内存限制和 OOM 监控，新增原生正则时复核。 |
| [CVE-2026-115819](https://security-tracker.debian.org/tracker/CVE-2026-115819) | **待上游修复。** Debian 13 和 2.43-7 均受影响；既有 ELF 检查未见受影响的宽 scanf 调用。 | 新增原生解析时避免宽 scanf 向窄数组写出，并约束长度。 |
| [CVE-2026-95619](https://security-tracker.debian.org/tracker/CVE-2026-95619) | **保持已修复。** 实际镜像使用已验证的 GCC 16 三件套和后续 filebuf 回补，沿用对齐分配的原生回归。 | 基础镜像更新后重验包及 ABI。 |

CVE-2026-128241 涉及镜像未安装的 `xmlwf`，按实际文件清单跟踪。已修复的 CVE-2026-125709 与上述四项 GCC 模板问题分别记录。 [Debian xmlwf 告警](https://security-tracker.debian.org/tracker/CVE-2026-128241) · [最终共享库复验](security-gcc-filebuf-ports-2026-11-17.md) · [Snappy 静态链接复现](security-snappy-static-reproduction-2026-11-18.md)

### 回补候选与回归结果

使用 Debian `glibc_2.41-12+deb13u4` 源包、Debian 补丁序列和原生 arm64 GCC 14，按 `debian/rules build_libc` 无模糊应用上游提交 `506ea57086bfb9ce3daff1c14246a1cb532aba0a` 的运行库修改。与固定 Distroless 基础镜像的原库相比，libc 和动态加载器分别保留 3,053 与 39 个版本化导出集合条目。符号绑定、可见性、数据和 TLS 对象大小、符号版本定义、依赖、SONAME、ELF flags、BTI/PAC、RELRO 与不可执行栈通过对照。候选仅替换临时隔离镜像中的 libc 和加载器；其他四种架构以及完整处理后的 Debian 软件包文件清单仍待验证。生产 Dockerfile 保留原摘要和运行时守卫。 [Debian glibc 源包](https://deb.debian.org/debian/pool/main/g/glibc/glibc_2.41-12+deb13u4.dsc) · [上游补丁](https://sourceware.org/git/?p=glibc.git;a=commit;h=506ea57086bfb9ce3daff1c14246a1cb532aba0a)

| 验证 | 本轮结果 |
| --- | --- |
| Helm 容器验证 | Helm 3.17.3 lint 和正常显式值通过；默认保留继承；规范化后 255 字节通过；超长列表、含多字节字符造成超限及多行值在渲染阶段被拒绝。 |
| DNS 和 C++ 回归 | 当前 arm64 最终镜像和临时回补镜像均通过 7 项守卫解析检查、9 项搜索域和运行环境场景、3 项真实 DNS/TCP 及合法配置重载。新旧两套 C++ 字符串 ABI 正常文件缓冲、线程和异常用例均通过。 |
| Java 原生调用 | 两个镜像均通过 DOM/SAX、XLSX 回环、AWT 字形渲染，实际映射已修复的 FreeType 6.20.6。 |
| API 启动 | 两个镜像使用默认入口和各自独立的 H2 桌面配置启动，readiness 均为 UP。实际 Kubernetes 与候选库的 MySQL／Redis／S3 验收待补。 |
| 发布脚本回归 | 20 项 Node 测试、Helm 验证脚本 Bash 语法及 `git diff --check` 通过。 |
| 当前 arm64 最终镜像复扫 | 镜像 `sha256:4c59b1b2262f51c7d7007c9896a8b5d1edaefe7029c400ce19e01ee1a97388bf`。Trivy 0.65.0 全级别扫描记录 66 条、42 个独立 CVE，含 8 HIGH、32 MEDIUM、20 LOW、6 UNKNOWN，CRITICAL 和应用 JAR 均为 0。用同一漏洞库执行可修复 HIGH/CRITICAL 门禁，退出 0。 |

Trivy 漏洞库与 Java 索引自官方 GHCR 导入并校验 OCI 层摘要，分别更新于 2026-11-17 19:04:24 UTC 和 2026-11-18 00:58:01 UTC。全级别报告 SHA-256 为 `59d56c9ab2c208c55c2e0c421f13d31413db70e77ac17154bfc59a2048036b79`。保留 CVE-2026-8674 的 libc6/libc-bin 记录。8 条 HIGH 仍对应 4 项 Expat 已修复问题和 4 项缺少受影响 util-linux 组件的源包级告警。 [HIGH 逐项依据](security-high-risk-validation-2026-11-11.md)

本轮专用 Colima、容器数据和临时工作目录已清理。复核默认 Colima 为 Stopped，原盘、离线快照及修复副本均在原位置，各为 100 GiB、0600。
