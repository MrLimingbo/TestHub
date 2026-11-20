# CVE-2026-125709 amd64 回补与下游核查

后续的 s390x、ppc64le、riscv64 回补和 Snappy 调试符号核查见 [其余架构与 Snappy 核查](security-gcc-filebuf-ports-2026-11-17.md)。

## 共享库

amd64 使用 GCC 16 稳定分支提交 `68b1b9056c70acb31f2964963b51c480ff820602` 的同一份源码补丁。Debian 仍将 `gcc-16 16.2.0-4` 标为受影响。漏洞发生在 `std::basic_filebuf` 处理失败的逐字节写入时。正常输入、构建产物及程序加载信息用来验证兼容性和可达性。[Debian 安全跟踪器](https://security-tracker.debian.org/tracker/CVE-2026-125709) · [上游提交](https://github.com/gcc-mirror/gcc/commit/68b1b9056c70acb31f2964963b51c480ff820602)

采用 amd64 原包的 72 项 Debian 补丁顺序、`-fcf-protection -mshstk`、CET configure 和 `systemtap-sdt-dev 5.6-1`。arm64 保留已有补丁顺序及 `-mbranch-protection=standard`。参照包中的 amd64 ELF 摘要为 `912b58566a48f860700ae033e3806d193123ed37ad4b76913edfdc37e675e403`。[Debian amd64 构建日志](https://buildd.debian.org/status/fetch.php?pkg=gcc-16&arch=amd64&ver=16.2.0-4&stamp=1792886944&raw=1) · [Debian 构建环境清单](https://buildinfos.debian.net/buildinfo-pool/g/gcc-16/gcc-16_16.2.0-4_amd64.buildinfo)

| 检查 | amd64 原包和候选的结果 |
|---|---|
| 动态 ABI | 6,166 个导出完全一致，包含版本后缀、类型、绑定、可见性和数据符号大小 |
| 加载契约 | SONAME 为 `libstdc++.so.6`，动态依赖一致，最高 GLIBC 需求为 `2.38` |
| ELF 加固 | RELRO、非执行栈一致。两份 ELF 都没有声明 IBT/SHSTK GNU 属性，窄字符和宽字符的 `overflow` 入口都带 `endbr64` |
| SystemTap | `catch`、`throw` 和 `rethrow` 三个探针齐全。对照构建漏掉依赖时，门禁拒绝打包 |
| C++ 兼容性 | 原包和修复候选在新旧两套字符串 ABI 上均通过线程、future、异常、对齐分配及文件缓冲测试。文件测试覆盖窄字符和宽字符，包含缓冲写入、关闭后以非缓冲模式追加以及完整读回 |

amd64 的执行验证使用 Apple Silicon 上的 Rosetta Linux 用户态转译。ELF 属性和机器码比较可以验证产物，CET 的硬件执行能力仍需在实体 amd64 主机上验证。

## 已经静态链接的下游

共享库覆盖只影响动态加载。逐个检查同一 Distroless 摘要中的 ELF，结合 `DT_NEEDED`、导入和定义符号，以及 Temurin 匹配 Build ID 的官方调试包。Snappy 核查也覆盖了其上游构建清单列出的 Snappy 1.1.10 和 Bitshuffle 0.3.4 的 JNI 构建源文件。

| 产物 | 核查结论 |
|---|---|
| Temurin 21.0.13+9 | amd64 45 个、arm64 44 个 ELF 均有符号表，分别匹配 44 和 43 个官方调试文件。HotSpot、`libjimage` 和 `libfontmanager` 含 C++ 定义，无 `basic_filebuf` 或 `__basic_file` 实现或动态 libstdc++ 依赖 |
| gRPC Netty 1.81.0 | 两种架构的 TLS 和 epoll JNI 均保留本地符号表，未发现受影响的文件流实现。TLS JNI 有内嵌 C++ 定义 |
| Snappy JNI 1.1.10.12 | 两种架构 Linux JNI 无动态 libstdc++ 依赖，公开符号含 C++，构建配置确认使用静态链接。公开二进制符号和对应 JNI/压缩源码未显示文件流调用。发布产物清除了本地符号，保留内联代码和私有符号的证据限制 |
| 其余基础镜像 ELF | 两种架构分别审计 289 个非 JRE ELF。只有旧版系统 libstdc++ 自身含相关实现。镜像覆盖层替换该共享库 |
| MinIO QA 服务 | 固定源码构建用 `CGO_ENABLED=0`，没有静态 libstdc++ 链接路径 |

实际构建阶段 JAR 的 24 个 ELF 条目逐字节匹配用于分析的产物。FreeBSD、SunOS、Android 等载荷不属于测试的两种 Linux 运行环境。镜像中 Temurin 原带 FreeType 由修复后的 Debian 共享库提供。[Temurin 调试包](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9) · [OpenJDK 静态链接说明](https://github.com/openjdk/jdk21u/blob/master/doc/building.md) · [Snappy 1.1.10.12 链接配置](https://github.com/xerial/snappy-java/blob/v1.1.10.12/Makefile.common)

s390x、ppc64le、riscv64 的共享库仍用 Debian 原包，需要逐一验证回补后的 ABI。使用已验证架构部署，保持非 root、只读根文件系统和有容量限制的临时目录。对原生写入错误告警并终止连续写入，限制新增 JNI 或插件引入。随供应方提供正式回补或匹配调试产物，更新基础包并复核静态产物。

## 最终镜像和全量回归

镜像为 `linux/amd64`，镜像 ID 为 `sha256:9d44fb38e00160b486d292e8137cb0ffe924141c37f7f66f75e77c954f4f33b3`。镜像中 `libstdc++6 16.2.0-4+testhub1` 的 ELF SHA-256 为 `f3f971cb74870a2c1d6cead45db641ae05db651ba25ccff1ec945ab168cfe57d`。最终 JAR SHA-256 为 `6559e7fb4f038563b3860cb48562fdd84c45387c864498499abcd0d01c224420`，与已审计构建阶段 JAR 一致。

| 验证 | 结果 |
|---|---|
| 生产 Dockerfile | amd64 完整构建通过。对镜像提取的 ELF 重新运行 6,166 个导出、架构属性和探针检查，通过 |
| arm64 构建回归 | 使用同一份构建脚本重新生成共享库，与 arm64 原包的 6,157 个导出、BTI/PAC/GCS、RELRO 及非执行栈一致 |
| 最终镜像原生回归 | 新旧 ABI 都加载 `/lib/x86_64-linux-gnu/libstdc++.so.6`，观察到窄、宽字符 `overflow` 的绑定。正常 Expat 窄、宽字符解析、FreeType、AWT 渲染、XLSX 回环和 7 项运行环境解析器检查通过 |
| 隔离镜像验收 | 运行的 API 镜像 ID 与复扫一致。MySQL 文件元数据和审计、Redis 会话跨 API 重启及登出、S3 签名读取和删除、下载字节一致性通过 |
| 1,380 项隔离后端全量回归 | `test-mysql-backend.mjs` 退出 0，Maven 和 161 份 JUnit 报告一致。1,380 通过，0 失败，0 错误，0 跳过 |
| 安全脚本回归 | 20 项通过，包含包摘要、下载失败、镜像扫描退出码和隔离验收磁盘守卫 |
| Trivy 0.65.0 | 66 条记录，42 个唯一漏洞编号。0 CRITICAL、8 HIGH、32 MEDIUM、20 LOW、6 UNKNOWN，应用 JAR 0 条，可修复 HIGH/CRITICAL 0 条，发布扫描门禁通过 |

漏洞库更新于 2026-11-17 01:05 UTC，Java 库更新于同日 00:59 UTC。扫描没有列出 CVE-2026-125709。该项修复结论来自固定的上游源码补丁、包清单校验及最终镜像 ELF 复核。

为排除隔离验收中 MinIO 镜像构建时的 APT 下载停滞，MinIO 运行阶段使用 Debian HTTPS 源，并以 Go 构建阶段的 CA 文件引导验证。安装仍由 APT 验证 Debian 仓库签名。MySQL、Redis、S3 验收及镜像验收各自拥有独立服务和数据卷。

默认 Colima 保持停机，原盘、离线快照及修复副本均保留。
