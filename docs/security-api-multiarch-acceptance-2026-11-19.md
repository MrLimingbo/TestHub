# API 四架构正式镜像验证（2026-11-19）

本轮范围为 amd64、s390x、ppc64le、riscv64 的正式 Dockerfile 产物、隔离 MySQL／Redis／S3 协议验收、实际 API 进程的 glibc 映射及全级别漏洞复扫。过程证据见[脱敏 JSON](security-api-multiarch-evidence-2026-11-19.json)。arm64 已完成的正式镜像验证见[前轮报告](security-glibc-dns-ports-2026-11-18.md)。

## 当前进度

amd64、s390x、ppc64le、riscv64 均完成正式构建、包清单校验、隔离服务验收、实际 API 进程的 glibc 映射核对、最终 ABI 复核、原生兼容性回归和漏洞复扫。版本化导出的核对数量见下表。

| 架构 | 正式构建及包清单 | 最终 glibc ABI | 隔离服务验收 | 实际 API 映射 | 原生兼容性 | 全级别复扫 |
| --- | --- | --- | --- | --- | --- | --- |
| amd64 | 通过，284 项 libc6 文件 | 3,141／38 通过 | 通过 | 两份摘要通过，可执行映射 | 通过 | 72 条／45 CVE，门禁 0 |
| s390x | 通过，288 项 libc6 文件 | 3,349／39 通过 | 通过 | 两份摘要和 QEMU 代码段覆盖通过 | 通过 | 72 条／45 CVE，门禁 0 |
| ppc64le | 通过，283 项 libc6 文件 | 3,275／41 通过 | 通过 | 两份摘要和 QEMU 代码段覆盖通过 | 通过，含两种 long double ABI | 72 条／45 CVE，门禁 0 |
| riscv64 | 通过，283 项 libc6 文件 | 3,010／39 通过 | 通过 | 两份摘要和 QEMU 代码段覆盖通过 | 通过 | 72 条／45 CVE，门禁 0 |

## 构建和验收方法

使用 `services/platform-api/Dockerfile` 的完整阶段生成目标架构镜像。libstdc++ 和 glibc 均使用目标架构编译器、固定源码、Debian 补丁序列及上游回补。构建分别检查版本化导出、符号类型／绑定／可见性、数据及 TLS 大小、版本定义、依赖、SONAME、GNU 架构属性、RELRO 和不可执行栈。最终镜像重新检查 Debian 包身份、补丁来源及所有保留文件的校验和。

为降低源树的磁盘峰值，GCC 和 glibc 临时构建目录使用 BuildKit tmpfs。glibc 的详细编译日志转存临时文件，失败时输出末尾诊断，保留 ABI 门禁输出。构建和验收在本轮独立 Colima 内执行，镜像均使用明确的本地身份。

MySQL、Redis、MinIO 使用 arm64 后端，API 使用待测目标架构。每轮生成独立 Compose 项目、随机凭据、数据库和数据卷，只向宿主回环地址发布 API。断言覆盖账户写入 MySQL、文件元数据和审计、Redis 会话跨 API 重启和登出、S3 签名读取、删除后 404、下载字节比较以及 readiness。结束时删除该轮容器和数据卷。

MinIO 验收夹具因前期空间不足改用宿主 Go 1.26.9 交叉编译 arm64 静态二进制。源码版本、源码 SHA-256、`-trimpath` 和完整链接参数与仓库固定构建一致，保留 Go 校验数据库，源码和依赖版本沿用原值。二进制 SHA-256 为 `5e5637d0e515191107008f411933388d0d8933e93fabab4bb5b05c2c35ce3835`。MySQL 和 Redis 使用生产 Compose 所指定镜像。

`verify-platform-image.py` 增加 `--startup-timeout` 和 `--request-timeout`，默认分别为 90 秒和 10 秒。本轮仿真验收使用 600 秒和 60 秒，首次启动与 API 重启均等待 readiness。s390x 观测到首次启动约 144 秒，原 90 秒期限不足以覆盖本机的仿真执行。构建并行时，一次账户注册超过 10 秒期限，该轮资源已清理，后续使用显式请求期限重跑完整流程。

原生兼容性回归使用同架构的 QA 编译镜像，检查 Expat 两个字符宽度版本、C++ 字符串 ABI、FreeType 两条库路径、DOM／SAX、XLSX、AWT、运行守卫和 DNS／TCP／配置重载。`verify-native-image.py` 增加 `--java-probe-timeout`，默认 30 秒，本轮扩展 Java 探针使用 180 秒。s390x 首轮在 30 秒期限内未完成，延长期限后整套回归通过。

