# glibc DNS 回补与 Temurin 模板核查

核查基线是 TestHub 当前固定的 Distroless Java 21 索引 `sha256:d978eefd303c59c42f382aef811a0e41f2c68a6e5c319a23769cd3d1d5bf8e3c`。glibc 源码与运行包以 Debian 13 的 `2.41-12+deb13u4` 为准，DNS 回补的来源是 GNU glibc 提交 `506ea57086bfb9ce3daff1c14246a1cb532aba0a`。核对时 Debian 13 跟踪器仍列为受影响。 [Debian CVE 状态](https://security-tracker.debian.org/tracker/CVE-2026-8674) · [Debian 源包清单](https://deb.debian.org/debian/pool/main/g/glibc/glibc_2.41-12+deb13u4.dsc) · [GNU 修复提交](https://sourceware.org/git/?p=glibc.git;a=commit;h=506ea57086bfb9ce3daff1c14246a1cb532aba0a)

生产 Dockerfile 已接入 `libc6 2.41-12+deb13u4+testhub1`，构建时校验固定源包及补丁摘要、原包身份和两份运行库的 ABI。发布原生验收同时核对新包身份、补丁来源和文件清单。五架构均通过 ABI、包清单和 JVM 实际加载核查，arm64 正式 Dockerfile 产物通过隔离服务验收和全级别复扫。

## Debian 原包基线

从固定索引按架构验证 OCI 清单及各层 SHA-256。镜像中的 `libc.so.6` 和动态加载器与对应的 Debian `libc6` 原包逐字节一致。两份包的 `md5sums` 均与基础镜像所存清单逐字节一致，原包解压后共核对 1,577 项文件校验。

| OCI 架构 | Debian 架构 | libc6 文件 | libc-bin 文件 |
| --- | --- | ---: | ---: |
| amd64 | amd64 | 284 | 31 |
| arm64 | arm64 | 284 | 31 |
| s390x | s390x | 288 | 31 |
| ppc64le | ppc64el | 283 | 31 |
| riscv64 | riscv64 | 283 | 31 |

arm64 当前 API 镜像的 284 项 libc6 文件校验均通过。Distroless 预先裁减 libc-bin 工具和文档，镜像保留其中 13 个文件，抽取后全部符合原包清单。glibc 兼容性比较沿用该裁减边界。

独立 Colima 上的当前 arm64 API 镜像基线 `sha256:deed99e85d337f6c4f691447bcb5ea7d1cfea92fbb873fa311b12b7366348cd7` 通过隔离 MySQL 文件元数据及审计、Redis 会话跨 API 重启及登出、S3 签名读取和删除、下载字节一致性、readiness 和专用 Compose 资源清理。

## 逐架构回补和包验证

四个新增架构使用 Debian GCC 14 交叉工具链、目标架构的 SELinux 等开发依赖及 Debian 的 `build_libc` 规则。构建保留 Debian 的架构选项，使用 `LIBGD=no` 跳过独立的 `memusagestat` 开发工具。候选基于 glibc 2.41 原包和同一上游补丁，完整对照版本化导出、符号类型／绑定／可见性、数据和 TLS 大小、GNU 架构属性、动态依赖、SONAME、RELRO 和不可执行栈。

| 架构 | libc 导出 | 加载器导出 | 架构属性对照 | 重打包后 libc6 文件 |
| --- | ---: | ---: | --- | ---: |
| amd64 | 3,141 | 38 | x86-64 baseline ISA、ELF 标志及 SONAME 匹配 | 284 |
| arm64 | 3,053 | 39 | BTI/PAC、ELF 标志及 SONAME 匹配 | 284 |
| s390x | 3,349 | 39 | ELF 机器类型、标志及 SONAME 匹配 | 288 |
| ppc64le | 3,275 | 41 | ELFv2、GNU Power 浮点属性及局部入口字段匹配 | 283 |
| riscv64 | 3,010 | 39 | RVC/LP64D、ISA 字符串、16 字节栈对齐及 SONAME 匹配 | 283 |

amd64 在 Rosetta，s390x、ppc64le、riscv64 在 QEMU 10.2.3 用户态通过 Temurin 21.0.13+9 启动。各候选 JRE 用 `/proc/self/maps` 检查 libc 和对应加载器的可执行映射，两份映射文件的 SHA-256 都与该架构回补产物一致。arm64 原生验证使用下节的完整 API 镜像。

## arm64 隔离验收

使用 Debian 原版 GCC 14、构建规则、补丁序列和本轮回补源码重新构建。libc 的 3,053 个版本化导出、加载器的 39 个版本化导出、ABI 属性、SONAME、BTI/PAC、RELRO 和不可执行栈均与原包匹配。两份 ELF 的 SHA-256 与上一轮 arm64 候选相同。

将两份库装入原 Debian `libc6` 包结构，保持其他文件与维护脚本，更新成 `2.41-12+deb13u4+testhub1`，记录上游提交并重新计算两项文件校验。重新打包、解包和从候选 API 镜像导出后均验证 284 项清单；`libc-bin` 的版本、保留文件与裁减范围和基线相同。

候选 API 镜像 `sha256:c92caa9d48aee2211e6e32dd4c710ba5c0643fe4a17ec3fe5af8fea0d54e3226` 使用新的 Compose 项目通过 MySQL 元数据和审计、Redis 会话跨重启及登出、S3 签名读取和删除、下载字节一致性和 readiness 验收。验收结束后删除该项目的容器和数据卷。

用同一官方 Trivy 0.65.0 漏洞库对照扫描基线与 arm64 候选的 OS 软件包。两者均为 66 条记录、42 个独立 CVE，CVE-2026-8674 仍关联 `libc6` 和 `libc-bin`。

## 正式镜像验收与复扫

使用与工作区一致的 Dockerfile 和构建脚本生成 arm64 API 镜像 `sha256:25ba96576e8ee4922ab0d6c44182418dc92f82d5ebbfe27a042f7af9bfe5bcbf`。最终镜像实测 284 个 `libc6` 文件校验通过，原生验收的镜像清单检查同步通过。通过两个独立的 Compose 项目复验 MySQL 元数据及审计、Redis 会话跨 API 重启及登出、S3 签名读取、删除后的 404、下载字节一致性和 readiness。

第二个项目在 API 重启后读取实际 JVM 的 `/proc/<pid>/maps`，并在该进程的根文件系统对两个可执行映射文件重新计算摘要。两份摘要与事先通过 ABI 的 ARM 候选完全一致。

| JVM 实际映射路径 | SHA-256 |
| --- | --- |
| `/usr/lib/aarch64-linux-gnu/libc.so.6` | `046c07e803f5379bb6c0f89a26eb9a1782e95d2797c3df7b4eab2f0647a3bde6` |
| `/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1` | `3a10600dca459a8bc57343b00098642e8a6fe8fb8f7921bc31aa466da48ab756` |

同一镜像 ID 用 Trivy 0.65.0 执行全级别漏洞扫描和可修复 HIGH／CRITICAL 门禁。OS 包为 66 条、42 个独立 CVE（8 HIGH、32 MEDIUM、20 LOW、6 UNKNOWN、0 CRITICAL），实际 JAR 为 0，门禁退出 0。临时版本的 `libc6` 和 Debian 原版 `libc-bin` 仍按源包关联报告 CVE-2026-8674。修复依据是上游补丁、构建门禁和程序实际加载的文件。原始报告保留这两条告警。漏洞库更新于 2026-11-17 19:04:24 UTC，Java 索引更新于 2026-11-18 00:58:01 UTC，原始全级别结果 SHA-256 为 `08dfc7643d974cc7bc96c5ad479a7e942d1d3c5d107512c3c2fcdae2497886e9`。

部署继续通过 `/app/testhub-runtime-guard` 启动。Helm 的显式覆盖和运行时继承列表继续校验，控制 DNS 配置来源并在修改搜索域后重启 API。待 Debian 13 推出正式修复后，用镜像摘要及原生门禁复验替换覆盖层。完整 API 协议验收及扫描覆盖 arm64 正式镜像，另外四架构的运行验证覆盖临时回补 JRE 镜像。

## Temurin 发布证据

对 Adoptium `21.0.13+9` 的五个官方 Linux 调试包校验发布资产 SHA-256。调试文件与固定镜像 JRE 逐文件比较 GNU Build ID、`.gnu_debuglink` 文件名和 CRC32，以及 ELF 机器类型、字长、字节序和 `.text` 地址、大小。riscv64 发布件没有 Build ID，依据同一发布资产的 SHA-256、调试链接 CRC32 及 ELF 节信息匹配。五架构的 `lib/libjsig.so` 和 `lib/server/libjsig.so` 各为同一文件的两个字节完全一致的副本。 [Temurin 官方发布资产](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9)

| 架构 | 镜像内 ELF | 匹配独立调试文件 | DWARF 编译单元 | DWARF 内联记录 |
| --- | ---: | ---: | ---: | ---: |
| amd64 | 43 | 42 | 2,130 | 2,151,927 |
| arm64 | 42 | 41 | 2,088 | 1,678,309 |
| s390x | 40 | 39 | 1,684 | 1,198,856 |
| ppc64le | 40 | 39 | 1,918 | 1,759,720 |
| riscv64 | 40 | 39 | 1,944 | 1,640,463 |

200 份独立 ELF 调试文件与镜像中的 205 个 ELF 路径对应。检查符号表、DWARF 名称、链接名称和行表文件，检查 404,655 个符号条目及 8,429,275 个内联记录，四项均未发现相关实现。证据限定于固定的 Temurin 发布二进制；新增 JNI 和重新构建后的二进制需要复验。

| CVE | 处理结论与验证依据 | 剩余措施 |
| --- | --- | --- |
| [CVE-2026-102010](https://security-tracker.debian.org/tracker/CVE-2026-102010) | 五架构未发现 PBDS 二叉堆相关完整／局部符号、DWARF 实例或相关行表引用。Debian GCC 16 已修复头文件。 | 新增 C++ 构建使用修复头文件；更换 Temurin/JNI 后重新审核实际编译产物。 |
| [CVE-2026-114690](https://security-tracker.debian.org/tracker/CVE-2026-114690) | 五架构未见 GNU 并行搜索模板的实现或内联证据。GCC 16.2.0-4 仍受影响。 | 新增原生模块时继续检查并行模式，选用修复头文件后重编译调用方。 |
| [CVE-2026-114691](https://security-tracker.debian.org/tracker/CVE-2026-114691) | 五架构未见 `std::vector<bool>` 相关特化或行表证据。GCC 16.2.0-4 仍受影响。 | 原生容器容量约束为可信上限；出现调用方后用修复头文件重编译。 |
| [CVE-2026-115455](https://security-tracker.debian.org/tracker/CVE-2026-115455) | 五架构未见宽流到 `std::bitset` 的提取模板，包括按调试链接 CRC 匹配的 riscv64。GCC 16.2.0-4 仍受影响。 | 避免用此接口解析不可信输入；新 riscv64 发布件优先复验。 |

安全脚本回归 20 项通过，Shell/Python 语法和 `git diff --check` 通过。本轮专用 Colima、镜像、缓存、数据卷和临时工作目录已清理。默认 Colima 仍为 Stopped，原盘、离线快照和修复副本均在原位置，各为 100 GiB、0600。
