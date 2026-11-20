# CVE-2026-125709 s390x、ppc64le、riscv64 和 Snappy 复核

后续调试证据见 [Snappy 发布二进制静态链接复现](security-snappy-static-reproduction-2026-11-18.md)。该报告记录六份发布 ELF 的字节对照、完整符号及内联代码核查。

## Debian 原包基线

三种架构的 `libstdc++6 16.2.0-4` 均从 Debian 官方归档下载，并校验项目中原有的包 SHA-256。三个构建日志的 73 项补丁顺序一致，与 arm64 序列的摘要相同。候选使用 GCC 16 稳定分支的同一源码回补 `68b1b9056c70acb31f2964963b51c480ff820602`。截至本轮核查，Debian 仍将原包标为受影响。[Debian 跟踪器](https://security-tracker.debian.org/tracker/CVE-2026-125709) · [GCC 上游提交](https://github.com/gcc-mirror/gcc/commit/68b1b9056c70acb31f2964963b51c480ff820602)

| 架构 | 原包动态导出 | 原包架构属性 |
| --- | ---: | --- |
| s390x | 6,473 | ELF64 大端，IBM S/390，`e_flags=0x0`，原编译器默认 z196、128 位 long double |
| ppc64le（Debian ppc64el） | 6,744 | ELF64 小端，`e_flags=0x2` ELFv2，4,380 个导出含本地入口属性，原包拥有 IBM 和 IEEE long double 接口 |
| riscv64 | 6,157 | ELF64 小端，`e_flags=0x5`、RVC、double-float ABI，`.riscv.attributes` 包含完整 ISA 扩展和 16 字节栈对齐 |

三份原包均使用 `libstdc++.so.6` SONAME，最高 GLIBC 需求为 2.38，含 RELRO、非执行栈以及 SystemTap 的 `catch`、`throw`、`rethrow` 探针。ppc64le 现有解析器漏掉了 `[<localentry>: 8]` 等字段，旧计数为 2,364。增强的比较读取整套动态导出及本地入口属性。对照替换 PowerPC 本地入口、RISC-V ISA 扩展和 S/390 ELF flags 时均被拒绝。[s390x 原包日志](https://buildd.debian.org/status/logs.php?pkg=gcc-16&arch=s390x&ver=16.2.0-4) · [ppc64el 原包日志](https://buildd.debian.org/status/logs.php?pkg=gcc-16&arch=ppc64el&ver=16.2.0-4) · [riscv64 原包日志](https://buildd.debian.org/status/logs.php?pkg=gcc-16&arch=riscv64&ver=16.2.0-4)

## Snappy 发布产物与调试符号

TestHub 重新构建 JAR 内嵌的 `snappy-java-1.1.10.12.jar` SHA-256 为 `1c03ce77b3f2e926c25a952612e372404d80bc88c6c8052bede964804fabd732`。五种 Linux 架构的 JNI ELF 均与 Xerial 发布标签和 Maven sources JAR 中的对应文件逐字节一致。所有样本均缺少 `.symtab`、`.gnu_debuglink` 和 DWARF 调试区，动态符号表未展示 `basic_filebuf`、`__basic_file` 或受影响的文件流实现。链接清单和动态依赖表支持静态链接部分 libstdc++ 的判断。[Maven Central](https://central.sonatype.com/artifact/org.xerial.snappy/snappy-java/1.1.10.12) · [上游链接和 strip 规则](https://github.com/xerial/snappy-java/blob/v1.1.10.12/Makefile)

| 发布二进制 | Build ID | 额外符号核查 |
| --- | --- | --- |
| Linux x86_64 | 无 | Debian 同版本的调试包对应有 `libsnappy.so.1` 动态依赖的发行版构建 |
| Linux aarch64 | `15f2e7563e48e4307a2cb98e02362190ceb04717` | Debian 和 elfutils debuginfod 均返回 404 |
| Linux s390x | 无 | 对应 Debian 构建的链接依赖不同，上游当前发布页无调试包 |
| Linux ppc64le | 无 | 对应 Debian 构建的链接依赖不同。2026-10-03 上游原始 native 构建未提供可下载产物 |
| Linux riscv64 | `d4a4e2abb0022e82ece1fcf7e454935768edc4bf` | Debian 和 elfutils debuginfod 均返回 404 |

11 月 17 日核查时，Xerial 发布和源码包没有另附调试文件，原始 native 构建运行的公开产物列表为空。Debian dbgsym 对应含 `DT_NEEDED libsnappy.so.1` 的 Debian JNI ELF。Maven 发布产物的指纹和链接方式不同。按上游源码历史检查了 Snappy 1.1.10 和 Bitshuffle 0.3.4 的 JNI 入口和编译清单。11 月 18 日使用逐字节匹配的发布产物完成隐藏和内联代码核查。 [Xerial 原始 native 构建](https://github.com/xerial/snappy-java/actions/runs/37107885821) · [Debian 同版本 dbgsym](https://packages.debian.org/sid/libsnappy-jni-dbgsym) · [逐字节复现](security-snappy-static-reproduction-2026-11-18.md)

## 正式构建与复扫

三种架构均通过候选 ABI 门禁后接入工作区构建。生产 Dockerfile 从工作区重建最终镜像，并重新执行原生测试和 Trivy 扫描。最终镜像提取的三个 libstdc++ ELF 分别与通过门禁的候选相同，再和对应 Debian 原包复核。镜像包清单和 MD5 校验通过，报告的包版本为 `16.2.0-4+testhub1`。

| 架构 | 最终镜像 ID | 镜像内 libstdc++ ELF SHA-256 |
| --- | --- | --- |
| s390x | `sha256:32de195633a0aa402c65b50489ff5e8b826cdc99dfa910b92fcbc08d79a62997` | `950755267e99062f1a6c12cd17ab112f42eb8d9c7cd149a1f12c0887eed6f9f2` |
| ppc64le | `sha256:19e4b6d00d456f29fca4f9d5decfc3e954f768f3bfff1b151dfcc3d3e7e10ae8` | `8996ae364599fc7b5a84b1852b30b776dbd361f575465262b4f704bf826df4f7` |
| riscv64 | `sha256:b93e3453423aff4b6383d4ab01fb4b95af6d70fe85a889c198e12c7d006a07fc` | `f7bedbb6333881333c4404e721b907a8e8739555f46e81cf3729e68416798537` |

- 每种架构都以新、旧 C++ 字符串 ABI 验证对齐分配、异常、线程、future、long double 流式转换及窄、宽字符文件缓冲。加载跟踪确认两个 `overflow` 符号绑定到镜像内的回补库。ppc64le 测试镜像显式编译 IEEE 和 IBM 两种 long double 模式，分别确认导入 `GLIBCXX_IEEE128_3.4.29` 和 `GLIBCXX_LDBL_3.4`，并在最终 API 镜像通过。
- 每个最终镜像的 Temurin DOM/SAX、XLSX 回环、AWT 字体渲染及 FreeType 映射验证通过。s390x 的 Java 探针首次在 QEMU 下超过 30 秒，使用 180 秒时限重跑后通过。
- ppc64le 的固定 Distroless 基础镜像把 `/usr/bin/java` 链到不存在的 `temurin-21-jre-ppc64le` 目录。构建仅对该架构校验旧链接和实际可执行文件，再将链接改为 `temurin-21-jre-ppc64el`。最终镜像通过正常 `java` 入口完成回归。
- Maven 在构建主机架构打包，目标架构分别构建运行库。三份最终应用 JAR 都为 `533a21518459c18070a98fa27b306868dd904ac1dcd911b99162a531570149bc`，均包含已经逐字节核查的 Snappy 发布二进制。
- 每份最终镜像的 Trivy 0.65.0 结果均为 66 条记录、42 个唯一编号。0 CRITICAL、8 HIGH、32 MEDIUM、20 LOW、6 UNKNOWN，应用 JAR 0 条，可修复 HIGH/CRITICAL 0 条，扫描门禁通过。CVE-2026-125709 未出现在扫描结果。该项修复依据为上游补丁和 ELF 复核。漏洞库更新于 2026-11-17 06:59 UTC，Java 库更新于 00:59 UTC。Java 库从官方 GHCR OCI 层续传，按清单 SHA-256 校验后离线导入。
- 20 项包摘要、扫描门禁、DNS 配置及 QA 磁盘守卫脚本测试通过。arm64 构建阶段重新校验 6,157 个动态导出及 BTI/PAC/GCS、RELRO、非执行栈，通过。

## 剩余措施

架构运行验证在 QEMU 8.2.2 的 Linux 用户态完成，实体 s390x、POWER、RISC-V 的硬件行为仍需部署环境验证。保留只读根文件系统、非 root 执行、临时目录容量限制及原生写入失败告警。

2026-11-18 已用六份逐字节匹配的 Snappy 发布产物完成隐藏和内联代码复核。完整符号、实际链接的归档成员和 DWARF 均未发现受 CVE-2026-125709 影响的文件缓冲实现。引入新 JNI 或更换 Snappy 发布版本时重新核对发布指纹及静态链接。继续跟踪 Debian 正式修复并在升级时重新校验覆盖层。 [Snappy 发布二进制静态链接复现](security-snappy-static-reproduction-2026-11-18.md)

默认 Colima 保持停机，原盘、离线快照和修复副本均保留。
