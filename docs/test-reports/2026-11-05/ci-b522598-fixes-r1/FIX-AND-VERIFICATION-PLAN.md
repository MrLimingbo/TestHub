# b522598 CI修复与验证方案

先核对GitHub push运行41981648534及失败日志。Web已成功；后端只有一项UTC时区下的迁移兼容断言失败，安全门有3项HIGH。已用独占MySQL/Redis/S3在UTC复现相同失败（3方法，2通过/1失败），并确认两依赖来自MinIO8.6.0。

修复范围：

1. 测试断言不经JVM默认时区重解释TIMESTAMP日期；保留完整数据库日历字段断言，额外对比迁移前后精确Instant，证明记录未变。不得修改生产迁移或全局时区掩盖失败。
2. 在既有dependencyManagement锁定Guava33.8.0-jre和Snappy1.1.10.12；不新增直接依赖、不放宽Trivy HIGH/CRITICAL门槛、不增加忽略项。
3. 原UTC红测证据保留；修复后在UTC和Asia/Shanghai分别跑同一兼容类，再执行隔离H2和打包依赖检查。推送修复后核对新main的web/platform-api/release-gate三项真实结果。

上游依据：[Guava33.8.0](https://github.com/google/guava/releases/tag/v33.8.0)、[Guava33.7.2安全修复](https://github.com/google/guava/releases/tag/v33.7.2)、[Snappy1.1.10.9安全修复](https://github.com/xerial/snappy-java/releases/tag/v1.1.10.9)、[Snappy1.1.10.12](https://github.com/xerial/snappy-java/releases/tag/v1.1.10.12)、[Connector/J时间瞬间说明](https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html)。

本批CI安全与测试资产问题单独记录，不改写已审核3090项/109Bug历史统计。D26和12项环境补验继续跳过；升级关闭超时仍未确认，不调查或复验。不触碰共享数据库、用户工作区、发行环境或分支保护。
