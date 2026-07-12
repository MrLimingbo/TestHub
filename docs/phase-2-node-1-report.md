# 阶段 2 · 节点 2.1 验收报告

## 交付内容

- Java 21 + Spring Boot 3.5.3 后端依赖与 Maven Wrapper。
- MySQL 8.4、Redis 7.4、MinIO 的 Docker Compose 开发环境。
- Flyway V1 平台基础迁移，建立用户、组织、项目、成员、令牌、参数、配置、审计、通知模板、执行任务和文件对象表。
- Redis 会话与 MinIO 配置入口，为后续节点提供基础设施。

## 实际验证

- MySQL 版本：8.4.10；事务隔离级别：READ-COMMITTED。
- Redis：健康，AOF 已开启，最近一次重写状态为 `ok`。
- MinIO：live/ready 健康检查通过。
- Flyway：V1 校验及迁移成功，数据库共创建 12 张平台表。
- Java 21 容器内 Spring Boot 上下文测试通过。

## 备注

Flyway 11.7.2 对 MySQL 8.4 输出“尚未完成官方测试”的升级建议，但迁移与重复校验均成功。后续升级 Flyway 时必须先在副本数据库执行兼容验证。
