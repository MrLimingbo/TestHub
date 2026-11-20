# CVE-2026-125709 的 Debian arm64 回补

正式镜像的 arm64 构建已接入 GCC 16 维护分支的 PR118099。构建使用 Debian gcc-16 16.2.0-4 源包、原 arm64 补丁顺序和运行库构建参数。只重新构建共享 libstdc++，产物登记为 `libstdc++6 16.2.0-4+testhub1`。Debian 的 gcc-16-base、libgcc-s1 保持 16.2.0-4。其他架构仍使用原 Debian 运行库包。 [GCC 16 补丁](https://github.com/gcc-mirror/gcc/commit/68b1b9056c70acb31f2964963b51c480ff820602) · [Debian 修复记录](https://security-tracker.debian.org/tracker/CVE-2026-125709)

## 68 个导出缺口和架构属性的原因

| 缺口 | 对照证据 |
| --- | --- |
| 2 个 C++23 模块初始化符号 | 上一次独立构建中，`std` 模块导入 `cfenv` 时编译失败，`std.compat` 随之失败。构建自动用空对象继续链接。按 Debian 源包和参数编译后，两个模块导出均恢复。 |
| 66 个线程和兼容符号 | 独立构建没有 GCC 顶层构建生成的 `libgcc/gthr-default.h`，令 gthreads 探测失败。仅恢复 Debian 编译和链接参数时，恰好仍缺这 66 项。按 libgcc 原规则将该生成头链接到 `gthr-posix.h` 后，gthreads 探测为 yes，`std::future`、条件变量等全部缺口消失。 |
| BTI、PAC、GCS | 原包在编译目标库时使用 `-mbranch-protection=standard`，构建日志记录的 binutils 为 2.47.50.20261021-1。上一次独立编译未传递 branch protection 参数，且只升级了汇编器。同一编译器及链接器构建的简单共享库对照证实，只增加此参数即可从无属性变为同时具备三项属性。回补库也保留三项 ELF 属性。 |

对照依据为 Debian arm64 16.2.0-4 的构建日志、buildinfo 和官方包。原包先由 Debian GCC 16.2.0-3 引导，然后用本次构建的 16.2.0-4 编译目标运行库。回补使用 Debian 已发布的 16.2.0-4 编译器，仅构建目标共享库。源归档、Debian 补丁归档、arm64 补丁序列和上游变更均校验 SHA-256。 [Debian 构建日志](https://buildd.debian.org/status/logs.php?pkg=gcc-16&arch=arm64&ver=16.2.0-4) · [Debian buildinfo](https://buildinfos.debian.net/buildinfo-pool/g/gcc-16/gcc-16_16.2.0-4_arm64.buildinfo)

## 落地门槛

干净重编译的回补对照与 Debian 原库的 **6,157 个动态导出条目一致**，保留符号版本、默认版本、类型、绑定、可见性以及 OBJECT/TLS 大小。旧报告的 6,155 是归并默认版本别名后的计数。回补前后两种 `basic_filebuf::overflow` 实例的编译产物均有变化，确保源补丁经过了实际编译。正式 Docker 构建再次验证去除调试信息后的产物，检查 SONAME、动态依赖、glibc 版本需求、BTI/PAC/GCS、RELRO 和非执行栈。最高 GLIBC 需求仍为 **2.38**，低于镜像 glibc 2.41。

构建和启用回补的入口分别是 `scripts/security/build-gcc-filebuf-backport.sh` 与 `scripts/security/prepare-gcc-runtime.sh`。发布原生校验针对 arm64 检查本地回补版本、上游提交和包文件清单。兼容性回归覆盖正常窄字符和宽字符缓存写入、读回、`std::future`、异常展开和既有 AWT/XLSX/DNS 用例。

## 最终镜像验收与复扫

正式 Dockerfile 构建的镜像 ID 为 `sha256:48b1a32a177a90e7d36b7effb433f1165d280a2e54eef57942f34b978cca3077`，Linux arm64。从镜像提取的共享库 SHA-256 为 `72c2b2233e416ebaa69494b6bb31da8e6004640e7da34e9367e34248a0fdc00e`。从实际镜像独立执行 ABI 检查，6,157 个动态导出条目和架构属性全部通过，包元数据记录上游提交 `68b1b9056c70acb31f2964963b51c480ff820602`。

实际 API 镜像使用默认入口，在独立 Compose 项目通过 MySQL 8.4.13、Redis 7.4.12 和固定源码版本 MinIO 的验收。首次启动和重启后 readiness 均为 UP。核验了 MySQL 文件元数据与审计、Redis 会话跨重启及登出、S3 签名读取、下载字节一致性和删除后的 404。本轮运行真实镜像验收，上一轮 1,380 项宿主 JDK 全量套件记录见 [隔离验证报告](security-cve-2026-125709-validation-2026-11-17.md)。

最终镜像通过 22 次原生库与环境检查，以及 3 组 DNS/TCP 回归。C++ 文件缓冲与 `std::future` 检查通过，装载追踪确认使用 `/lib/aarch64-linux-gnu/libstdc++.so.6`。首次重建原生测试辅助镜像因下载超时中断，复验按本轮从原生测试 Dockerfile 成功构建的辅助镜像 ID 运行全部原生用例。安全脚本测试 16/16、Bash/Python 语法与 `git diff --check` 通过。

2026-11-17 13:00（Asia/Shanghai）在原生回归通过后使用 Trivy 0.65.0 和同一数据库对照原库与回补镜像。两边均为 **66 条记录、42 个独立 CVE**，包括 0 CRITICAL、8 HIGH、32 MEDIUM、20 LOW、6 UNKNOWN。Java 依赖记录、有可用修复的 HIGH/CRITICAL 均为 0，发布门禁通过。漏洞库与 Java 索引更新时间分别为 11 月 17 日 01:05:25、00:59:10 UTC。原库和回补镜像都没有匹配 CVE-2026-125709 的扫描记录。arm64 修复依据是补丁来源、干净重编译、实际镜像文件和 ABI 验证。最终原始扫描报告 SHA-256 为 `c8dd0df4ba00bfffb3f2de646daa947326fa632ee2e42f3ce9ccdc04e3ca96f6`。

其他架构的共享库及已经静态链接的下游产物不在这次回补范围内。继续跟踪 Debian/Temurin 的修复更新。保留只读根文件系统和临时目录容量限制，原生写入失败应及时上报并停止连续重试。

隔离 Colima、测试容器和数据卷已清理，临时验收文件移至废纸篓。默认 Colima 保持 Stopped，原盘、离线快照和修复副本均保留在原位置，三份文件的大小和权限复核为 100 GiB、0600。
