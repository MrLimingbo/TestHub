# API 基础镜像剩余告警清单（2026-11-11）

> 这是 Expat 修复前的历史快照。后续已落地 Expat 2.10.0，完成实际库对照回归；8 条 HIGH 的最新处理结论与仍保留扫描告警的原因见 [HIGH 复核报告](security-high-risk-validation-2026-11-11.md)。下表保留当时版本，不作为当前安装清单。

这是最终 API 镜像于 2026-11-11 12:26（Asia/Shanghai）全级别复扫的脱敏摘要。镜像 OCI ID 为 `sha256:2086be535fab0458060ac841623b3e89a9294c1383542da084e51f6a36926196`（Linux arm64）。扫描器为 Trivy 0.65.0，漏洞库更新时间为 2026-11-10 06:59 UTC。未过滤未修复项；79 条包/CVE 记录归并为 45 个独立 CVE。当前扫描数据未给出 Debian 13 stable/security 可用修复版本。Java 依赖为 0 条，Web 全级别为 0 条，已修复的 zlib 与 OGNL 告警不在下表中。

严重程度为扫描器分类，不代表项目中已验证可利用。libuuid1 对应的 4 条 HIGH 涉及本镜像未携带的 mount/nsenter；libexpat1 的 4 条 HIGH 尚未消除。具体验证及修复见 [追加验收报告](security-infrastructure-validation-2026-11-10.md)。

