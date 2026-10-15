# 发行检查修复与开源框架升级（已审核）

用户已审核上一“原生桌面 CI”模块，并明确选择免费的 Spring Boot 4／Spring 7 路线，要求保留现有功能与接口。本模块完成该路线的本地实现、隔离回归、旧数据升级验证和发行镜像安全检查，已于 2026-10-15 获用户审核通过，并获授权提交 main、复验 verify 与三种架构的桌面 CI。此报告记录本地验收结果；远程结果在后续模块单独记录。

## 实际改动

| 范围 | 最终实现 |
| --- | --- |
| 应用框架 | Spring Boot 4.0.8、Spring Framework 7.0.9、Spring Security 7.0.7；Java 21 保持不变 |
| Servlet／网络 | Tomcat 11.0.27、Netty 4.2.19.Final；采用 Boot 4 对应的模块与包名 |
| JSON 与旧数据 | Jackson 2.22.3 继续处理业务接口与同步 JSON；同时修复 Boot 4 传递引入的 Jackson 3，统一至 3.1.7；H2 保持 2.3.232，保留既有文件格式 |
| 相关集成 | MyBatis Plus 的 Boot 4 starter 3.5.17、Springdoc 3.0.3；会话、JDBC、Flyway、测试 starter 按官方模块迁移 |
| 会话契约 | 显式沿用服务器配置的 Cookie 名称、HttpOnly、Secure、SameSite 等属性，保证 `TESTHUB_SESSION` 在嵌入式和 MockMvc 环境下保持一致 |
| MySQL 验收 | 新增 `test-mysql-backend.mjs`，独立 Compose 项目、随机凭据、全新卷和临时回环端口；需要独立数据库的测试类按其临时目录分配单独 schema |
| MinIO 基础设施 | 已撤回的社区镜像改为固定上游提交的源码构建，归档 SHA-256 校验，保持原服务器和备份客户端版本、命令及卷；镜像包含上游许可证 |
| 网页镜像 | Nginx 1.31.3 保持不变；从镜像现有 Alpine 稳定分支升级系统包，运行用户继续为 UID 101 |
| 构建上下文 | 排除 `.testhub`、`output`、临时包与扫描输出，避免开发验收数据进入 Docker 构建上下文 |