## 实际 API 进程核查

在服务协议验收和 API 重启完成后读取该容器的宿主 PID。检查真实 Java 命令及 `/app/testhub-platform-api.jar` 参数，通过 `/proc/<pid>/root` 读取映射的库文件并比较正式镜像中的 SHA-256。

原生映射要求可执行权限。QEMU 路径还核对 `/proc/<pid>/exe` 与目标 ELF 机器类型，并检查文件映射完整覆盖两份库的可执行 `PT_LOAD` 文件范围。QEMU 将目标代码翻译到独立代码缓冲区，宿主对目标库文件的映射可为只读。核查脚本记录这种映射形式，缺少代码段覆盖或摘要不符会失败。新增回归覆盖原生映射、QEMU 只读映射、机器类型不符、只映射 ELF 头、文件摘要变化及其他 Java 进程。

## 漏洞库和复扫

扫描器为 Trivy 0.65.0，镜像摘要 `sha256:a22415a38938a56c379387a8163fcb0ce38b10ace73e593475d3658d578b2436`。从官方 GHCR 获取并校验 OCI 层摘要，OS 漏洞库更新于 2026-11-19 01:05:40 UTC，Java 索引更新于 2026-11-19 00:58:12 UTC。各架构共用这份快照，通过本地 Docker 源执行离线全级别漏洞扫描及可修复 HIGH／CRITICAL 门禁，原始结果保留未修复记录。

amd64、s390x、ppc64le 和 riscv64 均为 72 条记录、45 个独立 CVE，包含 8 HIGH、34 MEDIUM、22 LOW、8 UNKNOWN，CRITICAL 为 0，应用 JAR 告警为 0。四份门禁退出值均为 0。本轮漏洞库比前轮增加三项字符转换告警，逐项处理见下节。

本轮同时将 CVE-2026-115819 从 UNKNOWN 调整为 MEDIUM，将 CVE-2026-100290 从 UNKNOWN 调整为 LOW，各涉及 libc6／libc-bin 两条源包记录。三项新增告警贡献六条 UNKNOWN 记录，原始告警总数因此从 66 增至 72。

## 新增字符转换告警

在已完成的 amd64／s390x／ppc64le 正式镜像中核对相关 gconv 文件仍存在。固定 C.UTF-8 可以约束默认原生编码，显式 `iconv_open` 的调用方仍需独立评估。当前未发现业务请求控制这些原生转换的可达调用链，保留条件性风险。

`JdkApiDebugTransport` 的响应解码使用 Java `Charset.forName`，不支持的名称回退至 UTF-8。本轮新增五个名称的有效文本回环和末尾错误字节回归。在 amd64／s390x／ppc64le 的正式 JRE 中，CP949、MS949 分别由 `jdk.charsets` 的 x-IBM949、x-windows-949 处理，ISO-2022-CN-EXT、IBM1390、IBM1399 名称按业务规则回退至 `java.base` 的 UTF-8。上述解码、XLSX 和字体执行后的映射均未出现四份受影响原生转换库。该观察覆盖所测试的 Java 路径，新增显式原生转换仍需独立复核。

| CVE | 触发前提和当前依据 | 剩余缓解和修复状态 |
| --- | --- | --- |
| CVE-2026-121757，UNKNOWN | 攻击者控制 CP949 输入及目的编码的 `//IGNORE`，原生 UHC 解码器在特定末尾错误序列后越界读取。镜像保留 `UHC.so`。 | 保持默认 C.UTF-8，新增原生编码转换时限制编码和错误处理模式。Debian 13 及 2.43-7 仍标记受影响，尚无匹配官方修复包。 |
| CVE-2026-121758，UNKNOWN | 攻击者控制 ISO-2022-CN-EXT 输入和编码，末尾特殊序列触发越界读取。镜像保留该转换模块。 | 保持编码入口约束。上游已有提交 `170ee485fa89b95c3bb7d5606c9812c6c100c9be`，本轮未对转换模块完成单独回补和兼容性对照。后续需同步核对模块 ABI、边界用例及包清单。 |
| CVE-2026-124024，UNKNOWN | 攻击者控制 UCS-4 到 IBM1390／1399 的输入、编码及边界布局，组合字符前瞻可能越界读取。镜像保留两份模块。 | 保持默认 C.UTF-8，原生转换必须验证完整 UCS-4 输入和输出容量。Debian 13 及 2.43-7 仍标记受影响，尚无匹配官方修复包。 |

