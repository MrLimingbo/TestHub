# MEDIUM 与 UNKNOWN 修复和可利用性复核（2026-11-12）

> 后续已处理此处保留的 19 项条件性风险，新增 DNS 启动校验和执行环境约束，并升级 C++ 运行库。逐项结果和最新扫描见 [19 项追加复核](security-conditional-risk-validation-2026-11-12.md)。以下保留本轮历史记录。

本轮完成系统 FreeType 与 Temurin 随附 FreeType 的修复，并为 Expat 2.10.0 回补已合并的边界检查。最终镜像通过原生库、AWT、XLSX 和启动验收。26 个独立 CVE 均已逐项评估，其中 2 项已有修复，5 项在当前镜像中缺少触发组件或权限条件，其余 19 项保留条件性风险与缓解措施。

本轮使用独立 Colima `testhub-medium-20261111`。默认 Colima 仍为 Stopped，原盘、离线快照和修复副本保留。验证范围为 Linux arm64 的 API 镜像，业务数据使用临时 H2。前一轮 MySQL/Redis/S3 的 1380 项验收记录继续保留，本轮验证集中于原生运行库，未重跑该全量套件。

最终镜像为 `testhub/platform-api:medium-final`，OCI ID 为 `sha256:e04566efd796c4e6827c970b911adf4b67e545c557d9d9b61d6582b133a9f4c2`。全级别扫描完成于 2026-11-12 15:00:18（Asia/Shanghai）。Trivy 0.65.0 使用本轮下载的数据库，漏洞库更新于 2026-11-12 01:03:48 UTC，Java 索引更新于 2026-11-12 00:58:04 UTC。

原始扫描为 **83 条包/CVE 记录，含 8 HIGH、47 MEDIUM、23 LOW、5 UNKNOWN，CRITICAL 为 0**，归并为 47 个独立 CVE。Java 依赖告警为 0。MEDIUM/UNKNOWN 对应 26 个独立 CVE。相比上一轮新增的 4 条 UNKNOWN 包记录来自 CVE-2026-100290 和 CVE-2026-115819，各同时命中 libc6/libc-bin。本轮开始时与修复后的同库扫描计数相同，扫描器正确识别了修复后的包版本，Debian 13 数据仍缺少相应 FixedVersion。保留全部原始条目，发布门禁通过并不意味着全级别零告警。脱敏证据见 [验证 JSON](security-medium-risk-evidence-2026-11-12.json)。