迁移依据为 [Boot 4 官方迁移说明](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)、[Spring 两项安全修复](https://spring.io/security/cve-2026-47884/)、[另一项 Spring 修复](https://spring.io/security/cve-2026-47890/)、[Jackson 3.1.7 修复说明](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7) 和 [Alpine 官方升级说明](https://docs.alpinelinux.org/user-handbook/0.1a/Working/apk.html)。MinIO 的提交、归档校验和构建／备份使用方式见 `infra/minio/Dockerfile`、`infra/minio/README.md`。

## 已完成的验证

| 验收项 | 实际结果 |
| --- | --- |
| 最终 H2 后端 | 616 项通过，失败／错误／跳过均为 0；使用全新独立工作区 |
| 最终 MySQL／Redis／MinIO 后端 | 616 项通过，失败／错误／跳过均为 0；真实 MySQL 8.4、Redis 7.4、源码构建的 MinIO；自有数据卷与测试 schema 已移除 |
| 前端 | `pnpm lint`、`pnpm test`、`pnpm build` 通过 |
| 桌面单元测试 | 545 项通过，无失败、取消或跳过 |
| 旧 H2 工作区真实升级 | 原 Boot 3 JAR 创建身份、完整需求记录、中文多行变量、密钥和附件，最终 Boot 4 JAR 原样读取；密文保持一致且可解密，旧登录会话仍返回 200 |
| 旧 MySQL／Redis／S3 真实升级 | 同一隔离数据库、Redis 和 S3 卷由原 Boot 3 JAR 切换到最终 Boot 4 JAR；完整业务 JSON、身份、密钥密文、附件字节和原登录会话保留；OpenAPI 登录接口继续可用 |
| Mac arm64 真实预览包 | 未修改 ASAR，实际 `isPackaged=true`，3 项检查／2 次真实启动通过；身份和完整需求记录重启后一致；窗口隐藏、离屏、未聚焦，意外 Electron 对话框为 0 |
| 原生运行时 | Java／Node 原生校验和 626 个运行时文件校验通过，H2 V42；`distributionReady=false` |
| MinIO 存储与备份客户端 | 5 项检查通过：实际 S3 上传／下载、嵌套对象键与 UTF-8 字节保留、镜像备份、删除后恢复、空桶／缺失桶行为；测试容器和卷已清除 |
| 网页网关 | 5 项检查通过：生产只读／tmpfs／能力限制下 UID 101、Vue 页面及 SPA 回退、JS 资源与安全响应头、POST／查询／正文／Cookie 转发、管理与指标路径隔离；上游为专用 HTTP fixture，真实业务后端另行完成前述回归 |
| 发行检查 | 实际执行完整 `release-gate.sh`，生成两份 Syft SBOM，Trivy 检查两份最终镜像；两者可修复 HIGH／CRITICAL 均为 0 |
| 配置与工具 | 开发／生产 Compose 配置校验、Helm lint／template、Shell 语法、相关 QA 脚本语法及最终 diff 检查通过 |

最终本地／包内业务 JAR SHA-256 为 `ae9efa1c234f258aa9b587ed0afe0e4d9f4783279f9eef1112fd07017bfe3623`；用于升级验证的上一实际本地运行时 JAR 为 `c7601a86f05f7fcf96fedcdc8e8d3a8e6ff37ec70bffc38848379710d556413f`。这两个摘要对应实际验证的二进制，不与 Docker 内独立 Maven 构建的 JAR 或上一原生 CI 的 JAR 摘要混同。

回归中发现的两处兼容性问题已修复：Boot 4 的 MockMvc 会话使用默认 Cookie 名称，现已显式保留原契约；独立 Controller 测试未加载 JSON mapper 配置，现使用与应用一致的 Jackson 2 转换器。MySQL 测试类共享 schema 导致其他类的 outbox 数据干扰“不得触发执行”的断言，现改为独立 schema。原有业务断言没有放宽、删除或跳过；登录测试另增加 HttpOnly／Secure 属性断言。

## 安全检查的边界

上一 main 的后端检查报告 11 项命中。本模块完成框架迁移后，扫描进一步发现默认 Jackson 3.1.5 的 5 项 HIGH 命中，已升级至 3.1.7；流程继续到此前未执行的网页扫描后，发现 19 项 Alpine HIGH 命中，也已修复。最终两份镜像均通过原有 `HIGH,CRITICAL`／`--ignore-unfixed` 规则，没有增加漏洞豁免或降低阈值。该结论限于此规则及本次数据库快照，不表示所有严重度或所有组件均无漏洞。

网页镜像实际修复的库包括 OpenSSL `libcrypto3`／`libssl3` 3.5.9-r0、libexpat 2.8.5-r0、libuuid 2.42.3-r1、PCRE2 10.49-r0、TIFF 4.7.2-r0。保留的脱敏摘要记录 Trivy 0.65.0、Syft 1.32.0、漏洞数据库更新时间和 SBOM 摘要／组件数；原始 SBOM 按阶段清理约定移除。

本轮验证环境为本机 Mac arm64 及独立 Linux arm64 Docker VM。新框架下 Intel Mac／Windows 原生 CI、签名／公证、默认目录实际安装和正式发行仍须后续单独验收；上一版本的三架构 CI 结果不能替代本轮版本。模块完成后仅保留当前运行时、复用 QA 脚本与脱敏摘要，阶段清理结果见 `.testhub/release-checks-module-20261014/final-verification.json`。用户已于 2026-10-15 审核通过并授权提交 main、执行新的远程验证，远程结果须以本轮新提交为准。