| CVE（Debian 官方状态） | 扫描级别 | 命中包与版本 |
| --- | --- | --- |
| [CVE-2026-113351](https://security-tracker.debian.org/tracker/CVE-2026-113351) | HIGH | libexpat1 2.9.0-1~deb13u1 |
| [CVE-2026-115506](https://security-tracker.debian.org/tracker/CVE-2026-115506) | HIGH | libexpat1 2.9.0-1~deb13u1 |
| [CVE-2026-117610](https://security-tracker.debian.org/tracker/CVE-2026-117610) | HIGH | libexpat1 2.9.0-1~deb13u1 |
| [CVE-2026-118370](https://security-tracker.debian.org/tracker/CVE-2026-118370) | HIGH | libexpat1 2.9.0-1~deb13u1 |
| [CVE-2026-76642](https://security-tracker.debian.org/tracker/CVE-2026-76642) | HIGH | libuuid1 2.41.5-0+deb13u1 |
| [CVE-2026-78408](https://security-tracker.debian.org/tracker/CVE-2026-78408) | HIGH | libuuid1 2.41.5-0+deb13u1 |
| [CVE-2026-78409](https://security-tracker.debian.org/tracker/CVE-2026-78409) | HIGH | libuuid1 2.41.5-0+deb13u1 |
| [CVE-2026-78410](https://security-tracker.debian.org/tracker/CVE-2026-78410) | HIGH | libuuid1 2.41.5-0+deb13u1 |
| [CVE-2025-66382](https://security-tracker.debian.org/tracker/CVE-2025-66382) | MEDIUM | libexpat1 2.9.0-1~deb13u1 |
| [CVE-2026-102010](https://security-tracker.debian.org/tracker/CVE-2026-102010) | MEDIUM | gcc-14-base 14.2.0-19；libgcc-s1 14.2.0-19；libstdc++6 14.2.0-19 |
| [CVE-2026-114690](https://security-tracker.debian.org/tracker/CVE-2026-114690) | MEDIUM | gcc-14-base 14.2.0-19；libgcc-s1 14.2.0-19；libstdc++6 14.2.0-19 |
| [CVE-2026-114691](https://security-tracker.debian.org/tracker/CVE-2026-114691) | MEDIUM | gcc-14-base 14.2.0-19；libgcc-s1 14.2.0-19；libstdc++6 14.2.0-19 |
| [CVE-2026-115455](https://security-tracker.debian.org/tracker/CVE-2026-115455) | MEDIUM | gcc-14-base 14.2.0-19；libgcc-s1 14.2.0-19；libstdc++6 14.2.0-19 |
| [CVE-2026-18374](https://security-tracker.debian.org/tracker/CVE-2026-18374) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-19499](https://security-tracker.debian.org/tracker/CVE-2026-19499) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-19542](https://security-tracker.debian.org/tracker/CVE-2026-19542) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-3184](https://security-tracker.debian.org/tracker/CVE-2026-3184) | MEDIUM | libuuid1 2.41.5-0+deb13u1 |
| [CVE-2026-42250](https://security-tracker.debian.org/tracker/CVE-2026-42250) | MEDIUM | libbz2-1.0 1.0.8-6 |
| [CVE-2026-5435](https://security-tracker.debian.org/tracker/CVE-2026-5435) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-6238](https://security-tracker.debian.org/tracker/CVE-2026-6238) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-6368](https://security-tracker.debian.org/tracker/CVE-2026-6368) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-6791](https://security-tracker.debian.org/tracker/CVE-2026-6791) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-77117](https://security-tracker.debian.org/tracker/CVE-2026-77117) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-80489](https://security-tracker.debian.org/tracker/CVE-2026-80489) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-84243](https://security-tracker.debian.org/tracker/CVE-2026-84243) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-8674](https://security-tracker.debian.org/tracker/CVE-2026-8674) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-86805](https://security-tracker.debian.org/tracker/CVE-2026-86805) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-89092](https://security-tracker.debian.org/tracker/CVE-2026-89092) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-95512](https://security-tracker.debian.org/tracker/CVE-2026-95512) | MEDIUM | libfreetype6 2.13.3+dfsg-1+deb13u1 |
| [CVE-2026-95619](https://security-tracker.debian.org/tracker/CVE-2026-95619) | MEDIUM | gcc-14-base 14.2.0-19；libgcc-s1 14.2.0-19；libstdc++6 14.2.0-19 |
| [CVE-2026-95818](https://security-tracker.debian.org/tracker/CVE-2026-95818) | MEDIUM | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2010-4756](https://security-tracker.debian.org/tracker/CVE-2010-4756) | LOW | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2018-20796](https://security-tracker.debian.org/tracker/CVE-2018-20796) | LOW | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2019-1010022](https://security-tracker.debian.org/tracker/CVE-2019-1010022) | LOW | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2019-1010023](https://security-tracker.debian.org/tracker/CVE-2019-1010023) | LOW | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2019-1010024](https://security-tracker.debian.org/tracker/CVE-2019-1010024) | LOW | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2019-1010025](https://security-tracker.debian.org/tracker/CVE-2019-1010025) | LOW | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2019-9192](https://security-tracker.debian.org/tracker/CVE-2019-9192) | LOW | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2021-4214](https://security-tracker.debian.org/tracker/CVE-2021-4214) | LOW | libpng16-16t64 1.6.48-1+deb13u6 |
| [CVE-2022-0563](https://security-tracker.debian.org/tracker/CVE-2022-0563) | LOW | libuuid1 2.41.5-0+deb13u1 |
| [CVE-2025-29070](https://security-tracker.debian.org/tracker/CVE-2025-29070) | LOW | liblcms2-2 2.16-2+deb13u2 |
| [CVE-2026-115456](https://security-tracker.debian.org/tracker/CVE-2026-115456) | LOW | gcc-14-base 14.2.0-19；libgcc-s1 14.2.0-19；libstdc++6 14.2.0-19 |
| [CVE-2026-3713](https://security-tracker.debian.org/tracker/CVE-2026-3713) | LOW | libpng16-16t64 1.6.48-1+deb13u6 |
| [CVE-2026-97399](https://security-tracker.debian.org/tracker/CVE-2026-97399) | LOW | libc-bin 2.41-12+deb13u4；libc6 2.41-12+deb13u4 |
| [CVE-2026-124317](https://security-tracker.debian.org/tracker/CVE-2026-124317) | UNKNOWN | libexpat1 2.9.0-1~deb13u1 |