触发条件和修复状态以本轮核对的 Debian 跟踪器及上游说明为依据。[CP949](https://security-tracker.debian.org/tracker/CVE-2026-121757) · [ISO-2022-CN-EXT](https://security-tracker.debian.org/tracker/CVE-2026-121758) · [IBM1390／1399](https://security-tracker.debian.org/tracker/CVE-2026-124024)

## 共用的剩余风险措施

glibc DNS 回补版本为 `2.41-12+deb13u4+testhub1`，来源提交为 `506ea57086bfb9ce3daff1c14246a1cb532aba0a`。Debian 13 官方包仍标记受影响，Trivy 继续按 glibc 源包记录告警。修复判定结合实际文件、构建 ABI 门禁和进程映射。部署继续使用运行时守卫、可信 DNS 配置和搜索域变更后的 API 重启流程。[Debian 状态及上游提交](https://security-tracker.debian.org/tracker/CVE-2026-8674)

原 19 项风险的逐项记录继续见[风险台账](security-conditional-risk-validation-2026-11-12.md)。字体配置相关的 CVE-2025-66382 依赖可信只读字体配置缓解。CVE-2026-77117、CVE-2026-80489 的显式原生 JIS 转换入口和 CVE-2026-84243 的 locale／消息目录入口继续受运行时守卫约束。新增字体、locale、JNI 或编码入口需复核其调用链。其他 glibc 条件项需继续控制原生格式、输入容量和资源边界。

四项 GCC 模板沿用固定 Temurin 发布件的匹配调试证据。此前已检查五架构 200 份独立 ELF 调试文件、404,655 个符号及 8,429,275 个 DWARF 内联条目，本轮基础镜像仍固定同一 Temurin 21.0.13+9。新增原生调用方及更新后的二进制需要重新核查。[匹配证据](security-glibc-dns-ports-2026-11-18.md)

| CVE | 对固定发布件的结论 | 剩余措施 |
| --- | --- | --- |
| CVE-2026-102010 | 未发现 PBDS 二叉堆模板的隐藏或内联实现，GCC 16.2.0-4 的头文件已修复。 | 新增 C++ 构建采用已修复头文件，Temurin／JNI 更新后复核实际产物。 |
| CVE-2026-114690 | 未发现 GNU 并行搜索模板实现，Debian GCC 16.2.0-4 仍标记受影响。 | 新增并行原生搜索时采用修复头文件并重编译调用方。 |
| CVE-2026-114691 | 未发现受影响的 `std::vector<bool>` 特化或内联实现。 | 对原生容器容量设置可信上限，新增调用方后复核分配计算。 |
| CVE-2026-115455 | 未发现宽字符流提取到 `std::bitset` 的隐藏或内联实现，包含匹配 CRC 的 riscv64 发布件。 | 避免用受影响接口处理不可信宽字符流，更新工具链或发布件后重新审核。 |

共享库中的 CVE-2026-125709 已通过五架构回补和 ABI 门禁处理。Snappy 的六个发布 JNI 变体已按原工具链剥离后逐字节复现，并检查完整及内联符号，未发现受影响的 filebuf 实现。本轮沿用该依赖身份与边界。[共享库证据](security-gcc-filebuf-ports-2026-11-17.md) · [Snappy 静态链接证据](security-snappy-static-reproduction-2026-11-18.md)

ppc64le 还需保留 CVE-2026-97399 的架构条件说明。该 LOW 告警涉及 Power8 优化的 `strncasecmp`，需要攻击者控制的字符串在页尾匹配，并使下一字节不可读。Debian 13 原包仍受影响。当前没有本项目业务输入进入此原生接口的已确认链路，后续原生字符串调用需要检查长度及缓冲区边界，采用兼容回补时补充页边界回归。amd64、s390x、riscv64 的对应原始源包告警不具备 Power8 架构前提。[Debian 与上游说明](https://security-tracker.debian.org/tracker/CVE-2026-97399)

## 环境与边界

按用户授权清理 `.testhub` 历史 QA 目录和 `output` 历史 QA 目录中的工作区、数据库、截图及临时包，共删除 2,353 个文件或子目录条目，保留 1,291 个可复用脚本和小型报告。可用空间从 4,820,533,248 字节增至 54,132,596,736 字节，约释放 45.9 GiB。清理范围依据历史目录命名和活动进程占用检查确定。

默认 Colima 维持停止。原盘、离线快照和修复副本保留在原位置，清理前后大小、分配块、inode、修改时间和权限的元数据对照一致。

amd64 在 Rosetta，其他三个目标在 QEMU 10.2.3 用户态执行。本机验证的范围包括目标架构用户态程序、库和服务协议，真实目标硬件、目标内核及 Kubernetes 部署仍需目标环境验收。
