# Snappy 发布二进制静态链接复现

2026-11-18，核查 TestHub 内嵌的 `snappy-java 1.1.10.12` 对 CVE-2026-125709 的静态链接暴露。内嵌 JAR SHA-256 为 `1c03ce77b3f2e926c25a952612e372404d80bc88c6c8052bede964804fabd732`。以 JAR 中的 Linux JNI ELF 为字节对照，覆盖部署镜像的五种架构，并单独核查 JAR 随附的 x86_64-musl 变体。

发布历史中，amd64、s390x 和 riscv64 的二进制由提交 `1f07c3182c2dc89d4226e9a6d8945b8458870a0a` 带入，实际构建源码是 `f2e97f27be0dc6c691369040ba8a673bface484c`。arm64 和 ppc64le 由 `d494eda82d1804d651b400745725b7307102d573` 带入，实际构建源码是 `d19b2d18eb20efa4d1eb36c0d1ea9ebbdbb79b57`。musl 变体从 `ded9f16e058c67ff138d7c573f4a9a460cf58fea` 带入，源码归档中该 ELF 与发布件一致。[2023 年产物更新](https://github.com/xerial/snappy-java/commit/1f07c3182c2dc89d4226e9a6d8945b8458870a0a) · [2026 年产物更新](https://github.com/xerial/snappy-java/commit/d494eda82d1804d651b400745725b7307102d573) · [musl 提交](https://github.com/xerial/snappy-java/commit/ded9f16e058c67ff138d7c573f4a9a460cf58fea)

重建使用相应源码中的 Makefile 及链接参数，离线提供 Snappy 1.1.10、Bitshuffle 0.3.4 和已提交的 JNI 头。基线保留完全剥离前的 ELF 和 GNU 链接映射，并用原工具链的 `strip` 生成对照文件。仅对通过 `cmp` 的基线使用完整符号表和静态归档成员记录。工具镜像中的 x86_64 构建程序由 Rosetta 执行。[原发布 Makefile](https://github.com/xerial/snappy-java/blob/f2e97f27be0dc6c691369040ba8a673bface484c/Makefile) · [Dockcross 工具链](https://github.com/dockcross/dockcross)

带 `-g` 的变体也运行完全剥离和字节比较。对于 arm64、riscv64，独立变体剥离后相差的 20 字节全在 GNU Build ID 描述字段，机器码、数据和异常展开节逐字节一致。将该变体的非加载 DWARF 节附回字节已匹配的基线 ELF，保留基线自身的 Build ID，并再次完全剥离。附加调试节后的文件在通过整个 ELF 的 `cmp` 后才参与内联分析。

ppc64le 分别保留 8 个带 DWARF 的编译对象，再用同一工具链从实际参与链接的对象中剥离调试节。重新链接的 ELF 通过发布字节比较后，才检查这些对象和链接中保留的运行库 DWARF。

## 核查结果

| 架构 | 已匹配的调试取证方式 | 符号表条目 | libstdc++ 归档成员 | 编译单元 | 内联记录 |
| --- | --- | ---: | ---: | ---: | ---: |
| amd64（glibc） | 带 DWARF 的完整 ELF 剥离 | 1,351 | 38 | 11 | 1,291 |
| arm64 | 附加已核对的 DWARF 节 | 1,968 | 33 | 45 | 2,403 |
| s390x | 带 DWARF 的完整 ELF 剥离 | 5,186 | 93 | 108 | 5,296 |
| ppc64le | 编译对象保留独立 DWARF | 1,465 | 33 | 45 | 2,454 |
| riscv64 | 附加已核对的 DWARF 节 | 1,192 | 34 | 47 | 1,520 |
| x86_64-musl | 带 DWARF 的完整 ELF 剥离 | 1,263 | 35 | 47 | 4,664 |

六份产物的完整符号表、实际链接的归档成员和 DWARF 均未发现 `basic_filebuf`、`__basic_file` 或相关文件流实现。s390x 链入通用 `istream`、`ostream` 和 `streambuf` 成员，其中没有受影响的文件缓冲对象。结论限定在以下发布文件指纹及 CVE-2026-125709。 [Debian 漏洞说明](https://security-tracker.debian.org/tracker/CVE-2026-125709)

## 工具链和产物指纹

| 架构 | 工具链 | 发布 ELF SHA-256 |
| --- | --- | --- |
| amd64（glibc） | GCC 4.8.3、binutils 2.17.50.0.6，Xerial CentOS 5 PIC 镜像 `sha256:8d2ff925c4a8d51c9bdf6ca1dbee59201906d8282bb43a9c88073791f1d0b6df` | `1b6b9db29b2603be5bb69bf76af473731499a92db3defab605ef98d4656583e4` |
| arm64 | GCC 8.5.0、binutils 2.30，Dockcross arm64 LTS `20260928-b67e766`，`sha256:1c7d47bc448cb7b4c28850f10f45203a7e20ea8f7032cb2ccd059d3ce4104f8f` | `d2540392eb2d856e425685a0314d0a6d641379ecd74d7d7f1475391d90fb1e54` |
| s390x | GCC 6.3.0、binutils 2.28，原配方固定 `20210210-84c47e5`，`sha256:16968007baf35e16da895d7df83c2ad6124f30c9077dd511ffe05af0870ffa4c` | `e7eeb24721f20c33e7a188ed867b3e3e27de7f0e2fc321f0c598e873a4da506d` |
| ppc64le | GCC 8.5.0、binutils 2.31.1，Dockcross ppc64le LTS `20260928-b67e766`，`sha256:90952fbdd9eb8fd868b753ebed1f3ace68d0ef0a1478f1ae0e5e182a5c33baa0` | `0217376c5c70fa507df23884b634fe74979618df50a892a3390003603897dc09` |
| riscv64 | GCC 11.3.0、binutils 2.37，Dockcross `20230905-7b2d74f`，`sha256:faf825affb8c3692ed70b162ab3ce8e62ef88b57453a87dd07eede98f906005e` | `adeddaea94e02a0458974493c77b916526cd87f6b5f032e1eea131664d387464` |
| x86_64-musl | GCC `12.2.1_git20220924-r10`、binutils `2.40-r8`，Alpine 3.18 基础镜像 `sha256:de0eb0b3f2a47ba1eb89389859a9bd88b28e82f5826b6969ad604979713c2d4f` | `5e3bfc804d826a62e70005167ea05b9474f114ff17e1edf29bbcc12718a9838c` |

首次并行解包达到宿主容量上限，清理本轮专用环境后，在有磁盘上限的独立环境完成余下架构。部署继续保持非 root、只读根文件系统、临时目录容量限制及写入失败告警。升级 Snappy 或引入新 JNI 产物时，按新发布文件的 SHA-256 重新核查静态链接。

默认 Colima 保持停机，原盘、离线快照和修复副本均保留。