FreeType 使用 SHA-256 固定的 Debian 官方 `libfreetype6 2.14.3+dfsg-3`，该包包含 CVE-2026-95512 修复。替换前的系统库为 2.13.3，Temurin 随附库为 2.14.3，其 release SOURCE 指向 `2ab10ca20c19`，对应源码的 CID loader 缺少此次 overlap 限制。JDK 原有库文件已移除，`lib/libfreetype.so` 链接到修复后的系统库。AWT 字体枚举、字体度量和绘制后，`/proc/self/maps` 确认实际映射 `libfreetype.so.6.20.6`。官方修复依据见 [Debian 状态](https://security-tracker.debian.org/tracker/CVE-2026-95512)、[Debian 下载页](https://packages.debian.org/sid/arm64/libfreetype6/download)和 [Temurin 对应构建源码](https://raw.githubusercontent.com/adoptium/jdk21u/2ab10ca20c19/src/java.desktop/share/native/libfreetype/src/cid/cidload.c)。

Expat 基于上游 2.10.0 发布源码和已合并提交 `3d77567612994172f9d09b46f31bb604e16fb08d` 构建窄/宽字符库。补丁原文保存在 `infra/native-patches/expat-CVE-2026-124317.patch`，源码和补丁均校验 SHA-256，补丁以零模糊方式应用。GitHub 动态补丁下载曾出现摘要差异，因此改为保存已核对的补丁原文并固定摘要。生成的运行包身份为 `2.10.0-1+testhub1`，记录上游提交，文件校验清单随库重建。该项采用项目维护的源码回补，需要后续跟进正式发布包。依据见 [上游合并 PR](https://github.com/libexpat/libexpat/pull/1492)。

下表按验收后的实际暴露程度排序。优先级 1 关注现有基础能力和配置边界，2 为存在库及受限触发条件，3 为未发现业务调用的接口或编译模板，4 为当前镜像缺少必要组件或权限。库存在、动态导入及源码检查提供可达性依据，仍需考虑内部调用、内联模板和后续新增功能。当前未确认经 TestHub HTTP 接口直接利用这些残留问题的路径。

| CVE / 级别 | 优先级与结论 | 依据 | 缓解与后续 |
| --- | --- | --- | --- |
| [CVE-2026-95512](https://security-tracker.debian.org/tracker/CVE-2026-95512) / MEDIUM | 已处理，已修复 | 系统 2.13.3 和 Temurin 随附 2.14.3 均纳入处理。已安装含 Debian 安全补丁的 2.14.3+dfsg-3，JDK 入口链接该库。AWT maps 确认实际加载 6.20.6，字体与字形渲染通过。当前无用户字体导入/注册业务路径，字体目录只读。 | 保持字体目录只读，限制文件上传用途。跟随 Debian/Temurin 后续正式更新。 |
| [CVE-2026-124317](https://security-tracker.debian.org/tracker/CVE-2026-124317) / UNKNOWN | 已处理，已回补上游修复 | 2.10.0 加入已合并提交 3d775676 的转换边界检查，包版本为 2.10.0-1+testhub1。补丁与源码 SHA-256 固定，零模糊应用，双库 ABI 相同。自定义多字节编码和分片兼容性通过。触发需要应用注册未知编码转换回调，fontconfig 导入检查未见该注册接口，当前未发现业务输入进入此回调的路径。 | 维持只读字体配置。后续采用包含补丁的正式发布包，移除本地源码回补。 |
| [CVE-2026-8674](https://security-tracker.debian.org/tracker/CVE-2026-8674) / MEDIUM | 1，待 Debian 13 回补，配置暴露优先处理 | libnet.so 实际导入 getaddrinfo，DNS 能力可达。过长搜索域来自 resolv.conf 或 LOCALDOMAIN，可能经 DHCP/VPN 配置进入。未发现 HTTP 请求修改这些配置的能力。 | 在部署环境约束 DNS 搜索域长度和总列表，校验 DHCP/VPN 下发配置，控制 LOCALDOMAIN，配置变更后检查解析与进程健康。 |
| [CVE-2025-66382](https://security-tracker.debian.org/tracker/CVE-2025-66382) / MEDIUM | 2，上游仍未发布修复 | Expat 2.10.0 仍受影响，上游 issue 1076 开放。fontconfig 会加载 Expat，当前业务输入未发现进入其 XML 配置的路径。 | 保持字体配置和用户目录只读，控制 FONTCONFIG_FILE/PATH 和 XDG_CONFIG_HOME，保留进程资源限制。 |
| [CVE-2026-77117](https://security-tracker.debian.org/tracker/CVE-2026-77117) / MEDIUM | 2，条件性暴露，等待匹配版本回补 | SHIFT_JISX0213 gconv 模块在镜像中。部分 JDK 原生库和 libstdc++ 导入 iconv，未发现业务可控制该原生转换及其输出缓冲策略。Java Charset 路径需与 glibc iconv 区别评估。 | 保持运行时 locale 配置受控。新增原生字符转换时限制编码与输入，处理转换无进展并设置调用上限。 |
| [CVE-2026-80489](https://security-tracker.debian.org/tracker/CVE-2026-80489) / MEDIUM | 2，条件性暴露，等待匹配版本回补 | EUC_JISX0213 gconv 模块在镜像中。部分 JDK 原生库和 libstdc++ 导入 iconv，未发现业务可控制该原生转换及其输出缓冲策略。Java Charset 路径需与 glibc iconv 区别评估。 | 保持运行时 locale 配置受控。新增原生字符转换时限制编码与输入，处理转换无进展并设置调用上限。 |
| [CVE-2026-84243](https://security-tracker.debian.org/tracker/CVE-2026-84243) / MEDIUM | 2，环境边界限制，尚无官方修复包 | fontconfig/libuuid 等导入 gettext 家族。镜像只有 C.utf8，未携带 .mo 目录文件，当前未发现请求设置 LANGUAGE/locale 的能力。 | 保持 locale 与 LANGUAGE 由部署端控制，禁止将上传文件目录作为消息目录。新增 locale 包或环境透传时重新评估。 |
| [CVE-2026-95619](https://security-tracker.debian.org/tracker/CVE-2026-95619) / MEDIUM | 2，运行库保留，未发现可控调用链 | 涉及 libstdc++ 对齐 operator new 的极大分配。库存在，未发现业务到该 C++ 接口的可控参数链。候选 gcc-16 运行库要求额外匹配的 gcc-16-base，当前未替换该运行库组合。 | 限制请求和进程内存，保持无用户 JNI/原生库执行入口，跟随基础镜像与 JDK 工具链安全更新。 |
| [CVE-2026-18374](https://security-tracker.debian.org/tracker/CVE-2026-18374) / MEDIUM | 3，未发现业务调用，保留待回补 | fopen 的 ,ccs= 模式由攻击者控制。JDK/原生库使用文件 API，但未发现业务控制原生 mode 参数。 | 避免把请求值拼入原生文件打开模式，跟随匹配的 glibc 修复。 |
| [CVE-2026-19499](https://security-tracker.debian.org/tracker/CVE-2026-19499) / MEDIUM | 3，未发现业务调用，保留待回补 | strfmon/strfmon_l 的宽度填充。325 个 ELF 的动态导入检查未发现该接口，Java 金额格式化使用 Java API。 | 新增原生货币格式化时审查输出容量与格式来源，跟随匹配的 glibc 修复。 |
| [CVE-2026-19542](https://security-tracker.debian.org/tracker/CVE-2026-19542) / MEDIUM | 3，未发现业务调用，保留待回补 | tdelete 的大规模树操作。动态导入未发现 tdelete，Java 集合使用独立实现。 | 新增原生树结构时限制规模并使用修复版本，跟随匹配的 glibc 修复。 |
| [CVE-2026-6368](https://security-tracker.debian.org/tracker/CVE-2026-6368) / MEDIUM | 3，未发现业务调用，保留待回补 | wordexp 加 WRDE_APPEND。动态导入未发现 wordexp，镜像也无 Shell。 | 保持参数数组和独立执行边界，避免添加 Shell 展开，跟随匹配的 glibc 修复。 |
| [CVE-2026-6791](https://security-tracker.debian.org/tracker/CVE-2026-6791) / MEDIUM | 3，未发现业务调用，保留待回补 | wordexp 的超长波浪号用户名展开。动态导入未发现 wordexp，未发现业务原生展开路径。 | 限制展开输入并避免将请求交给 wordexp，跟随匹配的 glibc 修复。 |
| [CVE-2026-5435](https://security-tracker.debian.org/tracker/CVE-2026-5435) / MEDIUM | 3，未发现业务调用，保留待回补 | 弃用的 DNS 记录打印接口写出。动态导入未发现 ns_printrr/ns_printrrf/fp_nquery，普通 DNS 解析不使用这些调试接口。 | 保持诊断代码使用受支持接口，避免引入这些打印函数，跟随匹配的 glibc 修复。 |
| [CVE-2026-6238](https://security-tracker.debian.org/tracker/CVE-2026-6238) / MEDIUM | 3，未发现业务调用，保留待回补 | 弃用的 DNS 记录打印接口读取。同类打印接口缺少业务调用证据，DNS getaddrinfo 的存在不能证明该问题可达。 | 新增 DNS 诊断功能时使用长度安全的解析和格式化实现，跟随匹配的 glibc 修复。 |
| [CVE-2026-100290](https://security-tracker.debian.org/tracker/CVE-2026-100290) / UNKNOWN | 3，未发现业务调用，保留待回补 | POSIX regcomp/regexec 在特定分配失败下清理出错。原生导入未发现这两项，业务 Pattern 是 Java 正则，间接或内部调用仍有分析边界。 | 保持内存限额并监控 OOM，新增 POSIX 正则调用时重新审查，跟随匹配的 glibc 修复。 |
| [CVE-2026-115819](https://security-tracker.debian.org/tracker/CVE-2026-115819) / UNKNOWN | 3，未发现业务调用，保留待回补 | 宽 scanf 向窄字符数组输出的终止字节。导入可见的是窄 sscanf/fscanf，未发现受影响的宽 scanf 调用。 | 避免宽 scanf 向窄数组输出，保持目标容量与格式校验，跟随匹配的 glibc 修复。 |
| [CVE-2026-102010](https://security-tracker.debian.org/tracker/CVE-2026-102010) / MEDIUM | 3，涉及编译模板，尚未发现业务可达路径 | __gnu_pbds 二叉堆 erase_if 模板。gcc-14-base 与 libgcc-s1 的同 CVE 命中属于源包关联，不能据此认定含相同缺陷代码。模板可能编入 C++ 调用方，替换共享库不足以证明修复；当前业务主要为 Java，未发现此类可控原生路径。 | 保持用户原生代码隔离，跟随使用修复工具链构建的 JDK，新增 C++ 模块时检查编译器头文件及实际构建版本。 |
| [CVE-2026-114690](https://security-tracker.debian.org/tracker/CVE-2026-114690) / MEDIUM | 3，涉及编译模板，尚未发现业务可达路径 | libstdc++ 并行搜索模板。gcc-14-base 与 libgcc-s1 的同 CVE 命中属于源包关联，不能据此认定含相同缺陷代码。模板可能编入 C++ 调用方，替换共享库不足以证明修复；当前业务主要为 Java，未发现此类可控原生路径。 | 保持用户原生代码隔离，跟随使用修复工具链构建的 JDK，新增 C++ 模块时检查编译器头文件及实际构建版本。 |
| [CVE-2026-114691](https://security-tracker.debian.org/tracker/CVE-2026-114691) / MEDIUM | 3，涉及编译模板，尚未发现业务可达路径 | vector<bool> 的超大元素计数。gcc-14-base 与 libgcc-s1 的同 CVE 命中属于源包关联，不能据此认定含相同缺陷代码。模板可能编入 C++ 调用方，替换共享库不足以证明修复；当前业务主要为 Java，未发现此类可控原生路径。 | 保持用户原生代码隔离，跟随使用修复工具链构建的 JDK，新增 C++ 模块时检查编译器头文件及实际构建版本。 |
| [CVE-2026-115455](https://security-tracker.debian.org/tracker/CVE-2026-115455) / MEDIUM | 3，涉及编译模板，尚未发现业务可达路径 | 宽字符流到 bitset 的模板提取。gcc-14-base 与 libgcc-s1 的同 CVE 命中属于源包关联，不能据此认定含相同缺陷代码。模板可能编入 C++ 调用方，替换共享库不足以证明修复；当前业务主要为 Java，未发现此类可控原生路径。 | 保持用户原生代码隔离，跟随使用修复工具链构建的 JDK，新增 C++ 模块时检查编译器头文件及实际构建版本。 |
| [CVE-2026-3184](https://security-tracker.debian.org/tracker/CVE-2026-3184) / MEDIUM | 4，当前镜像触发条件缺失 | 缺少 login/PAM 远程主机名登录链，libuuid 不含 login 实现。 | 保持镜像精简，不增加系统登录入口。 |
| [CVE-2026-42250](https://security-tracker.debian.org/tracker/CVE-2026-42250) / MEDIUM | 4，当前镜像触发条件缺失 | 缺少 bzip2recover，libbz2 共享库不含该命令行工具的越界写实现。 | 保持恢复工具在独立运维环境运行。 |
| [CVE-2026-89092](https://security-tracker.debian.org/tracker/CVE-2026-89092) / MEDIUM | 4，当前镜像触发条件缺失 | 镜像缺少 nscd 服务，相关包/进程不在应用容器中。 | 若部署端使用宿主 nscd，需单独按宿主服务评估。 |
| [CVE-2026-86805](https://security-tracker.debian.org/tracker/CVE-2026-86805) / MEDIUM | 4，当前镜像触发条件缺失 | 需要 AT_SECURE 的 SUID/SGID 程序及特定 $ORIGIN 路径竞争，实际镜像无 SUID/SGID 文件。 | 保持 UID 65532、禁止提权、只读根目录及全部 capabilities 移除。 |
| [CVE-2026-95818](https://security-tracker.debian.org/tracker/CVE-2026-95818) / MEDIUM | 4，当前镜像触发条件缺失 | 同样依赖 AT_SECURE 的 SUID/SGID 加载器路径，运行权限条件缺失。 | 保持上述运行权限和文件清单约束，新增特权工具时重新评估。 |

本轮没有实施跨发行版 glibc 或 GCC 运行库替换。当前 libc 为 2.41-12+deb13u4，Debian 13 尚无匹配修复包。检查到的 sid libc6 2.43-7 依赖相同版本的 libc-gconv-modules-extra，并约束旧 locale/locale-all/nscd 版本，后续升级需要连同加载器、转换模块和 locale/NSS 组合验证。部分 GCC 告警涉及头文件模板，更新共享库不能证明已经编入调用方的代码得到修复。对于这些剩余项，采用匹配版本回补或经过验证的基础镜像/JDK 更新。

验证结果如下。

| 验证 | 结果 |
| --- | --- |
| Expat 原生回归 | 最终镜像中窄/宽库各 4 项，8/8 通过，原有 HIGH 修复行为保留 |
| 新增编码兼容性 | 两种字符库的自定义多字节编码与合法分片处理均通过 |
| FreeType 实际库 | 系统入口、JDK 入口分别完成版本、字体打开与字形栅格化，2/2 通过 |
| ABI | Expat 双库导出符号、SONAME、NEEDED 与原 Debian 2.10.0 一致，构建时检查；FreeType 保留系统 203 个和 JDK 186 个公开 FT_* 符号 |
| Java/AWT | JDK DOM/SAX、中文/换行/数值 XLSX 往返、字体枚举/度量/绘制通过，AWT 实际映射修复后的系统库 |
| 业务 XLSX | FunctionalExchangeRoundTripTests 6 项通过，0 失败/错误/跳过 |
| 安装与扫描脚本 | 11/11 通过，覆盖包篡改、补丁篡改、下载失败、架构拒绝和扫描失败退出。修正 macOS sha256sum 标准输入参数，确保运行的是实际摘要校验 |
| 静态检查 | 新增/变更 JS 测试 ESLint、Python 编译检查、Bash 语法、git diff --check 通过 |
| 最终镜像启动 | 临时 H2/tmpfs、只读根目录、cap-drop ALL、禁止提权条件下 readiness 为 UP |
| SBOM/扫描 | Syft v1.32.0 确认 libexpat1 2.10.0-1+testhub1、libfreetype6 2.14.3+dfsg-3，Trivy 全级别扫描与现有可修复 HIGH/CRITICAL 门禁通过 |

新增兼容性用例使用正常多字节编码和镜像自带字体。Expat 修复结论结合已合并补丁、源码/补丁摘要、构建约束和实际库兼容性检查，本轮未执行相邻内存泄露攻击或资源耗尽攻击。当前只完成 Linux arm64 验收，其他架构需要运行相同门禁。前一轮 5328 项上游测试属于补丁之前的源码版本，本轮不将其计入回补后的测试数量。

复现时先选择隔离 Docker context，并设置 JDK 21，执行以下命令。

```bash
docker build -f services/platform-api/Dockerfile -t testhub/platform-api:security-qa .
python3 scripts/security/verify-native-image.py testhub/platform-api:security-qa
node --test scripts/security/*.test.mjs
./mvnw -q -pl services/platform-api -am test -Dtest=FunctionalExchangeRoundTripTests -Dsurefire.failIfNoSpecifiedTests=false
bash scripts/security/scan-image.sh testhub/platform-api:security-qa
```

新增回归已接入既有发布门禁。此次没有修改扫描忽略规则或部署共享服务。运维侧应优先核对 DNS 搜索域与 DHCP/VPN 配置来源，持续限制字体、locale、原生代码和进程环境的输入边界。

独立验收 Colima 及其数据已删除，临时软件包、源码、镜像、日志和工作区已清理。保留本报告、脱敏证据和回归脚本。默认 Colima 最终仍为 Stopped，原盘、快照及修复副本各为 107374182400 字节、权限 0600，均保留。此次未创建分支、提交或推送。
